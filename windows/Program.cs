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
sealed partial class ChatWindow : Form
{
    readonly PeerEngine engine;
    readonly NotifyIcon tray=new(){Icon=SystemIcons.Information,Text="LAN Messenger",Visible=true};
    readonly Button attach=new(){Text="Attach",AutoSize=true};
    readonly Button fastTransfer=new(){Text="Fast file",AutoSize=true};
    bool pendingFast,sendBusy;
    readonly Dictionary<string,(Control card,string content,PeerEngine.Message message)> cards=[];
    string feedConversation=""; int feedWidth;
    readonly Dictionary<string,Label> statusLabels=[];
    // Per-conversation pagination/view state and bounded caches (windows/PLAN-CHAT-PERFORMANCE.md).
    const int InitialVisibleMessages=10, OlderPageSize=20, MaxCachedChats=3, MaxCachedCards=600;
    readonly Dictionary<string,ChatViewState> chatViews=[];
    readonly ThumbnailCache thumbnails=new();
    bool loadingOlder;
    Control? olderHint;
    Control? emptyHint;
    readonly Button clear=new(){Text="Clear chat",AutoSize=true};
    readonly Button members=new(){Text="Members",AutoSize=true};
    readonly Button leaveGroup=new(){Text="Leave group",AutoSize=true};
    readonly Button recordVoice=new(){Text="Record voice",AutoSize=true,AccessibleName="Record a voice message"};
    readonly Button callButton=new(){Text="Call",AutoSize=true};
    readonly Button stopRecording=new(){Text="Stop recording",AutoSize=true,Visible=false,AccessibleName="Stop recording and save"};
    readonly ComboBox files=new(){Width=230,DropDownStyle=ComboBoxStyle.DropDownList};
    readonly Button saveFile=new(){Text="Open / Download",AutoSize=true};
    readonly Button preview=new(){Text="Preview image",AutoSize=true};
    public const string AppVersion="2.2.42";
    static readonly Color Accent=Color.FromArgb(47,111,237),HeaderDark=Color.FromArgb(26,43,74),Ink=Color.FromArgb(26,27,31),BubbleMine=Color.FromArgb(214,231,255),BubbleOther=Color.White,ChatBg=Color.FromArgb(238,241,246),SeenBlue=Color.FromArgb(94,150,245),PanelBg=Color.FromArgb(240,242,246);
    static readonly Color[] NamePalette=[Color.FromArgb(233,30,99),Color.FromArgb(156,39,176),Color.FromArgb(63,81,181),Color.FromArgb(230,126,0),Color.FromArgb(0,137,123),Color.FromArgb(121,85,72),Color.FromArgb(216,67,21)];
    static Color NameColor(string id){int h=0;foreach(var c in id)h=h*31+c;return NamePalette[Math.Abs(h)%NamePalette.Length];}
    // Flat, pill-shaped chrome instead of the stock Windows button — a light accent-tinted fill
    // for ordinary actions, a solid accent fill for the one primary action per row (Send, Call).
    // The flat/rounded shape alone, with whatever BackColor/ForeColor the caller already set --
    // for the few buttons (call accept/decline/hang-up) whose color is semantic (green/red) and
    // must survive the restyle untouched; only the stock 3D border and system colors are removed.
    internal static void MakePill(Button b)
    {
        b.FlatStyle=FlatStyle.Flat;b.FlatAppearance.BorderSize=0;
        b.Font=new Font("Segoe UI",9.5f,FontStyle.Bold);
        b.Padding=new Padding(12,4,12,4);
        RoundCorners(b,14);
    }
    internal static void StyleButton(Button b,bool primary=false)
    {
        MakePill(b);
        b.BackColor=primary?Accent:Color.FromArgb(224,233,250);
        b.ForeColor=primary?Color.White:HeaderDark;
    }
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
    readonly Button permissions=new(){Text="Permissions…",AutoSize=true};
    readonly Button deleteData=new(){Text="Delete app data",AutoSize=true};
    readonly ContextMenuStrip contactMenu=new();
    Image? avatarImage;
    bool exiting; string? notificationPeer;
    public int NotificationCount {get;private set;}
    readonly System.Windows.Forms.Timer timer = new() { Interval = 1000 };
    readonly ListBox contacts = new() { Dock=DockStyle.Fill, IntegralHeight=false, BorderStyle=BorderStyle.None, HorizontalScrollbar=true };
    readonly TextBox profile = new() { Width=180, MaxLength=30, PlaceholderText="Your display name" };
    readonly Label status = new() { AutoSize=true, Dock=DockStyle.Fill, ForeColor=Color.DimGray };
    readonly Button connection = new() { Text="Go offline", AutoSize=true };
    readonly ToolStripMenuItem trayConnection = new("Go offline");
    readonly string preferencePath;
    bool requestedOnline;
    readonly Label heading = new() { Text="Choose a contact", Dock=DockStyle.Fill, Font=new Font("Segoe UI",17,FontStyle.Bold), AutoSize=false, AutoEllipsis=true };
    readonly BufferedFeed feed = new() { Dock=DockStyle.Fill, AutoScroll=true, FlowDirection=FlowDirection.TopDown, WrapContents=false, BackColor=ChatBg, Padding=new Padding(8) };
    readonly FlowLayoutPanel attachmentDraft = new() { Dock=DockStyle.Fill, FlowDirection=FlowDirection.LeftToRight, WrapContents=false, AutoScroll=true, Visible=false, BackColor=Color.FromArgb(235,240,250), Padding=new Padding(8) };
    readonly TextBox composer = new() { Dock=DockStyle.Fill, Multiline=true, MaxLength=2000, PlaceholderText="Write a message…", Enabled=false };
    readonly Button send = new() { Text="Send", Dock=DockStyle.Fill, Enabled=false };
    Button scan=null!, add=null!;
    readonly Dictionary<string,string> drafts=[];
    string? selected;
    // The picked file's path, not its bytes — a 1 GB attachment is never fully read into memory
    // just to sit in the compose draft; it's streamed from disk only once actually queued to send.
    string? pendingAttachmentPath; string pendingAttachmentName=""; string? pendingAttachmentTarget; RowStyle pendingAttachmentRow=null!; RowStyle groupNoticeRow=null!;
    const long ThumbnailPreviewCap=20*1024*1024;
    readonly Dictionary<string,(long done,long total)> transferProgress=[];
    // Decoded once per contact-list rebuild (not per paint, which owner-draw would otherwise do on
    // every scroll/focus repaint) — a contact's real photo shown in the conversation list, once received.
    readonly Dictionary<string,Image> avatarCache=[];
    string lastFeed="", lastContacts="";
    bool rendering,updatingView,renderPending;

    public ChatWindow() : this(null,null) {}
    public ChatWindow(string? dataDirectory,IStorageProtector? protector)
    {
        Text="LAN Messenger"; Size=new Size(1140,810); MinimumSize=new Size(940,650); StartPosition=FormStartPosition.CenterScreen;
        try{var appIcon=Icon.ExtractAssociatedIcon(Application.ExecutablePath);if(appIcon!=null){Icon=appIcon;tray.Icon=appIcon;}}catch{}
        Font=new Font("Segoe UI",11); BackColor=PanelBg; RightToLeft=RightToLeft.No;
        var data=dataDirectory??Environment.GetEnvironmentVariable("LAN_MESSENGER_DATA") ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"LanMessenger");
        preferencePath=Path.Combine(data,"network-preference.txt");
        requestedOnline=!File.Exists(preferencePath)||File.ReadAllText(preferencePath).Trim()!="Offline";
        engine=new PeerEngine(data,Environment.UserName,protector); profile.Text=engine.Name;
        var root=new TableLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(0),ColumnCount=1,RowCount=5};
        root.ColumnStyles.Add(new(SizeType.Percent,100));
        root.RowStyles.Add(new(SizeType.Absolute,64));root.RowStyles.Add(new(SizeType.Absolute,46));root.RowStyles.Add(new(SizeType.Absolute,42));root.RowStyles.Add(new(SizeType.Percent,100));root.RowStyles.Add(new(SizeType.Absolute,32));
        var headerBar=new Panel{Dock=DockStyle.Fill,BackColor=HeaderDark,Padding=new Padding(20,0,20,0)};
        headerBar.Controls.Add(new Label{Text="LAN Messenger",Font=new Font("Segoe UI",18,FontStyle.Bold),AutoSize=true,ForeColor=Color.White,Location=new Point(20,14)});
        root.Controls.Add(headerBar,0,0);
        var toolbar=new FlowLayoutPanel{Dock=DockStyle.Fill,WrapContents=false,Padding=new Padding(20,4,0,0),BackColor=PanelBg};var save=new Button{Text="Save name",AutoSize=true};scan=new Button{Text="Refresh",AutoSize=true};add=new Button{Text="Add by IP",AutoSize=true};
        var createGroup=new Button{Text="New group",AutoSize=true};toolbar.Controls.AddRange([avatarBox,profile,save,scan,add,createGroup,connection,permissions,about,deleteData]);createGroup.Click+=(_,_)=>CreateGroup();permissions.Click+=(_,_)=>ShowPermissionsMenu();about.Click+=(_,_)=>ShowAbout();deleteData.Click+=(_,_)=>DeleteAllDataConfirm();avatarBox.Click+=async(_,_)=>await ChangeAvatar();RoundCorners(avatarBox,20);
        foreach(var b in new[]{save,scan,add,createGroup,connection,permissions,about,deleteData})StyleButton(b);
        root.Controls.Add(toolbar,0,1);var statusPanel=new Panel{Dock=DockStyle.Fill,Padding=new Padding(20,0,20,0),BackColor=PanelBg};statusPanel.Controls.Add(status);root.Controls.Add(statusPanel,0,2);
        var split=new SplitContainer{Size=new Size(950,460),Dock=DockStyle.Fill,SplitterDistance=300,FixedPanel=FixedPanel.Panel1,Panel1MinSize=220,Panel2MinSize=280};
        var people=new TableLayoutPanel{Dock=DockStyle.Fill,RowCount=2,ColumnCount=1};people.ColumnStyles.Add(new(SizeType.Percent,100));people.RowStyles.Add(new(SizeType.Absolute,35));people.RowStyles.Add(new(SizeType.Percent,100));people.Controls.Add(new Label{Text="CONVERSATIONS",AutoSize=true},0,0);people.Controls.Add(contacts,0,1);split.Panel1.Controls.Add(people);
        var chat=new TableLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(15,0,0,0),ColumnCount=1,RowCount=6};chat.ColumnStyles.Add(new(SizeType.Percent,100));chat.RowStyles.Add(new(SizeType.Absolute,48));chat.RowStyles.Add(new(SizeType.Absolute,44));groupNoticeRow=new RowStyle(SizeType.Absolute,0);chat.RowStyles.Add(groupNoticeRow);chat.RowStyles.Add(new(SizeType.Percent,100));pendingAttachmentRow=new RowStyle(SizeType.Absolute,0);chat.RowStyles.Add(pendingAttachmentRow);chat.RowStyles.Add(new(SizeType.Absolute,88));chat.Controls.Add(heading,0,0);var actions=new FlowLayoutPanel{Dock=DockStyle.Fill,WrapContents=false};actions.Controls.AddRange([verify,members,leaveGroup,clear,fastTransfer,recordVoice,stopRecording,callButton]);chat.Controls.Add(actions,0,1);
        foreach(var b in new[]{verify,members,leaveGroup,clear,fastTransfer,recordVoice,stopRecording})StyleButton(b);StyleButton(callButton,true);groupNotice.AutoSize=false;groupNotice.Dock=DockStyle.Fill;groupNotice.Margin=new Padding(0,2,0,0);chat.Controls.Add(groupNotice,0,2);chat.Controls.Add(feed,0,3);chat.Controls.Add(attachmentDraft,0,4);
        var input=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=3,Padding=new Padding(0,10,0,0)};input.ColumnStyles.Add(new(SizeType.Percent,100));input.ColumnStyles.Add(new(SizeType.Absolute,95));input.Controls.Add(composer,0,0);input.Controls.Add(send,1,0);input.ColumnStyles.Add(new(SizeType.Absolute,85));input.Controls.Add(attach,2,0);chat.Controls.Add(input,0,5);split.Panel2.Controls.Add(chat);
        StyleButton(attach);StyleButton(send,true);
        var splitPanel=new Panel{Dock=DockStyle.Fill,Padding=new Padding(20,4,20,4),BackColor=PanelBg};splitPanel.Controls.Add(split);root.Controls.Add(splitPanel,0,3);
        var footerPanel=new Panel{Dock=DockStyle.Fill,Padding=new Padding(20,4,20,4),BackColor=PanelBg};footerPanel.Controls.Add(new Label{Text="Encrypted connection and local history • Verify the safety code on both devices before chatting",AutoSize=true,ForeColor=Color.DimGray,Dock=DockStyle.Left});root.Controls.Add(footerPanel,0,4);Controls.Add(root);
        contacts.DrawMode=DrawMode.OwnerDrawFixed;contacts.ItemHeight=64;contacts.BackColor=Color.White;
        contacts.DrawItem+=(_,e)=>{if(e.Index<0)return;var item=(ContactItem)contacts.Items[e.Index];bool active=(e.State&DrawItemState.Selected)!=0;using var bg=new SolidBrush(active?Color.FromArgb(227,234,250):Color.White);e.Graphics.FillRectangle(bg,e.Bounds);
            var avatarRect=new Rectangle(e.Bounds.X+10,e.Bounds.Y+13,36,36);
            if(!item.Group&&avatarCache.TryGetValue(item.Id,out var photo)){
                var oldClip=e.Graphics.Clip;using var clipPath=new System.Drawing.Drawing2D.GraphicsPath();clipPath.AddEllipse(avatarRect);e.Graphics.SetClip(clipPath,System.Drawing.Drawing2D.CombineMode.Intersect);
                e.Graphics.DrawImage(photo,avatarRect);e.Graphics.Clip=oldClip;
            }else{using var badge=new SolidBrush(item.Group?Color.FromArgb(227,220,251):NameColor(item.Id));e.Graphics.FillEllipse(badge,avatarRect);TextRenderer.DrawText(e.Graphics,item.Group?"G":item.Name[..Math.Min(1,item.Name.Length)].ToUpperInvariant(),Font,new Rectangle(e.Bounds.X+10,e.Bounds.Y+18,36,28),Color.White,TextFormatFlags.HorizontalCenter);}
            if(!item.Group){var dot=new Rectangle(avatarRect.Right-10,avatarRect.Bottom-10,12,12);using var ring=new SolidBrush(Color.White);e.Graphics.FillEllipse(ring,Rectangle.Inflate(dot,2,2));using var online=new SolidBrush(item.Online?Color.FromArgb(33,150,243):Color.Gray);e.Graphics.FillEllipse(online,dot);}
            using var bold=new Font(Font,FontStyle.Bold);TextRenderer.DrawText(e.Graphics,item.Name,bold,new Rectangle(e.Bounds.X+55,e.Bounds.Y+10,e.Bounds.Width-60,24),Ink,TextFormatFlags.EndEllipsis);using var small=new Font("Segoe UI",9);TextRenderer.DrawText(e.Graphics,item.Detail,small,new Rectangle(e.Bounds.X+55,e.Bounds.Y+35,e.Bounds.Width-60,22),Color.SlateGray,TextFormatFlags.EndEllipsis);
            if(item.Unread>0){var count=item.Unread>99?"99+":item.Unread.ToString();int d=22;var badgeRect=new Rectangle(e.Bounds.Right-d-12,e.Bounds.Y+(e.Bounds.Height-d)/2,d,d);using var unread=new SolidBrush(Accent);e.Graphics.FillEllipse(unread,badgeRect);using var tiny=new Font("Segoe UI",8,FontStyle.Bold);TextRenderer.DrawText(e.Graphics,count,tiny,badgeRect,Color.White,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter);}};
        var deleteConversationItem=contactMenu.Items.Add("Delete conversation");deleteConversationItem.Click+=(_,_)=>DeleteConversationConfirm();
        contactMenu.Opening+=(_,e)=>{var idx=contacts.IndexFromPoint(contacts.PointToClient(Cursor.Position));if(idx<0||idx>=contacts.Items.Count){e.Cancel=true;return;}contacts.SelectedIndex=idx;deleteConversationItem.Text=((ContactItem)contacts.Items[idx]).Group?"Leave group":"Delete conversation";};
        contacts.ContextMenuStrip=contactMenu;
        StyleButtons(root);send.BackColor=Accent;send.ForeColor=Color.White;RoundCorners(send,8);feed.Resize+=(_,_)=>{if(selected!=null){lastFeed="";Render();}};feed.TopReached+=LoadOlderPage;
        avatarBox.Paint+=(_,e)=>{if(avatarImage==null){using var b=new SolidBrush(Accent);e.Graphics.FillEllipse(b,0,0,avatarBox.Width,avatarBox.Height);TextRenderer.DrawText(e.Graphics,engine.Name.Length>0?engine.Name[..1].ToUpperInvariant():"?",new Font("Segoe UI",14,FontStyle.Bold),avatarBox.ClientRectangle,Color.White,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter);}};
        try{var raw=engine.Avatar;if(raw!=null){avatarImage=TryImageThumbnail(raw,80,80);avatarBox.Image=avatarImage;}}catch{}
        attach.Click+=async(_,_)=>await AttachFile();fastTransfer.Click+=async(_,_)=>await PickAttachment(true);clear.Click+=(_,_)=>ClearChat();members.Click+=(_,_)=>ShowMembers();leaveGroup.Click+=(_,_)=>DeleteConversationConfirm();saveFile.Click+=async(_,_)=>{if(files.SelectedItem is FileItem item)await FileAction(item.Message);};preview.Click+=(_,_)=>PreviewImage();
        recordVoice.Click+=(_,_)=>StartVoiceRecording();stopRecording.Click+=(_,_)=>StopVoiceRecording();
        verify.Click+=(_,_)=>VerifyDevice();
        callButton.Click+=(_,_)=>StartCallToSelected();
        var trayMenu=new ContextMenuStrip();trayMenu.Items.Add("Open LAN Messenger",null,(_,_)=>RestoreWindow());trayMenu.Items.Add(trayConnection);trayMenu.Items.Add("Test notification",null,(_,_)=>ShowNotification(null,"LAN Messenger","This is a test notification from LAN Messenger."));trayMenu.Items.Add("Exit",null,(_,_)=>{exiting=true;Close();});tray.ContextMenuStrip=trayMenu;tray.DoubleClick+=(_,_)=>RestoreWindow();tray.BalloonTipClicked+=(_,_)=>RestoreWindow(notificationPeer);
        connection.Click+=(_,_)=>SetConnection(!requestedOnline||!engine.Running);trayConnection.Click+=(_,_)=>SetConnection(!requestedOnline||!engine.Running);
        engine.Received+=m=>{if(!IsDisposed&&IsHandleCreated)try{BeginInvoke(new Action(()=>{Render();var peer=engine.Peers.FirstOrDefault(p=>p.Id==m.From);ShowNotification(m.GroupId.Length>0?m.GroupId:m.From,m.GroupId.Length>0?engine.DisplayName(m.GroupId):peer?.Name??"LAN Messenger","New encrypted message");}));}catch{}};
        engine.Forgotten+=peerId=>{if(!IsDisposed&&IsHandleCreated)try{BeginInvoke(new Action(()=>{Render();ShowNotification(peerId,"LAN Messenger",engine.DisplayName(peerId)+" has removed you as a contact. Verify again to keep chatting.");}));}catch{}};
        // Attachment transfers run on background connection threads, not the UI thread — marshal
        // back to update the in-flight "Sending NN%" status shown on the message's own bubble.
        engine.TransferProgress+=(id,done,total)=>{if(IsDisposed||!IsHandleCreated)return;try{BeginInvoke(new Action(()=>{if(IsDisposed)return;if(done>=total)transferProgress.Remove(id);else transferProgress[id]=(done,total);UpdateTransferLabels();}));}catch{}};
        FormClosing+=(_,e)=>{if(!exiting&&e.CloseReason==CloseReason.UserClosing){e.Cancel=true;StopVoiceRecording();Hide();ShowNotification(null,"LAN Messenger","Still running. Right-click the tray icon and choose Exit to stop.");}};
        save.Click+=(_,_)=>{try{engine.Rename(profile.Text);profile.Text=engine.Name;}catch(Exception e){MessageBox.Show(e.Message,"Could not save name");}};
        scan.Click+=async(_,_)=>{if(!engine.Running)return;await engine.Announce();Render();};add.Click+=async(_,_)=>{if(engine.Running)await AddAddress();};
        contacts.SelectedIndexChanged+=(_,_)=>{if(rendering)return;if(selected!=null)drafts[selected]=composer.Text;var next=(contacts.SelectedItem as ContactItem)?.Id;if(pendingAttachmentTarget!=null&&pendingAttachmentTarget!=next)ClearPendingAttachment();if(recordingConversation!=null&&recordingConversation!=next)StopVoiceRecording();StopActivePlayer();selected=next;composer.Text=selected!=null&&drafts.TryGetValue(selected,out var draft)?draft:"";lastFeed="";Render();};
        send.Click+=async(_,_)=>await Send();composer.KeyDown+=async(_,e)=>{if(e.KeyCode==Keys.Enter&&!e.Shift){e.SuppressKeyPress=true;await Send();}};
        timer.Tick+=(_,_)=>{TickVoiceRecording();TickVoicePlayback();Render();TickCallView();};Shown+=(_,_)=>{timer.Start();if(requestedOnline)SetConnection(true,false);else Render();};
        FormClosed+=(_,_)=>{StopVoiceRecording();StopActivePlayer();callController?.Shutdown();timer.Stop();tray.Visible=false;tray.Dispose();engine.Dispose();};
        feed.Controls.Add(MessageLabel("People running LAN Messenger on your network appear automatically.\n\nContacts and messages stay saved after you close the app.\n\nOffline? Write a message now. It stays Queued until both devices are connected.\n\nNo contacts yet? Open the new app on another device on the same Wi-Fi. You can use Add by IP if discovery is blocked.",11,Ink,Math.Max(300,feed.Width-30)));
        InitializeCalls(data);
    }
    string connectionProblem="";
    void SetConnection(bool online,bool persist=true)
    {
        requestedOnline=online;
        if(persist)try{Directory.CreateDirectory(Path.GetDirectoryName(preferencePath)!);var temp=preferencePath+".tmp";File.WriteAllText(temp,online?"Online":"Offline");File.Move(temp,preferencePath,true);}catch(Exception e){MessageBox.Show(this,e.Message,"Could not save connection preference");}
        connectionProblem="";
        try{if(online)engine.Start();else engine.GoOffline();}
        catch(Exception e){connectionProblem=e.Message;}
        Render();
    }
    async Task Send()
    {
        if(selected==null||!send.Enabled||(pendingAttachmentPath==null&&string.IsNullOrWhiteSpace(composer.Text)))return;
        var target=selected;var path=pendingAttachmentPath;var name=pendingAttachmentName;var caption=composer.Text;var originalStatus=status.Text;var fast=pendingFast;sendBusy=true;
        send.Enabled=false;attach.Enabled=false;fastTransfer.Enabled=false;composer.Enabled=false;send.Text="Preparing…";
        try{
            if(path!=null){
                var total=new FileInfo(path).Length;
                status.Text="Preparing file…";
                await Task.Run(async()=>{if(fast)await engine.QueueFastFileAsync(target,caption,path,name);else await engine.QueueFileFromPathAsync(target,caption,path,null,name);});
            }else engine.Queue(target,caption);
            if(selected==target&&pendingAttachmentPath==path){composer.Clear();drafts.Remove(target);ClearPendingAttachment();}
        }
        catch(Exception e){MessageBox.Show(this,e.Message,"Message not saved");}
        finally{sendBusy=false;if(!IsDisposed){status.Text=originalStatus;send.Enabled=true;attach.Enabled=true;Render();}}
    }
}
