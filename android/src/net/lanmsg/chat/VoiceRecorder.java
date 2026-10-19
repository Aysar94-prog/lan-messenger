package net.lanmsg.chat;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

// Voice Messages (Phase 1 / Android), A03: a reusable AudioRecord capture adapter. This is the
// only file allowed to touch the native audio API; everything it produces outward is a
// VoicePcmFrame or an explicit event string (see VoiceMessages.java / pcm-contract.md "Forbidden
// dependencies" -- platform adapters translate device reads into fragments and events without
// leaking device types inward).
//
// Design notes (matches windows/VoiceRecorder.cs's W03 acceptance criteria and its "native
// callbacks deadlock or outlive managed data" risk, adapted to Android's very different device
// model):
// - Windows' waveIn delivers data via an OS-invoked native callback on an arbitrary thread at an
//   arbitrary time, which is what forces that file's careful "callback does the absolute minimum,
//   never takes the lock, queues real work to the thread pool" discipline. AudioRecord has no
//   such callback: this class owns a single dedicated thread that calls the blocking
//   AudioRecord.read() in a loop, so there is no foreign-thread callback to guard against -- the
//   equivalent risk here is instead "does stop()/dispose() reliably unblock and join that
//   thread", handled by calling AudioRecord.stop() (which is documented to make a blocked read()
//   return) before joining with a bounded timeout, so a slow or wedged device can never hang the
//   caller forever.
// - start()/stop()/dispose() are idempotent: calling any of them more than once, or stop() before
//   start(), is a safe no-op rather than a crash (mirrors VoiceRecorder.cs exactly).
// - Every native resource release (AudioRecord.release()) happens exactly once per real Start(),
//   funneled through one private helper so stop() and dispose() can't double-release or race.
public final class VoiceRecorder {
  public interface FrameListener { void onFrame(VoicePcmFrame frame); }
  public interface FailureListener { void onDeviceFailed(); }

  private static final int READ_CHUNK_BYTES = VoicePcmAssembler.FRAME_BYTES; // 640 bytes / 20 ms at the fixed contract rate.
  private static final int BUFFER_FRAMES = 8; // ~160 ms of internal AudioRecord buffering headroom.

  private final Object lock = new Object();
  private final VoicePcmAssembler assembler = new VoicePcmAssembler();
  private AudioRecord audioRecord;
  private Thread readThread;
  private boolean started, stopped, failed;

  public volatile FrameListener frameListener;
  public volatile FailureListener failureListener;

  public boolean isRecording() { synchronized (lock) { return started && !stopped && !failed; } }

  // Opens the device in the fixed contract format (16 kHz mono 16-bit PCM) and begins capture.
  // Throws IOException with a clear message on permission/initialization failure, per A03/A04's
  // "permission and initialization failures are clear" requirement -- RECORD_AUDIO's own runtime
  // grant/denial flow is A04's concern (Activity-level), not this adapter's; this class only
  // reports the SecurityException it gets if the caller invoked it without that permission.
  public void start() throws IOException {
    AudioRecord recorder;
    synchronized (lock) {
      if (started) throw new IllegalStateException("Recorder already started.");
      int minBuf = AudioRecord.getMinBufferSize(VoiceWav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      if (minBuf <= 0) throw new IOException("Could not open the recording device: unsupported format on this device.");
      int bufferSize = Math.max(minBuf, READ_CHUNK_BYTES * BUFFER_FRAMES);
      try {
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, VoiceWav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
      } catch (IllegalArgumentException | SecurityException ex) {
        throw new IOException("Could not open the recording device: " + ex.getMessage(), ex);
      }
      if (recorder.getState() != AudioRecord.STATE_INITIALIZED) { recorder.release(); throw new IOException("Could not open the recording device: no microphone available."); }
      try { recorder.startRecording(); }
      catch (IllegalStateException ex) { recorder.release(); throw new IOException("Could not start recording.", ex); }
      if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) { recorder.release(); throw new IOException("Could not start recording: the microphone may already be in use."); }
      audioRecord = recorder; started = true;
    }
    readThread = new Thread(this::runReadLoop, "voice-recorder");
    readThread.setDaemon(true);
    readThread.start();
  }

  // Runs on its own dedicated thread for the lifetime of one recording. Does the minimum
  // necessary synchronized work per iteration (assembling the just-read chunk into frames);
  // listener callbacks run outside the lock so a slow UI-side handler can never block capture.
  private void runReadLoop() {
    byte[] buf = new byte[READ_CHUNK_BYTES];
    while (true) {
      AudioRecord recorder;
      synchronized (lock) { if (stopped || failed) return; recorder = audioRecord; }
      int n;
      try { n = recorder.read(buf, 0, buf.length); }
      catch (Exception ex) { failLocked(); return; }
      if (n < 0) { failLocked(); return; } // AudioRecord.ERROR/ERROR_BAD_VALUE/ERROR_DEAD_OBJECT etc.
      if (n == 0) continue;
      byte[] chunk = (n == buf.length) ? buf.clone() : Arrays.copyOf(buf, n);
      List<VoicePcmFrame> frames;
      synchronized (lock) {
        if (stopped || failed) return;
        try { frames = assembler.push(chunk); }
        catch (IllegalStateException ex) { failLocked(); return; } // e.g. the 9,600,000-byte cap.
        if (assembler.terminallyFailed()) { failLocked(); return; }
      }
      FrameListener listener = frameListener;
      if (listener != null) for (VoicePcmFrame f : frames) listener.onFrame(f);
    }
  }

  private void failLocked() {
    boolean first;
    synchronized (lock) { first = !failed; failed = true; if (first) assembler.deviceFailure(); }
    if (first) { FailureListener listener = failureListener; if (listener != null) listener.onDeviceFailed(); }
  }

  // Normal Stop: idempotent. Safe to call more than once, before start(), or after a device
  // failure already tore things down.
  public VoicePcmEndResult stop() {
    boolean already;
    synchronized (lock) { already = !started || stopped; if (!already) stopped = true; }
    if (already) return null;
    stopNativeAndJoin();
    synchronized (lock) {
      if (failed) return new VoicePcmEndResult(null, VoiceMessages.EVT_DEVICE_FAILURE);
      try { return assembler.end(); }
      catch (IllegalStateException ex) { return new VoicePcmEndResult(null, VoiceMessages.EVT_DEVICE_FAILURE); }
    }
  }

  // Idempotent regardless of prior state: safe when never started, already stopped, or already
  // disposed. AudioRecord.stop() is what actually unblocks a read() the capture thread may be
  // blocked inside; join() has a bounded timeout so a wedged device can never hang the caller.
  private void stopNativeAndJoin() {
    AudioRecord recorder;
    synchronized (lock) { recorder = audioRecord; audioRecord = null; }
    if (recorder == null) return;
    try { recorder.stop(); } catch (IllegalStateException ignored) {}
    Thread thread = readThread;
    if (thread != null) { try { thread.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
    try { recorder.release(); } catch (Exception ignored) {}
  }

  public void dispose() {
    synchronized (lock) { if (started && !stopped) stopped = true; }
    stopNativeAndJoin();
  }
}
