package net.lanmsg.chat;

/** Media adapter interface.  A fake implementation is used for testing and
 *  early integration; the real WebRTC adapter replaces it in Phase A2. */
public interface ICallMedia {

  /** Lifecycle — called on the controller's thread or a dedicated media thread. */

  /** Initialize the media stack (codecs, audio device).  Must not start capture.
   *  Called once per media session, before any other method. */
  void initialize() throws Exception;

  /** Create a peer connection for an outgoing call.  Returns the local SDP offer. */
  String createOffer() throws Exception;

  /** Create a peer connection for an incoming call, given the remote offer.
   *  Returns the local SDP answer. */
  String createAnswer(String remoteSdp) throws Exception;

  /** Apply a remote SDP (offer or answer). */
  void setRemoteDescription(String sdp) throws Exception;

  /** Add an ICE candidate from the remote peer. */
  void addIceCandidate(String candidate, String sdpMid, int sdpMLineIndex) throws Exception;

  /** Called when both sides are ready — starts capture and playback. */
  void startMedia() throws Exception;

  /** Mute/unmute the local microphone. */
  void setMute(boolean muted) throws Exception;

  /** Stop all capture, playback and networking.  May be called more than once. */
  void dispose();

  /** Optional separate video connection. A v1/fake adapter may return null. */
  default Video video() { return null; }

  /** Renderer owns its surfaces/sinks; this lease keeps the service EGL root alive
   * until those surfaces have detached and released. No Android types in the core. */
  interface RendererLease extends AutoCloseable {
    Object sharedContext();
    @Override void close();
  }
  interface FrameSink {
    /** Borrowed native frame, valid only during this callback; never retain it. */
    void onFrame(Object frame);
  }
  interface CaptureGate { boolean mayCapture(); }
  interface Video {
    /** Initialize a video-only secured PC. This must not acquire/start a camera. */
    void initialize(long generation, CaptureGate gate) throws Exception;
    String createOffer(long generation) throws Exception;
    String createAnswer(long generation, String sdp) throws Exception;
    void setRemoteAnswer(long generation, String sdp) throws Exception;
    void addIce(long generation, String candidate, String mid, int index) throws Exception;
    /** Explicit local action, rechecking gate at actual acquisition. */
    void startCamera(long generation) throws Exception;
    void stopCamera(long generation);
    void switchCamera(long generation) throws Exception;
    void attachLocal(FrameSink sink);
    void detachLocal(FrameSink sink);
    void attachRemote(FrameSink sink);
    void detachRemote(FrameSink sink);
    RendererLease acquireRendererLease();
    void setListener(VideoListener listener);
    /** Releases only video, never healthy audio. Stale generations are ignored. */
    void dispose(long generation);
  }
  interface VideoListener {
    void onIce(long generation, String candidate, String mid, int index);
    void onReady(long generation);
    void onError(long generation, String message);
  }

  // ── Statistics (sampled asynchronously during Connected) ────────

  /** Snapshot of current media statistics.  All counts are cumulative. */
  class Stats {
    public long   packetsReceived;
    public long   packetsLost;
    public double jitterMs;
    public long   roundTripTimeMs;
    public long   timestampMs = System.currentTimeMillis();
    public boolean valid; // true if the adapter returned usable values
  }

  /** Retrieve the current cumulative statistics.  Must not block for long. */
  Stats getStats();

  // ── Events ─────────────────────────────────────────────────────

  interface Listener {
    /** Local ICE candidate generated, ready to send to remote peer. */
    void onIceCandidate(String candidate, String sdpMid, int sdpMLineIndex);

    /** Local media is ready (capture + playback active). */
    void onMediaReady();

    /** A fatal media error occurred. */
    void onError(String message);
  }

  void setListener(Listener listener);

  // ── Factory ────────────────────────────────────────────────────

  interface Factory {
    ICallMedia create() throws Exception;
  }
}
