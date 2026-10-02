package net.lanmsg.chat;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

// Messenger-style single-thumbnail rendering for an already-available photo or video
// attachment: no filename/size line, no button row -- the picture (or first video frame) is the
// whole card. A photo opens the existing fullscreen AttachmentFlow.previewImage on tap (which
// carries the download control). A video adds a centred play disc over its frame; a single tap
// plays it in place, a double tap opens the same fullscreen-with-download view as a photo.
// MainActivity keeps the ordinary generic file card (filename, size, Open/Download/Resume) for
// anything with no local preview yet -- that path is unchanged and this class is never reached
// for it.
final class MediaCard {
  private MediaCard() {}

  static void addImageCard(MainActivity activity, LinearLayout card, PeerEngine.Message m, Bitmap thumbnail, int maxBubble) {
    ImageView image = thumbnailView(activity, thumbnail, maxBubble);
    image.setContentDescription("Open " + m.fileName);
    image.setOnClickListener(v -> AttachmentFlow.previewImage(activity, m));
    card.addView(image);
  }

  static void addVideoCard(MainActivity activity, LinearLayout card, PeerEngine.Message m, int maxBubble) {
    PeerEngine e = activity.engine(); if (e == null) return;
    int fallbackHeight = activity.dp(180);
    FrameLayout holder = new FrameLayout(activity);
    Bitmap frame = videoThumbnail(activity, e, m);
    ImageView image = null;
    int height = fallbackHeight;
    if (frame != null) {
      image = thumbnailView(activity, frame, maxBubble);
      height = image.getLayoutParams().height;
      holder.addView(image);
    } else {
      holder.setBackground(activity.bg(Color.rgb(32, 32, 32)));
      holder.setLayoutParams(new LinearLayout.LayoutParams(maxBubble, height));
    }
    View playDisc = activity.circle("▶", Color.argb(170, 0, 0, 0), 56, 22);
    FrameLayout.LayoutParams discParams = new FrameLayout.LayoutParams(activity.dp(56), activity.dp(56), android.view.Gravity.CENTER);
    holder.addView(playDisc, discParams);
    holder.setContentDescription("Play " + m.fileName + ". Double tap to open and download.");
    final ImageView finalImage = image;
    final int finalHeight = height;

    // A time bar (elapsed/duration label + a draggable SeekBar) underneath the frame, WhatsApp/
    // Messenger-style -- the duration shows even before the first tap (extracted the same way the
    // thumbnail frame is, from the cached decrypted file), and the bar itself only accepts a drag
    // once a VideoView exists (toggleInline/the ticker below own the "what to seek" question).
    long durationMs = videoDurationMs(activity, e, m);
    LinearLayout column = new LinearLayout(activity); column.setOrientation(LinearLayout.VERTICAL);
    column.addView(holder);
    LinearLayout controls = new LinearLayout(activity); controls.setOrientation(LinearLayout.HORIZONTAL); controls.setGravity(android.view.Gravity.CENTER_VERTICAL);
    TextView timeLabel = activity.label(formatClock(0) + " / " + formatClock(durationMs), 12);
    SeekBar seekBar = new SeekBar(activity);
    seekBar.setMax((int) Math.max(1, durationMs));
    LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    timeParams.setMargins(0, 0, activity.dp(6), 0);
    controls.addView(timeLabel, timeParams);
    controls.addView(seekBar, seekParams);
    column.addView(controls);
    card.addView(column);

    // IDLE -> LOADING -> PLAYING <-> PAUSED, and PLAYING/PAUSED -> IDLE on natural completion or
    // error (torn down and rebuilt from scratch next time, same reasoning as the voice-message
    // AudioTrack fix recorded elsewhere in this app: never trust resuming a drained media object
    // in place). A single tap toggles play/pause once a VideoView exists; LOADING ignores a
    // second tap so impatient double-tapping during the decrypt-to-cache-file step can't start
    // two players. One mutable InlineVideo instance per card captures all of this per-holder
    // state across repeated taps on the same message.
    final InlineVideo state = new InlineVideo();
    state.durationMs = durationMs;
    seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) { if (fromUser) timeLabel.setText(formatClock(progress) + " / " + formatClock(durationMs)); }
      @Override public void onStartTrackingTouch(SeekBar sb) { state.dragging = true; }
      @Override public void onStopTrackingTouch(SeekBar sb) { state.dragging = false; if (state.video != null) { try { state.video.seekTo(sb.getProgress()); } catch (Exception ignored) {} } }
    });
    GestureDetector detector = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
      @Override public boolean onSingleTapConfirmed(MotionEvent ev) { toggleInline(activity, holder, playDisc, finalImage, m, maxBubble, finalHeight, state, timeLabel, seekBar); return true; }
      @Override public boolean onDoubleTap(MotionEvent ev) { AttachmentFlow.previewVideo(activity, m); return true; }
    });
    holder.setOnTouchListener((v, ev) -> { detector.onTouchEvent(ev); return true; });
  }

  private static final int IDLE = 0, LOADING = 1, PLAYING = 2, PAUSED = 3;
  private static final class InlineVideo { int phase = IDLE; VideoView video; long durationMs; boolean dragging; }

  // Polls the real playback position (VideoView has no position-changed callback, unlike
  // VoicePlayer's push model) every 300 ms while PLAYING, updating the label and bar unless the
  // user is actively dragging the bar. Self-terminating: it simply stops rescheduling itself the
  // moment the phase is no longer PLAYING (pause, completion, error, or the card being torn down
  // all naturally stop further ticks without needing an explicit cancellation handle).
  private static void tick(MainActivity activity, InlineVideo state, TextView timeLabel, SeekBar seekBar) {
    if (state.phase != PLAYING || state.video == null) return;
    if (!state.dragging) {
      int pos = state.video.getCurrentPosition();
      seekBar.setProgress(pos);
      timeLabel.setText(formatClock(pos) + " / " + formatClock(state.durationMs));
    }
    activity.ui.postDelayed(() -> tick(activity, state, timeLabel, seekBar), 300);
  }

  private static String formatClock(long ms) {
    long s = Math.max(0, ms / 1000);
    return s >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) : String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
  }

  private static ImageView thumbnailView(MainActivity activity, Bitmap thumbnail, int maxBubble) {
    ImageView image = new ImageView(activity);
    image.setImageBitmap(thumbnail);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    image.setAdjustViewBounds(false);
    int height = Math.max(activity.dp(150), Math.min(activity.dp(320), (int) ((long) maxBubble * thumbnail.getHeight() / Math.max(1, thumbnail.getWidth()))));
    image.setLayoutParams(new LinearLayout.LayoutParams(maxBubble, height));
    image.setBackground(activity.bg(Color.rgb(232, 236, 243)));
    image.setClipToOutline(true);
    return image;
  }

  // Decoding a video's first frame means decrypting the whole attachment to a private cache
  // file first -- MediaMetadataRetriever has no encrypted-bytes entry point -- then seeking.
  // Unlike inlineBitmap's image path this never holds the whole file in memory at once (it
  // streams straight to disk), so, per the user, it is deliberately NOT guarded by
  // THUMBNAIL_PREVIEW_CAP the way the image thumbnail is.
  static Bitmap videoThumbnail(MainActivity activity, PeerEngine e, PeerEngine.Message m) {
    if (!PeerEngine.isVideoAttachment(m) || !e.hasAttachment(m)) return null;
    String key = "video-thumb/" + m.from + "/" + m.id + "/" + m.fileHash;
    Bitmap cached = activity.thumbnailCache.get(key);
    if (cached != null && !cached.isRecycled()) return cached;
    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
    try {
      File decoded = decryptedVideoFile(activity, e, m);
      retriever.setDataSource(decoded.getAbsolutePath());
      Bitmap frame = retriever.getFrameAtTime(0);
      if (frame == null) return null;
      activity.thumbnailCache.put(key, frame);
      return frame;
    } catch (Exception ignored) { return null; }
    finally { try { retriever.release(); } catch (Exception ignored) {} }
  }

  // Reads the real duration from the same cached decrypted file the thumbnail/playback already
  // use -- cheap, since by the time this runs that file is either already on disk or about to be
  // decrypted anyway for the thumbnail.
  static long videoDurationMs(MainActivity activity, PeerEngine e, PeerEngine.Message m) {
    if (!PeerEngine.isVideoAttachment(m) || !e.hasAttachment(m)) return 0;
    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
    try {
      File decoded = decryptedVideoFile(activity, e, m);
      retriever.setDataSource(decoded.getAbsolutePath());
      String raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
      return raw == null ? 0 : Long.parseLong(raw);
    } catch (Exception ignored) { return 0; }
    finally { try { retriever.release(); } catch (Exception ignored) {} }
  }

  // One decrypted copy per message, reused by both the thumbnail extraction above and inline
  // playback below -- decrypting twice for the same tap-then-play sequence would double the
  // wait for no reason. Named by message id + a hash fragment so a stale file from a different
  // message with the same id (not expected, but cheap to guard) is never reused.
  static File decryptedVideoFile(MainActivity activity, PeerEngine e, PeerEngine.Message m) throws IOException {
    File file = new File(activity.getCacheDir(), "video-" + m.id + "-" + Math.abs(m.fileHash.hashCode()) + ".tmp");
    if (file.exists() && file.length() > 0) return file;
    File tmp = File.createTempFile("video-", ".part", activity.getCacheDir());
    try (OutputStream out = new FileOutputStream(tmp)) { e.readAttachmentStream(m, out, null); }
    if (!tmp.renameTo(file)) { tmp.delete(); if (!file.exists()) throw new IOException("Cannot prepare video"); }
    return file;
  }

  private static void toggleInline(MainActivity activity, FrameLayout holder, View playDisc, ImageView image, PeerEngine.Message m, int maxBubble, int height, InlineVideo state, TextView timeLabel, SeekBar seekBar) {
    switch (state.phase) {
      case PLAYING: state.video.pause(); state.phase = PAUSED; playDisc.setVisibility(View.VISIBLE); return;
      case PAUSED: state.video.start(); state.phase = PLAYING; playDisc.setVisibility(View.GONE); tick(activity, state, timeLabel, seekBar); return;
      case LOADING: return; // already on the way in from a first tap; ignore a second one
      default: break; // IDLE: fall through to build a fresh player below
    }
    PeerEngine e = activity.engine(); if (e == null) return;
    state.phase = LOADING;
    playDisc.setVisibility(View.GONE);
    VideoView video = new VideoView(activity);
    state.video = video;
    video.setLayoutParams(new FrameLayout.LayoutParams(maxBubble, height));
    holder.addView(video, 0);
    if (image != null) image.setVisibility(View.GONE);
    Runnable restore = () -> { state.phase = IDLE; state.video = null; video.setVisibility(View.GONE); if (image != null) image.setVisibility(View.VISIBLE); playDisc.setVisibility(View.VISIBLE); seekBar.setProgress(0); timeLabel.setText(formatClock(0) + " / " + formatClock(state.durationMs)); };
    new Thread(() -> {
      try {
        File decoded = decryptedVideoFile(activity, e, m);
        activity.ui.post(() -> {
          video.setVideoPath(decoded.getAbsolutePath());
          video.setOnPreparedListener(mp -> { if (state.phase == LOADING) { state.phase = PLAYING; tick(activity, state, timeLabel, seekBar); } });
          video.setOnCompletionListener(mp -> restore.run());
          video.setOnErrorListener((mp, what, extra) -> { restore.run(); activity.problem(new IOException("Cannot play this video")); return true; });
          video.start();
        });
      } catch (Exception error) { activity.ui.post(() -> { restore.run(); activity.problem(error); }); }
    }, "lan-video-inline").start();
  }
}
