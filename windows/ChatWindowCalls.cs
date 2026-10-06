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
    string? callDiagnosticPath;
    string? recipientGrantCallId;
    string? recipientGrantConfirmedCallId;
    long recipientGrantNextRefresh;
    bool recipientGrantRefreshing;

    void InitializeCalls(string dataDirectory)
    {
        callDiagnosticPath = Path.Combine(dataDirectory, "call-diagnostics.log");
        callSettings = new CallSettings(dataDirectory);
        callController = new CallController(engine, callSettings) { MediaFactory = new WebRtcCallMedia.Factory() };
        // One switch, read by both the CALLCAPS responder and the negotiator, so the engine can never
        // claim VP8 to a peer that the controller would then refuse to negotiate. The controller's
        // getter also requires an installed VideoMediaFactory. Missing or incompatible native code
        // leaves this false and gives the peer a clean v1 voice call.
        //
        // An earlier version of these two lines seeded the flag from the engine's old `()=>true`
        // default and then made the engine read it back, which pinned it to true with no path to
        // false. Installing the adapter means setting VideoEnabled = true as well as assigning the
        // factory; do not add a read-back here.
        engine.CallVideoSupport = () => callController.VideoEnabled;
        // The native adapter supplies an independent VP8 path and consent-gated default-camera capture.
        // Factory construction stays lazy so opening the app or ringing never loads a camera.
        try
        {
            using var abi = new LanMessenger.Windows.CallVideoNativeBridge();
            callController.VideoMediaFactory = () => new LanMessenger.Windows.CallVideoNativeMedia();
            callController.VideoEnabled = true;
        }
        catch (Exception e) { CallDiagnostic(e, "video-backend-unavailable", e.Message); }
        // Hardware access occurs only inside StartCamera, after the call-bound consent gate.
        callController.CameraEligible = _ => callController.VideoEnabled;
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
        callController.AttachVideoSinks(callView.LocalFrameSink, callView.RemoteFrameSink);
        RefreshRecipientGrant(snap);
        callView.Render(snap, peerName, RecipientControlMask(snap), engine.LastCallVideoProbeStatus);
    }

    int RecipientControlMask(CallSession snap) => snap.IsCaller && snap.State == CallProtocol.State.Connected
        && recipientGrantConfirmedCallId == snap.CallId
        ? engine.RemoteControlDisplayMask(snap.PeerId) : 0;

    void RefreshRecipientGrant(CallSession snap)
    {
        if (!snap.IsCaller || snap.State != CallProtocol.State.Connected)
        {
            recipientGrantCallId = null; recipientGrantConfirmedCallId = null; recipientGrantNextRefresh = 0; return;
        }
        long now = Environment.TickCount64;
        if (recipientGrantCallId != snap.CallId) { recipientGrantCallId = snap.CallId; recipientGrantConfirmedCallId = null; recipientGrantNextRefresh = 0; }
        if (recipientGrantRefreshing || now < recipientGrantNextRefresh) return;
        recipientGrantRefreshing = true; recipientGrantNextRefresh = now + 5000;
        string callId = snap.CallId, peerId = snap.PeerId;
        _ = Task.Run(() => engine.RefreshRemoteCallGrant(peerId)).ContinueWith(refresh =>
        {
            bool confirmed = refresh.Status == TaskStatus.RanToCompletion && refresh.Result;
            try
            {
                BeginInvoke(new Action(() =>
                {
                    recipientGrantRefreshing = false;
                    var current = callController?.Snapshot();
                    if (current?.CallId == callId && callView != null)
                    {
                        recipientGrantConfirmedCallId = confirmed ? callId : null;
                        var name = engine.Peers.FirstOrDefault(p => p.Id == current.PeerId)?.Name ?? current.PeerId;
                        callView.Render(current, name, RecipientControlMask(current), engine.LastCallVideoProbeStatus);
                    }
                }));
            }
            catch { recipientGrantRefreshing = false; }
        });
    }

    void AttachCallViewHandlers(CallView view, bool isIncoming)
    {
        // Answering is genuinely asynchronous -- it opens media -- so the task is not awaited here. It used
        // to be `_ = callController.AcceptAsync();` and nothing else, which meant an exception was
        // discarded: an incoming call that failed to accept produced no message and no log entry, and
        // the window was left showing "Connecting…" with a hang-up button and no way to make
        // progress. The continuation is now observed so a refused or undeliverable answer is
        // reported the same way every other video refusal already is.
        view.AcceptClicked += () =>
        {
            if (callController == null) return;
            _ = Task.Run(() => callController.AcceptAsync()).ContinueWith(accepted =>
            {
                if (accepted.IsCompletedSuccessfully) return;
                var cause = accepted.Exception?.GetBaseException().Message ?? "The call could not be answered.";
                CallDiagnostic(accepted.Exception?.GetBaseException(), "accept-failed", cause);
                ShowVideoRefusal(cause);
            }, TaskContinuationOptions.ExecuteSynchronously);
        };
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
            Task.Run(() =>
            {
                try
                {
                    var result = callController!.AnswerVideo(id, video);
                    if (video && result == CallVideoConsent.Result.Voice)
                        ShowVideoRefusal("Video is unavailable. The call was answered with audio only.");
                    else if (result is not (CallVideoConsent.Result.Ready or CallVideoConsent.Result.Voice))
                        ShowVideoRefusal(VideoResultMessage(result ?? CallVideoConsent.Result.Ignored));
                }
                catch (Exception e) { CallDiagnostic(e, "accept-failed", e.Message); ShowVideoRefusal(e.Message); }
            });
        };
        view.DeclineVideoClicked += () =>
        {
            var id = callController?.Snapshot()?.CallId;
            if (id != null) ReportVideoOutcome(() => callController!.AnswerVideo(id, video: false));
        };
        view.HangupClicked += () => callController?.Hangup();
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
        view.RemoteCameraClicked += (on, facing) =>
        {
            var current = callController?.Snapshot();
            if (current == null || (RecipientControlMask(current) & PeerEngine.TrustedRemoteCamera) == 0) return;
            callController!.SetRemoteCamera(current.CallId, on, facing);
        };
        view.RemoteSpeakerClicked += on =>
        {
            var current = callController?.Snapshot();
            if (current == null || (RecipientControlMask(current) & PeerEngine.TrustedRemoteSpeaker) == 0) return;
            callController!.SetRemoteSpeaker(current.CallId, on);
        };
        view.FormClosed += (_, _) => { callController?.AttachVideoSinks(null, null); callView = null; };
    }

    // Everything appended to call-diagnostics.log is tab-separated one value per field, so a cause
    // containing a tab or a newline would silently corrupt every field after it -- and a cause is
    // whatever an exception happened to say. Collapsed to a single line here, at the only place a
    // cause can enter the file.
    static string OneLine(string? value)
    {
        if (string.IsNullOrEmpty(value)) return "none";
        var flat = value.Replace('\t', ' ').Replace('\r', ' ').Replace('\n', ' ');
        return flat.Length <= 300 ? flat : flat.Substring(0, 300) + "...";
    }

    // The same log, for a failure that produced no snapshot of its own -- currently the answer path,
    // where the exception happens before any state change. Shares callDiagnosticPath deliberately:
    // splitting call evidence across two files is what made the original diagnosis expensive.
    void CallDiagnostic(Exception? error, string event_, string? cause)
    {
        try
        {
            File.AppendAllText(callDiagnosticPath!, $"{DateTime.UtcNow:O}\tevent={event_}"+
                $"\tcause={OneLine(cause ?? error?.Message)}"+
                $"{(error != null ? "\ttype=" + OneLine(error.GetType().FullName) : "")}{Environment.NewLine}");
        }
        // Same rule as the snapshot writer: a log that cannot be written must not take the app down.
        catch { }
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
        try
        {
            File.AppendAllText(callDiagnosticPath!, $"{DateTime.UtcNow:O}\tstate={snap.State}\tcaller={snap.IsCaller}"+
                $"\tv2={snap.VideoCapable}\tinvitedVideo={snap.InvitedVideo}\tvideo={snap.Video?.Phase.ToString() ?? "none"}"+
                $"\tend={snap.EndReason?.ToString() ?? "none"}"+
                // Printed only on the line that ends a call. Carrying it on every snapshot would
                // reprint an earlier call's reason against a later, healthy one. The immutable
                // snapshot owns its cause even if another call starts before this UI job runs.
                $"{(snap.EndReason != null ? "\tcause=" + OneLine(snap.FailureReason) : "")}{Environment.NewLine}");
        }
        catch { }
        var peerName = engine.Peers.FirstOrDefault(p => p.Id == snap.PeerId)?.Name ?? snap.PeerId;
        if (callView == null && !snap.State.Terminal())
        {
            callView = new CallView(peerName, isIncoming: snap.State == CallProtocol.State.IncomingRinging);
            AttachCallViewHandlers(callView, isIncoming: snap.State == CallProtocol.State.IncomingRinging);
            callController?.AttachVideoSinks(callView.LocalFrameSink, callView.RemoteFrameSink);
            callView.Show(this);
        }
        RefreshRecipientGrant(snap);
        callView?.Render(snap, peerName, RecipientControlMask(snap), engine.LastCallVideoProbeStatus);
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
