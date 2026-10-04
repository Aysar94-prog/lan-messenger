using System.Globalization;
using System.Text;

namespace LanMessenger;

// The video media adapter used until a production WebRTC build is available. It speaks the real
// wire syntax -- CallVideoProtocol.ValidSdp accepts the SDP it produces -- and it enforces the real
// capture gate, real generation staleness and real dispose semantics, so the coordinator above it can
// be exercised end to end without a camera or a native library.
//
// It is NOT a video implementation: there is no encoder, no transport and no picture. It exists so
// that negotiation, consent, generation binding, deadlines and cleanup can be tested against the
// exact same code paths production will use, rather than against a mock of those paths.
//
// This type ships in the application but is only ever constructed for the fake-media path; the
// x64 production build has no native WebRTC package to bind against yet, and pretending otherwise
// would mean shipping the test-only native package, which the plan forbids.
public sealed class FakeCallVideoMedia : ICallVideoMedia
{
    readonly object gate = new();
    readonly List<string> log = new();
    ICallVideoMediaListener? listener;
    long activeGeneration;
    Func<bool>? captureGate;
    bool cameraRunning, initialized, disposed;
    int localSinks, remoteSinks;
    CallVideoDiagnostics.Snapshot diagnostics = CallVideoDiagnostics.Snapshot.Unavailable();

    // Fake ICE gathering, emitted only if the owner asks for it. Off by default: automatic ICE on
    // every test would add frames to every assertion.
    public int IceCandidatesToEmit { get; set; }
    public bool FrontCamera { get; private set; } = true;
    public bool EmitReadyOnInitialize { get; set; }

    public IReadOnlyList<string> Log { get { lock (gate) return log.ToArray(); } }
    public bool CameraRunning { get { lock (gate) return cameraRunning; } }
    public bool Disposed { get { lock (gate) return disposed; } }
    public int LocalSinks { get { lock (gate) return localSinks; } }
    public int RemoteSinks { get { lock (gate) return remoteSinks; } }
    public long ActiveGeneration { get { lock (gate) return activeGeneration; } }

    void Record(string entry) { lock (gate) log.Add(entry); }

    public void SetDiagnostics(CallVideoDiagnostics.Snapshot snapshot) { lock (gate) diagnostics = snapshot; }
    public CallVideoDiagnostics.Snapshot Diagnostics() { lock (gate) return diagnostics; }

    public void Initialize(long generation, Func<bool> captureGate)
    {
        lock (gate)
        {
            if (disposed) throw new InvalidOperationException("Video media disposed");
            activeGeneration = generation; this.captureGate = captureGate;
            initialized = true; cameraRunning = false;
            localSinks = remoteSinks = 0;
            Record($"init:{generation}");
        }
        // No capture here, ever. A camera opening during negotiation would mean a peer could turn on
        // this machine's camera by sending an OFFER.
        if (EmitReadyOnInitialize) listener?.OnReady(generation);
        for (int i = 0; i < IceCandidatesToEmit; i++)
            listener?.OnIce(generation, Candidate($"fake-host-{i}"), "0", 0);
    }

    public string CreateOffer(long generation)
    {
        lock (gate)
        {
            Require(generation);
            Record($"offer:{generation}");
            return VideoSdp(generation, "actpass", "o");
        }
    }

    public string CreateAnswer(long generation, string sdp)
    {
        lock (gate)
        {
            Require(generation);
            Record($"answer:{generation}");
            return VideoSdp(generation, "active", "a");
        }
    }

    public void SetRemoteAnswer(long generation, string sdp)
    {
        lock (gate)
        {
            Require(generation);
            Record($"remote-answer:{generation}");
        }
    }

    public void AddIce(long generation, string candidate, string mid, int index)
    {
        lock (gate)
        {
            Require(generation);
            Record($"ice:{generation}");
        }
    }

    public void StartCamera(long generation)
    {
        lock (gate)
        {
            Require(generation);
            // The gate is consulted HERE, at actual acquisition, not when the user pressed the
            // button. Anything that could have changed since -- permission revocation, the app
            // moving to the background, the call ending -- is caught at this point.
            if (captureGate == null || !captureGate()) throw new InvalidOperationException("Capture not permitted");
            cameraRunning = true;
            Record($"camera-on:{generation}");
        }
    }

    public void StopCamera(long generation)
    {
        lock (gate)
        {
            if (generation != activeGeneration) return; // stale generation: leave the live one alone
            cameraRunning = false;
            Record($"camera-off:{generation}");
        }
    }

    public void SwitchCamera(long generation)
    {
        lock (gate)
        {
            Require(generation);
            FrontCamera = !FrontCamera;
            Record($"switch:{generation}:{(FrontCamera ? "front" : "rear")}");
        }
    }

    public bool LocalMirror => true;

    public void AttachLocal(ICallVideoFrameSink? sink) { lock (gate) localSinks += sink == null ? -1 : 1; Record($"attach-local:{sink != null}"); }
    public void DetachLocal(ICallVideoFrameSink? sink) { lock (gate) localSinks += sink == null ? 1 : -1; Record("detach-local"); }
    public void AttachRemote(ICallVideoFrameSink? sink) { lock (gate) remoteSinks += sink == null ? -1 : 1; Record($"attach-remote:{sink != null}"); }
    public void DetachRemote(ICallVideoFrameSink? sink) { lock (gate) remoteSinks += sink == null ? 1 : -1; Record("detach-remote"); }

    public ICallVideoRendererLease? AcquireRendererLease() => null;

    public void SetListener(ICallVideoMediaListener? value) { lock (gate) listener = value; }

    public void Dispose(long generation)
    {
        lock (gate)
        {
            // A stale generation must never tear down the live connection.
            if (generation != activeGeneration) return;
            cameraRunning = false; initialized = false;
            localSinks = remoteSinks = 0;
            Record($"dispose:{generation}");
        }
    }

    public void Dispose()
    {
        lock (gate) { disposed = true; cameraRunning = false; Record("dispose-all"); }
    }

    /// Test hook: stand in for the transport becoming ready. In production this arrives from the
    /// peer connection's own state machine; here it is driven explicitly so a test can prove that
    /// neither side's readiness alone is enough to open a camera.
    public void ReportReadyForTest()
    {
        var generation = ActiveGeneration;
        if (generation != 0) listener?.OnReady(generation);
    }

    /// Test hook: stand in for the device/OS reporting that capture stopped underneath us.
    public void ReportCameraStopped(long generation)
    {
        lock (gate) { if (generation != activeGeneration) return; cameraRunning = false; }
        listener?.OnCameraStopped(generation);
    }

    /// Test hook: stand in for a fatal native failure at this generation.
    public void ReportError(long generation, string message) => listener?.OnError(generation, message);

    void Require(long generation)
    {
        if (disposed) throw new InvalidOperationException("Video media disposed");
        if (!initialized) throw new InvalidOperationException("Video media not initialized");
        if (generation != activeGeneration)
            throw new InvalidOperationException($"Stale video generation {generation}");
    }

    static string Candidate(string foundation) =>
        $"candidate:{foundation} 1 udp 2130706431 10.0.0.1 50000 typ host generation 0 ufrag abcd network 1";

    // Built to satisfy CallVideoProtocol.ValidSdp(video: true) exactly: one m-line, UDP/TLS/RTP/SAVPF,
    // payload types that are all <= 127 and all named by an a=rtpmap line, VP8/90000 present, no
    // rtpmap outside {VP8, rtx, red, ulpfec}, and a sha-256 fingerprint.
    internal static string VideoSdp(long generation, string setup, string direction) =>
        "v=0\r\n"
        + "o=- " + (700000000000000000L + generation).ToString(CultureInfo.InvariantCulture) + " 2 IN IP4 127.0.0.1\r\n"
        + "s=-\r\nt=0 0\r\n"
        + "a=group:BUNDLE 0\r\na=msid-semantic: WMS\r\n"
        + "m=video 9 UDP/TLS/RTP/SAVPF 96 97\r\n"
        + "c=IN IP4 0.0.0.0\r\na=rtcp:9 IN IP4 0.0.0.0\r\n"
        + "a=ice-ufrag:" + Ufrag(generation) + "\r\n"
        + "a=ice-pwd:" + Pwd(generation) + "\r\n"
        + "a=ice-options:trickle\r\n"
        + "a=fingerprint:sha-256 " + Fingerprint(generation) + "\r\n"
        + "a=setup:" + setup + "\r\na=mid:0\r\n"
        + "a=" + direction + "recv\r\na=rtcp-mux\r\n"
        + "a=rtpmap:96 VP8/90000\r\n"
        + "a=rtcp-fb:96 nack\r\na=rtcp-fb:96 nack pli\r\na=rtcp-fb:96 goog-remb\r\n"
        + "a=rtpmap:97 rtx/90000\r\na=fmtp:97 apt=96\r\n";

    // The audio counterpart, for the existing G722 voice connection: one audio m-line, G722/8000, and
    // nothing else. Used to prove that a v1 or v2 *audio* frame still validates after video lands.
    internal static string AudioSdp(long generation, string setup, string direction) =>
        "v=0\r\n"
        + "o=- " + (600000000000000000L + generation).ToString(CultureInfo.InvariantCulture) + " 2 IN IP4 127.0.0.1\r\n"
        + "s=-\r\nt=0 0\r\n"
        + "a=group:BUNDLE 0\r\n"
        + "m=audio 9 UDP/TLS/RTP/SAVPF 9\r\n"
        + "c=IN IP4 0.0.0.0\r\na=rtcp:9 IN IP4 0.0.0.0\r\n"
        + "a=ice-ufrag:" + Ufrag(generation) + "\r\n"
        + "a=ice-pwd:" + Pwd(generation) + "\r\n"
        + "a=fingerprint:sha-256 " + Fingerprint(generation, 1) + "\r\n"
        + "a=setup:" + setup + "\r\na=mid:0\r\n"
        + "a=" + direction + "recv\r\na=rtcp-mux\r\n"
        + "a=rtpmap:9 G722/8000\r\n";

    static string Ufrag(long generation) => "u" + (generation & 0xFFFFF).ToString("x4", CultureInfo.InvariantCulture);
    static string Pwd(long generation) => "p" + ((generation * 7919) & 0xFFFFFFFF).ToString("x8", CultureInfo.InvariantCulture);

    // 32 hex bytes, colon separated -- exactly what the a=fingerprint:sha-256 check demands.
    static string Fingerprint(long generation, int salt = 0)
    {
        var sb = new StringBuilder(32 * 3 - 1);
        for (int i = 0; i < 32; i++)
        {
            if (i > 0) sb.Append(':');
            sb.Append(((i * 31 + generation * 17 + salt * 7) & 0xFF).ToString("X2", CultureInfo.InvariantCulture));
        }
        return sb.ToString();
    }
}
