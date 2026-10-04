namespace LanMessenger;

// Shared call protocol: message types, state machine, frame format and admission rules.
// Mirrors android/src/net/lanmsg/chat/CallProtocol.java field-for-field and constant-for-constant
// -- this is what makes a Windows call interoperate with an Android one. See the voice-calls plan
// (.ai-planner/sessions/20260930-091333-43c139/planning/plan-v006.md) and its 2026-10-02 addendum.
public static class CallProtocol
{
    public const string
        INVITE = "INVITE", RINGING = "RINGING", ACCEPT = "ACCEPT", DECLINE = "DECLINE",
        BUSY = "BUSY", CANCEL = "CANCEL", OFFER = "OFFER", ANSWER = "ANSWER", ICE = "ICE",
        MEDIA_READY = "MEDIA_READY", HANGUP = "HANGUP", ERROR = "ERROR", PING = "PING", PONG = "PONG";

    // A02b v2 additions. REMOTE_SPEAKER/REMOTE_CAMERA are the recipient-control commands; the
    // earlier provisional plan names for them were never implemented on Android and must not be
    // invented here either (WVC-04). These are only ever legal inside a v2 call.
    public const string
        VIDEO_REQUEST = "VIDEO_REQUEST", VIDEO_ACCEPT = "VIDEO_ACCEPT", VIDEO_DECLINE = "VIDEO_DECLINE",
        VIDEO_STATE = "VIDEO_STATE", REMOTE_SPEAKER = "REMOTE_SPEAKER", REMOTE_CAMERA = "REMOTE_CAMERA";

    public enum State { Idle, OutgoingRinging, IncomingRinging, Connecting, Connected, Ending }
    public static bool Terminal(this State s) => s == State.Idle || s == State.Ending;
    public static bool Active(this State s) => s == State.Connecting || s == State.Connected;

    public enum EndReason
    {
        LocalHangup, RemoteHangup, Declined, BusyRemote, Canceled, TimeoutRinging, TimeoutMedia,
        SignalingLost, NetworkFailure, Offline, MediaError, LocalDecline, GlareResolved, EngineShutdown
    }

    public const int InvitationLimitCount = 5;
    public const long InvitationLimitWindowMs = 60_000;

    public const long TimeoutCapabilityMs = 10_000;
    public const long TimeoutRingingMs = 30_000;
    public const long TimeoutMediaSetupMs = 15_000;
    public const long HeartbeatIntervalMs = 5_000;
    public const long HeartbeatFailMs = 15_000;
    public const long RouteRecoveryMs = 3_000;
    public const long LocalTeardownTargetMs = 2_000;

    public const int MaxFrameBytes = 64 * 1024;
    public const int MaxSdpBytes = 48 * 1024;
    public const int MaxIceCandidates = 128;
    public const int MaxCandidateBytes = 4 * 1024;
    public const int MaxVideoRequests = 128;

    // The quality-indicator contract's real metric mapping/calibration is deferred (see the
    // plan addendum) — this enum exists so CallSession has somewhere to carry the value without
    // a later storage-shape change, same as the Android side's CallProtocol.Quality.
    public enum Quality { Unknown, Normal, Reduced }

    public sealed class Frame
    {
        public int V = 1;
        public string T = "";
        public string Cid = "";
        public long Seq;
        public long Gen;
        public Dictionary<string, object>? B;
    }

    // Canonical lowercase 8-4-4-4-12 UUID, matching Android's `UUID.fromString(id).toString()
    // .equals(id)`. Guid.TryParseExact("D") alone is laxer than the Java original -- it accepts
    // UPPERCASE hex -- so a cid the Android peer would reject could otherwise be admitted here and
    // the two ends would disagree about which call a frame belongs to. Round-tripping through
    // ToString("D") reproduces the Java rule exactly, for v1 as well as v2: both platforms have
    // always generated the lowercase form, so this can only ever reject malformed input.
    public static bool ValidCallId(string? id) =>
        id != null && Guid.TryParseExact(id, "D", out var g) && g.ToString("D") == id;

    // Evaluated on the machine RECEIVING the frame: `state`/`isCaller` are local. Returns the role
    // the sender must hold ("caller"/"callee"/"both"), or null to ignore the frame.
    public static string? AllowedSender(string type, State state, bool isCaller) => type switch
    {
        INVITE => state == State.Idle && !isCaller ? "caller" : null,
        RINGING => state == State.OutgoingRinging && isCaller ? "callee" : null,
        ACCEPT => state == State.OutgoingRinging && isCaller ? "callee" : null,
        BUSY => state == State.OutgoingRinging && isCaller ? "callee" : null,
        DECLINE => state is State.IncomingRinging or State.OutgoingRinging ? "both" : null,
        CANCEL => (state is State.IncomingRinging or State.Connecting) && !isCaller ? "caller" : null,
        OFFER => state == State.Connecting && !isCaller ? "caller" : null,
        ANSWER => state == State.Connecting && isCaller ? "callee" : null,
        ICE => state.Active() ? "both" : null,
        MEDIA_READY => state.Active() ? "both" : null,
        HANGUP => !state.Terminal() ? "both" : null,
        ERROR => state.Active() ? "both" : null,
        PING => state.Active() ? "both" : null,
        PONG => state.Active() ? "both" : null,
        // Recipient controls: the party that PLACED the call drives the other end's camera/speaker,
        // so the sender must hold the caller role, and only once the call is actually Connected.
        // Matches CallProtocol.allowedSender's REMOTE_* rows on Android exactly.
        //
        // VIDEO_REQUEST/ACCEPT/DECLINE/STATE deliberately have NO row here. On Android those frames
        // are intercepted before this table is consulted (CallController routes any frame whose type
        // starts with VIDEO_, or whose body says media=video, straight to CallVideoCoordinator), and
        // CallVideoCoordinator.permits() is the authority for them -- it checks the bound request id,
        // the exact active generation and the current consent, which a state/role table cannot
        // express. Either peer may legitimately request video, so a role table would be wrong anyway.
        REMOTE_SPEAKER => state == State.Connected ? "caller" : null,
        REMOTE_CAMERA => state == State.Connected ? "caller" : null,
        _ => null,
    };

    public static State? Transition(State current, string type) => type switch
    {
        INVITE => current == State.Idle ? State.IncomingRinging : null,
        RINGING => current == State.OutgoingRinging ? State.OutgoingRinging : null,
        ACCEPT => current is State.OutgoingRinging or State.IncomingRinging ? State.Connecting : null,
        DECLINE or BUSY => current is State.IncomingRinging or State.OutgoingRinging ? State.Ending : null,
        CANCEL => current is State.IncomingRinging or State.OutgoingRinging ? State.Ending : null,
        MEDIA_READY => current == State.Connecting ? State.Connected : current,
        HANGUP => !current.Terminal() ? State.Ending : null,
        ERROR => current.Active() ? State.Ending : null,
        _ => current,
    };

    // Resolve simultaneous dialing: the caller whose (localId+remoteId) sorts lexicographically
    // lower wins. Returns true if the local side wins.
    public static bool GlareWinner(string localId, string remoteId) =>
        string.CompareOrdinal(localId + remoteId, remoteId + localId) <= 0;

    public sealed class InvitationLimiter
    {
        readonly object gate = new();
        readonly Dictionary<string, Queue<long>> attempts = new();

        public bool Record(string peerId, long nowMs)
        {
            lock (gate)
            {
                if (!attempts.TryGetValue(peerId, out var q)) attempts[peerId] = q = new Queue<long>();
                long cutoff = nowMs - InvitationLimitWindowMs;
                while (q.Count > 0 && q.Peek() < cutoff) q.Dequeue();
                if (q.Count >= InvitationLimitCount) return false;
                q.Enqueue(nowMs);
                return true;
            }
        }

        public void Prune(long nowMs)
        {
            lock (gate)
            {
                long cutoff = nowMs - InvitationLimitWindowMs;
                foreach (var key in attempts.Keys.ToList())
                {
                    var q = attempts[key];
                    while (q.Count > 0 && q.Peek() < cutoff) q.Dequeue();
                    if (q.Count == 0) attempts.Remove(key);
                }
            }
        }

        public void Clear() { lock (gate) attempts.Clear(); }
    }
}
