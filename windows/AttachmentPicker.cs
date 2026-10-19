namespace LanMessenger;

// The stock OpenFileDialog only commits files and FolderBrowserDialog only folders.
// Keep one selection workflow for both existing transfer actions instead of mode toggles.
internal sealed class AttachmentPicker : Form
{
    readonly TextBox address = new() { Dock = DockStyle.Fill, AccessibleName = "Folder or file path" };
    readonly ListView entries = new() { Dock = DockStyle.Fill, View = View.Details, FullRowSelect = true, MultiSelect = false, HideSelection = false, VirtualMode = true, AccessibleName = "Files and folders" };
    readonly Label hint = new() { AutoSize = false, Dock = DockStyle.Fill, AutoEllipsis = true, TextAlign = ContentAlignment.MiddleLeft };
    readonly Button select = new() { Text = "Select", AutoSize = true, Enabled = false };
    string current = "";
    int navigation;
    Item[] listedItems=[];
    CancellationTokenSource? listingCancellation;
    internal string? SelectedPath { get; private set; }
    sealed record Item(string Path, string Name, bool Folder, long Size);

    internal AttachmentPicker(string title, string initialDirectory)
    {
        Text = title; Size = new(800, 560); MinimumSize = new(640, 420);
        StartPosition = FormStartPosition.CenterParent; Font = new("Segoe UI", 10);
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new(12), RowCount = 4, ColumnCount = 1 };
        layout.ColumnStyles.Add(new(SizeType.Percent, 100));
        layout.RowStyles.Add(new(SizeType.Absolute, 40)); layout.RowStyles.Add(new(SizeType.Absolute, 36));
        layout.RowStyles.Add(new(SizeType.Percent, 100)); layout.RowStyles.Add(new(SizeType.Absolute, 48));
        var location = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 3, RowCount = 1 };
        location.ColumnStyles.Add(new(SizeType.Absolute, 58)); location.ColumnStyles.Add(new(SizeType.Percent, 100)); location.ColumnStyles.Add(new(SizeType.Absolute, 58));
        var up = new Button { Text = "Up", Dock = DockStyle.Fill }; var go = new Button { Text = "Go", Dock = DockStyle.Fill };
        location.Controls.Add(up, 0, 0); location.Controls.Add(address, 1, 0); location.Controls.Add(go, 2, 0);
        layout.Controls.Add(location, 0, 0);
        layout.Controls.Add(new Label { Text = "Select a file or folder. Double-click a folder to browse it. Folders are sent as ZIPs.", AutoSize = true, Dock = DockStyle.Fill }, 0, 1);
        entries.Columns.Add("Name", 410); entries.Columns.Add("Type", 110); entries.Columns.Add("Size", 150);
        layout.Controls.Add(entries, 0, 2);
        var footer = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 1, Padding = new(0, 8, 0, 0) };
        footer.ColumnStyles.Add(new(SizeType.Percent, 100)); footer.ColumnStyles.Add(new(SizeType.AutoSize));
        var buttons = new FlowLayoutPanel { AutoSize = true, WrapContents = false };
        var cancel = new Button { Text = "Cancel", AutoSize = true, DialogResult = DialogResult.Cancel };
        buttons.Controls.AddRange([select, cancel]);footer.Controls.Add(hint, 0, 0);footer.Controls.Add(buttons, 1, 0);
        layout.Controls.Add(footer, 0, 3);Controls.Add(layout);CancelButton = cancel;AcceptButton = select;
        select.Click += (_, _) => CommitSelection();
        entries.RetrieveVirtualItem += (_, e) => {
            var item=listedItems[e.ItemIndex];
            e.Item=new ListViewItem([item.Name,item.Folder?"Folder":"File",item.Folder?"":$"{item.Size:N0} bytes"]);
        };
        entries.SelectedIndexChanged += (_, _) => { select.Enabled = entries.Enabled && entries.SelectedIndices.Count == 1; hint.Text = select.Enabled ? listedItems[entries.SelectedIndices[0]].Path : "Choose an item, or enter its full path above."; };
        entries.DoubleClick += async (_, _) => {
            if (!entries.Enabled || entries.SelectedIndices.Count != 1) return;
            var item = listedItems[entries.SelectedIndices[0]];
            if (item.Folder) await Navigate(item.Path); else CommitSelection();
        };
        async Task Go()
        {
            try{
                var path = Path.GetFullPath(address.Text.Trim().Trim('"'), string.IsNullOrEmpty(current) ? Environment.CurrentDirectory : current);
                if (File.Exists(path)) { SelectedPath = path; DialogResult = DialogResult.OK; return; }
                await Navigate(path);
            }catch(Exception error) when(error is IOException or ArgumentException or UnauthorizedAccessException){hint.Text=error.Message;}
        }
        go.Click += async (_, _) => await Go();
        address.KeyDown += async (_, e) => { if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; await Go(); } };
        up.Click += async (_, _) => { if(string.IsNullOrEmpty(current))return;var parent = Directory.GetParent(current); if (parent != null) await Navigate(parent.FullName); };
        Shown += async (_, _) => await Navigate(initialDirectory);
    }
    void CommitSelection()
    {
        if (!entries.Enabled || entries.SelectedIndices.Count != 1) return;
        SelectedPath = listedItems[entries.SelectedIndices[0]].Path; DialogResult = DialogResult.OK;
    }
    async Task Navigate(string path)
    {
        int request = ++navigation;
        listingCancellation?.Cancel();
        var cancellation=new CancellationTokenSource();listingCancellation=cancellation;
        select.Enabled = false; entries.Enabled = false; hint.Text = "Loading…";
        try
        {
            path = Path.GetFullPath(path);
            var directory = path;
            var items = await Task.Run(() => Directory.EnumerateFileSystemEntries(directory).Select(p => {
                cancellation.Token.ThrowIfCancellationRequested();
                var folder = Directory.Exists(p); return new Item(p, Path.GetFileName(p), folder, folder ? 0 : new FileInfo(p).Length);
            }).OrderByDescending(i => i.Folder).ThenBy(i => i.Name, StringComparer.CurrentCultureIgnoreCase).ToArray());
            if (IsDisposed || request != navigation) return;
            current = path; address.Text = current;
            entries.SelectedIndices.Clear();
            listedItems=[new(current,"(This folder)",true,0),..items];
            entries.VirtualListSize=listedItems.Length;entries.Invalidate();
            hint.Text = "Choose an item, or enter its full path above.";
        }
        catch(OperationCanceledException){}
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException)
        {
            if (!IsDisposed && request == navigation) { hint.Text = error.Message; address.Text = current; }
        }
        finally {
            if(listingCancellation==cancellation)listingCancellation=null;
            cancellation.Dispose();
            if (!IsDisposed && request == navigation) { entries.Enabled = true; select.Enabled = entries.SelectedIndices.Count == 1; }
        }
    }
    protected override void Dispose(bool disposing){if(disposing)listingCancellation?.Cancel();base.Dispose(disposing);}
}
