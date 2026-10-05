using System;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;

internal sealed class AudioOnly
{
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate uint AbiVersion();
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate int Create(out ulong handle);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate int Destroy(ulong handle);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate int Command(ulong handle, [MarshalAs(UnmanagedType.LPUTF8Str)] string command, [MarshalAs(UnmanagedType.LPUTF8Str)] string payload, IntPtr output, int capacity);

    const int Ok = 1; const int BufferBytes = 131072;

    public static int Run(string dllPath)
    {
        IntPtr lib = NativeLibrary.Load(dllPath);
        IntPtr buf = Marshal.AllocHGlobal(BufferBytes);
        try
        {
            var abi = Marshal.GetDelegateForFunctionPointer<AbiVersion>(NativeLibrary.GetExport(lib, "lm_wr_abi_version"));
            var create = Marshal.GetDelegateForFunctionPointer<Create>(NativeLibrary.GetExport(lib, "lm_wr_create"));
            var destroy = Marshal.GetDelegateForFunctionPointer<Destroy>(NativeLibrary.GetExport(lib, "lm_wr_destroy"));
            var cmd = Marshal.GetDelegateForFunctionPointer<Command>(NativeLibrary.GetExport(lib, "lm_wr_command"));

            uint v = abi();
            Console.WriteLine($"ABI: 0x{v:X8}");
            for (int iteration = 0; iteration < 20; iteration++)
            {
                if (create(out ulong h) != Ok) return 1;
                try
                {
                    string Invoke(ulong handle, string action, string payload)
                    {
                        if (cmd(handle, action, payload, buf, BufferBytes) != Ok)
                            throw new InvalidOperationException(action + " failed");
                        return Marshal.PtrToStringUTF8(buf) ?? "";
                    }
                    string Sdp(string body)
                    {
                        using var json = JsonDocument.Parse(body);
                        string sdp = json.RootElement.GetProperty("sdp").GetString() ?? "";
                        if (!sdp.StartsWith("v=0") || !sdp.Contains("m=audio ") ||
                            !sdp.Contains("G722/8000") || sdp.Contains("m=video "))
                            throw new InvalidOperationException("Invalid audio-only SDP");
                        return sdp;
                    }
                    string offer = Sdp(Invoke(h, "create-offer", ""));
                    if (create(out ulong recipient) != Ok) return 1;
                    try
                    {
                        Invoke(recipient, "set-remote", offer);
                        Sdp(Invoke(recipient, "create-answer", ""));
                    }
                    finally { destroy(recipient); }
                    Console.WriteLine($"PASS audio offer/answer and teardown iteration {iteration + 1}");
                }
                finally { destroy(h); }
            }
            return 0;
        }
        catch (Exception error) { Console.WriteLine("FAIL " + error.Message); return 1; }
        finally { Marshal.FreeHGlobal(buf); NativeLibrary.Free(lib); }
    }
}
