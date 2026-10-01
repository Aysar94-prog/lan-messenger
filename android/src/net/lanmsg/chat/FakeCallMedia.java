package net.lanmsg.chat;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Fake media adapter for testing — no real audio, deterministic behavior.
 *  Produces synthetic SDP, ICE candidates, and statistics. */
public class FakeCallMedia implements ICallMedia {

  private final AtomicLong statsSeq = new AtomicLong(0);
  private Listener listener;
  private boolean muted;
  private boolean started;
  private volatile boolean disposed;

  // Configurable for tests
  private boolean failInit;
  private boolean failOffer;
  private boolean failAnswer;

  public FakeCallMedia() {}

  public FakeCallMedia failInit()    { this.failInit = true; return this; }
  public FakeCallMedia failOffer()   { this.failOffer = true; return this; }
  public FakeCallMedia failAnswer()  { this.failAnswer = true; return this; }

  @Override public void initialize() throws Exception {
    if (failInit) throw new Exception("Fake init failure");
  }

  @Override public String createOffer() throws Exception {
    if (failOffer) throw new Exception("Fake offer failure");
    return fakeSdp("offer");
  }

  @Override public String createAnswer(String remoteSdp) throws Exception {
    if (failAnswer) throw new Exception("Fake answer failure");
    return fakeSdp("answer");
  }

  @Override public void setRemoteDescription(String sdp) throws Exception {
    // no-op in fake
  }

  @Override public void addIceCandidate(String candidate, String sdpMid, int sdpMLineIndex) throws Exception {
    // no-op in fake
  }

  @Override public void startMedia() throws Exception {
    started = true;
    // Simulate ICE candidates
    if (listener != null) {
      listener.onIceCandidate("candidate:1 1 UDP 2122252543 10.0.0.1 54321 typ host", "0", 0);
      listener.onIceCandidate("candidate:2 1 UDP 2122252543 10.0.0.1 54322 typ host", "0", 0);
    }
    // Simulate media ready shortly after
    new Thread(() -> {
      try { Thread.sleep(50); } catch (InterruptedException e) { return; }
      if (!disposed && listener != null) listener.onMediaReady();
    }, "fake-media-ready").start();
  }

  @Override public void setMute(boolean muted) throws Exception {
    this.muted = muted;
  }

  @Override public void dispose() {
    disposed = true;
    started = false;
  }

  @Override public Stats getStats() {
    Stats s = new Stats();
    s.valid = true;
    long seq = statsSeq.incrementAndGet();
    s.packetsReceived = seq * 50;
    s.packetsLost = seq < 10 ? 1 : 0; // some loss in first 10 samples
    s.jitterMs = 5.0 + (seq % 5);      // 5-9 ms jitter
    s.roundTripTimeMs = 10 + (seq % 10); // 10-19 ms RTT
    s.timestampMs = System.currentTimeMillis();
    return s;
  }

  @Override public void setListener(Listener listener) { this.listener = listener; }

  private static String fakeSdp(String type) {
    return "v=0\r\n" +
      "o=- 0 0 IN IP4 127.0.0.1\r\n" +
      "s=LAN Messenger Fake " + type + "\r\n" +
      "t=0 0\r\n" +
      "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
      "c=IN IP4 0.0.0.0\r\n" +
      "a=rtpmap:111 opus/48000/2\r\n";
  }

  // ── Factory ────────────────────────────────────────────────────

  public static class Factory implements ICallMedia.Factory {
    private boolean failInit, failOffer, failAnswer;

    public Factory failInit()   { this.failInit = true; return this; }
    public Factory failOffer()  { this.failOffer = true; return this; }
    public Factory failAnswer() { this.failAnswer = true; return this; }

    @Override public ICallMedia create() throws Exception {
      FakeCallMedia m = new FakeCallMedia();
      if (failInit) m.failInit();
      if (failOffer) m.failOffer();
      if (failAnswer) m.failAnswer();
      return m;
    }
  }
}