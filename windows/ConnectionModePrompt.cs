namespace LanMessenger;

// Small modal chooser shown when the user clicks "Go offline"/"Messages only" while connected.
// Replaces a plain MessageBox because the choice is three-way (offline / messages-only / cancel,
// or offline / back-to-fully-online / cancel) and WinForms' stock MessageBox tops out at
// Yes/No/Cancel with fixed button text.
static class ConnectionModePrompt
{
    public enum Choice { Cancel, Offline, MessagesOnly, FullOnline }

    // currentlyMessagesOnly: whether the user is already in "Messages only" (changes the second
    // option from "switch to Messages only" to "go back to fully online").
    public static Choice Show(IWin32Window owner, bool currentlyMessagesOnly)
    {
        using var form = new Form
        {
            Text = "Go offline",
            FormBorderStyle = FormBorderStyle.FixedDialog,
            StartPosition = FormStartPosition.CenterParent,
            MinimizeBox = false, MaximizeBox = false, ShowInTaskbar = false,
            ClientSize = new Size(360, 170),
        };
        var label = new Label
        {
            Text = "Stay reachable for messages, or go fully offline?",
            AutoSize = false, Dock = DockStyle.Top, Height = 50, Padding = new Padding(16, 14, 16, 0),
        };
        var offlineButton = new Button { Text = "Offline (everything)", AutoSize = true, Dock = DockStyle.Top, Margin = new Padding(16, 6, 16, 0) };
        var secondButton = new Button
        {
            Text = currentlyMessagesOnly ? "Allow calls again (fully online)" : "Messages only (no calls)",
            AutoSize = true, Dock = DockStyle.Top, Margin = new Padding(16, 6, 16, 0),
        };
        var cancelButton = new Button { Text = "Cancel", AutoSize = true, Dock = DockStyle.Top, Margin = new Padding(16, 10, 16, 0) };
        var result = Choice.Cancel;
        offlineButton.Click += (_, _) => { result = Choice.Offline; form.Close(); };
        secondButton.Click += (_, _) => { result = currentlyMessagesOnly ? Choice.FullOnline : Choice.MessagesOnly; form.Close(); };
        cancelButton.Click += (_, _) => form.Close();
        var panel = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, Padding = new Padding(0, 0, 0, 10) };
        panel.Controls.AddRange([label, offlineButton, secondButton, cancelButton]);
        form.Controls.Add(panel);
        form.ShowDialog(owner);
        return result;
    }
}
