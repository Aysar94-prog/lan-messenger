using System.Runtime.InteropServices;
using System.Text.Json;

// Test-only stdin control; the peer connection, not stdin/adb, carries media.
static class EndpointDriver
{
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    delegate int Create(out ulong handle);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    delegate int Destroy(ulong handle);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    delegate int Command(ulong handle, [MarshalAs(UnmanagedType.LPUTF8Str)] string command,
        [MarshalAs(UnmanagedType.LPUTF8Str)] string payload, IntPtr output, int capacity);

    public static void Run(string path, bool check, string? legacyAssembly = null)
    {
        if (!Environment.Is64BitProcess) throw new InvalidOperationException("x64 required");
        var library = NativeLibrary.Load(Path.GetFullPath(path));
        var handles = new Dictionary<string, ulong>();
        var buffer = Marshal.AllocHGlobal(131072);
        try
        {
            var create = Marshal.GetDelegateForFunctionPointer<Create>(NativeLibrary.GetExport(library, "lm_endpoint_create"));
            var createVideo = Marshal.GetDelegateForFunctionPointer<Create>(NativeLibrary.GetExport(library, "lm_endpoint_create_video_only"));
            var destroy = Marshal.GetDelegateForFunctionPointer<Destroy>(NativeLibrary.GetExport(library, "lm_endpoint_destroy"));
            var command = Marshal.GetDelegateForFunctionPointer<Command>(NativeLibrary.GetExport(library, "lm_endpoint_command"));
            string Call(ulong handle, string operation, string payload = "")
            {
                var code = command(handle, operation, payload, buffer, 131072);
                var value = Marshal.PtrToStringUTF8(buffer) ?? "";
                if (code != 1) throw new InvalidOperationException($"{operation} failed ({code}): {value}");
                return value;
            }
            ulong New(string node, bool videoOnly = false)
            {
                if (node is not ("a" or "b") || handles.ContainsKey(node))
                    throw new InvalidOperationException("Node must be a or b and not already created");
                var code = (videoOnly ? createVideo : create)(out var handle);
                if (code != 1 || handle == 0) throw new InvalidOperationException($"Endpoint create failed: {code}");
                handles.Add(node, handle);
                return handle;
            }
            string Gathered(ulong handle)
            {
                var timeout = DateTime.UtcNow.AddSeconds(10);
                while (!Call(handle, "state").Contains("gathering=2"))
                {
                    if (DateTime.UtcNow >= timeout) throw new TimeoutException("ICE gathering timed out");
                    Thread.Sleep(100);
                }
                return Call(handle, "sdp");
            }
            if (legacyAssembly != null)
            {
                foreach (var legacyCaller in new[] { false, true })
                {
                    var a = New("a");
                    LegacyAudioCheck.Run(legacyAssembly, (operation, payload) => Call(a, operation, payload),
                        () => Gathered(a), legacyCaller);
                    Call(a, "stop-audio");
                    if (destroy(a) != 1) throw new InvalidOperationException("Baseline endpoint teardown failed");
                    handles.Remove("a");
                }
                return;
            }
            if (check)
            {
                var a = New("a");
                var b = New("b");
                Call(a, "offer");
                var offer = Gathered(a);
                if (!offer.Contains("G722/8000") || !offer.Contains("a=fingerprint:") || offer.Contains("m=video"))
                    throw new InvalidOperationException("Voice-only secure G722 SDP boundary failed");
                Call(b, "set-offer", offer);
                Call(b, "answer");
                Call(a, "set-answer", Gathered(b));
                var timeout = DateTime.UtcNow.AddSeconds(15);
                while (!Call(a, "state").Contains("connection=2") || !Call(b, "state").Contains("connection=2"))
                {
                    if (DateTime.UtcNow >= timeout) throw new TimeoutException("Native local peers did not connect");
                    Thread.Sleep(100);
                }
                if (command(0, "state", "", buffer, 131072) != -2 ||
                    command(a, "state", "", IntPtr.Zero, 0) != -1)
                    throw new InvalidOperationException("Endpoint ABI input bounds failed");
                using var report = JsonDocument.Parse(Call(a, "stats"));
                Call(a, "video"); Call(a, "offer");
                Call(b, "set-offer", Gathered(a)); Call(b, "video"); Call(b, "answer");
                Call(a, "set-answer", Gathered(b));
                timeout = DateTime.UtcNow.AddSeconds(15);
                while (true)
                {
                    using var ac = JsonDocument.Parse(Call(a, "counters"));
                    using var bc = JsonDocument.Parse(Call(b, "counters"));
                    bool Moving(JsonDocument c) => c.RootElement.GetProperty("decodedSinkFrames").GetInt64() >= 10
                        && c.RootElement.GetProperty("decodedMotionChanges").GetInt64() >= 5;
                    if (Moving(ac) && Moving(bc)) break;
                    if (DateTime.UtcNow >= timeout) throw new TimeoutException("Secure local generated video did not arrive");
                    Thread.Sleep(100);
                }
                Call(a, "video-off"); Call(b, "video-off");
                if (destroy(a) != 1 || destroy(a) != -2) throw new InvalidOperationException("Double destroy failed");
                handles.Remove("a");
                if (command(a, "state", "", buffer, 131072) != -2)
                    throw new InvalidOperationException("Stale endpoint handle accepted");
                if (destroy(b) != 1) throw new InvalidOperationException("Endpoint b teardown failed");
                handles.Remove("b");
                var replacement = New("a");
                if (replacement == a) throw new InvalidOperationException("Endpoint handle was reused");
                if (destroy(replacement) != 1) throw new InvalidOperationException("Replacement teardown failed");
                handles.Remove("a");
                // Verify the native live/pending resource cap before any fifth factory exists.
                for (int i = 0; i < 4; ++i)
                {
                    if (createVideo(out var limited) != 1 || limited == 0)
                        throw new InvalidOperationException("Bounded video endpoint creation failed");
                    handles.Add("limit-" + i, limited);
                }
                if (createVideo(out var excess) != -2 || excess != 0)
                {
                    if (excess != 0) handles.Add("unexpected-limit", excess);
                    throw new InvalidOperationException("Native endpoint resource limit failed");
                }
                foreach (var node in handles.Keys.ToArray())
                {
                    if (destroy(handles[node]) != 1) throw new InvalidOperationException("Bounded endpoint teardown failed");
                    handles.Remove(node);
                }
                for (int i = 0; i < 20; ++i)
                {
                    var repeated = New("a", videoOnly: true);
                    if (Call(repeated, "audio-status") != "video-only; no audio track")
                        throw new InvalidOperationException("Video endpoint unexpectedly acquired audio");
                    if (destroy(repeated) != 1) throw new InvalidOperationException("Repeated native teardown failed");
                    handles.Remove("a");
                }
                Console.WriteLine("PASS: native endpoints secure G722 audio-only SDP, local generated VP8 upgrade/motion both ways, stats, stale/double destroy, fresh lifetime; audio capture disabled");
                Console.WriteLine("PASS: native four-endpoint resource bound and 20 separate video-only teardown lifetimes");
                return;
            }
            Console.WriteLine("{\"ready\":true,\"testOnly\":true}");
            while (Console.ReadLine() is { } line)
            {
                try
                {
                    if (line.Length > 100000) throw new ArgumentException("Control line too large");
                    using var request = JsonDocument.Parse(line);
                    var root = request.RootElement;
                    var operation = root.GetProperty("cmd").GetString() ?? "";
                    var node = root.GetProperty("node").GetString() ?? "";
                    if (operation is "create" or "create-video") { New(node, operation == "create-video"); Console.WriteLine("{\"ok\":true,\"value\":\"created\"}"); continue; }
                    if (!handles.TryGetValue(node, out var handle)) throw new ArgumentException("Missing endpoint");
                    if (operation == "destroy")
                    {
                        if (destroy(handle) != 1) throw new InvalidOperationException("Destroy failed");
                        handles.Remove(node);
                        Console.WriteLine("{\"ok\":true,\"value\":\"destroyed\"}"); continue;
                    }
                    var payload = root.TryGetProperty("payload", out var body) ? body.GetString() ?? "" : "";
                    if (System.Text.Encoding.UTF8.GetByteCount(payload) > 65536 || payload.Contains('\0'))
                        throw new ArgumentException("Payload bounds failed");
                    Console.WriteLine(JsonSerializer.Serialize(new { ok = true, value = Call(handle, operation, payload) }));
                }
                catch (Exception error) { Console.WriteLine(JsonSerializer.Serialize(new { ok = false, error = error.Message })); }
            }
            // EOF tears down all endpoints; this driver never persists production data.
            foreach (var node in handles.Keys.ToArray())
            {
                if (destroy(handles[node]) != 1) throw new InvalidOperationException("EOF teardown failed");
                handles.Remove(node);
            }
        }
        finally
        {
            // Never unload native code with live native callback threads.
            bool safeToUnload = true;
            if (handles.Count != 0)
            {
                var destroy = Marshal.GetDelegateForFunctionPointer<Destroy>(NativeLibrary.GetExport(library, "lm_endpoint_destroy"));
                foreach (var handle in handles.Values)
                    if (destroy(handle) != 1) safeToUnload = false;
            }
            Marshal.FreeHGlobal(buffer);
            if (safeToUnload) NativeLibrary.Free(library);
            else Console.Error.WriteLine("Native teardown failed; retaining loaded code until process exit.");
        }
    }
}
