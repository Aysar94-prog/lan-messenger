package net.lanmsg.chat;

import java.io.File;
import java.util.UUID;

// Voice Messages Android, AT02 addendum: regression test for a real bug found during the A05
// verification pass and fixed on both platforms in the same session -- sendTransactionId was
// written by markVoiceDraftSendTransaction (step 7) but never actually read by reconcile(), so
// crash-outcome 8 (message durably queued at step 9, registry cleanup at step 10 never ran)
// resurfaced the stale draft as an ordinary Finalized/sendable draft -- clicking Send again would
// have duplicated an already-sent message, violating Draft state recovery item 5
// ("reconciliation must never produce a duplicate Send of the same draft"). Made deterministic
// the same way VoiceMessagesCheck.java avoids PeerEngine.start(): package-private field access
// needs no reflection here (unlike the C# side, where these fields are truly private). Invoked
// directly: `java -cp <dir> net.lanmsg.chat.VoiceDraftReconcileCheck`.
public final class VoiceDraftReconcileCheck {
  public static void main(String[] args) throws Exception {
    int pass = 0, fail = 0;
    File root = new File(System.getProperty("java.io.tmpdir"), "voice-draft-reconcile-check-" + UUID.randomUUID());
    PeerEngine e = new PeerEngine(root, "ReconcileCheck", new TestProtector(root));

    // Scenario A: crash-outcome 8 -- the message was durably queued (step 9) but the registry
    // entry was never removed (step 10). Reconciliation must complete step 10.
    String draftA = UUID.randomUUID().toString();
    String txA = UUID.randomUUID().toString();
    PeerEngine.VoiceDraft vdA = new PeerEngine.VoiceDraft(draftA, "some-conversation", false, System.currentTimeMillis(), System.currentTimeMillis(), PeerEngine.VoiceDraft.FINALIZED, 640, 20);
    vdA.sendTransactionId = txA;
    e.voiceDrafts.put(draftA, vdA);
    e.messages.add(new PeerEngine.Message(txA, e.id, "peer-x", "", System.currentTimeMillis(), "Queued", "", "voice-" + txA + ".lanvoice.wav", 640, "a".repeat(64)));

    // Scenario B (control): a Finalized draft with a send transaction started but no matching
    // message yet (crash before step 9 actually landed) must not be silently deleted by the new
    // crash-outcome-8 path -- it must still fall through to the normal per-state handling (here:
    // no file on disk at its path, so it becomes Invalid via the pre-existing re-validation
    // logic, distinct from the new deletion path this test targets).
    String draftB = UUID.randomUUID().toString();
    PeerEngine.VoiceDraft vdB = new PeerEngine.VoiceDraft(draftB, "some-conversation", false, System.currentTimeMillis(), System.currentTimeMillis(), PeerEngine.VoiceDraft.FINALIZED, 640, 20);
    vdB.sendTransactionId = UUID.randomUUID().toString();
    e.voiceDrafts.put(draftB, vdB);

    VoiceDrafts.reconcile(e);

    if (!e.voiceDrafts.containsKey(draftA)) { pass++; System.out.println("PASS: a draft whose message was already durably queued (crash-outcome 8) is removed by reconciliation, preventing a duplicate Send"); }
    else { fail++; System.out.println("FAIL: crash-outcome-8 draft was not removed by reconciliation"); }
    if (e.voiceDrafts.containsKey(draftB)) { pass++; System.out.println("PASS: a draft with a send transaction but no matching queued message is left alone by reconciliation (not wrongly deleted)"); }
    else { fail++; System.out.println("FAIL: control draft was wrongly deleted by reconciliation"); }

    System.out.println("VOICEDRAFTRECONCILECHECK\tPASS=" + pass + "\tFAIL=" + fail);
    deleteRecursively(root);
    System.exit(fail == 0 ? 0 : 1);
  }

  static void deleteRecursively(File dir) {
    File[] children = dir.listFiles();
    if (children != null) for (File child : children) { if (child.isDirectory()) deleteRecursively(child); else child.delete(); }
    dir.delete();
  }
}
