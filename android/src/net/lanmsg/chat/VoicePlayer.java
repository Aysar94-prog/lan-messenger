package net.lanmsg.chat;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import java.io.IOException;

// Voice Messages (Phase 1 / Android), A08: a reusable AudioTrack inline player. Mirrors
// VoiceRecorder.java (A03)'s safety construction: one dedicated thread owns all device I/O
// (here, writing sequential chunks to AudioTrack in streaming mode), idempotent play/pause/
// resume/stop/dispose, and a single funnel for native resource release.
//
// Seeking (contract.md's seven-step procedure, steps 5-7) needs no epoch-tagged-buffer scheme
// the way windows/VoicePlayer.cs's waveOut adapter does: there is no separate async completion
// callback to race against here, since every write is a synchronous blocking call on this
// class's own owned thread. seek() instead calls AudioTrack.pause()+flush() (documented to
// discard queued-but-not-yet-played audio) before updating the position, and the write loop
// discards a write's position-advance if `epoch` changed while that write() call was blocked --
// together these give the same "stale output invalidated" guarantee.
//
// The whole decrypted WAV lives in one bounded (<=9.6 MB) managed byte[] for the player's
// lifetime -- no plaintext playback file is ever written to disk (A08 acceptance).
//
// Audio focus (A08's own addition beyond Windows' W08, which has no equivalent system-wide audio
// session model): requests transient-exclusive focus on play/resume, releases it on stop/
// dispose, and pauses (never fails) on focus loss -- "Focus loss and lifecycle exit are safe"
// (A08 acceptance).
public final class VoicePlayer {
  public interface CompletedListener { void onCompleted(); }
  public interface FailureListener { void onDeviceFailed(); }
  public interface FocusLostListener { void onFocusLost(); }

  private static final int WRITE_CHUNK_BYTES = VoicePcmAssembler.FRAME_BYTES * 8; // ~160 ms per write.

  private final Object lock = new Object();
  private final byte[] wav;
  private final VoiceWavInfo info;
  private final AudioManager audioManager;
  private AudioTrack track;
  private AudioFocusRequest focusRequest;
  private Thread writeThread;
  private long playPosition; // absolute byte offset into `wav`, always within [info.dataOffset, dataEnd].
  private long epoch;        // bumped on every seek/stop; a write started under a stale epoch is discarded.
  private boolean started, playing, closed;

  public volatile CompletedListener completedListener;
  public volatile FailureListener failureListener;
  public volatile FocusLostListener focusLostListener;

  public VoicePlayer(Context context, byte[] wav, VoiceWavInfo info) {
    this.wav = wav; this.info = info; playPosition = info.dataOffset;
    audioManager = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
  }

  private long dataEnd() { return info.dataOffset + info.dataBytes; }

  public boolean isPlaying() { synchronized (lock) { return playing && !closed; } }
  public long positionNs() { synchronized (lock) { return (playPosition - info.dataOffset) / VoiceWav.BLOCK_ALIGN * 62500; } }

  public void play() throws IOException {
    synchronized (lock) {
      if (closed) throw new IllegalStateException("Player already disposed.");
      if (!started) { openLocked(); started = true; }
      if (!requestFocusLocked()) throw new IOException("Could not get audio playback focus.");
      if (playPosition >= dataEnd()) { playPosition = info.dataOffset; epoch++; }
      playing = true;
      lock.notifyAll();
    }
    track.play();
  }

  public void pause() {
    synchronized (lock) {
      if (track != null) track.pause();
      playing = false;
    }
    abandonFocus();
  }

  public void resume() throws IOException {
    synchronized (lock) {
      if (closed || track == null) return;
      if (!requestFocusLocked()) throw new IOException("Could not get audio playback focus.");
      // Without this, clicking Play again after a clip finishes silently does nothing: position
      // is still at dataEnd(), so the write loop immediately re-idles instead of restarting.
      // play() already had this reset; resume() (used by togglePlayback for the same active key,
      // which is exactly the "press Play again after it finished" case) needed it too, to let a
      // voice message be replayed any number of times.
      if (playPosition >= dataEnd()) {
        playPosition = info.dataOffset; epoch++;
        // A plain play() on a track that naturally drained (buffer underrun, never explicitly
        // stopped) doesn't reliably resume producing audio on every device -- confirmed on a
        // real device after the position-reset above alone wasn't enough. stop()+flush() forces
        // the track back to a clean, known state, exactly like seek()'s own pause+flush.
        //
        // This whole reset MUST finish, and play() MUST already have been called, before
        // notifyAll() below wakes the write thread -- otherwise the write thread can race ahead,
        // write a chunk into the track, and then have this very flush() wipe it out (or race
        // against this play() call), which is exactly what caused audio to start and then stop
        // immediately on a real device with the previous (racy) version of this fix.
        try { track.stop(); track.flush(); } catch (Exception ignored) {}
      }
      try { track.play(); } catch (Exception ex) { throw new IOException("Could not resume playback.", ex); }
      playing = true;
      lock.notifyAll();
    }
  }

  // Contract.md's seven-step seek procedure, steps 5-7: invalidates output already queued from
  // the old position (pause+flush, plus the epoch bump the write loop honors), resets playback
  // position, and reports the effective aligned position back to the caller. Steps 1-4 (clamp/
  // convert/resolve/align) are VoiceSeek.resolve's job (A01), reused here unchanged.
  public VoiceSeekResult seek(long requestedMs) {
    VoiceSeekResult result;
    synchronized (lock) {
      result = VoiceSeek.resolve(info, requestedMs);
      epoch++;
      if (track != null) { try { track.pause(); track.flush(); } catch (Exception ignored) {} }
      playPosition = result.alignedByte;
      if (result.state.equals("completed")) playing = false;
      lock.notifyAll();
    }
    if (track != null && playing) try { track.play(); } catch (Exception ignored) {}
    return result;
  }

  private void openLocked() throws IOException {
    int minBuf = AudioTrack.getMinBufferSize(VoiceWav.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
    if (minBuf <= 0) throw new IOException("Could not open the playback device: unsupported format on this device.");
    int bufferSize = Math.max(minBuf, WRITE_CHUNK_BYTES * 4);
    AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    AudioFormat format = new AudioFormat.Builder().setSampleRate(VoiceWav.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
    AudioTrack newTrack;
    try { newTrack = new AudioTrack(attrs, format, bufferSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE); }
    catch (IllegalArgumentException ex) { throw new IOException("Could not open the playback device: " + ex.getMessage(), ex); }
    if (newTrack.getState() != AudioTrack.STATE_INITIALIZED) { newTrack.release(); throw new IOException("Could not open the playback device."); }
    track = newTrack;
    writeThread = new Thread(this::runWriteLoop, "voice-player");
    writeThread.setDaemon(true);
    writeThread.start();
  }

  private boolean requestFocusLocked() {
    if (audioManager == null) return true; // never observed null on a real device; fail open rather than block playback.
    AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    AudioFocusRequest request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener(change -> {
          if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pause();
            FocusLostListener l = focusLostListener; if (l != null) l.onFocusLost();
          }
        }).build();
    focusRequest = request;
    return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
  }

  private void abandonFocus() {
    if (audioManager != null && focusRequest != null) try { audioManager.abandonAudioFocusRequest(focusRequest); } catch (Exception ignored) {}
  }

  // Runs on its own dedicated thread for the lifetime of the player. Blocks in
  // AudioTrack.write() (paced by the device's real playback rate, same shape as
  // VoiceRecorder.runReadLoop's blocking read()); pause()/seek()/stop() calling
  // AudioTrack.pause() from another thread is documented to make a blocked write() return
  // promptly, so this never needs its own timeout.
  private void runWriteLoop() {
    byte[] chunk = new byte[WRITE_CHUNK_BYTES];
    while (true) {
      long myEpoch; long pos;
      synchronized (lock) {
        if (closed) return;
        // The `playPosition >= dataEnd()` case here is a defensive edge (e.g. resume() called on
        // an already-finished player without an intervening play()/seek()) rather than the normal
        // completion path -- play()/seek() are the only two places that ever set playing=true with
        // a position at or past the end, and both reset the position first. Stopping silently here
        // (no onCompleted fire) is deliberate: onCompleted means "just finished writing the final
        // bytes", not "someone tried to resume a finished player".
        if (!playing || playPosition >= dataEnd()) { if (playPosition >= dataEnd()) playing = false; try { lock.wait(); } catch (InterruptedException ex) { return; } continue; }
        myEpoch = epoch; pos = playPosition;
      }
      int take = (int) Math.min(chunk.length, dataEnd() - pos);
      take -= take % VoiceWav.BLOCK_ALIGN;
      if (take <= 0) continue;
      System.arraycopy(wav, (int) pos, chunk, 0, take);
      int written;
      try { written = track.write(chunk, 0, take, AudioTrack.WRITE_BLOCKING); }
      catch (Exception ex) { failLocked(); return; }
      if (written < 0) { failLocked(); return; }
      boolean completedNow = false;
      synchronized (lock) {
        if (closed || epoch != myEpoch) continue; // a seek/stop landed mid-write; its own state already applies.
        playPosition += written;
        if (playing && playPosition >= dataEnd()) { playing = false; completedNow = true; }
      }
      if (completedNow) { CompletedListener l = completedListener; if (l != null) l.onCompleted(); }
    }
  }

  private void failLocked() {
    synchronized (lock) { playing = false; }
    FailureListener l = failureListener; if (l != null) l.onDeviceFailed();
  }

  // Idempotent: safe to call more than once, before play(), or after a device failure.
  public void stop() {
    synchronized (lock) {
      if (closed) return;
      epoch++; playing = false;
      if (track != null) { try { track.pause(); track.flush(); } catch (Exception ignored) {} }
      lock.notifyAll();
    }
    abandonFocus();
  }

  public void dispose() {
    synchronized (lock) {
      if (closed) return;
      closed = true; playing = false;
      lock.notifyAll();
    }
    if (writeThread != null) try { writeThread.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    if (track != null) { try { track.stop(); } catch (Exception ignored) {} try { track.release(); } catch (Exception ignored) {} track = null; }
    abandonFocus();
  }
}
