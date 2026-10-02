using System.Runtime.InteropServices;
namespace LanMessenger;

// Continuous (uncapped) 16 kHz mono 16-bit PCM capture/playback for voice calls, in fixed 20 ms
// frames (640 bytes / 320 samples) to match standard WebRTC RTP packetization. Reuses the exact
// proven winmm P/Invoke safety pattern VoiceRecorder.cs/VoicePlayer.cs already established
// (serialized native calls, a rooted callback delegate, unmanaged buffers, a minimal-work native
// callback, idempotent Start/Stop/Dispose, explicit per-slot Busy tracking rather than inferring
// free slots from a bare counter — see VoicePlayer.cs's own comment on the real bug that pattern
// caused) — deliberately NOT the voice-message classes themselves, since those are capped at
// 9.6 MB / 300 s for a message draft and assemble into a single in-memory WAV, neither of which
// fits an open-ended call. WaveIn/WaveOut/WAVEFORMATEX/WAVEHDR are the same internal types
// VoiceRecorder.cs/VoicePlayer.cs already declare (assembly-internal, so reusable here).

public sealed class CallAudioCapture : IDisposable
{
    public const int FrameBytes = 640; // 20 ms @ 16 kHz mono 16-bit
    public event Action<byte[]>? FrameReady;
    public event Action? DeviceFailed;

    const int BufferCount = 6;
    readonly object nativeLock = new();
    WaveInProc? callback;
    IntPtr handle = IntPtr.Zero;
    readonly List<(IntPtr header, IntPtr data)> buffers = new();
    bool started, stopped, failed;
    readonly System.Collections.Concurrent.BlockingCollection<IntPtr> pending = new();
    Thread? processingThread;

    public void Start()
    {
        lock (nativeLock)
        {
            if (started) return;
            var format = new WAVEFORMATEX { wFormatTag = 1, nChannels = 1, nSamplesPerSec = 16000, nAvgBytesPerSec = 32000, nBlockAlign = 2, wBitsPerSample = 16, cbSize = 0 };
            callback = NativeCallback;
            int rc = CallWaveIn.waveInOpen(out handle, unchecked((uint)-1), ref format, callback, IntPtr.Zero, CallWaveIn.CALLBACK_FUNCTION);
            if (rc != CallWaveIn.MMSYSERR_NOERROR) { handle = IntPtr.Zero; throw new IOException(CallWaveIn.ErrorMessage(rc, "open the microphone")); }
            try
            {
                for (int i = 0; i < BufferCount; i++) AllocateAndQueueLocked();
                rc = CallWaveIn.waveInStart(handle);
                if (rc != CallWaveIn.MMSYSERR_NOERROR) throw new IOException(CallWaveIn.ErrorMessage(rc, "start recording"));
            }
            catch { CloseNativeLocked(); throw; }
            started = true;
        }
        processingThread = new Thread(() => { foreach (var h in pending.GetConsumingEnumerable()) HandleData(h); }) { IsBackground = true, Name = "call-audio-capture" };
        processingThread.Start();
    }

    void AllocateAndQueueLocked()
    {
        var data = Marshal.AllocHGlobal(FrameBytes);
        var header = Marshal.AllocHGlobal(Marshal.SizeOf<WAVEHDR>());
        var hdr = new WAVEHDR { lpData = data, dwBufferLength = (uint)FrameBytes, dwBytesRecorded = 0, dwUser = IntPtr.Zero, dwFlags = 0, dwLoops = 0, lpNext = IntPtr.Zero, reserved = IntPtr.Zero };
        Marshal.StructureToPtr(hdr, header, false);
        buffers.Add((header, data));
        int rc = CallWaveIn.waveInPrepareHeader(handle, header, (uint)Marshal.SizeOf<WAVEHDR>());
        if (rc != CallWaveIn.MMSYSERR_NOERROR) throw new IOException(CallWaveIn.ErrorMessage(rc, "prepare a recording buffer"));
        rc = CallWaveIn.waveInAddBuffer(handle, header, (uint)Marshal.SizeOf<WAVEHDR>());
        if (rc != CallWaveIn.MMSYSERR_NOERROR) throw new IOException(CallWaveIn.ErrorMessage(rc, "queue a recording buffer"));
    }

    void NativeCallback(IntPtr hwi, uint uMsg, IntPtr dwInstance, IntPtr dwParam1, IntPtr dwParam2)
    {
        if (uMsg != CallWaveIn.MM_WIM_DATA) return;
        try { pending.Add(dwParam1); } catch (InvalidOperationException) { }
    }

    void HandleData(IntPtr headerPtr)
    {
        byte[]? pcm = null;
        lock (nativeLock)
        {
            if (failed || handle == IntPtr.Zero) return;
            var hdr = Marshal.PtrToStructure<WAVEHDR>(headerPtr);
            if (hdr.dwBytesRecorded > 0) { pcm = new byte[hdr.dwBytesRecorded]; Marshal.Copy(hdr.lpData, pcm, 0, (int)hdr.dwBytesRecorded); }
            if (!stopped)
            {
                int rc = CallWaveIn.waveInAddBuffer(handle, headerPtr, (uint)Marshal.SizeOf<WAVEHDR>());
                if (rc != CallWaveIn.MMSYSERR_NOERROR) { FailLocked(); return; }
            }
        }
        if (pcm != null) FrameReady?.Invoke(pcm);
    }

    void FailLocked() { if (failed) return; failed = true; var h = DeviceFailed; ThreadPool.QueueUserWorkItem(_ => h?.Invoke()); }

    public void Stop()
    {
        lock (nativeLock)
        {
            if (!started || stopped) return;
            stopped = true;
            if (!failed && handle != IntPtr.Zero) { CallWaveIn.waveInStop(handle); CallWaveIn.waveInReset(handle); }
        }
        pending.CompleteAdding();
        processingThread?.Join();
        lock (nativeLock) CloseNativeLocked();
    }

    void CloseNativeLocked()
    {
        if (handle != IntPtr.Zero)
        {
            foreach (var (header, _) in buffers) try { CallWaveIn.waveInUnprepareHeader(handle, header, (uint)Marshal.SizeOf<WAVEHDR>()); } catch { }
            try { CallWaveIn.waveInClose(handle); } catch { }
            handle = IntPtr.Zero;
        }
        foreach (var (header, data) in buffers) { Marshal.FreeHGlobal(header); Marshal.FreeHGlobal(data); }
        buffers.Clear();
    }

    bool disposed;
    public void Dispose()
    {
        lock (nativeLock) { if (disposed) return; disposed = true; if (!stopped && started) { stopped = true; if (!failed && handle != IntPtr.Zero) try { CallWaveIn.waveInStop(handle); CallWaveIn.waveInReset(handle); } catch { } } }
        if (started) { pending.CompleteAdding(); processingThread?.Join(); lock (nativeLock) CloseNativeLocked(); }
    }
}

public sealed class CallAudioPlayback : IDisposable
{
    public event Action? DeviceFailed;
    sealed class Slot { public IntPtr Header, Data; public bool Busy; }

    const int BufferCount = 6;
    readonly object nativeLock = new();
    WaveOutProc? callback;
    IntPtr handle = IntPtr.Zero;
    readonly List<Slot> buffers = new();
    readonly Queue<byte[]> queued = new();
    bool opened, closed;

    public void Start()
    {
        lock (nativeLock)
        {
            if (opened || closed) return;
            var format = new WAVEFORMATEX { wFormatTag = 1, nChannels = 1, nSamplesPerSec = 16000, nAvgBytesPerSec = 32000, nBlockAlign = 2, wBitsPerSample = 16, cbSize = 0 };
            callback = NativeCallback;
            int rc = CallWaveOut.waveOutOpen(out handle, unchecked((uint)-1), ref format, callback, IntPtr.Zero, CallWaveOut.CALLBACK_FUNCTION);
            if (rc != CallWaveOut.MMSYSERR_NOERROR) { handle = IntPtr.Zero; throw new IOException(CallWaveOut.ErrorMessage(rc, "open the speaker/headset")); }
            for (int i = 0; i < BufferCount; i++)
            {
                var data = Marshal.AllocHGlobal(CallAudioCapture.FrameBytes);
                var header = Marshal.AllocHGlobal(Marshal.SizeOf<WAVEHDR>());
                buffers.Add(new Slot { Header = header, Data = data });
            }
            opened = true;
        }
    }

    // Enqueues one decoded 20 ms PCM frame for playback; drops the oldest queued frame if the
    // decoder is running ahead of the device (bounded backlog — a VoIP call must never build
    // unbounded playback latency).
    public void Enqueue(byte[] pcm)
    {
        lock (nativeLock)
        {
            if (!opened || closed) return;
            queued.Enqueue(pcm);
            while (queued.Count > BufferCount * 2) queued.Dequeue();
            FillLocked();
        }
    }

    void FillLocked()
    {
        if (handle == IntPtr.Zero) return;
        foreach (var slot in buffers)
        {
            if (slot.Busy) continue;
            if (queued.Count == 0) break;
            var pcm = queued.Dequeue();
            int take = Math.Min(pcm.Length, CallAudioCapture.FrameBytes);
            Marshal.Copy(pcm, 0, slot.Data, take);
            var hdr = new WAVEHDR { lpData = slot.Data, dwBufferLength = (uint)take, dwBytesRecorded = 0, dwUser = IntPtr.Zero, dwFlags = 0, dwLoops = 0, lpNext = IntPtr.Zero, reserved = IntPtr.Zero };
            Marshal.StructureToPtr(hdr, slot.Header, false);
            int rc = CallWaveOut.waveOutPrepareHeader(handle, slot.Header, (uint)Marshal.SizeOf<WAVEHDR>());
            if (rc != CallWaveOut.MMSYSERR_NOERROR) { FailLocked(); return; }
            rc = CallWaveOut.waveOutWrite(handle, slot.Header, (uint)Marshal.SizeOf<WAVEHDR>());
            if (rc != CallWaveOut.MMSYSERR_NOERROR) { CallWaveOut.waveOutUnprepareHeader(handle, slot.Header, (uint)Marshal.SizeOf<WAVEHDR>()); FailLocked(); return; }
            slot.Busy = true;
        }
    }

    void NativeCallback(IntPtr hwo, uint uMsg, IntPtr dwInstance, IntPtr dwParam1, IntPtr dwParam2)
    {
        if (uMsg != CallWaveOut.MM_WOM_DONE) return;
        var headerPtr = dwParam1;
        ThreadPool.QueueUserWorkItem(_ => HandleDone(headerPtr));
    }

    void HandleDone(IntPtr headerPtr)
    {
        lock (nativeLock)
        {
            if (closed || handle == IntPtr.Zero) return;
            try { CallWaveOut.waveOutUnprepareHeader(handle, headerPtr, (uint)Marshal.SizeOf<WAVEHDR>()); } catch { }
            foreach (var slot in buffers) if (slot.Header == headerPtr) { slot.Busy = false; break; }
            FillLocked();
        }
    }

    void FailLocked() { var h = DeviceFailed; ThreadPool.QueueUserWorkItem(_ => h?.Invoke()); }

    public void Dispose()
    {
        lock (nativeLock)
        {
            if (closed) return;
            closed = true;
            if (handle != IntPtr.Zero)
            {
                CallWaveOut.waveOutReset(handle);
                foreach (var slot in buffers) try { CallWaveOut.waveOutUnprepareHeader(handle, slot.Header, (uint)Marshal.SizeOf<WAVEHDR>()); } catch { }
                try { CallWaveOut.waveOutClose(handle); } catch { }
                handle = IntPtr.Zero;
            }
            foreach (var slot in buffers) { Marshal.FreeHGlobal(slot.Header); Marshal.FreeHGlobal(slot.Data); }
            buffers.Clear();
            queued.Clear();
        }
    }
}

static class CallWaveIn
{
    public const uint CALLBACK_FUNCTION = 0x00030000;
    public const uint MM_WIM_DATA = 0x3C0;
    public const int MMSYSERR_NOERROR = 0;
    public const int MMSYSERR_ALLOCATED = 4;
    public const int MMSYSERR_BADDEVICEID = 2;
    public const int MMSYSERR_NODRIVER = 6;
    public static string ErrorMessage(int rc, string action) => rc switch
    {
        MMSYSERR_BADDEVICEID => $"Could not {action}: no such recording device.",
        MMSYSERR_ALLOCATED => $"Could not {action}: the microphone is already in use.",
        MMSYSERR_NODRIVER => $"Could not {action}: no microphone driver is installed.",
        _ => $"Could not {action} (mmresult {rc}).",
    };
    [DllImport("winmm.dll")] public static extern int waveInOpen(out IntPtr hWaveIn, uint uDeviceID, ref WAVEFORMATEX lpFormat, WaveInProc dwCallback, IntPtr dwInstance, uint dwFlags);
    [DllImport("winmm.dll")] public static extern int waveInPrepareHeader(IntPtr hWaveIn, IntPtr lpWaveInHdr, uint uSize);
    [DllImport("winmm.dll")] public static extern int waveInUnprepareHeader(IntPtr hWaveIn, IntPtr lpWaveInHdr, uint uSize);
    [DllImport("winmm.dll")] public static extern int waveInAddBuffer(IntPtr hWaveIn, IntPtr lpWaveInHdr, uint uSize);
    [DllImport("winmm.dll")] public static extern int waveInStart(IntPtr hWaveIn);
    [DllImport("winmm.dll")] public static extern int waveInStop(IntPtr hWaveIn);
    [DllImport("winmm.dll")] public static extern int waveInReset(IntPtr hWaveIn);
    [DllImport("winmm.dll")] public static extern int waveInClose(IntPtr hWaveIn);
}

static class CallWaveOut
{
    public const uint CALLBACK_FUNCTION = 0x00030000;
    public const uint MM_WOM_DONE = 0x3BD;
    public const int MMSYSERR_NOERROR = 0;
    public const int MMSYSERR_ALLOCATED = 4;
    public const int MMSYSERR_BADDEVICEID = 2;
    public const int MMSYSERR_NODRIVER = 6;
    public static string ErrorMessage(int rc, string action) => rc switch
    {
        MMSYSERR_BADDEVICEID => $"Could not {action}: no such playback device.",
        MMSYSERR_ALLOCATED => $"Could not {action}: the output device is already in use.",
        MMSYSERR_NODRIVER => $"Could not {action}: no playback driver is installed.",
        _ => $"Could not {action} (mmresult {rc}).",
    };
    [DllImport("winmm.dll")] public static extern int waveOutOpen(out IntPtr hWaveOut, uint uDeviceID, ref WAVEFORMATEX lpFormat, WaveOutProc dwCallback, IntPtr dwInstance, uint dwFlags);
    [DllImport("winmm.dll")] public static extern int waveOutPrepareHeader(IntPtr hWaveOut, IntPtr lpWaveOutHdr, uint uSize);
    [DllImport("winmm.dll")] public static extern int waveOutUnprepareHeader(IntPtr hWaveOut, IntPtr lpWaveOutHdr, uint uSize);
    [DllImport("winmm.dll")] public static extern int waveOutWrite(IntPtr hWaveOut, IntPtr lpWaveOutHdr, uint uSize);
    [DllImport("winmm.dll")] public static extern int waveOutReset(IntPtr hWaveOut);
    [DllImport("winmm.dll")] public static extern int waveOutClose(IntPtr hWaveOut);
}
