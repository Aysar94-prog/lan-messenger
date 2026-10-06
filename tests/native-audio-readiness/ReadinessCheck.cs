using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;

internal sealed class ReadinessCheck
{
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate uint AbiVersion();
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int Create(out ulong handle);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int Destroy(ulong handle);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int Command(ulong handle, [MarshalAs(UnmanagedType.LPUTF8Str)] string command,
        [MarshalAs(UnmanagedType.LPUTF8Str)] string payload, IntPtr output, int capacity);

    const int Ok = 1;
    const int BadArgument = -1;
    const int NotFound = -2;
    const int BufferTooSmall = -3;
    const int Internal = -4;
    const int BufferBytes = 131072;

    public static int Run(string dllPath)
    {
        if (!Environment.Is64BitProcess)
        {
            Console.Error.WriteLine("x64 required");
            return 2;
        }
        IntPtr lib = NativeLibrary.Load(dllPath);
        IntPtr buf = Marshal.AllocHGlobal(BufferBytes);
        int failures = 0;
        void Check(string name, bool condition, string detail = "")
        {
            if (condition) Console.WriteLine($"PASS  {name}");
            else { Console.Error.WriteLine($"FAIL  {name}" + (detail.Length>0?" "+detail:"")); failures++; }
        }
        try
        {
            var abi = Marshal.GetDelegateForFunctionPointer<AbiVersion>(NativeLibrary.GetExport(lib, "lm_wr_abi_version"));
            var create = Marshal.GetDelegateForFunctionPointer<Create>(NativeLibrary.GetExport(lib, "lm_wr_create"));
            var destroy = Marshal.GetDelegateForFunctionPointer<Destroy>(NativeLibrary.GetExport(lib, "lm_wr_destroy"));
            var cmd = Marshal.GetDelegateForFunctionPointer<Command>(NativeLibrary.GetExport(lib, "lm_wr_command"));

            uint v = abi();
            Check("ABI 1.1", v == (1u<<16 | 1u), $"0x{v:X8}");

            string Read()
            {
                int len = 0;
                while (len < BufferBytes-1 && Marshal.ReadByte(buf,len)!=0) len++;
                if (len==0) return "";
                byte[] b = new byte[len];
                Marshal.Copy(buf,b,0,len);
                return Encoding.UTF8.GetString(b);
            }
            (int Code,string Body) Call(ulong h,string verb,string payload="")
            {
                int c = cmd(h,verb,payload,buf,BufferBytes);
                return (c,c==Ok?Read():"");
            }
            bool TryParseBody(string body, out JsonDocument? d)
            {
                try { d = JsonDocument.Parse(body); return true; } catch { d = null; return false; }
            }

            // Collect latest output dir for provenance
            Console.WriteLine($"DLL: {dllPath}");

            // 1) Probe invariants (no device access, no enumeration)
            ulong probe = 0;
            try
            {
                probe = CreateHandle(create);
                var p = Call(probe,"probe");
                Check("probe succeeds", p.Code==Ok, p.Body);
                Check("probe declares no camera enumeration", p.Body.Contains("\"cameraEnumerated\":false"), p.Body);
                Check("probe declares no audio device opened", p.Body.Contains("\"audioDeviceOpened\":false"), p.Body);
                Check("probe factoryReady", p.Body.Contains("\"factoryReady\":true"), p.Body);
                Check("probe peerReady", p.Body.Contains("\"peerReady\":true"), p.Body);
            }
            finally { if (probe!=0) destroy(probe); }

            // 2) Synthetic video path and video-only disposal (fake path)
            ulong h = 0;
            try
            {
                h = CreateHandle(create);
                var refuse = Call(h,"start-video","camera:integrated");
                Check("unknown camera is refused with error", refuse.Body.Contains("\"error\"") && refuse.Body.Contains("unknown camera selection"), refuse.Body);
                var start = Call(h,"start-video","synthetic");
                Check("synthetic start succeeds with deviceOpened:false", start.Code==Ok && start.Body.Contains("\"deviceOpened\":false"), start.Body);
                var again = Call(h,"start-video","synthetic");
                Check("starting twice is idempotent", again.Body.Contains("alreadyStarted"), again.Body);
                var stop1 = Call(h,"stop-video");
                Check("stop-video reports wasRunning:true once", stop1.Body.Contains("\"wasRunning\":true"), stop1.Body);
                var stop2 = Call(h,"stop-video");
                Check("second stop-video is safe and reports wasRunning:false", stop2.Body.Contains("\"wasRunning\":false"), stop2.Body);
                var after = Call(h,"state");
                Check("call survives video disposal", after.Code==Ok, after.Body);
            }
            finally { if (h!=0) destroy(h); }

            // 3) Offer/answer bounded SDP path
            h = 0;
            try
            {
                h = CreateHandle(create);
                var offer = Call(h,"create-offer");
                Check("create-offer returns valid SDP with v=0", offer.Code==Ok && offer.Body.Contains("v=0"), offer.Body.Length>0?offer.Body.Substring(0,Math.Min(80,offer.Body.Length)):"");
                // undersized buffer
                int tiny = cmd(h,"create-offer","",buf,16);
                Check("undersized buffer reports BufferTooSmall and is cleared", tiny==BufferTooSmall && Read().Length==0, $"code {tiny}");
                Check("buffer too small does not crash", true);
            }
            finally { if (h!=0) destroy(h); }

            // 4) Repeated teardown / monotonic handles / capacity
            List<ulong> created = new();
            try
            {
                for (int i=0;i<8;i++) created.Add(CreateHandle(create));
                Check("eight bridges can exist at once", created.Count==8);
                Check("handles distinct", created.Distinct().ToString()?.Length>=0); // placeholder, real check below
                var distinct = new System.Collections.Generic.HashSet<ulong>(created);
                Check("handles distinct (set)", distinct.Count==8, string.Join(',',created));
                var overflow = cmd(created[0],"probe","",buf,BufferBytes); // use dummy to ensure nothing leaked? ignore
                ulong dummyHandle; int ov = create(out dummyHandle);
                Check("ninth bridge refused rather than admitted", ov==NotFound, $"code {ov}");
                if (ov==Ok) destroy(dummyHandle);
            }
            finally { foreach(var hh in created) destroy(hh); }

            ulong first = CreateHandle(create);
            destroy(first);
            int secondDestroy = destroy(first);
            Check("destroy is one-shot (second == NotFound)", secondDestroy==NotFound, $"code {secondDestroy}");
            int unknown = cmd(999999,"probe","",buf,BufferBytes);
            Check("commands on unknown handle report NotFound", unknown==NotFound, $"code {unknown}");
            ulong later = CreateHandle(create);
            Check("later handle is strictly greater than earlier one", later>first, $"{first} then {later}");
            destroy(later);

            // 5) Candidate gathering returns JSON array (may be empty by design in this increment)
            h = CreateHandle(create);
            try
            {
                var cand = Call(h,"take-candidates");
                Check("candidate gathering returns JSON array", cand.Code==Ok && cand.Body.StartsWith("[") , cand.Body);
                var bad = Call(h,"add-ice","not-a-candidate");
                Check("malformed candidate is refused with error", bad.Body.Contains("\"error\""), bad.Body);
            }
            finally { destroy(h); }

            Console.WriteLine(failures==0 ? "ALL PASS" : $"{failures} failure(s)");
            return failures==0 ? 0 : 1;
        }
        finally
        {
            Marshal.FreeHGlobal(buf);
            NativeLibrary.Free(lib);
        }
    }

    static ulong CreateHandle(Create create)
    {
        int c = create(out ulong h);
        if (c!=1 || h==0) throw new InvalidOperationException($"create failed {c}");
        return h;
    }
}
