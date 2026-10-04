// Production native WebRTC bridge P/Invoke (WVC-07).
// Mirrors the ABI verified by the NativeBridge harness.

using System;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;

namespace LanMessenger.Windows
{
    internal sealed class CallVideoNativeBridge : IDisposable
    {
        private const int Ok = 1;
        private const int BadArgument = -1;
        private const int NotFound = -2;
        private const int BufferTooSmall = -3;
        private const int Internal = -4;
        private const int BufferCapacity = 131072;

        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        private delegate uint AbiVersion();
        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        private delegate int Create(out ulong handle);
        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        private delegate int Destroy(ulong handle);
        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        private delegate int CommandNative(ulong handle, [MarshalAs(UnmanagedType.LPUTF8Str)] string command,
            [MarshalAs(UnmanagedType.LPUTF8Str)] string payload, IntPtr output, int capacity);

        private readonly IntPtr _library;
        private readonly AbiVersion _abiVersion;
        private readonly Create _create;
        private readonly Destroy _destroy;
        private readonly CommandNative _commandNative;
        private readonly IntPtr _buffer;
        private bool _disposed;

        public static string? FindLatestBridgeDll()
        {
            try
            {
                string root = @"D:\LAN-Messenger\outputs\.build\video-media";
                if (!Directory.Exists(root)) return null;
                var dirs = Directory.GetDirectories(root)
                    .Where(d => Path.GetFileName(d).StartsWith("bridge-", StringComparison.OrdinalIgnoreCase))
                    .OrderByDescending(d => Directory.GetLastWriteTimeUtc(d))
                    .ToArray();
                foreach (var d in dirs)
                {
                    string dll = Path.Combine(d, "LanMessenger.WebRtc.Native.dll");
                    if (File.Exists(dll)) return dll;
                }
            }
            catch { }
            return null;
        }

        public CallVideoNativeBridge(string? dllPath = null)
        {
            if (!Environment.Is64BitProcess)
                throw new InvalidOperationException("x64 required for production WebRTC bridge");
            string path = dllPath ?? FindLatestBridgeDll()
                ?? throw new FileNotFoundException("LanMessenger.WebRtc.Native.dll not found under outputs/.build/video-media");
            string full = Path.GetFullPath(path);
            if (!File.Exists(full)) throw new FileNotFoundException(full);
            _library = NativeLibrary.Load(full);
            _abiVersion = Marshal.GetDelegateForFunctionPointer<AbiVersion>(
                NativeLibrary.GetExport(_library, "lm_wr_abi_version"));
            _create = Marshal.GetDelegateForFunctionPointer<Create>(
                NativeLibrary.GetExport(_library, "lm_wr_create"));
            _destroy = Marshal.GetDelegateForFunctionPointer<Destroy>(
                NativeLibrary.GetExport(_library, "lm_wr_destroy"));
            _commandNative = Marshal.GetDelegateForFunctionPointer<CommandNative>(
                NativeLibrary.GetExport(_library, "lm_wr_command"));
            _buffer = Marshal.AllocHGlobal(BufferCapacity);
            uint v = _abiVersion();
            uint expected = (1u << 16) | 0u;
            if (v != expected)
                throw new InvalidOperationException($"bridge ABI mismatch: got 0x{v:X8}, expected 0x{expected:X8}");
        }

        public ulong CreateBridge()
        {
            int code = _create(out ulong h);
            if (code != Ok || h == 0) throw new InvalidOperationException($"lm_wr_create failed: {code}");
            return h;
        }

        public void DestroyBridge(ulong handle)
        {
            if (handle == 0) return;
            _destroy(handle);
        }

        public (int Code, string Body) Command(ulong handle, string verb, string payload = "")
        {
            if (string.IsNullOrEmpty(verb)) return (BadArgument, "");
            int cap = BufferCapacity;
            int code = _commandNative(handle, verb, payload ?? "", _buffer, cap);
            if (code != Ok && code != BufferTooSmall && code != BadArgument && code != NotFound && code != Internal)
                return (code, "");
            string body = "";
            if (code == Ok && cap >= 1)
            {
                int len = 0;
                while (len < BufferCapacity - 1 && Marshal.ReadByte(_buffer, len) != 0) len++;
                if (len > 0)
                {
                    byte[] bytes = new byte[len];
                    Marshal.Copy(_buffer, bytes, 0, len);
                    body = Encoding.UTF8.GetString(bytes);
                }
            }
            return (code, body);
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            if (_buffer != IntPtr.Zero) Marshal.FreeHGlobal(_buffer);
            if (_library != IntPtr.Zero) NativeLibrary.Free(_library);
        }
    }
}
