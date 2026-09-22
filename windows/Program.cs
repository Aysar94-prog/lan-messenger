using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace LanMessenger;
static class Program
{
    [STAThread]
    static void Main() {
        using var instance=new Mutex(true,"Local\\LANMessengerPeer-"+Environment.UserName,out var first);
        if(!first){MessageBox.Show("LAN Messenger is already running.","LAN Messenger");return;}
        ApplicationConfiguration.Initialize();
        try{Application.Run(new ChatWindow());}catch(Exception e){MessageBox.Show("LAN Messenger could not start. "+e.Message,"LAN Messenger");}
    }
}
sealed class ChatWindow : Form
{
    readonly PeerEngine engine;
    readonly NotifyIcon tray=new(){Icon=SystemIcons.Information,Text="LAN Messenger",Visible=true};
    readonly Button attach=new(){Text="Attach",AutoSize=true};
    readonly Button clear=new(){Text="Clear chat",AutoSize=true};
    readonly Button members=new(){Text="Members",AutoSize=true};
    readonly ComboBox files=new(){Width=230,DropDownStyle=ComboBoxStyle.DropDownList};
    readonly Button saveFile=new(){Text="Save file",AutoSize=true};
    readonly Button preview=new(){Text="Preview image",AutoSize=true};
    static readonly Color Accent=Color.FromArgb(47,91,231),Ink=Color.FromArgb(25,39,64);
    record FileItem(PeerEngine.Message Message){public override string ToString()=>Message.FileName;}
    readonly Button verify=new(){Text="Verify device",AutoSize=true};
    readonly Label groupNotice=new(){Text="Group messages and their attachments are automatically deleted after 7 days of being sent.",AutoSize=true,ForeColor=Color.SlateGray,Font=new Font("Segoe UI",8.5f),Margin=new Padding(12,10,0,0),Visible=false};
    bool exiting; string? notificationPeer;
    public int NotificationCount {get;private set;}
    readonly System.Windows.Forms.Timer timer = new() { Interval = 1000 };
    readonly ListBox contacts = new() { Dock=DockStyle.Fill, IntegralHeight=false, BorderStyle=BorderStyle.None, HorizontalScrollbar=true };
    readonly TextBox profile = new() { Width=180, MaxLength=30, PlaceholderText="Your display name" };
    readonly Label status = new() { AutoSize=true, Dock=DockStyle.Fill, ForeColor=Color.DimGray };
    readonly Label heading = new() { Text="Choose a contact", Dock=DockStyle.Fill, Font=new Font("Segoe UI",17,FontStyle.Bold), AutoSize=false, AutoEllipsis=true };
    readonly FlowLayoutPanel feed = new() { Dock=DockStyle.Fill, AutoScroll=true, FlowDirection=FlowDirection.TopDown, WrapContents=false, BackColor=Color.FromArgb(249,251,255), Padding=new Padding(8) };
    readonly FlowLayoutPanel attachmentDraft = new() { Dock=DockStyle.Fill, FlowDirection=FlowDirection.LeftToRight, WrapContents=false, AutoScroll=true, Visible=false, BackColor=Color.FromArgb(235,240,250), Padding=new Padding(8) };
    readonly TextBox composer = new() { Dock=DockStyle.Fill, Multiline=true, MaxLength=2000, PlaceholderText="Write a message…", Enabled=false };
    readonly Button send = new() { Text="Send", Dock=DockStyle.Fill, Enabled=false };
    readonly Dictionary<string,string> drafts=[];
    string? selected;
    byte[]? pendingAttachment; string pendingAttachmentName=""; string? pendingAttachmentTarget; RowStyle pendingAttachmentRow=null!;
    string lastFeed="", lastContacts="";
    bool rendering;
    record ContactItem(string Id,string Name,string Detail,bool Group=false,int Unread=0) { public override string ToString()=>Name; }

    public ChatWindow() : this(null,null) {}
    public ChatWindow(string? dataDirectory,IStorageProtector? protector)
    {
        Text="LAN Messenger"; Size=new Size(1140,810); MinimumSize=new Size(940,650); StartPosition=FormStartPosition.CenterScreen;
        Font=new Font("Segoe UI",11); BackColor=Color.FromArgb(243,246,252); RightToLeft=RightToLeft.No;
        var data=dataDirectory??Environment.GetEnvironmentVariable("LAN_MESSENGER_DATA") ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"LanMessenger");
        engine=new PeerEngine(data,Environment.UserName,protector); profile.Text=engine.Name;
        var root=new TableLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(20),ColumnCount=1,RowCount=5};
        root.ColumnStyles.Add(new(SizeType.Percent,100));
        root.RowStyles.Add(new(SizeType.Absolute,70));root.RowStyles.Add(new(SizeType.Absolute,46));root.RowStyles.Add(new(SizeType.Absolute,42));root.RowStyles.Add(new(SizeType.Percent,100));root.RowStyles.Add(new(SizeType.Absolute,36));
        root.Controls.Add(new Label{Text="LAN Messenger  /  Your local workspace",Font=new Font("Segoe UI",23,FontStyle.Bold),AutoSize=true,ForeColor=Color.FromArgb(20,37,58)},0,0);
        var toolbar=new FlowLayoutPanel{Dock=DockStyle.Fill,WrapContents=false};var save=new Button{Text="Save name",AutoSize=true};var scan=new Button{Text="Refresh",AutoSize=true};var add=new Button{Text="Add by IP",AutoSize=true};
        var createGroup=new Button{Text="New group",AutoSize=true};toolbar.Controls.AddRange([profile,save,scan,add,createGroup]);createGroup.Click+=(_,_)=>CreateGroup();root.Controls.Add(toolbar,0,1);root.Controls.Add(status,0,2);
        var split=new SplitContainer{Size=new Size(950,460),Dock=DockStyle.Fill,SplitterDistance=300,FixedPanel=FixedPanel.Panel1,Panel1MinSize=220,Panel2MinSize=280};
        var people=new TableLayoutPanel{Dock=DockStyle.Fill,RowCount=2,ColumnCount=1};people.ColumnStyles.Add(new(SizeType.Percent,100));people.RowStyles.Add(new(SizeType.Absolute,35));people.RowStyles.Add(new(SizeType.Percent,100));people.Controls.Add(new Label{Text="CONVERSATIONS",AutoSize=true},0,0);people.Controls.Add(contacts,0,1);split.Panel1.Controls.Add(people);
        var chat=new TableLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(15,0,0,0),ColumnCount=1,RowCount=5};chat.ColumnStyles.Add(new(SizeType.Percent,100));chat.RowStyles.Add(new(SizeType.Absolute,48));chat.RowStyles.Add(new(SizeType.Absolute,44));chat.RowStyles.Add(new(SizeType.Percent,100));pendingAttachmentRow=new RowStyle(SizeType.Absolute,0);chat.RowStyles.Add(pendingAttachmentRow);chat.RowStyles.Add(new(SizeType.Absolute,88));chat.Controls.Add(heading,0,0);var actions=new FlowLayoutPanel{Dock=DockStyle.Fill,WrapContents=false};actions.Controls.AddRange([verify,members,clear,groupNotice]);chat.Controls.Add(actions,0,1);chat.Controls.Add(feed,0,2);chat.Controls.Add(attachmentDraft,0,3);
        var input=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=3,Padding=new Padding(0,10,0,0)};input.ColumnStyles.Add(new(SizeType.Percent,100));input.ColumnStyles.Add(new(SizeType.Absolute,95));input.Controls.Add(composer,0,0);input.Controls.Add(send,1,0);input.ColumnStyles.Add(new(SizeType.Absolute,85));input.Controls.Add(attach,2,0);chat.Controls.Add(input,0,4);split.Panel2.Controls.Add(chat);root.Controls.Add(split,0,3);
        root.Controls.Add(new Label{Text="Encrypted connection and local history • Verify the safety code on both devices before chatting",AutoSize=true,ForeColor=Color.DimGray},0,4);Controls.Add(root);
        contacts.DrawMode=DrawMode.OwnerDrawFixed;contacts.ItemHeight=64;contacts.BackColor=Color.White;
        contacts.DrawItem+=(_,e)=>{if(e.Index<0)return;var item=(ContactItem)contacts.Items[e.Index];bool active=(e.State&DrawItemState.Selected)!=0;using var bg=new SolidBrush(active?Color.FromArgb(232,239,255):Color.White);e.Graphics.FillRectangle(bg,e.Bounds);using var badge=new SolidBrush(item.Group?Color.FromArgb(227,220,251):Color.FromArgb(216,235,248));e.Graphics.FillEllipse(badge,e.Bounds.X+10,e.Bounds.Y+13,36,36);TextRenderer.DrawText(e.Graphics,item.Group?"G":item.Name[..Math.Min(1,item.Name.Length)].ToUpperInvariant(),Font,new Rectangle(e.Bounds.X+10,e.Bounds.Y+18,36,28),Accent,TextFormatFlags.HorizontalCenter);using var bold=new Font(Font,FontStyle.Bold);TextRenderer.DrawText(e.Graphics,item.Name,bold,new Rectangle(e.Bounds.X+55,e.Bounds.Y+10,e.Bounds.Width-60,24),Ink,TextFormatFlags.EndEllipsis);using var small=new Font("Segoe UI",9);TextRenderer.DrawText(e.Graphics,item.Detail,small,new Rectangle(e.Bounds.X+55,e.Bounds.Y+35,e.Bounds.Width-60,22),Color.SlateGray,TextFormatFlags.EndEllipsis);
            if(item.Unread>0){var count=item.Unread>99?"99+":item.Unread.ToString();int d=22;var badgeRect=new Rectangle(e.Bounds.Right-d-12,e.Bounds.Y+(e.Bounds.Height-d)/2,d,d);using var unread=new SolidBrush(Color.FromArgb(37,211,102));e.Graphics.FillEllipse(unread,badgeRect);using var tiny=new Font("Segoe UI",8,FontStyle.Bold);TextRenderer.DrawText(e.Graphics,count,tiny,badgeRect,Color.White,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter);}};
        StyleButtons(root);send.BackColor=Accent;send.ForeColor=Color.White;feed.Resize+=(_,_)=>{if(selected!=null){lastFeed="";Render();}};
        attach.Click+=async(_,_)=>await AttachFile();clear.Click+=(_,_)=>ClearChat();members.Click+=(_,_)=>ShowMembers();saveFile.Click+=(_,_)=>SaveAttachment();preview.Click+=(_,_)=>PreviewImage();
        verify.Click+=(_,_)=>VerifyDevice();
        var trayMenu=new ContextMenuStrip();trayMenu.Items.Add("Open LAN Messenger",null,(_,_)=>RestoreWindow());trayMenu.Items.Add("Test notification",null,(_,_)=>ShowNotification(null,"LAN Messenger","This is a test notification from LAN Messenger."));trayMenu.Items.Add("Exit",null,(_,_)=>{exiting=true;Close();});tray.ContextMenuStrip=trayMenu;tray.DoubleClick+=(_,_)=>RestoreWindow();tray.BalloonTipClicked+=(_,_)=>RestoreWindow(notificationPeer);
        engine.Received+=m=>{if(!IsDisposed&&IsHandleCreated)try{BeginInvoke(new Action(()=>{Render();var peer=engine.Peers.FirstOrDefault(p=>p.Id==m.From);ShowNotification(m.GroupId.Length>0?m.GroupId:m.From,m.GroupId.Length>0?engine.DisplayName(m.GroupId):peer?.Name??"LAN Messenger","New encrypted message");}));}catch{}};
        FormClosing+=(_,e)=>{if(!exiting&&e.CloseReason==CloseReason.UserClosing){e.Cancel=true;Hide();ShowNotification(null,"LAN Messenger","Still running. Right-click the tray icon and choose Exit to stop.");}};
        save.Click+=(_,_)=>{try{engine.Rename(profile.Text);profile.Text=engine.Name;}catch(Exception e){MessageBox.Show(e.Message,"Could not save name");}};
        scan.Click+=async(_,_)=>{await engine.Announce();Render();};add.Click+=async(_,_)=>await AddAddress();
        contacts.SelectedIndexChanged+=(_,_)=>{if(rendering)return;if(selected!=null)drafts[selected]=composer.Text;var next=(contacts.SelectedItem as ContactItem)?.Id;if(pendingAttachmentTarget!=null&&pendingAttachmentTarget!=next)ClearPendingAttachment();selected=next;composer.Text=selected!=null&&drafts.TryGetValue(selected,out var draft)?draft:"";lastFeed="";Render();};
        send.Click+=(_,_)=>Send();composer.KeyDown+=(_,e)=>{if(e.KeyCode==Keys.Enter&&!e.Shift){e.SuppressKeyPress=true;Send();}};
        timer.Tick+=(_,_)=>Render();Shown+=(_,_)=>{try{engine.Start();timer.Start();Render();}catch(Exception e){status.Text="Could not start: "+e.Message;}};
        FormClosed+=(_,_)=>{timer.Stop();tray.Visible=false;tray.Dispose();engine.Dispose();};
        feed.Controls.Add(MessageLabel("People running LAN Messenger on your network appear automatically.\n\nContacts and messages stay saved after you close the app.\n\nOffline? Write a message now. It stays Queued until both devices are connected.\n\nNo contacts yet? Open the new app on another device on the same Wi-Fi. You can use Add by IP if discovery is blocked.",11,Ink,Math.Max(300,feed.ClientSize.Width-30)));
    }
    void Send(){if(selected==null||!send.Enabled||(pendingAttachment==null&&string.IsNullOrWhiteSpace(composer.Text)))return;try{if(pendingAttachment!=null)engine.QueueFile(selected,composer.Text,pendingAttachmentName,pendingAttachment);else engine.Queue(selected,composer.Text);composer.Clear();drafts.Remove(selected);ClearPendingAttachment();Render();}catch(Exception e){MessageBox.Show(e.Message,"Message not saved");}}
    void Render()
    {
        if(selected!=null&&Visible&&WindowState!=FormWindowState.Minimized)try{engine.MarkRead(selected);}catch{}
        var peers=engine.Peers.OrderByDescending(p=>p.Online).ThenBy(p=>p.Name).ToArray();
        status.Text=$"{(engine.Running?"Available on your network":"Offline")}  ·  {peers.Count(p=>p.Online)} online  ·  {engine.Pending} queued  ·  Your ID: {engine.Id[..8]}";
        var groups=engine.Groups;
        var entries=groups.Select(g=>new ContactItem(g.Id,g.Name,$"Group · {g.Members.Length} members",true,engine.Unread(g.Id))).Concat(peers.Select(p=>new ContactItem(p.Id,p.Name,$"{(p.Online?"Online":"Offline")} · {p.Security}",false,engine.Unread(p.Id)))).ToArray();
        string signature=string.Join("|",entries.Select(p=>$"{p.Id}:{p.Name}:{p.Detail}:{p.Unread}"));
        if(signature!=lastContacts){rendering=true;contacts.BeginUpdate();contacts.Items.Clear();contacts.Items.AddRange(entries);for(int i=0;i<contacts.Items.Count;i++)if(((ContactItem)contacts.Items[i]).Id==selected)contacts.SelectedIndex=i;contacts.EndUpdate();rendering=false;lastContacts=signature;}
        var peer=peers.FirstOrDefault(p=>p.Id==selected);var group=groups.FirstOrDefault(g=>g.Id==selected);
        verify.Enabled=peer!=null;members.Enabled=group!=null;clear.Enabled=selected!=null;send.Enabled=group!=null||peer?.Trusted==true;attach.Enabled=send.Enabled;composer.Enabled=selected!=null;groupNotice.Visible=group!=null;
        if(peer==null&&group==null){heading.Text="Your conversations, together";return;}
        heading.Text=group!=null?$"{group.Name} · {group.Members.Length} members":$"{peer!.Name} · {(peer.Online?"Online":"Offline")}";
        var items=engine.Messages(selected!);var signatureFeed=selected+string.Join("|",items.Select(m=>m.Id+":"+m.Status));
        if(lastFeed!=signatureFeed){lastFeed=signatureFeed;ClearFeed();
            if(items.Length==0)feed.Controls.Add(MessageLabel("A fresh start. Send a message or share a file.\n\nMessages are encrypted between verified devices.",11,Ink,Math.Max(300,feed.ClientSize.Width-30)));
            foreach(var m in items)feed.Controls.Add(MessageCard(m));
            feed.PerformLayout();feed.AutoScrollPosition=new Point(0,feed.VerticalScroll.Maximum);var previous=(files.SelectedItem as FileItem)?.Message.Id;files.Items.Clear();foreach(var m in items.Where(m=>m.FileName.Length>0))files.Items.Add(new FileItem(m));if(files.Items.Count>0){files.SelectedIndex=0;for(int i=0;i<files.Items.Count;i++)if(((FileItem)files.Items[i]!).Message.Id==previous)files.SelectedIndex=i;}}
        files.Enabled=saveFile.Enabled=preview.Enabled=files.Items.Count>0;
    }
    void ClearFeed(){foreach(Control control in feed.Controls.Cast<Control>().ToArray())control.Dispose();feed.Controls.Clear();}
    static Label MessageLabel(string text,float size,Color color,int width,bool bold=false)=>new(){Text=text,AutoSize=true,MaximumSize=new Size(width,0),Font=new Font("Segoe UI",size,bold?FontStyle.Bold:FontStyle.Regular),ForeColor=color,Margin=new Padding(0,2,0,2)};
    Control MessageCard(PeerEngine.Message message)
    {
        bool mine=message.From==engine.Id;int width=Math.Max(260,Math.Min(460,feed.ClientSize.Width-45));
        var card=new FlowLayoutPanel{FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoSize=true,AutoSizeMode=AutoSizeMode.GrowAndShrink,MinimumSize=new Size(width,0),MaximumSize=new Size(width,10000),Padding=new Padding(12,8,12,8),Margin=new Padding(mine?Math.Max(8,feed.ClientSize.Width-width-25):4,6,4,6),BackColor=mine?Color.FromArgb(220,233,255):Color.White};
        card.Controls.Add(MessageLabel(mine?"You":engine.DisplayName(message.From),10,mine?Accent:Ink,width-24,true));
        if(message.FileName.Length==0)card.Controls.Add(MessageLabel(message.Text,12,Ink,width-24));
        else{
            var thumbnail=TryImageThumbnail(message,Math.Min(420,width-24),320);
            if(thumbnail!=null){var picture=new PictureBox{Image=thumbnail,SizeMode=PictureBoxSizeMode.Zoom,Width=width-24,Height=Math.Max(150,Math.Min(320,(int)Math.Round((double)(width-24)*thumbnail.Height/thumbnail.Width))),Cursor=Cursors.Hand,BackColor=Color.FromArgb(232,236,243),Margin=new Padding(0,4,0,4)};picture.Click+=(_,_)=>PreviewImage(message);picture.Disposed+=(_,_)=>thumbnail.Dispose();card.Controls.Add(picture);}
            card.Controls.Add(MessageLabel($"{message.FileName}  ·  {message.FileSize/1024.0:0.#} KB",10,Ink,width-24));
            var actions=new FlowLayoutPanel{AutoSize=true,WrapContents=false,Margin=new Padding(0)};var save=new Button{Text="Save",AutoSize=true};save.Click+=(_,_)=>SaveAttachment(message);actions.Controls.Add(save);if(thumbnail!=null){var open=new Button{Text="Open",AutoSize=true};open.Click+=(_,_)=>PreviewImage(message);actions.Controls.Add(open);}StyleButtons(actions);card.Controls.Add(actions);
            if(message.Text.Length>0)card.Controls.Add(MessageLabel(message.Text,12,Ink,width-24));
        }
        var when=DateTimeOffset.FromUnixTimeMilliseconds(message.Time).LocalDateTime.ToString("MMM d, HH:mm");
        if(mine){bool seen=message.Status.StartsWith("Seen");var ticks=seen||message.Status.StartsWith("Delivered")?"✓✓":"✓";card.Controls.Add(MessageLabel($"{when}  ·  {ticks} {message.Status}",9,seen?Accent:Color.SlateGray,width-24));}
        else card.Controls.Add(MessageLabel(when,9,Color.SlateGray,width-24));
        return card;
    }
    Image? TryImageThumbnail(PeerEngine.Message message,int maxWidth,int maxHeight){try{return TryImageThumbnail(engine.ReadAttachment(message),maxWidth,maxHeight);}catch{return null;}}
    static Image? TryImageThumbnail(byte[] data,int maxWidth,int maxHeight){try{using var stream=new MemoryStream(data);using var source=Image.FromStream(stream);if((long)source.Width*source.Height>32000000)throw new IOException();double scale=Math.Min(1,Math.Min((double)maxWidth/source.Width,(double)maxHeight/source.Height));var result=new Bitmap(Math.Max(1,(int)(source.Width*scale)),Math.Max(1,(int)(source.Height*scale)));using var graphics=Graphics.FromImage(result);graphics.InterpolationMode=System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic;graphics.DrawImage(source,new Rectangle(0,0,result.Width,result.Height));return result;}catch{return null;}}
    static void StyleButtons(Control root){foreach(Control c in root.Controls){if(c is Button b){b.FlatStyle=FlatStyle.Flat;b.FlatAppearance.BorderColor=Color.FromArgb(217,226,239);b.BackColor=Color.White;b.ForeColor=Ink;b.Padding=new Padding(2);b.Cursor=Cursors.Hand;}StyleButtons(c);}}
    async Task AttachFile()
    {
        if(selected==null)return;var target=selected;
        using var dialog=new OpenFileDialog{Title="Share a photo or file · up to 10 MB",Filter="All files (*.*)|*.*|Images (*.png;*.jpg;*.jpeg;*.gif)|*.png;*.jpg;*.jpeg;*.gif"};
        if(dialog.ShowDialog(this)!=DialogResult.OK)return;
        try{if(new FileInfo(dialog.FileName).Length>PeerEngine.MaxFileSize)throw new IOException("Files must be 10 MB or smaller.");attach.Enabled=false;var data=await Task.Run(()=>File.ReadAllBytes(dialog.FileName));if(selected!=target)return;pendingAttachment=data;pendingAttachmentName=Path.GetFileName(dialog.FileName);pendingAttachmentTarget=target;RenderPendingAttachment();composer.Focus();}catch(Exception e){MessageBox.Show(this,e.Message,"Could not prepare file");}finally{attach.Enabled=send.Enabled;}
    }
    void RenderPendingAttachment(){foreach(Control control in attachmentDraft.Controls.Cast<Control>().ToArray())control.Dispose();attachmentDraft.Controls.Clear();if(pendingAttachment==null){attachmentDraft.Visible=false;pendingAttachmentRow.Height=0;return;}attachmentDraft.Visible=true;pendingAttachmentRow.Height=150;var thumb=TryImageThumbnail(pendingAttachment,150,125);if(thumb!=null){var image=new PictureBox{Image=thumb,Width=150,Height=125,SizeMode=PictureBoxSizeMode.Zoom,Cursor=Cursors.Hand,BackColor=Color.FromArgb(220,226,237)};image.Click+=(_,_)=>PreviewImage(pendingAttachment,pendingAttachmentName);image.Disposed+=(_,_)=>thumb.Dispose();attachmentDraft.Controls.Add(image);}var details=new FlowLayoutPanel{FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoSize=true};details.Controls.Add(MessageLabel(pendingAttachmentName+"  ·  "+(pendingAttachment.Length/1024.0).ToString("0.#")+" KB\nReady to send",10,Ink,330,true));var remove=new Button{Text="Remove",AutoSize=true};remove.Click+=(_,_)=>ClearPendingAttachment();details.Controls.Add(remove);StyleButtons(details);attachmentDraft.Controls.Add(details);}
    void ClearPendingAttachment(){pendingAttachment=null;pendingAttachmentName="";pendingAttachmentTarget=null;RenderPendingAttachment();}
    void SaveAttachment(){if(files.SelectedItem is FileItem item)SaveAttachment(item.Message);}
    void SaveAttachment(PeerEngine.Message message){using var dialog=new SaveFileDialog{FileName=message.FileName,Title="Save attachment",Filter="All files|*.*"};if(dialog.ShowDialog(this)!=DialogResult.OK)return;try{File.WriteAllBytes(dialog.FileName,engine.ReadAttachment(message));}catch(Exception e){MessageBox.Show(this,e.Message,"Could not save attachment");}}
    void PreviewImage(){if(files.SelectedItem is FileItem item)PreviewImage(item.Message);}
    void PreviewImage(PeerEngine.Message message){try{using var bytes=new MemoryStream(engine.ReadAttachment(message));using var decoded=Image.FromStream(bytes);if((long)decoded.Width*decoded.Height>32000000)throw new IOException("Image dimensions are too large for preview");using var picture=new Bitmap(decoded);using var window=new Form{Text=message.FileName,Size=new Size(900,700),StartPosition=FormStartPosition.CenterParent,BackColor=Color.FromArgb(20,24,31),KeyPreview=true};window.Controls.Add(new PictureBox{Dock=DockStyle.Fill,SizeMode=PictureBoxSizeMode.Zoom,Image=picture,BackColor=window.BackColor});window.KeyDown+=(_,e)=>{if(e.KeyCode==Keys.Escape)window.Close();};window.ShowDialog(this);}catch{MessageBox.Show(this,"This attachment cannot be opened as an image. Use Save to export it.","Image preview");}}
    void PreviewImage(byte[] data,string name){try{using var bytes=new MemoryStream(data);using var decoded=Image.FromStream(bytes);if((long)decoded.Width*decoded.Height>32000000)throw new IOException();using var picture=new Bitmap(decoded);using var window=new Form{Text=name,Size=new Size(900,700),StartPosition=FormStartPosition.CenterParent,BackColor=Color.FromArgb(20,24,31),KeyPreview=true};window.Controls.Add(new PictureBox{Dock=DockStyle.Fill,SizeMode=PictureBoxSizeMode.Zoom,Image=picture,BackColor=window.BackColor});window.KeyDown+=(_,e)=>{if(e.KeyCode==Keys.Escape)window.Close();};window.ShowDialog(this);}catch{MessageBox.Show(this,"This file is not a supported image.","Image preview");}}
    void ClearChat(){if(selected==null)return;if(MessageBox.Show(this,"Clear this conversation on this device? Local messages and attachments will be removed and pending sends cancelled. Other devices keep their copies.","Clear conversation",MessageBoxButtons.YesNo,MessageBoxIcon.Question)!=DialogResult.Yes)return;try{engine.ClearConversation(selected);drafts.Remove(selected);composer.Clear();ClearPendingAttachment();lastFeed="";Render();}catch(Exception e){MessageBox.Show(this,e.Message,"Could not clear conversation");}}
    void ShowMembers(){var g=engine.Groups.FirstOrDefault(g=>g.Id==selected);if(g==null)return;MessageBox.Show(this,string.Join("\n",g.Members.Select(id=>engine.DisplayName(id)+(id==engine.Id?" (you)":engine.Peers.Any(p=>p.Id==id&&p.Trusted)?" · Verified":" · Verify in People")))+"\n\nEvery pair must verify each other to exchange group messages. Membership is fixed for this group.",g.Name+" · Members");}
    void CreateGroup()
    {
        var peers=engine.Peers.Where(p=>p.Trusted).ToArray();if(peers.Length<2){MessageBox.Show(this,"Verify at least two contacts before creating a group.","New group");return;}
        using var dialog=new Form{Text="New group",Size=new Size(440,470),StartPosition=FormStartPosition.CenterParent,Font=Font};
        var layout=new TableLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(18),RowCount=4,ColumnCount=1};layout.RowStyles.Add(new(SizeType.Absolute,40));layout.RowStyles.Add(new(SizeType.Absolute,45));layout.RowStyles.Add(new(SizeType.Percent,100));layout.RowStyles.Add(new(SizeType.Absolute,45));
        var name=new TextBox{PlaceholderText="Group name",MaxLength=50,Dock=DockStyle.Fill};var list=new CheckedListBox{Dock=DockStyle.Fill,CheckOnClick=true};foreach(var p in peers)list.Items.Add(p.Name+" · "+p.Id[..6]);var create=new Button{Text="Create group",Dock=DockStyle.Fill};layout.Controls.Add(name,0,0);layout.Controls.Add(new Label{Text="Choose 2–15 verified contacts",AutoSize=true},0,1);layout.Controls.Add(list,0,2);layout.Controls.Add(create,0,3);dialog.Controls.Add(layout);
        create.Click+=(_,_)=>{try{var id=engine.CreateGroup(name.Text,list.CheckedIndices.Cast<int>().Select(i=>peers[i].Id));if(selected!=null)drafts[selected]=composer.Text;ClearPendingAttachment();selected=id;composer.Clear();lastFeed="";Render();dialog.Close();}catch(Exception e){MessageBox.Show(dialog,e.Message,"Could not create group");}};dialog.ShowDialog(this);
    }

    void ShowNotification(string? peer,string title,string text)
    {
        if(IsDisposed||exiting)return;
        notificationPeer=peer;
        tray.BalloonTipTitle=title.Length>60?title[..60]:title;
        tray.BalloonTipText=text;
        tray.BalloonTipIcon=ToolTipIcon.Info;
        tray.ShowBalloonTip(5000);NotificationCount++;
        try{System.Media.SystemSounds.Asterisk.Play();}catch{ /* Sound availability must not interrupt reception. */ }
    }
    void RestoreWindow(string? peer=null)
    {
        Show();WindowState=FormWindowState.Normal;Activate();Render();
        if(peer!=null){for(int i=0;i<contacts.Items.Count;i++)if(((ContactItem)contacts.Items[i]).Id==peer){contacts.SelectedIndex=i;break;}}Render();
    }
    void VerifyDevice()
    {
        if(selected==null)return;var peer=engine.Peers.First(p=>p.Id==selected);
        try{
            string code=engine.PairingCode(peer.Id);string formatted=string.Join(" ",Enumerable.Range(0,8).Select(i=>code.Substring(i*8,8)));
            using var dialog=new Form{Text="Verify device",Size=new Size(660,340),StartPosition=FormStartPosition.CenterParent,Font=Font};
            var layout=new FlowLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(18),FlowDirection=FlowDirection.TopDown,WrapContents=false};
            layout.Controls.Add(new Label{Text=peer.KeyChanged?"KEY CHANGED — do not send until you have checked with this person.":"Compare this entire safety code on BOTH devices in person or through a trusted channel.",AutoSize=true,MaximumSize=new Size(590,0)});
            layout.Controls.Add(new TextBox{Text=formatted,ReadOnly=true,Multiline=true,Width=590,Height=65,Font=new Font("Consolas",13)});
            layout.Controls.Add(new Label{Text="On the other device, select your contact and open Verify device. Confirm on each device only if all groups match.",AutoSize=true,MaximumSize=new Size(590,0)});
            var confirm=new Button{Text="Codes match — verify",AutoSize=true,Enabled=!peer.KeyChanged};var revoke=new Button{Text="Revoke verification",AutoSize=true,Enabled=peer.Verified.Length>0};layout.Controls.Add(confirm);layout.Controls.Add(revoke);dialog.Controls.Add(layout);
            confirm.Click+=(_,_)=>{try{engine.Verify(peer.Id,code);dialog.Close();Render();}catch(Exception error){MessageBox.Show(error.Message,"Verification failed");}};
            revoke.Click+=(_,_)=>{if(MessageBox.Show("Stop trusting this device? Messages will stay queued until you compare and verify its code again.","Revoke verification",MessageBoxButtons.YesNo)==DialogResult.Yes){engine.Revoke(peer.Id);dialog.Close();Render();}};
            dialog.ShowDialog(this);
        }catch(Exception e){MessageBox.Show(e.Message,"Verify device");}
    }
    async Task AddAddress()
    {
        using var dialog=new Form{Text="Add a device",Size=new Size(490,230),StartPosition=FormStartPosition.CenterParent,Font=Font};
        var layout=new FlowLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(15),FlowDirection=FlowDirection.TopDown};
        var ips=NetworkInterface.GetAllNetworkInterfaces().Where(n=>n.OperationalStatus==OperationalStatus.Up).SelectMany(n=>n.GetIPProperties().UnicastAddresses).Where(a=>a.Address.AddressFamily==AddressFamily.InterNetwork&&!IPAddress.IsLoopback(a.Address)).Select(a=>a.Address.ToString());
        layout.Controls.Add(new Label{Text="Your IP: "+string.Join(", ",ips),AutoSize=true,MaximumSize=new Size(440,0)});
        var address=new TextBox{Width=420,PlaceholderText="Other device IP, e.g. 192.168.1.20"};var connect=new Button{Text="Find device",AutoSize=true};layout.Controls.Add(address);layout.Controls.Add(connect);dialog.Controls.Add(layout);
        connect.Click+=async(_,_)=>{connect.Enabled=false;try{await engine.AddAddress(address.Text);dialog.Close();Render();}catch(Exception){MessageBox.Show("Device not reachable. Check its IP, LAN Messenger, Wi-Fi and firewall.","Could not find device");}finally{if(!connect.IsDisposed)connect.Enabled=true;}};
        dialog.ShowDialog(this);await Task.CompletedTask;
    }
}
