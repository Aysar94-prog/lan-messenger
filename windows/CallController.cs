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

    readonly object gate = new();
    CallSession.Builder? session;
    ICallMedia? media;
    long sequence;
    long negotiationGeneration;
    bool localMediaReadySent;

    System.Threading.Timer? ringTimeoutTimer, mediaTimeoutTimer, heartbeatTimer;
    long lastInboundMs;
    ITransport? activeTransport;

    volatile Callback callback = _ => { };
    readonly List<Callback> listeners = new();

    public CallController(PeerEngine engine, CallSettings settings)
    {
        this.engine = engine; this.settings = settings;
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

    // ── Outgoing call ──────────────────────────────────────────────

    public string StartCall(string peerId, ITransportFactory factory)
    {
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
            activeTransport = opened;
            negotiationGeneration = 0; sequence = 0; localMediaReadySent = false;
            var invite = CallSignaling.Invite(callId, ++sequence, engine.Id, peerId);
            try { SendFrame(opened, invite); }
            catch { session = null; activeTransport = null; CloseTransportQuietly(opened); throw; }
            ScheduleRingTimeout();
            var snap = session.Snapshot();
            NotifyCallback(snap);
            return callId;
        }
    }

    // ── Incoming call (first frame on an adopted channel) ─────────

    public void OnInvite(CallProtocol.Frame frame, string peerId, ITransport transport)
    {
        lock (gate)
        {
            if (!CallProtocol.ValidCallId(frame.Cid)) { CloseTransportQuietly(transport); return; }
            long now = PeerEngine.Now;
            if (!limiter.Record(peerId, now)) { SendBestEffort(transport, CallSignaling.Busy(frame.Cid, 1)); CloseTransportQuietly(transport); return; }
            if (session != null && !session.State.Terminal()) { SendBestEffort(transport, CallSignaling.Busy(frame.Cid, 1)); CloseTransportQuietly(transport); return; }
            var peer = engine.Peers.FirstOrDefault(p => p.Id == peerId);
            if (peer == null || !peer.Trusted) { CloseTransportQuietly(transport); return; } // silence: identity mismatch
            if (!settings.AllowIncomingCalls) { SendBestEffort(transport, CallSignaling.Decline(frame.Cid, 1)); CloseTransportQuietly(transport); return; }

            session = new CallSession.Builder(frame.Cid, peerId, false, now) { State = CallProtocol.State.IncomingRinging };
            activeTransport = transport;
            negotiationGeneration = 0; sequence = 0; localMediaReadySent = false;
            lastInboundMs = now;
            try { SendFrame(transport, CallSignaling.Ringing(frame.Cid, ++sequence)); } catch { }
            ScheduleRingTimeout();
            NotifyCallback(session.Snapshot());
            if ((engine.TrustedCallMask(peerId) & PeerEngine.TrustedAutoAnswerVoice) != 0)
                _ = AcceptAsync();
        }
    }

    // ── Local user actions ─────────────────────────────────────────

    public async Task AcceptAsync()
    {
        ITransport? t; string? peerId; bool isCaller;
        lock (gate)
        {
            if (session == null || session.State != CallProtocol.State.IncomingRinging) return;
            session.State = CallProtocol.State.Connecting;
            t = activeTransport; peerId = session.PeerId; isCaller = session.IsCaller;
            CancelRingTimeout();
            try { SendFrame(t!, CallSignaling.Accept(session.CallId, ++sequence)); } catch { }
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

    // ── Media setup ────────────────────────────────────────────────

    async Task StartMediaAsCalleeAsync()
    {
        // The callee waits for the caller's OFFER (handled in OnFrame) before creating any media --
        // capture must never start before both local accept and remote acceptance are in, per the
        // plan's "capture requires explicit local action and remote acceptance" rule.
        ICallMedia? m;
        lock (gate)
        {
            if (session == null || MediaFactory == null) return;
            if (media == null) { media = MediaFactory.Create(); WireMedia(media); }
            m = media;
        }
        await Task.CompletedTask;
        _ = m; // media is driven by incoming OFFER/ANSWER frames from here
    }

    void WireMedia(ICallMedia m)
    {
        m.OnLocalIceCandidate += (candidate, sdpMid, idx) =>
        {
            ITransport? t; string? callId; long gen;
            lock (gate) { if (session == null) return; t = activeTransport; callId = session.CallId; gen = negotiationGeneration; }
            if (t == null) return;
            try { SendFrame(t, CallSignaling.Ice(callId!, NextSeq(), gen, candidate, sdpMid, idx)); } catch { }
        };
        m.OnMediaReady += () =>
        {
            lock (gate)
            {
                if (session == null || localMediaReadySent) return;
                localMediaReadySent = true;
                if (activeTransport != null) try { SendFrame(activeTransport, CallSignaling.MediaReady(session.CallId, NextSeq(), negotiationGeneration)); } catch { }
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

    // ── Inbound frame dispatch ──────────────────────────────────────

    public void OnFrame(CallProtocol.Frame frame, string authenticatedPeerId)
    {
        ICallMedia? m = null; bool startCallerOffer = false;
        lock (gate)
        {
            if (session == null || session.PeerId != authenticatedPeerId || session.CallId != frame.Cid) return;
            var allowed = CallProtocol.AllowedSender(frame.T, session.State, session.IsCaller);
            if (allowed == null) return; // inadmissible in this state/role — ignore
            lastInboundMs = PeerEngine.Now;

            switch (frame.T)
            {
                case CallProtocol.RINGING:
                    NotifyCallback(session.Snapshot());
                    return;
                case CallProtocol.ACCEPT:
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
                        if (media == null) { media = MediaFactory.Create(); WireMedia(media); }
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
                default:
                    return;
            }
        }

        if (startCallerOffer) _ = SendCallerOfferAsync();
        else if (frame.T == CallProtocol.OFFER && m != null) _ = HandleRemoteOfferAsync(m, frame);
        else if (frame.T == CallProtocol.ANSWER && m != null) _ = HandleRemoteAnswerAsync(m, frame);
    }

    async Task SendCallerOfferAsync()
    {
        ICallMedia? m; string? callId; long gen;
        lock (gate)
        {
            if (session == null || MediaFactory == null) return;
            if (media == null) { media = MediaFactory.Create(); WireMedia(media); }
            m = media; callId = session.CallId; gen = negotiationGeneration;
        }
        try
        {
            var sdp = await m!.CreateOfferAsync();
            ITransport? t; lock (gate) t = activeTransport;
            if (t != null) SendFrame(t, CallSignaling.Offer(callId!, NextSeq(), gen, sdp));
        }
        catch { lock (gate) { SendBestEffort(activeTransport, CallSignaling.Hangup(callId ?? "", NextSeq())); EndCallLocked(CallProtocol.EndReason.MediaError); } }
    }

    async Task HandleRemoteOfferAsync(ICallMedia m, CallProtocol.Frame frame)
    {
        try
        {
            var sdp = await m.CreateAnswerAsync(CallSignaling.GetSdp(frame) ?? "");
            ITransport? t; string? callId; long gen;
            lock (gate) { if (session == null) return; t = activeTransport; callId = session.CallId; gen = negotiationGeneration; }
            if (t != null) SendFrame(t, CallSignaling.Answer(callId!, NextSeq(), gen, sdp));
        }
        catch { lock (gate) { SendBestEffort(activeTransport, CallSignaling.Hangup(session?.CallId ?? "", NextSeq())); EndCallLocked(CallProtocol.EndReason.MediaError); } }
    }

    async Task HandleRemoteAnswerAsync(ICallMedia m, CallProtocol.Frame frame)
    {
        try { await m.SetRemoteAnswerAsync(CallSignaling.GetSdp(frame) ?? ""); }
        catch { lock (gate) { SendBestEffort(activeTransport, CallSignaling.Hangup(session?.CallId ?? "", NextSeq())); EndCallLocked(CallProtocol.EndReason.MediaError); } }
    }

    // ── Timers ───────────────────────────────────────────────────────

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

    // ── Termination ───────────────────────────────────────────────

    static void SendFrame(ITransport t, CallProtocol.Frame f) => t.Send(CallSignaling.Serialize(f));
    static void SendBestEffort(ITransport? t, CallProtocol.Frame f) { if (t == null) return; try { SendFrame(t, f); } catch { } }

    // Called under `gate`. Computes final duration, tears down media/timers, appends the local
    // call-history entry, releases the transport, and returns the controller to Idle.
    void EndCallLocked(CallProtocol.EndReason reason)
    {
        if (session == null) return;
        CancelRingTimeout(); mediaTimeoutTimer?.Dispose(); mediaTimeoutTimer = null; StopHeartbeat();
        session.State = CallProtocol.State.Ending;
        session.EndReason = reason;
        if (session.State == CallProtocol.State.Ending && session.ConnectedAtMs > 0)
            session.DurationMs = PeerEngine.Now - session.ConnectedAtMs;
        var snap = session.Snapshot();
        var peerId = session.PeerId; var isCaller = session.IsCaller; var connected = session.ConnectedAtMs > 0; var duration = session.DurationMs;
        NotifyCallback(snap);

        media?.Dispose(); media = null;
        if (activeTransport is CallChannel ch) ch.BeginGracefulClose(); else CloseTransport();
        activeTransport = null;

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
