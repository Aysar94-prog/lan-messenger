// Managed harness for the WVC-05 production native bridge.
//
//   NativeBridge <path-to-LanMessenger.WebRtc.Native.dll>
//
// Exit 0 when every ABI-contract check passes, 1 when any check fails, 2 when the bridge cannot
// be loaded at all. This is an offline check: it opens no device and contacts no peer.
if (args.Length != 1)
{
    Console.Error.WriteLine("usage: NativeBridge <path-to-LanMessenger.WebRtc.Native.dll>");
    return 2;
}
return BridgeCheck.Run(args[0]);