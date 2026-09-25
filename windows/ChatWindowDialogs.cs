using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
namespace LanMessenger;
sealed partial class ChatWindow
{
    void ClearChat(){if(selected==null)return;if(MessageBox.Show(this,"Clear this conversation on this device? Local messages and attachments will be removed and pending sends cancelled. Other devices keep their copies.","Clear conversation",MessageBoxButtons.YesNo,MessageBoxIcon.Question)!=DialogResult.Yes)return;try{engine.ClearConversation(selected);chatViews.Remove(selected);thumbnails.RemoveConversation(selected);drafts.Remove(selected);composer.Clear();ClearPendingAttachment();lastFeed="";Render();}catch(Exception e){MessageBox.Show(this,e.Message,"Could not clear conversation");}}
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
