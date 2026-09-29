package net.lanmsg.chat;

import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

// Voice Messages (Phase 1 / Android), A07: Candidate/Fetching/Playable/Invalid marked
// content/Unavailable receiver cards, per tests/voice_messages/validation-contract.md's Receiver
// state and fallback rules. Called only for received (non-mine) messages whose filename
// classifies as a genuine voice Candidate -- see MainActivity's render() gate, mirroring
// windows/ChatWindowVoiceCard.cs (W07) and its MessageCard gate exactly.
//
// Play/seek/Save-via-playback are a stub here (Toast "not implemented yet") pending A08
// (AudioTrack player) -- this matches Windows' *original* W07 scope (before its later W08
// commit wired the real player into the same file), not the current post-W08 state of that
// file. Save/Export still works via the existing destination-picker path (AttachmentFlow),
// since that's a distinct, already-built action, not a playback feature.
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
        row.addView(activity.label("Voice message · Retrieving…" + pct, 15));
        break;
      }
      case PLAYABLE: {
        row.addView(activity.label("Voice message", 15));
        Button play = activity.button("Play"); play.setOnClickListener(v -> Toast.makeText(activity, "Playback is not implemented yet.", Toast.LENGTH_SHORT).show()); row.addView(play);
        Button save = activity.button("Save"); save.setOnClickListener(v -> AttachmentFlow.exportFile(activity, m)); row.addView(save);
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
    card.addView(row);
  }
}
