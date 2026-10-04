namespace LanMessenger;

// A03: call-bound camera access identity. Permission is NOT video consent -- this type decides only
// whether the machine is currently willing and able to hand over a camera, and which call a pending
// answer belongs to.
//
// The Windows port keeps the whole request-identity protocol and drops only the Android-specific
// part: there is no OS permission dialog with a numeric request code on desktop Windows, so
// NextRequestCode has no counterpart here. What replaces it is the same three questions the caller
// already answers -- camera present, camera access currently granted, access permanently denied (the
// user switched the camera off for this app in Windows Settings, or no camera is plugged in).
//
// The identity protocol is the part worth keeping verbatim. A permission or device-change answer
// arrives asynchronously and may land after the call it was asked for has ended, so every completion
// is matched against a monotonically increasing token AND the live call id. Applying a late answer to
// whatever call happens to be current is how a machine ends up with a camera light on for a call that
// finished ten minutes ago.
public sealed class CallCameraPermission
{
    public enum Decision { Ready, Request, Denied, Unavailable, Stale, Busy }

    string? pendingCallId;
    long nextToken, pendingToken;

    public Decision Begin(string? expectedCallId, string? liveCallId,
        bool online, bool cameraAvailable, bool currentlyGranted, bool permanentlyDenied)
    {
        lock (this)
        {
            if (expectedCallId == null || expectedCallId != liveCallId || !online) return Decision.Stale;
            if (!cameraAvailable) return Decision.Unavailable;
            if (pendingCallId != null) return Decision.Busy;
            if (!currentlyGranted && permanentlyDenied) return Decision.Denied;
            if (nextToken == long.MaxValue) return Decision.Stale;
            pendingCallId = expectedCallId; pendingToken = ++nextToken;
            return currentlyGranted ? Decision.Ready : Decision.Request;
        }
    }

    public long Token { get { lock (this) return pendingToken; } }
    public bool HasPending { get { lock (this) return pendingCallId != null; } }
    public string? CallId { get { lock (this) return pendingCallId; } }

    public Decision Complete(long token, string? liveCallId, bool online, bool cameraAvailable, bool currentlyGranted)
    {
        lock (this)
        {
            if (pendingCallId == null || token != pendingToken) return Decision.Stale;
            bool current = online && pendingCallId == liveCallId;
            CancelCore();
            if (!current) return Decision.Stale;
            if (!cameraAvailable) return Decision.Unavailable;
            return currentlyGranted ? Decision.Ready : Decision.Denied;
        }
    }

    public void Reconcile(string? liveCallId, bool online)
    {
        lock (this)
        {
            if (!online || (pendingCallId != null && pendingCallId != liveCallId)) CancelCore();
        }
    }

    public void Cancel() { lock (this) CancelCore(); }

    void CancelCore() { pendingCallId = null; pendingToken = 0; }
}
