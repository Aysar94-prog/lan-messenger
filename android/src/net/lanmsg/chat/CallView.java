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

  // How long a terminal result stays on screen before the overlay goes away.
  private static final long TERMINAL_VISIBLE_MS = 2500;

  /** Overlay views currently attached, so render() can find the one to update. */
  private static View overlay;
  private static TextView stateText, detailText, qualityText, routeText;
  private static long lastQualityAnnounceMs;

  /** The call ID the Accept/Decline buttons act on, so a rebuilt view cannot act on a newer call. */
  private static String boundCallId;

  /** Show or refresh the call overlay.  Safe to call on every render pass. */
  static void render(final MainActivity activity) {
    MessengerService host = activity.host;
    CallUi ui = host == null ? null : host.calls();
    if (ui == null) { hide(activity); return; }

    CallSession call = ui.getCurrent();
    if (call == null) {
      // No live call. If a terminal snapshot is still being shown, keep the overlay up until it
      // expires so the end reason is readable, then take it down.
      CallSession ended = ui.getTerminal();
      if (ended != null && System.currentTimeMillis() - lastTerminalAt < TERMINAL_VISIBLE_MS) {
        buildTerminal(activity, ended);
        return;
      }
      ui.takeTerminal();
      hide(activity);
      return;
    }

    if (call.state.terminal()) {
      lastTerminalAt = System.currentTimeMillis();
      buildTerminal(activity, call);
      return;
    }

    lastTerminalAt = 0;
    // Rebuild only when the call identity or state changed; otherwise just refresh the clock.
    if (overlay == null || !call.callId.equals(boundCallId) || stateText == null) {
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

  private static long lastTerminalAt;

  private static void build(final MainActivity activity, CallUi ui, final CallSession call) {
    detach(activity);
    boundCallId = call.callId;

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
      if (call.state == CallProtocol.State.Connected) {
        Button mute = activity.button(call.muted ? "Unmute" : "Mute");
        mute.setContentDescription(call.muted ? "Unmute microphone" : "Mute microphone");
        mute.setOnClickListener(v -> activity.runCallAction(ui::toggleMute, "Could not change the microphone."));
        row.addView(mute, new LinearLayout.LayoutParams(0, activity.dp(56), 1));

        Button route = activity.button("Speaker");
        route.setContentDescription("Switch audio to the speaker");
        route.setOnClickListener(v -> activity.toggleSpeakerphone(call));
        row.addView(route, new LinearLayout.LayoutParams(0, activity.dp(56), 1));
      }
      panel.addView(row);

      Button hangup = activity.button("Hang up");
      hangup.setTextColor(Color.WHITE);
      hangup.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(211, 47, 47)));
      hangup.setContentDescription("Hang up");
      hangup.setOnClickListener(v -> activity.runCallAction(ui::hangup, "Could not end the call."));
      panel.addView(hangup, new LinearLayout.LayoutParams(-1, activity.dp(56)));
    }

    android.widget.FrameLayout holder = new android.widget.FrameLayout(activity);
    holder.addView(panel, new android.widget.FrameLayout.LayoutParams(-1, -1));
    attach(activity, holder);
  }

  /** Terminal snapshot: show the end reason, then take the overlay down. */
  private static void buildTerminal(final MainActivity activity, CallSession call) {
    detach(activity);
    boundCallId = call.callId;
    LinearLayout panel = activity.column();
    panel.setGravity(Gravity.CENTER_HORIZONTAL);
    panel.setPadding(activity.dp(24), activity.dp(24), activity.dp(24), activity.dp(24));
    panel.setBackgroundColor(Color.rgb(17, 27, 33));
    TextView label = activity.label(CallUi.stateLabel(call), 22);
    label.setTextColor(Color.WHITE);
    label.setGravity(Gravity.CENTER);
    label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    panel.addView(label);
    android.widget.FrameLayout holder = new android.widget.FrameLayout(activity);
    holder.addView(panel, new android.widget.FrameLayout.LayoutParams(-1, -1));
    attach(activity, holder);
    // The label was just attached; announce it once so the end reason is not silent.
    label.post(() -> announce(activity, CallUi.stateLabel(call)));
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
    lastTerminalAt = 0;
  }

  /** Drop references to a view tree the Activity has already thrown away.  Called from frame()
   *  when the stage is rebuilt; without it a later render would try to remove a view from a stage
   *  that no longer contains it. */
  static void forgetOverlay() {
    overlay = null;
    stateText = null; detailText = null; qualityText = null; routeText = null;
    boundCallId = null;
    lastTerminalAt = 0;
    lastQualityAnnounceMs = 0;
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
