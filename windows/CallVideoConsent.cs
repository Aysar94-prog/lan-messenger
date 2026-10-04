namespace LanMessenger;

// A04: call-bound video consent, independent of microphone mute/voice state. Owns no window, camera,
// socket or media object. All mutations are serialized; the controller must separately authenticate
// and validate wire input.
//
// Ported line-for-line from android/src/net/lanmsg/chat/CallVideoConsent.java. This class decides
// *policy* only -- whether video may exist at all for this call -- and deliberately holds nothing
// that could be used to capture: every entry point that could lead to a camera takes a fresh
// `eligible`/`permission` boolean supplied by the owner, never a cached "the user said yes".
public sealed class CallVideoConsent
{
    public enum Result { Ready, Voice, Prompt, Declined, Ignored, Denied, Busy, Unsupported }
    public enum Phase { Voice, Waiting, Negotiating, Video, Ended }

    readonly string callId;
    readonly bool capable, caller, invitedVideo;
    bool initialAnswered, connected, ended, localConsent, peerConsent;
    bool cameraWanted, mediaReady, remoteRequest, remoteCamera;
    string? request;
    long generation, lastGeneration, remoteRevision = -1;
    readonly HashSet<string> usedRequests = new(StringComparer.Ordinal);
    const int MaxRequests = 128;

    public CallVideoConsent(string callId, bool capable, bool caller, bool invitedVideo)
    {
        if (!CallProtocol.ValidCallId(callId)) throw new ArgumentException("Invalid call ID");
        if (invitedVideo && !capable) throw new ArgumentException("Video needs capability");
        this.callId = callId; this.capable = capable; this.caller = caller;
        this.invitedVideo = invitedVideo;
        // An outgoing video invitation is an explicit local action; an incoming invitation expresses
        // only the PEER's consent, never local capture authorization.
        localConsent = invitedVideo && caller;
        peerConsent = invitedVideo && !caller;
        cameraWanted = localConsent;
    }

    bool Live(string expected) => !ended && callId == expected;

    public bool IsLive(string expected) { lock (this) return Live(expected); }

    public Result AcceptInitial(string expected, bool video, bool eligible)
    {
        lock (this)
        {
            if (!Live(expected) || caller || initialAnswered) return Result.Ignored;
            if (video && (!capable || !invitedVideo)) return Result.Unsupported;
            if (video && !eligible) return Result.Denied;
            initialAnswered = true;
            if (!video) { ClearVideo(); return Result.Voice; }
            localConsent = peerConsent = cameraWanted = true;
            request = callId;
            usedRequests.Add(request);
            return Result.Ready;
        }
    }

    public Result PeerAnswered(string expected, bool video)
    {
        lock (this)
        {
            if (!Live(expected) || !caller || initialAnswered) return Result.Ignored;
            if (video && (!capable || !invitedVideo)) return Result.Unsupported;
            initialAnswered = true;
            if (!video) { ClearVideo(); return Result.Voice; }
            peerConsent = true; request = callId;
            usedRequests.Add(request);
            return Result.Ready;
        }
    }

    /// Explicit trusted initial video acceptance WITHOUT authorizing local capture. The controller
    /// alone selects this path, and only after checking the certificate-bound trust grant.
    public Result AcceptInitialReceiveOnly(string expected)
    {
        lock (this)
        {
            if (!Live(expected) || caller || initialAnswered) return Result.Ignored;
            if (!capable || !invitedVideo) return Result.Unsupported;
            initialAnswered = true;
            localConsent = peerConsent = true;
            cameraWanted = false;
            request = callId;
            usedRequests.Add(request);
            return Result.Ready;
        }
    }

    /// Sending the acceptance failed before audio connected: permit an explicit retry/voice answer
    /// instead of stranding the user in a call they can never answer.
    public void RollbackInitialAnswer(string expected)
    {
        lock (this)
        {
            if (!Live(expected) || connected || caller) return;
            initialAnswered = false; ClearVideo(); peerConsent = invitedVideo;
        }
    }

    public void SetConnected(string expected, bool value)
    {
        lock (this)
        {
            if (!Live(expected)) return;
            connected = value;
            if (!value) ClearVideo();
        }
    }

    /// `eligible` is a fresh permission/hardware/foreground check, not cached consent.
    public Result RequestUpgrade(string expected, string id, bool eligible)
    {
        lock (this)
        {
            if (!Live(expected) || !connected || !CallProtocol.ValidCallId(id)) return Result.Ignored;
            if (!capable) return Result.Unsupported;
            if (!eligible) return Result.Denied;
            if (request != null) return Result.Busy;
            if (usedRequests.Contains(id)) return Result.Ignored;
            if (usedRequests.Count >= MaxRequests) return Result.Unsupported;
            usedRequests.Add(id);
            request = id; remoteRequest = false;
            localConsent = cameraWanted = true; peerConsent = false;
            return Result.Ready;
        }
    }

    /// A peer invitation is only a PROMPT -- never an acceptance. When both ends request video at
    /// once the lower UUID wins, so the outcome does not depend on who happened to be faster, and
    /// the losing side's own in-flight request is torn down rather than left to resolve itself.
    public Result ReceiveRequest(string expected, string id)
    {
        lock (this)
        {
            if (!Live(expected) || !connected || !CallProtocol.ValidCallId(id)) return Result.Ignored;
            if (!capable) return Result.Unsupported;
            if (generation != 0) return Result.Busy;
            if (usedRequests.Contains(id) && id != request) return Result.Ignored;
            if (usedRequests.Count >= MaxRequests && !usedRequests.Contains(id)) return Result.Unsupported;
            if (request != null)
            {
                if (request == id) return Result.Ignored;
                if (string.CompareOrdinal(request, id) < 0) return Result.Declined;
                // A local action for the losing UUID must not silently accept the winner.
                ClearVideo();
            }
            request = id; remoteRequest = true; peerConsent = true;
            usedRequests.Add(id);
            localConsent = cameraWanted = false;
            return Result.Prompt;
        }
    }

    public Result AcceptUpgrade(string expected, string id, bool eligible)
    {
        lock (this)
        {
            if (!Live(expected) || !connected || request == null || request != id
                || !remoteRequest || localConsent) return Result.Ignored;
            if (!eligible) return Result.Denied;
            localConsent = cameraWanted = true;
            return Result.Ready;
        }
    }

    public Result PeerAccepted(string expected, string id)
    {
        lock (this)
        {
            if (!Live(expected) || request == null || request != id || remoteRequest
                || !localConsent || peerConsent) return Result.Ignored;
            peerConsent = true;
            return Result.Ready;
        }
    }

    public Result DeclineUpgrade(string expected, string id)
    {
        lock (this)
        {
            if (!Live(expected) || request == null || request != id) return Result.Ignored;
            ClearVideo(); return Result.Declined;
        }
    }

    /// The caller allocates the generation; the callee admits only an authenticated caller's new one.
    public bool AuthorizeGeneration(string expected, string id, long value)
    {
        lock (this)
        {
            if (!CanAuthorizeGeneration(expected, id, value)) return false;
            generation = lastGeneration = value;
            mediaReady = false; remoteRevision = -1; remoteCamera = false;
            return true;
        }
    }

    public bool CanAuthorizeGeneration(string expected, string id, long value)
    {
        lock (this)
        {
            return Live(expected) && connected && localConsent && peerConsent && request != null
                && request == id && generation == 0 && value >= 2 && value > lastGeneration;
        }
    }

    public bool CanPeerAccept(string expected, string id)
    {
        lock (this)
        {
            return Live(expected) && request != null && request == id && !remoteRequest
                && localConsent && !peerConsent && generation == 0;
        }
    }

    public bool MarkMediaReady(string expected, long value)
    {
        lock (this)
        {
            if (!Live(expected) || generation == 0 || generation != value) return false;
            mediaReady = true; return true;
        }
    }

    /// Re-evaluated at the ACTUAL camera acquisition, not only at button/permission time, so a
    /// consent decision made seconds earlier cannot by itself open a camera.
    public bool CanCapture(string expected, bool permission, bool cameraAvailable, bool foreground)
    {
        lock (this)
        {
            return Live(expected) && connected && capable && localConsent && peerConsent
                && request != null && generation >= 2 && mediaReady && cameraWanted
                && permission && cameraAvailable && foreground;
        }
    }

    public void CameraOff(string expected) { lock (this) if (Live(expected)) cameraWanted = false; }

    public Result CameraOn(string expected, bool eligible)
    {
        lock (this)
        {
            if (!Live(expected) || !connected || !mediaReady || generation < 2
                || !localConsent || !peerConsent) return Result.Ignored;
            if (!eligible) return Result.Denied;
            if (cameraWanted) return Result.Busy;
            cameraWanted = true; return Result.Ready;
        }
    }

    public bool RemoteCameraState(string expected, long value, long revision, bool on)
    {
        lock (this)
        {
            if (!CanRemoteCameraState(expected, value, revision)) return false;
            remoteRevision = revision; remoteCamera = on;
            return true; // Never changes local consent or cameraWanted.
        }
    }

    public bool CanRemoteCameraState(string expected, long value, long revision)
    {
        lock (this)
        {
            return Live(expected) && generation != 0 && value == generation && revision >= 0 && revision > remoteRevision;
        }
    }

    /// Backgrounding or a revoked permission stops the camera. Turning it back on needs another
    /// explicit local action.
    public void RevokeCapture(string expected) => CameraOff(expected);

    /// A video failure or timeout never modifies the call's audio state.
    public bool FailVideo(string expected, long value)
    {
        lock (this)
        {
            if (!Live(expected) || generation == 0 || generation != value) return false;
            ClearVideo(); return true;
        }
    }

    public void End() { lock (this) { ended = true; connected = false; ClearVideo(); } }

    public string? RequestId { get { lock (this) return request; } }
    public long Generation { get { lock (this) return generation; } }
    public bool RemoteCameraOn { get { lock (this) return remoteCamera; } }
    public bool RemoteRequestPending { get { lock (this) return remoteRequest && request != null && generation == 0; } }

    public Phase CurrentPhase
    {
        get
        {
            lock (this)
            {
                if (ended) return Phase.Ended;
                if (generation > 0) return mediaReady ? Phase.Video : Phase.Negotiating;
                return request == null ? Phase.Voice : Phase.Waiting;
            }
        }
    }

    void ClearVideo()
    {
        request = null; generation = 0; remoteRevision = -1;
        localConsent = peerConsent = cameraWanted = mediaReady = remoteRequest = remoteCamera = false;
    }
}
