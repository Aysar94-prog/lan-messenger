package net.lanmsg.chat;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** UI state helper for call views.  Pure Java — no Android dependency.
 *  The Activity binds to this for display and actions.
 *
 *  Responsibilities:
 *  - Format call duration, state labels, quality hints
 *  - Expose "Allow incoming calls" preference
 *  - Provide action methods for the UI
 *  - Retain the terminal snapshot so the caller sees "Declined" after the controller has already
 *    returned to Idle (plan-v006: "Preserve a terminal UI snapshot while returning the controller
 *    to Idle")
 *
 *  The controller is service-owned and never holds an Activity reference.  This class is a
 *  passive view-model: the Activity binds, reads snapshots, and issues commands.
 */
public class CallUi {

  // ── Bound controller and settings ──────────────────────────────

  private CallController controller;
  private CallSettings settings;
  private final List<CallController.Callback> observers = new CopyOnWriteArrayList<>();

  // ── Current snapshot ───────────────────────────────────────────

  private volatile CallSession current;

  // The most recent terminal snapshot, kept after the controller drops back to Idle so the end
  // reason can still be shown.  Cleared when a new call starts, or when the UI consumes it.
  private volatile CallSession terminal;
  private volatile long terminalAtMs;

  // ── UI labels (plan-v006 contract wording) ─────────────────────

  /** State label for the caller's UI. */
  public static String stateLabel(CallSession s) {
    if (s == null) return "";
    switch (s.state) {
      case OutgoingRinging: return "Ringing…";
      case IncomingRinging: return s.isCaller ? "Ringing…" : "Incoming call";
      case Connecting:      return "Connecting…";
      case Connected:       return formatDuration(s.elapsedMs());
      case Ending:          return endLabel(s.endReason);
      default:              return "";
    }
  }

  /** Subtitle / detail label. */
  public static String detailLabel(CallSession s) {
    if (s == null) return "";
    switch (s.state) {
      case Connecting:  return "Establishing secure audio…";
      case Connected: {
        // Mute and the speaker route are two independent toggles and both are worth naming in
        // words: a glyph alone is not readable by everyone, and the collapsed bar carries no
        // control of its own for the user to read a glyph off.
        StringBuilder detail = new StringBuilder();
        if (s.muted) detail.append("Muted");
        if ("Speaker".equals(s.audioRoute)) detail.append(detail.length() > 0 ? " · Speaker" : "Speaker");
        return detail.toString();
      }
      default:          return "";
    }
  }

  /** Quality hint — only shown when Reduced. */
  public static String qualityHint(CallSession s) {
    if (s == null || s.quality != CallProtocol.Quality.Reduced) return null;
    return "Connection quality reduced\nCheck your LAN connection. On Wi-Fi, try moving closer to the router.";
  }

  /** End reason label. "Declined" for both manual and policy — never discloses preference. */
  static String endLabel(CallProtocol.EndReason r) {
    if (r == null) return "Call ended";
    switch (r) {
      case DECLINED:       return "Declined";
      case LOCAL_DECLINE:  return "Declined";
      case BUSY_REMOTE:    return "Busy";
      case CANCELED:       return "Canceled";
      case TIMEOUT_RINGING: return "No answer";
      case TIMEOUT_MEDIA:   return "Connection failed";
      case REMOTE_HANGUP:   return "Call ended";
      case LOCAL_HANGUP:    return "Call ended";
      case SIGNALING_LOST:  return "Disconnected";
      case NETWORK_FAILURE: return "Network unavailable";
      case OFFLINE:         return "You went offline";
      case MEDIA_ERROR:     return "Audio error";
      default:              return "Call ended";
    }
  }

  /** A plain sentence saying what the end label means.
   *
   *  <p>The label alone was not enough, and this was not a hypothetical: "Connection lost" was
   *  shown to a caller whose own Wi-Fi was fine, because the phone at the other end had been
   *  killed, and the only way to tell the difference from the wording was to already know what
   *  the app does internally.  Every reason now also says whose side it happened on and what to
   *  do about it, so the end of a call is something the user can act on rather than something they
   *  have to interpret. */
  public static String endHint(CallProtocol.EndReason r) {
    if (r == null) return "";
    switch (r) {
      case DECLINED:       return "The other phone declined the call.";
      // LOCAL_DECLINE is only ever produced on the phone that pressed Decline, so the sentence has
      // to be the other way round from DECLINED.  Telling the person who declined that *the other
      // phone* declined is the app accusing the far end of something they just did themselves.
      case LOCAL_DECLINE:  return "You declined the call.";
      case BUSY_REMOTE:    return "The other phone is already on a call.";
      case CANCELED:       return "The call was cancelled before anyone answered.";
      case TIMEOUT_RINGING: return "The other phone rang but did not answer.";
      case TIMEOUT_MEDIA:   return "The audio connection did not start in time.";
      case REMOTE_HANGUP:   return "The other phone hung up.";
      case LOCAL_HANGUP:    return "You hung up.";
      // No side named here on purpose.  SIGNALING_LOST is raised by the heartbeat timing out, by a
      // local frame failing to send, and by the local channel closing, and each of those happens
      // just as easily when this phone is the one that dropped.  The earlier wording sent a user
      // with a dead Wi-Fi to go and check the other phone, which is the one thing they cannot do.
      case SIGNALING_LOST:  return "Lost the link between the phones. One app closed, or one phone went off Wi-Fi.";
      case NETWORK_FAILURE: return "This phone has no network connection.";
      case OFFLINE:         return "This phone went offline during the call.";
      case MEDIA_ERROR:     return "The audio stream failed.";
      // Internal reasons, which reach the banner through endLabel's default. They are given real
      // sentences too: if one of them ever ends a call the user is watching, "Call ended" with no
      // explanation is the same unhelpful banner this method exists to fix.
      case GLARE_RESOLVED:  return "Both phones rang each other at the same time, so one call was dropped.";
      case ENGINE_SHUTDOWN: return "LAN Messenger closed while the call was running.";
      default:              return "";
    }
  }

  /** Duration formatted as MM:SS. */
  public static String formatDuration(long ms) {
    long sec = ms / 1000;
    return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
  }

  // ── Binding ────────────────────────────────────────────────────

  /** Bind to the service-owned controller and its settings.  The service owns this instance and
   *  makes it the controller's primary callback, so the terminal snapshot is retained even when no
   *  Activity is bound. */
  public void bind(CallController ctrl, CallSettings sets) {
    this.controller = ctrl;
    this.settings = sets;
    if (ctrl != null) this.current = ctrl.snapshot();
  }

  /** Register a snapshot observer, typically the Activity.  An observer cannot displace the
   *  primary callback, so binding the UI can never stop the service's call notification. */
  public void addObserver(CallController.Callback observer) {
    if (observer == null) return;
    if (!observers.contains(observer)) observers.add(observer);
    if (controller != null) controller.addListener(observer);
  }

  public void removeObserver(CallController.Callback observer) {
    if (observer == null) return;
    observers.remove(observer);
    if (controller != null) controller.removeListener(observer);
  }

  /** Release the controller and settings.  Only the service calls this, when it shuts down. */
  public void unbind() {
    for (CallController.Callback observer : observers) {
      if (controller != null) controller.removeListener(observer);
    }
    observers.clear();
    controller = null;
    settings = null;
    current = null;
    terminal = null;
    terminalAtMs = 0;
  }

  /** Absorb one controller snapshot.  Package-private so the retention rules below are testable:
   *  they are what stops the end banner from covering the screen forever, and that failure was
   *  invisible because the banner lives in a view. */
  void onSnapshot(CallSession snap) {
    this.current = snap;
    if (snap == null) return;
    if (snap.state.terminal()) {
      // Retain the end reason; the controller clears its own session immediately afterwards.
      //
      // The end time is stamped only when this is a NEW terminal outcome. The same terminal
      // snapshot is re-delivered on every render pass, and re-stamping it each time meant the end
      // banner refreshed itself once a second and never expired: it stayed on screen covering the
      // header, so a call that ended badly left the user with no visible way back.
      if (terminal == null || !terminal.callId.equals(snap.callId)
          || terminal.state != snap.state
          || terminal.endReason != snap.endReason) {
        this.terminal = snap;
        this.terminalAtMs = System.currentTimeMillis();
      }
    } else {
      // A new call invalidates any previously retained end reason.
      this.terminal = null;
      this.terminalAtMs = 0;
    }
  }

  /** Attach this instance as the controller's primary callback.  Used by the service, which is
   *  the single owner of the state stream; the Activity then binds as an extra observer. */
  public void adoptAsPrimary() {
    if (controller != null) controller.setCallback(this::onSnapshot);
  }

  /** Current call snapshot (thread-safe, may be null). */
  public CallSession getCurrent() { return current; }

  /** The retained terminal snapshot, or null. */
  public CallSession getTerminal() { return terminal; }

  /** When the retained terminal snapshot was recorded, or 0 if there is none.
   *
   *  <p>This is the single source of truth for "when did the call end".  The overlay used to keep
   *  its own static timestamp and refreshed it on every render, so a terminal snapshot left in
   *  {@link #current} made the end banner refresh itself once a second and never expire: the
   *  overlay stayed on screen indefinitely, covering the header and its back button. */
  public long terminalAtMs() { return terminal == null ? 0 : terminalAtMs; }

  /** Consume the retained terminal snapshot, so an end banner is shown once.
   *
   *  <p>The terminal snapshot still sitting in {@code current} is dropped too.  The controller
   *  notifies the end state and then drops its own session without ever publishing an idle one,
   *  so {@code current} keeps handing back that same terminal snapshot forever.  A view that
   *  re-reads it after the banner was dismissed would put the banner straight back up, and the
   *  user would be unable to close it at all. */
  public CallSession takeTerminal() {
    CallSession t = terminal;
    if (t != null) { terminal = null; terminalAtMs = 0; }
    CallSession c = current;
    if (c != null && c.state.terminal()) current = null;
    return t;
  }

  /** Whether a call is active (ringing, connecting, or connected). */
  public boolean hasActive() {
    CallSession s = current;
    return s != null && !s.state.terminal();
  }

  /** Whether an incoming call is waiting for a local decision. */
  public boolean hasIncoming() {
    CallSession s = current;
    return s != null && s.state == CallProtocol.State.IncomingRinging;
  }

  /** Whether an outgoing call is still ringing and can be canceled. */
  public boolean hasOutgoingRinging() {
    CallSession s = current;
    return s != null && s.state == CallProtocol.State.OutgoingRinging;
  }

  /** Whether a call is established, so in-call controls are meaningful. */
  public boolean isConnected() {
    CallSession s = current;
    return s != null && s.state == CallProtocol.State.Connected;
  }

  // ── Incoming-call preference ───────────────────────────────────

  public boolean allowIncoming() {
    return settings != null ? settings.allowIncoming() : true;
  }

  public String settingsLoadError() {
    return settings != null ? settings.loadError() : null;
  }

  /** Change the preference.  Going through the controller is what withdraws a still-ringing
   *  invitation, so the UI and its notification disappear instead of timing out. */
  public void setAllowIncoming(boolean value) throws Exception {
    if (controller != null) { controller.setAllowIncoming(value); return; }
    if (settings != null) settings.setAllowIncoming(value);
  }

  // ── Actions (forward to controller) ────────────────────────────

  /** Place a call.  The channel is opened by the service inside the controller's lock, so the
   *  first signaling frame can never precede its connection. */
  public void startCall(String peerId, CallController.TransportFactory channel) throws Exception {
    if (controller == null) throw new java.io.IOException("Calls are not available");
    controller.startCall(peerId, channel);
  }

  /** Accept an incoming call.  expectedCallId revalidates a stale notification action. */
  public void accept(String expectedCallId) throws Exception {
    if (controller != null && current != null &&
        current.state == CallProtocol.State.IncomingRinging) {
      controller.accept(expectedCallId);
    }
  }

  /** Decline an incoming call. */
  public void decline() throws Exception {
    if (controller != null && current != null) {
      controller.decline();
    }
  }

  /** Cancel an outgoing invitation still ringing. */
  public void cancel() throws Exception {
    if (controller != null && current != null) controller.cancel();
  }

  /** Hang up the current call. */
  public void hangup() throws Exception {
    if (controller != null && current != null && !current.state.terminal()) {
      controller.hangup();
    }
  }

  /** Toggle mute. */
  public void toggleMute() throws Exception {
    if (controller != null && current != null &&
        current.state == CallProtocol.State.Connected) {
      controller.setMute(!controller.isMuted());
    }
  }

  /** Whether the current call is muted. */
  public boolean isMuted() {
    return controller != null && controller.isMuted();
  }
}
