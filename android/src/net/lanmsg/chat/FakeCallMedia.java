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
  private final FakeVideo video = new FakeVideo();
  @Override public ICallMedia.Video video() { return video; }

  /** Test video connection, deliberately independent from the fake audio state. */
  public final class FakeVideo implements ICallMedia.Video {
    private long generation,lastGeneration;
    private boolean ready,camera;
    private CaptureGate gate;
    private VideoListener videoListener;
    private final Set<FrameSink> local=Collections.newSetFromMap(new IdentityHashMap<FrameSink,Boolean>());
    private final Set<FrameSink> remote=Collections.newSetFromMap(new IdentityHashMap<FrameSink,Boolean>());
    private final CallVideoResources resources=new CallVideoResources(() -> new CallVideoResources.Root() {
      private final Object context=new Object();
      public Object sharedContext(){return context;}
      public void close(){}
    });
    private CallVideoResources.Lease lease;
    private void current(long expected) {
      if(disposed || generation==0 || expected!=generation)throw new IllegalStateException("Stale fake video generation");
    }
    @Override public synchronized void initialize(long gen,CaptureGate captureGate) {
      if(disposed||generation!=0||gen<2||gen<=lastGeneration||captureGate==null)
        throw new IllegalStateException("Invalid fake video initialization");
      lease=resources.acquireMedia();generation=lastGeneration=gen;gate=captureGate;ready=camera=false;
    }
    @Override public synchronized String createOffer(long gen) {current(gen);return sdp();}
    @Override public synchronized String createAnswer(long gen,String sdp) {
      current(gen);if(!CallVideoProtocol.validSdp(sdp,true))throw new IllegalArgumentException("Invalid video SDP");
      markReady();return sdp();
    }
    @Override public synchronized void setRemoteAnswer(long gen,String sdp) {
      current(gen);if(!CallVideoProtocol.validSdp(sdp,true))throw new IllegalArgumentException("Invalid video SDP");markReady();
    }
    private void markReady(){ready=true;if(videoListener!=null)videoListener.onReady(generation);}
    @Override public synchronized void addIce(long gen,String candidate,String mid,int index) {current(gen);}
    @Override public synchronized void startCamera(long gen) {
      current(gen);if(!ready||!gate.mayCapture())throw new IllegalStateException("Camera not eligible");camera=true;
    }
    @Override public synchronized void stopCamera(long gen) {if(gen==generation)camera=false;}
    @Override public synchronized void switchCamera(long gen) {
      current(gen);if(!camera||!gate.mayCapture())throw new IllegalStateException("Camera switch not eligible");
    }
    private void attach(Set<FrameSink> sinks,FrameSink sink) {
      if(sink==null)throw new IllegalArgumentException("Missing sink");
      if(!sinks.contains(sink)&&sinks.size()>=2)throw new IllegalStateException("Sink limit");sinks.add(sink);
    }
    @Override public synchronized void attachLocal(FrameSink sink){attach(local,sink);}
    @Override public synchronized void detachLocal(FrameSink sink){local.remove(sink);}
    @Override public synchronized void attachRemote(FrameSink sink){attach(remote,sink);}
    @Override public synchronized void detachRemote(FrameSink sink){remote.remove(sink);}
    @Override public synchronized RendererLease acquireRendererLease(){current(generation);return resources.acquireRenderer();}
    @Override public synchronized void setListener(VideoListener listener){videoListener=listener;}
    @Override public synchronized void dispose(long gen) {
      if(generation==0||gen!=generation)return;
      camera=ready=false;generation=0;gate=null;local.clear();remote.clear();
      if(lease!=null){lease.close();lease=null;}
    }
    /** Borrowed fake frame delivery, used by A05/AT03 without native UI. */
    public synchronized void emitLocal(Object frame){if(camera)for(FrameSink sink:new ArrayList<FrameSink>(local))sink.onFrame(frame);}
    public synchronized void emitRemote(Object frame){if(ready)for(FrameSink sink:new ArrayList<FrameSink>(remote))sink.onFrame(frame);}
    public synchronized boolean cameraActive(){return camera;}
    public synchronized void fail() {
      long failed=generation;dispose(failed);
      if(failed!=0&&videoListener!=null)videoListener.onError(failed,"fake video failure");
    }
    private void close(){dispose(generation);resources.close();videoListener=null;}
    // Plain `SAVP` rather than `SAVPF`: the two are the same ICE/DTLS transport and real stacks
    // disagree about which to emit (SIPSorcery writes SAVP, libwebrtc writes SAVPF), so a fake that
    // agreed with the validator by construction could never catch a mismatch with a real peer.
    private String sdp(){return "v=0\r\nm=video 9 UDP/TLS/RTP/SAVP 96\r\na=fingerprint:sha-256 "+
      String.join(":",Collections.nCopies(32,"00"))+"\r\na=rtpmap:96 VP8/90000\r\n";}
  }

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
    video.close();
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
      "m=audio 9 UDP/TLS/RTP/SAVP 111\r\n" +
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
