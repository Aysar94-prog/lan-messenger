package net.lanmsg.chat;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.StatsObserver;
import org.webrtc.StatsReport;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Production WebRTC media adapter (io.github.webrtc-sdk:android:150.7871.01).
 *
 *  Threading: every org.webrtc PeerConnection / PeerConnectionFactory call must happen on the
 *  thread that created the factory. This class owns a dedicated HandlerThread and funnels every
 *  call through {@link #onSignalingThread}. The ICallMedia methods are synchronous, so each one
 *  posts its work and blocks on a latch; WebRTC's own callbacks (SdpObserver, PeerConnection.Observer,
 *  StatsObserver) arrive on that same thread and need no handoff.
 *
 *  The factory and audio device module are process-global and refcounted rather than created per
 *  call: building a native PeerConnectionFactory is expensive, and A04 calls for permanent service
 *  ownership. The first call creates it, the last call to finish releases it.
 */
public class WebRtcCallMedia implements ICallMedia {

  // ── Process-global native stack (refcounted) ───────────────────

  private static final Object INIT_LOCK = new Object();
  private static boolean nativeReady;
  private static PeerConnectionFactory sharedFactory;
  private static JavaAudioDeviceModule sharedAdm;
  private static AudioManager sharedAudioManager;
  private static int factoryRefs;
  private static int savedAudioMode = -1;

  private Context appContext;
  private HandlerThread signalingThread;
  private Handler signaling;

  private PeerConnection peerConnection;
  private AudioSource audioSource;
  private AudioTrack audioTrack;

  private volatile Listener listener;
  private volatile boolean disposed;
  private volatile boolean mediaStarted;
  private volatile boolean muted;

  // Candidates that arrived before the remote description was applied. addIceCandidate before
  // setRemoteDescription is rejected by the native layer, so they are queued and drained.
  private final List<IceCandidate> pendingCandidates = new ArrayList<>();
  private boolean remoteDescriptionSet;

  // Latest statistics sample, refreshed by the internal 1 s poll so getStats() never blocks.
  private volatile Stats latestStats = new Stats();
  private Runnable statsPoll;

  // SDP produced by the last successful createSdp(). Assigned on the signaling thread before the
  // runOnSignaling block returns, and read by the caller once that block completes.
  private volatile String lastSdp;

  // Listener callbacks are handed to this executor instead of running inline. org.webrtc
  // delivers PeerConnection.Observer callbacks on the signaling thread, so a listener that
  // blocks (writing a signaling frame to a socket, say) would stall the media thread and could
  // deadlock it against the caller waiting on runOnSignaling.
  private java.util.concurrent.ExecutorService callbackExecutor;
  private volatile boolean mediaReadySent;

  // ── Construction ───────────────────────────────────────────────

  private WebRtcCallMedia(Context context) {
    this.appContext = context.getApplicationContext();
  }

  /** Must be called from the service before any call media is created. */
  public static void install(Context context) {
    synchronized (INIT_LOCK) { sharedAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE); }
  }

  /** Bring the native stack up once and report whether real media works on this device.
   *  Loads libjingle_peerconnection_so and exercises a real audio source/track, so a missing
   *  .so, a wrong ABI, or a blocked AEC path shows up here rather than on the first call. */
  public static boolean probe(Context context) {
    long started = android.os.SystemClock.elapsedRealtime();
    WebRtcCallMedia media = null;
    try {
      install(context);
      media = new WebRtcCallMedia(context);
      media.initialize();
      android.util.Log.i(TAG, "WebRTC native stack OK in " + (android.os.SystemClock.elapsedRealtime() - started)
        + " ms; hwAEC=" + JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
        + " hwNS=" + JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported());
      return true;
    } catch (Throwable t) {
      // A missing or unloadable native library surfaces as UnsatisfiedLinkError, which is an
      // Error and not an Exception -- catching Throwable is deliberate.
      android.util.Log.e(TAG, "WebRTC native stack unavailable: " + t, t);
      return false;
    } finally {
      if (media != null) media.dispose();
    }
  }

  private static final String TAG = "LanCallMedia";

  @Override
  public void initialize() throws Exception {
    synchronized (INIT_LOCK) {
      if (!nativeReady) {
        // Load the native library and set up the process-wide WebRTC state. Done once.
        PeerConnectionFactory.initialize(
          PeerConnectionFactory.InitializationOptions.builder(appContext)
            .setEnableInternalTracer(false)
            .createInitializationOptions());
        nativeReady = true;
      }
    }

    signalingThread = new HandlerThread("webrtc-signaling", Process.THREAD_PRIORITY_URGENT_AUDIO);
    signalingThread.start();
    signaling = new Handler(signalingThread.getLooper());
    callbackExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "webrtc-callbacks");
      t.setDaemon(true);
      return t;
    });

    synchronized (INIT_LOCK) {
      if (sharedFactory == null) {
        if (sharedAdm == null) {
          // Hardware AEC/NS when the device has it; otherwise WebRTC's software processing.
          JavaAudioDeviceModule.Builder admBuilder = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .setUseLowLatency(true);
          sharedAdm = admBuilder.createAudioDeviceModule();
        }
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        options.disableEncryption = false;   // DTLS-SRTP is mandatory for calls
        options.disableNetworkMonitor = false;
        sharedFactory = PeerConnectionFactory.builder()
          .setOptions(options)
          .setAudioDeviceModule(sharedAdm)
          .createPeerConnectionFactory();
      }
      factoryRefs++;
    }

    // MediaConstraints' mandatory/optional lists are final references to mutable lists, so they are
    // populated via add() rather than assignment.
    MediaConstraints audioConstraints = new MediaConstraints();
    audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
    audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
    audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
    audioConstraints.optional.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
    audioConstraints.optional.add(new MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"));

    final AudioSource[] outSource = new AudioSource[1];
    final Exception[] failure = new Exception[1];
    runOnSignaling(() -> {
      try {
        outSource[0] = sharedFactory.createAudioSource(audioConstraints);
        audioSource = outSource[0];
        audioTrack = sharedFactory.createAudioTrack("lanmsg-audio-0", outSource[0]);
        audioTrack.setEnabled(true);
      } catch (Exception e) { failure[0] = e; }
    });
    if (failure[0] != null) throw new RuntimeException("Could not create the WebRTC audio source", failure[0]);
  }

  // ── SDP / ICE ──────────────────────────────────────────────────

  @Override
  public String createOffer() throws Exception {
    return createSdp(true);
  }

  @Override
  public String createAnswer(String remoteSdp) throws Exception {
    // The controller applies the remote offer via setRemoteDescription() before calling this, so
    // the remote description is already in place. Re-applying it would fail the native state
    // machine, so this only creates and applies the answer.
    if (!remoteDescriptionSet) applyRemoteDescription(remoteSdp);
    return createSdp(false);
  }

  private String createSdp(final boolean offer) throws Exception {
    ensurePeerConnection();
    final Exception[] failure = new Exception[1];
    runOnSignaling(() -> {
      MediaConstraints constraints = new MediaConstraints();
      // Offer only Opus + CN/telephone-event so the two platforms negotiate a common profile.
      constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
      constraints.mandatory.add(new MediaConstraints.KeyValuePair("googSrtpProtectionProfiles", "srtp-aes128-aescm"));
      SdpObserverImpl observer = new SdpObserverImpl(offer ? "createOffer" : "createAnswer");
      if (offer) peerConnection.createOffer(observer, constraints);
      else peerConnection.createAnswer(observer, constraints);
      try { observer.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
      if (observer.error != null) { failure[0] = new RuntimeException(observer.error); return; }
      if (observer.sdp == null) { failure[0] = new RuntimeException("the native layer returned no SDP"); return; }
      // The local description must be applied or ICE gathering never starts.
      SdpObserverImpl applyObserver = new SdpObserverImpl("setLocalDescription");
      SessionDescription.Type type = offer ? SessionDescription.Type.OFFER : SessionDescription.Type.ANSWER;
      peerConnection.setLocalDescription(applyObserver, new SessionDescription(type, observer.sdp));
      try { applyObserver.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
      if (applyObserver.error != null) failure[0] = new RuntimeException(applyObserver.error);
      lastSdp = observer.sdp;
    });
    if (failure[0] != null) throw new RuntimeException("WebRTC SDP negotiation failed", failure[0]);
    return lastSdp;
  }

  @Override
  public void setRemoteDescription(String sdp) throws Exception {
    if (sdp == null || sdp.isEmpty()) throw new IllegalArgumentException("Empty SDP");
    ensurePeerConnection();
    applyRemoteDescription(sdp);
  }

  private void applyRemoteDescription(final String sdp) throws Exception {
    // The controller calls this once per role, so the SDP body is the only signal for its type.
    // An offerer sets a=setup:actpass; an answerer sets a=setup:active or a=setup:passive.
    SessionDescription.Type type =
      (sdp.contains("a=setup:active") || sdp.contains("a=setup:passive"))
        ? SessionDescription.Type.ANSWER : SessionDescription.Type.OFFER;

    final Exception[] failure = new Exception[1];
    runOnSignaling(() -> {
      CountDownLatch latch = new CountDownLatch(1);
      SdpObserver observer = new SdpObserver() {
        @Override public void onCreateSuccess(SessionDescription s) {}
        @Override public void onCreateFailure(String error) { failure[0] = new RuntimeException("Remote SDP create failed: " + error); latch.countDown(); }
        @Override public void onSetSuccess() { latch.countDown(); }
        @Override public void onSetFailure(String error) { failure[0] = new RuntimeException("Remote SDP rejected: " + error); latch.countDown(); }
      };
      peerConnection.setRemoteDescription(observer, new SessionDescription(type, sdp));
      try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
      if (failure[0] == null) {
        remoteDescriptionSet = true;
        List<IceCandidate> drain = new ArrayList<>(pendingCandidates);
        pendingCandidates.clear();
        for (IceCandidate c : drain) peerConnection.addIceCandidate(c);
      }
    });
    if (failure[0] != null) throw new RuntimeException("WebRTC rejected the remote description", failure[0]);
  }

  @Override
  public void addIceCandidate(String candidate, String sdpMid, int sdpMLineIndex) throws Exception {
    if (candidate == null || candidate.isEmpty()) return;
    final IceCandidate ice = new IceCandidate(sdpMid, sdpMLineIndex, candidate);
    runOnSignaling(() -> {
      if (!remoteDescriptionSet) { pendingCandidates.add(ice); return; }
      // A rejected candidate is not fatal: another candidate pair may still connect.
      peerConnection.addIceCandidate(ice);
    });
  }

  // ── Media start/stop ───────────────────────────────────────────

  @Override
  public void startMedia() throws Exception {
    if (disposed || mediaStarted) return;
    setCommunicationMode(true);
    mediaStarted = true;
    startStatsPolling();
  }

  @Override
  public void setMute(boolean muted) throws Exception {
    this.muted = muted;
    runOnSignaling(() -> { if (audioTrack != null) audioTrack.setEnabled(!muted); });
  }

  @Override
  public void dispose() {
    if (disposed) return;
    disposed = true;
    stopStatsPolling();
    setCommunicationMode(false);
    if (signaling != null) {
      try {
        runOnSignaling(() -> {
          if (peerConnection != null) { peerConnection.close(); peerConnection = null; }
          if (audioTrack != null) { audioTrack.dispose(); audioTrack = null; }
          if (audioSource != null) { audioSource.dispose(); audioSource = null; }
        });
      } catch (Exception ignored) {
        // The signaling thread is already gone; the native objects die with the process anyway.
      }
      signalingThread.quitSafely();
    }
    java.util.concurrent.ExecutorService ex = callbackExecutor;
    callbackExecutor = null;
    if (ex != null) ex.shutdownNow();
    releaseFactory();
  }

  // ── Statistics (A07q: normalized, bounded polling) ─────────────

  private void startStatsPolling() {
    final long[] lastPoll = { 0 };
    statsPoll = new Runnable() {
      @Override public void run() {
        if (disposed) return;
        long now = android.os.SystemClock.elapsedRealtime();
        // Bounded: one collection per second, and the callback cannot outrun the next poll.
        if (now - lastPoll[0] < CallProtocol.QUALITY_SAMPLE_INTERVAL_MS) {
          signaling.postDelayed(this, CallProtocol.QUALITY_SAMPLE_INTERVAL_MS);
          return;
        }
        lastPoll[0] = now;
        try {
          peerConnection.getStats(new StatsObserver() {
            @Override public void onComplete(StatsReport[] reports) { latestStats = normalize(reports); }
          }, null);
        } catch (Throwable ignored) {
          // Stats are advisory; a failed collection leaves the previous sample in place.
        }
        signaling.postDelayed(this, CallProtocol.QUALITY_SAMPLE_INTERVAL_MS);
      }
    };
    signaling.postDelayed(statsPoll, CallProtocol.QUALITY_SAMPLE_INTERVAL_MS);
  }

  private void stopStatsPolling() {
    if (signaling != null && statsPoll != null) signaling.removeCallbacks(statsPoll);
    statsPoll = null;
  }

  /** Flatten WebRTC's name/value stat pairs into ICallMedia.Stats. Values arrive as strings, and
   *  a stat name may be absent entirely, so every field is parsed defensively. */
  static Stats normalize(StatsReport[] reports) {
    Stats s = new Stats();
    s.timestampMs = System.currentTimeMillis();
    if (reports == null || reports.length == 0) return s;
    long packetsReceived = 0, packetsLost = 0, rttMs = 0;
    double jitterMs = 0;
    boolean sawAny = false;
    for (StatsReport report : reports) {
      if (report == null || report.values == null) continue;
      for (StatsReport.Value value : report.values) {
        if (value == null || value.name == null) continue;
        switch (value.name) {
          case "packetsReceived": packetsReceived += parseLong(value.value); sawAny = true; break;
          case "packetsLost":     packetsLost += parseLong(value.value);     sawAny = true; break;
          case "jitter":          jitterMs = Math.max(jitterMs, parseDouble(value.value)); sawAny = true; break;
          case "roundTripTime":   rttMs = Math.max(rttMs, parseLong(value.value)); sawAny = true; break;
          default: break;
        }
      }
    }
    s.packetsReceived = packetsReceived;
    s.packetsLost = packetsLost;
    s.jitterMs = jitterMs;
    s.roundTripTimeMs = rttMs;
    s.valid = sawAny;
    return s;
  }

  private static long parseLong(String v) {
    if (v == null) return 0;
    try { return (long) Double.parseDouble(v.trim()); } catch (NumberFormatException e) { return 0; }
  }

  private static double parseDouble(String v) {
    if (v == null) return 0;
    try { return Double.parseDouble(v.trim()); } catch (NumberFormatException e) { return 0; }
  }

  @Override
  public Stats getStats() {
    Stats s = latestStats;
    s.timestampMs = System.currentTimeMillis();
    s.valid = s.valid && !disposed;
    return s;
  }

  @Override
  public void setListener(Listener listener) { this.listener = listener; }

  /** Delivers a listener callback off the WebRTC signaling thread. */
  private void post(Runnable work) {
    java.util.concurrent.ExecutorService ex = callbackExecutor;
    if (ex == null || ex.isShutdown()) return;
    try { ex.execute(work); } catch (Exception ignored) {}
  }

  private void postMediaReady() {
    // Only the first transition matters; the controller moves to Connected once.
    if (mediaReadySent) return;
    mediaReadySent = true;
    post(() -> { Listener l = listener; if (l != null) l.onMediaReady(); });
  }

  private void postError(String message) {
    post(() -> { Listener l = listener; if (l != null) l.onError(message); });
  }

  // ── Internals ──────────────────────────────────────────────────

  /** SdpObserver that latches its own completion so the caller can block for the result. Declared
   *  as a named class because the await() helper is invisible through the SdpObserver interface. */
  private static final class SdpObserverImpl implements SdpObserver {
    private final CountDownLatch latch = new CountDownLatch(1);
    private final String label;
    String sdp;
    String error;

    SdpObserverImpl(String label) { this.label = label; }

    @Override public void onCreateSuccess(SessionDescription description) {
      android.util.Log.i(TAG, label + " onCreateSuccess (" + (description == null ? 0 : description.description.length()) + " bytes)");
      sdp = description.description; latch.countDown();
    }
    @Override public void onCreateFailure(String reason) {
      android.util.Log.e(TAG, label + " onCreateFailure: " + reason);
      error = "create: " + reason; latch.countDown();
    }
    @Override public void onSetSuccess() {
      android.util.Log.i(TAG, label + " onSetSuccess");
      latch.countDown();
    }
    @Override public void onSetFailure(String reason) {
      android.util.Log.e(TAG, label + " onSetFailure: " + reason);
      error = "set: " + reason; latch.countDown();
    }

    boolean await() throws InterruptedException { return latch.await(5, TimeUnit.SECONDS); }
  }

  /** Applies a local description and blocks until the native layer accepts it. */
  private void applyLocalDescription(SessionDescription.Type type, String sdp) throws Exception {
    final Exception[] failure = new Exception[1];
    runOnSignaling(() -> {
      SdpObserverImpl observer = new SdpObserverImpl("setRemoteDescription");
      peerConnection.setLocalDescription(observer, new SessionDescription(type, sdp));
      try { observer.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
      if (observer.error != null) failure[0] = new RuntimeException("Local SDP rejected: " + observer.error);
    });
    if (failure[0] != null) throw new RuntimeException("WebRTC could not apply the local description", failure[0]);
  }

  private void ensurePeerConnection() throws Exception {
    if (peerConnection != null || disposed) return;
    final Exception[] failure = new Exception[1];
    runOnSignaling(() -> {
      try {
        // LAN only: no STUN, no TURN. Host candidates on both ends are enough for a shared
        // network, and keeping the list empty avoids any outbound dependency.
        PeerConnection.RTCConfiguration config =
          new PeerConnection.RTCConfiguration(Collections.<PeerConnection.IceServer>emptyList());
        // Gather TCP host candidates too, so a LAN that blocks UDP still connects.
        config.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        peerConnection = sharedFactory.createPeerConnection(config, new PeerConnection.Observer() {
          @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
          @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
            if (s == PeerConnection.IceConnectionState.FAILED || s == PeerConnection.IceConnectionState.CLOSED) {
              postError("The call connection was lost (" + s + ").");
            } else if (s == PeerConnection.IceConnectionState.CONNECTED
                    || s == PeerConnection.IceConnectionState.COMPLETED) {
              postMediaReady();
            }
          }
          @Override public void onIceConnectionReceivingChange(boolean receiving) {}
          @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
          @Override public void onIceCandidate(IceCandidate candidate) {
            if (candidate == null) return;   // null signals end-of-candidates
            final String sdp = candidate.sdp, mid = candidate.sdpMid;
            final int idx = candidate.sdpMLineIndex;
            post(() -> { Listener l = listener; if (l != null) l.onIceCandidate(sdp, mid, idx); });
          }
          @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
          @Override public void onAddStream(org.webrtc.MediaStream s) {}
          @Override public void onRemoveStream(org.webrtc.MediaStream s) {}
          @Override public void onDataChannel(org.webrtc.DataChannel channel) {}
          @Override public void onRenegotiationNeeded() {}
          @Override public void onAddTrack(org.webrtc.RtpReceiver r, org.webrtc.MediaStream[] streams) {}
          @Override public void onTrack(org.webrtc.RtpTransceiver t) { postMediaReady(); }
        });

        MediaConstraints recv = new MediaConstraints();
        recv.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        // The null stream id list means "single default stream".
        peerConnection.addTrack(audioTrack, Collections.<String>emptyList());
      } catch (Exception e) { failure[0] = e; }
    });
    if (failure[0] != null) throw failure[0];
  }

  /** A07: put the device in communication mode so audio routes like a call, and restore the
   *  previous mode when the last call ends. */
  private void setCommunicationMode(boolean active) {
    AudioManager am = sharedAudioManager;
    if (am == null) return;
    try {
      synchronized (INIT_LOCK) {
        if (active) {
          if (savedAudioMode == -1) savedAudioMode = am.getMode();
          if (savedAudioMode != AudioManager.MODE_IN_COMMUNICATION) am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        } else {
          if (savedAudioMode != -1) {
            if (am.getMode() == AudioManager.MODE_IN_COMMUNICATION) am.setMode(savedAudioMode);
            savedAudioMode = -1;
          }
        }
      }
    } catch (Exception ignored) {
      // Some devices restrict mode changes while another app holds audio focus.
    }
  }

  private void releaseFactory() {
    synchronized (INIT_LOCK) {
      factoryRefs--;
      if (factoryRefs > 0) return;
      factoryRefs = 0;
      if (sharedFactory != null) { try { sharedFactory.dispose(); } catch (Throwable ignored) {} sharedFactory = null; }
      if (sharedAdm != null) { try { sharedAdm.release(); } catch (Throwable ignored) {} sharedAdm = null; }
    }
  }

  private void runOnSignaling(Runnable work) throws Exception {
    if (disposed && signaling == null) throw new IllegalStateException("Media already disposed");
    if (signaling == null) throw new IllegalStateException("Media not initialized");
    if (Thread.currentThread() == signalingThread) { work.run(); return; }
    final CountDownLatch latch = new CountDownLatch(1);
    final Exception[] failure = new Exception[1];
    if (!signaling.post(() -> {
      try { work.run(); } catch (Throwable t) { failure[0] = new RuntimeException(t); } finally { latch.countDown(); }
    })) throw new IllegalStateException("The WebRTC signaling thread is gone");
    if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("The WebRTC signaling thread did not respond");
    if (failure[0] != null) throw failure[0];
  }

  // ── Factory ────────────────────────────────────────────────────

  public static class Factory implements ICallMedia.Factory {
    private final Context context;
    public Factory(Context context) { this.context = context; }
    @Override public ICallMedia create() throws Exception {
      WebRtcCallMedia media = new WebRtcCallMedia(context);
      try { media.initialize(); }
      catch (Exception e) { media.dispose(); throw e; }
      return media;
    }
  }
}
