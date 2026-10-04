namespace LanMessenger;

// Wires the call layer (CallController/CallSettings/WebRtcCallMedia) into the existing app shell,
// mirroring the architecture split the Android/plan-v006 addendum calls for: ChatWindow (the one
// long-lived shell, like Android's MessengerService) owns the controller for the process lifetime;
// CallView is a thin, non-modal presentation window driven entirely by CallSession snapshots.
sealed partial class ChatWindow
{
    CallController? callController;
    CallSettings? callSettings;
    CallView? callView;
    CallRingtone? ringtone;

    void InitializeCalls(string dataDirectory)
    {
        callSettings = new CallSettings(dataDirectory);
        callController = new CallController(engine, callSettings) { MediaFactory = new WebRtcCallMedia.Factory() };
        // The capability responder is one switch shared with the engine, so a staged build can never
        // answer "I speak v2" from the engine while the controller refuses to negotiate v2.
        callController.VideoEnabled = engine.CallVideoSupport();
        engine.CallVideoSupport = () => callController.VideoEnabled;
        // No ICallVideoMedia factory is installed. The seam exists and the whole negotiation is wired,
        // but until a native video adapter is built there is nothing to create, so a video-capable
        // call negotiates as v2 audio-only. That is a legitimate outcome on the wire (ACCEPT carrying
        // media=audio) rather than a failure path.
        callController.CameraEligible = _ => false;
        callController.ApplyRemoteSpeakerRoute = () => false;
        callController.SetCallback(snap => { if (IsDisposed || !IsHandleCreated) return; try { BeginInvoke(new Action(() => OnCallStateChanged(snap))); } catch { } });
        engine.CallConnectReceived += (peerId, stream, callId) =>
        {
            // Runs on the engine's own accept-loop thread; CallChannel starts its own reader and
            // CallController is internally thread-safe, so no UI-thread marshaling is needed here
            // — only the eventual snapshot callback above touches the UI thread.
            CallChannel.AdoptIncoming(stream, peerId, callController);
        };
        engine.CallEngineOffline += () => callController.OnOffline();
        engine.Forgotten += peerId => callController.OnPeerRevoked(peerId);
    }

    sealed class EngineTransportFactory(PeerEngine engine, CallController controller) : CallController.ITransportFactory
    {
        public CallController.ITransport Open(string callId)
        {
            var stream = engine.OpenCallConnectionAsync(peerIdForOpen!, callId).GetAwaiter().GetResult();
            return CallChannel.OpenOutgoing(stream, peerIdForOpen!, callId, controller);
        }
        public string? peerIdForOpen;
    }

    void StartCallToSelected()
    {
        if (callController == null) return;
        if (selected == null) { MessageBox.Show(this, "Choose a contact first.", "Call"); return; }
        var peer = engine.Peers.FirstOrDefault(p => p.Id == selected);
        if (peer == null) { MessageBox.Show(this, "Group calls are not supported yet.", "Call"); return; }
        if (!peer.Trusted) { MessageBox.Show(this, "Verify this device before calling.", "Call"); return; }
        if (!peer.Online) { MessageBox.Show(this, peer.Name + " is offline.", "Call"); return; }
        if (callController.HasActive()) { MessageBox.Show(this, "Already in a call.", "Call"); return; }
        var peerId = selected;
        // StartCall opens the outbound TCP+TLS connection synchronously (via the transport
        // factory) while holding the controller's internal lock -- genuine network I/O that can
        // take seconds or hang outright against an unresponsive peer. Calling it directly from
        // this button's click handler would freeze the whole UI ("Not Responding") for that
        // whole time; CallController is already internally thread-safe and marshals its own
        // snapshot callback back to the UI thread, so the actual start can run on a background
        // thread with only the failure path needing to come back here.
        Task.Run(() =>
        {
            try
            {
                var factory = new EngineTransportFactory(engine, callController) { peerIdForOpen = peerId };
                callController.StartCall(peerId, factory);
            }
            catch (Exception e)
            {
                try { BeginInvoke(new Action(() => MessageBox.Show(this, e.Message, "Could not start the call"))); } catch { }
            }
        });
    }

    // The 1 Hz tick ChatWindow already runs (TickVoiceRecording/TickVoicePlayback/Render) also
    // refreshes the live duration clock while Connected — a snapshot-driven callback alone only
    // fires on state transitions, not every second.
    void TickCallView()
    {
        if (callView == null || callController == null) return;
        var snap = callController.Snapshot();
        if (snap == null) return;
        var peerName = engine.Peers.FirstOrDefault(p => p.Id == snap.PeerId)?.Name ?? snap.PeerId;
        callView.Render(snap, peerName);
    }

    void AttachCallViewHandlers(CallView view, bool isIncoming)
    {
        view.AcceptClicked += () => { if (callController != null) _ = callController.AcceptAsync(); };
        // The same button means "Decline" to an incoming call and "Cancel" to one of ours. The two are
        // genuinely different wire messages and, more importantly, different things to tell the other
        // person -- a cancellation must never be presented as the peer declining.
        view.DeclineClicked += () =>
        {
            if (callController == null) return;
            if (isIncoming) callController.Decline(); else callController.CancelOutgoing();
        };
        view.MuteClicked += muted => callController?.SetMuted(muted);
        // Every video button is a request through the controller's command boundary. None of them
        // opens a camera directly, and none of them reports success: the controller republishes a
        // snapshot, which is what actually redraws the controls.
        view.AcceptVideoClicked += video =>
        {
            var id = callController?.Snapshot()?.CallId;
            if (id == null) return;
            ReportVideoOutcome(() => callController!.AnswerVideo(id, video));
        };
        view.DeclineVideoClicked += () =>
        {
            var id = callController?.Snapshot()?.CallId;
            if (id != null) ReportVideoOutcome(() => callController!.AnswerVideo(id, video: false));
        };
        view.RequestVideoClicked += () =>
        {
            var id = callController?.Snapshot()?.CallId;
            if (id == null) return;
            // A request is a question to the other person, so "no" here is an ordinary answer, not a
            // failure the user needs an error box for. Only an outright refusal is worth saying out loud.
            try { callController!.RequestVideo(id); } catch (IOException e) { ShowVideoRefusal(e.Message); }
        };
        view.CameraClicked += on =>
        {
            var id = callController?.Snapshot()?.CallId;
            if (id == null) return;
            ReportVideoOutcome(() => callController!.SetCamera(id, on));
        };
        view.FormClosed += (_, _) => callView = null;
    }

    void ReportVideoOutcome(Func<CallVideoConsent.Result?> action)
    {
        try
        {
            var result = action();
            if (result is null or CallVideoConsent.Result.Ready or CallVideoConsent.Result.Voice) return;
            ShowVideoRefusal(VideoResultMessage(result.Value));
        }
        catch (IOException e) { ShowVideoRefusal(e.Message); }
    }

    static string VideoResultMessage(CallVideoConsent.Result result) => result switch
    {
        CallVideoConsent.Result.Unsupported => "This device cannot do video right now. The call continues with audio only.",
        CallVideoConsent.Result.Declined => "Video was declined.",
        CallVideoConsent.Result.Busy => "Something else is using the camera. The call continues with audio only.",
        CallVideoConsent.Result.Ignored => "That video request no longer applies.",
        _ => "Video is not available right now. The call continues with audio only.",
    };

    void ShowVideoRefusal(string message)
    {
        if (IsDisposed || !IsHandleCreated) return;
        try { BeginInvoke(new Action(() => MessageBox.Show(this, message, "Video"))); } catch { }
    }

    void OnCallStateChanged(CallSession snap)
    {
        var peerName = engine.Peers.FirstOrDefault(p => p.Id == snap.PeerId)?.Name ?? snap.PeerId;
        if (callView == null && !snap.State.Terminal())
        {
            callView = new CallView(peerName, isIncoming: snap.State == CallProtocol.State.IncomingRinging);
            AttachCallViewHandlers(callView, isIncoming: snap.State == CallProtocol.State.IncomingRinging);
            callView.Show(this);
        }
        callView?.Render(snap, peerName);
        UpdateRingtone(snap.State);
        if (snap.State.Terminal())
        {
            var closing = callView;
            if (closing != null)
            {
                var closeTimer = new System.Windows.Forms.Timer { Interval = 2500 };
                closeTimer.Tick += (_, _) => { closeTimer.Stop(); closeTimer.Dispose(); try { closing.Close(); } catch { } };
                closeTimer.Start();
            }
        }
    }

    // Plays an audible ring tone for as long as the call is ringing on either end -- without this
    // there is no audible cue at all that a call is incoming or being placed, only the (easy to
    // miss) CallView window. Stops the instant the state leaves *Ringing, whether because it was
    // answered, declined, canceled or timed out. Goes through CallAudioPlayback (the same winmm
    // path real call audio uses) rather than the OS system-sound event -- real-device testing
    // showed the latter can be silently inaudible regardless of speaker volume.
    void UpdateRingtone(CallProtocol.State state)
    {
        bool shouldRing = state == CallProtocol.State.IncomingRinging || state == CallProtocol.State.OutgoingRinging;
        if (shouldRing && ringtone == null)
        {
            ringtone = new CallRingtone();
            ringtone.Start();
        }
        else if (!shouldRing && ringtone != null)
        {
            ringtone.Dispose(); ringtone = null;
        }
    }
}
