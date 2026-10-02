package net.lanmsg.chat;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

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

  /** The caller's teal, used for the header and the bottom bar alike, so the screen reads as one
   *  surface with the peer's picture sitting in a window cut out of it. */
  private static final int BAR = Color.rgb(14, 82, 76);
  /** Muted teal for the status and timer lines, so the name stays the loudest thing on screen. */
  private static final int BAR_DIM = Color.rgb(163, 199, 193);
  /** The wall the peer's picture is mounted on. */
  private static final int STAGE_COLOR = Color.rgb(126, 148, 151);
  /** Diameter of the peer's picture, and of the floating end-call disc. */
  private static final int AVATAR_DP = 168;
  private static final int HANGUP_DP = 84;
  /** Height of the docked control bar, needed to float the hang-up disc clear of it. */
  private static final int BAR_HEIGHT_DP = 92;

  /** Overlay views currently attached, so render() can find the one to update. */
  private static View overlay;
  private static TextView stateText, detailText, qualityText, routeText;
  private static long lastQualityAnnounceMs;

  /** The call ID the Accept/Decline buttons act on, so a rebuilt view cannot act on a newer call. */
  private static String boundCallId;

  /** The peer's current display name, or their ID before the service has one.
   *
   *  <p>Read on every render, not just at build time, so a rename is noticed. That is the whole point
   *  of putting it in {@link CallUi#overlayKey}. */
  private static String peerNameOf(MainActivity activity, CallSession call) {
    return activity == null || activity.host == null ? call.peerId : activity.host.callPeerName(call.peerId);
  }

  private static String boundKey;

  /** Whether the attached overlay is the end-reason banner rather than a live call panel.
   *
   *  <p>This has to be tracked separately from the call ID.  The banner replaces the panel for the
   *  call that just ended, so both carry the same ID and an identity test alone is satisfied by the
   *  stale panel: nothing was rebuilt, and the user was left looking at a finished call still
   *  showing its duration, its toggles and a live-looking Hang up, with no end reason anywhere. */
  private static boolean boundTerminal;

  /** Where the user dragged the minimised bar to, or -1 while it has never been dragged.
   *
   *  <p>Kept outside the view tree on purpose. The bar is rebuilt on every state change, and a
   *  position held in the view would be lost each time, so a minimised call would jump back to the
   *  bottom every time it was muted -- which is exactly the "it is in the way again" complaint. */
  private static float collapsedLeft = -1, collapsedTop = -1;

  /** When true the call overlay is collapsed to a compact bar at the foot of the stage.
   *
   *  <p>The full-screen panel made a call impossible to walk away from: there was no way to shrink
   *  it, so the only options were to answer, decline or force the app closed, and the screen could
   *  not be left alone.  Collapsing keeps the call running and the rest of the app reachable while
   *  still leaving a hang-up within one tap.  Reset by hide() and forgetOverlay(), so it can never
   *  leak into the next call. */
  private static boolean collapsed;

  /** The call whose screen the user asked to leave, for the 💬 control, or null.
   *
   *  <p>Held as a call ID rather than a boolean so it expires on its own: a new call, or this one
   *  reaching a terminal state, clears it in render() and the overlay comes back exactly as it
   *  would have without the user ever touching anything.  A boolean would have needed clearing in
   *  every path that ends a call, and one missed path would leave the next call with no screen at
   *  all. */
  private static String dismissedCallId;

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
        // A collapsed bar has nowhere to show why the call ended, so the banner always replaces it.
        // boundCallId is cleared as well, because the bar is still bound to this call ID and would
        // otherwise satisfy the identity test below and leave the bar standing.
        if (collapsed) { collapsed = false; boundCallId = null; }
        if (overlay == null || !boundTerminal || !ended.callId.equals(boundCallId)) {
          buildTerminal(activity, ended, ui);
        }
        return;
      }
      hide(activity);
      return;
    }

    // The user asked to be in the conversation instead, with this call still running.  The call bar
    // across the top is what stands in for the call screen until they come back to it; putting the
    // overlay up again here would undo the tap that opened the chat.
    if (dismissedCallId != null && !dismissedCallId.equals(call.callId)) dismissedCallId = null;
    if (CallUi.shouldStayDismissed(dismissedCallId, call)) return;

    // Announce a state change once, here, on the single path every render takes. It sits above the
    // collapsed/full split and above the rebuild test because both of those return early: announcing
    // after them missed every transition that happened to rebuild the screen, which is most of them
    // -- picking up a call rebuilds, so "Connected" was the one update a screen-reader user never
    // heard. It also has to sit above the clock, or the clock is announced once a second.
    announceStateChange(activity, call);

    // While collapsed the bar stands in for the panel, and it refreshes the same way the panel
    // does. It is checked before the rebuild test below, because the bar reuses stateText and
    // detailText to stay live, and that test would otherwise be satisfied and nothing would redraw.
    if (collapsed) {
      if (overlay == null || !call.callId.equals(boundCallId) || stateText == null) {
        buildCollapsed(activity, ui, call);
        return;
      }
      stateText.setText(CallUi.stateLabel(call));
      detailText.setText(CallUi.detailLabel(call));
      return;
    }

    // Rebuild whenever anything the overlay was built from has changed: the controls on it are
    // state-specific, so keeping the old ones would leave dead buttons on screen. Compared as one
    // key string rather than a chain of clauses -- see boundKey for why.
    if (overlay == null || boundTerminal || stateText == null
        || !CallUi.overlayKey(call, peerNameOf(activity, call)).equals(boundKey)) {
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

  /** The state the last announcement was made for, so a transition can be told from a repaint.
   *
   *  <p>render() runs once a second for the whole length of a call and the status line carries the
   *  duration clock, so the status line cannot be an accessibility live region: that had TalkBack
   *  read "0:04", then "0:05", then "0:06", once a second, for as long as the call lasted.  That
   *  makes the screen least usable to exactly the users the accessibility labels elsewhere in this
   *  file exist for: a screen-reader user cannot hear the other person over their own clock.  The
   *  clock still has to be *visible* every second, so it is the announcement that is gated, not the
   *  text. */
  private static CallProtocol.State announcedState;

  /** Announce a state transition, once, and only when the state really changed.
   *
   *  <p>The rule itself lives in {@link CallUi#isNewStateAnnouncement} and the wording in
   *  {@link CallUi#stateSpokenLabel}, because this class cannot be loaded without an Android runtime
   *  and the checks that prove the clock is not read aloud every second have to be able to reach
   *  them.
   *
   *  <p>Every state is spoken, Connected included.  With the live region gone there is nothing else
   *  that would say the call connected, and answering is the update that most needs saying out loud. */
  private static void announceStateChange(MainActivity activity, CallSession call) {
    if (!CallUi.isNewStateAnnouncement(announcedState, call.state)) return;
    announcedState = call.state;
    announce(activity, CallUi.stateSpokenLabel(call));
  }

  private static void build(final MainActivity activity, CallUi ui, final CallSession call) {
    detach(activity);
    boundCallId = call.callId;
    boundTerminal = false;

    String peerName = peerNameOf(activity, call);
    // Bound last, after the name is resolved, so the key and the views agree on this build.
    boundKey = CallUi.overlayKey(call, peerName);

    // Root is a FrameLayout so the end-call disc can float over the picture while the header and
    // the control bar stay pinned to the edges, which a single LinearLayout cannot do.
    FrameLayout holder = new FrameLayout(activity);
    holder.setBackgroundColor(STAGE_COLOR);
    // A background colour does not make a view swallow touches.  FrameLayout.onTouchEvent returns
    // false, so every tap that missed a child -- the wall around the picture, the header padding,
    // the gaps beside the disc -- was handed straight back to `stage`, which then dispatched it to
    // `chrome` underneath.  On the call screen that meant a tap beside the avatar opened the
    // conversation behind the call, and a drag scrolled it: the user had to know where the hidden
    // buttons were to use the screen at all.  buildCollapsed and buildTerminal deliberately leave
    // their holders transparent to touches, because there the chat underneath is meant to stay
    // usable; only this full-screen panel is a wall.
    holder.setClickable(true);

    LinearLayout panel = new LinearLayout(activity);
    panel.setOrientation(LinearLayout.VERTICAL);

    panel.addView(header(activity, ui, call, peerName), new LinearLayout.LayoutParams(-1, -2));

    // The picture takes all the space that is left, so the screen stays balanced on a short
    // device and on a tall one without the controls drifting away from the bottom edge.
    FrameLayout stage = new FrameLayout(activity);
    stage.setBackgroundColor(STAGE_COLOR);
    View picture = avatarView(activity, call, peerName);
    stage.addView(picture, new FrameLayout.LayoutParams(activity.dp(AVATAR_DP),
      activity.dp(AVATAR_DP), Gravity.CENTER));
    panel.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

    // Ringing states put their decision where the floating disc would be; live states put the
    // control bar there.  Both sit directly above the bottom edge, so the shape of the screen is
    // the same whether or not anybody has picked up yet.
    LinearLayout actionZone = new LinearLayout(activity);
    actionZone.setOrientation(LinearLayout.VERTICAL);
    actionZone.setGravity(Gravity.CENTER);
    if (call.state == CallProtocol.State.IncomingRinging) {
      actionZone.addView(decision(activity, ui, call, peerName, true));
    } else if (call.state == CallProtocol.State.OutgoingRinging) {
      actionZone.addView(decision(activity, ui, call, peerName, false));
    } else {
      actionZone.addView(controlBar(activity, ui, call, peerName));
    }
    panel.addView(actionZone, new LinearLayout.LayoutParams(-1, -2));
    holder.addView(panel, new FrameLayout.LayoutParams(-1, -1));

    // The end-call disc belongs to the answered call only.  While the call is still ringing the
    // action zone holds Accept/Decline, and the disc was floated over the top of it at the same
    // offset: it covered both buttons, so tapping Accept ended the call instead of answering it.
    // There is also nothing for it to mean while ringing — Decline and Cancel already are the way
    // to stop a ringing call, and a second red target would be a third answer to the same question.
    if (call.state != CallProtocol.State.IncomingRinging
        && call.state != CallProtocol.State.OutgoingRinging) {
      // The description passed here names the peer, and it is kept. It used to be overwritten with a
      // bare "Hang up" a line later, so the specific "End the call with <name>" never reached a
      // screen reader: on a call screen the only other control is the microphone, so "Hang up" told
      // a screen-reader user nothing they could not work out, and the redundant second
      // setContentDescription made it look deliberate.
      View hangup = activity.hangupCircle("End the call with " + peerName, HANGUP_DP);
      hangup.setOnClickListener(v -> activity.runCallAction(ui::hangup, "Could not end the call."));
      FrameLayout.LayoutParams overStage =
        new FrameLayout.LayoutParams(activity.dp(HANGUP_DP), activity.dp(HANGUP_DP),
          Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
      overStage.bottomMargin = activity.dp(BAR_HEIGHT_DP + 28);
      holder.addView(hangup, overStage);
    }

    attach(activity, holder);
  }

  /** The teal header: minimise, the peer's name as a headline, and the timer or status under it.
   *
   *  <p>The status line doubles as the duration clock, which is what the reference layout does —
   *  it is the only place the elapsed time appears, so it has to be the live region.  The minimise
   *  control lives up here rather than in the bottom bar, because the bottom bar is the caller's
   *  three real controls and adding a fourth would break the shape the layout is recognised by.
   *  It stays available in every live state, ringing included, or the call could not be walked
   *  away from at any stage. */
  private static View header(final MainActivity activity, final CallUi ui, final CallSession call, String peerName) {
    LinearLayout bar = new LinearLayout(activity);
    bar.setOrientation(LinearLayout.VERTICAL);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(activity.dp(20), activity.dp(10), activity.dp(20), activity.dp(12));
    bar.setBackgroundColor(BAR);

    LinearLayout top = new LinearLayout(activity);
    top.setOrientation(LinearLayout.HORIZONTAL);
    top.setGravity(Gravity.CENTER_VERTICAL);

    LinearLayout names = new LinearLayout(activity);
    names.setOrientation(LinearLayout.VERTICAL);
    names.setGravity(Gravity.CENTER_VERTICAL);
    names.addView(activity.callTitle(peerName), new LinearLayout.LayoutParams(-1, -2));

    stateText = activity.label(CallUi.stateLabel(call), 16);
    stateText.setTextColor(BAR_DIM);
    stateText.setGravity(Gravity.LEFT);
    // Deliberately NOT an accessibility live region. render() rewrites this line once a second for
    // the whole length of a call, so a live region made TalkBack read the duration aloud every
    // second -- a screen-reader user could not hear the person they were on a call with. State
    // transitions are announced instead, once each, by announceStateChange().
    names.addView(stateText, new LinearLayout.LayoutParams(-1, -2));
    top.addView(names, new LinearLayout.LayoutParams(0, -2, 1));

    // 🔽 rather than a diagonal double arrow: ⤢ is a Unicode technical symbol that renders as a
    // small ambiguous mark, so the control that lets the user walk away from a call looked like
    // decoration and was never found.  A down arrow onto a bar is the same idea every phone uses.
    Button minimize = activity.dotButton("🔽", "Minimize the call screen", false, 44);
    minimize.setOnClickListener(v -> { collapsed = true; buildCollapsed(activity, ui, call); });
    top.addView(minimize, new LinearLayout.LayoutParams(activity.dp(44), activity.dp(44)));
    bar.addView(top, new LinearLayout.LayoutParams(-1, -2));

    // Mute and the speaker route are named in words under the name.  A glyph alone is unreadable
    // for some users, and on a call screen the words are the only place the state of the two
    // toggles is stated at the same time as the picture of the person being called.
    detailText = activity.label(CallUi.detailLabel(call), 13);
    detailText.setTextColor(BAR_DIM);
    detailText.setGravity(Gravity.LEFT);
    bar.addView(detailText, new LinearLayout.LayoutParams(-1, -2));

    routeText = activity.label(routeLabel(activity, call), 12);
    routeText.setTextColor(BAR_DIM);
    routeText.setGravity(Gravity.LEFT);
    bar.addView(routeText, new LinearLayout.LayoutParams(-1, -2));

    // Quality is inline, not a dialog, and only present when Reduced.
    qualityText = activity.label("", 12);
    qualityText.setTextColor(Color.rgb(255, 205, 120));
    qualityText.setGravity(Gravity.LEFT);
    String hint = CallUi.qualityHint(call);
    qualityText.setText(hint == null ? "" : hint);
    qualityText.setVisibility(hint == null ? View.GONE : View.VISIBLE);
    bar.addView(qualityText, new LinearLayout.LayoutParams(-1, -2));

    return bar;
  }

  /** The teal control bar docked at the bottom: chat, speaker, microphone.
   *
   *  <p>Mute and Speaker appear from the moment the call is answered, not only once it is fully
   *  connected.  Waiting for Connected left the user with no way to silence themselves during a
   *  negotiation that can take seconds, and a stalled negotiation never reaches Connected at
   *  all, so the controls were simply absent for the whole time they were most wanted.
   *
   *  <p>Both toggles are drawn as round glyphs that show which way each currently stands: two
   *  labelled words ("Mute", "Speaker") looked like actions to perform rather than states to
   *  switch, and nothing on screen said which of the two the audio was actually using. */
  private static View controlBar(final MainActivity activity, final CallUi ui,
                                 final CallSession call, final String peerName) {
    LinearLayout bar = new LinearLayout(activity);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER);
    bar.setPadding(activity.dp(8), activity.dp(10), activity.dp(8), activity.dp(10));
    bar.setBackgroundColor(BAR);

    // Open the conversation without ending the call.  The Activity's own call bar stays across the
    // top of every screen with Return and End on it, so the call is neither hidden nor lost.
    Button chat = activity.dotButton("💬", "Open the conversation with " + peerName, false, 56);
    // Leaving the call screen for the conversation has to be recorded, not just acted on.  Clearing
    // the overlay was not enough on its own: showChat() ends in render(), which saw a live call with
    // nothing attached and rebuilt the full-screen panel straight back over the chat the user had
    // just asked for, so the control did nothing visible.  The dismissal is keyed to the call ID so
    // it lapses by itself when that call ends or a different one starts, and cannot leak into the
    // next call.
    chat.setOnClickListener(v -> { hide(activity); dismissedCallId = call.callId; activity.showChat(call.peerId); });
    bar.addView(chat, barSlot(activity));

    if (call.state.active()) {
      boolean speakerOn = "Speaker".equals(call.audioRoute);
      Button route = activity.dotButton(speakerOn ? "🔊" : "🔈",
        speakerOn ? "Speaker on. Switch audio to the earpiece"
                  : "Speaker off. Switch audio to the speaker", speakerOn, 56);
      route.setOnClickListener(v -> activity.toggleSpeakerphone(call));
      bar.addView(route, barSlot(activity));

      Button mute = activity.dotButton(call.muted ? "🔇" : "🎤",
        call.muted ? "Microphone muted. Switch the microphone back on"
                   : "Microphone on. Mute the microphone", call.muted, 56);
      mute.setOnClickListener(v -> activity.runCallAction(ui::toggleMute,
        "Could not change the microphone."));
      bar.addView(mute, barSlot(activity));
    }
    return bar;
  }

  /** Equal share of the bar's width, so the three controls sit evenly spaced whatever their count.
   *
   *  <p>Weight rather than a fixed width: during negotiation only two of the three are shown, and
   *  fixed margins left them bunched in the middle instead of holding the same positions. */
  private static LinearLayout.LayoutParams barSlot(MainActivity activity) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, activity.dp(BAR_HEIGHT_DP - 20), 1);
    p.setMargins(activity.dp(8), 0, activity.dp(8), 0);
    return p;
  }

  /** Accept / Decline for an incoming call, or Cancel for one still ringing out.
   *
   *  <p>Worded and full width rather than two bare circles.  These are the only two decisions the
   *  callee can make, and getting them wrong is not a recoverable mistake, so they are spelled out
   *  instead of being left to a glyph the user has to already recognise. */
  private static View decision(final MainActivity activity, final CallUi ui, final CallSession call,
                               final String peerName, boolean incoming) {
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(LinearLayout.VERTICAL);
    row.setGravity(Gravity.CENTER);
    row.setPadding(activity.dp(24), 0, activity.dp(24), 0);

    if (incoming) {
      Button accept = activity.button("Accept  📞");
      accept.setTextColor(Color.WHITE);
      accept.setTextSize(16);
      accept.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(37, 211, 102)));
      accept.setContentDescription("Accept incoming call");
      // Acts on the view-model this button was built from, exactly as Decline does. Routing it
      // through the Activity's own field instead made Accept inert whenever that second reference
      // was not bound -- a cold start never bound it -- so the user could not answer a call at all.
      accept.setOnClickListener(v -> activity.acceptCall(ui, call.callId));
      row.addView(accept, new LinearLayout.LayoutParams(-1, activity.dp(56)));

      Button decline = activity.button("Decline  📴");
      decline.setTextColor(Color.WHITE);
      decline.setTextSize(16);
      decline.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(211, 47, 47)));
      decline.setContentDescription("Decline incoming call");
      decline.setOnClickListener(v -> activity.runCallAction(ui::decline, "Could not decline the call."));
      row.addView(decline, new LinearLayout.LayoutParams(-1, activity.dp(56)));
      return row;
    }

    Button cancel = activity.button("Cancel  📴");
    cancel.setTextColor(Color.WHITE);
    cancel.setTextSize(16);
    cancel.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(211, 47, 47)));
    cancel.setContentDescription("Cancel this call");
    cancel.setOnClickListener(v -> activity.runCallAction(ui::cancel, "Could not cancel the call."));
    row.addView(cancel, new LinearLayout.LayoutParams(-1, activity.dp(56)));
    return row;
  }

  /** The peer's picture, or a coloured initial disc when they have not sent one.
   *
   *  <p>Uses the avatar already synced from the peer rather than a placeholder, because the one
   *  thing a caller wants to confirm before they speak is that the right person is on the line.
   *  Falls back to the same initial disc the people list uses, so a device with no avatar looks
   *  the same here as it does everywhere else rather than showing an empty grey circle. */
  private static View avatarView(MainActivity activity, CallSession call, String peerName) {
    String initial = peerName == null || peerName.isEmpty()
      ? "?" : peerName.substring(0, 1).toUpperCase(Locale.ROOT);
    PeerEngine engine = activity.engine();
    byte[] raw = engine == null ? null : engine.peerAvatar(call.peerId);
    Bitmap bitmap = raw == null ? null : activity.inlineBitmap(raw);
    if (bitmap == null) {
      TextView disc = activity.circle(initial, activity.nameColor(call.peerId), AVATAR_DP, 64);
      disc.setContentDescription(peerName);
      return disc;
    }
    ImageView picture = new ImageView(activity);
    picture.setImageBitmap(bitmap);
    picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
    picture.setBackground(activity.circleBg(activity.nameColor(call.peerId), AVATAR_DP));
    picture.setClipToOutline(true);
    picture.setContentDescription(peerName);
    return picture;
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
    boundTerminal = true;
    boundKey = CallUi.overlayKey(call, peerNameOf(activity, call));
    LinearLayout panel = activity.column();
    panel.setGravity(Gravity.CENTER_HORIZONTAL);
    panel.setPadding(activity.dp(24), activity.dp(20), activity.dp(24), activity.dp(20));
    panel.setBackgroundColor(BAR);
    TextView label = activity.label(CallUi.stateLabel(call), 22);
    label.setTextColor(Color.WHITE);
    label.setGravity(Gravity.CENTER);
    label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    panel.addView(label);

    // The reason in words underneath, so "Disconnected" is not the whole of what the user is told.
    TextView hint = activity.label(CallUi.endHint(call.endReason), 14);
    hint.setTextColor(BAR_DIM);
    hint.setGravity(Gravity.CENTER);
    hint.setVisibility(CallUi.endHint(call.endReason).isEmpty() ? View.GONE : View.VISIBLE);
    panel.addView(hint);

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

  /** The user asked for the call screen again, so end the chat dismissal and stand it up full.
   *
   *  <p>Called from the Activity's Return-to-call control.  Without it, returning to a call the user
   *  had left for the conversation would render nothing at all: render() holds the overlay down for
   *  as long as the dismissal stands, which is what stops the call screen rebuilding itself over
   *  the chat, so *something* has to lift it.  It used to be lifted by forgetOverlay() wiping the
   *  record during the rebuild, which meant Return only worked as a side effect of the very thing
   *  that broke the conversation control.
   *
   *  <p>Also un-collapses: asking for the call means asking for the call screen, not the bar. */
  static void returnToCall() {
    dismissedCallId = null;
    collapsed = false;
  }

  /** Drop the end-reason banner and the retained snapshot behind it. */
  static void dismiss(MainActivity activity, CallUi ui) {
    if (ui != null) ui.takeTerminal();
    hide(activity);
  }

  /** The collapsed call: a small floating bar the user can drag anywhere on the stage.
  *
  *  <p>Asking for it was that a minimised call sat exactly where the message box and its keyboard
  *  appear, so a call and typing could not both be used at once, and it covered the whole width of
  *  the screen for as long as the call ran. So it is sized to itself and free-floating, and dragging
  *  it aside is the point. It starts at the foot of the stage, which is where a minimised call was
  *  found least surprising, and stays wherever it was last put, including across restarts.
  *
  *  <p>Tapping the bar restores the full panel; hang-up stays one tap away without expanding. */
  private static void buildCollapsed(final MainActivity activity, CallUi ui, final CallSession call) {
    detach(activity);
    boundCallId = call.callId;
    boundTerminal = false;

    String peerName = peerNameOf(activity, call);
    boundKey = CallUi.overlayKey(call, peerName);

    LinearLayout bar = new LinearLayout(activity);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(activity.dp(16), activity.dp(8), activity.dp(12), activity.dp(8));
    bar.setBackgroundColor(BAR);

    LinearLayout texts = activity.column();
    TextView who = activity.label(peerName, 16);
    who.setTextColor(Color.WHITE);
    texts.addView(who);
    stateText = activity.label(CallUi.stateLabel(call), 13);
    stateText.setTextColor(BAR_DIM);
    // Same reason as the full screen's status line: this one carries the clock too, and it is
    // rewritten every second for as long as the call is minimised.
    texts.addView(stateText);
    detailText = activity.label(CallUi.detailLabel(call), 12);
    detailText.setTextColor(BAR_DIM);
    detailText.setVisibility(View.GONE);
    texts.addView(detailText);
    bar.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

    // Hang-up stays reachable without expanding, because the bar is what is left on screen.
    // Named like the disc on the full screen.  A bare "Hang up" gives a screen-reader user no idea
    // whose call they are about to end, and the bar is the only call screen left while minimised --
    // so this is the one control they have.
    Button hangup = activity.button("End call");
    hangup.setTextColor(Color.WHITE);
    hangup.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(211, 47, 47)));
    hangup.setContentDescription("End the call with " + peerName);
    hangup.setOnClickListener(v -> activity.runCallAction(ui::hangup, "Could not end the call."));
    bar.addView(hangup, new LinearLayout.LayoutParams(-2, activity.dp(48)));

    bar.setContentDescription("Minimized call with " + peerName + ". Tap to reopen, or drag to move.");
    // Tapping the bar has to take the overlay down before re-rendering.  Clearing collapsed on its
    // own was not enough: render() compares the call ID, the state, mute and route against what the
    // attached overlay was built for, and the bar happened to match all of them, so it decided
    // there was nothing to rebuild and left the bar exactly where it was.  The call kept running
    // and the user was left tapping a bar that opened onto itself -- there was no way back into the
    // call screen at all, and no way to tell that from a call that had frozen.
    bar.setOnClickListener(v -> { hide(activity); render(activity); });
    makeDraggable(activity, bar);

    android.widget.FrameLayout holder = new android.widget.FrameLayout(activity);
    // Sized to the bar and free-floating, not stretched across the foot of the stage.  As a
    // full-width strip it covered the message box and everything near it for as long as the call ran,
    // which is the same complaint as the full call screen letting taps through: the call was
    // standing on top of the conversation instead of beside it.  Dragging it aside is the point --
    // a minimised call has to be able to get out of the way of typing -- so it is no longer
    // anchored to the bottom edge at all.
    android.widget.FrameLayout.LayoutParams floating =
      new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
    // No margins: the position comes from setX/setY and the edge margin is applied by the clamp, so
    // having both would add the margin twice and let the bar sit that far past the right edge.
    floating.setMargins(0, 0, 0, 0);
    holder.addView(bar, floating);
    attach(activity, holder);

    // Position is applied after layout, because the bar has no measured size until then and
    // clamping before that would pin it to the top-left corner.
    bar.post(() -> placeCollapsed(activity, bar));
  }

  /** Let the minimised bar be dragged anywhere on the stage, and treat a tap on it as reopening.
   *
   *  <p>A {@link GestureDetector} rather than {@code setOnClickListener} alone, because the two
   *  gestures have to be told apart: the click listener fires for accessibility activation and
   *  programmatic clicks, the detector for a real finger.  Keeping both means a TalkBack user still
   *  gets "tap to reopen", and a touch user gets tap-to-reopen and drag-to-move from one control.
   *
   *  <p>The hang-up button is a child, so it takes its own touches first and dragging can never be
   *  mistaken for pressing it. */
  private static void makeDraggable(final MainActivity activity, final View bar) {
    final float[] downAt = new float[2];
    final float[] startAt = new float[2];
    android.view.GestureDetector gestures =
      new android.view.GestureDetector(activity, new android.view.GestureDetector.SimpleOnGestureListener() {
        @Override public boolean onDown(android.view.MotionEvent e) {
          downAt[0] = e.getRawX(); downAt[1] = e.getRawY();
          // Where the bar was when the finger landed, not where it has got to by the next event.
          // CallUi.draggedLeft/Top measure from here, so the bar tracks the finger exactly instead of
          // trailing it by however much the detector's own focus smoothing lost.
          startAt[0] = bar.getX(); startAt[1] = bar.getY();
          return true;
        }
        @Override public boolean onSingleTapUp(android.view.MotionEvent e) {
          hide(activity); render(activity);
          return true;
        }
        @Override public boolean onScroll(android.view.MotionEvent from, android.view.MotionEvent to,
                                          float dx, float dy) {
          // dx and dy are ignored on purpose: see CallUi.draggedLeft for why they add up short.
          if (to == null) return false;
          // Ignore the jitter of what was meant as a tap, so a tap that opens the call does not
          // also nudge the bar somewhere the user did not put it.
          if (!CallUi.isBarDrag(downAt[0], downAt[1], to.getRawX(), to.getRawY(), activity.dp(6))) return false;
          placeCollapsed(activity, bar,
            CallUi.draggedLeft(downAt[0], startAt[0], to.getRawX()),
            CallUi.draggedTop(downAt[1], startAt[1], to.getRawY()));
          return true;
        }
      });
    bar.setOnTouchListener((v, e) -> gestures.onTouchEvent(e));
  }

  /** Move the bar to a dragged position, pulled back inside the stage. */
  private static void placeCollapsed(MainActivity activity, View bar, float left, float top) {
    int margin = activity.dp(8);
    bar.setX(CallUi.clampBarLeft(left, bar.getWidth(), stageWidth(activity), margin));
    bar.setY(CallUi.clampBarTop(top, bar.getHeight(), stageHeight(activity), margin));
    collapsedLeft = bar.getX();
    collapsedTop = bar.getY();
    saveBarPlace(activity);
  }

  /** Put the bar back where the user last dragged it, or at the foot of the stage the first time. */
  private static void placeCollapsed(MainActivity activity, View bar) {
    float left = collapsedLeft;
    float top = collapsedTop;
    if (left < 0 || top < 0) {
      left = readBarPlace(activity, true);
      top = readBarPlace(activity, false);
    }
    if (left < 0 || top < 0) {
      // Never dragged: sit at the foot of the stage, which is where a minimised call was found
      // least surprising before it could be moved.
      left = activity.dp(8);
      top = Math.max(activity.dp(8), stageHeight(activity) - bar.getHeight() - activity.dp(8));
    }
    placeCollapsed(activity, bar, left, top);
  }

  /** Where the bar was left, remembered across calls and across restarts.
   *
   *  <p>A position the user chose once and loses on every cold start is a position they will have to
   *  choose again, which is the same work the dragging was meant to remove. Stored as -1 when never
   *  set, and clamped on the way back out rather than trusted, because the other phone or a rotation
   *  may have a smaller stage than the one it was dragged on. */
  private static float readBarPlace(MainActivity activity, boolean horizontal) {
    try {
      android.content.SharedPreferences p =
        activity.getSharedPreferences("lan_messenger_ui", android.content.Context.MODE_PRIVATE);
      return p.getFloat(horizontal ? "call_bar_x" : "call_bar_y", -1);
    } catch (Exception ignored) { return -1; }
  }

  private static void saveBarPlace(MainActivity activity) {
    try {
      activity.getSharedPreferences("lan_messenger_ui", android.content.Context.MODE_PRIVATE).edit()
        .putFloat("call_bar_x", collapsedLeft).putFloat("call_bar_y", collapsedTop).apply();
    } catch (Exception ignored) {}
  }

  private static int stageWidth(MainActivity activity) {
    return activity.stage == null ? 0 : activity.stage.getWidth();
  }

  private static int stageHeight(MainActivity activity) {
    return activity.stage == null ? 0 : activity.stage.getHeight();
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
    boundKey = null;
    boundTerminal = false;
    collapsed = false;
    dismissedCallId = null;
    announcedState = null;
  }

  /** Drop references to a view tree the Activity has already thrown away.  Called from frame()
   *  when the stage is rebuilt; without it a later render would try to remove a view from a stage
   *  that no longer contains it.
   *
   *  <p>It deliberately does <b>not</b> clear {@code collapsed} or {@code dismissedCallId}. Those
   *  record what the user asked for, not anything about the discarded tree, and clearing them here
   *  is what made the conversation control do nothing: the chat button records the dismissal and
   *  then calls showChat, which ends in frame(), which called this -- wiping the record before the
   *  next render could act on it, so the full call panel was rebuilt straight back over the
   *  conversation the user had just asked for. Clearing them belongs to {@link #hide}, which means
   *  "the call screen is intentionally not up".
   *
   *  <p>{@code announcedState} is cleared too, so a screen that comes back after a rebuild
   *  re-announces the state rather than staying silent because it already said it once. */
  static void forgetOverlay() {
    overlay = null;
    stateText = null; detailText = null; qualityText = null; routeText = null;
    boundCallId = null;
    boundKey = null;
    boundTerminal = false;
    lastQualityAnnounceMs = 0;
    announcedState = null;
  }

  /** Whether an overlay is currently attached. */
  static boolean isShowing() { return overlay != null; }

  /** Handle Back while a call is running.  Returns true when the overlay took the key.
   *
   *  <p>Back used to take the overlay down outright, leaving the call running with no full-screen
   *  sign of it: the user was dropped into a chat screen that looked exactly like the call had
   *  ended, which is the one impression a live call must never give.  Back now toggles — expand a
   *  minimised call, minimise a full one — so the call stays on screen in one form or the other for
   *  as long as it is running, and there is always a way back into it.
   *
   *  <p>A call that is deliberately off the screen, which is what opening the conversation does, is
   *  left alone: Back there has to keep navigating, or the user could not leave the chat at all. */
  static boolean backWhileLive(MainActivity activity) {
    if (overlay == null) return false;
    boolean expand = collapsed;
    // hide() detaches the overlay and clears every binding, collapsed included, so the shape is
    // chosen again afterwards and render() rebuilds from scratch rather than testing a matching
    // identity and deciding there is nothing to do -- which is what made the bar impossible to
    // expand from.
    hide(activity);
    collapsed = !expand;
    render(activity);
    return true;
  }

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
