using LanMessenger.Windows;
using LanMessenger;
using System.Text.Json;

int passed = 0;
string? nativeDll = args.FirstOrDefault(arg => arg.EndsWith(".dll", StringComparison.OrdinalIgnoreCase));
void Check(bool value, string name)
{
    if (!value) throw new Exception("FAIL: " + name);
    passed++; Console.WriteLine("PASS: " + name);
}
try
{
    if (args.Contains("--camera-local"))
    {
        // Explicit local device test. There is no remote description or ICE peer, so no camera
        // content can leave this process. Native startup/teardown runs on this same owning thread.
        using var local = new CallVideoNativeMedia(nativeDll);
        var preview = new PreviewSink(); local.AttachLocal(preview);
        local.Initialize(2, () => true);
        try { local.StartCamera(2); }
        catch (InvalidOperationException e)
        {
            Console.WriteLine("CAMERA NOT ACCEPTED: " + e.Message); return 2;
        }
        DateTime deadline = DateTime.UtcNow.AddSeconds(8);
        while (Volatile.Read(ref preview.Frames) < 3 && DateTime.UtcNow < deadline) Thread.Sleep(50);
        Check(preview.Frames >= 3, "physical default camera produces bounded local preview frames");
        local.StopCamera(2);
        int before = preview.Frames;
        local.StartCamera(2); deadline = DateTime.UtcNow.AddSeconds(8);
        while (Volatile.Read(ref preview.Frames) < before + 3 && DateTime.UtcNow < deadline) Thread.Sleep(50);
        Check(preview.Frames >= before + 3, "physical default camera reopens after stop");
        local.StopCamera(2);
        Console.WriteLine($"Local physical camera: {passed} passed, 0 failed (no remote peer)"); return 0;
    }
    // Production managed adapter, real native stack, no camera acquired.
    for (int cycle = 0; cycle < 20; cycle++)
    {
        using var media = new CallVideoNativeMedia(nativeDll);
        media.Initialize(2, () => false);
        try { media.StartCamera(2); throw new Exception("Camera gate was bypassed"); }
        catch (InvalidOperationException e) { Check(e.Message.Contains("not authorized"), "capture gate refuses before acquisition " + cycle); }
        string offer = media.CreateOffer(2);
        Check(CallVideoProtocol.ValidSdp(offer, true), "real native offer satisfies Android/Windows SDP contract " + cycle);
        Check(offer.Contains("m=video") && offer.Contains("VP8/90000") && offer.Contains("a=sendrecv"), "send slot negotiated without camera " + cycle);
        try { media.SetRemoteAnswer(2, "invalid SDP"); throw new Exception("Native error was swallowed"); }
        catch (InvalidOperationException) { Check(true, "native JSON errors propagate " + cycle); }
        media.Dispose(1);
        Check(!media.GetState().Contains("error"), "stale disposal keeps active peer " + cycle);
        media.Dispose(2);
        media.Initialize(3, () => false);
        Check(media.CreateOffer(3).Contains("m=video"), "new generation works after video failure " + cycle);
    }

    // Independent native peers exchange real ICE/DTLS/VP8 using the explicit synthetic test source.
    // This proves transport and decoded callbacks, not physical camera or Android acceptance.
    for (int cycle = 0; cycle < 3; cycle++)
    {
        using var a = new CallVideoNativeBridge(nativeDll); using var b = new CallVideoNativeBridge(nativeDll);
        ulong ah = a.CreateBridge(), bh = b.CreateBridge();
        int remoteFrames = 0, localFrames = 0;
        CallVideoNativeBridge.FrameCallback callback = (handle, kind, pixels, width, height, stride, context) =>
        {
            if (pixels == IntPtr.Zero || width <= 0 || height <= 0 || stride < width * 4) return;
            if (kind == 1) Interlocked.Increment(ref remoteFrames);
            else if (kind == 0) Interlocked.Increment(ref localFrames);
        };
        try
        {
            b.RegisterFrameCallback(bh, callback);
            a.RegisterFrameCallback(ah, callback);
            string Sdp(CallVideoNativeBridge bridge, ulong h, string verb)
            {
                var result = bridge.Command(h, verb);
                using var j = JsonDocument.Parse(result.Body);
                return j.RootElement.GetProperty("sdp").GetString()!;
            }
            void Ok(CallVideoNativeBridge bridge, ulong h, string verb, string body = "")
            {
                var r = bridge.Command(h, verb, body);
                if (r.Code != 1 || r.Body.Contains("\"error\"")) throw new Exception(verb + ": " + r.Body);
            }
            string offer = Sdp(a, ah, "create-offer");
            Check(CallVideoProtocol.ValidSdp(offer, true), "native caller offer satisfies video wire contract " + cycle);
            Ok(a, ah, "set-local", "offer|" + offer); Ok(b, bh, "set-remote", "offer|" + offer);
            string answer = Sdp(b, bh, "create-answer");
            Check(CallVideoProtocol.ValidSdp(answer, true), "native callee answer satisfies video wire contract " + cycle);
            Ok(b, bh, "set-local", "answer|" + answer); Ok(a, ah, "set-remote", "answer|" + answer);
            void Ice(CallVideoNativeBridge from, ulong fh, CallVideoNativeBridge to, ulong th)
            {
                foreach (string candidate in JsonSerializer.Deserialize<string[]>(from.Command(fh, "take-candidates").Body)!)
                {
                    string[] fields = candidate.Split('|', 3);
                    if (fields.Length != 3 || fields[1] != "0") throw new Exception("Video ICE has invalid m-line index");
                    Ok(to, th, "add-ice", candidate);
                }
            }
            DateTime deadline = DateTime.UtcNow.AddSeconds(15);
            bool connected = false;
            while (DateTime.UtcNow < deadline)
            {
                Ice(a, ah, b, bh); Ice(b, bh, a, ah);
                string sa = a.Command(ah, "state").Body, sb = b.Command(bh, "state").Body;
                bool Ready(string s) => s.Contains("\"ice\":\"connected\"") || s.Contains("\"ice\":\"completed\"");
                if (Ready(sa) && Ready(sb)) { connected = true; break; }
                Thread.Sleep(50);
            }
            Check(connected, "native peers connect with real ICE " + cycle);
            Ok(a, ah, "start-video", "synthetic");
            deadline = DateTime.UtcNow.AddSeconds(10);
            while (Volatile.Read(ref remoteFrames) < 3 && DateTime.UtcNow < deadline) Thread.Sleep(50);
            Check(remoteFrames >= 3 && localFrames >= 3, $"VP8 decoded remote frames and local preview arrive {cycle} (remote={remoteFrames}, local={localFrames})");
            Ok(a, ah, "stop-video");
            int before = remoteFrames;
            Ok(a, ah, "start-video", "synthetic");
            deadline = DateTime.UtcNow.AddSeconds(10);
            while (Volatile.Read(ref remoteFrames) < before + 3 && DateTime.UtcNow < deadline) Thread.Sleep(50);
            Check(remoteFrames >= before + 3, "camera restart uses negotiated sender without new offer " + cycle);
            Parallel.For(0, 40, _ => CheckState(a.Command(ah, "state")));
            Check(true, "shared native output buffer survives concurrent polling " + cycle);
        }
        finally
        {
            a.RegisterFrameCallback(ah, null); b.RegisterFrameCallback(bh, null);
            a.DestroyBridge(ah); b.DestroyBridge(bh); GC.KeepAlive(callback);
        }
    }
    Console.WriteLine($"Native video media: {passed} passed, 0 failed"); return 0;
}
catch (Exception e) { Console.Error.WriteLine(e); return 1; }

static void CheckState((int Code, string Body) r)
{
    if (r.Code != 1) throw new Exception("State command failed");
    using var j = JsonDocument.Parse(r.Body);
    if (!j.RootElement.TryGetProperty("ice", out _)) throw new Exception("Native output buffer corrupted");
}

sealed class PreviewSink : ICallVideoFrameSink
{
    public int Frames;
    public void OnFrame(object frame)
    {
        if (frame is CallVideoFrame f && f.Width > 0 && f.Height > 0 &&
            f.Bgra.Length == f.Stride * f.Height && f.Stride >= f.Width * 4)
            Interlocked.Increment(ref Frames);
    }
}
