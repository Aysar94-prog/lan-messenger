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
    public const string AppVersion="0.7.5";
    static readonly Color Accent=Color.FromArgb(37,211,102),HeaderDark=Color.FromArgb(7,94,84),Ink=Color.FromArgb(17,27,33),BubbleMine=Color.FromArgb(220,248,198),BubbleOther=Color.White,ChatBg=Color.FromArgb(236,229,221),SeenBlue=Color.FromArgb(83,169,239),PanelBg=Color.FromArgb(240,242,245);
    static readonly Color[] NamePalette=[Color.FromArgb(233,30,99),Color.FromArgb(156,39,176),Color.FromArgb(63,81,181),Color.FromArgb(230,126,0),Color.FromArgb(0,137,123),Color.FromArgb(121,85,72),Color.FromArgb(216,67,21)];
    static Color NameColor(string id){int h=0;foreach(var c in id)h=h*31+c;return NamePalette[Math.Abs(h)%NamePalette.Length];}
    static void RoundCorners(Control c,int radius)
    {
        void Apply(object? s,EventArgs e){if(c.Width<=0||c.Height<=0)return;int d=Math.Min(radius*2,Math.Min(c.Width,c.Height));using var path=new System.Drawing.Drawing2D.GraphicsPath();path.AddArc(0,0,d,d,180,90);path.AddArc(c.Width-d,0,d,d,270,90);path.AddArc(c.Width-d,c.Height-d,d,d,0,90);path.AddArc(0,c.Height-d,d,d,90,90);path.CloseFigure();var old=c.Region;c.Region=new Region(path);old?.Dispose();}
        c.SizeChanged+=Apply;Apply(null,EventArgs.Empty);
    }
    record FileItem(PeerEngine.Message Message){public override string ToString()=>Message.FileName;}
    readonly Button verify=new(){Text="Verify device",AutoSize=true};
    readonly Label groupNotice=new(){Text="Group messages and their attachments are automatically deleted after 7 days of being sent.",AutoSize=true,ForeColor=Color.SlateGray,Font=new Font("Segoe UI",8.5f),Margin=new Padding(12,10,0,0),Visible=false};
    readonly PictureBox avatarBox=new(){Width=40,Height=40,SizeMode=PictureBoxSizeMode.Zoom,Cursor=Cursors.Hand,BackColor=Color.FromArgb(216,235,248),Margin=new Padding(3,3,8,3)};
    readonly Button about=new(){Text="About",AutoSize=true};
    Image? avatarImage;
    bool exiting; string? notificationPeer;
    public int NotificationCount {get;private set;}
    readonly System.Windows.Forms.Timer timer = new() { Interval = 1000 };
    readonly ListBox contacts = new() { Dock=DockStyle.Fill, IntegralHeight=false, BorderStyle=BorderStyle.None, HorizontalScrollbar=true };
    readonly TextBox profile = new() { Width=180, MaxLength=30, PlaceholderText="Your display name" };
    readonly Label status = new() { AutoSize=true, Dock=DockStyle.Fill, ForeColor=Color.DimGray };
    readonly Label heading = new() { Text="Choose a contact", Dock=DockStyle.Fill, Font=new Font("Segoe UI",17,FontStyle.Bold), AutoSize=false, AutoEllipsis=true };
    readonly FlowLayoutPanel feed = new() { Dock=DockStyle.Fill, AutoScroll=true, FlowDirection=FlowDirection.TopDown, WrapContents=false, BackColor=ChatBg, Padding=new Padding(8) };
    readonly FlowLayoutPanel attachmentDraft = new() { Dock=DockStyle.Fill, FlowDirection=FlowDirection.LeftToRight, WrapContents=false, AutoScroll=true, Visible=false, BackColor=Color.FromArgb(235,240,250), Padding=new Padding(8) };
    readonly TextBox composer = new() { Dock=DockStyle.Fill, Multiline=true, MaxLength=2000, PlaceholderText="Write a message…", Enabled=false };
    readonly Button send = new() { Text="Send", Dock=DockStyle.Fill, Enabled=false };
    readonly Dictionary<string,string> drafts=[];
    string? selected;
    // The picked file's path, not its bytes — a 1 GB attachment is never fully read into memory
    // just to sit in the compose draft; it's streamed from disk only once actually queued to send.
    string? pendingAttachmentPath; string pendingAttachmentName=""; string? pendingAttachmentTarget; RowStyle pendingAttachmentRow=null!;
    const long ThumbnailPreviewCap=20*1024*1024;
    readonly Dictionary<string,(long done,long total)> transferProgress=[];
    // Decoded once per contact-list rebuild (not per paint, which owner-draw would otherwise do on
    // every scroll/focus repaint) — a contact's real photo shown in the conversation list, once received.
    readonly Dictionary<string,Image> avatarCache=[];
    string lastFeed="", lastContacts="";
    bool rendering;
    record ContactItem(string Id,string Name,string Detail,bool Group=false,int Unread=0,long LastActivity=0) { public override string ToString()=>Name; }

    public ChatWindow() : this(null,null) {}
    public ChatWindow(string? dataDirectory,IStorageProtector? protector)
    {
        Text="LAN Messenger"; Size=new Size(1140,810); MinimumSize=new Size(940,650); StartPosition=FormStartPosition.CenterScreen;
        Font=new Font("Segoe UI",11); BackColor=PanelBg; RightToLeft=RightToLeft.No;
        var data=dataDirectory??Environment.GetEnvironmentVariable("LAN_MESSENGER_DATA") ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"LanMessenger");
        engine=new PeerEngine(data,Environment.UserName,protector); profile.Text=engine.Name;
        var root=new TableLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(0),ColumnCount=1,RowCount=5};
        root.ColumnStyles.Add(new(SizeType.Percent,100));
        root.RowStyles.Add(new(SizeType.Absolute,64));root.RowStyles.Add(new(SizeType.Absolute,46));root.RowStyles.Add(new(SizeType.Absolute,42));root.RowStyles.Add(new(SizeType.Percent,100));root.RowStyles.Add(new(SizeType.Absolute,32));
        var headerBar=new Panel{Dock=DockStyle.Fill,BackColor=HeaderDark,Padding=new Padding(20,0,20,0)};
        headerBar.Controls.Add(new Label{Text="LAN Messenger",Font=new Font("Segoe UI",18,FontStyle.Bold),AutoSize=true,ForeColor=Color.White,Location=new Point(20,14)});
        root.Controls.Add(headerBar,0,0);
        var toolbar=new FlowLayoutPanel{Dock=DockStyle.Fill,WrapContents=false,Padding=new Padding(20,4,0,0),BackColor=PanelBg};var save=new Button{Text="Save name",AutoSize=true};var scan=new Button{Text="Refresh",AutoSize=true};var add=new Button{Text="Add by IP",AutoSize=true};
        var createGroup=new Button{Text="New group",AutoSize=true};toolbar.Controls.AddRange([avatarBox,profile,save,scan,add,createGroup,about]);createGroup.Click+=(_,_)=>CreateGroup();about.Click+=(_,_)=>ShowAbout();avatarBox.Click+=async(_,_)=>await ChangeAvatar();RoundCorners(avatarBox,20);root.Controls.Add(toolbar,0,1);var statusPanel=new Panel{Dock=DockStyle.Fill,Padding=new Padding(20,0,20,0),BackColor=PanelBg};statusPanel.Controls.Add(status);root.Controls.Add(statusPanel,0,2);
        var split=new SplitContainer{Size=new Size(950,460),Dock=DockStyle.Fill,SplitterDistance=300,FixedPanel=FixedPanel.Panel1,Panel1MinSize=220,Panel2MinSize=280};
        var people=new TableLayoutPanel{Dock=DockStyle.Fill,RowCount=2,ColumnCount=1};people.ColumnStyles.Add(new(SizeType.Percent,100));people.RowStyles.Add(new(SizeType.Absolute,35));people.RowStyles.Add(new(SizeType.Percent,100));people.Controls.Add(new Label{Text="CONVERSATIONS",AutoSize=true},0,0);people.Controls.Add(contacts,0,1);split.Panel1.Controls.Add(people);
        var chat=new TableLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(15,0,0,0),ColumnCount=1,RowCount=5};chat.ColumnStyles.Add(new(SizeType.Percent,100));chat.RowStyles.Add(new(SizeType.Absolute,48));chat.RowStyles.Add(new(SizeType.Absolute,44));chat.RowStyles.Add(new(SizeType.Percent,100));pendingAttachmentRow=new RowStyle(SizeType.Absolute,0);chat.RowStyles.Add(pendingAttachmentRow);chat.RowStyles.Add(new(SizeType.Absolute,88));chat.Controls.Add(heading,0,0);var actions=new FlowLayoutPanel{Dock=DockStyle.Fill,WrapContents=false};actions.Controls.AddRange([verify,members,clear,groupNotice]);chat.Controls.Add(actions,0,1);chat.Controls.Add(feed,0,2);chat.Controls.Add(attachmentDraft,0,3);
        var input=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=3,Padding=new Padding(0,10,0,0)};input.ColumnStyles.Add(new(SizeType.Percent,100));input.ColumnStyles.Add(new(SizeType.Absolute,95));input.Controls.Add(composer,0,0);input.Controls.Add(send,1,0);input.ColumnStyles.Add(new(SizeType.Absolute,85));input.Controls.Add(attach,2,0);chat.Controls.Add(input,0,4);split.Panel2.Controls.Add(chat);
        var splitPanel=new Panel{Dock=DockStyle.Fill,Padding=new Padding(20,4,20,4),BackColor=PanelBg};splitPanel.Controls.Add(split);root.Controls.Add(splitPanel,0,3);
        var footerPanel=new Panel{Dock=DockStyle.Fill,Padding=new Padding(20,4,20,4),BackColor=PanelBg};footerPanel.Controls.Add(new Label{Text="Encrypted connection and local history • Verify the safety code on both devices before chatting",AutoSize=true,ForeColor=Color.DimGray,Dock=DockStyle.Left});root.Controls.Add(footerPanel,0,4);Controls.Add(root);
        contacts.DrawMode=DrawMode.OwnerDrawFixed;contacts.ItemHeight=64;contacts.BackColor=Color.White;
        contacts.DrawItem+=(_,e)=>{if(e.Index<0)return;var item=(ContactItem)contacts.Items[e.Index];bool active=(e.State&DrawItemState.Selected)!=0;using var bg=new SolidBrush(active?Color.FromArgb(230,247,234):Color.White);e.Graphics.FillRectangle(bg,e.Bounds);
            var avatarRect=new Rectangle(e.Bounds.X+10,e.Bounds.Y+13,36,36);
            if(!item.Group&&avatarCache.TryGetValue(item.Id,out var photo)){
                var oldClip=e.Graphics.Clip;using var clipPath=new System.Drawing.Drawing2D.GraphicsPath();clipPath.AddEllipse(avatarRect);e.Graphics.SetClip(clipPath,System.Drawing.Drawing2D.CombineMode.Intersect);
                e.Graphics.DrawImage(photo,avatarRect);e.Graphics.Clip=oldClip;
            }else{using var badge=new SolidBrush(item.Group?Color.FromArgb(227,220,251):NameColor(item.Id));e.Graphics.FillEllipse(badge,avatarRect);TextRenderer.DrawText(e.Graphics,item.Group?"G":item.Name[..Math.Min(1,item.Name.Length)].ToUpperInvariant(),Font,new Rectangle(e.Bounds.X+10,e.Bounds.Y+18,36,28),Color.White,TextFormatFlags.HorizontalCenter);}
            using var bold=new Font(Font,FontStyle.Bold);TextRenderer.DrawText(e.Graphics,item.Name,bold,new Rectangle(e.Bounds.X+55,e.Bounds.Y+10,e.Bounds.Width-60,24),Ink,TextFormatFlags.EndEllipsis);using var small=new Font("Segoe UI",9);TextRenderer.DrawText(e.Graphics,item.Detail,small,new Rectangle(e.Bounds.X+55,e.Bounds.Y+35,e.Bounds.Width-60,22),Color.SlateGray,TextFormatFlags.EndEllipsis);
            if(item.Unread>0){var count=item.Unread>99?"99+":item.Unread.ToString();int d=22;var badgeRect=new Rectangle(e.Bounds.Right-d-12,e.Bounds.Y+(e.Bounds.Height-d)/2,d,d);using var unread=new SolidBrush(Accent);e.Graphics.FillEllipse(unread,badgeRect);using var tiny=new Font("Segoe UI",8,FontStyle.Bold);TextRenderer.DrawText(e.Graphics,count,tiny,badgeRect,Color.White,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter);}};
        StyleButtons(root);send.BackColor=Accent;send.ForeColor=Color.White;RoundCorners(send,8);feed.Resize+=(_,_)=>{if(selected!=null){lastFeed="";Render();}};
        avatarBox.Paint+=(_,e)=>{if(avatarImage==null){using var b=new SolidBrush(Accent);e.Graphics.FillEllipse(b,0,0,avatarBox.Width,avatarBox.Height);TextRenderer.DrawText(e.Graphics,engine.Name.Length>0?engine.Name[..1].ToUpperInvariant():"?",new Font("Segoe UI",14,FontStyle.Bold),avatarBox.ClientRectangle,Color.White,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter);}};
        try{var raw=engine.Avatar;if(raw!=null){avatarImage=TryImageThumbnail(raw,80,80);avatarBox.Image=avatarImage;}}catch{}
        attach.Click+=async(_,_)=>await AttachFile();clear.Click+=(_,_)=>ClearChat();members.Click+=(_,_)=>ShowMembers();saveFile.Click+=(_,_)=>SaveAttachment();preview.Click+=(_,_)=>PreviewImage();
        verify.Click+=(_,_)=>VerifyDevice();
        var trayMenu=new ContextMenuStrip();trayMenu.Items.Add("Open LAN Messenger",null,(_,_)=>RestoreWindow());trayMenu.Items.Add("Test notification",null,(_,_)=>ShowNotification(null,"LAN Messenger","This is a test notification from LAN Messenger."));trayMenu.Items.Add("Exit",null,(_,_)=>{exiting=true;Close();});tray.ContextMenuStrip=trayMenu;tray.DoubleClick+=(_,_)=>RestoreWindow();tray.BalloonTipClicked+=(_,_)=>RestoreWindow(notificationPeer);
        engine.Received+=m=>{if(!IsDisposed&&IsHandleCreated)try{BeginInvoke(new Action(()=>{Render();var peer=engine.Peers.FirstOrDefault(p=>p.Id==m.From);ShowNotification(m.GroupId.Length>0?m.GroupId:m.From,m.GroupId.Length>0?engine.DisplayName(m.GroupId):peer?.Name??"LAN Messenger","New encrypted message");}));}catch{}};
        // Attachment transfers run on background connection threads, not the UI thread — marshal
        // back to update the in-flight "Sending NN%" status shown on the message's own bubble.
        engine.TransferProgress+=(id,done,total)=>{if(IsDisposed||!IsHandleCreated)return;try{BeginInvoke(new Action(()=>{if(IsDisposed)return;if(done>=total)transferProgress.Remove(id);else transferProgress[id]=(done,total);lastFeed="";Render();}));}catch{}};
        FormClosing+=(_,e)=>{if(!exiting&&e.CloseReason==CloseReason.UserClosing){e.Cancel=true;Hide();ShowNotification(null,"LAN Messenger","Still running. Right-click the tray icon and choose Exit to stop.");}};
        save.Click+=(_,_)=>{try{engine.Rename(profile.Text);profile.Text=engine.Name;}catch(Exception e){MessageBox.Show(e.Message,"Could not save name");}};
        scan.Click+=async(_,_)=>{await engine.Announce();Render();};add.Click+=async(_,_)=>await AddAddress();
        contacts.SelectedIndexChanged+=(_,_)=>{if(rendering)return;if(selected!=null)drafts[selected]=composer.Text;var next=(contacts.SelectedItem as ContactItem)?.Id;if(pendingAttachmentTarget!=null&&pendingAttachmentTarget!=next)ClearPendingAttachment();selected=next;composer.Text=selected!=null&&drafts.TryGetValue(selected,out var draft)?draft:"";lastFeed="";Render();};
        send.Click+=async(_,_)=>await Send();composer.KeyDown+=async(_,e)=>{if(e.KeyCode==Keys.Enter&&!e.Shift){e.SuppressKeyPress=true;await Send();}};
        timer.Tick+=(_,_)=>Render();Shown+=(_,_)=>{try{engine.Start();timer.Start();Render();}catch(Exception e){status.Text="Could not start: "+e.Message;}};
        FormClosed+=(_,_)=>{timer.Stop();tray.Visible=false;tray.Dispose();engine.Dispose();};
        feed.Controls.Add(MessageLabel("People running LAN Messenger on your network appear automatically.\n\nContacts and messages stay saved after you close the app.\n\nOffline? Write a message now. It stays Queued until both devices are connected.\n\nNo contacts yet? Open the new app on another device on the same Wi-Fi. You can use Add by IP if discovery is blocked.",11,Ink,Math.Max(300,feed.ClientSize.Width-30)));
    }
    async Task Send()
    {
        if(selected==null||!send.Enabled||(pendingAttachmentPath==null&&string.IsNullOrWhiteSpace(composer.Text)))return;
        var target=selected;var path=pendingAttachmentPath;var name=pendingAttachmentName;var caption=composer.Text;var originalStatus=status.Text;
        send.Enabled=false;attach.Enabled=false;
        try{
            if(path!=null){
                var total=new FileInfo(path).Length;
                // Runs on the UI thread already (no cross-thread marshal needed): every await in
                // this call chain started from this UI-thread click and never leaves that context.
                await engine.QueueFileFromPathAsync(target,caption,path,done=>{if(!IsDisposed)status.Text=$"Preparing to send… {(total>0?done*100/total:100)}%";},name);
            }else engine.Queue(target,caption);
            if(selected==target){composer.Clear();drafts.Remove(target);ClearPendingAttachment();}
        }
        catch(Exception e){MessageBox.Show(this,e.Message,"Message not saved");}
        finally{if(!IsDisposed){status.Text=originalStatus;send.Enabled=true;attach.Enabled=true;Render();}}
    }
    void Render()
    {
        if(selected!=null&&Visible&&WindowState!=FormWindowState.Minimized)try{engine.MarkRead(selected);}catch{}
        var peers=engine.Peers;
        status.Text=$"{(engine.Running?"Available on your network":"Offline")}  ·  {peers.Count(p=>p.Online)} online  ·  {engine.Pending} queued  ·  Your ID: {engine.Id[..8]}";
        var groups=engine.Groups;
        // Most recently active conversation first, like a typical chat app — not name/online order.
        long LastActivity(string conversation){var items=engine.Messages(conversation);return items.Length>0?items.Max(m=>m.Time):0;}
        var entries=groups.Select(g=>new ContactItem(g.Id,g.Name,$"Group · {g.Members.Length} members",true,engine.Unread(g.Id),LastActivity(g.Id))).Concat(peers.Select(p=>new ContactItem(p.Id,p.Name,$"{(p.Online?"Online":"Offline")} · {p.Security}",false,engine.Unread(p.Id),LastActivity(p.Id)))).OrderByDescending(e=>e.LastActivity).ThenBy(e=>e.Name).ToArray();
        string signature=string.Join("|",entries.Select(p=>$"{p.Id}:{p.Name}:{p.Detail}:{p.Unread}:{p.LastActivity}"))+"|"+string.Join(",",peers.Select(p=>p.Id+":"+p.ReceivedAvatarHash));
        if(signature!=lastContacts){
            rendering=true;contacts.BeginUpdate();contacts.Items.Clear();contacts.Items.AddRange(entries);for(int i=0;i<contacts.Items.Count;i++)if(((ContactItem)contacts.Items[i]).Id==selected)contacts.SelectedIndex=i;contacts.EndUpdate();rendering=false;lastContacts=signature;
            // Decoded once here, not per paint — DrawItem below just reads this cache.
            foreach(var img in avatarCache.Values)img.Dispose();avatarCache.Clear();
            foreach(var p in peers)try{var raw=engine.PeerAvatar(p.Id);if(raw!=null){var img=TryImageThumbnail(raw,72,72);if(img!=null)avatarCache[p.Id]=img;}}catch{}
        }
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
    static Bitmap DrawInitialCircle(string initial,Color color,int size)
    {
        var bmp=new Bitmap(size,size);using var g=Graphics.FromImage(bmp);g.SmoothingMode=System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
        using var b=new SolidBrush(color);g.FillEllipse(b,0,0,size,size);
        TextRenderer.DrawText(g,initial,new Font("Segoe UI",size*0.42f,FontStyle.Bold),new Rectangle(0,0,size,size),Color.White,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter);
        return bmp;
    }
    // A small avatar next to the sender's name in every bubble: your own real picture for your own
    // messages when set, the sender's real picture once they've sent it over a verified connection,
    // a colored initial otherwise.
    Control SenderRow(bool mine,string senderId,string name,Color color,int width)
    {
        var row=new FlowLayoutPanel{FlowDirection=FlowDirection.LeftToRight,WrapContents=false,AutoSize=true,Margin=new Padding(0)};
        const int avatarSize=18;
        var avatar=new PictureBox{Width=avatarSize,Height=avatarSize,SizeMode=PictureBoxSizeMode.Zoom,Margin=new Padding(0,0,5,0)};
        Image? peerPhoto=null;if(!mine)try{var raw=engine.PeerAvatar(senderId);if(raw!=null)peerPhoto=TryImageThumbnail(raw,64,64);}catch{}
        if(mine&&avatarImage!=null){var clone=new Bitmap(avatarImage);avatar.Image=clone;avatar.Disposed+=(_,_)=>clone.Dispose();}
        else if(peerPhoto!=null){avatar.Image=peerPhoto;avatar.Disposed+=(_,_)=>peerPhoto.Dispose();}
        else{var drawn=DrawInitialCircle(name.Length>0?name[..1].ToUpperInvariant():"?",color,64);avatar.Image=drawn;avatar.Disposed+=(_,_)=>drawn.Dispose();}
        row.Controls.Add(avatar);
        row.Controls.Add(MessageLabel(name,10,color,width-avatarSize-8,true));
        return row;
    }
    Control MessageCard(PeerEngine.Message message)
    {
        bool mine=message.From==engine.Id;int width=Math.Max(260,Math.Min(460,feed.ClientSize.Width-45));
        var card=new FlowLayoutPanel{FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoSize=true,AutoSizeMode=AutoSizeMode.GrowAndShrink,MinimumSize=new Size(width,0),MaximumSize=new Size(width,10000),Padding=new Padding(12,8,12,8),Margin=new Padding(mine?Math.Max(8,feed.ClientSize.Width-width-25):4,6,4,6),BackColor=mine?BubbleMine:BubbleOther};
        RoundCorners(card,10);
        card.Controls.Add(SenderRow(mine,message.From,mine?"You":engine.DisplayName(message.From),mine?Accent:NameColor(message.From),width-24));
        if(message.FileName.Length==0)card.Controls.Add(MessageLabel(message.Text,12,Ink,width-24));
        else{
            var thumbnail=TryImageThumbnail(message,Math.Min(420,width-24),320);
            if(thumbnail!=null){var picture=new PictureBox{Image=thumbnail,SizeMode=PictureBoxSizeMode.Zoom,Width=width-24,Height=Math.Max(150,Math.Min(320,(int)Math.Round((double)(width-24)*thumbnail.Height/thumbnail.Width))),Cursor=Cursors.Hand,BackColor=Color.FromArgb(232,236,243),Margin=new Padding(0,4,0,4)};picture.Click+=(_,_)=>PreviewImage(message);picture.Disposed+=(_,_)=>thumbnail.Dispose();card.Controls.Add(picture);}
            card.Controls.Add(MessageLabel($"{message.FileName}  ·  {FormatSize(message.FileSize)}",10,Ink,width-24));
            var actions=new FlowLayoutPanel{AutoSize=true,WrapContents=false,Margin=new Padding(0)};var save=new Button{Text="Save",AutoSize=true};save.Click+=(_,_)=>SaveAttachment(message);actions.Controls.Add(save);if(thumbnail!=null){var open=new Button{Text="Open",AutoSize=true};open.Click+=(_,_)=>PreviewImage(message);actions.Controls.Add(open);}StyleButtons(actions);card.Controls.Add(actions);
            if(message.Text.Length>0)card.Controls.Add(MessageLabel(message.Text,12,Ink,width-24));
        }
        var when=DateTimeOffset.FromUnixTimeMilliseconds(message.Time).LocalDateTime.ToString("MMM d, HH:mm");
        if(mine){bool seen=message.Status.StartsWith("Seen");var ticks=seen||message.Status.StartsWith("Delivered")?"✓✓":"✓";
            var statusText=message.Status;
            if(message.Status=="Queued"&&message.FileName.Length>0&&transferProgress.TryGetValue(message.Id,out var progress)&&progress.total>0)statusText=$"Sending {progress.done*100/progress.total}%";
            card.Controls.Add(MessageLabel($"{when}  ·  {ticks} {statusText}",9,seen?SeenBlue:Color.SlateGray,width-24));}
        else card.Controls.Add(MessageLabel(when,9,Color.SlateGray,width-24));
        return card;
    }
    // Guarded by size: decoding an inline thumbnail means fully decrypting the attachment into
    // memory (ReadAttachment), which must stay off the table for anything near the 1 GB cap —
    // rendering a whole conversation's history would otherwise decrypt every large file in it.
    Image? TryImageThumbnail(PeerEngine.Message message,int maxWidth,int maxHeight){if(message.FileSize>ThumbnailPreviewCap)return null;try{return TryImageThumbnail(engine.ReadAttachment(message),maxWidth,maxHeight);}catch{return null;}}
    static Image? TryImageThumbnail(byte[] data,int maxWidth,int maxHeight){try{using var stream=new MemoryStream(data);using var source=Image.FromStream(stream);if((long)source.Width*source.Height>32000000)throw new IOException();double scale=Math.Min(1,Math.Min((double)maxWidth/source.Width,(double)maxHeight/source.Height));var result=new Bitmap(Math.Max(1,(int)(source.Width*scale)),Math.Max(1,(int)(source.Height*scale)));using var graphics=Graphics.FromImage(result);graphics.InterpolationMode=System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic;graphics.DrawImage(source,new Rectangle(0,0,result.Width,result.Height));return result;}catch{return null;}}
    static string FormatSize(long bytes)=>bytes>=1024*1024?$"{bytes/1024.0/1024.0:0.#} MB":$"{bytes/1024.0:0.#} KB";
    static void StyleButtons(Control root){foreach(Control c in root.Controls){if(c is Button b){b.FlatStyle=FlatStyle.Flat;b.FlatAppearance.BorderColor=Color.FromArgb(217,226,239);b.BackColor=Color.White;b.ForeColor=Ink;b.Padding=new Padding(2);b.Cursor=Cursors.Hand;}StyleButtons(c);}}
    async Task AttachFile()
    {
        if(selected==null)return;var target=selected;
        using var dialog=new OpenFileDialog{Title=$"Share a photo or file · up to {PeerEngine.MaxFileSize/1024/1024} MB",Filter="All files (*.*)|*.*|Images (*.png;*.jpg;*.jpeg;*.gif)|*.png;*.jpg;*.jpeg;*.gif"};
        if(dialog.ShowDialog(this)!=DialogResult.OK)return;
        try{
            var size=await Task.Run(()=>new FileInfo(dialog.FileName).Length);
            if(size>PeerEngine.MaxFileSize)throw new IOException($"Files must be {PeerEngine.MaxFileSize/1024/1024} MB or smaller.");
            if(selected!=target)return;
            pendingAttachmentPath=dialog.FileName;pendingAttachmentName=Path.GetFileName(dialog.FileName);pendingAttachmentTarget=target;RenderPendingAttachment();composer.Focus();
        }catch(Exception e){MessageBox.Show(this,e.Message,"Could not prepare file");}
    }
    void RenderPendingAttachment()
    {
        foreach(Control control in attachmentDraft.Controls.Cast<Control>().ToArray())control.Dispose();attachmentDraft.Controls.Clear();
        if(pendingAttachmentPath==null){attachmentDraft.Visible=false;pendingAttachmentRow.Height=0;return;}
        attachmentDraft.Visible=true;pendingAttachmentRow.Height=150;
        var path=pendingAttachmentPath;long size;try{size=new FileInfo(path).Length;}catch{size=0;}
        // Only decode a thumbnail for attachments small enough that reading them fully is cheap;
        // a large file just shows its name/size, matching how most desktop apps handle big attachments.
        if(size>0&&size<=ThumbnailPreviewCap){Image? thumb=null;try{thumb=TryImageThumbnail(File.ReadAllBytes(path),150,125);}catch{}
            if(thumb!=null){var image=new PictureBox{Image=thumb,Width=150,Height=125,SizeMode=PictureBoxSizeMode.Zoom,Cursor=Cursors.Hand,BackColor=Color.FromArgb(220,226,237)};image.Click+=(_,_)=>PreviewImagePath(path,pendingAttachmentName);image.Disposed+=(_,_)=>thumb.Dispose();attachmentDraft.Controls.Add(image);}}
        var details=new FlowLayoutPanel{FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoSize=true};
        details.Controls.Add(MessageLabel(pendingAttachmentName+"  ·  "+FormatSize(size)+"\nReady to send",10,Ink,330,true));
        var remove=new Button{Text="Remove",AutoSize=true};remove.Click+=(_,_)=>ClearPendingAttachment();details.Controls.Add(remove);StyleButtons(details);attachmentDraft.Controls.Add(details);
    }
    void ClearPendingAttachment(){pendingAttachmentPath=null;pendingAttachmentName="";pendingAttachmentTarget=null;RenderPendingAttachment();}
    void SaveAttachment(){if(files.SelectedItem is FileItem item)SaveAttachment(item.Message);}
    void SaveAttachment(PeerEngine.Message message){using var dialog=new SaveFileDialog{FileName=message.FileName,Title="Save attachment",Filter="All files|*.*"};if(dialog.ShowDialog(this)!=DialogResult.OK)return;_=ExportAttachment(message,dialog.FileName);}
    // Streams straight to the destination file, decrypting on the fly — an export never needs
    // the whole attachment in memory either, same reasoning as the send/receive path.
    async Task ExportAttachment(PeerEngine.Message message,string destination){try{await engine.ExportAttachmentAsync(message,destination);}catch(Exception e){if(!IsDisposed)MessageBox.Show(this,e.Message,"Could not save attachment");}}
    void PreviewImage(){if(files.SelectedItem is FileItem item)PreviewImage(item.Message);}
    void PreviewImage(PeerEngine.Message message){try{PreviewImageCore(engine.ReadAttachment(message),message.FileName);}catch{MessageBox.Show(this,"This attachment cannot be opened as an image. Use Save to export it.","Image preview");}}
    void PreviewImagePath(string path,string name){try{PreviewImageCore(File.ReadAllBytes(path),name);}catch{MessageBox.Show(this,"This file is not a supported image.","Image preview");}}
    void PreviewImageCore(byte[] data,string name){using var bytes=new MemoryStream(data);using var decoded=Image.FromStream(bytes);if((long)decoded.Width*decoded.Height>32000000)throw new IOException();using var picture=new Bitmap(decoded);using var window=new Form{Text=name,Size=new Size(900,700),StartPosition=FormStartPosition.CenterParent,BackColor=Color.FromArgb(20,24,31),KeyPreview=true};window.Controls.Add(new PictureBox{Dock=DockStyle.Fill,SizeMode=PictureBoxSizeMode.Zoom,Image=picture,BackColor=window.BackColor});window.KeyDown+=(_,e)=>{if(e.KeyCode==Keys.Escape)window.Close();};window.ShowDialog(this);}
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
    async Task ChangeAvatar()
    {
        using var dialog=new OpenFileDialog{Title="Choose a profile picture",Filter="Images (*.png;*.jpg;*.jpeg;*.bmp)|*.png;*.jpg;*.jpeg;*.bmp"};
        if(dialog.ShowDialog(this)!=DialogResult.OK)return;
        try{
            var data=await Task.Run(()=>File.ReadAllBytes(dialog.FileName));
            var thumb=TryImageThumbnail(data,256,256)??throw new IOException("This file is not a supported image.");
            using var bytes=new MemoryStream();thumb.Save(bytes,System.Drawing.Imaging.ImageFormat.Png);
            engine.SetAvatar(bytes.ToArray());
            avatarImage?.Dispose();avatarImage=thumb;avatarBox.Image=avatarImage;avatarBox.Invalidate();
        }catch(Exception e){MessageBox.Show(this,e.Message,"Could not set profile picture");}
    }
    void ShowAbout()
    {
        using var dialog=new Form{Text="About LAN Messenger",Size=new Size(420,260),StartPosition=FormStartPosition.CenterParent,Font=Font,FormBorderStyle=FormBorderStyle.FixedDialog,MaximizeBox=false,MinimizeBox=false};
        var layout=new FlowLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(20),FlowDirection=FlowDirection.TopDown,WrapContents=false};
        layout.Controls.Add(new Label{Text="LAN Messenger",Font=new Font("Segoe UI",16,FontStyle.Bold),ForeColor=HeaderDark,AutoSize=true});
        layout.Controls.Add(MessageLabel($"Version {AppVersion}",11,Color.SlateGray,360));
        layout.Controls.Add(MessageLabel("Private Windows and Android messaging on a local network. No central server, host laptop, account or Internet relay.",10,Ink,360));
        layout.Controls.Add(MessageLabel($"Your device ID: {engine.Id}",9,Color.SlateGray,360));
        var close=new Button{Text="Close",AutoSize=true};close.Click+=(_,_)=>dialog.Close();StyleButtons(layout);layout.Controls.Add(close);
        dialog.Controls.Add(layout);dialog.ShowDialog(this);
    }
}
