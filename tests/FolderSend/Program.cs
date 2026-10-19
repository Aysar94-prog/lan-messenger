using System.Diagnostics;
using System.IO.Compression;
using System.Reflection;
using System.Security.Cryptography;
using LanMessenger;

class Check
{
    static int passed;
    static void Pass(string name) { passed++; Console.WriteLine("PASS: " + name); }
    static void Assert(bool condition, string message) { if (!condition) throw new Exception(message); }
    static readonly BindingFlags Private = BindingFlags.NonPublic | BindingFlags.Instance;
    sealed class InlineProgress(Action<FolderArchive.Progress> action) : IProgress<FolderArchive.Progress>
    { public void Report(FolderArchive.Progress value) => action(value); }

    [STAThread] static void Main(string[] args)
    {
        if(args.Length>1&&args[1]=="--published-large")
        {
            try{PublishedLargeChecks(args[0]);Console.WriteLine($"PASS={passed} FAIL=0");}
            catch(Exception e){Console.Error.WriteLine(e);Console.WriteLine($"PASS={passed} FAIL=1");Environment.ExitCode=1;}
            return;
        }
        var root = Path.Combine(Path.GetFullPath(args[0]), "folder-send-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        Console.WriteLine("Evidence: " + root);
        try
        {
            ArchiveChecks(root);
            UiChecks(root);
            Console.WriteLine($"PASS={passed} FAIL=0");
        }
        catch (Exception e) { Console.Error.WriteLine(e); Console.WriteLine($"PASS={passed} FAIL=1"); Environment.ExitCode = 1; }
    }

    // Exercises the helper inside the actual app assembly, rather than its source-linked
    // test copy. Reuse a previously generated fixture so a release gate need not recreate it.
    static void PublishedLargeChecks(string fixture)
    {
        var archiveType=Assembly.Load("LanMessenger").GetType("LanMessenger.FolderArchive")!;
        var create=archiveType.GetMethod("Create",BindingFlags.Static|BindingFlags.NonPublic)!;
        var cache=Path.Combine(Path.GetDirectoryName(fixture)!,"published-archives-"+Guid.NewGuid().ToString("N"));
        foreach(var limit in new[]{(long)PeerEngine.MaxFileSize,PeerEngine.MaxFastFileSize})
        {
            var clock=Stopwatch.StartNew();
            using var draft=(IDisposable)create.Invoke(null,new object?[]{fixture,cache,limit,CancellationToken.None,null})!;
            var path=(string)draft.GetType().GetProperty("Path",Private)!.GetValue(draft)!;
            using var zip=ZipFile.OpenRead(path);
            Assert(zip.Entries.Count==65540&&zip.GetEntry("many/65535")!=null&&zip.GetEntry("many/nested/empty/")!=null,"Published ZIP64 lost entries");
            var target=Path.Combine(cache,"extracted-"+limit);Directory.CreateDirectory(target);
            zip.GetEntry("many/nested/sample.txt")!.ExtractToFile(Path.Combine(target,"sample.txt"));
            Assert(File.ReadAllText(Path.Combine(target,"sample.txt"))=="ZIP64 content שלום","Published ZIP extraction content differs");
            Console.WriteLine($"INFO: exact app ZIP64 {clock.ElapsedMilliseconds}ms, peak {Process.GetCurrentProcess().PeakWorkingSet64/1024/1024}MiB");
            Pass(limit==PeerEngine.MaxFileSize?"Published File accepts 65,540 ZIP64 entries and extracts content":"Published Fast File accepts 65,540 ZIP64 entries and extracts content");
        }
    }

    static void ArchiveChecks(string root)
    {
        var source = Path.Combine(root, "Documents-שלום");
        Directory.CreateDirectory(Path.Combine(source, "nested", "empty"));
        File.WriteAllText(Path.Combine(source, "nested", "résumé.txt"), "Hello שלום\n");
        File.WriteAllBytes(Path.Combine(source, "zero.bin"), []);
        var data = RandomNumberGenerator.GetBytes(2*1024*1024);
        File.WriteAllBytes(Path.Combine(source, "random.bin"), data);
        var cache = Path.Combine(root, "archives");
        using (var draft = FolderArchive.Create(source, cache, PeerEngine.MaxFileSize))
        {
            Assert(draft.Name == "Documents-שלום.zip", "Archive display name");
            using var zip = ZipFile.OpenRead(draft.Path);
            var names = zip.Entries.Select(e => e.FullName).Order().ToArray();
            Assert(names.SequenceEqual(new[]{"Documents-שלום/", "Documents-שלום/nested/", "Documents-שלום/nested/empty/", "Documents-שלום/nested/résumé.txt", "Documents-שלום/random.bin", "Documents-שלום/zero.bin"}.Order()), "Names/empty dirs changed");
            var extracted = Path.Combine(root, "extracted");
            zip.ExtractToDirectory(extracted);
            Assert(File.ReadAllBytes(Path.Combine(extracted, "Documents-שלום", "random.bin")).SequenceEqual(data), "Binary bytes changed");
            Assert(File.ReadAllText(Path.Combine(extracted, "Documents-שלום", "nested", "résumé.txt")) == "Hello שלום\n", "Unicode content changed");
            Pass("ZIP preserves root, nested and empty directories, Unicode names, zero-byte and binary files");
        }
        Assert(!Directory.GetDirectories(cache).Any(), "Disposed ZIP leaked");
        Pass("Draft disposal removes temporary ZIP and lease");

        var longName=new string('x',125);var longFolder=Path.Combine(root,longName);Directory.CreateDirectory(longFolder);
        using(var draft=FolderArchive.Create(longFolder,cache,PeerEngine.MaxFileSize))
        using(var zip=ZipFile.OpenRead(draft.Path))
            Assert(draft.Name.Length<=120&&draft.Name.EndsWith(".zip")&&zip.Entries.Single().FullName==longName+"/","Long folder name lost ZIP extension or original root");
        Pass("Long folder names preserve ZIP extension within attachment metadata limit");

        void Fails(Action action, string label)
        {
            try { action(); throw new Exception("Expected failure: " + label); }
            catch (IOException) { }
            Assert(!Directory.GetDirectories(cache).Any(), "Partial ZIP leaked: " + label);
            Pass(label);
        }
        Fails(() => FolderArchive.Create(source, cache, 32).Dispose(), "Uncompressed folder limit rejects with no partial draft");
        var empty = Path.Combine(root, "empty"); Directory.CreateDirectory(empty);
        Fails(() => FolderArchive.Create(empty, cache, 10).Dispose(), "ZIP headers/central directory count toward output limit");
        Fails(() => FolderArchive.Create(root, cache, PeerEngine.MaxFileSize).Dispose(), "Folder containing own staging cache is rejected");
        Fails(() => FolderArchive.Create(Path.Combine(root,"missing"), cache, PeerEngine.MaxFileSize).Dispose(), "Unreadable/missing source fails explicitly");

        using (var cancel = new CancellationTokenSource())
        {
            try
            {
                FolderArchive.Create(source, cache, PeerEngine.MaxFileSize, cancel.Token,
                    new InlineProgress(p => { if(p.Entries > 0 && p.Bytes == 0) cancel.Cancel(); })).Dispose();
                throw new Exception("Cancellation succeeded as a draft");
            }
            catch (OperationCanceledException) { }
            Assert(!Directory.GetDirectories(cache).Any(), "Cancelled ZIP leaked");
            Pass("Cancellation after scan removes partial archive");
        }
        bool changed = false;
        Fails(() => FolderArchive.Create(source, cache, PeerEngine.MaxFileSize, progress: new InlineProgress(p => {
            if (!changed && p.Entries > 0 && p.Bytes == 0) { changed = true; File.AppendAllText(Path.Combine(source,"zero.bin"), "changed"); }
        })).Dispose(), "File changed after scan fails instead of sending stale content");
        File.WriteAllBytes(Path.Combine(source, "zero.bin"), []);
        bool directoryChanged = false;
        Fails(() => FolderArchive.Create(source, cache, PeerEngine.MaxFileSize, progress: new InlineProgress(p => {
            if (!directoryChanged && p.Entries > 0 && p.Bytes == 0) { directoryChanged = true; File.WriteAllText(Path.Combine(source,"new.txt"), "new"); }
        })).Dispose(), "Directory membership change is detected");
        File.Delete(Path.Combine(source,"new.txt"));
        var deep = Path.Combine(root, "deep"); Directory.CreateDirectory(deep);
        string next = deep;
        for (int i=0;i<FolderArchive.MaxDepth+1;i++) { next=Path.Combine(next,"d");Directory.CreateDirectory(next); }
        Fails(() => FolderArchive.Create(deep, cache, PeerEngine.MaxFileSize).Dispose(), "Excessive nesting is bounded");
        var many = Path.Combine(root, "many"); Directory.CreateDirectory(many);
        var largeClock=Stopwatch.StartNew();
        Console.WriteLine("INFO: creating >10,000-entry fixture");
        for (int i=0;i<10001;i++) File.WriteAllBytes(Path.Combine(many,i.ToString()), []);
        using(var draft=FolderArchive.Create(many,cache,PeerEngine.MaxFileSize))
        using(var zip=ZipFile.OpenRead(draft.Path))Assert(zip.Entries.Count==10002,"Large folder count changed");
        Pass("File accepts more than 10,000 entries without truncation");
        Console.WriteLine("INFO: expanding fixture past ZIP64 65,535-entry boundary");
        for(int i=10001;i<65536;i++)File.WriteAllBytes(Path.Combine(many,i.ToString()),[]);
        Directory.CreateDirectory(Path.Combine(many,"nested","empty"));
        File.WriteAllText(Path.Combine(many,"nested","sample.txt"),"ZIP64 content שלום");
        foreach(var limit in new[]{(long)PeerEngine.MaxFileSize,PeerEngine.MaxFastFileSize})
        {
            var archiveClock=Stopwatch.StartNew();
            using var draft=FolderArchive.Create(many,cache,limit);
            using var zip=ZipFile.OpenRead(draft.Path);
            Assert(zip.Entries.Count==65540&&zip.Entries.Select(e=>e.FullName).Distinct().Count()==65540,"ZIP64 entry count/truncation");
            Assert(zip.GetEntry("many/65535")!=null&&zip.GetEntry("many/nested/empty/")!=null,"ZIP64 last entry or empty folder missing");
            using var reader=new StreamReader(zip.GetEntry("many/nested/sample.txt")!.Open());
            Assert(reader.ReadToEnd()=="ZIP64 content שלום","ZIP64 data differs");
            Console.WriteLine($"INFO: ZIP64 {zip.Entries.Count} entries, {archiveClock.ElapsedMilliseconds}ms, peak process working set {Process.GetCurrentProcess().PeakWorkingSet64/1024/1024}MiB");
            Pass(limit==PeerEngine.MaxFileSize?"File ZIP64 preserves all >65,535 entries and nested content":"Fast File ZIP64 preserves all >65,535 entries and nested content");
        }
        using(var cancel=new CancellationTokenSource())
        {
            try{FolderArchive.Create(many,cache,PeerEngine.MaxFileSize,cancel.Token,new InlineProgress(p=>{if(p.Entries>=1000)cancel.Cancel();})).Dispose();throw new Exception("Large-folder cancellation ignored");}
            catch(OperationCanceledException){}
            Assert(!Directory.GetDirectories(cache).Any(),"Large cancelled archive leaked");
            Pass("Large-folder cancellation cleans temporary archive");
        }
        Console.WriteLine($"INFO: large-folder fixture and checks total {largeClock.ElapsedMilliseconds}ms");

        var live = FolderArchive.Create(source, cache, PeerEngine.MaxFileSize);
        var heldPath = live.Path;
        var hold = live.KeepAlive();
        Directory.SetLastWriteTimeUtc(Path.GetDirectoryName(heldPath)!, DateTime.UtcNow.AddDays(-2));
        FolderArchive.CleanupAbandoned(cache);
        Assert(File.Exists(heldPath), "Cleanup deleted locked active archive");
        live.Dispose(); live.Dispose();
        Assert(File.Exists(heldPath), "Draft removed archive while import owns it");
        hold.Dispose(); hold.Dispose();
        Assert(!File.Exists(heldPath), "Final import lease did not clean up");
        Pass("Active archive lock and import lease protect remove/switch, with idempotent cleanup");

        var abandoned = Path.Combine(cache, Guid.NewGuid().ToString("N")); Directory.CreateDirectory(abandoned);
        File.WriteAllBytes(Path.Combine(abandoned,"archive.zip"), [1,2]);File.WriteAllBytes(Path.Combine(abandoned,"lease"), []);
        Directory.SetLastWriteTimeUtc(abandoned, DateTime.UtcNow.AddDays(-2));
        FolderArchive.CleanupAbandoned(cache);
        Assert(!Directory.Exists(abandoned), "Abandoned archive survived cleanup");
        Pass("Startup recovery removes old abandoned archive");

        var link = Path.Combine(source,"linked"); var target = Path.Combine(root,"link-target"); Directory.CreateDirectory(target);
        File.WriteAllText(Path.Combine(target,"outside.txt"),"not sent");
        var info=new ProcessStartInfo("cmd.exe") {UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true};
        info.ArgumentList.Add("/c");info.ArgumentList.Add("mklink");info.ArgumentList.Add("/J");info.ArgumentList.Add(link);info.ArgumentList.Add(target);
        using(var process=Process.Start(info)!) { process.WaitForExit();Assert(process.ExitCode==0,"Could not create junction fixture: "+process.StandardError.ReadToEnd()); }
        using(var draft=FolderArchive.Create(source,cache,PeerEngine.MaxFileSize))
        using(var zip=ZipFile.OpenRead(draft.Path))
            Assert(draft.SkippedLinks==1&&!zip.Entries.Any(e=>e.FullName.Contains("linked")),"Junction followed or omission not reported");
        Fails(()=>FolderArchive.Create(link,cache,PeerEngine.MaxFileSize).Dispose(),"Root junction is rejected");
        Pass("Nested junctions are omitted with an explicit omission count");
    }

    static void UiChecks(string root)
    {
        ApplicationConfiguration.Initialize();
        var dataRoot=Path.Combine(root,"sender");Directory.CreateDirectory(dataRoot);
        File.WriteAllText(Path.Combine(dataRoot,"network-preference.txt"),"Offline");
        var type=Assembly.Load("LanMessenger").GetType("LanMessenger.ChatWindow")!;
        using var form=(Form)Activator.CreateInstance(type,new object?[]{dataRoot,new TestProtector(dataRoot)})!;
        object? Field(string name)=>type.GetField(name,Private)!.GetValue(form);
        void Set(string name,object? value)=>type.GetField(name,Private)!.SetValue(form,value);
        object? Call(string name,params object?[] values)=>type.GetMethod(name,Private)!.Invoke(form,values);
        Task CallAsync(string name,params object?[] values)=>(Task)Call(name,values)!;
        var engine=(PeerEngine)Field("engine")!;
        var peerRoot=Path.Combine(root,"receiver");using var peer=new PeerEngine(peerRoot,"Receiver",new TestProtector(peerRoot));
        peer.Start("127.0.0.26",46792,46791);
        form.ShowInTaskbar=false;form.Opacity=0;form.Show();
        EventHandler? run=null;run=async(_,_)=>{
            Application.Idle-=run;
            try
            {
                engine.Start("127.0.0.25",46792,46791);
                await engine.AddAddress("127.0.0.26:46792");await peer.AddAddress("127.0.0.25:46792");
                Call("RestoreWindow",peer.Id);Call("Render");
                bool pickerCalled=false;Set("attachmentPicker",(Func<bool,string?>)(_=>{pickerCalled=true;return null;}));
                await CallAsync("PickAttachment",false);
                Assert(!pickerCalled&&Field("pendingAttachmentPath")==null,"Unverified contact selected a folder");
                Pass("Unverified Windows contact cannot invoke folder picker");
                var code=engine.PairingCode(peer.Id);engine.Verify(peer.Id,code);peer.Verify(engine.Id,code);Call("Render");
                Assert(type.GetField("attachmentMenu",Private)==null,"Separate Folder action remains");
                var pickerType=type.Assembly.GetType("LanMessenger.AttachmentPicker")!;
                async Task CheckPicker(string selection,bool folder)
                {
                    using var picker=(Form)Activator.CreateInstance(pickerType,BindingFlags.Instance|BindingFlags.Public|BindingFlags.NonPublic,null,new object[]{"Choose file or folder",Path.Combine(root,"Documents-שלום")},null)!;
                    picker.ShowInTaskbar=false;picker.Opacity=0;picker.Show(form);
                    var list=(ListView)pickerType.GetField("entries",Private)!.GetValue(picker)!;
                    await Wait(()=>list.Enabled&&list.VirtualListSize>1,"Combined picker lists files and folders");
                    var index=Enumerable.Range(0,list.VirtualListSize).Single(i=>list.Items[i].Text==selection);list.SelectedIndices.Add(index);
                    var select=(Button)pickerType.GetField("select",Private)!.GetValue(picker)!;
                    Assert(select.Enabled,"Shared Select button disabled");
                    using(var shot=new Bitmap(picker.Width,picker.Height)){picker.DrawToBitmap(shot,new Rectangle(Point.Empty,picker.Size));shot.Save(Path.Combine(root,folder?"combined-picker-folder.png":"combined-picker-file.png"));}
                    select.PerformClick();
                    var result=(string)pickerType.GetProperty("SelectedPath",BindingFlags.Instance|BindingFlags.NonPublic)!.GetValue(picker)!;
                    Assert(folder?Directory.Exists(result):File.Exists(result),"Combined picker returned wrong kind");
                    Pass(folder?"Same Select action accepts a folder":"Same Select action accepts an ordinary file");
                }
                await CheckPicker("nested",true);await CheckPicker("random.bin",false);
                using(var largePicker=(Form)Activator.CreateInstance(pickerType,BindingFlags.Instance|BindingFlags.Public|BindingFlags.NonPublic,null,new object[]{"Large-folder picker",Path.Combine(root,"many")},null)!)
                {
                    largePicker.ShowInTaskbar=false;largePicker.Opacity=0;largePicker.Show(form);
                    var list=(ListView)pickerType.GetField("entries",Private)!.GetValue(largePicker)!;
                    await Wait(()=>list.Enabled&&list.VirtualListSize==65538,"Picker accepts >20,000 children through virtual rows");
                    Assert(list.VirtualMode,"Large picker allocates native rows for entire directory");
                    list.SelectedIndices.Add(0);
                    ((Button)pickerType.GetField("select",Private)!.GetValue(largePicker)!).PerformClick();
                    Assert((string?)pickerType.GetProperty("SelectedPath",BindingFlags.Instance|BindingFlags.NonPublic)!.GetValue(largePicker)==Path.Combine(root,"many"),"Current large folder cannot be selected");
                    Pass("Current large folder remains selectable without a new Folder action");
                }

                var source=Path.Combine(root,"Documents-שלום");
                var normal=Path.Combine(root,"normal.txt");File.WriteAllText(normal,"unchanged");
                Set("pendingAttachmentPath",normal);Set("pendingAttachmentName","normal.txt");Set("pendingAttachmentTarget",peer.Id);
                Call("RenderPendingAttachment");
                await CallAsync("PickAttachment",false);
                Assert(pickerCalled&&(string?)Field("pendingAttachmentPath")==normal,"Picker cancellation discarded existing file");
                Pass("Cancelling shared picker preserves existing ordinary-file draft");
                foreach(bool fast in new[]{false,true})
                {
                    bool? chosenMode=null;
                    Set("attachmentPicker",(Func<bool,string?>)(mode=>{chosenMode=mode;return normal;}));
                    await CallAsync("PickAttachment",fast);
                    Assert(chosenMode==fast&&(string?)Field("pendingAttachmentPath")==normal&&(bool)Field("pendingFast")! == fast&&Field("pendingFolderArchive")==null,"Ordinary-file behavior/mode changed");
                    Call("ClearPendingAttachment");Call("Render");
                    Assert(File.Exists(normal),"Ordinary source deleted on Remove");
                    Pass($"{(fast?"Fast file":"File")} ordinary selection keeps original path and mode");
                    Set("attachmentPicker",(Func<bool,string?>)(_=>source));
                    var cancelled=CallAsync("PickAttachment",fast);
                    ((Control)Field("attachmentDraft")!).Controls.OfType<Button>().Single(b=>b.Text=="Cancel").PerformClick();
                    await cancelled;
                    Assert(Field("pendingAttachmentPath")==null&&Field("folderPreparation")==null,"Cancelled preparation returned stale draft");
                    Assert(!Directory.GetDirectories((string)Field("folderDraftRoot")!).Any(),"Cancelled preparation leaked");
                    Pass($"{(fast?"Fast file":"File")} folder preparation cancellation cleans and cannot repopulate draft");
                }
                Set("attachmentPicker",(Func<bool,string?>)(_=>source));
                var prepare=CallAsync("PickAttachment",false);
                Assert(Field("folderPreparation")!=null&&!((Button)Field("send")!).Enabled,"Preparation not visible/send enabled");
                var panel=(Control)Field("attachmentDraft")!;
                Assert(panel.Controls.OfType<Button>().Any(b=>b.Text=="Cancel"),"No preparation Cancel action");
                await prepare;
                var zipPath=(string)Field("pendingAttachmentPath")!;
                Assert(zipPath.EndsWith("archive.zip")&&File.Exists(zipPath)&&!(bool)Field("pendingFast")!,"Folder draft missing or Fast enabled");
                Assert(engine.Messages(peer.Id).Length==0,"Folder selection sent before Send");
                Assert(TextIn(panel).Contains("receiver saves and extracts"),"Folder draft does not explain ZIP");
                using(var shot=new Bitmap(form.Width,form.Height)){form.DrawToBitmap(shot,new Rectangle(Point.Empty,form.Size));shot.Save(Path.Combine(root,"folder-draft.png"));}
                var bytes=File.ReadAllBytes(zipPath);
                var failedQueue=(Task)typeof(PeerEngine).GetMethod("QueueFastFolderArchiveAsync",Private)!.Invoke(engine,new object[]{Guid.NewGuid().ToString(),"",zipPath,"test.zip"})!;
                try { await failedQueue; throw new Exception("Invalid conversation queue succeeded"); }
                catch(IOException) { }
                Assert(File.Exists(zipPath)&&(string?)Field("pendingAttachmentPath")==zipPath,"Queue failure discarded source draft");
                Pass("Queue failure retains folder ZIP for retry");
                Pass("Background folder preparation shows Cancel and stages one ZIP for explicit Send");

                // A successful queue owns an encrypted copy even after the ZIP draft disappears.
                engine.GoOffline();Call("Render");
                ((TextBox)Field("composer")!).Text="Folder caption";
                await CallAsync("Send");
                var queued=engine.Messages(peer.Id).Single();
                Assert(queued.FileName=="Documents-שלום.zip"&&queued.Text=="Folder caption"&&queued.Status=="Queued","Queued folder metadata");
                Assert(!File.Exists(zipPath)&&engine.ReadAttachment(queued).SequenceEqual(bytes),"ZIP copy or cleanup after queue failed");
                Pass("Offline Send durably queues encrypted ZIP with caption and removes plaintext draft");
                // Fast-file folder ZIP must become a durable RAW source without silently using TLS.
                Set("attachmentPicker",(Func<bool,string?>)(_=>source));
                await CallAsync("PickAttachment",true);
                var fastDraft=(string)Field("pendingAttachmentPath")!;
                Assert((bool)Field("pendingFast")!&&TextIn((Control)Field("attachmentDraft")!).Contains("unencrypted"),"Fast folder mode/warning lost");
                ((TextBox)Field("composer")!).Text="Fast folder caption";await CallAsync("Send");
                var fastQueued=engine.Messages(peer.Id).Single(m=>m.Text=="Fast folder caption");
                var managed=engine.SavedDestination(fastQueued);
                Assert(!File.Exists(fastDraft)&&managed.EndsWith(".sec.folder.zip")&&File.Exists(managed),"Fast ZIP source not retained independently");
                Assert(engine.ReadAttachment(fastQueued).SequenceEqual(File.ReadAllBytes(managed)),"Fast ZIP source unreadable");
                var isFast=typeof(PeerEngine).GetMethod("IsFastAttachment",Private)!;
                Assert((bool)isFast.Invoke(engine,new object[]{fastQueued})!&&!(bool)isFast.Invoke(engine,new object[]{queued})!,"Selected transfer modes switched");
                Pass("Fast folder uses Fast source-reference path, warning and durable generated ZIP");
                Set("attachmentPicker",(Func<bool,string?>)(_=>normal));await CallAsync("PickAttachment",true);
                ((TextBox)Field("composer")!).Text="Ordinary Fast file";await CallAsync("Send");
                var ordinaryFast=engine.Messages(peer.Id).Single(m=>m.Text=="Ordinary Fast file");
                Assert(engine.SavedDestination(ordinaryFast)==normal,"Ordinary Fast file source was copied or changed");
                Pass("Ordinary Fast File still references original user file");
                Set("attachmentPicker",(Func<bool,string?>)(_=>normal));await CallAsync("PickAttachment",false);
                ((TextBox)Field("composer")!).Text="Ordinary File";await CallAsync("Send");
                var ordinaryFile=engine.Messages(peer.Id).Single(m=>m.Text=="Ordinary File");
                Assert(engine.ReadAttachment(ordinaryFile).SequenceEqual(File.ReadAllBytes(normal))&&!(bool)isFast.Invoke(engine,new object[]{ordinaryFile})!,"Ordinary File changed pipeline");
                Pass("Ordinary File still owns an encrypted stored copy");
                // Switching chats cancels a pending preparation and removes its plaintext draft.
                var switching=CallAsync("PickAttachment",true);
                ((ListBox)Field("contacts")!).SelectedIndex=-1;await switching;
                Assert(Field("pendingAttachmentPath")==null&&Field("folderPreparation")==null,"Switching conversation left draft");
                Pass("Conversation switch clears attachment state");
                Set("exiting",true);form.Close();
                var orphan=Path.Combine(dataRoot,"attachments",engine.Id+"-"+Guid.NewGuid()+".sec.folder.zip");
                File.WriteAllBytes(orphan,[1,2,3]);
                using var resumed=new PeerEngine(dataRoot,"Resumed",new TestProtector(dataRoot));
                Assert(!File.Exists(orphan)&&File.Exists(managed),"Startup orphan cleanup lost retained ZIP or kept orphan");
                Pass("Startup removes orphaned generated ZIP while preserving retained Fast source");
                var saved=resumed.Messages(peer.Id).Single(m=>m.Id==queued.Id);
                Assert(resumed.ReadAttachment(saved).SequenceEqual(bytes),"Restart lost queued folder bytes");
                resumed.Start("127.0.0.25",46792,46791);
                await resumed.AddAddress("127.0.0.26:46792");
                await Wait(()=>peer.Messages(resumed.Id).Any(m=>m.Id==saved.Id),"Restart delivers queued ZIP offer");
                var received=peer.Messages(resumed.Id).Single(m=>m.Id==saved.Id);
                var destination=Path.Combine(root,"received.zip");await peer.DownloadToAsync(received,destination).WaitAsync(TimeSpan.FromSeconds(45));
                Assert(!(bool)isFast.Invoke(peer,new object[]{received})!,"Normal folder used Fast payload");
                Assert(File.ReadAllBytes(destination).SequenceEqual(bytes),"Received ZIP differs");
                Assert(received.FileHash==Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant(),"Wire hash differs");
                ZipFile.ExtractToDirectory(destination,Path.Combine(root,"received-extracted"));
                Assert(Directory.Exists(Path.Combine(root,"received-extracted","Documents-שלום","nested","empty")),"Receiver lost empty directory");
                Pass("Windows-to-Windows encrypted transfer after sender restart preserves ZIP/hash and extracts correctly");
                var fastSaved=resumed.Messages(peer.Id).Single(m=>m.Id==fastQueued.Id);
                Assert(resumed.SavedDestination(fastSaved)==managed&&File.Exists(managed),"Restart lost managed Fast ZIP");
                await Wait(()=>peer.Messages(resumed.Id).Any(m=>m.Id==fastSaved.Id),"Restart delivers queued Fast ZIP offer");
                var fastReceived=peer.Messages(resumed.Id).Single(m=>m.Id==fastSaved.Id);
                var fastDestination=Path.Combine(root,"received-fast.zip");
                bool cancelledDownload=false;
                void CancelAtBlock(string id,long done,long total) { if(id==fastReceived.Id&&done>=256*1024&&!cancelledDownload){cancelledDownload=true;peer.CancelDownload(fastReceived);} }
                peer.TransferProgress+=CancelAtBlock;
                try { await peer.DownloadToAsync(fastReceived,fastDestination).WaitAsync(TimeSpan.FromSeconds(45)); throw new Exception("Expected partial Fast download cancellation"); }
                catch(OperationCanceledException) { }
                finally { peer.TransferProgress-=CancelAtBlock; }
                var partialLength=new FileInfo(fastDestination).Length;
                Assert(cancelledDownload&&partialLength>0&&partialLength<fastReceived.FileSize,"No real partial download");
                peer.GoOffline();
                using var receiverAgain=new PeerEngine(peerRoot,"Receiver resumed",new TestProtector(peerRoot));
                receiverAgain.Start("127.0.0.26",46792,46791);
                var resumeMessage=receiverAgain.Messages(resumed.Id).Single(m=>m.Id==fastReceived.Id);
                Assert(receiverAgain.PendingDestination(resumeMessage)==fastDestination,"Resume destination lost at restart");
                long firstProgress=-1;
                receiverAgain.TransferProgress+=(id,done,total)=>{if(id==resumeMessage.Id&&firstProgress<0)firstProgress=done;};
                await receiverAgain.DownloadToAsync(resumeMessage,fastDestination).WaitAsync(TimeSpan.FromSeconds(45));
                Assert(firstProgress==partialLength&&firstProgress>=256*1024,"Resume restarted from zero");
                Assert(File.ReadAllBytes(fastDestination).SequenceEqual(File.ReadAllBytes(managed)),"Fast ZIP received bytes differ");
                Assert((bool)isFast.Invoke(receiverAgain,new object[]{resumeMessage})!,"Fast ZIP payload did not use RAW path");
                ZipFile.ExtractToDirectory(fastDestination,Path.Combine(root,"received-fast-extracted"));
                Pass("Fast folder RAW download cancels after a block, resumes after receiver restart and preserves ZIP/hash");
                var ordinarySaved=resumed.Messages(peer.Id).Single(m=>m.Id==ordinaryFast.Id);
                Assert(resumed.SavedDestination(ordinarySaved)==normal,"Restart changed ordinary Fast reference");
                resumed.ClearConversation(peer.Id);
                Assert(!File.Exists(managed)&&File.Exists(normal),"Clearing chat deleted ordinary source or leaked owned ZIP");
                Pass("Clearing transfer deletes generated Fast ZIP and preserves ordinary external file");
            }
            catch(Exception e){Console.Error.WriteLine(e);Environment.ExitCode=1;}
            finally{if(!form.IsDisposed){Set("exiting",true);form.Close();}Application.ExitThread();}
        };
        Application.Idle+=run;Application.Run();
        if(Environment.ExitCode!=0)throw new Exception("Windows folder UI/transfer checks failed");
    }
    static string TextIn(Control control)=>control.Text+string.Concat(control.Controls.Cast<Control>().Select(TextIn));
    static async Task Wait(Func<bool> condition,string label)
    {
        var end=DateTime.UtcNow.AddSeconds(30);
        while(!condition()&&DateTime.UtcNow<end)await Task.Delay(100);
        Assert(condition(),label);Pass(label);
    }
}
