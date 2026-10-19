using LanMessenger;

// Voice Messages Windows, WT03 (VoiceRecorder.cs, W03) and part of WT05 (VoicePlayer.cs, W08)
// device-level lifecycle/fault coverage: repeated actions, Stop/Dispose idempotency, native
// resource release, and behavior under real winmm calls. This environment turns out to expose
// one real waveIn/waveOut device (confirmed by direct probe before writing this), so these are
// genuine native calls, not a simulation -- unlike WT01 (pure algorithms) and WT02 (registry
// only), this is the first coverage of the actual P/Invoke adapters. Invoked via
// `CsharpHarness --voice-device-check`.
//
// Not covered here (documented gap, not silently skipped): the UI-level lifecycle paths in
// ChatWindowVoice.cs (conversation-switch forced stop, Offline-transition forced stop, window
// close/shutdown) are WinForms event handlers, not reachable from this console harness -- they
// would need the separate screenshot-based tests/WindowsUi automation, not attempted here. A
// real device *failure* (physical unplug) also cannot be forced from software; only the
// no-device/never-crash paths that don't require one are exercised.
static class VoiceDeviceLifecycleCheck
{
    [System.Runtime.InteropServices.DllImport("winmm.dll")] static extern uint waveInGetNumDevs();
    [System.Runtime.InteropServices.DllImport("winmm.dll")] static extern uint waveOutGetNumDevs();

    public static int Run()
    {
        if (waveInGetNumDevs() == 0 || waveOutGetNumDevs() == 0)
        {
            Console.WriteLine("VOICEDEVICECHECK\tSKIP\tno waveIn/waveOut device present in this environment");
            return 0;
        }
        int pass = 0, fail = 0;
        void Check(bool ok, string label)
        {
            if (ok) { pass++; Console.WriteLine("PASS: " + label); }
            else { fail++; Console.WriteLine("FAIL: " + label); }
        }
        void CheckThrows<T>(Action act, string label) where T : Exception
        {
            try { act(); Check(false, label + " (no exception thrown)"); }
            catch (T) { Check(true, label); }
            catch (Exception e) { Check(false, label + " (wrong exception: " + e.GetType().Name + ")"); }
        }

        // --- VoiceRecorder (W03) ---
        using (var r = new VoiceRecorder())
        {
            Check(r.Stop() == null, "Stop() before Start() is a safe no-op");
            Check(!r.Recording, "Recording is false before Start()");
        }
        Check(true, "Dispose() before Start() does not throw"); // the using block above already proved this

        using (var r = new VoiceRecorder())
        {
            try
            {
                r.Start();
                CheckThrows<InvalidOperationException>(() => r.Start(), "Start() while already started throws InvalidOperationException");
                Check(r.Recording, "Recording is true after Start()");
                System.Threading.Thread.Sleep(1500);
                var result = r.Stop();
                Check(result != null, "Stop() after a real recording returns a result");
                Check(!r.Recording, "Recording is false after Stop()");
                Check(r.Stop() == null, "A second Stop() call is a safe no-op (idempotent)");
            }
            catch (Exception e) { Check(false, "basic Start/record/Stop lifecycle (unexpected: " + e.Message + ")"); }
        }

        {
            int frameCount = 0;
            using var r = new VoiceRecorder();
            r.FrameReady += _ => System.Threading.Interlocked.Increment(ref frameCount);
            try
            {
                r.Start();
                System.Threading.Thread.Sleep(1500);
                r.Stop();
                Check(frameCount > 0, "real captured frames actually arrive via FrameReady (got " + frameCount + ")");
            }
            catch (Exception e) { Check(false, "frame delivery check (unexpected: " + e.Message + ")"); }
        }

        try
        {
            var r = new VoiceRecorder();
            r.Start();
            r.Dispose(); // no Stop() call -- must not hang or crash even while callbacks may be in flight.
            Check(true, "Dispose() without a prior Stop() completes without hanging");
        }
        catch (Exception e) { Check(false, "Dispose()-without-Stop() (unexpected: " + e.Message + ")"); }

        try
        {
            using var r = new VoiceRecorder();
            r.Start(); r.Stop();
            r.Dispose(); r.Dispose(); // Dispose() twice after a clean Stop() must also be safe.
            Check(true, "Dispose() called twice after Stop() is safe");
        }
        catch (Exception e) { Check(false, "double Dispose() after Stop() (unexpected: " + e.Message + ")"); }

        // Two concurrent recorders on the same device: document whichever clean outcome this
        // driver actually gives (shared access, or a clear "already in use" refusal) -- never a
        // crash or hang either way.
        try
        {
            using var r1 = new VoiceRecorder(); r1.Start();
            using var r2 = new VoiceRecorder();
            try { r2.Start(); Check(true, "a second concurrent recorder is accepted by this driver (shared access)"); r2.Stop(); }
            catch (IOException e) { Check(true, "a second concurrent recorder is cleanly refused: " + e.Message); }
            r1.Stop();
        }
        catch (Exception e) { Check(false, "concurrent-recorder probe crashed instead of a clean outcome (" + e.Message + ")"); }

        // --- VoicePlayer (W08) device-level lifecycle ---
        var pcm = new byte[16000 * 2]; // 1 s of silence, contract format
        var wav = VoiceWav.BuildCanonical(pcm);
        var info = VoiceWav.Validate(wav).Info!;

        using (var p = new VoicePlayer(wav, info))
        {
            p.Stop(); // safe before Play()
            Check(!p.Playing, "Playing is false before Play()");
        }

        {
            using var p = new VoicePlayer(wav, info);
            p.Dispose();
            CheckThrows<InvalidOperationException>(() => p.Play(), "Play() after Dispose() throws InvalidOperationException");
        }

        try
        {
            bool completed = false;
            using var p = new VoicePlayer(wav, info);
            p.Completed += () => completed = true;
            p.Play();
            Check(p.Playing, "Playing is true after Play()");
            p.Pause();
            Check(!p.Playing, "Playing is false after Pause()");
            p.Resume();
            Check(p.Playing, "Playing is true after Resume()");
            var seek = p.Seek(200);
            Check(seek.State == "ready" || seek.State == "completed", "Seek() mid-playback returns a valid state (" + seek.State + ")");
            System.Threading.Thread.Sleep(2000);
            Check(completed, "playback of a short WAV reaches Completed");
        }
        catch (Exception e) { Check(false, "Play/Pause/Resume/Seek/Completed lifecycle (unexpected: " + e.Message + ")"); }

        try
        {
            var p = new VoicePlayer(wav, info);
            p.Play();
            p.Dispose(); // no Stop() call -- must not hang even mid-playback.
            Check(true, "Dispose() mid-playback without a prior Stop() completes without hanging");
        }
        catch (Exception e) { Check(false, "Dispose()-mid-playback (unexpected: " + e.Message + ")"); }

        Console.WriteLine($"VOICEDEVICECHECK\tPASS={pass}\tFAIL={fail}");
        return fail == 0 ? 0 : 1;
    }
}
