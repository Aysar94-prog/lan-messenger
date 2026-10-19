package net.lanmsg.chat;

import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

// Voice Messages (Phase 1 / Android), A07: Candidate/Fetching/Playable/Invalid marked
// content/Unavailable cards, per tests/voice_messages/validation-contract.md's Receiver state
// and fallback rules, reused for the SENDER's own sent voice message too -- see MainActivity's
// render() gate, which routes any message whose filename classifies as a voice Candidate through
// here regardless of `mine`. A sender's own message always already has the attachment locally
// right after sending, so classify() resolves straight to PLAYABLE/INVALID without ever touching
// the Candidate/Fetching/Unavailable retrieval states -- those only apply to a peer's recording.
//
// Play/seek are wired to the real VoicePlayer (A08) via VoicePlayback, the shared one-active-
// player controller, including a draggable seek bar (WhatsApp-style scrubbing, not fixed steps).
// Save/Export uses the existing destination-picker path (AttachmentFlow), a distinct, already-
// built action, not a playback feature.
final class VoiceCard {
  private VoiceCard() {}
  static final String CANDIDATE = "Candidate", FETCHING = "Fetching", PLAYABLE = "Playable", INVALID = "Invalid", UNAVAILABLE = "Unavailable";

  // Classification stays separate from content validation (contract.md): the caller-side gate
  // (VoiceMarker.classify) already guarantees the marker parses and its id matches before this
  // is ever reached, so a genuine Candidate always starts here; only a fully retrieved,
  // WAV-validated file becomes Playable, and only a Candidate whose retrieved bytes fail that
  // validation becomes Invalid marked content -- a mismatched or unparsed marker is never routed
  // here at all (it renders as an ordinary attachment from MainActivity directly).
  static String classify(PeerEngine e, PeerEngine.Message m) {
    if (e.hasAttachment(m)) {
      try { VoiceWavValidation v = VoiceWav.validate(e.readAttachment(m)); return v.pass ? PLAYABLE : INVALID; }
      catch (Exception ex) { return INVALID; }
    }
    if (e.downloading(m)) return FETCHING;
    // Not yet retrieved and not currently fetching. Unavailable is reserved for a message whose
    // conversation is definitively gone from the current list (contact forgotten or group left)
    // -- everything else, including "no peer online right now", stays a Candidate awaiting the
    // next automatic or manual retrieval attempt.
    boolean stillReachable = m.groupId.isEmpty() ? anyPeerMatches(e, m.from) : anyGroupMatches(e, m.groupId);
    return stillReachable ? CANDIDATE : UNAVAILABLE;
  }
  private static boolean anyGroupMatches(PeerEngine e, String groupId) { for (PeerEngine.Group g : e.groups()) if (g.id.equals(groupId)) return true; return false; }
  private static boolean anyPeerMatches(PeerEngine e, String peerId) { for (PeerEngine.Peer p : e.peers()) if (p.id.equals(peerId)) return true; return false; }

  // Only reached from the PLAYABLE branch, where classify() has already confirmed validation
  // passes -- re-validating here (rather than threading the VoiceWavValidation through) is a
  // deliberate, cheap defense-in-depth re-check, same reasoning as classify() itself re-checking
  // on every call instead of trusting a cached result.
  private static long readDurationMs(PeerEngine e, PeerEngine.Message m) {
    try { return VoiceWav.validate(e.readAttachment(m)).info.durationMs; } catch (Exception ex) { return 0; }
  }

  // Builds the card's voice-specific content into `card`; the caller still adds the caption text
  // afterward, same as any other attachment message.
  static void addVoiceCard(MainActivity activity, LinearLayout card, PeerEngine.Message m) {
    PeerEngine e = activity.engine(); if (e == null) return;
    String state = classify(e, m);
    LinearLayout row = new LinearLayout(activity); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
    switch (state) {
      case CANDIDATE: {
        row.addView(activity.label("Voice message", 15));
        Button retry = activity.button("Retrieve"); retry.setContentDescription("Retrieve this voice message");
        // Retry/Resume operate on the internal retrieval state only -- never a destination
        // picker, per contract.md's Receiver state and fallback item 6.
        retry.setOnClickListener(v -> new Thread(() -> {
          try { TransferManager.downloadAttachment(e, m); } catch (Exception ignored) {}
          activity.ui.post(() -> { activity.lastSignature = ""; activity.render(); });
        }, "lan-voice-retrieve").start());
        row.addView(retry);
        break;
      }
      case FETCHING: {
        long[] progress = activity.transferProgress.get(m.id);
        String pct = progress != null && progress[1] > 0 ? " " + (progress[0] * 100 / progress[1]) + "%" : "";
        TextView fetchingLabel = activity.label("Voice message · Retrieving…" + pct, 15);
        fetchingLabel.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        row.addView(fetchingLabel);
        break;
      }
      case PLAYABLE: {
        String playKey = "msg:" + m.from + "/" + m.id;
        long durationMs = readDurationMs(e, m);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout controls = new LinearLayout(activity); controls.setOrientation(LinearLayout.HORIZONTAL); controls.setGravity(Gravity.CENTER_VERTICAL);
        TextView durationLabel = activity.label(VoicePlayback.playbackText(activity, playKey, durationMs), 15);
        durationLabel.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        controls.addView(durationLabel);
        Button play = activity.button(playKey.equals(activity.activePlayerKey) && activity.activePlayer != null && activity.activePlayer.isPlaying() ? VoicePlayback.PAUSE_ICON : VoicePlayback.PLAY_ICON);
        play.setContentDescription("Play or pause this voice message");
        android.widget.SeekBar seekBar = VoicePlayback.buildSeekBar(activity, playKey);
        seekBar.setLayoutParams(new LinearLayout.LayoutParams(activity.dp(220), LinearLayout.LayoutParams.WRAP_CONTENT));
        Runnable refresh = () -> {
          durationLabel.setText(VoicePlayback.playbackText(activity, playKey, durationMs));
          play.setText(playKey.equals(activity.activePlayerKey) && activity.activePlayer != null && activity.activePlayer.isPlaying() ? VoicePlayback.PAUSE_ICON : VoicePlayback.PLAY_ICON);
          VoicePlayback.updateSeekBar(activity, playKey, seekBar, durationMs);
        };
        play.setOnClickListener(v -> VoicePlayback.togglePlayback(activity, playKey, () -> { try { return e.readAttachment(m); } catch (Exception ex) { throw new RuntimeException(ex); } }, refresh));
        controls.addView(play);
        Button save = activity.button("Save"); save.setContentDescription("Save this voice message to a file"); save.setOnClickListener(v -> AttachmentFlow.exportFile(activity, m)); controls.addView(save);
        row.addView(controls);
        row.addView(seekBar);
        refresh.run();
        break;
      }
      case INVALID: {
        // Falls back to the ordinary attachment treatment, backed by whatever copy is already
        // retrieved -- never a second fetch attempt for this reason alone.
        TextView name = activity.label(m.fileName + "  ·  " + MainActivity.formatSize(m.fileSize), 15);
        name.setOnClickListener(v -> { if (e.hasAttachment(m)) AttachmentFlow.fileAction(activity, m); });
        row.addView(name);
        boolean available = e.hasAttachment(m);
        Button open = activity.button(available ? "Open" : e.downloading(m) ? "Pause" : e.pendingDestination(m).isEmpty() ? "Download" : "Resume");
        open.setOnClickListener(v -> AttachmentFlow.fileAction(activity, m));
        row.addView(open);
        break;
      }
      case UNAVAILABLE: {
        TextView label = activity.label("Voice message · No longer available", 15); label.setTextColor(Color.rgb(112, 128, 144)); row.addView(label);
        break;
      }
    }
    // Wrapped in a HorizontalScrollView, matching MainActivity's composeActions row: a card is
    // narrower than the full screen (bounded by maxBubble), and the Playable row alone has a
    // duration label plus 4 buttons -- without this, the last one or two controls (typically
    // Save) can be laid out past the visible width and become unreachable, a real bug found
    // during manual device testing after A08 shipped this row without it.
    HorizontalScrollView scroll = new HorizontalScrollView(activity);
    scroll.setHorizontalScrollBarEnabled(false);
    scroll.addView(row);
    card.addView(scroll);
  }
}
