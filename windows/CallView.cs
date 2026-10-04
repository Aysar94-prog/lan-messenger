namespace LanMessenger;

// A simple, functional, non-modal call window — not yet the full Messenger-style reference layout
// Android's CallView adopted (teal header, peer photo, floating hang-up disc); this first pass
// prioritizes a correct, working call over matching that graphic design. Driven entirely by
// CallSession snapshots via Render(), same as Android's CallUi/CallView split.
//
// A02b adds a video stage, but only for a call both peers proved is video-capable: a voice-only call
// keeps exactly the window it had before, so nothing about the existing call experience changes for a
// peer that cannot do video.
sealed class CallView : Form
{
    public event Action? AcceptClicked;
    public event Action? DeclineClicked;
    public event Action? HangupClicked;
    public event Action<bool>? MuteClicked;
    // Video commands. Each one is a *request*: the controller routes it through CallVideoActions,
    // which rechecks live eligibility before anything touches a camera. The view decides nothing.
    public event Action<bool>? AcceptVideoClicked;
    public event Action? RequestVideoClicked;
    public event Action? DeclineVideoClicked;
    public event Action<bool>? CameraClicked;

    readonly Label nameLabel = new() { AutoSize = false, Dock = DockStyle.Top, Height = 40, TextAlign = ContentAlignment.MiddleCenter, Font = new Font("Segoe UI", 16, FontStyle.Bold) };
    readonly Label stateLabel = new() { AutoSize = false, Dock = DockStyle.Top, Height = 30, TextAlign = ContentAlignment.MiddleCenter, Font = new Font("Segoe UI", 11) };
    readonly Label hintLabel = new() { AutoSize = false, Dock = DockStyle.Top, Height = 40, TextAlign = ContentAlignment.MiddleCenter, ForeColor = Color.DimGray, Font = new Font("Segoe UI", 9) };

    // The stage is a plain Panel rather than a PictureBox: a stage with nothing to show must still
    // exist and must still own the preview's layout, so the preview cannot end up floating over the
    // controls. Hidden entirely for a voice-only call.
    readonly Panel stage = new() { Dock = DockStyle.Fill, BackColor = Color.FromArgb(8, 32, 30), Visible = false };
    readonly Panel remoteStage = new() { Dock = DockStyle.Fill, BackColor = Color.FromArgb(8, 32, 30) };
    readonly Label remotePlaceholder = new() { Dock = DockStyle.Fill, Text = "No video", TextAlign = ContentAlignment.MiddleCenter, ForeColor = Color.FromArgb(120, 150, 148), Font = new Font("Segoe UI", 11) };
    // The local preview is draggable and its remembered position is clamped into the stage on every
    // layout. CallVideoPlacement exists precisely because a remembered drag position would otherwise
    // survive a window resize and leave the preview sitting over the controls.
    readonly Panel preview = new() { Size = new Size(160, 120), BackColor = Color.FromArgb(20, 60, 58), Visible = false, Cursor = Cursors.SizeAll };
    readonly Label previewPlaceholder = new() { Dock = DockStyle.Fill, Text = "Your camera", TextAlign = ContentAlignment.MiddleCenter, ForeColor = Color.FromArgb(150, 180, 178), Font = new Font("Segoe UI", 8) };
    readonly CallVideoPlacement placement = new();
    // WinForms has no SizableDialog: a resizable dialog IS a Sizable ToolWindow. The fixed small form
    // is kept for a voice-only call and swapped for the resizable one the moment video is possible.
    const FormBorderStyle videoCapableStyle = FormBorderStyle.SizableToolWindow;

    readonly FlowLayoutPanel buttons = new() { Dock = DockStyle.Bottom, Height = 60, FlowDirection = FlowDirection.LeftToRight, WrapContents = false, Padding = new Padding(10) };
    readonly Button accept = new() { Text = "Accept", AutoSize = true, BackColor = Color.FromArgb(37, 211, 102), ForeColor = Color.White };
    readonly Button acceptVideo = new() { Text = "Accept with video", AutoSize = true, Visible = false };
    readonly Button decline = new() { Text = "Decline", AutoSize = true, BackColor = Color.FromArgb(211, 47, 47), ForeColor = Color.White };
    readonly Button declineVideo = new() { Text = "Decline video", AutoSize = true, Visible = false };
    readonly Button hangup = new() { Text = "Hang up", AutoSize = true, BackColor = Color.FromArgb(211, 47, 47), ForeColor = Color.White };
    readonly Button mute = new() { Text = "Mute", AutoSize = true };
    readonly Button camera = new() { Text = "Camera off", AutoSize = true, Visible = false };
    readonly Button requestVideo = new() { Text = "Add video", AutoSize = true, Visible = false };
    bool muted;
    bool dragging;
    Point dragOrigin;
    bool videoCapable;

    public CallView(string peerName, bool isIncoming)
    {
        Text = "Call"; Size = new Size(360, 260); StartPosition = FormStartPosition.CenterScreen;
        // Start as the small fixed voice window exactly as before. It is widened to a resizable form
        // only once a call is known to be video-capable -- a voice-only call must be unchanged.
        FormBorderStyle = FormBorderStyle.FixedDialog; MaximizeBox = false; MinimizeBox = true;
        BackColor = Color.FromArgb(14, 82, 76); nameLabel.ForeColor = Color.White; stateLabel.ForeColor = Color.White;
        nameLabel.Text = peerName;
        stateLabel.Text = isIncoming ? "Incoming call…" : "Calling…";

        remoteStage.Controls.Add(remotePlaceholder);
        preview.Controls.Add(previewPlaceholder);
        stage.Controls.Add(remoteStage);
        stage.Controls.Add(preview);

        Controls.Add(stage);
        Controls.Add(hintLabel); Controls.Add(stateLabel); Controls.Add(nameLabel); Controls.Add(buttons);

        accept.Click += (_, _) => AcceptClicked?.Invoke();
        acceptVideo.Click += (_, _) => AcceptVideoClicked?.Invoke(true);
        decline.Click += (_, _) => DeclineClicked?.Invoke();
        declineVideo.Click += (_, _) => DeclineVideoClicked?.Invoke();
        hangup.Click += (_, _) => HangupClicked?.Invoke();
        mute.Click += (_, _) => { muted = !muted; mute.Text = muted ? "Unmute" : "Mute"; MuteClicked?.Invoke(muted); };
        camera.Click += (_, _) => CameraClicked?.Invoke(camera.Text.StartsWith("Camera off", StringComparison.Ordinal));
        requestVideo.Click += (_, _) => RequestVideoClicked?.Invoke();

        buttons.Controls.Add(accept); buttons.Controls.Add(acceptVideo);
        buttons.Controls.Add(decline); buttons.Controls.Add(declineVideo);
        buttons.Controls.Add(hangup); buttons.Controls.Add(mute);
        buttons.Controls.Add(camera); buttons.Controls.Add(requestVideo);
        ApplyButtonVisibility(isIncoming, active: false, videoLive: false);

        // Layout, not the constructor, positions the preview: the clamped position depends on the
        // stage size, which does not exist until the window handle does.
        stage.Resize += (_, _) => LayoutPreview();
        preview.MouseDown += PreviewMouseDown;
        preview.MouseMove += PreviewMouseMove;
        preview.MouseUp += PreviewMouseUp;
    }

    void PreviewMouseDown(object? sender, MouseEventArgs e)
    {
        if (e.Button != MouseButtons.Left) return;
        dragging = true; dragOrigin = e.Location;
    }

    void PreviewMouseMove(object? sender, MouseEventArgs e)
    {
        if (!dragging) return;
        // The mouse position is relative to the preview, so the stage-relative target adds the
        // preview's current offset rather than assuming the preview is at the origin.
        placement.Move(preview.Left + e.X - dragOrigin.X, preview.Top + e.Y - dragOrigin.Y);
        LayoutPreview();
    }

    void PreviewMouseUp(object? sender, MouseEventArgs e)
    {
        if (e.Button != MouseButtons.Left) return;
        dragging = false;
        preview.BringToFront();
    }

    void LayoutPreview()
    {
        if (preview.Parent == null) return;
        var at = placement.Position(stage.ClientSize.Width, stage.ClientSize.Height, preview.Width, preview.Height, 16);
        preview.Location = new Point((int)at[0], (int)at[1]);
        preview.BringToFront();
    }

    /// Reveal the video stage. Called only once a call is known to be video-capable, so the video
    /// buttons never appear for a call that cannot do video.
    public void SetVideoCapable(bool capable, bool invitedVideo)
    {
        if (IsDisposed || videoCapable == capable) return;
        videoCapable = capable;
        stage.Visible = capable;
        preview.Visible = capable;
        // WinForms has no resizable *dialog*; a resizable dialog is a sizeable tool window. The style
        // is live-settable, so the window can become resizable here rather than at construction.
        FormBorderStyle = capable ? videoCapableStyle : FormBorderStyle.FixedDialog;
        MaximizeBox = capable;
        if (capable) { Size = new Size(720, 540); LayoutPreview(); }
    }

    /// Reflect the live coordinator snapshot. The remote-video frame itself comes from the adapter
    /// later; until then the stage says plainly that there is no video rather than showing a stale or
    /// blank-looking "connected" surface.
    public void RenderVideo(CallVideoCoordinator.Snapshot? video)
    {
        if (IsDisposed) return;
        var live = video != null && video.Phase == CallVideoConsent.Phase.Video;
        // Text and enabled-state only. Visibility belongs to ApplyButtonVisibility, which is the one
        // place that knows whether the call is still ringing, active or finished -- setting it here
        // too would make the two writers race on snapshot order.
        camera.Text = live && video!.LocalCamera ? "Camera off" : "Camera on";
        camera.Enabled = live;
        remotePlaceholder.Text = live && !video!.RemoteCamera ? "Their camera is off" : "No video";
    }

    void ApplyButtonVisibility(bool isIncoming, bool active, bool videoLive)
    {
        accept.Visible = isIncoming && !active;
        acceptVideo.Visible = isIncoming && !active && videoCapable;
        decline.Visible = !active; // "Decline" for incoming-ringing, repurposed as Cancel for outgoing-ringing
        decline.Text = isIncoming ? "Decline" : "Cancel";
        // Only offered while ringing. Once the call is up, turning video on is an upgrade *request*,
        // and offering both would leave two different controls meaning the same thing.
        declineVideo.Visible = isIncoming && !active && videoCapable;
        hangup.Visible = active;
        mute.Visible = active;
        camera.Visible = active && videoLive;
        requestVideo.Visible = active && videoCapable && !videoLive;
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
        // Video-capable is a property of the call, not of a particular frame: it is set as soon as
        // both peers are known to speak v2, so the stage appears before any video exists.
        SetVideoCapable(snap.VideoCapable, snap.InvitedVideo);
        var videoLive = snap.Video != null && snap.Video.Phase == CallVideoConsent.Phase.Video;
        switch (snap.State)
        {
            case CallProtocol.State.OutgoingRinging: stateLabel.Text = "Ringing…"; break;
            case CallProtocol.State.IncomingRinging: stateLabel.Text = "Incoming call…"; break;
            case CallProtocol.State.Connecting: stateLabel.Text = "Connecting…"; break;
            case CallProtocol.State.Connected: stateLabel.Text = FormatDuration(snap.ElapsedMs(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds())); break;
            case CallProtocol.State.Ending:
            case CallProtocol.State.Idle: stateLabel.Text = EndReasonLabel(snap.EndReason); break;
        }
        var finished = snap.State.Terminal();
        // Ringing is the only state with no action button, and active is everything else that is not
        // finished -- so "who pressed Call" only decides the incoming/outgoing wording, not visibility.
        var active = !finished && snap.State is not (CallProtocol.State.OutgoingRinging or CallProtocol.State.IncomingRinging);
        ApplyButtonVisibility(snap.State == CallProtocol.State.IncomingRinging, active, videoLive);
        if (finished)
        {
            accept.Visible = acceptVideo.Visible = decline.Visible = declineVideo.Visible = false;
            hangup.Visible = mute.Visible = camera.Visible = requestVideo.Visible = false;
        }
        RenderVideo(snap.Video);
    }
}
