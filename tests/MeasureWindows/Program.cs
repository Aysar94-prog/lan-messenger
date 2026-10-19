using System.Diagnostics;
using System.Drawing.Drawing2D;
using System.Reflection;
using LanMessenger;
// Chat-performance measurement harness for the Windows pagination/cache plan.
// Run BEFORE the change (baseline) and AFTER it, then compare the RESULT lines:
//   RESULT|<label>|<firstOpenMs>|<revisitMs>|<paintMs>|<cardsBuilt>|<imagesBuilt>|<totalFeedPictures>
// With --pages it also reports repeated opens of the largest chat once pagination exists.
class Measure
{
 [STAThread] static void Main(string[] args)
 {
  bool pages = args.Contains("--pages");
  if (args.Contains("--probe")) { RunProbe(args[0]); return; }
  var diag = Path.Combine(args[0], "diag.log");
  ApplicationConfiguration.Initialize();
  var root = Path.Combine(args[0], "measure-" + Guid.NewGuid().ToString("N"));
  var type = Assembly.Load("LanMessenger").GetType("LanMessenger.ChatWindow")!;
  using var form = (Form)Activator.CreateInstance(type, new object?[] { root, new TestProtector(root) })!;
  object Field(string name) => type.GetField(name, BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(form)!;
  void Call(string name, params object?[] values) => type.GetMethod(name, BindingFlags.NonPublic | BindingFlags.Instance)!.Invoke(form, values);
  var engine = (PeerEngine)Field("engine");
  engine.Start("127.0.0.5", 45982, 45981);
  form.ShowInTaskbar = false; form.Opacity = 0; form.Show();
  EventHandler? run = null; run = async (_, _) =>
  {
   Application.Idle -= run;
   try
   {
    byte[] Photo(int w, int h, Color a, Color b)
    {
     using var bmp = new Bitmap(w, h);
     using (var g = Graphics.FromImage(bmp))
     {
      g.Clear(a); using var brush = new LinearGradientBrush(new Rectangle(0, 0, w, h), a, b, 45f);
      g.FillRectangle(brush, 0, 0, w, h);
      using var pen = new Pen(Color.FromArgb(255, 255, 255, 220), 8f); g.DrawEllipse(pen, w / 3, h / 3, w / 3, h / 3);
      g.DrawString("LAN Messenger photo " + w + "x" + h, SystemFonts.DefaultFont, Brushes.White, 24, 24);
     }
     using var ms = new MemoryStream(); bmp.Save(ms, System.Drawing.Imaging.ImageFormat.Png); return ms.ToArray();
    }
    var photos = new[] { Photo(1280, 800, Color.FromArgb(180, 40, 60), Color.FromArgb(20, 60, 140)), Photo(1920, 1080, Color.FromArgb(30, 120, 80), Color.FromArgb(140, 40, 170)), Photo(800, 600, Color.FromArgb(230, 150, 30), Color.FromArgb(20, 30, 90)), Photo(1024, 768, Color.FromArgb(40, 90, 190), Color.FromArgb(200, 210, 60)) };

    async Task<PeerEngine> AddRemote(string name, byte ip, int port)
    {
     var dir = Path.Combine(root, name);
     var r = new PeerEngine(dir, name, new TestProtector(dir));
     r.Start("127.0.0." + ip, port, 45981);
     await engine.AddAddress("127.0.0." + ip + ":" + port); await r.AddAddress("127.0.0.5:45982");
     string c = engine.PairingCode(r.Id); engine.Verify(r.Id, c); r.Verify(engine.Id, c);
     return r;
    }
    var smallPeer = await AddRemote("small", 6, 45983);
    var mediumPeer = await AddRemote("medium", 7, 45984);
    var largePeer = await AddRemote("large", 8, 45985);

    async Task Seed(PeerEngine src, string marker, int texts, int images)
    {
     for (int i = 0; i < texts; i++) { src.Queue(engine.Id, marker + " text message " + i + " with some realistic chat content so layout work is visible."); await Task.Delay(3); }
     for (int i = 0; i < images; i++) { src.QueueFile(engine.Id, marker + "-photo-" + i + ".png", photos[i % photos.Length]); await Task.Delay(3); }
    }
    await Seed(smallPeer, "small", 5, 0);
    await Seed(mediumPeer, "medium", 40, 6);
    await Seed(largePeer, "large", 100, 12);

    (PeerEngine, string, int)[] chats = { (smallPeer, "small", 5), (mediumPeer, "medium", 46), (largePeer, "large", 112) };
    foreach (var (peer, marker, total) in chats)
     await Wait(() => engine.Messages(peer.Id).Where(m => m.Text.StartsWith(marker) || m.FileName.StartsWith(marker)).Count() >= total, "seed " + marker);
    await Wait(() => engine.Messages(largePeer.Id).Where(m => m.FileName.Length > 0).All(m => engine.HasAttachment(m)), "photo auto-download settles");
    await Task.Delay(2500); // let delivery/seen ACK traffic settle

    ((System.Windows.Forms.Timer)Field("timer")).Stop();
    var feed = (FlowLayoutPanel)Field("feed");
    var contacts = (ListBox)Field("contacts");
    Call("Render");

    int CardCount() => feed.Controls.Cast<Control>().Count(c => c is FlowLayoutPanel);
    int InlineImages() => feed.Controls.Cast<Control>().SelectMany(AllControls).OfType<PictureBox>().Count(p => p.Width >= 200);
    void SelectConversation(string peerId)
    {
     for (int i = 0; i < contacts.Items.Count; i++)
     {
      object item = contacts.Items[i]!;
      if ((string)item.GetType().GetProperty("Id")!.GetValue(item) == peerId) { contacts.SelectedIndex = i; return; }
     }
     throw new Exception("Conversation not in contact list: " + peerId);
    }
    long OpenMs(string peerId, out int cardsOut, out int imagesOut)
    {
     SelectConversation(peerId);
     var sw = Stopwatch.StartNew();
     Call("Render");
     feed.PerformLayout();
     sw.Stop();
     cardsOut = CardCount(); imagesOut = InlineImages();
     return sw.ElapsedMilliseconds;
    }
    long RevisitMs(string peerId)
    {
     var other = chats.First(c => c.Item1.Id != peerId).Item1.Id;
     SelectConversation(other); Call("Render"); feed.PerformLayout();
     SelectConversation(peerId);
     var sw = Stopwatch.StartNew();
     Call("Render"); feed.PerformLayout();
     return sw.ElapsedMilliseconds;
    }
    long PaintMs()
    {
     var sw = Stopwatch.StartNew();
     using var bmp = new Bitmap(form.Width, form.Height);
     form.DrawToBitmap(bmp, new Rectangle(0, 0, form.Width, form.Height));
     return sw.ElapsedMilliseconds;
    }

    foreach (var (peer, marker, _) in chats)
    {
     int cards, images;
     long first = OpenMs(peer.Id, out cards, out images);
     long revisit = RevisitMs(peer.Id);
     long paint = PaintMs();
     Console.WriteLine($"RESULT|{marker}|{first}|{revisit}|{paint}|{cards}|{images}");
    }

    if (pages)
    {
     // W04 section: repeatedly open the largest chat and time each open once pagination exists.
     SelectConversation(largePeer.Id); Call("Render"); feed.PerformLayout();
     for (int round = 1; round <= 4; round++)
     {
      int cards;
      long ms = OpenMs(largePeer.Id, out cards, out _);
      Console.WriteLine($"PAGE|large|round{round}|{ms}|{cards}");
     }
    }

    Application.ExitThread();
   }
   catch (Exception e) { Console.Error.WriteLine(e); Environment.ExitCode = 1; Application.ExitThread(); }
  };
  Application.Idle += run; Application.Run();

    async Task Wait(Func<bool> condition, string label) { var end = DateTime.UtcNow.AddSeconds(420); int p = 0; while (!condition() && DateTime.UtcNow < end) { if (++p % 25 == 0) File.AppendAllText(diag, $"WAIT {label} p={p} pending={engine.Pending} time={DateTime.UtcNow:HH:mm:ss}\n"); await Task.Delay(100); } if (!condition()) throw new Exception("Timeout: " + label); }
  IEnumerable<Control> AllControls(Control c) { yield return c; foreach (Control child in c.Controls) foreach (var d in AllControls(child)) yield return d; }
 }
 // Isolated seeding probe: measures how fast loopback delivery drains queued messages without
 // any UI involvement, to separate engine throughput from harness measurement noise.
 static void RunProbe(string root)
 {
  var outFile = Path.Combine(root, "probe-out.txt");
  var dir = Path.Combine(root, "probe-" + Guid.NewGuid().ToString("N"));
  void Log(string s) => File.AppendAllText(outFile, s + "\n");
  using var engine = new PeerEngine(Path.Combine(dir, "me"), "Probe me", new TestProtector(dir));
  engine.Start("127.0.0.5", 45992, 45991);
  using var remote = new PeerEngine(Path.Combine(dir, "remote"), "Probe remote", new TestProtector(dir));
  remote.Start("127.0.0.6", 45992, 45991);
  MainAsync().GetAwaiter().GetResult();
  async Task MainAsync()
  {
   Log("addressing...");
   await engine.AddAddress("127.0.0.6:45992"); await remote.AddAddress("127.0.0.5:45992");
   string c = engine.PairingCode(remote.Id); engine.Verify(remote.Id, c); remote.Verify(engine.Id, c);
   Log("paired, queueing 400");
   var sw = Stopwatch.StartNew();
   for (int i = 0; i < 400; i++) remote.Queue(engine.Id, "probe text message " + i);
   Log($"queued 400 in {sw.ElapsedMilliseconds}ms, now waiting for delivery");
   for (int t = 0; t < 30; t++)
   {
    await Task.Delay(5000);
    Log($"t={5 * (t + 1)}s engineTotal={engine.Messages(remote.Id).Length} remotePending={remote.Pending}");
    if (engine.Messages(remote.Id).Length == 400) break;
   }
   Log("probe done");
  }
 }
}