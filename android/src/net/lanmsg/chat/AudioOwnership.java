package net.lanmsg.chat;

/** Coordinates audio device ownership between voice calls and voice messages.
 *  Pure Java — no Android dependency.  The service owns one instance.
 *
 *  Rules (plan-v006):
 *  - Call capture blocks voice message recording and playback.
 *  - Voice recording blocks call start (calls check before inviting).
 *  - Voice playback is interrupted by calls, not the reverse.
 *  - Drafts survive; failed release blocks competing capture.
 */
public class AudioOwnership {

  public enum Owner {
    FREE,              // No one owns audio
    CALL_CAPTURE,      // An active call is capturing the microphone
    CALL_RINGING,      // A call is ringing (not yet capturing, but reserve)
    VOICE_RECORDING,   // Voice message is recording
    VOICE_PLAYBACK     // Voice message is playing
  }

  private Owner current = Owner.FREE;
  private long ownerSinceMs;

  // ── Queries ──────────────────────────────

  public synchronized Owner current() { return current; }

  /** Can a call start? */
  public synchronized boolean canStartCall() {
    return current == Owner.FREE || current == Owner.CALL_RINGING ||
           current == Owner.VOICE_PLAYBACK;
  }

  /** Can a voice message start recording? */
  public synchronized boolean canStartRecording() {
    return current == Owner.FREE;
  }

  /** Can voice playback start? */
  public synchronized boolean canStartPlayback() {
    return current == Owner.FREE || current == Owner.VOICE_PLAYBACK ||
           current == Owner.VOICE_RECORDING;
  }

  // ── Claims ──────────────────────────────

  /** Call controller claims audio for an incoming ringing call. */
  public synchronized boolean claimRing() {
    if (current == Owner.VOICE_RECORDING) return false;
    if (current == Owner.VOICE_PLAYBACK) {
      // Interrupt playback — caller should stop active player
    }
    current = Owner.CALL_RINGING;
    ownerSinceMs = System.currentTimeMillis();
    return true;
  }

  /** Call controller claims microphone for Connected. */
  public synchronized boolean claimCallCapture() {
    if (current != Owner.CALL_RINGING && current != Owner.FREE) return false;
    current = Owner.CALL_CAPTURE;
    ownerSinceMs = System.currentTimeMillis();
    return true;
  }

  /** Voice message recording starts.  Returns false if call is using audio. */
  public synchronized boolean claimRecording() {
    if (current != Owner.FREE && current != Owner.VOICE_PLAYBACK) return false;
    // Interrupt playback if active
    current = Owner.VOICE_RECORDING;
    ownerSinceMs = System.currentTimeMillis();
    return true;
  }

  /** Voice message playback starts.  Returns false if call is capturing. */
  public synchronized boolean claimPlayback() {
    if (current == Owner.CALL_CAPTURE || current == Owner.CALL_RINGING) return false;
    current = Owner.VOICE_PLAYBACK;
    ownerSinceMs = System.currentTimeMillis();
    return true;
  }

  // ── Release ──────────────────────────────

  /** Release current ownership back to FREE. */
  public synchronized void release() {
    current = Owner.FREE;
    ownerSinceMs = 0;
  }

  /** Release if owned by the given owner (idempotent). */
  public synchronized void releaseIf(Owner expected) {
    if (current == expected) release();
  }

  /** Force release — call ended or engine shutdown. */
  public synchronized void forceRelease() {
    current = Owner.FREE;
    ownerSinceMs = 0;
  }

  public synchronized long heldMs() {
    return ownerSinceMs > 0 ? System.currentTimeMillis() - ownerSinceMs : 0;
  }

  /** The service's shared owner, or null when no service is bound. Lets the Activity-side
   *  voice-message code participate in the same ownership arbitration as the call controller. */
  static AudioOwnership of(MessengerService service) {
    return service == null ? null : service.audioOwner;
  }
}