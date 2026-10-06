namespace LanMessenger;

// Immutable call snapshot for thread-safe UI consumption, mirroring CallSession.java.
public sealed class CallSession
{
    public string CallId { get; }
    public string PeerId { get; }
    public CallProtocol.State State { get; }
    public bool IsCaller { get; }
    public bool Muted { get; }
    public string AudioRoute { get; }
    public CallProtocol.Quality Quality { get; }
    public long CreatedAtMs { get; }
    public long ConnectedAtMs { get; }
    public long DurationMs { get; }
    public CallProtocol.EndReason? EndReason { get; }
    public string? FailureReason { get; }
    // A02b. VideoCapable records that BOTH peers proved they speak v2 for this call; it is not "this
    // machine has a camera". InvitedVideo records what the caller actually offered, and is the only
    // thing that lets a callee answer with video. Video is the live coordinator snapshot, or null when
    // the call has no video -- which includes a v2 call whose adapter could not be created.
    public bool VideoCapable { get; }
    public bool InvitedVideo { get; }
    public CallVideoCoordinator.Snapshot? Video { get; }

    public CallSession(string callId, string peerId, CallProtocol.State state, bool isCaller, bool muted,
        string audioRoute, CallProtocol.Quality quality, long createdAtMs, long connectedAtMs, long durationMs,
        CallProtocol.EndReason? endReason, bool videoCapable = false, bool invitedVideo = false,
        CallVideoCoordinator.Snapshot? video = null, string? failureReason = null)
    {
        CallId = callId; PeerId = peerId; State = state; IsCaller = isCaller; Muted = muted;
        AudioRoute = audioRoute; Quality = quality; CreatedAtMs = createdAtMs; ConnectedAtMs = connectedAtMs;
        DurationMs = durationMs; EndReason = endReason;
        VideoCapable = videoCapable; InvitedVideo = invitedVideo; Video = video;
        FailureReason = failureReason;
    }

    // A snapshot taken before ConnectedAtMs was set still reports the duration it had; once
    // connected, elapsed time is measured live from the actual connect moment, not from creation
    // (the Android equivalent fixed exactly this bug: a call answered 4s after ringing must read
    // 0:00, not 0:04).
    public long ElapsedMs(long nowMs)
    {
        long sinceConnect = ConnectedAtMs > 0 ? Math.Max(0, nowMs - ConnectedAtMs) : 0;
        return Math.Max(DurationMs, sinceConnect);
    }

    public sealed class Builder
    {
        public string CallId, PeerId;
        public CallProtocol.State State = CallProtocol.State.Idle;
        public bool IsCaller;
        public bool Muted;
        public string AudioRoute = "System";
        public CallProtocol.Quality Quality = CallProtocol.Quality.Unknown;
        public long CreatedAtMs;
        public long ConnectedAtMs;
        public long DurationMs;
        public CallProtocol.EndReason? EndReason;
        public string? FailureReason;
        public bool VideoCapable;
        public bool InvitedVideo;
        public CallVideoCoordinator.Snapshot? Video;

        public Builder(string callId, string peerId, bool isCaller, long nowMs)
        {
            CallId = callId; PeerId = peerId; IsCaller = isCaller; CreatedAtMs = nowMs;
        }

        public CallSession Snapshot() => new(CallId, PeerId, State, IsCaller, Muted, AudioRoute, Quality,
            CreatedAtMs, ConnectedAtMs, DurationMs, EndReason, VideoCapable, InvitedVideo, Video, FailureReason);

        public CallSession SnapshotWithDuration()
        {
            if (State == CallProtocol.State.Connected && ConnectedAtMs > 0)
                DurationMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - ConnectedAtMs;
            return Snapshot();
        }
    }
}
