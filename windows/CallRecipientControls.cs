namespace LanMessenger;

// Pure presentation policy shared by CallView and the headless harness. Remote grants are already
// certificate-bound and freshness-filtered by PeerEngine; this final layer prevents controls from
// leaking into the callee/ringing/finished UI and keeps camera actions tied to an active video leg.
public static class CallRecipientControls
{
    public static int VisibleMask(bool isCaller, CallProtocol.State state, bool videoCapable, int confirmedMask, bool videoLive)
    {
        // REMOTE_* belongs to the v2 call grammar. A v1/voice-only peer must never get a control
        // that would either be rejected or, worse, look successful while doing nothing.
        if (!isCaller || state != CallProtocol.State.Connected || !videoCapable) return 0;
        int visible = confirmedMask & PeerEngine.TrustedRemoteSpeaker;
        if (videoLive) visible |= confirmedMask & PeerEngine.TrustedRemoteCamera;
        return visible;
    }
}
