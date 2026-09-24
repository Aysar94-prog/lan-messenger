using System.Reflection;
using LanMessenger;
class Check
{
 [STAThread] static void Main(string[] args)
 {
  ApplicationConfiguration.Initialize();
  var root=Path.Combine(Path.GetFullPath(args[0]),"ui-"+Guid.NewGuid().ToString("N"));
  var type=Assembly.Load("LanMessenger").GetType("LanMessenger.ChatWindow")!;
  using var form=(Form)Activator.CreateInstance(type,new object?[]{root,new TestProtector(root)})!;
  object Field(string name)=>type.GetField(name,BindingFlags.NonPublic|BindingFlags.Instance)!.GetValue(form)!;
  void SetField(string name,object? value)=>type.GetField(name,BindingFlags.NonPublic|BindingFlags.Instance)!.SetValue(form,value);
  void Call(string name,params object?[] values)=>type.GetMethod(name,BindingFlags.NonPublic|BindingFlags.Instance)!.Invoke(form,values);
  async Task CallAsync(string name,params object?[] values)=>await (Task)type.GetMethod(name,BindingFlags.NonPublic|BindingFlags.Instance)!.Invoke(form,values)!;
  string TextIn(Control control)=>control.Text+string.Concat(control.Controls.Cast<Control>().Select(TextIn));
  int Count()=>(int)type.GetProperty("NotificationCount")!.GetValue(form)!;
  var engine=(PeerEngine)Field("engine");engine.Start("127.0.0.5",45972,45971);
  var remoteRoot=Path.Combine(root,"remote");using var remote=new PeerEngine(remoteRoot,"Phone test",new TestProtector(remoteRoot));remote.Start("127.0.0.6",45972,45971);
  form.ShowInTaskbar=false;form.Opacity=0;form.Show();
  EventHandler? run=null;run=async(_,_)=>{
   Application.Idle-=run;
   async Task Wait(Func<bool> condition,string label){var end=DateTime.UtcNow.AddSeconds(30);while(!condition()&&DateTime.UtcNow<end)await Task.Delay(100);if(!condition())throw new Exception(label);Console.WriteLine("PASS: "+label);}
   try{
    await engine.AddAddress("127.0.0.6:45972");await remote.AddAddress("127.0.0.5:45972");
    Call("Render");((ListBox)Field("contacts")).SelectedIndex=0;
    if(((Button)Field("send")).Enabled)throw new Exception("Unverified UI allowed sending");
    string code=engine.PairingCode(remote.Id);if(code!=remote.PairingCode(engine.Id))throw new Exception("Pairing mismatch");engine.Verify(remote.Id,code);remote.Verify(engine.Id,code);Call("Render");
    form.WindowState=FormWindowState.Minimized;int before=Count();
    remote.Queue(engine.Id,"PRIVATE minimized message");
    await Wait(()=>Count()==before+1,"Minimized window dispatches incoming notification once");
    var tray=(NotifyIcon)Field("tray");
    if(tray.BalloonTipText!="New encrypted message"||tray.BalloonTipTitle.Contains("PRIVATE"))throw new Exception("Notification privacy failed");
    form.Close();if(form.Visible||!engine.Running)throw new Exception("Close did not preserve background reception");
    before=Count();remote.Queue(engine.Id,"PRIVATE hidden message");
    await Wait(()=>Count()==before+1&&engine.Messages(remote.Id).Any(m=>m.Text=="PRIVATE hidden message"),"Hidden window continues receiving and notifying");
    await Wait(()=>remote.Messages(engine.Id).Count(m=>m.Status=="Delivered")==2,"Notifications do not prevent durable acknowledgements");
    await Task.Delay(2300);if(Count()!=before+1)throw new Exception("Repeated notification without new message");
    ((ListBox)Field("contacts")).SelectedIndex=-1;
    typeof(NotifyIcon).GetMethod("OnBalloonTipClicked",BindingFlags.Instance|BindingFlags.NonPublic)!.Invoke(tray,null);
    if(!form.Visible||form.WindowState!=FormWindowState.Normal||(string)Field("selected")!=remote.Id)throw new Exception("Notification click did not restore sender conversation");
    if(!TextIn((Control)Field("feed")).Contains("PRIVATE hidden message"))throw new Exception("Incoming chat not rendered");
    remote.Dispose();((TextBox)Field("composer")).Text="Saved from native Windows UI";await CallAsync("Send");
    if(engine.Pending!=1||!TextIn((Control)Field("feed")).Contains("Queued"))throw new Exception("Native offline compose failed");
    var secondRoot=Path.Combine(root,"second");using var second=new PeerEngine(secondRoot,"Design team",new TestProtector(secondRoot));second.Start("127.0.0.7",45972,45971);
    await engine.AddAddress("127.0.0.7:45972");await second.AddAddress("127.0.0.5:45972");var secondCode=engine.PairingCode(second.Id);engine.Verify(second.Id,secondCode);second.Verify(engine.Id,secondCode);
    var groupId=engine.CreateGroup("Project room",new[]{remote.Id,second.Id});Call("Render");Call("RestoreWindow",groupId);
    ((TextBox)Field("composer")).Text="Here is the design for our next update.";await CallAsync("Send");
    var picture=Convert.FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jk1sAAAAASUVORK5CYII=");var picturePath=Path.Combine(root,"design.png");File.WriteAllBytes(picturePath,picture);SetField("pendingAttachmentPath",picturePath);SetField("pendingAttachmentName","Design.png");SetField("pendingAttachmentTarget",groupId);Call("RenderPendingAttachment");
    if(engine.Messages(groupId).Length!=1||!((Control)Field("attachmentDraft")).Controls.Cast<Control>().SelectMany(AllControls).Any(c=>c is PictureBox))throw new Exception("Choosing an image sent it before Send or omitted the draft preview");
    using(var draftBitmap=new Bitmap(form.Width,form.Height)){form.DrawToBitmap(draftBitmap,new Rectangle(0,0,form.Width,form.Height));draftBitmap.Save(Path.Combine(root,"windows-draft.png"));}
    ((TextBox)Field("composer")).Text="Draft image caption";await CallAsync("Send");
    if(((ComboBox)Field("files")).Items.Count!=1||!((Button)Field("saveFile")).Enabled||!((Button)Field("members")).Enabled||((Button)Field("verify")).Enabled)throw new Exception("Group/attachment controls failed");
    if(engine.Messages(groupId).Length!=2||engine.Messages(groupId).Last().Text!="Draft image caption"||!TextIn((Control)Field("feed")).Contains("Design.png")||!((Control)Field("feed")).Controls.Cast<Control>().SelectMany(AllControls).Any(c=>c is PictureBox))throw new Exception("Explicit attachment send, caption or inline image failed");
    Console.WriteLine("PASS: native group selection, sending, attachment list and group actions");
    using var bitmap=new Bitmap(form.Width,form.Height);form.DrawToBitmap(bitmap,new Rectangle(0,0,form.Width,form.Height));bitmap.Save(Path.Combine(root,"windows.png"));
    Console.WriteLine("PASS: private alert, notification click restores sender, verified offline compose, no repeated alert");
    Console.WriteLine("Screenshot: "+Path.Combine(root,"windows.png"));
    form.Size=form.MinimumSize;form.PerformLayout();using var compact=new Bitmap(form.Width,form.Height);form.DrawToBitmap(compact,new Rectangle(0,0,form.Width,form.Height));compact.Save(Path.Combine(root,"windows-compact.png"));
    second.Queue(engine.Id,"Second seen check");
    await Wait(()=>engine.Messages(second.Id).Any(m=>m.Text=="Second seen check"&&m.Status=="Received"),"1:1 message from a verified device arrives before being read");
    Call("RestoreWindow",second.Id);
    await Wait(()=>engine.Messages(second.Id).Any(m=>m.Text=="Second seen check"&&(m.Status=="Read"||m.Status=="Seen")),"Opening the conversation in the UI marks the message read");
    await Wait(()=>second.Messages(engine.Id).Any(m=>m.Text=="Second seen check"&&m.Status=="Seen"),"Seen receipt reaches the original sender");
    ((TextBox)Field("composer")).Text="Seen tick check";await CallAsync("Send");
    await Wait(()=>engine.Messages(second.Id).Any(m=>m.Text=="Seen tick check"&&m.Status=="Delivered"),"Reply sent from the native UI is delivered");
    second.MarkRead(engine.Id);
    await Wait(()=>engine.Messages(second.Id).Any(m=>m.Text=="Seen tick check"&&m.Status=="Seen"),"Native UI message reaches Seen once the other device reads it");
    Call("Render");
    if(!TextIn((Control)Field("feed")).Contains("✓✓"))throw new Exception("Seen ticks not rendered in the message feed");
    using(var seenShot=new Bitmap(form.Width,form.Height)){form.DrawToBitmap(seenShot,new Rectangle(0,0,form.Width,form.Height));seenShot.Save(Path.Combine(root,"windows-seen.png"));}
    Console.WriteLine("Screenshot: "+Path.Combine(root,"windows-seen.png"));
    Console.WriteLine("PASS: opening a conversation marks messages read and renders seen ticks");
    var liveFeed=(Control)Field("feed");var firstCard=liveFeed.Controls[0];int notices=Count();
    for(int i=1;i<100;i++)typeof(PeerEngine).GetMethod("ReportProgress",BindingFlags.NonPublic|BindingFlags.Instance)!.Invoke(engine,new object[]{"synthetic-progress",(long)i,100L});
    await Task.Delay(200);Call("Render");
    if(!ReferenceEquals(firstCard,liveFeed.Controls[0])||Count()!=notices)throw new Exception("Progress rebuilt cards or generated notifications");
    second.Queue(engine.Id,"Append without blinking");
    await Wait(()=>engine.Messages(second.Id).Any(m=>m.Text=="Append without blinking"),"Next message arrives");
    Call("Render");if(!ReferenceEquals(firstCard,liveFeed.Controls[0]))throw new Exception("Appending a message rebuilt existing cards");
    second.QueueFile(engine.Id,"manual-download.bin",picture);
    await Wait(()=>engine.Messages(second.Id).Any(m=>m.FileName=="manual-download.bin"),"Incoming file offer arrives");
    var offer=engine.Messages(second.Id).First(m=>m.FileName=="manual-download.bin");Call("Render");
    if(engine.HasAttachment(offer)||!TextIn(liveFeed).Contains("Download"))throw new Exception("Incoming attachment downloaded before click or no Download button");
    var downloadButton=liveFeed.Controls.Cast<Control>().SelectMany(AllControls).OfType<Button>().First(b=>b.Text=="Download");SetField("downloadDestinationPicker",(Func<PeerEngine.Message,string?>)(_=>null));
      await CallAsync("FileAction",offer);
      if(engine.HasAttachment(offer)||engine.Downloading(offer))throw new Exception("Picker cancellation started download");
      var chosen=Path.Combine(root,"chosen-download.bin");
      SetField("downloadDestinationPicker",(Func<PeerEngine.Message,string?>)(_=>chosen));downloadButton.PerformClick();
    await Wait(()=>engine.HasAttachment(offer),"Download button explicitly receives the attachment");Call("Render");
    using(var manualShot=new Bitmap(form.Width,form.Height)){form.DrawToBitmap(manualShot,new Rectangle(0,0,form.Width,form.Height));manualShot.Save(Path.Combine(root,"windows-download.png"));}
    if(engine.SavedDestination(offer)!=chosen||!File.ReadAllBytes(chosen).SequenceEqual(picture))throw new Exception("Chosen destination was not used");
      string? opened=null;SetField("openDownloadedFile",(Action<string>)(path=>opened=path));await CallAsync("FileAction",offer);
      if(opened!=chosen)throw new Exception("Completed file did not open its chosen destination");
      Console.WriteLine("PASS: picker cancellation, chosen destination, opening downloaded file, stable cards");
    second.QueueFile(engine.Id,"automatic-photo.png",picture);
    await Wait(()=>engine.Messages(second.Id).Any(m=>m.FileName=="automatic-photo.png"&&engine.HasAttachment(m)),"Image downloads without clicking Download");
    Call("Render");
    var imageMessage=engine.Messages(second.Id).First(m=>m.FileName=="automatic-photo.png");
    var imageCard=liveFeed.Controls.Cast<Control>().First(c=>TextIn(c).Contains(imageMessage.FileName));
    if(!imageCard.Controls.OfType<PictureBox>().Any())throw new Exception("Automatic image missing inline preview");
    if(TextIn(imageCard).Contains("Download"))throw new Exception("Downloaded image still asks for Download");
    Console.WriteLine("PASS: automatic photo renders inline");
    for(int cycle=0;cycle<6;cycle++){
      Call("RestoreWindow",cycle%2==0?groupId:second.Id);await Task.Delay(80);Call("Render");
      var panel=(FlowLayoutPanel)Field("feed");
      panel.AutoScrollPosition=new Point(0,panel.VerticalScroll.Maximum);await Task.Delay(30);
      var visibleCards=panel.Controls.Cast<Control>().ToArray();
      for(int i=0;i<visibleCards.Length;i++){
        var card=visibleCards[i];
        if(card.Controls[0].Height>50)throw new Exception("Sender row retained default blank height");
        if(i>0&&card.Top<visibleCards[i-1].Bottom)throw new Exception("Cards overlap after chat switch");
        foreach(var actions in card.Controls.OfType<FlowLayoutPanel>().Where(p=>p.Controls.OfType<Button>().Any()))
          if(actions.Height>60)throw new Exception("File actions retained default blank height");
      }
    }
    using(var switchShot=new Bitmap(form.Width,form.Height)){form.DrawToBitmap(switchShot,new Rectangle(0,0,form.Width,form.Height));switchShot.Save(Path.Combine(root,"windows-switch.png"));}
    Console.WriteLine("PASS: repeated scrolled chat switching keeps rows compact and cards separate");
    Call("RestoreWindow",groupId);engine.ClearConversation(groupId);Call("Render");if(((ComboBox)Field("files")).Items.Count!=0||engine.Messages(groupId).Length!=0)throw new Exception("Cleared UI retained attachments");
   }catch(Exception e){Console.Error.WriteLine(e);Environment.ExitCode=1;}
   finally{type.GetField("exiting",BindingFlags.NonPublic|BindingFlags.Instance)!.SetValue(form,true);form.Close();if(engine.Running||trayVisible())Environment.ExitCode=1;Application.ExitThread();}
  bool trayVisible()=>((NotifyIcon)Field("tray")).Visible;
  IEnumerable<Control> AllControls(Control control){yield return control;foreach(Control child in control.Controls)foreach(var descendant in AllControls(child))yield return descendant;}
  };
  Application.Idle+=run;Application.Run();
 }
}
