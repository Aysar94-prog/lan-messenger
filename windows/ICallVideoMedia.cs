namespace LanMessenger;

// The separate video media seam. This is deliberately a *managed* interface with no native types in
// it: the proven SIPSorcery/G722 adapter in WebRtcCallMedia.cs keeps serving audio untouched, and
// video gets its own connection, its own generation counter and its own lifetime.
//
// The shape mirrors ICallMedia.Video on Android exactly, because the coordinator that drives it is a
// direct port. Two properties are load-bearing and must not be relaxed:
//
//   * Initialize() must NOT acquire or start a camera. Negotiation can begin while the user is
//     ringing the other side, and the consent object is what authorizes capture -- not the mere fact
//     that a peer connection was created.
//   * Dispose(generation) releases VIDEO ONLY. A video failure must leave a healthy audio call
//     connected; the reverse (tearing down audio because video failed) is exactly the bug the
//     contract forbids. Stale generations are ignored so a late callback cannot dispose the live one.
public interface ICallVideoMedia : IDisposable
{
    /// Initialize a video-only secured peer connection for this generation. Must not capture.
    void Initialize(long generation, Func<bool> captureGate);

    string CreateOffer(long generation);
    string CreateAnswer(long generation, string sdp);
    void SetRemoteAnswer(long generation, string sdp);
    void AddIce(long generation, string candidate, string mid, int index);

    /// Explicit local action. The capture gate is rechecked at the actual acquisition, not only at
    /// the moment the button was pressed.
    void StartCamera(long generation);
    void StopCamera(long generation);
    void SwitchCamera(long generation);

    void AttachLocal(ICallVideoFrameSink? sink);
    void DetachLocal(ICallVideoFrameSink? sink);
    void AttachRemote(ICallVideoFrameSink? sink);
    void DetachRemote(ICallVideoFrameSink? sink);

    /// Keeps the shared render root alive until the renderer has detached and released its surfaces.
    ICallVideoRendererLease? AcquireRendererLease();
    void SetListener(ICallVideoMediaListener? listener);

    /// Nonblocking snapshot; missing or stale measurements remain unavailable.
    CallVideoDiagnostics.Snapshot Diagnostics() => CallVideoDiagnostics.Snapshot.Unavailable();

    bool LocalMirror => true;

    /// Releases only video, never healthy audio. Stale generations are ignored.
    void Dispose(long generation);
}

public interface ICallVideoFrameSink
{
    /// A borrowed frame, valid only for the duration of this call. Never retain it.
    void OnFrame(object frame);
}

public interface ICallVideoMediaListener
{
    void OnIce(long generation, string candidate, string mid, int index);
    void OnReady(long generation);
    void OnError(long generation, string message);
    void OnCameraStopped(long generation) { }
}

public interface ICallVideoRendererLease : IDisposable
{
    object? SharedContext { get; }
}
