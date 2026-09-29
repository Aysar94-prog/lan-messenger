using System.Reflection;
using LanMessenger;

// Voice Messages Windows, WT02 addendum: regression test for a real bug found during the A05
// (Android) verification pass and fixed on both platforms in the same session --
// SendTransactionId was written by MarkVoiceDraftSendTransaction (step 7) but never actually
// read by ReconcileVoiceDrafts, so crash-outcome 8 (message durably queued at step 9, registry
// cleanup at step 10 never ran) resurfaced the stale draft as an ordinary Finalized/sendable
// draft -- clicking Send again would have duplicated an already-sent message, violating Draft
// state recovery item 5 ("reconciliation must never produce a duplicate Send of the same
// draft"). Made deterministic the same way VoiceSchedulerCheck.cs is: never call
// PeerEngine.Start(), reflection-seed voiceDrafts/messages directly, call the real (public)
// ReconcileVoiceDrafts(). Invoked via `CsharpHarness --voice-draft-reconcile-check`.
static class VoiceDraftReconcileCheck
{
    public static int Run()
    {
        int pass = 0, fail = 0;
        void Check(bool ok, string label) { if (ok) { pass++; Console.WriteLine("PASS: " + label); } else { fail++; Console.WriteLine("FAIL: " + label); } }

        var root = Path.Combine(Path.GetTempPath(), "voice-draft-reconcile-check-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        var engine = new PeerEngine(root, "ReconcileCheck", new TestProtector(root));
        var type = typeof(PeerEngine);
        object GetField(string name) => type.GetField(name, BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(engine)!;

        var draftType = type.Assembly.GetType("LanMessenger.VoiceDraft")!;
        var stateType = type.Assembly.GetType("LanMessenger.VoiceDraftState")!;
        object finalizedState = Enum.Parse(stateType, "Finalized");
        var voiceDrafts = (System.Collections.IDictionary)GetField("voiceDrafts");
        var messages = (System.Collections.IList)GetField("messages");

        // Scenario A: crash-outcome 8 -- the message was durably queued (step 9) but the
        // registry entry was never removed (step 10). Reconciliation must complete step 10.
        // Real draft ids are always Guid.NewGuid().ToString() in production (see
        // CreateVoiceDraft); VoiceDraftPath validates this, so the test uses real UUIDs too.
        var draftA = Guid.NewGuid().ToString();
        var txA = Guid.NewGuid().ToString();
        var vdA = Activator.CreateInstance(draftType, draftA, "some-conversation", false, PeerEngine.Now, PeerEngine.Now, finalizedState, 640L, 20L, txA)!;
        voiceDrafts[draftA] = vdA;
        var messageType = type.GetNestedType("Message")!;
        var sentMessage = Activator.CreateInstance(messageType, txA, engine.Id, "peer-x", "", PeerEngine.Now, "Queued", "", "voice-" + txA + ".lanvoice.wav", 640L, new string('a', 64), "", false)!;
        messages.Add(sentMessage);

        // Scenario B (control): a Finalized draft with a send transaction started but no
        // matching message yet (crash before step 9 actually landed) must not be silently
        // deleted by the new crash-outcome-8 path -- it must still fall through to the normal
        // per-state handling (here: no file on disk at its path, so it becomes Invalid via the
        // pre-existing re-validation logic, distinct from the new deletion path this test targets).
        var draftB = Guid.NewGuid().ToString();
        var vdB = Activator.CreateInstance(draftType, draftB, "some-conversation", false, PeerEngine.Now, PeerEngine.Now, finalizedState, 640L, 20L, Guid.NewGuid().ToString())!;
        voiceDrafts[draftB] = vdB;

        try { engine.ReconcileVoiceDrafts(); }
        catch (Exception e) { Check(false, "ReconcileVoiceDrafts() threw: " + e.Message); }

        Check(!voiceDrafts.Contains(draftA), "a draft whose message was already durably queued (crash-outcome 8) is removed by reconciliation, preventing a duplicate Send");
        Check(voiceDrafts.Contains(draftB), "a draft with a send transaction but no matching queued message is left alone by reconciliation (not wrongly deleted)");

        Console.WriteLine($"VOICEDRAFTRECONCILECHECK\tPASS={pass}\tFAIL={fail}");
        try { Directory.Delete(root, true); } catch { }
        return fail == 0 ? 0 : 1;
    }
}
