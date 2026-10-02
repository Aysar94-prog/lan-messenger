namespace LanMessenger;

// Abstract media adapter, mirroring ICallMedia.java. The controller only ever talks through this
// interface, never to SIPSorcery/RTCPeerConnection directly -- the same seam Android keeps between
// CallController and WebRtcCallMedia, so a fake implementation can drive the pure state-machine
// logic in tests without any real audio device or network.
public interface ICallMedia : IDisposable
{
    // Creates the local SDP offer (caller side). Must not capture audio before this call.
    Task<string> CreateOfferAsync();
    // Accepts the remote offer and creates the local SDP answer (callee side).
    Task<string> CreateAnswerAsync(string remoteOfferSdp);
    // Caller side: installs the callee's SDP answer.
    Task SetRemoteAnswerAsync(string remoteAnswerSdp);
    void AddRemoteIceCandidate(string candidate, string? sdpMid, int sdpMLineIndex);
    // Fired with (candidate, sdpMid, sdpMLineIndex) for each local ICE candidate as it's gathered.
    event Action<string, string, int>? OnLocalIceCandidate;
    // Fired once local+remote media is actually flowing both ways.
    event Action? OnMediaReady;
    event Action? OnMediaFailed;
    void SetMuted(bool muted);
    bool IsMuted { get; }
    CallStats GetStats();

    public interface IFactory
    {
        ICallMedia Create();
    }
}

public sealed class CallStats
{
    public long PacketsLost;
    public long PacketsReceived;
    public double JitterMs;
    public double RttMs;
    public bool Available;
}
