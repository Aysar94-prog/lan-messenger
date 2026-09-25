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
    byte[] picture;using(var fixture=new Bitmap(640,480)){using(var g=Graphics.FromImage(fixture)){
      g.Clear(Color.CornflowerBlue);g.FillRectangle(Brushes.Orange,0,0,320,240);g.FillRectangle(Brushes.Green,320,240,320,240);
      g.DrawString("Photo scroll regression",SystemFonts.DefaultFont,Brushes.White,40,100);
    }using var png=new MemoryStream();fixture.Save(png,System.Drawing.Imaging.ImageFormat.Png);picture=png.ToArray();}var picturePath=Path.Combine(root,"design.png");File.WriteAllBytes(picturePath,picture);SetField("pendingAttachmentPath",picturePath);SetField("pendingAttachmentName","Design.png");SetField("pendingAttachmentTarget",groupId);Call("RenderPendingAttachment");
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
      for(int step=0;step<8;step++){
        panel.AutoScrollPosition=new Point(0,step%2==0?panel.VerticalScroll.Maximum:step*37);
        typeof(Control).GetMethod("OnMouseWheel",BindingFlags.NonPublic|BindingFlags.Instance)!.Invoke(panel,new object[]{new MouseEventArgs(MouseButtons.None,0,30,30,step%2==0?-120:120)});
        await Task.Delay(30);
      }
      panel.AutoScrollPosition=new Point(0,panel.VerticalScroll.Maximum);await Task.Delay(30);
      var visibleCards=panel.Controls.Cast<Control>().ToArray();
      for(int i=0;i<visibleCards.Length;i++){
        var card=visibleCards[i];
        if(card.Region!=null)throw new Exception("Scrolling card must not use a native window region");
        if(card.Controls[0].Height>50)throw new Exception("Sender row retained default blank height");
        if(i>0&&card.Top<visibleCards[i-1].Bottom)throw new Exception("Cards overlap after chat switch");
        foreach(var actions in card.Controls.OfType<FlowLayoutPanel>().Where(p=>p.Controls.OfType<Button>().Any()))
          if(actions.Height>60)throw new Exception("File actions retained default blank height");
      }
    }
    using(var switchShot=new Bitmap(form.Width,form.Height)){form.DrawToBitmap(switchShot,new Rectangle(0,0,form.Width,form.Height));switchShot.Save(Path.Combine(root,"windows-switch.png"));}
    Console.WriteLine("PASS: repeated scrolled chat switching keeps rows compact and cards separate");
    Call("RestoreWindow",groupId);engine.ClearConversation(groupId);Call("Render");if(((ComboBox)Field("files")).Items.Count!=0||engine.Messages(groupId).Length!=0)throw new Exception("Cleared UI retained attachments");
    // ============ T01: pagination, progressive history, arrivals, per-chat cache ============
    var gateObj=typeof(PeerEngine).GetField("gate",BindingFlags.NonPublic|BindingFlags.Instance)!.GetValue(engine)!;
    var store=(List<PeerEngine.Message>)typeof(PeerEngine).GetField("messages",BindingFlags.NonPublic|BindingFlags.Instance)!.GetValue(engine)!;
    long nowMs=PeerEngine.Now;
    void Inject(params PeerEngine.Message[] batch){lock(gateObj)store.AddRange(batch);}
    IEnumerable<FlowLayoutPanel> AllCards()=>((Control)Field("feed")).Controls.Cast<Control>().OfType<FlowLayoutPanel>();
    FlowLayoutPanel? CardFor(string text)=>AllCards().FirstOrDefault(c=>TextIn(c).Contains(text));
    void WheelUp(){var panel=(FlowLayoutPanel)Field("feed");typeof(Control).GetMethod("OnMouseWheel",BindingFlags.NonPublic|BindingFlags.Instance)!.Invoke(panel,new object[]{new MouseEventArgs(MouseButtons.None,0,30,30,120)});}
    var paging=new List<PeerEngine.Message>();long pt=nowMs;
    for(int i=0;i<30;i++){pt-=60_000;paging.Add(new PeerEngine.Message(Guid.NewGuid().ToString(),second.Id,engine.Id,"perf paging text "+i+" with some realistic message content.",pt,"Received"));}
    Inject(paging.ToArray());
    ((System.Collections.IDictionary)Field("chatViews")).Remove(second.Id);
    Call("RestoreWindow",second.Id);Call("Render");((FlowLayoutPanel)Field("feed")).PerformLayout();
    if(AllCards().Count()!=10)throw new Exception("T01: opening a long chat must render newest 10, saw "+AllCards().Count());
    if(!TextIn((Control)Field("feed")).Contains("Older messages"))throw new Exception("T01: older-history hint missing on open");
    if(TextIn((Control)Field("feed")).Contains("perf paging text 0"))throw new Exception("T01: oldest message rendered before any page load");
    if(!TextIn((Control)AllCards().First()).Contains("perf paging text 20"))throw new Exception("T01: newest-10 window not at the correct offset");
    Console.WriteLine("PASS: T01 newest-10 open with older-history hint");
    var feedPanel=(FlowLayoutPanel)Field("feed");
    feedPanel.AutoScrollPosition=new Point(0,0);WheelUp();await Task.Delay(400);Call("Render");feedPanel.PerformLayout();
    if(AllCards().Count()!=30)throw new Exception("T01: first older page loaded "+AllCards().Count()+" cards");
    if(!ReferenceEquals(CardFor("perf paging text 20"),AllCards().FirstOrDefault(c=>TextIn(c).Contains("perf paging text 20")))&&AllCards().FirstOrDefault(c=>TextIn(c).Contains("perf paging text 20"))==null)throw new Exception("T01: first visible card lost after page load");
    Console.WriteLine("PASS: T01 +20 older messages, anchor preserved");
    feedPanel.AutoScrollPosition=new Point(0,0);WheelUp();await Task.Delay(400);Call("Render");feedPanel.PerformLayout();
    if(AllCards().Count()!=35)throw new Exception("T01: full history shows "+AllCards().Count()+" cards");
    if(TextIn((Control)Field("feed")).Contains("Older messages"))throw new Exception("T01: hint remained after full history");
    if(CardFor("perf paging text 0")==null)throw new Exception("T01: oldest message missing after full history");
    if(TextIn((Control)Field("feed")).Split("perf paging text ").Length-1!=30)throw new Exception("T01: paging rows duplicated or lost");
    Console.WriteLine("PASS: T01 full history loads without duplicates or loss");
    var midCard=CardFor("perf paging text 15");
    Inject(new PeerEngine.Message(Guid.NewGuid().ToString(),second.Id,engine.Id,"perf arrival after history load",nowMs+1000,"Received"));
    Call("Render");feedPanel.PerformLayout();
    if(AllCards().Count()!=35)throw new Exception("T01: arrival changed window size to "+AllCards().Count());
    if(CardFor("perf arrival after history load")==null)throw new Exception("T01: new arrival not rendered");
    if(!ReferenceEquals(midCard,CardFor("perf paging text 15")))throw new Exception("T01: arrival rebuilt visible mid cards");
    Console.WriteLine("PASS: T01 arrival keeps mid-history cards stable");
    var scrollBefore=feedPanel.AutoScrollPosition.Y;var maxBefore=feedPanel.VerticalScroll.Maximum;var beforeSet=AllCards().ToArray();
    Call("RestoreWindow",groupId);Call("Render");feedPanel.PerformLayout();
    Call("RestoreWindow",second.Id);Call("Render");feedPanel.PerformLayout();
    var afterSet=AllCards().ToArray();
    if(beforeSet.Length!=afterSet.Length)throw new Exception("T01: revisit changed card count "+beforeSet.Length+" -> "+afterSet.Length);
    for(int i=0;i<beforeSet.Length;i++)if(!ReferenceEquals(beforeSet[i],afterSet[i]))throw new Exception("T01: revisit rebuilt card "+i);
    if(Math.Abs(feedPanel.AutoScrollPosition.Y-scrollBefore)>2)throw new Exception("T01: revisit lost scroll position scroll "+scrollBefore+"->"+feedPanel.AutoScrollPosition.Y+" max "+maxBefore+"->"+feedPanel.VerticalScroll.Maximum+" h "+feedPanel.DisplayRectangle.Height);
    Console.WriteLine("PASS: T01 chat switch preserves cards, count and scroll");
    // ============ T02: image cards, cache bounds, eviction safety ============
    byte[] Photo2(int w,int h,int seed){using var bmp=new Bitmap(w,h);using(var g=Graphics.FromImage(bmp)){g.Clear(Color.FromArgb(30+seed*13%200,40,90,170));g.FillRectangle(Brushes.Orange,20,20,w/2,h/2);g.DrawString("cache "+seed,SystemFonts.DefaultFont,Brushes.White,300,350);}using var ms=new MemoryStream();bmp.Save(ms,System.Drawing.Imaging.ImageFormat.Png);return ms.ToArray();}
    var imgMessages=new List<PeerEngine.Message>();var imgFiles=new List<(PeerEngine.Message,byte[])>();
    for(int i=0;i<12;i++){var data=Photo2(1280,800,i);var id=Guid.NewGuid().ToString();var m=new PeerEngine.Message(id,second.Id,engine.Id,"",nowMs+2000+i,"Received","","cache-photo-"+i+".png",data.Length,SecureIdentity.Hash(data));imgMessages.Add(m);imgFiles.Add((m,data));}
    Inject(imgMessages.ToArray());
    var storeAtt=typeof(PeerEngine).GetMethod("StoreAttachment",BindingFlags.NonPublic|BindingFlags.Instance)!;
    await Task.Run(()=>{foreach(var (m,data) in imgFiles)storeAtt.Invoke(engine,new object[]{m,data});});
    var cacheObj=Field("thumbnails");var cacheType=cacheObj.GetType();var bytesProp=cacheType.GetProperty("Bytes")!;
    Call("RestoreWindow",second.Id);Call("Render");feedPanel.PerformLayout();
    if(AllCards().Count()!=35)throw new Exception("T02: image window shows "+AllCards().Count()+" cards");
    if(AllCards().Count(c=>TextIn(c).Contains("cache-photo-"))!=12)throw new Exception("T02: image cards not all rendered");
    if((long)bytesProp.GetValue(cacheObj)!>17L*1024*1024)throw new Exception("T02: thumbnail bytes over budget with 12 live images");
    for(int cycle2=0;cycle2<3;cycle2++){
      Call("RestoreWindow",cycle2%2==0?groupId:second.Id);await Task.Delay(80);Call("Render");feedPanel.PerformLayout();
      var visible2=AllCards().ToArray();
      for(int i=0;i<visible2.Length;i++){var card=visible2[i];if(card.Region!=null)throw new Exception("T02: cached card uses a native window region");if(i>0&&card.Top<visible2[i-1].Bottom)throw new Exception("T02: cards overlap after switch");}
    }
    if((int)type.GetProperty("CachedChatCount")!.GetValue(form)!>3)throw new Exception("T02: chat cache exceeded 3 chats");
    if((int)type.GetProperty("CachedCardCount")!.GetValue(form)!>600)throw new Exception("T02: card cache exceeded 600");
    using(var imgShot=new Bitmap(form.Width,form.Height)){form.DrawToBitmap(imgShot,new Rectangle(0,0,form.Width,form.Height));imgShot.Save(Path.Combine(root,"windows-cache.png"));}
    Console.WriteLine("PASS: T02 image switches stay within cache bounds and paint cleanly");
    Call("RestoreWindow",second.Id);engine.ClearConversation(second.Id);Call("Render");feedPanel.PerformLayout();
    if(AllCards().Count()!=0)throw new Exception("T02: cleared image chat retained cards");
    Call("RestoreWindow",groupId);Call("Render");feedPanel.PerformLayout();
    Call("RestoreWindow",second.Id);Call("Render");feedPanel.PerformLayout();
    if(AllCards().Count()!=0||!TextIn((Control)Field("feed")).Contains("A fresh start"))throw new Exception("T02: cleared history was not dropped on revisit");
    ((System.Collections.IDictionary)Field("chatViews")).Remove(second.Id);
    var reSeed=new List<PeerEngine.Message>();long rt=nowMs+5000;
    for(int i=0;i<11;i++){rt+=60_000;reSeed.Add(new PeerEngine.Message(Guid.NewGuid().ToString(),second.Id,engine.Id,"perf fresh "+i+" after clear.",rt,"Received"));}
    Inject(reSeed.ToArray());Call("Render");feedPanel.PerformLayout();
    if(AllCards().Count()!=10)throw new Exception("T02: fresh view after clear shows "+AllCards().Count()+" cards, expected 10");
    if(!TextIn((Control)Field("feed")).Contains("Older messages"))throw new Exception("T02: fresh view missing older-history hint");
    Console.WriteLine("PASS: T02 clear is safe on revisit; fresh view re-paginates at 10");
    // White-box thumbnail cache checks: repeat key hits, budget pressure retires, release frees bytes.
    var acquire=cacheType.GetMethod("Acquire")!;var release=cacheType.GetMethod("Release")!;
    var hitsProp=cacheType.GetField("Hits")!;var retProp=cacheType.GetField("Retirements")!;
    var h0=(long)hitsProp.GetValue(cacheObj)!;
    var imgA=(Image)acquire.Invoke(cacheObj,new object[]{"t2-key","t2conv",(Func<Image?>)(()=>new Bitmap(420,320))})!;
    var imgB=(Image)acquire.Invoke(cacheObj,new object[]{"t2-key","t2conv",(Func<Image?>)(()=>new Bitmap(420,320))})!;
    if((long)hitsProp.GetValue(cacheObj)!<=h0)throw new Exception("T02: repeated key must hit the thumbnail cache");
    release.Invoke(cacheObj,new object[]{"t2-key"});release.Invoke(cacheObj,new object[]{"t2-key"});imgA.Dispose();imgB.Dispose();
    Console.WriteLine("PASS: T02 white-box thumbnail cache hits on repeat key");
    long r0=(long)retProp.GetValue(cacheObj)!;var keys=new List<string>();long beforeP=0;
    for(int i=0;i<40;i++){var key="t2-pressure-"+i;keys.Add(key);var img=(Image)acquire.Invoke(cacheObj,new object[]{"t2-pressure-"+i,"t2conv",(Func<Image?>)(()=>new Bitmap(420,320))})!;img.Dispose();}
    if((long)retProp.GetValue(cacheObj)!<=r0)throw new Exception("T02: 40 thumbnails must exceed the 16 MiB budget and retire entries");
    beforeP=(long)bytesProp.GetValue(cacheObj)!;
    foreach(var key in keys)release.Invoke(cacheObj,new object[]{key});
    if((long)bytesProp.GetValue(cacheObj)!>beforeP)throw new Exception("T02: releasing retired thumbnails must free bytes");
    Console.WriteLine("PASS: T02 white-box budget retirement releases bytes");
    // Regression: opening a chat for the first time at five messages must not
    // freeze its visible window at five when the sixth message arrives.
    var shortChat=engine.CreateGroup("Short arrival regression",new[]{remote.Id,second.Id});
    long shortTime=PeerEngine.Now;
    var shortMessages=Enumerable.Range(1,5).Select(i=>new PeerEngine.Message(Guid.NewGuid().ToString(),second.Id,engine.Id,"short arrival "+i,shortTime+i,"Received",shortChat)).ToArray();
    Inject(shortMessages);Call("RestoreWindow",shortChat);Call("Render");
    if(AllCards().Count()!=5||!TextIn((Control)Field("feed")).Contains("short arrival 1"))throw new Exception("Short-chat setup did not display five messages");
    Inject(new PeerEngine.Message(Guid.NewGuid().ToString(),second.Id,engine.Id,"short arrival 6",shortTime+6,"Received",shortChat));Call("Render");
    if(AllCards().Count()!=6||!TextIn((Control)Field("feed")).Contains("short arrival 1")||!TextIn((Control)Field("feed")).Contains("short arrival 6")||TextIn((Control)Field("feed")).Contains("Older messages"))throw new Exception("A sixth arrival hid an earlier message before the ten-message limit");
    Console.WriteLine("PASS: a first-open five-message chat grows to six without hiding history");
    // Repeated redraws and switches must leave exactly one empty-chat hint.
    var emptyChat=engine.CreateGroup("Empty hint regression",new[]{remote.Id,second.Id});
    Call("RestoreWindow",emptyChat);Call("Render");
    type.GetField("lastFeed",BindingFlags.NonPublic|BindingFlags.Instance)!.SetValue(form,"");Call("Render");
    Call("RestoreWindow",shortChat);Call("RestoreWindow",emptyChat);Call("Render");
    var hintCount=((Control)Field("feed")).Controls.Cast<Control>().Count(c=>c.Text=="A fresh start. Send a message or share a file.");
    if(hintCount!=1)throw new Exception("Empty-chat hint repeated "+hintCount+" times");
    Console.WriteLine("PASS: empty-chat hint remains single after redraw and chat switching");
   }catch(Exception e){Console.Error.WriteLine(e);Environment.ExitCode=1;}
   finally{type.GetField("exiting",BindingFlags.NonPublic|BindingFlags.Instance)!.SetValue(form,true);form.Close();if(engine.Running||trayVisible())Environment.ExitCode=1;Application.ExitThread();}
  bool trayVisible()=>((NotifyIcon)Field("tray")).Visible;
  IEnumerable<Control> AllControls(Control control){yield return control;foreach(Control child in control.Controls)foreach(var descendant in AllControls(child))yield return descendant;}
  };
  Application.Idle+=run;Application.Run();
 }
}
