package net.lanmsg.chat;

import android.content.Context;
import android.content.pm.PackageManager;
import org.webrtc.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Production A06 adapter. One video-only PC per authorized generation; never
 * changes the audio PC or opens a camera during initialization/SDP/render binding.
 * Native callbacks only enqueue work. Frame sinks receive borrowed frames and
 * must not perform controller/socket work or retain the frame. */
final class WebRtcCallVideo implements ICallMedia.Video {
  private final Context context;
  private final PeerConnectionFactory factory;
  private final CallVideoResources resources;
  private final BooleanSupplier parentLive;
  private volatile Thread workerThread;
  private final ThreadPoolExecutor worker;
  private final ThreadPoolExecutor callbacks;
  private volatile ICallMedia.VideoListener listener;
  private volatile Node active;
  private volatile boolean closed;
  private volatile boolean terminated;
  private long lastGeneration;
  private volatile CallVideoDiagnostics.Snapshot diagnostic=CallVideoDiagnostics.Snapshot.unavailable();
  private final CallVideoDiagnostics.Sampler diagnosticSampler=new CallVideoDiagnostics.Sampler();
  private final java.util.concurrent.atomic.AtomicBoolean statsPending=new java.util.concurrent.atomic.AtomicBoolean();
  private volatile long lastStatsNanos;
  private volatile boolean localFront=true;
  private final Map<ICallMedia.FrameSink,VideoSink> local = new IdentityHashMap<>();
  private final Map<ICallMedia.FrameSink,VideoSink> remote = new IdentityHashMap<>();

  WebRtcCallVideo(Context context, PeerConnectionFactory factory,
      CallVideoResources resources, BooleanSupplier parentLive) {
    this.context=context; this.factory=factory; this.resources=resources; this.parentLive=parentLive;
    worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(64), r -> {
      Thread t=new Thread(r,"call-video-worker"); t.setDaemon(true); workerThread=t; return t;
    });
    callbacks=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(160), r -> {
      Thread t=new Thread(r,"call-video-events"); t.setDaemon(true); return t;
    });
  }
  private <T> T execute(Callable<T> work) throws Exception {
    if(Thread.currentThread()==workerThread)return work.call();
    Future<T> future=worker.submit(work);
    try {return future.get(15,TimeUnit.SECONDS);}
    catch(ExecutionException e) {throw new Exception("Video operation failed",e.getCause());}
    // Do not cancel timed-out native work and then free its factory/context.
  }
  private void queue(Runnable work) {
    try {worker.execute(work);} catch(RejectedExecutionException ignored) {}
  }
  private boolean current(Node n) {return n!=null && active==n && !n.retiring && !n.failureQueued.get() && !closed && parentLive.getAsBoolean();}
  private Node node(long generation) {
    Node n=active;
    if(!current(n) || n.generation!=generation)throw new IllegalStateException("Stale video generation");
    return n;
  }
  private void event(Node n, Runnable work) {
    try {callbacks.execute(() -> {if(current(n))work.run();});}
    catch(RejectedExecutionException error) {fail(n,"Video event limit reached");}
  }
  private void fail(Node n,String message) {
    if(!current(n)||!n.failureQueued.compareAndSet(false,true))return;
    n.ready=false;
    queue(() -> {
      if(active!=n||closed)return;
      try {release(n);} catch(Throwable ignored) {} // Keep active node and its lease for a safe cleanup retry.
      final ICallMedia.VideoListener target=listener;
      if(target!=null)try {callbacks.execute(() -> {if(!closed)target.onError(n.generation,message);});}
      catch(RejectedExecutionException ignored) {}
    });
  }
  @Override public void initialize(long generation, ICallMedia.CaptureGate gate) throws Exception {
    execute(() -> {
      if(closed || !parentLive.getAsBoolean() || gate==null || generation<2 || generation<=lastGeneration || active!=null)
        throw new IllegalStateException("Video initialization is not authorized");
      lastGeneration=generation;
      Node n=new Node(generation,gate); active=n;
      try {
        n.lease=resources.acquireMedia();
        PeerConnection.RTCConfiguration config=new PeerConnection.RTCConfiguration(Collections.emptyList());
        config.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.continualGatheringPolicy=PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        n.pc=factory.createPeerConnection(config,observer(n));
        if(n.pc==null)throw new IllegalStateException("Video connection unavailable");
        n.source=factory.createVideoSource(false);
        n.track=factory.createVideoTrack("lanmsg-video-"+generation,n.source);
        n.track.setEnabled(false);
        for(VideoSink sink:local.values())n.track.addSink(sink);
      } catch(Throwable error) {release(n); throw new Exception("Video initialization failed",error);}
      return null;
    });
  }
  private void sender(Node n) {
    if(n.senderAdded)return;
    RtpSender sender=n.pc.addTrack(n.track,Collections.singletonList("lanmsg-video"));
    if(sender==null)throw new IllegalStateException("Video sender unavailable");
    List<RtpCapabilities.CodecCapability> codecs=new ArrayList<>();
    for(RtpCapabilities.CodecCapability c:factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs)
      if("VP8".equalsIgnoreCase(c.name))codecs.add(c);
    if(codecs.isEmpty())throw new IllegalStateException("VP8 unavailable");
    boolean selected=false;
    for(RtpTransceiver t:n.pc.getTransceivers())if(t.getSender().id().equals(sender.id())) {
      t.setCodecPreferences(codecs).throwError(); selected=true;
    }
    if(!selected)throw new IllegalStateException("Video transceiver unavailable");
    n.senderAdded=true;
  }
  private String localDescription(Node n,boolean offer) throws Exception {
    sender(n);
    AwaitSdp result=new AwaitSdp();
    if(offer)n.pc.createOffer(result,new MediaConstraints()); else n.pc.createAnswer(result,new MediaConstraints());
    result.await();
    if(result.sdp==null || !CallVideoProtocol.validSdp(result.sdp.description,true))
      throw new IllegalStateException("Unexpected video SDP profile");
    AwaitSdp applied=new AwaitSdp(); n.pc.setLocalDescription(applied,result.sdp); applied.await();
    return result.sdp.description;
  }
  @Override public String createOffer(long generation) throws Exception {
    return execute(() -> localDescription(node(generation),true));
  }
  private void applyRemote(Node n,String sdp,SessionDescription.Type type) throws Exception {
    if(!CallVideoProtocol.validSdp(sdp,true))throw new IllegalArgumentException("Invalid video SDP");
    AwaitSdp applied=new AwaitSdp(); n.pc.setRemoteDescription(applied,new SessionDescription(type,sdp)); applied.await();
    n.remoteSet=true;
    for(IceCandidate c:n.pending)if(!n.pc.addIceCandidate(c))throw new IllegalStateException("Video ICE rejected");
    n.pending.clear();
  }
  @Override public String createAnswer(long generation,String sdp) throws Exception {
    return execute(() -> {Node n=node(generation); applyRemote(n,sdp,SessionDescription.Type.OFFER); return localDescription(n,false);});
  }
  @Override public void setRemoteAnswer(long generation,String sdp) throws Exception {
    execute(() -> {applyRemote(node(generation),sdp,SessionDescription.Type.ANSWER);return null;});
  }
  @Override public void addIce(long generation,String candidate,String mid,int index) throws Exception {
    execute(() -> {
      Node n=node(generation);
      if(candidate==null || !candidate.startsWith("candidate:") || candidate.indexOf('\r')>=0 || candidate.indexOf('\n')>=0
          || candidate.indexOf('\0')>=0 || candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096
          || mid==null || mid.isEmpty() || mid.length()>64 || index!=0)throw new IllegalArgumentException("Invalid video ICE");
      String key=mid+"\n"+candidate;
      if(n.seen.contains(key))return null;
      if(n.seen.size()>=128)throw new IllegalStateException("Video ICE limit reached");
      n.seen.add(key); IceCandidate c=new IceCandidate(mid,index,candidate);
      if(!n.remoteSet)n.pending.add(c); else if(!n.pc.addIceCandidate(c))throw new IllegalStateException("Video ICE rejected");
      return null;
    });
  }
  private boolean eligible(Node n) {
    try {return current(n) && n.ready && n.gate.mayCapture()
      && context.checkSelfPermission("android.permission.CAMERA")==PackageManager.PERMISSION_GRANTED;}
    catch(Throwable denied) {return false;}
  }
  @Override public void startCamera(long generation) throws Exception {
    execute(() -> {
      Node n=node(generation); if(n.camera!=null)return null;
      if(!eligible(n))throw new SecurityException("Camera capture is not authorized");
      CameraEnumerator devices=Camera2Enumerator.isSupported(context)?new Camera2Enumerator(context):new Camera1Enumerator(true);
      String name=null;
      for(String value:devices.getDeviceNames()) {if(name==null)name=value;if(devices.isFrontFacing(value)){name=value;break;}}
      if(name==null)throw new IllegalStateException("No camera available");
      localFront=devices.isFrontFacing(name);
      final long epoch=++n.cameraEpoch;
      try {
        n.camera=devices.createCapturer(name,cameraEvents(n,epoch));
        if(n.camera==null)throw new IllegalStateException("Camera unavailable");
        n.texture=SurfaceTextureHelper.create("call-camera-"+generation,(EglBase.Context)n.lease.sharedContext());
        if(n.texture==null)throw new IllegalStateException("Camera texture unavailable");
        final CapturerObserver downstream=n.source.getCapturerObserver();
        n.camera.initialize(n.texture,context,new CapturerObserver() {
          public void onCapturerStarted(boolean success) {
            if(!current(n)||n.cameraEpoch!=epoch)return;
            if(!success){fail(n,"Camera start failed");return;}
            if(!eligible(n)){stopCameraAsync(n,epoch);return;}
            downstream.onCapturerStarted(true);
          }
          public void onCapturerStopped() {if(current(n)&&n.cameraEpoch==epoch)downstream.onCapturerStopped();}
          public void onFrameCaptured(VideoFrame frame) {
            if(n.cameraEpoch!=epoch)return;
            if(eligible(n))downstream.onFrameCaptured(frame);
            else if(n.stopQueued.compareAndSet(false,true))queue(() -> {if(current(n)&&n.cameraEpoch==epoch)try {stop(n);cameraStopped(n);}catch(Exception error){fail(n,"Camera stop failed");}});
          }
        });
        if(!eligible(n))throw new SecurityException("Camera eligibility changed");
        n.track.setEnabled(true); n.camera.startCapture(640,480,20);
      } catch(Throwable error) {stop(n);throw new Exception("Camera start failed",error);}
      return null;
    });
  }
  private void stop(Node n) throws Exception {
    ++n.cameraEpoch; n.stopQueued.set(false);
    if(n.track!=null)n.track.setEnabled(false);
    if(n.camera!=null) {
      n.camera.stopCapture();
      if(n.source!=null)n.source.getCapturerObserver().onCapturerStopped();
      n.camera.dispose();n.camera=null;
    }
    if(n.texture!=null) {n.texture.dispose();n.texture=null;}
  }
  @Override public void stopCamera(long generation) {
    try {execute(() -> {Node n=active;if(n!=null&&n.generation==generation)stop(n);return null;});}
    catch(Exception error) {Node n=active;if(n!=null&&n.generation==generation)fail(n,"Camera stop failed");}
  }
  @Override public void switchCamera(long generation) throws Exception {
    execute(() -> {
      Node n=node(generation); if(!eligible(n)||n.camera==null)throw new SecurityException("Camera switch is not authorized");
      final long epoch=n.cameraEpoch;
      n.camera.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
        public void onCameraSwitchDone(boolean front) {if(current(n)&&n.cameraEpoch==epoch){localFront=front;if(!eligible(n))stopCameraAsync(n,epoch);}}
        public void onCameraSwitchError(String error) {if(current(n)&&n.cameraEpoch==epoch)fail(n,"Camera switch failed");}
      }); return null;
    });
  }
  private void cameraStopped(Node n){event(n,()->{ICallMedia.VideoListener value=listener;if(value!=null)value.onCameraStopped(n.generation);});}
  private void stopCameraAsync(Node n,long epoch) {queue(() -> {if(current(n)&&n.cameraEpoch==epoch)try {stop(n);cameraStopped(n);}catch(Exception error){fail(n,"Camera stop failed");}});}
  private CameraVideoCapturer.CameraEventsHandler cameraEvents(Node n,long epoch) {
    return new CameraVideoCapturer.CameraEventsHandler() {
      private void error() {if(current(n)&&n.cameraEpoch==epoch)fail(n,"Camera unavailable");}
      public void onCameraError(String message){error();} public void onCameraDisconnected(){error();}
      public void onCameraFreezed(String message){error();} public void onCameraOpening(String name){}
      public void onFirstFrameAvailable(){} public void onCameraClosed(){}
    };
  }
  private void bind(ICallMedia.FrameSink sink,boolean own,boolean attach) {
    if(sink==null)return;
    try {execute(() -> {
      if(closed) return null;
      Map<ICallMedia.FrameSink,VideoSink> bindings=own?local:remote;
      Node n=active; VideoTrack track=n==null?null:(own?n.track:n.remoteTrack);
      if(attach) {
        if(bindings.containsKey(sink))return null;
        if(bindings.size()>=2)throw new IllegalStateException("Renderer binding limit reached");
        VideoSink wrapped=frame -> {try {sink.onFrame(frame);}catch(Throwable ignored){}};
        bindings.put(sink,wrapped); if(track!=null)track.addSink(wrapped);
      } else {VideoSink wrapped=bindings.remove(sink);if(track!=null&&wrapped!=null)track.removeSink(wrapped);}
      return null;
    });} catch(Exception error) {throw new IllegalStateException("Renderer binding failed",error);}
  }
  public void attachLocal(ICallMedia.FrameSink sink){bind(sink,true,true);}
  public void detachLocal(ICallMedia.FrameSink sink){bind(sink,true,false);}
  public void attachRemote(ICallMedia.FrameSink sink){bind(sink,false,true);}
  public void detachRemote(ICallMedia.FrameSink sink){bind(sink,false,false);}
  public ICallMedia.RendererLease acquireRendererLease() {
    if(closed||!parentLive.getAsBoolean()||active==null)throw new IllegalStateException("No live video renderer");
    return resources.acquireRenderer();
  }
  public void setListener(ICallMedia.VideoListener value){listener=value;}
  public boolean localMirror(){return localFront;}
  public CallVideoDiagnostics.Snapshot diagnostics(){
    long now=System.nanoTime();Node n=active;
    if(current(n)&&n.ready&&now-lastStatsNanos>=1_000_000_000L&&statsPending.compareAndSet(false,true)){
      lastStatsNanos=now;
      try{worker.execute(()->{
        if(!current(n)||n.pc==null){statsPending.set(false);return;}
        try{n.pc.getStats(report->{
          try{worker.execute(()->{
            try{if(current(n))diagnostic=diagnosticSampler.sample(counters(report),System.nanoTime());}
            finally{statsPending.set(false);}
          });}catch(RejectedExecutionException ignored){statsPending.set(false);}
        });}catch(Exception ignored){statsPending.set(false);}
      });}catch(RejectedExecutionException ignored){statsPending.set(false);}
    }
    return current(n)?diagnostic.fresh(now):CallVideoDiagnostics.Snapshot.unavailable();
  }
  private static Long count(Object value){
    if(!(value instanceof Number))return null;
    double n=((Number)value).doubleValue();long number=((Number)value).longValue();
    return Double.isFinite(n)&&number>=0&&n==(double)number?number:null;
  }
  private static CallVideoDiagnostics.Counters counters(RTCStatsReport report){
    if(report==null||report.getStatsMap().size()>256)return null;
    CallVideoDiagnostics.Counters c=new CallVideoDiagnostics.Counters();
    c.timestampUs=(long)report.getTimestampUs();
    int inbound=0,outbound=0;
    for(RTCStats item:report.getStatsMap().values()){
      Map<String,Object> m=item.getMembers();String type=item.getType();
      if(!"video".equals(m.get("kind"))&&!"video".equals(m.get("mediaType")))continue;
      if("outbound-rtp".equals(type)){
        if(++outbound>1)return null;
        c.sentFrames=count(m.get("framesEncoded"));c.sentBytes=count(m.get("bytesSent"));
      }else if("inbound-rtp".equals(type)){
        if(++inbound>1)return null;
        c.receivedFrames=count(m.get("framesDecoded"));c.receivedBytes=count(m.get("bytesReceived"));
        c.receivedPackets=count(m.get("packetsReceived"));c.lostPackets=count(m.get("packetsLost"));
      }else continue;
      Object codecId=m.get("codecId");RTCStats codec=codecId instanceof String?report.getStatsMap().get(codecId):null;
      if(codec!=null&&"video/VP8".equalsIgnoreCase(String.valueOf(codec.getMembers().get("mimeType"))))c.codec="VP8";
    }
    return c;
  }
  public void dispose(long generation) {
    try {execute(() -> {Node n=active;if(n!=null&&n.generation==generation)release(n);return null;});}
    catch(Exception error) {Node n=active;if(n!=null&&n.generation==generation)fail(n,"Video cleanup failed");}
  }
  /** Parent must retain its native factory if cleanup could not finish safely. */
  boolean closeAll() {
    if(terminated)return true;
    closed=true;
    try {execute(() -> {if(active!=null)release(active);local.clear();remote.clear();return null;});}
    catch(Exception error) {return false;}
    finally {callbacks.shutdownNow();listener=null;}
    worker.shutdown();terminated=true;return true;
  }
  private void release(Node n) throws Exception {
    diagnostic=CallVideoDiagnostics.Snapshot.unavailable();diagnosticSampler.reset();statsPending.set(false);
    n.retiring=true;
    n.ready=false;
    stop(n);
    if(n.track!=null) {for(VideoSink sink:local.values())n.track.removeSink(sink);}
    if(n.remoteTrack!=null) {for(VideoSink sink:remote.values())n.remoteTrack.removeSink(sink);}
    if(n.pc!=null){n.pc.close();n.pc.dispose();n.pc=null;}
    if(n.remoteTrack!=null){n.remoteTrack.dispose();n.remoteTrack=null;}
    if(n.track!=null){n.track.dispose();n.track=null;}
    if(n.source!=null){n.source.dispose();n.source=null;}
    n.pending.clear();n.seen.clear();
    if(n.lease!=null){n.lease.close();n.lease=null;}
    if(active==n)active=null;
  }
  private PeerConnection.Observer observer(Node n) {
    return new PeerConnection.Observer() {
      public void onSignalingChange(PeerConnection.SignalingState s){}
      public void onIceConnectionChange(PeerConnection.IceConnectionState s){}
      public void onConnectionChange(PeerConnection.PeerConnectionState s) {
        if(!current(n))return;
        if(s==PeerConnection.PeerConnectionState.CONNECTED) {n.ready=true;event(n,() -> {ICallMedia.VideoListener l=listener;if(l!=null)l.onReady(n.generation);});}
        else if(s==PeerConnection.PeerConnectionState.FAILED)fail(n,"Video connection lost");
        else if(s!=PeerConnection.PeerConnectionState.NEW){n.ready=false;stopCameraAsync(n,n.cameraEpoch);}
      }
      public void onIceConnectionReceivingChange(boolean receiving){}
      public void onIceGatheringChange(PeerConnection.IceGatheringState s){}
      public void onIceCandidate(IceCandidate c) {
        if(c==null||!current(n))return;
        if(n.outgoing.incrementAndGet()>128){fail(n,"Video ICE limit reached");return;}
        event(n,() -> {ICallMedia.VideoListener l=listener;if(l!=null)l.onIce(n.generation,c.sdp,c.sdpMid,c.sdpMLineIndex);});
      }
      public void onIceCandidatesRemoved(IceCandidate[] c){}
      public void onAddStream(MediaStream s){} public void onRemoveStream(MediaStream s){}
      public void onDataChannel(DataChannel channel){fail(n,"Unexpected video data channel");}
      public void onRenegotiationNeeded(){}
      public void onTrack(RtpTransceiver t) {
        if(!current(n))return;
        MediaStreamTrack track=t.getReceiver().track();
        if(!(track instanceof VideoTrack)){fail(n,"Unexpected video track");return;}
        VideoTrack video=(VideoTrack)track;
        queue(() -> {
          if(!current(n))return;
          if(n.remoteTrack==video)return;
          if(n.remoteTrack!=null){fail(n,"Unexpected additional video track");return;}
          n.remoteTrack=video;for(VideoSink sink:remote.values())video.addSink(sink);
        });
      }
    };
  }
  private static final class Node {
    final long generation; final ICallMedia.CaptureGate gate;
    volatile boolean ready,retiring; volatile long cameraEpoch;
    boolean remoteSet,senderAdded;
    final AtomicInteger outgoing=new AtomicInteger();
    final java.util.concurrent.atomic.AtomicBoolean stopQueued=new java.util.concurrent.atomic.AtomicBoolean();
    final java.util.concurrent.atomic.AtomicBoolean failureQueued=new java.util.concurrent.atomic.AtomicBoolean();
    final List<IceCandidate> pending=new ArrayList<>(); final Set<String> seen=new HashSet<>();
    PeerConnection pc; VideoSource source; VideoTrack track,remoteTrack;
    CameraVideoCapturer camera; SurfaceTextureHelper texture; CallVideoResources.Lease lease;
    Node(long generation,ICallMedia.CaptureGate gate){this.generation=generation;this.gate=gate;}
  }
  private static final class AwaitSdp implements SdpObserver {
    final CountDownLatch done=new CountDownLatch(1); SessionDescription sdp; String error;
    public void onCreateSuccess(SessionDescription value){sdp=value;done.countDown();}
    public void onSetSuccess(){done.countDown();}
    public void onCreateFailure(String value){error="create";done.countDown();}
    public void onSetFailure(String value){error="apply";done.countDown();}
    void await() throws Exception {
      if(!done.await(4,TimeUnit.SECONDS))throw new IllegalStateException("Video SDP timeout");
      if(error!=null)throw new IllegalStateException("Video SDP "+error+" failed");
    }
  }
}
