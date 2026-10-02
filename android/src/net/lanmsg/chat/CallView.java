package net.lanmsg.chat;

import android.app.AlertDialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A09: the in-call view.
 *
 *  A full-screen overlay added to the Activity's stage, so it covers the chat and the people list
 *  alike — a call is not a chat screen.  The view is rebuilt from the controller's immutable
 *  snapshot on every state change and re-rendered once a second while Connected for the duration
 *  clock, so nothing it shows is derived from Activity-side guesses.
 *
 *  Route, quality and connection state are rendered as three separate things, because they are
 *  three separate things: quality is a hint about the network, route is where the audio is going,
 *  and connection state is the call's own lifecycle.  A09's acceptance requires they stay distinct.
 */
final class CallView {

  private CallView() {}

  /** Overlay views currently attached, so render() can find the one to update. */
  private static View overlay;
  private static TextView stateText, detailText, qualityText, routeText;
  private static long lastQualityAnnounceMs;

  /** The call ID the Accept/Decline buttons act on, so a rebuilt view cannot act on a newer call. */
  private static String boundCallId;

  /** The call state the attached overlay was built for.
   *
   *  <p>The overlay holds completely different controls in different states — Accept/Decline while
   *  ringing, Cancel while ringing out, Mute and Speaker once media is up — so a state change has to
   *  rebuild it.  Rebuilding only on a call-ID change left the ringing buttons on screen after the
   *  call was answered, where tapping them did nothing because the state had already moved on, and
   *  meant the in-call controls never appeared at all. */
  private static CallProtocol.State boundState;

  /** Mute and audio route as last built.
   *
   *  <p>The round in-call toggles draw their glyph from these two values, so flipping either one
   *  has to rebuild the overlay: without it the control kept showing the state it was built with,
   *  which looked exactly like a toggle that does nothing. */
  private static boolean boundMuted;
  private static String boundRoute;

  /** Null-safe string equality, so an unknown route and a known one compare as different. */
  private static boolean sameRoute(String a, String b) { return a == null ? b == null : a.equals(b); }

  /** Layout params for one round in-call toggle: a fixed circle, spaced evenly in its row. */
  private static LinearLayout.LayoutParams dot(MainActivity activity) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(activity.dp(56), activity.dp(56));
    p.setMargins(activity.dp(14), 0, activity.dp(14), 0);
    return p;
  }

  /** When true the call overlay is collapsed to a compact bar at the foot of the stage.
   *
   *  <p>The full-screen panel made a call impossible to walk away from: there was no way to shrink
   *  it, so the only options were to answer, decline or force the app closed, and the screen could
   *  not be left alone.  Collapsing keeps the call running and the rest of the app reachable while
   *  still leaving a hang-up within one tap.  Reset by hide() and forgetOverlay(), so it can never
   *  leak into the next call. */
  private static boolean collapsed;

  /** Show or refresh the call overlay.  Safe to call on every render pass. */
  static void render(final MainActivity activity) {
    MessengerService host = activity.host;
    CallUi ui = host == null ? null : host.calls();
    if (ui == null) { hide(activity); return; }

    CallSession call = ui.getCurrent();
    if (call == null || call.state.terminal()) {
      // No live call. If a terminal snapshot is still being shown, keep the overlay up so the end
      // reason stays readable.
      //
      // The end reason is the entire point of this banner. "Declined", "Busy" and "No answer" are
      // the only thing the caller is told about how their attempt ended, and expiring the banner
      // after a couple of seconds left the caller staring at a closed popup with no idea whether
      // the other device was busy, ignored them, or never heard the phone. It now stays until it
      // is dismissed, which is safe because it is bottom-anchored -- the header and the back
      // button stay visible -- and it carries its own Close control.
      CallSession ended = call != null && call.state.terminal() ? call : ui.getTerminal();
      if (ended != null) {
        // A collapsed bar has nowhere to show why the call ended, so the banner always replaces
        // it. boundCallId is cleared as well, because the bar is still bound to this call ID and
        // would otherwise satisfy the identity test below and leave the bar standing.
        if (collapsed) { collapsed = false; boundCallId = null; }
        if (overlay == null || !ended.callId.equals(boundCallId)) buildTerminal(activity, ended, ui);
        return;
      }
      hide(activity);
      return;
    }

    // While collapsed the bar stands in for the panel, and it refreshes the same way the panel
    // does. It is checked before the rebuild test below, because the bar reuses stateText and
    // detailText to stay live, and that test would otherwise be satisfied and nothing would redraw.
    if (collapsed) {
      if (overlay == null || !call.callId.equals(boundCallId) || stateText == null) {
        buildCollapsed(activity, call);
        return;
      }
      stateText.setText(CallUi.stateLabel(call));
      detailText.setText(CallUi.detailLabel(call));
      return;
    }

    // Rebuild whenever the call identity OR the state changed: the controls on this overlay are
    // state-specific, so keeping the old ones would leave dead buttons on screen.
    if (overlay == null || !call.callId.equals(boundCallId) || call.state != boundState
        || call.muted != boundMuted || !sameRoute(call.audioRoute, boundRoute)
        || stateText == null) {
      build(activity, ui, call);
      return;
    }
    stateText.setText(CallUi.stateLabel(call));
    detailText.setText(CallUi.detailLabel(call));
    routeText.setText(routeLabel(activity, call));
    String hint = CallUi.qualityHint(call);
    qualityText.setText(hint == null ? "" : hint);
    qualityText.setVisibility(hint == null ? View.GONE : View.VISIBLE);
    if (hint != null) {
      // Announce the transition once, not on every render pass. The plan requires a single
      // accessible announcement per transition and no repeated announcements.
      long now = System.currentTimeMillis();
      if (now - lastQualityAnnounceMs > 5000) {
        lastQualityAnnounceMs = now;
        announce(activity, "Connection quality reduced");
      }
    }
  }

  private static void build(final MainActivity activity, CallUi ui, final CallSession call) {
    detach(activity);
    boundCallId = call.callId;
    boundState = call.state;
    boundMuted = call.muted;
    boundRoute = call.audioRoute;

    LinearLayout panel = activity.column();
    panel.setGravity(Gravity.CENTER_HORIZONTAL);
    panel.setPadding(activity.dp(24), activity.dp(24), activity.dp(24), activity.dp(24));
    panel.setBackgroundColor(Color.rgb(17, 27, 33));

    String peerName = activity.host == null ? call.peerId : activity.host.callPeerName(call.peerId);
    TextView name = activity.label(peerName, 26);
    name.setTextColor(Color.WHITE);
    name.setGravity(Gravity.CENTER);
    panel.addView(name);

    stateText = activity.label(CallUi.stateLabel(call), 20);
    stateText.setTextColor(Color.WHITE);
    stateText.setGravity(Gravity.CENTER);
    // The state line changes on its own while connected (the duration clock) and on every
    // transition, so it is a live region.
    stateText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    panel.addView(stateText);

    detailText = activity.label(CallUi.detailLabel(call), 15);
    detailText.setTextColor(Color.rgb(190, 200, 210));
    detailText.setGravity(Gravity.CENTER);
    panel.addView(detailText);

    routeText = activity.label(routeLabel(activity, call), 14);
    routeText.setTextColor(Color.rgb(190, 200, 210));
    routeText.setGravity(Gravity.CENTER);
    panel.addView(routeText);

    // Quality is inline, not a dialog, and only present when Reduced.
    qualityText = activity.label("", 14);
    qualityText.setTextColor(Color.rgb(255, 205, 120));
    qualityText.setGravity(Gravity.CENTER);
    String hint = CallUi.qualityHint(call);
    qualityText.setText(hint == null ? "" : hint);
    qualityText.setVisibility(hint == null ? View.GONE : View.VISIBLE);
    panel.addView(qualityText);

    panel.addView(activity.label("", 12));

    // Incoming: the decision buttons. Outgoing: Cancel. Connected: Mute and Hang up.
    if (call.state == CallProtocol.State.IncomingRinging) {
      LinearLayout row = activity.column();
      Button accept = activity.button("Accept");
      accept.setTextColor(Color.WHITE);
      accept.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(37, 211, 102)));
      accept.setContentDescription("Accept incoming call");
      accept.setOnClickListener(v -> activity.acceptCall(call.callId));
      row.addView(accept, new LinearLayout.LayoutParams(-1, activity.dp(56)));

      Button decline = activity.button("Decline");
      decline.setTextColor(Color.WHITE);
      decline.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(211, 47, 47)));
      decline.setContentDescription("Decline incoming call");
      decline.setOnClickListener(v -> activity.runCallAction(ui::decline, "Could not decline the call."));
      row.addView(decline, new LinearLayout.LayoutParams(-1, activity.dp(56)));
      panel.addView(row);
    } else if (call.state == CallProtocol.State.OutgoingRinging) {
      Button cancel = activity.button("Cancel");
      cancel.setTextColor(Color.WHITE);
      cancel.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(211, 47, 47)));
      cancel.setContentDescription("Cancel this call");
      cancel.setOnClickListener(v -> activity.runCallAction(ui::cancel, "Could not cancel the call."));
      panel.addView(cancel, new LinearLayout.LayoutParams(-1, activity.dp(56)));
    } else {
      LinearLayout row = new LinearLayout(activity);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER);
      // Mute and Speaker appear from the moment the call is answered, not only once it is fully
      // connected. Waiting for Connected left the user with no way to silence themselves during a
      // negotiation that can take seconds, and a stalled negotiation never reaches Connected at
      // all, so the controls were simply absent for the whole time they were most wanted.
      if (call.state.active()) {
        // Round glyph controls that show which way each toggle currently stands, the way a caller
        // expects from a phone call: two labelled words ("Mute", "Speaker") looked like actions to
        // perform rather than states to switch, and nothing on screen said which of the two the
        // audio was actually using.
        boolean speakerOn = "Speaker".equals(call.audioRoute);
        Button mute = activity.dotButton(call.muted ? "🔇" : "🎤",
          call.muted ? "Microphone muted. Switch the microphone back on"
                     : "Microphone on. Mute the microphone", call.muted);
        mute.setOnClickListener(v -> activity.runCallAction(ui::toggleMute, "Could not change the microphone."));
        row.addView(mute, dot(activity));

        Button route = activity.dotButton(speakerOn ? "🔊" : "🔈",
          speakerOn ? "Speaker on. Switch audio to the earpiece"
                    : "Speaker off. Switch audio to the speaker", speakerOn);
        route.setOnClickListener(v -> activity.toggleSpeakerphone(call));
        row.addView(route, dot(activity));
      }
      panel.addView(row);

      Button hangup = activity.button("Hang up");
      hangup.setTextColor(Color.WHITE);
      hangup.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(211, 47, 47)));
      hangup.setContentDescription("Hang up");
      hangup.setOnClickListener(v -> activity.runCallAction(ui::hangup, "Could not end the call."));
      panel.addView(hangup, new LinearLayout.LayoutParams(-1, activity.dp(56)));
    }

    // Offered in every live state, ringing or connected: the point is to be able to walk away from
    // the screen without ending the call, and that has to be possible at any stage of it.
    Button minimize = activity.button("Minimize");
    minimize.setTextColor(Color.rgb(190, 200, 210));
    minimize.setContentDescription("Minimize the call screen");
    minimize.setOnClickListener(v -> { collapsed = true; buildCollapsed(activity, call); });
    panel.addView(minimize, new LinearLayout.LayoutParams(-1, activity.dp(48)));

    android.widget.FrameLayout holder = new android.widget.FrameLayout(activity);
    holder.addView(panel, new android.widget.FrameLayout.LayoutParams(-1, -1));
    attach(activity, holder);
  }

  /** Terminal snapshot: show the end reason, then take the overlay down.
   *
   *  Anchored to the bottom of the stage rather than filling it.  As a full-screen panel the end
   *  reason sat on top of the header, hiding the back button, so a call that ended badly could
   *  look like a screen with no way out of it.  The holder is still full-size but transparent and
   *  not clickable, so the header stays visible and tappable underneath. */
  private static void buildTerminal(final MainActivity activity, final CallSession call,
                                    final CallUi ui) {
    detach(activity);
    boundCallId = call.callId;
    boundState = call.state;
    LinearLayout panel = activity.column();
    panel.setGravity(Gravity.CENTER_HORIZONTAL);
    panel.setPadding(activity.dp(24), activity.dp(20), activity.dp(24), activity.dp(20));
    panel.setBackgroundColor(Color.rgb(17, 27, 33));
    TextView label = activity.label(CallUi.stateLabel(call), 22);
    label.setTextColor(Color.WHITE);
    label.setGravity(Gravity.CENTER);
    label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    panel.addView(label);

    Button close = activity.button("Close");
    close.setContentDescription("Dismiss this message");
    close.setOnClickListener(v -> dismiss(activity, ui));
    panel.addView(close, new LinearLayout.LayoutParams(-1, activity.dp(48)));

    android.widget.FrameLayout holder = new android.widget.FrameLayout(activity);
    android.widget.FrameLayout.LayoutParams atBottom =
      new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    atBottom.setMargins(0, 0, 0, activity.dp(24));
    holder.addView(panel, atBottom);
    attach(activity, holder);
    // The label was just attached; announce it once so the end reason is not silent.
    label.post(() -> announce(activity, CallUi.stateLabel(call)));
  }

  /** Drop the end-reason banner and the retained snapshot behind it. */
  static void dismiss(MainActivity activity, CallUi ui) {
    if (ui != null) ui.takeTerminal();
    hide(activity);
  }

  /** The collapsed call: a compact bar pinned to the foot of the stage.
 *
 *  <p>Anchored to the bottom and only as tall as its text, so the header, the conversation and the
 *  rest of the app stay visible and usable underneath — that is the whole point of collapsing.
 *  Tapping the bar restores the full panel; hang-up stays one tap away without expanding. */
  private static void buildCollapsed(final MainActivity activity, final CallSession call) {
    detach(activity);
    boundCallId = call.callId;
    boundState = call.state;
    boundMuted = call.muted;
    boundRoute = call.audioRoute;

    String peerName = activity.host == null ? call.peerId : activity.host.callPeerName(call.peerId);

    LinearLayout bar = new LinearLayout(activity);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(activity.dp(16), activity.dp(8), activity.dp(12), activity.dp(8));
    bar.setBackgroundColor(Color.rgb(17, 27, 33));

    LinearLayout texts = activity.column();
    TextView who = activity.label(peerName, 16);
    who.setTextColor(Color.WHITE);
    texts.addView(who);
    stateText = activity.label(CallUi.stateLabel(call), 13);
    stateText.setTextColor(Color.rgb(190, 200, 210));
    stateText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    texts.addView(stateText);
    detailText = activity.label(CallUi.detailLabel(call), 12);
    detailText.setTextColor(Color.rgb(190, 200, 210));
    detailText.setVisibility(View.GONE);
    texts.addView(detailText);
    bar.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

    // Hang-up stays reachable without expanding, because the bar is what is left on screen.
    Button hangup = activity.button("Hang up");
    hangup.setTextColor(Color.WHITE);
    hangup.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(211, 47, 47)));
    hangup.setContentDescription("Hang up");
    hangup.setOnClickListener(v -> {
      CallUi ui = activity.host == null ? null : activity.host.calls();
      if (ui == null) return;
      activity.runCallAction(ui::hangup, "Could not end the call.");
    });
    bar.addView(hangup, new LinearLayout.LayoutParams(-2, activity.dp(48)));

    bar.setContentDescription("Minimized call with " + peerName + ". Tap to reopen.");
    bar.setOnClickListener(v -> { collapsed = false; render(activity); });

    android.widget.FrameLayout holder = new android.widget.FrameLayout(activity);
    android.widget.FrameLayout.LayoutParams atBottom =
      new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    atBottom.setMargins(0, 0, 0, activity.dp(8));
    holder.addView(bar, atBottom);
    attach(activity, holder);
  }

  private static String routeLabel(MainActivity activity, CallSession call) {
    String route = call.audioRoute;
    if (route == null || route.isEmpty()) return "";
    return "Audio: " + route;
  }

  private static void attach(MainActivity activity, View view) {
    if (activity.stage == null) return;
    activity.stage.addView(view, new android.widget.FrameLayout.LayoutParams(-1, -1));
    overlay = view;
  }

  private static void detach(MainActivity activity) {
    if (overlay != null) {
      if (activity.stage != null) activity.stage.removeView(overlay);
      overlay = null;
    }
    stateText = null; detailText = null; qualityText = null; routeText = null;
  }

  static void hide(MainActivity activity) {
    detach(activity);
    boundCallId = null;
    boundState = null;
    boundMuted = false;
    boundRoute = null;
    collapsed = false;
  }

  /** Drop references to a view tree the Activity has already thrown away.  Called from frame()
   *  when the stage is rebuilt; without it a later render would try to remove a view from a stage
   *  that no longer contains it. */
  static void forgetOverlay() {
    overlay = null;
    stateText = null; detailText = null; qualityText = null; routeText = null;
    boundCallId = null;
    boundState = null;
    boundMuted = false;
    boundRoute = null;
    lastQualityAnnounceMs = 0;
    collapsed = false;
  }

  /** Whether an overlay is currently attached. */
  static boolean isShowing() { return overlay != null; }

  private static void announce(MainActivity activity, String text) {
    if (text == null || text.isEmpty()) return;
    try {
      View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
      if (root != null) root.announceForAccessibility(text);
    } catch (Exception ignored) {
      // Accessibility services are optional; a failure here must never break the call UI.
    }
  }
}
