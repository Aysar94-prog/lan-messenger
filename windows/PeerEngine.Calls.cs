using System.Net;
using System.Net.Sockets;

namespace LanMessenger;

// Voice calls: the CALLCONNECT handoff (outbound side) and the local-only call-history log entry.
// The inbound handoff lives in PeerEngine.cs's Receive() itself, right next to the ordinary
// HELLO/READY/trust handshake it shares. Mirrors android/src/net/lanmsg/chat/PeerEngine.java's
// equivalent additions (+CALLCONNECT protocol, +openCallConnection) exactly in spirit.
public sealed partial class PeerEngine
{
    void TrackCall(TcpClient client) { lock (networkGate) { if (!Running) { client.Dispose(); throw new IOException("Network is offline."); } activeCallClients.Add(client); } }
    void UntrackCall(TcpClient client) { lock (networkGate) activeCallClients.Remove(client); }

    // Opens a fresh authenticated connection to `peerId` and performs the CALLCONNECT handshake:
    // HELLO/READY (same mutual-TLS trust check as any other connection) followed by one
    // `LM4\tCALLCONNECT\t{callId}` line. Neither side replies to that line -- both switch the same
    // stream to CallSignaling's 4-byte-length+JSON framing immediately afterward, so the first real
    // call frame (INVITE) can never precede the connection that carries it.
    public async Task<Stream> OpenCallConnectionAsync(string peerId, string callId)
    {
        string host; int peerPort;
        lock (gate) { var p = peers[peerId]; host = p.Host; peerPort = p.Port; }
        var client = await Connect(host, peerPort);
        TrackCall(client);
        try
        {
            var tls = identity.Wrap(client.GetStream());
            await identity.Authenticate(tls, false);
            await Write(tls, Hello());
            var h = (await Read(tls)).Split('\t');
            if (!ValidHello(h) || h[2] != peerId) throw new IOException("Unexpected device at the other end.");
            var fingerprint = SecureIdentity.Remote(tls);
            RecordCertificate(peerId, fingerprint, SecureIdentity.RemotePublicKey(tls));
            if (!Trusted(peerId, fingerprint)) throw new IOException("Peer is not verified — verify before calling");
            var ready = await Read(tls);
            if (ready != "LM4\tREADY") throw new IOException("The other device refused the connection.");
            await Write(tls, $"LM4\tCALLCONNECT\t{callId}");
            tls.UseLongLivedTimeouts();
            return tls;
        }
        catch { client.Dispose(); UntrackCall(client); throw; }
    }

    // A call's caller/callee role, connect time and duration are already known identically on
    // both ends the moment the call ends -- neither side needs the other to say so. So this is a
    // purely local annotation: status is set to a terminal value from creation (never "Queued"),
    // which keeps it out of Deliver()'s retry loop (which only ever looks at Queued rows) forever
    // -- it is never sent to the peer, never acked, never retried. From/To are set so the existing
    // mine-vs-theirs bubble gate falls out for free, mirroring the Android call-log feature.
    public void AppendCallLog(string peerId, bool isCaller, bool connected, long durationMs)
    {
        var from = isCaller ? Id : peerId;
        var to = isCaller ? peerId : Id;
        var marker = CallLogMarker.Encode(isCaller, connected, durationMs);
        var m = new Message(Guid.NewGuid().ToString(), from, to, "", Now, "Delivered", "", marker, 0, "", "", false);
        lock (gate)
        {
            messages.Add(m);
            try { Save(); } catch { messages.Remove(m); return; }
        }
        Notify();
    }
}
