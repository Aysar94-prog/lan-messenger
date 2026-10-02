namespace LanMessenger;

// A simple, functional, non-modal call window — not yet the full Messenger-style reference layout
// Android's CallView adopted (teal header, peer photo, floating hang-up disc); this first pass
// prioritizes a correct, working call over matching that graphic design. Driven entirely by
// CallSession snapshots via Render(), same as Android's CallUi/CallView split.
sealed class CallView : Form
{
    public event Action? AcceptClicked;
    public event Action? DeclineClicked;
    public event Action? HangupClicked;
    public event Action<bool>? MuteClicked;

    readonly Label nameLabel = new() { AutoSize = false, Dock = DockStyle.Top, Height = 40, TextAlign = ContentAlignment.MiddleCenter, Font = new Font("Segoe UI", 16, FontStyle.Bold) };
    readonly Label stateLabel = new() { AutoSize = false, Dock = DockStyle.Top, Height = 30, TextAlign = ContentAlignment.MiddleCenter, Font = new Font("Segoe UI", 11) };
    readonly Label hintLabel = new() { AutoSize = false, Dock = DockStyle.Top, Height = 40, TextAlign = ContentAlignment.MiddleCenter, ForeColor = Color.DimGray, Font = new Font("Segoe UI", 9) };
    readonly FlowLayoutPanel buttons = new() { Dock = DockStyle.Bottom, Height = 60, FlowDirection = FlowDirection.LeftToRight, WrapContents = false, Padding = new Padding(10) };
    readonly Button accept = new() { Text = "Accept", AutoSize = true, BackColor = Color.FromArgb(37, 211, 102), ForeColor = Color.White };
    readonly Button decline = new() { Text = "Decline", AutoSize = true, BackColor = Color.FromArgb(211, 47, 47), ForeColor = Color.White };
    readonly Button hangup = new() { Text = "Hang up", AutoSize = true, BackColor = Color.FromArgb(211, 47, 47), ForeColor = Color.White };
    readonly Button mute = new() { Text = "Mute", AutoSize = true };
    bool muted;

    public CallView(string peerName, bool isIncoming)
    {
        Text = "Call"; Size = new Size(360, 260); StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedDialog; MaximizeBox = false; MinimizeBox = true;
        BackColor = Color.FromArgb(14, 82, 76); nameLabel.ForeColor = Color.White; stateLabel.ForeColor = Color.White;
        nameLabel.Text = peerName;
        stateLabel.Text = isIncoming ? "Incoming call…" : "Calling…";

        Controls.Add(hintLabel); Controls.Add(stateLabel); Controls.Add(nameLabel); Controls.Add(buttons);

        accept.Click += (_, _) => AcceptClicked?.Invoke();
        decline.Click += (_, _) => DeclineClicked?.Invoke();
        hangup.Click += (_, _) => HangupClicked?.Invoke();
        mute.Click += (_, _) => { muted = !muted; mute.Text = muted ? "Unmute" : "Mute"; MuteClicked?.Invoke(muted); };

        buttons.Controls.Add(accept); buttons.Controls.Add(decline); buttons.Controls.Add(hangup); buttons.Controls.Add(mute);
        ApplyButtonVisibility(isIncoming, active: false);
    }

    void ApplyButtonVisibility(bool isIncoming, bool active)
    {
        accept.Visible = isIncoming && !active;
        decline.Visible = !active; // "Decline" for incoming-ringing, repurposed as Cancel for outgoing-ringing
        decline.Text = isIncoming ? "Decline" : "Cancel";
        hangup.Visible = active;
        mute.Visible = active;
    }

    static string FormatDuration(long ms)
    {
        var s = Math.Max(0, ms / 1000);
        return s >= 3600 ? $"{s / 3600}:{(s % 3600) / 60:D2}:{s % 60:D2}" : $"{s / 60}:{s % 60:D2}";
    }

    static string EndReasonLabel(CallProtocol.EndReason? reason) => reason switch
    {
        CallProtocol.EndReason.LocalHangup or CallProtocol.EndReason.RemoteHangup => "Call ended",
        CallProtocol.EndReason.Declined or CallProtocol.EndReason.LocalDecline => "Declined",
        CallProtocol.EndReason.BusyRemote => "Busy",
        CallProtocol.EndReason.Canceled => "Canceled",
        CallProtocol.EndReason.TimeoutRinging => "No answer",
        CallProtocol.EndReason.TimeoutMedia => "Connection failed",
        CallProtocol.EndReason.SignalingLost => "Disconnected",
        CallProtocol.EndReason.NetworkFailure => "Connection failed",
        CallProtocol.EndReason.MediaError => "Call failed",
        CallProtocol.EndReason.Offline => "Call ended",
        CallProtocol.EndReason.EngineShutdown => "Call ended",
        CallProtocol.EndReason.GlareResolved => "Call ended",
        _ => "Call ended",
    };

    public void Render(CallSession snap, string peerName)
    {
        if (IsDisposed) return;
        nameLabel.Text = peerName;
        switch (snap.State)
        {
            case CallProtocol.State.OutgoingRinging: stateLabel.Text = "Ringing…"; ApplyButtonVisibility(false, false); break;
            case CallProtocol.State.IncomingRinging: stateLabel.Text = "Incoming call…"; ApplyButtonVisibility(true, false); break;
            case CallProtocol.State.Connecting: stateLabel.Text = "Connecting…"; ApplyButtonVisibility(false, true); break;
            case CallProtocol.State.Connected: stateLabel.Text = FormatDuration(snap.ElapsedMs(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds())); ApplyButtonVisibility(false, true); break;
            case CallProtocol.State.Ending:
            case CallProtocol.State.Idle:
                stateLabel.Text = EndReasonLabel(snap.EndReason);
                accept.Visible = decline.Visible = hangup.Visible = mute.Visible = false;
                break;
        }
    }
}
