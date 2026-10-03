using System.Runtime.InteropServices;

// R02 ABI smoke test only. No media connection, audio device, camera or network.
if (args.Length != 1 || !Environment.Is64BitProcess)
    throw new ArgumentException("An explicit x64 test DLL path is required.");
var library = NativeLibrary.Load(Path.GetFullPath(args[0]));
try
{
    var probe = Marshal.GetDelegateForFunctionPointer<Probe>(NativeLibrary.GetExport(library, "lm_probe_g722"));
    var buffer = Marshal.AllocHGlobal(4096);
    try
    {
        if (probe(IntPtr.Zero, 0) != -1 || probe(buffer, 1) != -1 || probe(buffer, 65537) != -1)
            throw new InvalidOperationException("Native ABI bound checks failed.");
        if (probe(buffer, 4096) != 1)
            throw new InvalidOperationException("G722 is absent from the native codec factory.");
        var codecs = Marshal.PtrToStringAnsi(buffer) ?? "";
        if (!codecs.Split(',').Contains("G722/8000"))
            throw new InvalidOperationException("Unexpected codec report.");
        Console.WriteLine($"PASS: net9 x64 C ABI; codec factory={codecs}; invalid-buffer checks=3");
    }
    finally { Marshal.FreeHGlobal(buffer); }
}
finally { NativeLibrary.Free(library); }

[UnmanagedFunctionPointer(CallingConvention.Cdecl)]
delegate int Probe(IntPtr output, int capacity);
