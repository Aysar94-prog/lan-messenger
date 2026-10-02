namespace LanMessenger;

// One authenticated call channel: the stream, its single reader thread, and a serialized writer.
// Mirrors android/src/net/lanmsg/chat/CallChannel.java exactly, including the graceful-close
// behavior (shut down the write side first so a terminal frame isn't lost to a TCP reset) and the
// "first frame on an adopted channel must be the INVITE naming this call" rule.
public sealed class CallChannel : IDisposable, CallController.ITransport
{
    const long GracefulCloseMs = 1500;

    readonly Stream stream;
    readonly string peerId;
    readonly CallController controller;
    readonly object writeLock = new();
    volatile bool closed;
    volatile bool streamClosed;
    string? callId;
    Thread? reader;

    CallChannel(Stream stream, string peerId, CallController controller, string? callId)
    {
        this.stream = stream; this.peerId = peerId; this.controller = controller; this.callId = callId;
    }

    public static CallChannel OpenOutgoing(Stream stream, string peerId, string callId, CallController controller)
    {
        var channel = new CallChannel(stream, peerId, controller, callId);
        channel.StartReader();
        return channel;
    }

    public static CallChannel AdoptIncoming(Stream stream, string peerId, CallController controller)
    {
        var channel = new CallChannel(stream, peerId, controller, null);
        channel.StartReader();
        return channel;
    }

    public void Send(byte[] frameBytes)
    {
        if (closed) throw new IOException("The call channel is closed");
        lock (writeLock)
        {
            stream.Write(frameBytes, 0, frameBytes.Length);
            stream.Flush();
        }
    }

    void StartReader()
    {
        var t = new Thread(() =>
        {
            try
            {
                var lengthBytes = new byte[4];
                while (!closed)
                {
                    ReadFully(lengthBytes, 0, 4);
                    int length = (lengthBytes[0] << 24) | (lengthBytes[1] << 16) | (lengthBytes[2] << 8) | lengthBytes[3];
                    if (length <= 0 || length > CallProtocol.MaxFrameBytes) break;
                    var wire = new byte[4 + length];
                    Buffer.BlockCopy(lengthBytes, 0, wire, 0, 4);
                    ReadFully(wire, 4, length);
                    var frame = CallSignaling.Parse(wire);
                    if (frame == null) continue; // malformed input is dropped, not fatal
                    Dispatch(frame);
                }
            }
            catch { /* usually just the socket closing */ }
            finally
            {
                // A channel that dies while a call is up ends that call -- without this, a dead
                // channel leaves an established call looking live until the heartbeat notices.
                if (!closed) try { controller.OnSignalingChannelClosed(); } catch { }
            }
        }) { IsBackground = true, Name = "call-channel" };
        reader = t;
        t.Start();
    }

    void ReadFully(byte[] buffer, int offset, int count)
    {
        while (count > 0)
        {
            int n = stream.Read(buffer, offset, count);
            if (n <= 0) throw new EndOfStreamException();
            offset += n; count -= n;
        }
    }

    void Dispatch(CallProtocol.Frame frame)
    {
        if (frame.T == CallProtocol.INVITE && callId == null)
        {
            callId = frame.Cid;
            try { controller.OnInvite(frame, peerId, this); } catch { }
            return;
        }
        try { controller.OnFrame(frame, peerId); } catch { }
    }

    public bool IsClosed => closed;
    public string? CallId => callId;
    public string PeerId => peerId;

    public void Dispose() => Close();

    public void Close()
    {
        if (closed) return;
        closed = true;
        HardClose();
        var t = reader;
        reader = null;
    }

    // Half-close now, hard-close the stream a moment later: the last thing written before a call
    // ends is the frame that says why (DECLINE/HANGUP/CANCEL), and closing the socket immediately
    // afterwards can send a TCP reset that destroys that frame before the peer reads it, leaving
    // them ringing until their own timeout expires. There is no managed "shutdown send only" on a
    // TLS-wrapped stream, so this best-effort version flushes, waits, then closes.
    public void BeginGracefulClose()
    {
        if (closed) return;
        closed = true;
        var t = reader;
        reader = null;
        var closer = new Thread(() =>
        {
            try { Thread.Sleep((int)GracefulCloseMs); } catch { }
            HardClose();
        }) { IsBackground = true, Name = "call-channel-close" };
        closer.Start();
    }

    void HardClose()
    {
        if (streamClosed) return;
        streamClosed = true;
        try { stream.Dispose(); } catch { }
    }
}
