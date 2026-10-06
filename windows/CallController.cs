namespace LanMessenger;

// Main call controller: one instance per process, owned by the app shell (ChatWindow), mirroring
// android/src/net/lanmsg/chat/CallController.java's architecture and wire behavior. Coordinates
// signaling, state, media, admission policy and timers. Pure logic plus an injected ICallMedia --
// no direct SIPSorcery/audio-device dependency here, the same seam Android keeps.
public sealed class CallController
{
    public interface ITransport { void Send(byte[] frameBytes); }
    public interface ITransportFactory { ITransport Open(string callId); }
    public delegate void Callback(CallSession snapshot);

    readonly PeerEngine engine;
    readonly CallSettings settings;
    readonly CallProtocol.InvitationLimiter limiter = new();
    public ICallMedia.IFactory? MediaFactory;
    // The video adapter is a SEPARATE factory from the audio one, on purpose. Voice keeps running on
    // the proven SIPSorcery/G722 path untouched, and a video adapter that cannot be created -- or that
    // fails later -- leaves a healthy audio call connected. Returning null is a legitimate answer: the
    // call then negotiates as v2 audio-only, which is exactly what an ACCEPT carrying media=audio says.
    public Func<ICallVideoMedia?>? VideoMediaFactory;
    // Whether this build will answer a peer's capability probe. Mirrors PeerEngine.CallVideoSupport so
    // there is one switch, not two that can disagree.
    //
    // The getter is deliberately not the backing field: it refuses to report video unless a media
    // backend is actually installed. That turns "don't advertise a capability you cannot deliver"
    // from a convention someone has to remember into a property of the type -- there is no assignment
    // that produces a build claiming VP8 with nothing to serve it, which is the exact failure this
    // replaced (a plain auto-property defaulting to true, reachable before any factory existed).
    // Wiring the adapter therefore means installing the factory *and* setting this true; neither alone
    // is enough, and no ordering mistake can leave it half-enabled.
    public bool VideoEnabled
    {
        get => videoEnabled && VideoMediaFactory != null;
        set => videoEnabled = value;
    }
    bool videoEnabled;

    readonly object gate = new();
    CallSession.Builder? session;
    ICallMedia? media;
    long sequence;
    long negotiationGeneration;
    bool localMediaReadySent;

    // ── A02b v2 video state ───────────────────────────────────────────────────
    // 1 = the archived voice protocol, which every shipped build still speaks. 2 is only ever adopted
    // after the peer answered CALLCAPS on its own verified connection, so a v2 frame is never sent to
    // a peer that has not proven it can parse one.
    int protocolVersion = 1;
    CallFrameAdmission? frameAdmission;
    CallVideoConsent? videoConsent;
    CallVideoCoordinator? videoCoordinator;
    CallVideoActions? videoActions;
    ICallVideoFrameSink? localVideoSink, remoteVideoSink;
    bool invitedVideo;

    System.Threading.Timer? ringTimeoutTimer, mediaTimeoutTimer, heartbeatTimer;
    long lastInboundMs;
    ITransport? activeTransport;

    volatile Callback callback = _ => { };
    readonly List<Callback> listeners = new();

    // Why the current call ended, or null if nothing has failed yet. Set only by the three media
    // paths and by a failed answer. Reset when a new session starts, because a stale reason printed
    // against a later call's hang-up would be a lie in the log -- EndReason is written by every
    // session and this is not, so the two only mean the same thing within one call.
    public string? LastFailureReason { get; private set; }

    // Raised when a media-negotiation exception ends a call, with the cause. This exists because the
    // session is torn down inside the same lock that records the reason, so a snapshot published by
    // EndCallLocked cannot carry it: whoever needs the cause has to hear about the failure itself.
    public event Action<string, string>? MediaFaulted;

    public CallController(PeerEngine engine, CallSettings settings)
    {
        this.engine = engine; this.settings = settings;
        // Off by default. With no VideoMediaFactory installed the getter reports false whatever is
        // stored here, so this line documents intent rather than enabling anything on its own.
    }

    public void SetCallback(Callback cb) => callback = cb ?? (_ => { });
    public void AddListener(Callback cb) { if (cb != null) lock (gate) listeners.Add(cb); }
    public void RemoveListener(Callback cb) { if (cb != null) lock (gate) listeners.Remove(cb); }
    void NotifyCallback(CallSession snap)
    {
        try { callback(snap); } catch { }
        Callback[] copy; lock (gate) copy = listeners.ToArray();
        foreach (var l in copy) try { l(snap); } catch { }
    }

    public CallSession? Snapshot() { lock (gate) return session?.SnapshotWithDuration(); }
    public bool HasActive() { lock (gate) return session != null && !session.State.Terminal(); }
    public bool IsIdle() { lock (gate) return session == null || session.State == CallProtocol.State.Idle; }
    public string? ActivePeerId() { lock (gate) return session?.PeerId; }
    public bool CanCall() => MediaFactory != null;

    public void CloseTransport()
    {
        var t = activeTransport; activeTransport = null;
        if (t is IDisposable d) try { d.Dispose(); } catch { }
    }
    static void CloseTransportQuietly(ITransport t) { if (t is IDisposable d) try { d.Dispose(); } catch { } }

    // ── Outgoing call setup ────────────────────────────────────────────────────

    public string StartCall(string peerId, ITransportFactory factory) => StartCall(peerId, factory, false);

    /// Place a call. `inviteVideo` only means "offer video in the INVITE"; it is a request the callee
    /// may decline, and it never acquires a camera -- not here, not during the probe, not while the
    /// other side is still ringing.
    public string StartCall(string peerId, ITransportFactory factory, bool inviteVideo)
    {
        // The capability probe is a network transaction and runs before the controller lock, exactly as
        // on Android. Every failure -- including not being able to ask at all -- means plain v1 voice,
        // never a partially-negotiated v2 call.
        bool capable = VideoEnabled && engine.ProbeCallVideo(peerId);
        lock (gate)
        {
            if (session != null && !session.State.Terminal()) throw new IOException("Already in a call");
            var peer = engine.Peers.FirstOrDefault(p => p.Id == peerId) ?? throw new IOException("Peer not found");
            if (!peer.Trusted) throw new IOException("Peer is not verified — verify before calling");
            long now = PeerEngine.Now;
            if (!limiter.Record(peerId, now)) throw new IOException("Too many call attempts — wait before trying again");
            string callId = Guid.NewGuid().ToString();
            var opened = factory.Open(callId) ?? throw new IOException("Could not open a call channel");
            session = new CallSession.Builder(callId, peerId, true, now) { State = CallProtocol.State.OutgoingRinging };
            LastFailureReason = null;
            activeTransport = opened;
            negotiationGeneration = capable ? 1 : 0; sequence = 0; localMediaReadySent = false;
            protocolVersion = capable ? 2 : 1;
            frameAdmission = new CallFrameAdmission(callId, peerId, protocolVersion, 0);
            invitedVideo = capable && inviteVideo;
            videoConsent = capable ? new CallVideoConsent(callId, true, true, invitedVideo) : null;
            session.VideoCapable = capable;
            session.InvitedVideo = invitedVideo;
            BindVideoActions(callId);
            var invite = CallSignaling.Invite(callId, ++sequence, engine.Id, peerId);
            // A v1 INVITE keeps exactly caller/callee. Adding "media" to it would break the archived
            // 2.2.42 peer, so the key appears only when the peer proved it understands v2.
            if (capable) invite.B!["media"] = invitedVideo ? "video" : "audio";
            try { SendFrameTo(opened, invite); }
            catch
            {
                CloseVideoLocked();
                session = null; activeTransport = null; frameAdmission = null;
                CloseTransportQuietly(opened); throw;
            }
            ScheduleRingTimeout();
            var snap = session.Snapshot();
            NotifyCallback(snap);
            return callId;
        }
    }

    // ── Incoming call (first frame on an adopted channel) ──────────────────────

    public void OnInvite(CallProtocol.Frame frame, string peerId, ITransport transport)
    {
        lock (gate)
        {
            if (!CallProtocol.ValidCallId(frame.Cid)) { CloseTransportQuietly(transport); return; }
            // A v2 INVITE must satisfy the full v2 grammar before it is even considered, and this build
            // must have video enabled to answer one at all. A v1 INVITE is admitted exactly as before.
            if (frame.V != 1 && (frame.V != 2 || !VideoEnabled || !CallVideoProtocol.Valid(frame)))
            { CloseTransportQuietly(transport); return; }
            long now = PeerEngine.Now;
            if (!limiter.Record(peerId, now)) { SendBestEffort(transport, CallSignaling.Busy(frame.Cid, 1)); CloseTransportQuietly(transport); return; }
            if (session != null && !session.State.Terminal()) { SendBestEffort(transport, CallSignaling.Busy(frame.Cid, 1)); CloseTransportQuietly(transport); return; }
            var peer = engine.Peers.FirstOrDefault(p => p.Id == peerId);
            if (peer == null || !peer.Trusted) { CloseTransportQuietly(transport); return; } // silence: identity mismatch
            if (!settings.AllowIncomingCalls) { SendBestEffort(transport, CallSignaling.Decline(frame.Cid, 1)); CloseTransportQuietly(transport); return; }

            session = new CallSession.Builder(frame.Cid, peerId, false, now) { State = CallProtocol.State.IncomingRinging };
            LastFailureReason = null;
            activeTransport = transport;
            negotiationGeneration = frame.V == 2 ? 1 : 0; sequence = 0; localMediaReadySent = false;
            protocolVersion = frame.V;
            // The peer's own INVITE sequence is the opening sequence for admission: it has already been
            // authenticated and shape-checked above, and refusing to consume it would let the peer's very
            // next frame be replayed.
            frameAdmission = new CallFrameAdmission(frame.Cid, peerId, protocolVersion, frame.Seq);
            invitedVideo = frame.V == 2 && frame.B != null && frame.B.TryGetValue("media", out var m) && m is "video";
            videoConsent = frame.V == 2 ? new CallVideoConsent(frame.Cid, true, false, invitedVideo) : null;
            session.VideoCapable = frame.V == 2;
            session.InvitedVideo = invitedVideo;
            BindVideoActions(frame.Cid);
            lastInboundMs = now;
            try { SendFrameTo(transport, CallSignaling.Ringing(frame.Cid, ++sequence)); } catch { }
            ScheduleRingTimeout();
            NotifyCallback(session.Snapshot());
            // Trusted auto-answer authorizes answering, not bypassing camera eligibility: a trusted
            // video invitation is only accepted receive-only when this machine cannot currently capture.
            var mask = engine.TrustedCallMask(peerId);
            if (invitedVideo && (mask & PeerEngine.TrustedAutoAnswerVideo) != 0)
                _ = AcceptAsync(true, trusted: true);
            else if ((mask & PeerEngine.TrustedAutoAnswerVoice) != 0)
                _ = AcceptAsync();
        }
    }

    // ── Local user actions ─────────────────────────────────────────────────────

    public Task AcceptAsync() => AcceptAsync(false, trusted: false);

    /// Answer an incoming call, optionally with video. A video answer is a *request*: the camera is
    /// only acquired later, once audio is connected and both peers have agreed a generation. Video
    /// can always be declined by answering without it.
    public async Task AcceptAsync(bool video, bool trusted)
    {
        ITransport? t; string? peerId; bool isCaller;
        lock (gate)
        {
            if (session == null || session.State != CallProtocol.State.IncomingRinging) return;
            var callId = session.CallId;
            var wantVideo = video && videoConsent != null;
            if (video && videoConsent == null)
                throw new IOException("Video is not available for this call");
            if (videoConsent != null)
            {
                bool eligible = wantVideo && MayCaptureLocked(callId);
                // A trusted video auto-answer authorizes answering, not bypassing eligibility: without a
                // usable camera the call still connects, receive-only.
                var decision = trusted && wantVideo && !eligible
                    ? videoConsent.AcceptInitialReceiveOnly(callId)
                    : videoConsent.AcceptInitial(callId, wantVideo, eligible);
                if (decision is not (CallVideoConsent.Result.Ready or CallVideoConsent.Result.Voice))
                    throw new IOException("Camera is not available right now");
            }
            // The ACCEPT goes on the wire BEFORE the state moves to Connecting and before the ring
            // watchdog is cancelled. It used to be the other way round, with the send wrapped in
            // `try { } catch { }`: a send that failed still left the session in Connecting with no
            // watchdog of any kind -- the caller never learned the answer was not delivered and the
            // call could not be ended, because the only way out was a timer that no longer existed.
            // An accept is either on the wire or the call ends here, and while it still ends the
            // peer is told so it does not sit ringing at a stranger.
            var accept = CallSignaling.Accept(callId, ++sequence);
            if (protocolVersion == 2) accept.B!["media"] = wantVideo ? "video" : "audio";
            try { SendFrameTo(activeTransport, accept); }
            catch (Exception e)
            {
                LastFailureReason = e.Message;
                MediaFaulted?.Invoke(callId, LastFailureReason);
                EndCallLocked(CallProtocol.EndReason.NetworkFailure);
                throw;
            }
            session.State = CallProtocol.State.Connecting;
            t = activeTransport; peerId = session.PeerId; isCaller = session.IsCaller;
            CancelRingTimeout();
            ScheduleMediaTimeout();
            StartHeartbeat();
            NotifyCallback(session.Snapshot());
        }
        await StartMediaAsCalleeAsync();
    }

    public void Decline()
    {
        lock (gate)
        {
            if (session == null || session.State != CallProtocol.State.IncomingRinging) return;
            SendBestEffort(activeTransport, CallSignaling.Decline(session.CallId, ++sequence));
            EndCallLocked(CallProtocol.EndReason.LocalDecline);
        }
    }

    public void CancelOutgoing()
    {
        lock (gate)
        {
            if (session == null || session.State != CallProtocol.State.OutgoingRinging) return;
            SendBestEffort(activeTransport, CallSignaling.Cancel(session.CallId, ++sequence));
            EndCallLocked(CallProtocol.EndReason.Canceled);
        }
    }

    public void Hangup()
    {
        lock (gate)
        {
            if (session == null || session.State.Terminal()) return;
            SendBestEffort(activeTransport, CallSignaling.Hangup(session.CallId, ++sequence));
            EndCallLocked(CallProtocol.EndReason.LocalHangup);
        }
    }

    public bool IsMuted() { lock (gate) return session?.Muted ?? false; }
    public void SetMuted(bool muted)
    {
        lock (gate)
        {
            if (session == null) return;
            session.Muted = muted;
            media?.SetMuted(muted);
            NotifyCallback(session.Snapshot());
        }
    }

    // ── Media setup ────────────────────────────────────────────────────────────

    Task StartMediaAsCalleeAsync()
    {
        // The callee waits for the caller's OFFER (handled in OnFrame) before creating any media --
        // capture must never start before both local accept and remote acceptance are in, per the
        // plan's "capture requires explicit local action and remote acceptance" rule.
        lock (gate) StartMediaLocked();
        return Task.CompletedTask; // media is driven by incoming OFFER/ANSWER frames from here
    }

    // The single place any media adapter is created, for either medium and from either direction.
    // Called under `gate`. Keeping it in one place is what makes the contract checkable: no camera and
    // no microphone can be opened on a path that does not pass here, and this is reached only after
    // an INVITE has been accepted -- never during a capability probe and never while a peer is still
    // ringing.
    void StartMediaLocked()
    {
        if (session == null) return;
        if (media == null && MediaFactory != null) { media = MediaFactory.Create(); WireMedia(media); }
        StartVideoLocked();
    }

    // Creates the video adapter and its coordinator, if this call can have video at all. Called under
    // `gate`, from both the caller's offer path and the callee's accept path.
    //
    // A missing adapter is not an error: the call stays a healthy v2 audio call, and every video frame
    // is refused because there is no coordinator to admit it. Failing the whole call instead would
    // mean a missing camera could tear down working audio, which the contract explicitly forbids.
    void StartVideoLocked()
    {
        if (session == null || videoConsent == null || videoCoordinator != null) return;
        var adapter = VideoMediaFactory?.Invoke();
        if (adapter == null) return;
        var bound = session.CallId;
        var mask = engine.TrustedCallMask(session.PeerId);
        var autoVideo = (mask & PeerEngine.TrustedAutoAnswerVideo) != 0;
        // The caller always drives negotiation, so a camera may only auto-start on the caller's side
        // (its own device, its own consent); the callee's side waits for its own explicit answer.
        var autoCamera = invitedVideo && (session.IsCaller || autoVideo);
        var created = new CallVideoCoordinator(bound, session.IsCaller, videoConsent, MayCapture, adapter,
            f =>
            {
                lock (gate)
                {
                    if (session == null || session.CallId != bound || session.State != CallProtocol.State.Connected)
                        throw new IOException("That call has ended");
                    f.Seq = ++sequence;
                    SendFrameTo(activeTransport!, f);
                }
            },
            snap =>
            {
                lock (gate)
                {
                    if (session == null || session.CallId != bound || session.State.Terminal()) return;
                    session.Video = snap;
                    NotifyCallback(session.Snapshot());
                }
            },
            autoAcceptVideo: autoVideo, autoStartCamera: autoCamera);
        adapter.AttachLocal(localVideoSink);
        adapter.AttachRemote(remoteVideoSink);
        videoCoordinator = created;
        session.Video = created.Published;
    }

    public void AttachVideoSinks(ICallVideoFrameSink? local, ICallVideoFrameSink? remote)
    {
        lock (gate)
        {
            var current = videoCoordinator?.Media;
            if (localVideoSink != null) current?.DetachLocal(localVideoSink);
            if (remoteVideoSink != null) current?.DetachRemote(remoteVideoSink);
            localVideoSink = local; remoteVideoSink = remote;
            current?.AttachLocal(local); current?.AttachRemote(remote);
        }
    }

    // Releases video only. Audio, and the call itself, are untouched: a video failure must never end a
    // working voice call. Called under `gate`.
    void CloseVideoLocked()
    {
        var coordinator = videoCoordinator; videoCoordinator = null;
        try { coordinator?.Dispose(); } catch { }
        videoConsent?.End();
        videoActions = null;
        if (session != null) session.Video = null;
    }

    void WireMedia(ICallMedia m)
    {
        m.OnLocalIceCandidate += (candidate, sdpMid, idx) =>
        {
            ITransport? t; string? callId; long gen;
            lock (gate) { if (session == null) return; t = activeTransport; callId = session.CallId; gen = negotiationGeneration; }
            if (t == null) return;
            try { SendFrameTo(t, CallSignaling.Ice(callId!, NextSeq(), gen, candidate, sdpMid, idx)); } catch { }
        };
        m.OnMediaReady += () =>
        {
            lock (gate)
            {
                if (session == null || localMediaReadySent) return;
                localMediaReadySent = true;
                if (activeTransport != null) try { SendFrameTo(activeTransport, CallSignaling.MediaReady(session.CallId, NextSeq(), negotiationGeneration)); } catch { }
            }
        };
        m.OnMediaFailed += () =>
        {
            lock (gate)
            {
                if (session == null || session.State.Terminal()) return;
                SendBestEffort(activeTransport, CallSignaling.Hangup(session.CallId, NextSeq()));
                EndCallLocked(CallProtocol.EndReason.MediaError);
            }
        };
    }

    long NextSeq() => ++sequence;

    // ── Incoming signalling dispatch ───────────────────────────────────────────

    public void OnFrame(CallProtocol.Frame frame, string authenticatedPeerId)
    {
        // Stage 1: a video frame bypasses the state/role table entirely and goes to the coordinator,
        // which decides admissibility against the bound request id, the active generation and the live
        // consent. The envelope is only admitted if the coordinator asks for it, so a video frame can
        // never keep a call alive or consume a sequence unless it is genuinely admissible. This
        // ordering is the whole point: a rejected frame must not refresh the heartbeat.
        CallVideoCoordinator? videoTarget;
        lock (gate) videoTarget = VideoRouteLocked(frame, authenticatedPeerId);
        if (videoTarget != null)
        {
            videoTarget.ReceiveAdmitted(frame, admitted =>
            {
                lock (gate)
                {
                    if (session == null || session.State != CallProtocol.State.Connected
                        || videoCoordinator != videoTarget || session.PeerId != authenticatedPeerId
                        || session.CallId != admitted.Cid) return false;
                    return frameAdmission?.Admit(admitted, authenticatedPeerId, permitted: true) ?? false;
                }
            });
            return;
        }

        ICallMedia? m = null; bool startCallerOffer = false;
        lock (gate)
        {
            if (session == null || session.PeerId != authenticatedPeerId || session.CallId != frame.Cid) return;
            var allowed = CallProtocol.AllowedSender(frame.T, session.State, session.IsCaller);
            var permitted = allowed != null;
            if (protocolVersion == 2)
            {
                bool videoFrame = VideoFrame(frame);
                if (videoFrame)
                {
                    // Without a coordinator (no adapter, or the peer declined video) a video frame is
                    // simply not admissible -- there is nothing that could act on it.
                    permitted = session.State == CallProtocol.State.Connected
                        && videoCoordinator != null && videoCoordinator.Permits(frame);
                }
                else if (frame.T is CallProtocol.OFFER or CallProtocol.ANSWER)
                    permitted = permitted && session.State == CallProtocol.State.Connecting;
                // A callee may only say "video" in its ACCEPT if video was actually invited.
                if (frame.T == CallProtocol.ACCEPT && frame.B != null && frame.B.TryGetValue("media", out var am) && am is "video")
                    permitted = permitted && invitedVideo;
            }
            if (!frameAdmission!.Admit(frame, authenticatedPeerId, permitted)) return;
            lastInboundMs = PeerEngine.Now;
            if (VideoFrame(frame)) { videoCoordinator!.Receive(frame); return; }

            switch (frame.T)
            {
                case CallProtocol.RINGING:
                    NotifyCallback(session.Snapshot());
                    return;
                case CallProtocol.ACCEPT:
                    videoConsent?.PeerAnswered(session.CallId,
                        frame.B != null && frame.B.TryGetValue("media", out var am2) && am2 is "video");
                    CancelRingTimeout();
                    session.State = CallProtocol.State.Connecting;
                    ScheduleMediaTimeout();
                    StartHeartbeat();
                    startCallerOffer = true;
                    NotifyCallback(session.Snapshot());
                    break;
                case CallProtocol.BUSY:
                    EndCallLocked(CallProtocol.EndReason.BusyRemote);
                    return;
                case CallProtocol.DECLINE:
                    EndCallLocked(CallProtocol.EndReason.Declined);
                    return;
                case CallProtocol.CANCEL:
                    EndCallLocked(CallProtocol.EndReason.Canceled);
                    return;
                case CallProtocol.OFFER:
                    if (MediaFactory != null)
                    {
                        StartMediaLocked();
                        m = media;
                    }
                    break;
                case CallProtocol.ANSWER:
                    m = media;
                    break;
                case CallProtocol.ICE:
                    media?.AddRemoteIceCandidate(CallSignaling.GetCandidate(frame) ?? "", CallSignaling.GetSdpMid(frame), CallSignaling.GetSdpMLineIndex(frame));
                    return;
                case CallProtocol.MEDIA_READY:
                    session.State = CallProtocol.State.Connected;
                    session.ConnectedAtMs = PeerEngine.Now;
                    videoCoordinator?.AudioConnected();
                    NotifyCallback(session.Snapshot());
                    return;
                case CallProtocol.HANGUP:
                    EndCallLocked(CallProtocol.EndReason.RemoteHangup);
                    return;
                case CallProtocol.ERROR:
                    EndCallLocked(CallProtocol.EndReason.NetworkFailure);
                    return;
                case CallProtocol.PING:
                    SendBestEffort(activeTransport, CallSignaling.Pong(session.CallId, NextSeq()));
                    return;
                case CallProtocol.PONG:
                    return;
                case CallProtocol.REMOTE_SPEAKER:
                    ApplyRemoteSpeakerLocked(frame, authenticatedPeerId);
                    return;
                case CallProtocol.REMOTE_CAMERA:
                    ApplyRemoteCameraLocked(frame, authenticatedPeerId);
                    return;
                default:
                    return;
            }
        }

        if (startCallerOffer) _ = SendCallerOfferAsync();
        else if (frame.T == CallProtocol.OFFER && m != null) _ = HandleRemoteOfferAsync(m, frame);
        else if (frame.T == CallProtocol.ANSWER && m != null) _ = HandleRemoteAnswerAsync(m, frame);
    }

    // A frame belongs to video when it says so by type or by body. ACCEPT is excluded from the body
    // test on purpose: "answering with video" is a normal call-level answer, not a media frame.
    static bool VideoFrame(CallProtocol.Frame f) =>
        f.T.StartsWith("VIDEO_", StringComparison.Ordinal)
        || (f.T != CallProtocol.ACCEPT && f.B != null && f.B.TryGetValue("media", out var m) && m is "video");

    // The coordinator that should judge this frame, or null when this is not a connected video frame.
    CallVideoCoordinator? VideoRouteLocked(CallProtocol.Frame frame, string authenticatedPeerId) =>
        protocolVersion == 2 && VideoFrame(frame)
        && session != null && session.State == CallProtocol.State.Connected
        && session.PeerId == authenticatedPeerId && session.CallId == frame.Cid
        ? videoCoordinator : null;

    async Task SendCallerOfferAsync()
    {
        ICallMedia? m; string? callId; long gen;
        lock (gate)
        {
            if (session == null || MediaFactory == null) return;
            StartMediaLocked();
            m = media; callId = session.CallId; gen = negotiationGeneration;
        }
        try
        {
            var sdp = await m!.CreateOfferAsync();
            ITransport? t; lock (gate) t = activeTransport;
            if (t != null) SendFrameTo(t, CallSignaling.Offer(callId!, NextSeq(), gen, sdp));
        }
        catch (Exception e) { FailMediaLocked(e, callId ?? ""); }
    }

    async Task HandleRemoteOfferAsync(ICallMedia m, CallProtocol.Frame frame)
    {
        string? callId = null;
        try
        {
            var sdp = await m.CreateAnswerAsync(CallSignaling.GetSdp(frame) ?? "");
            ITransport? t; long gen;
            lock (gate) { if (session == null) return; t = activeTransport; callId = session.CallId; gen = frame.Gen; }
            if (t != null) SendFrameTo(t, CallSignaling.Answer(callId!, NextSeq(), gen, sdp));
        }
        catch (Exception e) { FailMediaLocked(e, callId ?? ""); }
    }

    async Task HandleRemoteAnswerAsync(ICallMedia m, CallProtocol.Frame frame)
    {
        string? callId = null;
        try
        {
            lock (gate) callId = session?.CallId;
            await m.SetRemoteAnswerAsync(CallSignaling.GetSdp(frame) ?? "");
        }
        catch (Exception e) { FailMediaLocked(e, callId ?? ""); }
    }

    // One place for every media-negotiation failure, under `gate`. These three paths used to catch
    // and discard the exception entirely, so a validator or media refusal -- the single most
    // common reason a call dies -- was indistinguishable from a network drop in the call log: the
    // session simply ended as MediaError with no cause anywhere. The reason is recorded on
    // LastFailureReason before the session is torn down, which is the same lifetime as the call, and
    // is also where `MediaFaulted` is raised for anyone who wants to see the cause without polling
    // a field that EndCallLocked has already invalidated.
    void FailMediaLocked(Exception e, string callId)
    {
        LastFailureReason = e is IOException || e is InvalidOperationException ? e.Message : e.GetType().Name;
        MediaFaulted?.Invoke(callId, LastFailureReason);
        SendBestEffort(activeTransport, CallSignaling.Hangup(callId, NextSeq()));
        EndCallLocked(CallProtocol.EndReason.MediaError);
    }



    // ── Timers ─────────────────────────────────────────────────────────────────

    void ScheduleRingTimeout()
    {
        CancelRingTimeout();
        ringTimeoutTimer = new System.Threading.Timer(_ => OnRingTimeout(), null, (int)CallProtocol.TimeoutRingingMs, Timeout.Infinite);
    }
    void CancelRingTimeout() { ringTimeoutTimer?.Dispose(); ringTimeoutTimer = null; }
    void OnRingTimeout()
    {
        lock (gate)
        {
            if (session == null) return;
            if (session.State is CallProtocol.State.OutgoingRinging or CallProtocol.State.IncomingRinging)
            {
                SendBestEffort(activeTransport, CallSignaling.Hangup(session.CallId, NextSeq()));
                EndCallLocked(CallProtocol.EndReason.TimeoutRinging);
            }
        }
    }

    void ScheduleMediaTimeout()
    {
        mediaTimeoutTimer?.Dispose();
        mediaTimeoutTimer = new System.Threading.Timer(_ => OnMediaTimeout(), null, (int)CallProtocol.TimeoutMediaSetupMs, Timeout.Infinite);
    }
    void OnMediaTimeout()
    {
        lock (gate)
        {
            if (session == null || session.State != CallProtocol.State.Connecting) return;
            SendBestEffort(activeTransport, CallSignaling.Hangup(session.CallId, NextSeq()));
            EndCallLocked(CallProtocol.EndReason.TimeoutMedia);
        }
    }

    void StartHeartbeat()
    {
        heartbeatTimer?.Dispose();
        lastInboundMs = PeerEngine.Now;
        heartbeatTimer = new System.Threading.Timer(_ =>
        {
            lock (gate)
            {
                if (session == null || session.State.Terminal()) { heartbeatTimer?.Dispose(); heartbeatTimer = null; return; }
                if (PeerEngine.Now - lastInboundMs > CallProtocol.HeartbeatFailMs)
                {
                    EndCallLocked(CallProtocol.EndReason.SignalingLost);
                    return;
                }
                SendBestEffort(activeTransport, CallSignaling.Ping(session.CallId, NextSeq()));
            }
        }, null, (int)CallProtocol.HeartbeatIntervalMs, (int)CallProtocol.HeartbeatIntervalMs);
    }
    void StopHeartbeat() { heartbeatTimer?.Dispose(); heartbeatTimer = null; }



    void SendBestEffort(ITransport? t, CallProtocol.Frame f) { if (t == null) return; try { SendFrameTo(t, f); } catch { } }

    // Every outbound frame passes through here, so a v2 frame is stamped and validated in exactly one
    // place. This is the same sequence android/src/net/lanmsg/chat/CallController.sendFrame applies,
    // and it matters for wire compatibility rather than tidiness:
    //
    //   * a v1 call is sent byte-for-byte as it always was;
    //   * on a v2 call, audio media frames are forced to generation 1 and an audio media tag, because
    //     that is what the peer validator requires and what the archived voice path assumes;
    //   * a frame that does not satisfy the v2 grammar is never put on the wire at all, because the
    //     peer would drop it silently and the two sides would then disagree about what was sent.
    void SendFrameTo(ITransport? t, CallProtocol.Frame f)
    {
        if (t == null) return;
        if (protocolVersion == 2)
        {
            f.V = 2;
            f.B ??= new Dictionary<string, object>();
            bool video = f.B.TryGetValue("media", out var tag) && tag is "video";
            switch (f.T)
            {
                case CallProtocol.OFFER:
                case CallProtocol.ANSWER:
                case CallProtocol.ICE:
                case CallProtocol.MEDIA_READY:
                case CallProtocol.ERROR:
                    if (!video) { f.Gen = 1; f.B["media"] = "audio"; }
                    if (f.T == CallProtocol.ERROR && !f.B.ContainsKey("code")) f.B["code"] = "failed";
                    break;
                case CallProtocol.INVITE:
                case CallProtocol.ACCEPT:
                case CallProtocol.VIDEO_REQUEST:
                case CallProtocol.VIDEO_ACCEPT:
                case CallProtocol.VIDEO_DECLINE:
                case CallProtocol.VIDEO_STATE:
                case CallProtocol.REMOTE_SPEAKER:
                case CallProtocol.REMOTE_CAMERA:
                    break;
                default:
                    // RINGING/DECLINE/BUSY/CANCEL/HANGUP/PING/PONG carry no body in v2.
                    f.Gen = 0; f.B.Clear(); break;
            }
            if (!CallVideoProtocol.Valid(f)) throw new IOException("Refusing to send an invalid call frame");
        }
        t.Send(CallSignaling.Serialize(f));
    }

    // A recipient may only be told to route audio when the caller granted it, and the same grant is
    // re-checked at the moment of application rather than trusted from an earlier frame.
    void ApplyRemoteSpeakerLocked(CallProtocol.Frame frame, string authenticatedPeerId)
    {
        if (session == null || session.IsCaller) return;
        if ((engine.TrustedCallMask(authenticatedPeerId) & PeerEngine.TrustedRemoteSpeaker) == 0) return;
        if (frame.B == null || !frame.B.TryGetValue("speaker", out var raw) || raw is not bool on) return;
        if (!VideoResourceRootApplySpeaker()) return;
        session.AudioRoute = on ? "Speaker" : "System";
        NotifyCallback(session.Snapshot());
    }

    // Remote camera control only reaches the device through the coordinator, which re-checks the grant
    // at the point of use and applies it to the LIVE generation only.
    void ApplyRemoteCameraLocked(CallProtocol.Frame frame, string authenticatedPeerId)
    {
        if (session == null || session.IsCaller || videoCoordinator == null) return;
        if ((engine.TrustedCallMask(authenticatedPeerId) & PeerEngine.TrustedRemoteCamera) == 0) return;
        if (frame.B == null || !frame.B.TryGetValue("camera", out var cam) || cam is not bool on) return;
        if (!frame.B.TryGetValue("facing", out var facing) || facing is not string where) return;
        var live = videoCoordinator.Published;
        if (live.Phase != CallVideoConsent.Phase.Video || live.Generation != frame.Gen) return;
        if (frame.B.TryGetValue("request", out var bound) && bound as string != live.Request) return;
        videoCoordinator.RemoteCamera(on, where,
            () => (engine.TrustedCallMask(authenticatedPeerId) & PeerEngine.TrustedRemoteCamera) != 0);
    }

    // Audio routing needs a device to route to. Kept behind a delegate so the controller stays free of
    // an audio-device dependency and a test can prove the grant is honoured without one.
    public Func<bool> ApplyRemoteSpeakerRoute { get; set; } = () => false;
    bool VideoResourceRootApplySpeaker() => ApplyRemoteSpeakerRoute();


    // The UI calls these; it never touches consent, the coordinator or the adapter directly. Every
    // action rechecks live eligibility, so a button pressed while the call was healthy but released
    // after it was revoked cannot open a camera.

    // Camera eligibility. The device question is answered by an injected predicate rather than by
    // probing a real camera here: this method is on the call path, and a machine with no camera must
    // produce a clean "no", never an exception out of a call.
    public Func<string?, bool> CameraEligible { get; set; } = _ => true;

    bool MayCapture(string expectedCallId)
    {
        lock (gate) return MayCaptureLocked(expectedCallId);
    }
    bool MayCaptureLocked(string? expectedCallId)
    {
        if (session == null || session.State.Terminal()) return false;
        if (expectedCallId != null && session.CallId != expectedCallId) return false;
        try { return CameraEligible(session.CallId); } catch { return false; }
    }

    void BindVideoActions(string callId)
    {
        var consent = videoConsent;
        if (consent == null) { videoActions = null; return; }
        videoActions = new CallVideoActions(consent, MayCapture, new VideoEffects(this));
    }

    // The one place user commands turn into frames. Each effect re-checks that the call it was issued
    // for is still live, so a button pressed on a call that has since ended cannot be written into a
    // sequence counter that a later call will reuse.
    sealed class VideoEffects(CallController owner) : CallVideoActions.IEffects
    {
        public void Answer(string callId, bool video) => owner.SendAnswerLocked(callId, video);
        public void Request(string callId, string request)
        {
            owner.SendLocked(callId, CallSignaling.VideoRequest(callId, owner.NextSeq(), 0, request));
            owner.videoCoordinator?.ProposalAcceptedLocally(request);
        }
        public void Accept(string callId, string request)
        {
            owner.SendLocked(callId, CallSignaling.VideoAcceptUpgrade(callId, owner.NextSeq(), 0, request));
            owner.videoCoordinator?.UpgradeAcceptedLocally(request);
        }
        public void Decline(string callId, string request)
        {
            owner.SendLocked(callId, CallSignaling.VideoDecline(callId, owner.NextSeq(), 0, request));
            owner.videoCoordinator?.UpgradeDeclinedLocally(request);
        }
        public void Camera(string callId, bool on) { lock (owner.gate) owner.videoCoordinator?.CameraChosenLocally(on); }
    }

    // Effects that touch the transport take the lock exactly once, and re-check that the call they
    // were issued for is still the live one. A queued command for a call that has since ended is
    // dropped rather than written into a reused sequence counter.
    void SendLocked(string expectedCallId, CallProtocol.Frame frame)
    {
        lock (gate)
        {
            if (session == null || session.CallId != expectedCallId || session.State.Terminal()) return;
            SendFrameTo(activeTransport, frame);
        }
    }

    /// Answer a v2 call that is still ringing, with or without video.
    ///
    /// While ringing this is an answer; once the call is up it is an upgrade *answer* for an
    /// outstanding request. Both go through CallVideoActions, which owns the consent mutation and
    /// rolls it back if the frame cannot be sent -- the UI must never leave the user believing video
    /// was accepted while the peer never heard about it.
    public CallVideoConsent.Result? AnswerVideo(string expectedCallId, bool video = true)
    {
        lock (gate)
        {
            if (session == null || session.CallId != expectedCallId || videoActions == null) return null;
            if (session.State == CallProtocol.State.IncomingRinging)
                return video ? videoActions.AcceptVideoOrVoice(expectedCallId) : videoActions.AnswerWithVoice(expectedCallId);
            var request = videoConsent?.RequestId;
            if (request == null) return null;
            return video ? videoActions.AcceptUpgrade(expectedCallId, request) : videoActions.DeclineUpgrade(expectedCallId, request);
        }
    }

    void SendAnswerLocked(string expectedCallId, bool video)
    {
        if (session == null || session.CallId != expectedCallId || session.State != CallProtocol.State.IncomingRinging)
            throw new IOException("That call is no longer waiting");
        var accept = CallSignaling.Accept(expectedCallId, ++sequence);
        if (protocolVersion == 2) accept.B!["media"] = video ? "video" : "audio";
        // Sent before the state change and before the ring watchdog is cancelled, for the same reason
        // as in AcceptAsync: an answer that could not be delivered must end the call rather than
        // leave it ringing forever from this side. Unlike AcceptAsync this is reached synchronously
        // from a button handler through CallVideoActions, which rolls its own consent mutation back
        // when this throws -- so ending the call here is the behaviour the caller already expects.
        try { SendFrameTo(activeTransport, accept); }
        catch (Exception e)
        {
            LastFailureReason = e.Message;
            MediaFaulted?.Invoke(expectedCallId, LastFailureReason);
            EndCallLocked(CallProtocol.EndReason.NetworkFailure);
            throw;
        }
        session.State = CallProtocol.State.Connecting;
        CancelRingTimeout();
        ScheduleMediaTimeout();
        StartHeartbeat();
        // Media is created here, not only on AcceptAsync: answering with video goes through
        // CallVideoActions.AcceptVideo, which lands in this method, and a call that never gets an
        // audio adapter would negotiate nothing and simply time out.
        StartMediaLocked();
        NotifyCallback(session.Snapshot());
    }

    public CallVideoConsent.Result? RequestVideo(string expectedCallId)
    {
        lock (gate)
        {
            if (session == null || session.CallId != expectedCallId || videoActions == null) return null;
            return videoActions.RequestVideo(expectedCallId);
        }
    }

    public CallVideoConsent.Result? AcceptVideoUpgrade(string expectedCallId, string request)
    {
        lock (gate)
        {
            if (session == null || session.CallId != expectedCallId || videoActions == null) return null;
            return videoActions.AcceptUpgrade(expectedCallId, request);
        }
    }

    public CallVideoConsent.Result? DeclineVideoUpgrade(string expectedCallId, string request)
    {
        lock (gate)
        {
            if (session == null || session.CallId != expectedCallId || videoActions == null) return null;
            return videoActions.DeclineUpgrade(expectedCallId, request);
        }
    }

    public CallVideoConsent.Result? SetCamera(string expectedCallId, bool on)
    {
        lock (gate)
        {
            if (session == null || session.CallId != expectedCallId || videoActions == null) return null;
            if (on) return videoActions.TurnCameraOn(expectedCallId);
            videoActions.TurnCameraOff(expectedCallId);
            return videoConsent?.CurrentPhase == CallVideoConsent.Phase.Video
                ? CallVideoConsent.Result.Ready : CallVideoConsent.Result.Voice;
        }
    }

    /// Tell the other end to start or stop their camera. Only the party that PLACED the call may do
    /// this, and only when that other device has granted recipient control.
    public void SetRemoteCamera(string expectedCallId, bool on, string facing)
    {
        lock (gate)
        {
            if (session == null || session.CallId != expectedCallId || !session.IsCaller || session.State != CallProtocol.State.Connected) return;
            // Recipient controls use the grant the OTHER device reported to this caller (Slave
            // direction), never this machine's local Masters grant. The short freshness window also
            // makes revoke/failure fail closed between the UI click and the wire send.
            if ((engine.RemoteControlDisplayMask(session.PeerId) & PeerEngine.TrustedRemoteCamera) == 0) return;
            var live = videoCoordinator?.Published;
            if (live?.Request == null) return;
            SendFrameTo(activeTransport, CallSignaling.RemoteCamera(expectedCallId, NextSeq(), live.Generation,
                live.Request, on, facing));
        }
    }

    public void SetRemoteSpeaker(string expectedCallId, bool on)
    {
        lock (gate)
        {
            if (session == null || session.CallId != expectedCallId || !session.IsCaller || session.State != CallProtocol.State.Connected) return;
            if ((engine.RemoteControlDisplayMask(session.PeerId) & PeerEngine.TrustedRemoteSpeaker) == 0) return;
            SendFrameTo(activeTransport, CallSignaling.RemoteSpeaker(expectedCallId, NextSeq(), on));
        }
    }

    public CallVideoCoordinator.Snapshot? Video(string expectedCallId)
    {
        lock (gate) return session?.CallId == expectedCallId ? session.Video : null;
    }

    // Called under `gate`. Computes final duration, tears down media/timers, appends the local
    // call-history entry, releases the transport, and returns the controller to Idle.
    // ── Termination ────────────────────────────────────────────────────────────

    void EndCallLocked(CallProtocol.EndReason reason)
    {
        if (session == null) return;
        CancelRingTimeout(); mediaTimeoutTimer?.Dispose(); mediaTimeoutTimer = null; StopHeartbeat();
        // Video is released BEFORE audio, and independently: a video failure must not leave the audio
        // call running with no way to end it, and must not end a healthy audio call either.
        CloseVideoLocked();
        session.State = CallProtocol.State.Ending;
        session.EndReason = reason;
        session.FailureReason = LastFailureReason;
        if (session.State == CallProtocol.State.Ending && session.ConnectedAtMs > 0)
            session.DurationMs = PeerEngine.Now - session.ConnectedAtMs;
        var snap = session.Snapshot();
        var peerId = session.PeerId; var isCaller = session.IsCaller; var connected = session.ConnectedAtMs > 0; var duration = session.DurationMs;
        NotifyCallback(snap);

        media?.Dispose(); media = null;
        if (activeTransport is CallChannel ch) ch.BeginGracefulClose(); else CloseTransport();
        activeTransport = null;
        frameAdmission = null;
        protocolVersion = 1;
        invitedVideo = false;

        try { engine.AppendCallLog(peerId, isCaller, connected, duration); } catch { }

        session = null;
    }

    public void OnSignalingChannelClosed()
    {
        lock (gate)
        {
            if (session == null || session.State.Terminal()) return;
            EndCallLocked(CallProtocol.EndReason.SignalingLost);
        }
    }

    public void OnPeerRevoked(string peerId)
    {
        lock (gate)
        {
            if (session != null && session.PeerId == peerId && !session.State.Terminal())
            {
                SendBestEffort(activeTransport, CallSignaling.Hangup(session.CallId, NextSeq()));
                EndCallLocked(CallProtocol.EndReason.EngineShutdown);
            }
        }
    }

    public void OnOffline()
    {
        lock (gate)
        {
            if (session != null && !session.State.Terminal()) EndCallLocked(CallProtocol.EndReason.Offline);
        }
    }

    public void Shutdown()
    {
        lock (gate)
        {
            if (session != null && !session.State.Terminal()) EndCallLocked(CallProtocol.EndReason.EngineShutdown);
        }
    }
}
