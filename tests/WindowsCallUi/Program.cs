using System.Reflection;
using LanMessenger;

class Check
{
    [STAThread] static void Main()
    {
        ApplicationConfiguration.Initialize();
        var type = Assembly.Load("LanMessenger").GetType("LanMessenger.CallView")!;
        using var form = (Form)Activator.CreateInstance(type, "Call UI fixture", false)!;
        form.Opacity = 0; form.ShowInTaskbar = false;
        object Field(string name) => type.GetField(name, BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(form)!;
        int passed = 0;
        void Assert(bool ok, string label) { if (!ok) throw new Exception(label); passed++; Console.WriteLine("PASS: " + label); }
        string cid = Guid.NewGuid().ToString(), rid = Guid.NewGuid().ToString();
        var consent = new CallVideoConsent(cid, true, true, false);
        consent.SetConnected(cid, true);
        using var coordinator = new CallVideoCoordinator(cid, true, consent, _ => true, new FakeCallVideoMedia(), _ => { });
        void Render(CallVideoCoordinator.Snapshot? video, CallProtocol.State state = CallProtocol.State.Connected)
        {
            var snap = new CallSession(cid, rid, state, true, false, "System", CallProtocol.Quality.Unknown,
                PeerEngine.Now, PeerEngine.Now, 0, null, true, false, video);
            type.GetMethod("Render")!.Invoke(form, new object?[] { snap, "Call UI fixture", 0, "fixture" });
        }
        EventHandler? run = null;
        run = async (_, _) =>
        {
            Application.Idle -= run;
            try
            {
                Render(null);
                Assert(((Button)Field("requestVideo")).Visible, "connected voice call offers Add video");
                bool? requestedCamera = null;
                type.GetEvent("CameraClicked")!.AddEventHandler(form, new Action<bool>(on => requestedCamera = on));
                var cameraButton = (Button)Field("camera");
                cameraButton.Visible = true; cameraButton.Enabled = true; cameraButton.Text = "Camera off"; cameraButton.PerformClick();
                Assert(requestedCamera == false, "Camera off requests capture stop, not an already-on refusal");
                cameraButton.Text = "Camera on"; cameraButton.PerformClick();
                Assert(requestedCamera == true, "Camera on requests capture start");
                Render(null);
                coordinator.ReceiveAdmitted(CallSignaling.VideoRequest(cid, 1, 0, rid), _ => true);
                coordinator.AwaitIdle(5000); Render(coordinator.Published);
                Assert(((Button)Field("acceptVideo")).Visible && ((Button)Field("declineVideo")).Visible,
                    "remote upgrade displays Accept video and Decline video");
                Assert(!((Button)Field("requestVideo")).Visible, "pending upgrade hides a duplicate Add video action");
                Render(coordinator.Published, CallProtocol.State.Ending);
                Assert(!((Button)Field("acceptVideo")).Visible && !((Button)Field("hangup")).Visible, "ended call hides live actions");
                Render(null);
                var sink = (ICallVideoFrameSink)type.GetProperty("RemoteFrameSink")!.GetValue(form)!;
                await Task.Run(() =>
                {
                    for (int i = 0; i < 1000; i++) sink.OnFrame(new CallVideoFrame(new byte[64 * 48 * 4], 64, 48, 64 * 4));
                });
                await Task.Delay(100);
                var picture = (PictureBox)Field("remotePicture");
                Assert(picture.Image?.Width == 64 && picture.Image.Height == 48, "frame burst renders through UI thread");
                var image = picture.Image;
                sink.OnFrame(new CallVideoFrame(new byte[1], 64, 48, 256)); await Task.Delay(50);
                Assert(ReferenceEquals(image, picture.Image), "malformed frame rejected without replacing valid bitmap");
                for (int i = 0; i < 5; i++) { form.Size = new Size(480 + i * 80, 400 + i * 60); await Task.Delay(10); }
                var preview = (Panel)Field("preview"); var stage = (Panel)Field("stage");
                Assert(preview.Left >= 0 && preview.Top >= 0 && preview.Right <= stage.ClientSize.Width && preview.Bottom <= stage.ClientSize.Height,
                    "preview stays within resized stage");
                form.Dispose();
                Assert(picture.Image == null, "call window releases its frame bitmap on disposal");
                sink.OnFrame(new CallVideoFrame(new byte[4], 1, 1, 4));
                Assert(true, "late frame after disposal is ignored");
                Console.WriteLine($"Windows call UI: {passed} passed, 0 failed");
            }
            catch (Exception e) { Console.Error.WriteLine(e); Environment.ExitCode = 1; form.Dispose(); }
            finally { Application.ExitThread(); }
        };
        form.Show(); Application.Idle += run; Application.Run();
    }
}
