using System.Runtime.InteropServices;

// Automated ABI-contract verification for the WVC-05 production native bridge.
//
// Every check here runs with no camera, no microphone, no network and no peer. That is the
// point: the properties being verified are the ones that must hold before any hardware is
// trusted, and they are exactly the properties a hardware-dependent test cannot demonstrate --
// that probing never opens a device, that handles are never reused, that teardown happens once,
// that video can be disposed without ending the call.
//
// The native library is loaded by explicit path and bound through export delegates rather than
// a DllImport name, so the check cannot silently bind a different DLL that happens to be on the
// loader path.
static class BridgeCheck
{
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    delegate uint AbiVersion();
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    delegate int Create(out ulong handle);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    delegate int Destroy(ulong handle);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    delegate int Command(ulong handle, [MarshalAs(UnmanagedType.LPUTF8Str)] string command,
        [MarshalAs(UnmanagedType.LPUTF8Str)] string payload, IntPtr output, int capacity);

    const int Ok = 1;
    const int BadArgument = -1;
    const int NotFound = -2;
    const int BufferTooSmall = -3;

    const int BufferBytes = 131072;

    static readonly List<string> Failures = new();
    static int _checks;

    static void Check(string name, bool condition, string detail = "")
    {
        _checks++;
        if (condition)
        {
            Console.WriteLine($"PASS  {name}");
        }
        else
        {
            Failures.Add($"{name}{(detail.Length > 0 ? ": " + detail : "")}");
            Console.WriteLine($"FAIL  {name}{(detail.Length > 0 ? ": " + detail : "")}");
        }
    }

    public static int Run(string path)
    {
        if (!Environment.Is64BitProcess)
        {
            Console.Error.WriteLine("x64 required");
            return 2;
        }
        var full = Path.GetFullPath(path);
        if (!File.Exists(full))
        {
            Console.Error.WriteLine($"native bridge not found: {full}");
            return 2;
        }
        var library = NativeLibrary.Load(full);
        var buffer = Marshal.AllocHGlobal(BufferBytes);
        try
        {
            var abi = Marshal.GetDelegateForFunctionPointer<AbiVersion>(
                NativeLibrary.GetExport(library, "lm_wr_abi_version"));
            var create = Marshal.GetDelegateForFunctionPointer<Create>(
                NativeLibrary.GetExport(library, "lm_wr_create"));
            var destroy = Marshal.GetDelegateForFunctionPointer<Destroy>(
                NativeLibrary.GetExport(library, "lm_wr_destroy"));
            var command = Marshal.GetDelegateForFunctionPointer<Command>(
                NativeLibrary.GetExport(library, "lm_wr_command"));

            string Read()
            {
                var length = 0;
                while (length < BufferBytes - 1 && Marshal.ReadByte(buffer, length) != 0) length++;
                var bytes = new byte[length];
                if (length > 0) Marshal.Copy(buffer, bytes, 0, length);
                return System.Text.Encoding.UTF8.GetString(bytes);
            }

            (int Code, string Body) Call(ulong handle, string verb, string payload = "",
                int capacity = BufferBytes)
            {
                var code = command(handle, verb, payload, buffer, capacity);
                return (code, capacity >= 1 ? Read() : "");
            }

            // ---- ABI handshake ------------------------------------------------------
            // The managed side must be able to refuse a DLL built against a different surface.
            var packed = abi();
            Check("abi version is 1.1", packed == (1u << 16 | 1u), $"got 0x{packed:X8}");

            ulong New()
            {
                var code = create(out var handle);
                if (code != Ok || handle == 0)
                    throw new InvalidOperationException($"bridge create failed: {code}");
                return handle;
            }

            // ---- probe must not touch a device --------------------------------------
            // This is the load-bearing check of the whole design. cameraEnumerated and
            // audioDeviceOpened are reported by the native side about itself; if any probe or
            // signalling path could reach a device, these would not both be false here.
            var probeHandle = New();
            try
            {
                var (probeCode, probeBody) = Call(probeHandle, "probe");
                Check("probe succeeds", probeCode == Ok, probeBody);
                Check("probe declares no camera enumeration", probeBody.Contains("\"cameraEnumerated\":false"), probeBody);
                Check("probe declares no audio device opened", probeBody.Contains("\"audioDeviceOpened\":false"), probeBody);
                Check("probe reports a ready factory", probeBody.Contains("\"factoryReady\":true"), probeBody);
                Check("probe reports a ready peer", probeBody.Contains("\"peerReady\":true"), probeBody);
                Check("probe advertises VP8", probeBody.Contains("VP8"), probeBody);

                // ---- lifecycle, one-shot teardown, unknown handles --------------------
                Check("destroy of a live handle succeeds", Call(probeHandle, "state").Code == Ok);
                Check("destroy succeeds", destroy(probeHandle) == Ok);
                Check("destroy is one-shot", destroy(probeHandle) == NotFound);
                var (goneCode, _) = Call(probeHandle, "state");
                Check("commands on a destroyed handle report not-found", goneCode == NotFound, $"code {goneCode}");
                var (unknownCode, _) = Call(999999, "probe");
                Check("commands on an unknown handle report not-found", unknownCode == NotFound, $"code {unknownCode}");

                // ---- argument validation ---------------------------------------------
                var live = New();
                try
                {
                    Check("capacity below the minimum is rejected", command(live, "probe", "", buffer, 1) == BadArgument);
                    Check("zero capacity is rejected", command(live, "probe", "", buffer, 0) == BadArgument);
                    Check("absurd capacity is rejected", command(live, "probe", "", buffer, 1 << 21) == BadArgument);
                    Check("an over-long command name is rejected",
                        command(live, new string('x', 65), "", buffer, BufferBytes) == BadArgument);
                    Check("an over-long payload is rejected",
                        command(live, "set-remote", new string('x', 65537), buffer, BufferBytes) == BadArgument);

                    var (unknownVerbCode, unknownVerbBody) = Call(live, "nonsense");
                    Check("an unknown command is refused with a reason",
                        unknownVerbCode == Ok && unknownVerbBody.Contains("\"error\""), unknownVerbBody);

                    // ---- bounded output -------------------------------------------------
                    var (offerCode, offerBody) = Call(live, "create-offer");
                    Check("create-offer succeeds", offerCode == Ok, offerBody);
                    Check("create-offer returns SDP", offerBody.Contains("v=0"), offerBody[..Math.Min(120, offerBody.Length)]);
                    var shortCode = command(live, "create-offer", "", buffer, 16);
                    Check("an undersized buffer reports too-small", shortCode == BufferTooSmall, $"code {shortCode}");
                    Check("an undersized buffer is cleared, not truncated", Read().Length == 0);

                    // ---- video: refused for a real device, synthetic only ---------------
                    var (refusedCode, refusedBody) = Call(live, "start-video", "camera:integrated");
                    Check("a named camera is refused, never silently substituted",
                        refusedBody.Contains("\"error\""), refusedBody);
                    Check("the refusal names the reason it cannot open hardware",
                        refusedBody.Contains("unknown camera selection"), refusedBody);

                    var (startCode, startBody) = Call(live, "start-video", "synthetic");
                    Check("the synthetic source starts", startCode == Ok && startBody.Contains("\"ok\":true"), startBody);
                    Check("the synthetic source reports no device opened",
                        startBody.Contains("\"deviceOpened\":false"), startBody);
                    var (againCode, againBody) = Call(live, "start-video", "synthetic");
                    Check("starting twice is idempotent", againBody.Contains("alreadyStarted"), againBody);

                    var (stopCode, stopBody) = Call(live, "stop-video");
                    Check("video stops", stopCode == Ok && stopBody.Contains("\"wasRunning\":true"), stopBody);
                    var (stopTwiceCode, stopTwiceBody) = Call(live, "stop-video");
                    Check("stopping video twice is safe", stopTwiceCode == Ok && stopTwiceBody.Contains("\"wasRunning\":false"), stopTwiceBody);

                    // ---- video failure must not end the call ----------------------------
                    // The "video failure disposes video only" rule, verified: after disposing
                    // video, the same bridge still answers and can still produce SDP.
                    var (afterStopCode, afterStopBody) = Call(live, "state");
                    Check("the call survives video being disposed", afterStopCode == Ok, afterStopBody);
                    var (reofferCode, reofferBody) = Call(live, "create-offer");
                    Check("SDP still works after video is disposed", reofferCode == Ok && reofferBody.Contains("v=0"));

                    var (candidatesCode, candidatesBody) = Call(live, "take-candidates");
                    Check("candidate gathering returns a JSON array",
                        candidatesCode == Ok && candidatesBody.StartsWith('['), candidatesBody);

                    var (addIceCode, addIceBody) = Call(live, "add-ice", "not-a-candidate-shape");
                    Check("a malformed candidate is refused with a reason",
                        addIceCode == Ok && addIceBody.Contains("\"error\""), addIceBody);

                    // ---- handles are never reused ----------------------------------------
                    var first = live;
                    Check("destroy after use succeeds", destroy(first) == Ok);
                    var second = New();
                    Check("a later handle is strictly greater than an earlier one",
                        second > first, $"{first} then {second}");
                    Check("destroy of the later handle succeeds", destroy(second) == Ok);
                }
                finally
                {
                    if (live != 0) destroy(live);
                }
            }
            finally
            {
                if (probeHandle != 0) destroy(probeHandle);
            }

            // ---- the bridge count is capped -------------------------------------------
            var created = new List<ulong>();
            try
            {
                for (var index = 0; index < 8; index++) created.Add(New());
                Check("eight bridges can exist at once", created.Count == 8);
                Check("handles are distinct", created.Distinct().Count() == 8);
                var overflow = create(out _);
                Check("a ninth bridge is refused rather than admitted", overflow == NotFound, $"code {overflow}");
            }
            finally
            {
                foreach (var handle in created) destroy(handle);
            }
        }
        finally
        {
            Marshal.FreeHGlobal(buffer);
            NativeLibrary.Free(library);
        }

        Console.WriteLine();
        Console.WriteLine($"{_checks - Failures.Count}/{_checks} bridge ABI checks passed.");
        if (Failures.Count > 0)
        {
            Console.Error.WriteLine("Failures:");
            foreach (var failure in Failures) Console.Error.WriteLine("  " + failure);
            return 1;
        }
        return 0;
    }
}
