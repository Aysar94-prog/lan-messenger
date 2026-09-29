using System.Collections.Concurrent;
using System.Runtime.InteropServices;
namespace LanMessenger;

// Voice Messages (Phase 2 / Windows), W03: a reusable waveIn capture adapter. This is the only
// file allowed to touch the native audio API; everything it produces outward is a
// VoicePcmFrame or an explicit VoicePcmEvent (see VoiceMessages.cs / pcm-contract.md
// "Forbidden dependencies" — platform adapters translate device callbacks into fragments and
// events without leaking device types inward).
//
// Design notes (matches the plan's W03 acceptance criteria and its "Windows callbacks
// deadlock or outlive managed data" risk):
// - All native waveIn* calls are serialized under `nativeLock`.
// - The callback delegate is rooted as an instance field so it cannot be collected while
//   winmm.dll still holds a function pointer to it.
// - The native callback itself does the minimum possible (capture the header pointer and hand
//   it to a single dedicated processing thread via a FIFO queue — see pendingBuffers below for
//   why this must be one dedicated thread, not the thread pool) rather than doing any
//   processing, buffer requeueing, or taking `nativeLock` on the callback thread — avoids the
//   classic MM callback deadlock where the callback blocks on a lock the stopping thread
//   already holds while inside waveInStop/waveInReset.
// - Start()/Stop()/Dispose() are idempotent: calling any of them more than once, or Stop()
//   before Start(), is a safe no-op rather than a crash.
// - Buffers are native (unmanaged) memory, never pinned managed arrays, so there is nothing
//   for the GC to worry about and nothing that can be use-after-freed by a stray callback:
//   every buffer is unprepared and freed only after the handle itself is closed.
public sealed class VoiceRecorder : IDisposable
{
    public event Action<VoicePcmFrame>? FrameReady;
    public event Action? DeviceFailed;

    const int BufferBytes=3200; // 100 ms at the fixed 32000 bytes/s contract byte rate.
    const int BufferCount=4;

    readonly object nativeLock=new();
    readonly VoicePcmAssembler assembler=new();
    WaveInProc? callback; // rooted: must outlive every native call that can invoke it.
    IntPtr handle=IntPtr.Zero;
    readonly List<(IntPtr header,IntPtr data)> buffers=new();
    bool started,stopped,failed;

    // winmm invokes NativeCallback once per buffer, strictly in the order buffers actually
    // filled -- but dispatching each one independently via ThreadPool.QueueUserWorkItem (the
    // original design here) throws that ordering away: two queued work items can run on
    // different pool threads and race for nativeLock in either order. assembler.Push(pcm) is a
    // strictly sequential PCM stream, so a buffer processed out of order scrambles the recorded
    // audio -- this was the real cause of a real "recording sounds choppy" bug report. Routing
    // every buffer through this single dedicated thread, FIFO, guarantees HandleData always runs
    // in the same order the audio was actually captured.
    readonly BlockingCollection<IntPtr> pendingBuffers=new();
    Thread? processingThread;

    public bool Recording{get{lock(nativeLock)return started&&!stopped&&!failed;}}

    // Opens the device in the fixed contract format (16 kHz mono 16-bit PCM) and begins
    // capture. Throws IOException with a clear message on permission/initialization failure,
    // per A/W03-04's "permission and initialization failures are clear" requirement.
    public void Start()
    {
        lock(nativeLock){
            if(started)throw new InvalidOperationException("Recorder already started.");
            var format=new WAVEFORMATEX{wFormatTag=1,nChannels=1,nSamplesPerSec=16000,nAvgBytesPerSec=32000,nBlockAlign=2,wBitsPerSample=16,cbSize=0};
            callback=NativeCallback;
            int rc=WaveIn.waveInOpen(out handle,unchecked((uint)-1),ref format,callback,IntPtr.Zero,WaveIn.CALLBACK_FUNCTION);
            if(rc!=WaveIn.MMSYSERR_NOERROR){handle=IntPtr.Zero;throw new IOException(WaveIn.ErrorMessage(rc,"open the recording device"));}
            try{
                for(int i=0;i<BufferCount;i++)AllocateAndQueueBufferLocked();
                rc=WaveIn.waveInStart(handle);
                if(rc!=WaveIn.MMSYSERR_NOERROR)throw new IOException(WaveIn.ErrorMessage(rc,"start recording"));
            }catch{CloseNativeLocked();throw;}
            started=true;
        }
        processingThread=new Thread(ProcessingLoop){IsBackground=true,Name="voice-recorder"};
        processingThread.Start();
    }

    // The single consumer of pendingBuffers -- see that field's comment for why this must be
    // exactly one dedicated thread rather than the thread pool.
    void ProcessingLoop()
    {
        foreach(var headerPtr in pendingBuffers.GetConsumingEnumerable())HandleData(headerPtr);
    }

    void AllocateAndQueueBufferLocked()
    {
        var data=Marshal.AllocHGlobal(BufferBytes);
        var header=Marshal.AllocHGlobal(Marshal.SizeOf<WAVEHDR>());
        var hdr=new WAVEHDR{lpData=data,dwBufferLength=(uint)BufferBytes,dwBytesRecorded=0,dwUser=IntPtr.Zero,dwFlags=0,dwLoops=0,lpNext=IntPtr.Zero,reserved=IntPtr.Zero};
        Marshal.StructureToPtr(hdr,header,false);
        buffers.Add((header,data));
        int rc=WaveIn.waveInPrepareHeader(handle,header,(uint)Marshal.SizeOf<WAVEHDR>());
        if(rc!=WaveIn.MMSYSERR_NOERROR)throw new IOException(WaveIn.ErrorMessage(rc,"prepare a recording buffer"));
        rc=WaveIn.waveInAddBuffer(handle,header,(uint)Marshal.SizeOf<WAVEHDR>());
        if(rc!=WaveIn.MMSYSERR_NOERROR)throw new IOException(WaveIn.ErrorMessage(rc,"queue a recording buffer"));
    }

    // Runs on winmm's own callback thread. Does the absolute minimum: recognizes a data-ready
    // message and hands the header pointer to the thread pool. Never touches `nativeLock`,
    // never calls back into winmm, never allocates beyond the closure — safe to run at any
    // point, including while Stop()/Dispose() is concurrently tearing the device down.
    void NativeCallback(IntPtr hwi,uint uMsg,IntPtr dwInstance,IntPtr dwParam1,IntPtr dwParam2)
    {
        if(uMsg!=WaveIn.MM_WIM_DATA)return;
        // Enqueue, don't process here (still the "minimum possible work" rule the class header
        // describes) -- but unlike a raw ThreadPool hand-off, adding to this queue preserves the
        // exact order winmm called us in, for the single ProcessingLoop consumer to honor.
        try{pendingBuffers.Add(dwParam1);}catch(InvalidOperationException){} // CompleteAdding already called; a very last in-flight callback racing teardown -- safe to drop.
    }

    void HandleData(IntPtr headerPtr)
    {
        List<VoicePcmFrame> frames;
        lock(nativeLock){
            // `handle==IntPtr.Zero` (native buffer memory already freed by CloseNativeLocked) is
            // the only condition that makes touching headerPtr unsafe. `stopped` alone is NOT
            // one: waveInStop/waveInReset synchronously flush every still-pending buffer back to
            // us via NativeCallback before Stop() proceeds to drain this queue (see Stop()'s
            // DrainAndClose call, which deliberately waits for this very processing loop to empty
            // the queue before it ever touches native memory) -- so the last one or two buffers
            // of a recording normally arrive with `stopped` already true, and must still be
            // pushed into the assembler or the tail of every recording gets silently truncated.
            if(failed||handle==IntPtr.Zero)return;
            var hdr=Marshal.PtrToStructure<WAVEHDR>(headerPtr);
            if(hdr.dwBytesRecorded==0){if(!stopped)RequeueLocked(headerPtr);return;}
            var pcm=new byte[hdr.dwBytesRecorded];
            Marshal.Copy(hdr.lpData,pcm,0,(int)hdr.dwBytesRecorded);
            try{frames=assembler.Push(pcm);}
            catch(InvalidOperationException){FailLocked();return;} // e.g. the 9,600,000-byte cap.
            if(assembler.TerminallyFailed){FailLocked();return;}
            // Once stopped, the device is being torn down regardless -- requeuing a buffer back
            // to it would be pointless, and calling waveInAddBuffer on an already-reset handle
            // can itself return an error, which would incorrectly flip this into a failure state
            // for what was actually a perfectly normal stop.
            if(!stopped)RequeueLocked(headerPtr);
        }
        foreach(var frame in frames)FrameReady?.Invoke(frame);
    }

    void RequeueLocked(IntPtr headerPtr)
    {
        int rc=WaveIn.waveInAddBuffer(handle,headerPtr,(uint)Marshal.SizeOf<WAVEHDR>());
        if(rc!=WaveIn.MMSYSERR_NOERROR)FailLocked();
    }

    void FailLocked()
    {
        if(failed)return;
        failed=true;
        assembler.DeviceFailure();
        var handler=DeviceFailed;
        ThreadPool.QueueUserWorkItem(_=>handler?.Invoke());
    }

    // Normal Stop: idempotent. Safe to call from any thread, including more than once, before
    // Start(), or after a device failure already tore things down.
    public (VoicePcmFrame? finalFrame,VoicePcmEvent evt)? Stop()
    {
        lock(nativeLock){
            if(!started||stopped)return null;
            stopped=true;
            // waveInStop+waveInReset synchronously flushes every still-queued buffer back to us
            // through NativeCallback (which needs no lock, so this still succeeds even though we
            // hold nativeLock here) before either call returns.
            if(!failed&&handle!=IntPtr.Zero){WaveIn.waveInStop(handle);WaveIn.waveInReset(handle);}
        }
        // Outside the lock: DrainAndClose() waits for ProcessingLoop to actually push every one
        // of those just-flushed final buffers into the assembler (HandleData needs nativeLock
        // too) before it frees any native buffer memory. Doing this inline, still holding
        // nativeLock, would deadlock ProcessingLoop against this very thread.
        DrainAndClose();
        lock(nativeLock){
            if(failed)return (null,VoicePcmEvent.DeviceFailure);
            try{return assembler.End();}
            catch(InvalidOperationException){return (null,VoicePcmEvent.DeviceFailure);}
        }
    }

    // Shared by Stop() and Dispose(); safe to call from either, and safe if called by both (each
    // of CompleteAdding/Join/CloseNativeLocked is independently idempotent).
    void DrainAndClose()
    {
        pendingBuffers.CompleteAdding();
        processingThread?.Join();
        lock(nativeLock)CloseNativeLocked();
    }

    void CloseNativeLocked()
    {
        if(handle!=IntPtr.Zero){
            foreach(var (header,_) in buffers)try{WaveIn.waveInUnprepareHeader(handle,header,(uint)Marshal.SizeOf<WAVEHDR>());}catch{}
            try{WaveIn.waveInClose(handle);}catch{}
            handle=IntPtr.Zero;
        }
        foreach(var (header,data) in buffers){Marshal.FreeHGlobal(header);Marshal.FreeHGlobal(data);}
        buffers.Clear();
    }

    bool disposed;

    // Safe even if Stop() was never called (e.g. a failure/cancel path that disposes directly):
    // stops the device first if that hasn't happened yet, then reuses the exact same
    // drain-before-close sequence Stop() uses. Also safe to call more than once itself --
    // DrainAndClose()'s own steps tolerate a repeat call, but pendingBuffers.Dispose() does not
    // (BlockingCollection throws ObjectDisposedException on a second Dispose()), so this needs
    // its own explicit guard rather than relying on DrainAndClose()'s idempotency alone.
    public void Dispose()
    {
        lock(nativeLock){
            if(disposed)return;
            disposed=true;
            if(!stopped&&started){stopped=true;if(!failed&&handle!=IntPtr.Zero){try{WaveIn.waveInStop(handle);WaveIn.waveInReset(handle);}catch{}}}
        }
        if(started)DrainAndClose();
        pendingBuffers.Dispose();
    }
}

delegate void WaveInProc(IntPtr hwi,uint uMsg,IntPtr dwInstance,IntPtr dwParam1,IntPtr dwParam2);

[StructLayout(LayoutKind.Sequential)]
struct WAVEFORMATEX
{
    public ushort wFormatTag;
    public ushort nChannels;
    public uint nSamplesPerSec;
    public uint nAvgBytesPerSec;
    public ushort nBlockAlign;
    public ushort wBitsPerSample;
    public ushort cbSize;
}

[StructLayout(LayoutKind.Sequential)]
struct WAVEHDR
{
    public IntPtr lpData;
    public uint dwBufferLength;
    public uint dwBytesRecorded;
    public IntPtr dwUser;
    public uint dwFlags;
    public uint dwLoops;
    public IntPtr lpNext;
    public IntPtr reserved;
}

static class WaveIn
{
    public const uint CALLBACK_FUNCTION=0x00030000;
    public const uint MM_WIM_DATA=0x3C0;
    public const int MMSYSERR_NOERROR=0;
    public const int MMSYSERR_ALLOCATED=4;
    public const int MMSYSERR_BADDEVICEID=2;
    public const int MMSYSERR_NODRIVER=6;
    public const int WAVERR_UNPREPARED=32+2;

    public static string ErrorMessage(int rc,string action)=>rc switch{
        MMSYSERR_BADDEVICEID=>$"Could not {action}: no such recording device.",
        MMSYSERR_ALLOCATED=>$"Could not {action}: the microphone is already in use.",
        MMSYSERR_NODRIVER=>$"Could not {action}: no microphone driver is installed.",
        _=>$"Could not {action} (mmresult {rc}).",
    };

    [DllImport("winmm.dll")]
    public static extern int waveInOpen(out IntPtr hWaveIn,uint uDeviceID,ref WAVEFORMATEX lpFormat,WaveInProc dwCallback,IntPtr dwInstance,uint dwFlags);
    [DllImport("winmm.dll")]
    public static extern int waveInPrepareHeader(IntPtr hWaveIn,IntPtr lpWaveInHdr,uint uSize);
    [DllImport("winmm.dll")]
    public static extern int waveInUnprepareHeader(IntPtr hWaveIn,IntPtr lpWaveInHdr,uint uSize);
    [DllImport("winmm.dll")]
    public static extern int waveInAddBuffer(IntPtr hWaveIn,IntPtr lpWaveInHdr,uint uSize);
    [DllImport("winmm.dll")]
    public static extern int waveInStart(IntPtr hWaveIn);
    [DllImport("winmm.dll")]
    public static extern int waveInStop(IntPtr hWaveIn);
    [DllImport("winmm.dll")]
    public static extern int waveInReset(IntPtr hWaveIn);
    [DllImport("winmm.dll")]
    public static extern int waveInClose(IntPtr hWaveIn);
}
