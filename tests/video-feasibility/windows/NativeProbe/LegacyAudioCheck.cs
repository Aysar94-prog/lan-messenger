using System.Reflection;
using System.Text.Json;

// R03 historical production baseline only. Never references the old RTC package
// from this test project's dependency graph or selects it for a new release.
static class LegacyAudioCheck
{
    public static void Run(string baselineAssembly, Func<string, string, string> native,
        Func<string> gathered, bool legacyCaller)
    {
        var path = Path.GetFullPath(baselineAssembly);
        var directory = Path.GetDirectoryName(path)!;
        Assembly? Resolve(object? sender, ResolveEventArgs args)
        {
            var name = new AssemblyName(args.Name).Name;
            if (string.IsNullOrEmpty(name) || name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0) return null;
            var dependency = Path.Combine(directory, name + ".dll");
            return File.Exists(dependency) ? Assembly.LoadFrom(dependency) : null;
        }
        AppDomain.CurrentDomain.AssemblyResolve += Resolve;
        object? legacy = null;
        try
        {
            var assembly = Assembly.LoadFrom(path);
            if (assembly.GetName().Version != new Version(2, 2, 42, 0))
                throw new InvalidOperationException("Only the unchanged 2.2.42 baseline is accepted");
            var type = assembly.GetType("LanMessenger.WebRtcCallMedia", throwOnError: true)!;
            legacy = Activator.CreateInstance(type)!;
            object Invoke(string method, params object[] arguments) =>
                type.GetMethod(method)!.Invoke(legacy, arguments)!;
            string Description(string method, params object[] arguments) =>
                ((Task<string>)Invoke(method, arguments)).GetAwaiter().GetResult();
            // This is an explicitly invoked physical-device test, not an unattended
            // capture path. The historical adapter starts audio in its constructor.
            if (legacyCaller)
            {
                var offer = Description("CreateOfferAsync");
                VoiceOnly(offer);
                native("set-offer", offer);
                native("answer", "");
                var answer = gathered();
                VoiceOnly(answer);
                ((Task)Invoke("SetRemoteAnswerAsync", answer)).GetAwaiter().GetResult();
            }
            else
            {
                native("offer", "");
                var offer = gathered();
                VoiceOnly(offer);
                var answer = Description("CreateAnswerAsync", offer);
                VoiceOnly(answer);
                native("set-answer", answer);
            }
            var deadline = DateTime.UtcNow.AddSeconds(15);
            while (!native("state", "").Contains("connection=2"))
            {
                if (DateTime.UtcNow >= deadline) throw new TimeoutException("Historical production audio ICE failed");
                Thread.Sleep(100);
            }
            native("start-audio", "");
            deadline = DateTime.UtcNow.AddSeconds(8);
            while (true)
            {
                using var stats = JsonDocument.Parse(native("stats", ""));
                long sent = 0, received = 0;
                bool g722 = false;
                foreach (var row in stats.RootElement.EnumerateArray())
                {
                    if (!row.TryGetProperty("type", out var kind)) continue;
                    var value = kind.GetString();
                    if (value == "codec" && row.TryGetProperty("mimeType", out var mime)
                        && mime.GetString() == "audio/G722") g722 = true;
                    if (value == "outbound-rtp" && row.TryGetProperty("packetsSent", out var s)) sent += s.GetInt64();
                    if (value == "inbound-rtp" && row.TryGetProperty("packetsReceived", out var r)) received += r.GetInt64();
                }
                if (sent >= 30 && received >= 30 && g722)
                {
                    Invoke("SetMuted", true);
                    if (!(bool)type.GetProperty("IsMuted")!.GetValue(legacy)!)
                        throw new InvalidOperationException("Historical mute state failed");
                    Invoke("SetMuted", false);
                    Console.WriteLine($"PASS: native ↔ unchanged Windows 2.2.42 voice-only G722; legacyCaller={legacyCaller}; sent={sent}; received={received}; audible acceptance separate");
                    break;
                }
                if (DateTime.UtcNow >= deadline) throw new TimeoutException("Historical G722 packets missing in one direction");
                Thread.Sleep(250);
            }
        }
        finally
        {
            if (legacy is IDisposable disposable) disposable.Dispose();
            AppDomain.CurrentDomain.AssemblyResolve -= Resolve;
        }
    }
    static void VoiceOnly(string sdp)
    {
        if (!sdp.StartsWith("v=0") || !sdp.Contains("m=audio") || sdp.Contains("m=video")
            || !sdp.Contains("a=fingerprint:") || !sdp.Contains("G722/8000"))
            throw new InvalidOperationException("Historical pairing is not secure G722 voice-only SDP");
    }
}
