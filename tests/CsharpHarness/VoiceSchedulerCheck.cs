using System.Reflection;
using System.Threading;
using LanMessenger;

// Voice Messages Windows, WT04 remainder: the automatic-media scheduler's "3 consecutive voice
// admissions then force an image" fairness rule (Transfers.cs QueueAutomaticMedia, rule 4).
// tests/voice_scheduler.py already proves the scheduler works end to end over a real network
// (WT04, partial); asserting the *exact admission ordering* there would be timing-fragile
// against real async downloads. Sidestepped here by never calling PeerEngine.Start() at all —
// no real networking, no TimerLoop, nothing but a single direct, synchronous, reflection-driven
// call to the private QueueAutomaticMedia() method against hand-seeded state. This makes the
// fairness decision itself fully deterministic: DownloadAttachmentAsync is still kicked off for
// whatever gets admitted (against a fake, unreachable peer), but it fails asynchronously in the
// background after the synchronous admission decision we're actually checking has already
// happened, and its own failure path is caught internally and cannot affect the assertions.
// Invoked via `CsharpHarness --voice-scheduler-check`.
static class VoiceSchedulerCheck
{
    public static int Run()
    {
        int pass = 0, fail = 0;
        void Check(bool ok, string label) { if (ok) { pass++; Console.WriteLine("PASS: " + label); } else { fail++; Console.WriteLine("FAIL: " + label); } }

        var root = Path.Combine(Path.GetTempPath(), "voice-scheduler-check-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        var engine = new PeerEngine(root, "SchedulerCheck", new TestProtector(root));
        var type = typeof(PeerEngine);
        void SetField(string name, object value) => type.GetField(name, BindingFlags.NonPublic | BindingFlags.Instance)!.SetValue(engine, value);
        object GetField(string name) => type.GetField(name, BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(engine)!;
        void QueueAutomaticMedia() => type.GetMethod("QueueAutomaticMedia", BindingFlags.NonPublic | BindingFlags.Instance)!.Invoke(engine, null);

        SetField("running", true); // skip Start(): no real socket, no TimerLoop -- only our own direct calls ever run the scheduler.
        var peerId = Guid.NewGuid().ToString();
        var peers = (System.Collections.IDictionary)GetField("peers");
        var peerType = typeof(PeerEngine).GetNestedType("Peer")!;
        var peer = Activator.CreateInstance(peerType, peerId, "Peer1", "127.0.0.1", 1, PeerEngine.Now, "F", "F", "", "", "")!;
        peers[peerId] = peer;

        var messages = (System.Collections.IList)GetField("messages");
        PeerEngine.Message MakeMessage(string id, string fileName) => new(id, peerId, engine.Id, "", PeerEngine.Now, "Received", "", fileName, 1000, new string('a', 64));
        var voiceMessages = Enumerable.Range(0, 5).Select(i => MakeMessage(Guid.NewGuid().ToString(), "voice-" + Guid.NewGuid().ToString() + ".lanvoice.wav")).ToArray();
        var imageMessage = MakeMessage(Guid.NewGuid().ToString(), "photo.png");
        foreach (var m in voiceMessages) messages.Add(m);
        messages.Add(imageMessage);

        var imageAttempts = (System.Collections.IDictionary)GetField("imageAttempts");
        var voiceAttempts = (System.Collections.IDictionary)GetField("voiceAttempts");
        bool ImageAdmitted() => imageAttempts.Contains(imageMessage.From + "/" + imageMessage.Id);
        int VoicesAdmitted() => voiceMessages.Count(m => voiceAttempts.Contains(m.From + "/" + m.Id));

        // Baseline (rule 2/3): with the fairness counter at 0, voice is preferred by default —
        // both free slots go to voice candidates; the image is not touched this call.
        SetField("consecutiveAutoVoiceAdmissions", 0);
        QueueAutomaticMedia();
        Check(VoicesAdmitted() == 2 && !ImageAdmitted(), "with the fairness counter at 0, both free slots admit voice candidates and the image waits");

        // Reset admission state (a fresh scheduler run, same candidates still pending/undownloaded).
        // Each admission kicked off a real DownloadAttachmentAsync against the fake, unreachable
        // peer, which retries forever on its own (by design, for a real offline peer) rather than
        // giving up -- so its imageSlots permit is only freed once we explicitly cancel it.
        imageAttempts.Clear(); voiceAttempts.Clear();
        var imageSlots = (SemaphoreSlim)GetField("imageSlots");
        var slotsEnd = DateTime.UtcNow.AddSeconds(15);
        // Retried, not one-shot: DownloadAttachmentAsync registers itself in `downloads` only once
        // its Task.Run body actually starts on a thread-pool thread, which can lag behind this
        // synchronous code -- a CancelDownload call before that registration is a silent no-op.
        while (imageSlots.CurrentCount < 2 && DateTime.UtcNow < slotsEnd)
        {
            foreach (var m in voiceMessages.Append(imageMessage)) engine.CancelDownload(m);
            Thread.Sleep(100);
        }
        Check(imageSlots.CurrentCount == 2, "both scheduler slots free up again once the in-flight fake downloads are cancelled");

        // Rule 4: after 3 consecutive voice admissions, the next admission must be the image,
        // even though voice is still the default preference and voice candidates remain.
        SetField("consecutiveAutoVoiceAdmissions", 3);
        QueueAutomaticMedia();
        Check(ImageAdmitted(), "after 3 consecutive voice admissions, the next admission is forced to the waiting image");
        Check(VoicesAdmitted() == 1, "the fairness counter resets after the forced image, so the same call's second free slot goes to voice");

        Console.WriteLine($"VOICESCHEDULERCHECK\tPASS={pass}\tFAIL={fail}");
        try { Directory.Delete(root, true); } catch { }
        return fail == 0 ? 0 : 1;
    }
}
