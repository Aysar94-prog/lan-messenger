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

    void InitializeCalls(string dataDirectory)
    {
        callSettings = new CallSettings(dataDirectory);
        callController = new CallController(engine, callSettings) { MediaFactory = new WebRtcCallMedia.Factory() };
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
        try
        {
            var factory = new EngineTransportFactory(engine, callController) { peerIdForOpen = selected };
            callController.StartCall(selected, factory);
        }
        catch (Exception e) { MessageBox.Show(this, e.Message, "Could not start the call"); }
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

    void OnCallStateChanged(CallSession snap)
    {
        var peerName = engine.Peers.FirstOrDefault(p => p.Id == snap.PeerId)?.Name ?? snap.PeerId;
        if (snap.State == CallProtocol.State.IncomingRinging && callView == null)
        {
            callView = new CallView(peerName, isIncoming: true);
            callView.AcceptClicked += () => { if (callController != null) _ = callController.AcceptAsync(); };
            callView.DeclineClicked += () => callController?.Decline();
            callView.HangupClicked += () => callController?.Hangup();
            callView.MuteClicked += muted => callController?.SetMuted(muted);
            callView.FormClosed += (_, _) => callView = null;
            callView.Show(this);
        }
        else if (callView == null && !snap.State.Terminal())
        {
            callView = new CallView(peerName, isIncoming: false);
            callView.AcceptClicked += () => { if (callController != null) _ = callController.AcceptAsync(); };
            callView.DeclineClicked += () => callController?.CancelOutgoing();
            callView.HangupClicked += () => callController?.Hangup();
            callView.MuteClicked += muted => callController?.SetMuted(muted);
            callView.FormClosed += (_, _) => callView = null;
            callView.Show(this);
        }
        callView?.Render(snap, peerName);
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
}
