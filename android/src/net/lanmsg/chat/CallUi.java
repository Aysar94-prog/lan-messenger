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
  private volatile CallVideoActions videoActions;

  /** Service binds the call's consent commands; never retain an Activity here. */
  public void bindVideoActions(CallVideoActions actions) { videoActions = actions; }

  private CallVideoActions videoAction(String expectedCallId, boolean incoming) throws java.io.IOException {
    CallSession s=current;
    CallVideoActions actions=videoActions;
    if(actions==null&&controller!=null)actions=controller.videoActions(expectedCallId);
    if(s==null || expectedCallId==null || !expectedCallId.equals(s.callId)
        || s.state!=(incoming?CallProtocol.State.IncomingRinging:CallProtocol.State.Connected))
      throw new java.io.IOException("That video action is no longer available");
    if(actions==null || !actions.isForCall(expectedCallId))
      throw new java.io.IOException("Video is not available for this call");
    return actions;
  }
  public CallVideoConsent.Result acceptVideo(String callId) throws java.io.IOException {
    return videoAction(callId,true).acceptVideo(callId);
  }
  public void answerWithVoice(String callId) throws Exception {
    CallVideoActions actions=videoActions;
    if(actions==null&&controller!=null)actions=controller.videoActions(callId);
    if(actions!=null && actions.isForCall(callId))videoAction(callId,true).answerWithVoice(callId);
    else accept(callId);
  }
  public CallVideoConsent.Result requestVideo(String callId) throws java.io.IOException {
    return videoAction(callId,false).requestVideo(callId);
  }
  public CallVideoConsent.Result acceptVideoUpgrade(String callId,String request) throws java.io.IOException {
    return videoAction(callId,false).acceptUpgrade(callId,request);
  }
  public CallVideoConsent.Result declineVideoUpgrade(String callId,String request) throws java.io.IOException {
    return videoAction(callId,false).declineUpgrade(callId,request);
  }
  public CallVideoConsent.Result turnCameraOn(String callId) throws java.io.IOException {
    return videoAction(callId,false).turnCameraOn(callId);
  }
  public void turnCameraOff(String callId) throws java.io.IOException {
    videoAction(callId,false).turnCameraOff(callId);
  }
  public void setRemoteSpeaker(String callId,boolean speaker)throws java.io.IOException {
    CallSession s=getCurrent();
    if(controller==null||s==null||!callId.equals(s.callId)||s.state!=CallProtocol.State.Connected)
      throw new java.io.IOException("That call is no longer available");
    controller.requestRemoteSpeaker(speaker);
  }
  public void setRemoteCamera(String callId,boolean on,String facing)throws java.io.IOException {
    if(controller==null)throw new java.io.IOException("Call unavailable");
    controller.requestRemoteCamera(callId,on,facing);
  }
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

  /** True when this render announces a new state rather than repainting the same one.
   *
   *  <p>The call screen redraws once a second for the whole length of a call and the status line
   *  carries the duration clock, so a permanently-live accessibility region had TalkBack read
   *  "0:04", then "0:05", then "0:06", once a second, for as long as the call lasted.  That makes
   *  the screen least usable to exactly the users the accessibility labels exist for: a
   *  screen-reader user cannot hear the other person over their own clock.  The clock still has to be
   *  *visible* every second, so it is the announcement that is gated, not the text.
   *
   *  <p>Equal states -- which is every one of the ~1800 renders a ten-minute call makes -- return
   *  false, so nothing is announced and the repetition stops. */
  public static boolean isNewStateAnnouncement(CallProtocol.State last, CallProtocol.State now) {
    return now != null && now != last;
  }

  /** Whether the user asked to be in the conversation rather than on the call screen, for this call.
   *
   *  <p>The chat control records the request instead of only performing it, because performing it was
   *  not enough: {@code showChat} ends in {@code render()}, which saw a live call with nothing
   *  attached and rebuilt the full-screen panel straight back over the conversation the user had
   *  just asked for, so the control did nothing visible.  Keyed by call ID so it lapses by itself
   *  when that call ends or a different one starts -- a boolean would have needed clearing in every
   *  path that ends a call, and one missed path would leave the next call with no screen at all.
   *
   *  <p>This is the exact test render() makes, which is why the two cannot drift apart. */
  public static boolean shouldStayDismissed(String dismissedCallId, CallSession call) {
    if (dismissedCallId == null || call == null) return false;
    return dismissedCallId.equals(call.callId);
  }

  /** What a screen reader is told when the call changes state.
   *
   *  <p>Not {@link #stateLabel}. That is the line on screen, which while Connected is the duration
   *  clock -- "0:03". Speaking the clock at the moment of connecting tells the user nothing about
   *  what just happened, and from then on the clock is shown silently, so they would hear "0:03"
   *  once and then nothing for the rest of the call.
   *
   *  <p>Pure so the harness can assert it: {@code CallView} cannot be loaded without an Android
   *  runtime, and this is the word a user with a screen reader actually hears. */
  public static String stateSpokenLabel(CallSession call) {
    if (call == null || call.state == null) return "";
    if (call.state == CallProtocol.State.Connected) return "Connected";
    return stateLabel(call);
  }

  /** Clamp a dragged minimised call bar so it stays on screen and clear of the edges.
   *
   *  <p>The bar is draggable because a minimised call parked at the foot of the stage sits exactly
   *  where the message box and its keyboard appear, so the two could not both be used.  Draggable
   *  means the user decides, but it also means the bar can be dropped where nothing is visible to
   *  grab it again -- so every position is pulled back inside the stage.
   *
   *  <p>{@code stageW}/{@code stageH} are the stage's size and {@code barW}/{@code barH} the bar's
   *  measured size.  Either may be 0 before the first layout pass, in which case nothing is clamped
   *  and the position is returned unchanged: clamping against a zero-width stage would throw the
   *  bar into the corner, and the next pass corrects it.
   *
   *  <p>Pure so the edges are asserted by the harness, which cannot load a View. */
  public static int clampBarLeft(float left, int barW, int stageW, int margin) {
    if (stageW <= 0 || barW <= 0) return Math.max(margin, (int) left);
    return Math.max(margin, Math.min((int) left, stageW - barW - margin));
  }

  /** Clamp the bar's vertical position.  See {@link #clampBarLeft}. */
  public static int clampBarTop(float top, int barH, int stageH, int margin) {
    if (stageH <= 0 || barH <= 0) return Math.max(margin, (int) top);
    return Math.max(margin, Math.min((int) top, stageH - barH - margin));
  }

  /** Where a dragged bar goes horizontally, given where the finger went.
   *
   *  <p>Measured from where the finger went <b>down</b>, not from the per-event delta
   *  {@code GestureDetector.onScroll} hands over.  The detector only starts reporting once the finger
   *  has left the tap region, and up to that point it measures from a focus point it smooths as the
   *  gesture goes on -- so accumulating its deltas leaves the bar short of the finger and stopping
   *  wherever the smoothing happened to run out: a swipe of 227px on a real phone moved the bar
   *  148px.  The finger is the thing the user is watching, so the bar is placed against the finger's
   *  position relative to where it went down, which is also the only version that cannot drift.
   *
   *  <p>Pure so the harness, which can load neither a View nor a GestureDetector, can still assert
   *  that the bar lands exactly under the finger. */
  public static float draggedLeft(float downX, float startLeft, float fingerX) {
    return startLeft + (fingerX - downX);
  }

  /** Vertical counterpart of {@link #draggedLeft}. */
  public static float draggedTop(float downY, float startTop, float fingerY) {
    return startTop + (fingerY - downY);
  }

  /** Whether a finger that has moved this far is dragging the bar rather than tapping it.
   *
   *  <p>One control has to answer both "open the call" and "move me", so a movement short of the
   *  slop is read as the jitter of a tap and dropped.  Without it every tap to reopen nudges the bar
   *  slightly and it walks away from where the user put it, one tap at a time.  The test is on either
   *  axis rather than on the distance, so a straight sideways drag is a drag even when it is short. */
  public static boolean isBarDrag(float downX, float downY, float fingerX, float fingerY, float slop) {
    return Math.abs(fingerX - downX) >= slop || Math.abs(fingerY - downY) >= slop;
  }

  /** Which view-model an "accept this call" action should go through.
   *
   *  <p>There were two references to the same model in play: the one a control was built from, and
   *  the Activity's own separately-bound field.  Preferring the field was what made Accept inert on
   *  a cold start -- the field was never bound, because the service creates its model after both of
   *  the Activity's only two bind attempts -- and the user could not answer an incoming call at all.
   *  A control that is on screen must act on the instance that put it there.
   *
   *  <p>Pure so the ordering is pinned by the harness: {@code MainActivity} cannot be loaded without
   *  an Android runtime, and this is the rule that decides whether the one control a ringing call
   *  offers works at all.  Returns null only when no reference exists anywhere, which the callers
   *  report to the user rather than ignoring. */
  public static CallUi resolveForAccept(CallUi fromControl, CallUi bound, CallUi service) {
    if (fromControl != null) return fromControl;
    if (bound != null) return bound;
    return service;
  }

  /** Everything the call screen was built from, as one string to compare against.
   *
   *  <p>This replaces an eight-clause {@code ||} chain in {@code render()}. Nothing flags a clause
   *  that was forgotten, and one was: the peer's *name* was never in it, so a contact renamed
   *  mid-call left the old name and the old initial disc on screen until some unrelated change
   *  happened to force a rebuild. The name is the first thing a user reads on a call screen, and it
   *  is also the initial on the picture and the words in three accessibility labels, so all of them
   *  were wrong together.
   *
   *  <p>The route is compared by value rather than by identity because it arrives nullable from the
   *  audio route policy.
   *
   *  <p>Pure so the harness can assert it — {@code CallView} cannot be loaded without an Android
   *  runtime, and this is the rule that decides whether what the user sees is current. */
  public static String overlayKey(CallSession call, String peerName) {
    if (call == null) return "";
    return call.callId + "|" + call.state + "|" + call.muted + "|"
      + (call.audioRoute == null ? "" : call.audioRoute) + "|" + (peerName == null ? "" : peerName)
      +"|"+call.videoCapable+"|"+call.invitedVideo+(call.video==null?"":"|"+call.video.phase+"|"+call.video.request+"|"+call.video.generation+"|"+call.video.localCamera+"|"+call.video.remoteCamera);
  }

  /** Everything the return-to-call bar shows, as one string to compare against.
   *
   *  <p>This is what decides whether the bar is stale, and it was wrong twice before. It first keyed
   *  on {@code call.state}, an enum that stops changing the moment the call connects, while the text
   *  beside it is the duration -- so the bar was built once and showed "0:00" for the whole call. It
   *  then keyed only on whether the bar was the right visibility, so a bar whose words changed while
   *  it was already showing never rebuilt. Both were found by a user, not by a test.
   *
   *  <p>Here rather than in MainActivity so the pure-Java harness can assert it: it cannot load an
   *  Activity, so a rule that decides what a user sees has to live where the test can reach it. */
  public static String callBarWords(CallSession call) {
    return stateLabel(call) + "|" + detailLabel(call) + "|" + call.peerId;
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
    videoActions = null;
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

  /** A03: terminal/absent snapshots cannot own a pending camera permission action.
   * This does not authorize video capture or imply video consent. */
  public static String cameraPermissionCallId(CallSession call) {
    return call == null || call.state == null || call.state.terminal() ? null : call.callId;
  }

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
