package net.lanmsg.chat;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

// Voice Messages (Phase 1 / Android), A04: Record/Stop/Preview/Delete/Send UI and the
// lifecycle-safe-stop wiring (see MainActivity: showChat/showPeople conversation-switch,
// onPause backgrounding, onDestroy shutdown, the 1 s tick, Offline detection via host.state).
// Reuses the existing attachmentDraft panel -- a pending file attachment and an active/pending
// voice draft are mutually exclusive composition states, so sharing one panel is a deliberate
// simplification matching windows/ChatWindowVoice.cs (W04) exactly, not an oversight.
//
// All device/file I/O (VoiceRecorder.stop()'s AudioRecord.stop()+join(), VoiceDraftWriter's
// encryption, PeerEngine.finalizeVoiceDraft's re-read+validate) is blocking, so unlike Windows'
// `async void StopVoiceRecording` (which can await inline on the UI thread), every stop/finalize
// path here runs on a background thread and posts back to activity.ui -- the same pattern
// MainActivity.java already uses for Send and every attachment operation.
final class VoiceUi {
  private VoiceUi() {}
  static final long MAX_RECORDING_SECONDS = 300;
  static final int RECORD_AUDIO_REQUEST = 2;

  static void startVoiceRecording(MainActivity activity) {
    if (activity.selected == null || activity.recordingDraftId != null) return;
    if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, RECORD_AUDIO_REQUEST);
      return;
    }
    PeerEngine e = activity.engine(); if (e == null) return;
    String target = activity.selected; boolean isGroup = false;
    for (PeerEngine.Group g : e.groups()) if (g.id.equals(target)) isGroup = true;
    String draftId;
    try { draftId = e.createVoiceDraft(target, isGroup); } catch (Exception ex) { activity.problem(ex); return; }
    VoiceDraftWriter writer;
    try { writer = e.openVoiceDraftWriter(draftId); }
    catch (Exception ex) { try { e.deleteVoiceDraft(draftId); } catch (Exception ignored) {} activity.problem(ex); return; }
    VoiceRecorder recorder = new VoiceRecorder();
    activity.recordingDraftId = draftId; activity.recordingConversation = target;
    activity.activeWriter = writer; activity.activeRecorder = recorder;
    activity.recordingStartedAtMs = System.currentTimeMillis(); activity.recordingStopping = false;
    recorder.frameListener = frame -> { VoiceDraftWriter w = activity.activeWriter; if (w != null) safeWriteFrame(activity, w, frame.payload); };
    recorder.failureListener = () -> activity.ui.post(() -> failVoiceRecording(activity, "The microphone was disconnected or failed."));
    try { recorder.start(); }
    catch (Exception ex) {
      activity.recordingDraftId = null; activity.recordingConversation = null; activity.activeWriter = null; activity.activeRecorder = null;
      try { writer.abort(); } catch (Exception ignored) {}
      try { e.deleteVoiceDraft(draftId); } catch (Exception ignored) {}
      activity.problem(ex); return;
    }
    renderVoicePanel(activity);
  }

  // A write failure mid-recording (disk full, etc.) is exactly the same as a device failure from
  // the recorder's point of view: stop and diagnose, never leave a half-written draft sitting in
  // Recording state. Runs on the recorder's own capture thread (frameListener callback), so the
  // failure path posts back to the UI thread itself.
  private static void safeWriteFrame(MainActivity activity, VoiceDraftWriter writer, byte[] pcm) {
    try { writer.writeFrame(pcm); }
    catch (Exception ex) { activity.ui.post(() -> failVoiceRecording(activity, "The recording could not be written to disk.")); }
  }

  // Explicit Stop, or any required safe-stop trigger (conversation exit/switch, backgrounding,
  // Offline transition, shutdown, device failure, duration limit). Always finalizes rather than
  // silently discarding -- the user still gets Preview/Delete on the result, or a clear "could
  // not be saved" message if finalization itself fails.
  static void stopVoiceRecording(MainActivity activity) {
    if (activity.recordingDraftId == null || activity.recordingStopping) return;
    activity.recordingStopping = true;
    String draftId = activity.recordingDraftId; VoiceRecorder recorder = activity.activeRecorder; VoiceDraftWriter writer = activity.activeWriter;
    activity.recordingDraftId = null; activity.recordingConversation = null; activity.activeRecorder = null; activity.activeWriter = null;
    PeerEngine e = activity.engine();
    new Thread(() -> {
      Exception failure = null;
      try {
        VoicePcmEndResult result = recorder != null ? recorder.stop() : null;
        if (writer != null) {
          if (result != null && VoiceMessages.EVT_DEVICE_FAILURE.equals(result.event)) {
            writer.abort();
            if (e != null) e.invalidateVoiceDraft(draftId);
          } else {
            if (result != null && result.finalFrame != null) writer.writeFrame(result.finalFrame.payload);
            writer.finish();
            if (e != null) {
              VoiceWavValidation validation = e.finalizeVoiceDraft(draftId);
              if (!validation.pass) {
                activity.ui.post(() -> { if (!activity.isFinishing() && !activity.isDestroyed())
                  new AlertDialog.Builder(activity).setTitle("Recording invalid").setMessage("This recording could not be saved: " + validation.failureReason).setPositiveButton("Close", null).show(); });
              }
            }
          }
        }
        if (recorder != null) recorder.dispose();
      } catch (Exception ex) {
        failure = ex;
        try { if (writer != null) writer.abort(); } catch (Exception ignored) {}
        try { if (recorder != null) recorder.dispose(); } catch (Exception ignored) {}
        if (e != null) e.invalidateVoiceDraft(draftId);
      }
      final Exception finalFailure = failure;
      activity.ui.post(() -> {
        activity.recordingStopping = false;
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (finalFailure != null) activity.problem(finalFailure);
        renderVoicePanel(activity); activity.lastSignature = ""; activity.render();
      });
    }, "lan-voice-stop").start();
  }

  static void failVoiceRecording(MainActivity activity, String message) {
    if (activity.recordingDraftId == null) return;
    if (!activity.isFinishing() && !activity.isDestroyed()) Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    stopVoiceRecording(activity);
  }

  // Called from MainActivity's existing 1 s tick: enforces the five-minute limit and stops
  // recording if the app has gone Offline, without a dedicated timer of its own. host.state is
  // the service-owned engine's connection state ("Online"/"Starting"/"Offline") -- the Android
  // equivalent of Windows' engine.Running, which W03/W04 force-stop on identically.
  static void tickVoiceRecording(MainActivity activity) {
    if (activity.recordingDraftId == null) return;
    boolean offline = activity.host == null || !"Online".equals(activity.host.state);
    if (offline) { stopVoiceRecording(activity); return; }
    if ((System.currentTimeMillis() - activity.recordingStartedAtMs) / 1000 >= MAX_RECORDING_SECONDS) { stopVoiceRecording(activity); return; }
    if (activity.recordingConversation != null && activity.recordingConversation.equals(activity.selected)) renderVoicePanel(activity);
  }

  static void sendVoiceDraftClicked(MainActivity activity, String draftId) {
    PeerEngine e = activity.engine(); if (e == null) return;
    PeerEngine.VoiceDraft draft = e.getVoiceDraft(draftId);
    String caption = draft != null && activity.selected != null && activity.selected.equals(draft.conversationId) && activity.composer != null ? activity.composer.getText().toString() : "";
    new Thread(() -> {
      try {
        e.sendVoiceDraft(draftId, caption);
        activity.ui.post(() -> {
          if (activity.selected != null && activity.composer != null) { activity.composer.setText(""); activity.drafts.remove(activity.selected); }
          activity.lastVoicePanel = ""; renderVoicePanel(activity); activity.lastSignature = ""; activity.render();
        });
      } catch (Exception ex) { activity.ui.post(() -> { if (!activity.isFinishing() && !activity.isDestroyed()) activity.problem(ex); }); }
    }, "lan-voice-send").start();
  }

  static void deleteVoiceDraftClicked(MainActivity activity, String draftId) {
    new AlertDialog.Builder(activity).setTitle("Delete recording?").setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) -> {
      PeerEngine e = activity.engine(); if (e == null) return;
      try { e.deleteVoiceDraft(draftId); } catch (Exception ex) { activity.problem(ex); }
      activity.lastVoicePanel = ""; renderVoicePanel(activity);
    }).show();
  }

  // Rebuilds attachmentDraft's contents for the recording/pending-draft state. A pending file
  // attachment (pendingAttachmentUri) always takes priority, matching how it already owns this
  // panel. Play/±10s preview of a finalized draft is a stub pending A08 (AudioTrack player), same
  // scope split as Windows' W04/W08 -- the draft itself is unaffected, still fully Delete/Sendable.
  static void renderVoicePanel(MainActivity activity) {
    if (activity.attachmentDraft == null) return;
    if (activity.pendingAttachmentUri != null) return; // AttachmentFlow.renderPendingAttachment owns the panel here.
    PeerEngine e = activity.engine();
    boolean recordingHere = activity.recordingConversation != null && activity.recordingConversation.equals(activity.selected);
    PeerEngine.VoiceDraft draft = null;
    if (!recordingHere && e != null && activity.selected != null) {
      List<PeerEngine.VoiceDraft> drafts = e.voiceDraftsFor(activity.selected);
      for (int i = drafts.size() - 1; i >= 0; i--) if (drafts.get(i).state.equals(PeerEngine.VoiceDraft.FINALIZED)) { draft = drafts.get(i); break; }
    }
    String signature = recordingHere ? "rec:" + activity.recordingDraftId : draft != null ? "draft:" + draft.id + ":" + draft.state : "none";
    if (signature.equals(activity.lastVoicePanel) && !recordingHere) return;
    activity.lastVoicePanel = signature;
    activity.releaseImages(activity.attachmentDraft); activity.attachmentDraft.removeAllViews();
    if (recordingHere) {
      activity.attachmentDraft.setPadding(activity.dp(10), activity.dp(6), activity.dp(10), activity.dp(6));
      activity.attachmentDraft.setBackground(activity.bg(Color.rgb(255, 235, 235)));
      activity.attachmentDraft.addView(activity.label(formatElapsed(System.currentTimeMillis() - activity.recordingStartedAtMs), 15));
      Button stop = activity.button("Stop"); stop.setOnClickListener(v -> stopVoiceRecording(activity)); activity.attachmentDraft.addView(stop);
    } else if (draft != null) {
      activity.attachmentDraft.setPadding(activity.dp(10), activity.dp(6), activity.dp(10), activity.dp(6));
      activity.attachmentDraft.setBackground(activity.bg(Color.rgb(235, 240, 250)));
      boolean sendable = e != null && e.voiceDraftSendable(draft.id);
      String playKey = "draft:" + draft.id; final String dId = draft.id; final long durationMs = draft.durationMs;
      android.widget.TextView durationLabel = activity.label(VoicePlayback.playbackText(activity, playKey, durationMs) + (sendable ? "" : "  ·  This conversation can no longer receive messages"), 14);
      activity.attachmentDraft.addView(durationLabel);
      LinearLayout row = new LinearLayout(activity); row.setOrientation(LinearLayout.HORIZONTAL);
      Button play = activity.button(playKey.equals(activity.activePlayerKey) && activity.activePlayer != null && activity.activePlayer.isPlaying() ? "Pause" : "Play");
      play.setContentDescription("Play or pause this recording");
      Runnable refresh = () -> {
        durationLabel.setText(VoicePlayback.playbackText(activity, playKey, durationMs) + (sendable ? "" : "  ·  This conversation can no longer receive messages"));
        play.setText(playKey.equals(activity.activePlayerKey) && activity.activePlayer != null && activity.activePlayer.isPlaying() ? "Pause" : "Play");
      };
      play.setOnClickListener(v -> { PeerEngine engine = activity.engine(); if (engine == null) return; VoicePlayback.togglePlayback(activity, playKey, () -> { try { return engine.readVoiceDraftWav(dId); } catch (Exception ex) { throw new RuntimeException(ex); } }, refresh); });
      row.addView(play);
      Button back = activity.button("-10s"); back.setContentDescription("Rewind 10 seconds"); back.setOnClickListener(v -> { VoicePlayback.seekActivePlayer(activity, playKey, -10000); refresh.run(); }); row.addView(back);
      Button fwd = activity.button("+10s"); fwd.setContentDescription("Forward 10 seconds"); fwd.setOnClickListener(v -> { VoicePlayback.seekActivePlayer(activity, playKey, 10000); refresh.run(); }); row.addView(fwd);
      Button delete = activity.button("Delete"); delete.setOnClickListener(v -> deleteVoiceDraftClicked(activity, dId)); row.addView(delete);
      if (sendable) { Button send = activity.button("Send"); send.setOnClickListener(v -> sendVoiceDraftClicked(activity, dId)); row.addView(send); }
      activity.attachmentDraft.addView(row);
    }
  }

  static String formatElapsed(long ms) { return "Recording…  " + formatClock(ms); }
  private static String formatClock(long ms) {
    long s = Math.max(0, ms / 1000);
    return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
  }

  // Called from MainActivity.onRequestPermissionsResult when RECORD_AUDIO is denied.
  // shouldShowRequestPermissionRationale returns false both before the very first request and
  // after a permanent ("don't ask again") denial; since this is only ever reached after we've
  // already requested once, false here means permanent denial, and Settings is the only way out.
  static void permissionDenied(MainActivity activity) {
    if (!activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
      new AlertDialog.Builder(activity).setTitle("Microphone permission needed")
        .setMessage("Voice messages need microphone access. Enable it for LAN Messenger in Settings.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Open Settings", (d, w) -> { try { activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.getPackageName(), null))); } catch (Exception ignored) {} })
        .show();
    } else Toast.makeText(activity, "Microphone permission is required to record a voice message.", Toast.LENGTH_LONG).show();
  }
}
