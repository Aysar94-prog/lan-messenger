namespace LanMessenger;

// One call's separate video negotiation. All signaling and media work runs on a bounded dedicated
// worker -- never on a media callback and never while the call controller holds its lock.
//
// The owner authenticates the channel and admits the shared call sequence FIRST. This second
// boundary then checks the video-specific facts: role, consent, the bound request id and the exact
// active generation. Two independent boundaries are the point: an admitted audio frame is not
// thereby an admitted video frame.
//
// Ported from android/src/net/lanmsg/chat/CallVideoCoordinator.java.
public sealed class CallVideoCoordinator : IDisposable
{
    /// Sends one frame on the shared call channel. Implementations assign the real sender sequence
    /// under their own serialized send boundary -- the coordinator only supplies a placeholder.
    public delegate void Wire(CallProtocol.Frame frame);
    public delegate void Observer(Snapshot snapshot);
    public delegate bool Admission(CallProtocol.Frame frame);

    public sealed class Snapshot
    {
        public CallVideoConsent.Phase Phase { get; }
        public string? Request { get; }
        public long Generation { get; }
        public bool LocalCamera { get; }
        public bool RemoteCamera { get; }
        public bool RemoteRequest { get; }

        internal Snapshot(CallVideoConsent consent, bool camera)
        {
            Phase = consent.CurrentPhase; Request = consent.RequestId; Generation = consent.Generation;
            LocalCamera = camera; RemoteCamera = consent.RemoteCameraOn;
            RemoteRequest = consent.RemoteRequestPending;
        }
    }

    readonly string callId;
    readonly bool caller;
    readonly CallVideoConsent consent;
    readonly CallVideoActions.IEligibility eligibility;
    readonly ICallVideoMedia media;
    readonly Wire wire;
    readonly Observer? observer;

    readonly BoundedWorker worker;
    // Two timers, not one, exactly as on Android: the negotiation deadline and the single automatic
    // camera retry are scheduled on separate executors there, so arming the retry must not cancel an
    // in-flight negotiation deadline (and vice versa).
    readonly System.Threading.Timer deadlineTimer, retryTimer;

    readonly object closedGate = new();
    bool closed;
    Snapshot snapshot;
    bool deadlineArmed;
    bool retryArmed;
    string? pendingDeadline;          // deadlineTimer callback, marshalled back onto the worker
    long pendingDeadlineGeneration;

    long lastGeneration = 1, revision;
    bool camera, offered, localReady, remoteReady;
    volatile bool answered;
    int incomingIce, outgoingIce;
    readonly long requestTimeoutMs, videoTimeoutMs;
    readonly bool autoAcceptVideo;
    readonly bool autoStartCamera;
    bool autoCameraRetried;
    volatile bool autoCameraCanceled;

    // One constructor with optional trailing knobs rather than overloads: overloads that differ only
    // in optional arguments cannot be told apart at the call site, and a silent pick of the wrong one
    // would quietly change a deadline or an auto-accept policy.
    public CallVideoCoordinator(string id, bool caller, CallVideoConsent consent,
        CallVideoActions.IEligibility eligibility, ICallVideoMedia media, Wire wire,
        Observer? observer = null, long? requestTimeoutMs = null, long? videoTimeoutMs = null,
        bool autoAcceptVideo = false, bool autoStartCamera = false)
    {
        if (!CallProtocol.ValidCallId(id) || consent == null || eligibility == null || media == null || wire == null)
            throw new ArgumentException("Missing video boundary");
        var requestMs = requestTimeoutMs ?? CallVideoProtocol.RequestTimeoutMs;
        var videoMs = videoTimeoutMs ?? CallVideoProtocol.VideoTimeoutMs;
        if (requestMs <= 0 || videoMs <= 0) throw new ArgumentException("Invalid deadline");
        this.requestTimeoutMs = requestMs; this.videoTimeoutMs = videoMs;
        this.autoAcceptVideo = autoAcceptVideo; this.autoStartCamera = autoStartCamera;
        this.callId = id; this.caller = caller; this.consent = consent; this.eligibility = eligibility;
        this.media = media; this.wire = wire; this.observer = observer;

        worker = new BoundedWorker("call-video-coordinator");
        deadlineTimer = new System.Threading.Timer(_ => OnDeadline(), null, Timeout.Infinite, Timeout.Infinite);
        retryTimer = new System.Threading.Timer(_ => OnRetry(), null, Timeout.Infinite, Timeout.Infinite);
        snapshot = new Snapshot(consent, false);

        media.SetListener(new MediaBridge(this));
    }

    sealed class MediaBridge : ICallVideoMediaListener
    {
        readonly CallVideoCoordinator owner;
        public MediaBridge(CallVideoCoordinator owner) { this.owner = owner; }

        public void OnIce(long generation, string candidate, string mid, int index) => owner.Post(() =>
        {
            if (!owner.Current(generation) || ++owner.outgoingIce > 128) return;
            owner.Send(CallProtocol.ICE, generation, ("candidate", (object)candidate), ("sdpMid", mid), ("sdpMLineIndex", (long)index));
        });

        public void OnReady(long generation) => owner.Post(() =>
        {
            if (!owner.Current(generation)) return;
            owner.localReady = true; owner.Send(CallProtocol.MEDIA_READY, generation); owner.Activate(generation);
        });

        public void OnError(long generation, string message) => owner.Post(() =>
        {
            if (owner.Current(generation)) owner.Fail("failed", true);
        });

        public void OnCameraStopped(long generation) => owner.Post(() =>
        {
            if (!owner.Current(generation)) return;
            owner.consent.CameraOff(owner.callId); owner.camera = false; owner.State();
        });
    }

    /// The most recently published state. Read by the UI thread, so it is replaced wholesale rather
    /// than mutated.
    public Snapshot Published => snapshot;

    /// A coordinator belongs to exactly one call; every inbound frame and every eligibility check is
    /// bound to that id so a stale coordinator can never act on a call that has already ended.
    public bool IsForCall(string expected) => !Closed && callId == expected;

    public ICallVideoMedia Media => media;

    bool Closed { get { lock (closedGate) return closed; } }

    /// Lightweight pre-admission check. No media work and no consent mutation happens here, so the
    /// caller can reject a frame without any observable side effect.
    public bool Permits(CallProtocol.Frame? f)
    {
        if (Closed || f == null || callId != f.Cid || !CallVideoProtocol.Valid(f)) return false;
        string? id = BodyString(f, "request");
        long gen = f.Gen;
        switch (f.T)
        {
            case CallProtocol.VIDEO_REQUEST:
                return consent.CurrentPhase is CallVideoConsent.Phase.Voice or CallVideoConsent.Phase.Waiting;
            case CallProtocol.VIDEO_ACCEPT:
                return consent.CanPeerAccept(callId, id!);
            case CallProtocol.VIDEO_DECLINE:
                return gen == 0 && id == consent.RequestId && consent.Generation == 0;
            case CallProtocol.OFFER:
                return !caller && consent.CanAuthorizeGeneration(callId, id!, gen);
            case CallProtocol.ANSWER:
                return caller && id == consent.RequestId && gen == consent.Generation && !answered;
            case CallProtocol.VIDEO_STATE:
                return id == consent.RequestId && consent.CanRemoteCameraState(callId, gen, BodyLong(f, "revision"));
            case CallProtocol.MEDIA_READY:
                return !remoteReady && gen >= 2 && gen == consent.Generation && id == consent.RequestId;
            case CallProtocol.ICE:
            case CallProtocol.ERROR:
                return gen >= 2 && gen == consent.Generation && id == consent.RequestId;
            default:
                return false;
        }
    }

    internal void Post(Action job)
    {
        if (Closed) return;
        if (!worker.TryPost(() =>
        {
            if (Closed) return;
            try { job(); }
            catch (Exception) { Fail("failed", true); }
            Publish();
        }))
        {
            // Overflow invalidates consent immediately. The deadline timer still supplies cleanup even
            // when the bounded worker cannot accept more work.
            Dispose();
        }
    }

    void Publish()
    {
        snapshot = new Snapshot(consent, camera);
        try { observer?.Invoke(snapshot); } catch { }
        if (autoStartCamera && !camera && snapshot.Phase == CallVideoConsent.Phase.Video && !autoCameraRetried)
        {
            autoCameraRetried = true;
            // One automatic retry, deferred: on Windows the window may not yet be visible/foreground
            // when the media-ready callbacks land, and a capture attempt in that moment would fail
            // for a reason that has nothing to do with the user's decision.
            lock (closedGate)
            {
                if (!retryArmed && !closed)
                {
                    retryArmed = true;
                    retryTimer.Change(750, Timeout.Infinite);
                }
            }
        }
    }

    void OnRetry()
    {
        lock (closedGate)
        {
            retryArmed = false;
            if (closed) return;
        }
        Post(() =>
        {
            if (autoCameraCanceled) return;
            bool allowed = Eligible();
            var result = consent.CameraOn(callId, allowed);
            if (result is CallVideoConsent.Result.Ready or CallVideoConsent.Result.Busy) SetCamera(true);
        });
    }

    bool Current(long gen) => !Closed && gen >= 2 && consent.Generation == gen;

    bool Eligible() => !Closed && eligibility(callId);

    void Send(string type, long gen, params (string Key, object? Value)[] values)
    {
        var f = new CallProtocol.Frame { T = type, Cid = callId, Seq = 1, Gen = gen, V = 2, B = new() };
        if (gen >= 2)
        {
            // VIDEO_STATE describes the local camera rather than a media stream, so it carries no
            // `media` key -- CallVideoProtocol would reject it if it did.
            if (type != CallProtocol.VIDEO_STATE) f.B["media"] = "video";
            f.B["request"] = consent.RequestId!;
        }
        foreach (var (k, v) in values) f.B[k] = v!;
        if (!CallVideoProtocol.Valid(f)) throw new IOException("Invalid local video frame");
        wire(f); // the wire assigns the shared call sequence
    }

    void CancelDeadline()
    {
        lock (closedGate)
        {
            deadlineArmed = false; pendingDeadline = null;
            deadlineTimer.Change(Timeout.Infinite, Timeout.Infinite);
        }
    }

    void Deadline(string request, long gen, long delay)
    {
        lock (closedGate)
        {
            deadlineArmed = false;
            pendingDeadline = request; pendingDeadlineGeneration = gen;
            if (closed) return;
            deadlineArmed = true;
            deadlineTimer.Change(delay, Timeout.Infinite);
        }
    }

    void OnDeadline()
    {
        string request; long gen;
        lock (closedGate)
        {
            if (closed || !deadlineArmed) return;
            deadlineArmed = false;
            request = pendingDeadline!; gen = pendingDeadlineGeneration;
        }
        Post(() =>
        {
            if (request != consent.RequestId || gen != consent.Generation) return;
            if (gen == 0)
            {
                Send(CallProtocol.VIDEO_DECLINE, 0, ("request", request));
                consent.DeclineUpgrade(callId, request);
            }
            else Fail("timeout", true);
        });
    }

    /// Audio must be established first -- including for an accepted video INVITE. Starting video
    /// negotiation before audio is up would let a video exchange replace a call that never connected.
    public void AudioConnected() => Post(() =>
    {
        consent.SetConnected(callId, true);
        if (!caller && consent.RequestId != null) Deadline(consent.RequestId!, 0, videoTimeoutMs);
        BeginIfCaller();
    });

    public void RequestVideo() => Post(() =>
    {
        var id = Guid.NewGuid().ToString("D");
        if (consent.RequestUpgrade(callId, id, Eligible()) != CallVideoConsent.Result.Ready) return;
        try { Send(CallProtocol.VIDEO_REQUEST, 0, ("request", id)); Deadline(id, 0, requestTimeoutMs); }
        catch { consent.DeclineUpgrade(callId, id); throw; }
    });

    public void AcceptUpgrade(string id) => Post(() =>
    {
        if (consent.AcceptUpgrade(callId, id, Eligible()) != CallVideoConsent.Result.Ready) return;
        Send(CallProtocol.VIDEO_ACCEPT, 0, ("request", id));
        if (!caller) Deadline(id, 0, videoTimeoutMs);
        BeginIfCaller();
    });

    public void DeclineUpgrade(string id) => Post(() =>
    {
        if (id != consent.RequestId) return;
        Send(CallProtocol.VIDEO_DECLINE, 0, ("request", id));
        consent.DeclineUpgrade(callId, id);
        CancelDeadline();
    });

    public void CameraOff()
    {
        autoCameraCanceled = true;
        // Consent is invalidated synchronously, before the queued job can drain: a user who turns the
        // camera off must not keep it running for the length of the worker backlog.
        consent.CameraOff(callId);
        Post(() => SetCamera(false));
    }

    public void CameraOn() => Post(() =>
    {
        if (consent.CameraOn(callId, Eligible()) == CallVideoConsent.Result.Ready) SetCamera(true);
    });

    public void SwitchCamera() => Post(() =>
    {
        long gen = consent.Generation;
        if (!camera || !Current(gen) || !Eligible()) return;
        media.SwitchCamera(gen); State();
    });

    public void RemoteCamera(bool on, string facing, Func<bool> authorized) => Post(() =>
    {
        if (!authorized() || consent.CurrentPhase != CallVideoConsent.Phase.Video) return;
        if (!on) { autoCameraCanceled = true; consent.CameraOff(callId); SetCamera(false); return; }
        var result = consent.CameraOn(callId, Eligible());
        if (result is not (CallVideoConsent.Result.Ready or CallVideoConsent.Result.Busy)) return;
        SetCamera(true);
        if (camera && facing != "keep" && media.LocalMirror != (facing == "front"))
            media.SwitchCamera(consent.Generation);
    });

    public void RevokeCapture() => CameraOff();

    // Effects for CallVideoActions, which has already mutated the consent policy.
    public void ProposalAcceptedLocally(string id) => Post(() =>
    {
        if (id != consent.RequestId || consent.Generation != 0) return;
        Send(CallProtocol.VIDEO_REQUEST, 0, ("request", id));
        Deadline(id, 0, requestTimeoutMs);
    });

    public void UpgradeAcceptedLocally(string id) => Post(() =>
    {
        if (id != consent.RequestId) return;
        Send(CallProtocol.VIDEO_ACCEPT, 0, ("request", id));
        if (!caller) Deadline(id, 0, videoTimeoutMs);
        BeginIfCaller();
    });

    public void UpgradeDeclinedLocally(string id) => Post(() =>
    {
        Send(CallProtocol.VIDEO_DECLINE, 0, ("request", id));
        CancelDeadline();
    });

    public void CameraChosenLocally(bool on) => Post(() => SetCamera(on));

    void State()
    {
        if (revision == long.MaxValue) { Fail("failed", true); return; }
        Send(CallProtocol.VIDEO_STATE, consent.Generation, ("camera", camera), ("revision", ++revision));
    }

    void SetCamera(bool on)
    {
        long gen = consent.Generation;
        if (!Current(gen)) return;
        if (on)
        {
            if (!consent.CanCapture(callId, Eligible(), true, true)) return;
            media.StartCamera(gen); camera = true;
        }
        else { media.StopCamera(gen); camera = false; }
        State();
    }

    void Initialize(long gen)
    {
        offered = answered = localReady = remoteReady = camera = false;
        incomingIce = outgoingIce = 0; revision = 0;
        media.Initialize(gen, () => consent.CanCapture(callId, Eligible(), true, true));
        Deadline(consent.RequestId!, gen, videoTimeoutMs);
    }

    void BeginIfCaller()
    {
        if (!caller || consent.Generation != 0 || consent.RequestId == null) return;
        if (lastGeneration == long.MaxValue) { consent.DeclineUpgrade(callId, consent.RequestId); return; }
        long gen = lastGeneration + 1;
        if (!consent.AuthorizeGeneration(callId, consent.RequestId, gen)) return;
        lastGeneration = gen; Initialize(gen);
        string sdp = media.CreateOffer(gen); offered = true;
        Send(CallProtocol.OFFER, gen, ("sdp", sdp));
    }

    void Activate(long gen)
    {
        if (!Current(gen) || !answered || !localReady || !remoteReady) return;
        consent.MarkMediaReady(callId, gen); CancelDeadline(); SetCamera(true);
    }

    /// The owner supplies authenticated channel/sequence admission. Invalid video input never changes
    /// consent, generation, camera or media descriptions.
    public void Receive(CallProtocol.Frame? original)
    {
        if (original == null) return;
        var copy = Copy(original);
        Post(() => Handle(copy));
    }

    /// Called only by the signaling reader, outside controller locks. Admission is serialized behind
    /// preceding video work, so an ICE candidate arriving immediately after an OFFER cannot race its
    /// generation. The wait completes before any media work begins, and the shared call sequence is
    /// consumed in wire order.
    public void ReceiveAdmitted(CallProtocol.Frame? original, Admission admission)
    {
        if (Closed || original == null) return;
        var copy = Copy(original);
        var done = new ManualResetEventSlim(false);
        var accepted = false;
        Post(() =>
        {
            accepted = Permits(copy) && admission(copy);
            done.Set();
            if (accepted) Handle(copy);
        });
        if (!done.Wait(TimeSpan.FromSeconds(20)))
            throw new IOException("Video signaling admission unavailable");
    }

    void Handle(CallProtocol.Frame f)
    {
        if (callId != f.Cid || !CallVideoProtocol.Valid(f)) return;
        string? id = BodyString(f, "request");
        long gen = f.Gen;
        switch (f.T)
        {
            case CallProtocol.VIDEO_REQUEST:
            {
                var old = consent.RequestId;
                var result = consent.ReceiveRequest(callId, id!);
                if (result == CallVideoConsent.Result.Prompt)
                {
                    if (old != null) Send(CallProtocol.VIDEO_DECLINE, 0, ("request", old));
                    if (autoAcceptVideo && consent.AcceptUpgrade(callId, id!, Eligible()) == CallVideoConsent.Result.Ready)
                    {
                        Send(CallProtocol.VIDEO_ACCEPT, 0, ("request", id));
                        if (!caller) Deadline(id!, 0, videoTimeoutMs);
                        BeginIfCaller();
                    }
                    else Deadline(id!, 0, requestTimeoutMs);
                }
                else if (result is CallVideoConsent.Result.Declined or CallVideoConsent.Result.Busy
                    or CallVideoConsent.Result.Unsupported)
                {
                    Send(CallProtocol.VIDEO_DECLINE, 0, ("request", id));
                }
                return;
            }
            case CallProtocol.VIDEO_ACCEPT:
                if (consent.PeerAccepted(callId, id!) == CallVideoConsent.Result.Ready)
                {
                    if (!caller) Deadline(id!, 0, videoTimeoutMs);
                    BeginIfCaller();
                }
                return;
            case CallProtocol.VIDEO_DECLINE:
                if (consent.Generation == 0 && consent.DeclineUpgrade(callId, id!) == CallVideoConsent.Result.Declined)
                    CancelDeadline();
                return;
        }

        if (id != consent.RequestId) return;
        if (f.T == CallProtocol.OFFER)
        {
            if (caller || offered || consent.Generation != 0 || gen <= lastGeneration) return;
            if (!consent.AuthorizeGeneration(callId, id!, gen)) return;
            lastGeneration = gen; Initialize(gen); offered = true;
            string sdp = media.CreateAnswer(gen, BodyString(f, "sdp")!);
            answered = true;
            Send(CallProtocol.ANSWER, gen, ("sdp", sdp));
            return;
        }

        if (!Current(gen)) return;
        switch (f.T)
        {
            case CallProtocol.ANSWER:
                if (caller && offered && !answered)
                {
                    media.SetRemoteAnswer(gen, BodyString(f, "sdp")!); answered = true; Activate(gen);
                }
                break;
            case CallProtocol.ICE:
                if (++incomingIce > 128) { Fail("failed", true); return; }
                media.AddIce(gen, BodyString(f, "candidate")!, BodyString(f, "sdpMid")!, 0);
                break;
            case CallProtocol.MEDIA_READY:
                remoteReady = true; Activate(gen);
                break;
            case CallProtocol.VIDEO_STATE:
                consent.RemoteCameraState(callId, gen, BodyLong(f, "revision"), BodyBool(f, "camera"));
                break;
            case CallProtocol.ERROR:
                Fail("failed", false);
                break;
        }
    }

    void Fail(string code, bool notify)
    {
        long gen = consent.Generation;
        string? request = consent.RequestId;
        if (gen >= 2)
        {
            if (notify) try { Send(CallProtocol.ERROR, gen, ("code", code)); } catch { }
            consent.FailVideo(callId, gen); camera = false;
            // Video only. The audio connection and the call itself are untouched.
            try { media.Dispose(gen); } catch { }
        }
        else if (request != null)
        {
            if (notify) try { Send(CallProtocol.VIDEO_DECLINE, 0, ("request", request)); } catch { }
            consent.DeclineUpgrade(callId, request);
        }
        offered = answered = localReady = remoteReady = false;
        CancelDeadline();
        Publish();
    }

    /// Consent is revoked synchronously; media disposal stays off the caller's thread.
    public void Dispose()
    {
        lock (closedGate)
        {
            if (closed) return;
            closed = true;
        }
        long gen;
        lock (consent) gen = consent.Generation;
        consent.End();
        camera = false; Publish();
        CancelDeadline();
        lock (closedGate)
        {
            retryArmed = false;
            retryTimer.Change(Timeout.Infinite, Timeout.Infinite);
            retryTimer.Dispose();
        }
        deadlineTimer.Dispose();
        worker.Clear();
        if (!worker.TryPost(() => { try { if (gen >= 2) media.Dispose(gen); } finally { worker.Shutdown(); } }))
            worker.Shutdown();
    }

    /// Quiescence for tests and for orderly call teardown. Never called from a media callback or a
    /// controller lock.
    public bool AwaitIdle(int timeoutMs)
    {
        if (Closed) return worker.AwaitTermination(timeoutMs);
        return worker.AwaitMarker(timeoutMs);
    }

    static CallProtocol.Frame Copy(CallProtocol.Frame f) => new()
    {
        V = f.V, T = f.T, Cid = f.Cid, Seq = f.Seq, Gen = f.Gen,
        B = f.B == null ? null : new Dictionary<string, object>(f.B),
    };

    static string? BodyString(CallProtocol.Frame f, string key) =>
        f.B != null && f.B.TryGetValue(key, out var v) ? v as string : null;

    static long BodyLong(CallProtocol.Frame f, string key) =>
        f.B != null && f.B.TryGetValue(key, out var v) ? Convert.ToInt64(v) : 0;

    static bool BodyBool(CallProtocol.Frame f, string key) =>
        f.B != null && f.B.TryGetValue(key, out var v) && v is bool b && b;

    // A single-threaded worker with a bounded queue. Overflow is not silently dropped or run inline:
    // the caller is told, and the coordinator reacts by invalidating consent immediately.
    sealed class BoundedWorker
    {
        readonly System.Collections.Concurrent.BlockingCollection<Action> queue =
            new(new System.Collections.Concurrent.ConcurrentQueue<Action>(), 64);
        readonly Thread thread;
        volatile bool stopped;

        public BoundedWorker(string name)
        {
            thread = new Thread(Loop) { IsBackground = true, Name = name };
            thread.Start();
        }

        void Loop()
        {
            try
            {
                foreach (var job in queue.GetConsumingEnumerable())
                {
                    try { job(); } catch (Exception) { }
                }
            }
            // The collection completes on Shutdown and is disposed underneath us on coordinator
            // teardown; ObjectDisposedException derives from this, so one clause covers both.
            catch (InvalidOperationException) { }
        }

        public bool TryPost(Action job)
        {
            if (stopped) return false;
            try { return queue.TryAdd(job); }
            catch (InvalidOperationException) { return false; }
        }

        public void Clear()
        {
            try { while (queue.TryTake(out _)) { } } catch { }
        }

        public void Shutdown()
        {
            stopped = true;
            try { queue.CompleteAdding(); } catch { }
        }

        public bool AwaitTermination(int timeoutMs) => thread.Join(timeoutMs);

        public bool AwaitMarker(int timeoutMs)
        {
            using var marker = new ManualResetEventSlim(false);
            if (!TryPost(marker.Set)) return stopped;
            return marker.Wait(timeoutMs);
        }
    }
}
