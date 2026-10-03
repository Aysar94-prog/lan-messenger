using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
namespace LanMessenger;
sealed partial class ChatWindow
{
    void ClearChat(){if(selected==null)return;if(MessageBox.Show(this,"Clear this conversation on this device? Local messages and attachments will be removed and pending sends cancelled. Other devices keep their copies.","Clear conversation",MessageBoxButtons.YesNo,MessageBoxIcon.Question)!=DialogResult.Yes)return;try{engine.ClearConversation(selected);chatViews.Remove(selected);thumbnails.RemoveConversation(selected);drafts.Remove(selected);composer.Clear();ClearPendingAttachment();lastFeed="";Render();}catch(Exception e){MessageBox.Show(this,e.Message,"Could not clear conversation");}}
    void DeleteConversationConfirm()
    {
        if(contacts.SelectedItem is not ContactItem item)return;
        if(item.Group){
            var owned=engine.Groups.FirstOrDefault(x=>x.Id==item.Id);
            if(owned!=null&&owned.Owner==engine.Id){
                var others=owned.Members.Where(m=>m!=engine.Id).ToArray();
                if(others.Length>0){ShowTransferOwnershipPicker(owned,others);return;}
            }
        }
        var title=item.Group?"Leave group":"Delete conversation";
        var message=item.Group
            ?$"Leave \"{item.Name}\"? You'll need a new invitation to rejoin. Other members keep the group and their own copies."
            :$"Delete \"{item.Name}\" entirely? This removes the conversation and its attachments, and revokes verification. Seeing this device again on the network starts from an unverified state. Other devices keep their own copies.";
        if(MessageBox.Show(this,message,title,MessageBoxButtons.YesNo,MessageBoxIcon.Warning)!=DialogResult.Yes)return;
        try{
            engine.DeleteConversation(item.Id);
            chatViews.Remove(item.Id);thumbnails.RemoveConversation(item.Id);drafts.Remove(item.Id);
            if(selected==item.Id){selected=null;feed.Controls.Clear();cards.Clear();statusLabels.Clear();feedConversation="";composer.Clear();ClearPendingAttachment();}
            lastFeed="";lastContacts="";Render();
        }catch(Exception e){MessageBox.Show(this,e.Message,item.Group?"Could not leave group":"Could not delete conversation");}
    }
    // Shown instead of the normal leave confirmation when the local user is this group's owner and
    // other members remain — leaving requires handing off first. Confirming starts the handoff; the
    // actual departure completes automatically, in the background, once every other member has caught
    // up (see PLAN-GROUP-OWNERSHIP-TRANSFER.md), not immediately when this dialog closes.
    void ShowTransferOwnershipPicker(PeerEngine.Group g,string[] others)
    {
        using var dialog=new Form{Text="Choose a new admin",Size=new Size(440,Math.Min(560,220+others.Length*40)),StartPosition=FormStartPosition.CenterParent,Font=Font};
        var layout=new FlowLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(18),FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoScroll=true};
        layout.Controls.Add(MessageLabel($"You're the admin of \"{g.Name}\". Choose who takes over before you leave. You'll leave automatically, in the background, once they and everyone else have caught up.",10,Color.SlateGray,390));
        foreach(var id in others){
            var row=new FlowLayoutPanel{FlowDirection=FlowDirection.LeftToRight,WrapContents=false,AutoSize=true,Margin=new Padding(0,4,0,4)};
            row.Controls.Add(MessageLabel(engine.DisplayName(id),11,Ink,240));
            var pick=new Button{Text="Make admin",AutoSize=true};
            pick.Click+=async(_,_)=>{
                pick.Enabled=false;
                try{await engine.TransferOwnership(g.Id,id);dialog.Close();lastContacts="";lastFeed="";Render();}
                catch(Exception e){MessageBox.Show(dialog,e.Message,"Could not transfer ownership");}
                finally{if(!pick.IsDisposed)pick.Enabled=true;}
            };
            row.Controls.Add(pick);
            layout.Controls.Add(row);
        }
        var cancel=new Button{Text="Cancel",AutoSize=true};cancel.Click+=(_,_)=>dialog.Close();StyleButtons(layout);layout.Controls.Add(cancel);
        dialog.Controls.Add(layout);dialog.ShowDialog(this);
    }
    void DeleteAllDataConfirm()
    {
        if(MessageBox.Show(this,"Delete ALL app data? This permanently removes every conversation, contact, group and downloaded file on this device. Your profile name and picture are kept. This cannot be undone.","Delete app data",MessageBoxButtons.YesNo,MessageBoxIcon.Warning)!=DialogResult.Yes)return;
        try{
            engine.DeleteAllData();
            foreach(var conv in chatViews.Keys.ToList())thumbnails.RemoveConversation(conv);
            chatViews.Clear();drafts.Clear();selected=null;feed.Controls.Clear();cards.Clear();statusLabels.Clear();feedConversation="";composer.Clear();ClearPendingAttachment();lastFeed="";lastContacts="";
            Render();
        }catch(Exception e){MessageBox.Show(this,e.Message,"Could not delete app data");}
    }
    void ShowMembers()
    {
        var g=engine.Groups.FirstOrDefault(g=>g.Id==selected);if(g==null)return;
        var known=engine.AllKnownMembers(g.Id);bool isOwner=g.Owner==engine.Id;
        using var dialog=new Form{Text=g.Name+" · Members",Size=new Size(460,Math.Min(640,140+known.Length*44)),StartPosition=FormStartPosition.CenterParent,Font=Font};
        var layout=new FlowLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(18),FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoScroll=true};
        layout.Controls.Add(MessageLabel("Every pair must verify each other to exchange group messages.",10,Color.SlateGray,400));
        foreach(var (id,active) in known){
            var row=new FlowLayoutPanel{FlowDirection=FlowDirection.LeftToRight,WrapContents=false,AutoSize=true,Margin=new Padding(0,4,0,4)};
            var status=id==engine.Id?" (you)":id==g.Owner?" · Admin":engine.Peers.Any(p=>p.Id==id&&p.Trusted)?" · Verified":" · Verify in People";
            // Owner-only, and only meaningful for a currently-active member other than yourself — never
            // implies the group as a whole is consistent, just whether this one member has caught up.
            var sync=isOwner&&active&&id!=engine.Id?(engine.MemberAckedVersion(g.Id,id)>=g.MembersVersion?" · Synced":" · Catching up"):"";
            row.Controls.Add(MessageLabel(engine.DisplayName(id)+status+sync+(active?"":" · Left"),11,Ink,!active&&isOwner?190:280));
            if(isOwner&&!active){
                var reinvite=new Button{Text="Re-invite",AutoSize=true};
                reinvite.Click+=async(_,_)=>{reinvite.Enabled=false;try{await engine.ReinviteMember(g.Id,id);dialog.Close();}catch(Exception e){MessageBox.Show(dialog,e.Message,"Could not re-invite");}finally{if(!reinvite.IsDisposed)reinvite.Enabled=true;}};
                row.Controls.Add(reinvite);
            }
            layout.Controls.Add(row);
        }
        var close=new Button{Text="Close",AutoSize=true};close.Click+=(_,_)=>dialog.Close();StyleButtons(layout);layout.Controls.Add(close);
        dialog.Controls.Add(layout);dialog.ShowDialog(this);
    }
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
            var confirm=new Button{Text="Codes match — verify",AutoSize=true,Enabled=!peer.KeyChanged};var revoke=new Button{Text="Revoke verification",AutoSize=true,Enabled=peer.Verified.Length>0};var trusted=new Button{Text="Trusted call access…",AutoSize=true,Enabled=peer.Trusted};layout.Controls.Add(confirm);layout.Controls.Add(revoke);layout.Controls.Add(trusted);dialog.Controls.Add(layout);
            confirm.Click+=(_,_)=>{try{engine.Verify(peer.Id,code);dialog.Close();Render();}catch(Exception error){MessageBox.Show(error.Message,"Verification failed");}};
            revoke.Click+=(_,_)=>{if(MessageBox.Show("Stop trusting this device? Messages will stay queued until you compare and verify its code again.","Revoke verification",MessageBoxButtons.YesNo)==DialogResult.Yes){engine.Revoke(peer.Id);dialog.Close();Render();}};
            trusted.Click+=(_,_)=>ShowTrustedCallAccess(peer,dialog);
            dialog.ShowDialog(this);
        }catch(Exception e){MessageBox.Show(e.Message,"Verify device");}
    }

    void ShowTrustedCallAccess(PeerEngine.Peer peer,Form owner)
    {
        var mask=engine.TrustedCallMask(peer.Id);
        using var dialog=new Form{Text="Trusted call access",Size=new Size(540,330),StartPosition=FormStartPosition.CenterParent,Font=Font};
        var panel=new FlowLayoutPanel{Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false,Padding=new Padding(18),AutoScroll=true};
        panel.Controls.Add(new Label{Text="Allow this verified device to answer calls without asking. Camera and video controls remain unavailable on Windows until production video calling is complete.",AutoSize=true,MaximumSize=new Size(480,0)});
        var voice=new CheckBox{Text="Automatically answer voice calls",AutoSize=true,Checked=(mask&PeerEngine.TrustedAutoAnswerVoice)!=0};
        var video=new CheckBox{Text="Automatically answer video calls (not available yet)",AutoSize=true,Enabled=false};
        var camera=new CheckBox{Text="Allow remote front/rear camera control (not available yet)",AutoSize=true,Enabled=false};
        var speaker=new CheckBox{Text="Allow remote speaker control (planned protocol)",AutoSize=true,Enabled=false};
        var save=new Button{Text="Save",AutoSize=true};panel.Controls.AddRange([voice,video,camera,speaker,save]);dialog.Controls.Add(panel);
        save.Click+=(_,_)=>{var next=voice.Checked?PeerEngine.TrustedAutoAnswerVoice:0;if(next!=0&&MessageBox.Show("Calls from this device will connect immediately and may activate your microphone. You can mute or hang up at any time.","Enable trusted call access",MessageBoxButtons.OKCancel,MessageBoxIcon.Warning)!=DialogResult.OK)return;engine.SetTrustedCallMask(peer.Id,next);dialog.Close();owner.Close();Render();};
        dialog.ShowDialog(owner);
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
