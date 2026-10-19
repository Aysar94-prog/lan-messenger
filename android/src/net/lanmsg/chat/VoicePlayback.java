package net.lanmsg.chat;

import android.widget.Toast;
import java.io.IOException;
import java.util.function.Supplier;

// Voice Messages (Phase 1 / Android), A08: the shared playback controller used by both the
// own-draft Preview row (VoiceUi.java) and a received Playable message's Play row
// (VoiceCard.java). Exactly one VoicePlayer exists at a time (contract.md Decision 6): starting
// playback for a different key always stops whatever was playing first. Mirrors
// windows/ChatWindowVoicePlayback.cs (W08), including its StopActivePlayer fix (see
// PLAN-VOICE-MESSAGES-WINDOWS.md's WT05 entry): the deactivated row's own refresh callback is
// invoked after stopping so its label doesn't keep showing a stale "Pause" state -- render()'s
// signature diff is keyed on message content, never playback state, so nothing else would ever
// correct it.
final class VoicePlayback {
  private VoicePlayback() {}

  // WhatsApp-style icon glyphs instead of text labels for the transport buttons.
  static final String PLAY_ICON = "▶", PAUSE_ICON = "⏸";

  static void stopActivePlayer(MainActivity activity) {
    if (activity.activePlayer == null) return;
    VoicePlayer p = activity.activePlayer;
    Runnable oldRefresh = activity.activePlayerUiRefresh;
    activity.activePlayer = null; activity.activePlayerKey = null; activity.activePlayerUiRefresh = null;
    p.dispose();
    // A05: the playback device is gone, so the claim goes with it. Guarded by the expected owner
    // so this cannot release a claim a voice call has since taken.
    AudioOwnership owner = AudioOwnership.of(activity.host);
    if (owner != null) owner.releaseIf(AudioOwnership.Owner.VOICE_PLAYBACK);
    if (oldRefresh != null) oldRefresh.run();
  }

  // Starts playback for `key`, or toggles Play/Pause if `key` is already the active player.
  // `loadWav` decrypts and reads the content; it only runs when starting a NEW key, not on
  // every toggle.
  static void togglePlayback(MainActivity activity, String key, Supplier<byte[]> loadWav, Runnable uiRefresh) {
    // A finished player (played all the way to the end) is deliberately NOT resumed in place --
    // reusing its AudioTrack past that point was confirmed unreliable on a real device even with
    // a careful stop()+flush()+play() reset (see VoicePlayer.isFinished()'s comment). Falling
    // through to the same "different key" path below, which builds a brand-new VoicePlayer, is
    // exactly what leaving and re-entering the conversation already did to make replay work.
    if (key.equals(activity.activePlayerKey) && activity.activePlayer != null && !activity.activePlayer.isFinished()) {
      VoicePlayer p = activity.activePlayer;
      try { if (p.isPlaying()) p.pause(); else p.resume(); } catch (Exception ex) { activity.problem(ex); }
      uiRefresh.run();
      return;
    }
    stopActivePlayer(activity);
    byte[] wav;
    try { wav = loadWav.get(); } catch (Exception ex) { activity.problem(ex); return; }
    VoiceWavValidation validation = VoiceWav.validate(wav);
    if (!validation.pass) { activity.problem(new IOException("This recording is no longer valid: " + validation.failureReason)); return; }
    VoicePlayer player = new VoicePlayer(activity, wav, validation.info);
    player.completedListener = () -> activity.ui.post(() -> { if (key.equals(activity.activePlayerKey)) uiRefresh.run(); });
    player.failureListener = () -> activity.ui.post(() -> { if (key.equals(activity.activePlayerKey)) { Toast.makeText(activity, "The playback device failed.", Toast.LENGTH_SHORT).show(); stopActivePlayer(activity); } });
    player.focusLostListener = () -> activity.ui.post(() -> { if (key.equals(activity.activePlayerKey)) uiRefresh.run(); });
    // A05: a ringing or connected call owns the audio device, so playback cannot start. This is a
    // refusal, not a queue: the user retries after the call, and the message stays unplayed.
    AudioOwnership owner = AudioOwnership.of(activity.host);
    if (owner != null && !owner.claimPlayback()) {
      try { player.dispose(); } catch (Exception ignored) {}
      Toast.makeText(activity, "Finish the call before playing voice messages.", Toast.LENGTH_SHORT).show();
      return;
    }
    try { player.play(); }
    catch (Exception ex) {
      if (owner != null) owner.releaseIf(AudioOwnership.Owner.VOICE_PLAYBACK);
      try { player.dispose(); } catch (Exception ignored) {} activity.problem(ex); return;
    }
    activity.activePlayer = player; activity.activePlayerKey = key; activity.activePlayerUiRefresh = uiRefresh;
    uiRefresh.run();
  }

  static void seekActivePlayer(MainActivity activity, String key, long deltaMs) {
    if (!key.equals(activity.activePlayerKey) || activity.activePlayer == null) return;
    long newMs = Math.max(0, activity.activePlayer.positionNs() / 1_000_000 + deltaMs);
    try { activity.activePlayer.seek(newMs); } catch (Exception ignored) {}
  }

  // A draggable WhatsApp-style scrubber, shared by VoiceCard's Playable row and VoiceUi's own-
  // draft preview row. VoicePlayer.seek(long) already takes an absolute position (contract.md's
  // seven-step procedure), so the bar's progress maps onto it directly -- no delta math needed.
  // The bar's own tag doubles as a "user is dragging" flag: updateSeekBar must not fight the
  // user's finger by snapping progress back to the live playback position on every tick while
  // they're mid-drag, so it no-ops whenever the tag is true.
  static android.widget.SeekBar buildSeekBar(MainActivity activity, String key) {
    android.widget.SeekBar bar = new android.widget.SeekBar(activity);
    bar.setMax(1);
    bar.setTag(Boolean.FALSE);
    bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
      public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {}
      public void onStartTrackingTouch(android.widget.SeekBar sb) { sb.setTag(Boolean.TRUE); }
      public void onStopTrackingTouch(android.widget.SeekBar sb) {
        sb.setTag(Boolean.FALSE);
        if (key.equals(activity.activePlayerKey) && activity.activePlayer != null) { try { activity.activePlayer.seek(sb.getProgress()); } catch (Exception ignored) {} }
      }
    });
    return bar;
  }

  static void updateSeekBar(MainActivity activity, String key, android.widget.SeekBar bar, long durationMs) {
    if (Boolean.TRUE.equals(bar.getTag())) return;
    bar.setMax((int) Math.max(1, durationMs));
    bar.setProgress(key.equals(activity.activePlayerKey) && activity.activePlayer != null ? (int) Math.min(durationMs, activity.activePlayer.positionNs() / 1_000_000) : 0);
  }

  static String playbackText(MainActivity activity, String key, long durationMs) {
    String total = formatClock(durationMs);
    if (key.equals(activity.activePlayerKey) && activity.activePlayer != null) {
      String pos = formatClock(activity.activePlayer.positionNs() / 1_000_000);
      return "Voice message · " + pos + " / " + total;
    }
    return "Voice message · " + total;
  }

  private static String formatClock(long ms) {
    long s = Math.max(0, ms / 1000);
    return s >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) : String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
  }
}
