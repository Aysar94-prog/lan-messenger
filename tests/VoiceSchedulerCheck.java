package net.lanmsg.chat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Voice Messages Android, AT04 remainder: the automatic-media scheduler's "3 consecutive voice
// admissions then force an image" fairness rule (TransferManager.queueAutomaticMedia, rule 4).
// tests/voice_scheduler_android.py already proves the scheduler works end to end over a real
// network (AT04, partial); asserting the *exact admission ordering* there would be
// timing-fragile against real async downloads. Sidestepped here the same way
// tests/CsharpHarness/VoiceSchedulerCheck.cs is: never call PeerEngine.start() at all -- no real
// networking, no periodic flush() -- and directly seed engine state (package-private fields
// need no reflection here, unlike the C# side). Invoked directly:
// `java -cp <dir> net.lanmsg.chat.VoiceSchedulerCheck`.
public final class VoiceSchedulerCheck {
  static int pass, fail;
  static void check(boolean ok, String label) {
    if (ok) { pass++; System.out.println("PASS: " + label); }
    else { fail++; System.out.println("FAIL: " + label); }
  }

  public static void main(String[] args) throws Exception {
    File root = new File(System.getProperty("java.io.tmpdir"), "voice-scheduler-check-" + UUID.randomUUID());
    PeerEngine e = new PeerEngine(root, "SchedulerCheck", new TestProtector(root));
    e.running = true; // skip start(): no real socket, no periodic flush() -- only our own direct calls ever run the scheduler.

    String peerId = UUID.randomUUID().toString();
    PeerEngine.Peer peer = new PeerEngine.Peer(peerId, "Peer1", "127.0.0.1", 1);
    peer.seen = System.currentTimeMillis(); peer.fingerprint = "F"; peer.verified = "F";
    e.peers.put(peerId, peer);

    List<PeerEngine.Message> voiceMessages = new ArrayList<>();
    for (int i = 0; i < 5; i++)
      voiceMessages.add(new PeerEngine.Message(UUID.randomUUID().toString(), peerId, e.id, "", System.currentTimeMillis(), "Received", "", "voice-" + UUID.randomUUID() + ".lanvoice.wav", 1000, "a".repeat(64)));
    PeerEngine.Message imageMessage = new PeerEngine.Message(UUID.randomUUID().toString(), peerId, e.id, "", System.currentTimeMillis(), "Received", "", "photo.png", 1000, "a".repeat(64));
    e.messages.addAll(voiceMessages); e.messages.add(imageMessage);

    // Baseline (rule 2/3): with the fairness counter at 0, voice is preferred by default -- both
    // free slots go to voice candidates; the image is not touched this call.
    e.consecutiveAutoVoiceAdmissions = 0;
    TransferManager.queueAutomaticMedia(e);
    boolean imageAdmitted = e.imageAttempts.contains(imageMessage.from + "/" + imageMessage.id);
    long voicesAdmitted = voiceMessages.stream().filter(m -> e.voiceAttempts.contains(m.from + "/" + m.id)).count();
    check(voicesAdmitted == 2 && !imageAdmitted, "with the fairness counter at 0, both free slots admit voice candidates and the image waits");

    // Reset admission state (a fresh scheduler run, same candidates still pending/undownloaded).
    // Each admission kicked off a real downloadAttachment against the fake, unreachable peer,
    // which retries until cancelled -- explicitly cancel and poll for both scheduler slots to
    // free rather than waiting on a network timeout.
    e.imageAttempts.clear(); e.voiceAttempts.clear();
    long deadline = System.currentTimeMillis() + 15000;
    while (e.imageSlots.availablePermits() < 2 && System.currentTimeMillis() < deadline) {
      for (PeerEngine.Message m : voiceMessages) TransferManager.cancelDownload(e, m);
      TransferManager.cancelDownload(e, imageMessage);
      Thread.sleep(100);
    }
    check(e.imageSlots.availablePermits() == 2, "both scheduler slots free up again once the in-flight fake downloads are cancelled");

    // Rule 4: after 3 consecutive voice admissions, the next admission must be the image, even
    // though voice is still the default preference and voice candidates remain.
    e.consecutiveAutoVoiceAdmissions = 3;
    TransferManager.queueAutomaticMedia(e);
    imageAdmitted = e.imageAttempts.contains(imageMessage.from + "/" + imageMessage.id);
    voicesAdmitted = voiceMessages.stream().filter(m -> e.voiceAttempts.contains(m.from + "/" + m.id)).count();
    check(imageAdmitted, "after 3 consecutive voice admissions, the next admission is forced to the waiting image");
    check(voicesAdmitted == 1, "the fairness counter resets after the forced image, so the same call's second free slot goes to voice");

    System.out.println("VOICESCHEDULERCHECK\tPASS=" + pass + "\tFAIL=" + fail);
    VoiceDraftReconcileCheck.deleteRecursively(root);
    System.exit(fail == 0 ? 0 : 1);
  }
}
