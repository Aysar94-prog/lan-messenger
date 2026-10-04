namespace LanMessenger;

// Per-call authenticated envelope/replay boundary. The controller must check role and state
// (CallProtocol.AllowedSender) BEFORE passing permitted=true; this type then binds the frame to the
// authenticated peer, the call, the negotiated protocol version and a strictly increasing sender
// sequence.
//
// The ordering rule is the whole point: a rejected frame must NOT refresh heartbeat/deadlines and
// must NOT consume the accepted sequence, so a peer cannot keep a dead call alive -- or push the
// real sequence forward to hide a later genuine frame -- by replaying traffic that fails validation.
// Ported from android/src/net/lanmsg/chat/CallFrameAdmission.java.
public sealed class CallFrameAdmission
{
    readonly string callId;
    readonly string peerId;
    readonly int version;
    long lastAccepted;

    public CallFrameAdmission(string callId, string peerId, int version, long openingSequence)
    {
        if (!CallProtocol.ValidCallId(callId) || !CallProtocol.ValidCallId(peerId)
            || (version != 1 && version != 2) || openingSequence < 0)
            throw new ArgumentException("Invalid authenticated call binding");
        this.callId = callId; this.peerId = peerId; this.version = version;
        lastAccepted = openingSequence;
    }

    public bool Admit(CallProtocol.Frame? frame, string authenticatedPeer, bool permitted)
    {
        lock (this)
        {
            if (!permitted || frame == null) return false;
            if (peerId != authenticatedPeer) return false;
            if (callId != frame.Cid) return false;
            if (frame.V != version) return false;
            if (frame.T.Length == 0) return false;
            if (frame.Seq <= lastAccepted || frame.Seq <= 0) return false;
            // Only after every check above: a v2 frame's shape is validated last so that a malformed
            // body can never advance the sequence that later frames are compared against.
            if (version == 2 && !CallVideoProtocol.Valid(frame)) return false;
            lastAccepted = frame.Seq;
            return true;
        }
    }

    public long LastAcceptedSequence { get { lock (this) return lastAccepted; } }
}
