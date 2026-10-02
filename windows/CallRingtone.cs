namespace LanMessenger;

// A synthesized ring/ringback tone played directly through the app's own proven winmm playback
// path (CallAudioPlayback), rather than the OS "system sound" event (MessageBeep/SystemSounds).
// Real-device testing showed the OS route can be silently inaudible -- a user's sound scheme can
// have no file assigned, or system-sound playback can be muted/disabled independently of the
// speaker volume -- while CallAudioPlayback plays through the actual default output device the
// same way call audio itself will. Starts/stops instantly and cheaply; no file asset involved.
public sealed class CallRingtone : IDisposable
{
    const int SampleRate = 16000;
    const int FrameBytes = CallAudioCapture.FrameBytes; // 640 bytes = 20ms @ 16kHz mono 16-bit
    static readonly byte[][] pattern = BuildPattern();

    readonly CallAudioPlayback playback = new();
    Thread? thread;
    volatile bool stop;

    public void Start()
    {
        if (thread != null) return;
        try { playback.Start(); } catch { return; } // no playback device -- ring silently rather than throw
        stop = false;
        var t = new Thread(Run) { IsBackground = true, Name = "call-ringtone" };
        thread = t;
        t.Start();
    }

    void Run()
    {
        var sw = System.Diagnostics.Stopwatch.StartNew();
        long nextMs = 0;
        for (int i = 0; !stop; i++)
        {
            try { playback.Enqueue(pattern[i % pattern.Length]); } catch { return; }
            nextMs += 20;
            var waitMs = nextMs - sw.ElapsedMilliseconds;
            if (waitMs > 0) Thread.Sleep((int)waitMs);
        }
    }

    public void Stop()
    {
        if (thread == null) return;
        stop = true;
        var t = thread; thread = null;
        try { t.Join(300); } catch { }
        try { playback.Dispose(); } catch { }
    }

    public void Dispose() => Stop();

    // ~2s of a soft two-tone ring (440Hz+480Hz, the classic North American ring cadence) followed
    // by ~3s of silence, repeating for as long as Start() keeps feeding frames from it.
    static byte[][] BuildPattern()
    {
        const double toneSeconds = 2.0, silenceSeconds = 3.0;
        int toneFrames = (int)(toneSeconds * 1000 / 20);
        int silenceFrames = (int)(silenceSeconds * 1000 / 20);
        int samplesPerFrame = FrameBytes / 2;
        var frames = new byte[toneFrames + silenceFrames][];
        double phase1 = 0, phase2 = 0;
        for (int f = 0; f < toneFrames; f++)
        {
            var buf = new byte[FrameBytes];
            for (int s = 0; s < samplesPerFrame; s++)
            {
                double sample = 0.30 * Math.Sin(phase1) + 0.30 * Math.Sin(phase2);
                phase1 += 2 * Math.PI * 440.0 / SampleRate;
                phase2 += 2 * Math.PI * 480.0 / SampleRate;
                short v = (short)(Math.Clamp(sample, -1.0, 1.0) * short.MaxValue);
                buf[s * 2] = (byte)(v & 0xFF);
                buf[s * 2 + 1] = (byte)((v >> 8) & 0xFF);
            }
            frames[f] = buf;
        }
        var silence = new byte[FrameBytes];
        for (int f = 0; f < silenceFrames; f++) frames[toneFrames + f] = silence;
        return frames;
    }
}
