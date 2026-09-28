using System.Runtime.InteropServices;
namespace LanMessenger;

// Voice Messages (Phase 2 / Windows), W08: a reusable waveOut inline player. Mirrors
// VoiceRecorder.cs's safety construction exactly (serialized native control, a rooted
// callback delegate, unmanaged buffers, minimal-work native callback that only queues a
// thread-pool work item, idempotent Stop/Dispose) — see that file's design notes.
//
// Additional concern specific to playback: a Seek must invalidate any output already queued
// from the old position (contract.md seek step 5) without a late MM_WOM_DONE callback for one
// of those stale buffers corrupting the new position. Every buffer written carries the epoch
// it was written under (via WAVEHDR.dwUser); Seek()/Stop() bump the epoch, and the callback
// path discards completions whose epoch no longer matches — "stale buffers cannot play after
// seeking" (W08 acceptance).
//
// The whole decrypted WAV lives in one bounded (≤9.6 MB) managed byte[] for the lifetime of
// the player; no plaintext playback file is ever written to disk (W08 acceptance).
public sealed class VoicePlayer : IDisposable
{
    public event Action? Completed;
    public event Action? DeviceFailed;

    const int BufferBytes=3200; // 100 ms at the fixed 32000 bytes/s contract byte rate.
    const int BufferCount=4;

    readonly object nativeLock=new();
    readonly byte[] wav;
    readonly VoiceWavInfo info;
    WaveOutProc? callback; // rooted: must outlive every native call that can invoke it.
    IntPtr handle=IntPtr.Zero;
    readonly List<(IntPtr header,IntPtr data)> buffers=new();
    long playPosition;   // absolute byte offset into `wav`, always within [info.DataOffset, dataEnd].
    long epoch;          // bumped on every Seek/Stop; a completion callback for an older epoch is discarded.
    int buffersInFlight;
    bool playing,closed;
    long DataEnd=>info.DataOffset+info.DataBytes;

    public VoicePlayer(byte[] wav,VoiceWavInfo info)
    {
        this.wav=wav;this.info=info;playPosition=info.DataOffset;
    }

    public bool Playing{get{lock(nativeLock)return playing&&!closed;}}
    public long PositionNs{get{lock(nativeLock)return (playPosition-info.DataOffset)/VoiceWav.BlockAlign*62500;}}

    public void Play()
    {
        lock(nativeLock){
            if(closed)throw new InvalidOperationException("Player already disposed.");
            if(handle==IntPtr.Zero)OpenLocked();
            if(playPosition>=DataEnd){playPosition=info.DataOffset;epoch++;}
            playing=true;
            FillQueueLocked();
        }
    }

    public void Pause(){lock(nativeLock){if(handle!=IntPtr.Zero)WaveOut.waveOutPause(handle);playing=false;}}

    public void Resume()
    {
        lock(nativeLock){
            if(handle==IntPtr.Zero||closed)return;
            WaveOut.waveOutRestart(handle);playing=true;FillQueueLocked();
        }
    }

    // Contract.md's seven-step seek procedure, steps 5-7: invalidates output queued from the
    // old position (bumps epoch + waveOutReset), resets playback sequence/timestamp state
    // (playPosition), and reports the effective aligned position back to the caller. Steps 1-4
    // (clamp/convert/resolve/align) are VoiceSeek.Resolve's job (W01), reused here unchanged.
    public VoiceSeekResult Seek(long requestedMs)
    {
        lock(nativeLock){
            var result=VoiceSeek.Resolve(info,requestedMs);
            epoch++;buffersInFlight=0;
            if(handle!=IntPtr.Zero)WaveOut.waveOutReset(handle);
            playPosition=result.AlignedByte;
            if(result.State=="completed")playing=false;
            else if(playing)FillQueueLocked();
            return result;
        }
    }

    void OpenLocked()
    {
        var format=new WAVEFORMATEX{wFormatTag=1,nChannels=1,nSamplesPerSec=16000,nAvgBytesPerSec=32000,nBlockAlign=2,wBitsPerSample=16,cbSize=0};
        callback=NativeCallback;
        int rc=WaveOut.waveOutOpen(out handle,unchecked((uint)-1),ref format,callback,IntPtr.Zero,WaveOut.CALLBACK_FUNCTION);
        if(rc!=WaveOut.MMSYSERR_NOERROR){handle=IntPtr.Zero;throw new IOException(WaveOut.ErrorMessage(rc,"open the playback device"));}
        for(int i=0;i<BufferCount;i++){
            var data=Marshal.AllocHGlobal(BufferBytes);
            var header=Marshal.AllocHGlobal(Marshal.SizeOf<WAVEHDR>());
            buffers.Add((header,data));
        }
    }

    void FillQueueLocked()
    {
        if(handle==IntPtr.Zero||!playing)return;
        foreach(var (header,data) in buffers){
            if(buffersInFlight>=BufferCount||playPosition>=DataEnd)break;
            int take=(int)Math.Min(BufferBytes,DataEnd-playPosition);
            take-=take%VoiceWav.BlockAlign; // complete samples only, matching the contract's frame rule.
            if(take<=0)break;
            Marshal.Copy(wav,(int)playPosition,data,take);
            var hdr=new WAVEHDR{lpData=data,dwBufferLength=(uint)take,dwBytesRecorded=0,dwUser=(IntPtr)epoch,dwFlags=0,dwLoops=0,lpNext=IntPtr.Zero,reserved=IntPtr.Zero};
            Marshal.StructureToPtr(hdr,header,false);
            int rc=WaveOut.waveOutPrepareHeader(handle,header,(uint)Marshal.SizeOf<WAVEHDR>());
            if(rc!=WaveOut.MMSYSERR_NOERROR){FailLocked();return;}
            rc=WaveOut.waveOutWrite(handle,header,(uint)Marshal.SizeOf<WAVEHDR>());
            if(rc!=WaveOut.MMSYSERR_NOERROR){WaveOut.waveOutUnprepareHeader(handle,header,(uint)Marshal.SizeOf<WAVEHDR>());FailLocked();return;}
            playPosition+=take;buffersInFlight++;
        }
    }

    // Runs on winmm's own callback thread. Minimal work only, mirroring VoiceRecorder's
    // NativeCallback — never touches nativeLock, never calls back into winmm.
    void NativeCallback(IntPtr hwo,uint uMsg,IntPtr dwInstance,IntPtr dwParam1,IntPtr dwParam2)
    {
        if(uMsg!=WaveOut.MM_WOM_DONE)return;
        var headerPtr=dwParam1;
        ThreadPool.QueueUserWorkItem(_=>HandleDone(headerPtr));
    }

    void HandleDone(IntPtr headerPtr)
    {
        bool completed=false;
        lock(nativeLock){
            if(closed||handle==IntPtr.Zero)return;
            var hdr=Marshal.PtrToStructure<WAVEHDR>(headerPtr);
            WaveOut.waveOutUnprepareHeader(handle,headerPtr,(uint)Marshal.SizeOf<WAVEHDR>());
            if((long)hdr.dwUser!=epoch)return; // a stale buffer from a position we've since left — discard.
            buffersInFlight=Math.Max(0,buffersInFlight-1);
            if(playing)FillQueueLocked();
            if(playPosition>=DataEnd&&buffersInFlight==0){playing=false;completed=true;}
        }
        if(completed)Completed?.Invoke();
    }

    void FailLocked()
    {
        var handler=DeviceFailed;playing=false;
        ThreadPool.QueueUserWorkItem(_=>handler?.Invoke());
    }

    // Idempotent: safe to call more than once, before Play(), or after a device failure.
    public void Stop()
    {
        lock(nativeLock){
            if(closed)return;
            epoch++;playing=false;buffersInFlight=0;
            if(handle!=IntPtr.Zero){
                WaveOut.waveOutReset(handle);
                foreach(var (header,_) in buffers)try{WaveOut.waveOutUnprepareHeader(handle,header,(uint)Marshal.SizeOf<WAVEHDR>());}catch{}
                try{WaveOut.waveOutClose(handle);}catch{}
                handle=IntPtr.Zero;
            }
        }
    }

    public void Dispose()
    {
        lock(nativeLock){
            if(closed)return;
            closed=true;
            if(handle!=IntPtr.Zero){WaveOut.waveOutReset(handle);foreach(var (header,_) in buffers)try{WaveOut.waveOutUnprepareHeader(handle,header,(uint)Marshal.SizeOf<WAVEHDR>());}catch{}try{WaveOut.waveOutClose(handle);}catch{}handle=IntPtr.Zero;}
            foreach(var (header,data) in buffers){Marshal.FreeHGlobal(header);Marshal.FreeHGlobal(data);}
            buffers.Clear();
        }
    }
}

delegate void WaveOutProc(IntPtr hwo,uint uMsg,IntPtr dwInstance,IntPtr dwParam1,IntPtr dwParam2);

static class WaveOut
{
    public const uint CALLBACK_FUNCTION=0x00030000;
    public const uint MM_WOM_DONE=0x3BD;
    public const int MMSYSERR_NOERROR=0;
    public const int MMSYSERR_ALLOCATED=4;
    public const int MMSYSERR_BADDEVICEID=2;
    public const int MMSYSERR_NODRIVER=6;

    public static string ErrorMessage(int rc,string action)=>rc switch{
        MMSYSERR_BADDEVICEID=>$"Could not {action}: no such playback device.",
        MMSYSERR_ALLOCATED=>$"Could not {action}: the output device is already in use.",
        MMSYSERR_NODRIVER=>$"Could not {action}: no playback driver is installed.",
        _=>$"Could not {action} (mmresult {rc}).",
    };

    [DllImport("winmm.dll")]
    public static extern int waveOutOpen(out IntPtr hWaveOut,uint uDeviceID,ref WAVEFORMATEX lpFormat,WaveOutProc dwCallback,IntPtr dwInstance,uint dwFlags);
    [DllImport("winmm.dll")]
    public static extern int waveOutPrepareHeader(IntPtr hWaveOut,IntPtr lpWaveOutHdr,uint uSize);
    [DllImport("winmm.dll")]
    public static extern int waveOutUnprepareHeader(IntPtr hWaveOut,IntPtr lpWaveOutHdr,uint uSize);
    [DllImport("winmm.dll")]
    public static extern int waveOutWrite(IntPtr hWaveOut,IntPtr lpWaveOutHdr,uint uSize);
    [DllImport("winmm.dll")]
    public static extern int waveOutPause(IntPtr hWaveOut);
    [DllImport("winmm.dll")]
    public static extern int waveOutRestart(IntPtr hWaveOut);
    [DllImport("winmm.dll")]
    public static extern int waveOutReset(IntPtr hWaveOut);
    [DllImport("winmm.dll")]
    public static extern int waveOutClose(IntPtr hWaveOut);
}
