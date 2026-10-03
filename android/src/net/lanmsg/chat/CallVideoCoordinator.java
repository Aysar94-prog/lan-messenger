package net.lanmsg.chat;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One call's separate video negotiation. All signaling/native work runs on a
 * bounded service worker, never on a native callback or a controller lock.
 * The owner authenticates the channel and admits the shared call sequence first.
 * This second boundary checks video role, consent, request and generation. */
public final class CallVideoCoordinator implements AutoCloseable {
  public interface Wire { void send(CallProtocol.Frame frame) throws IOException; }
  public interface Observer { void changed(Snapshot snapshot); }
  public interface Admission { boolean admit(CallProtocol.Frame frame); }
  public static final class Snapshot {
    public final CallVideoConsent.Phase phase;
    public final String request;
    public final long generation;
    public final boolean localCamera, remoteCamera, remoteRequest;
    Snapshot(CallVideoConsent consent, boolean camera) {
      phase=consent.phase(); request=consent.requestId(); generation=consent.generation();
      localCamera=camera; remoteCamera=consent.remoteCameraOn();
      remoteRequest=consent.remoteRequestPending();
    }
  }
  private final String callId;
  private final boolean caller;
  private final CallVideoConsent consent;
  private final CallVideoActions.Eligibility eligibility;
  private final ICallMedia.Video media;
  private final Wire wire;
  private final Observer observer;
  private final ThreadPoolExecutor worker;
  private final ScheduledThreadPoolExecutor deadlines;
  private final AtomicBoolean closed=new AtomicBoolean();
  private volatile Snapshot snapshot;
  private ScheduledFuture<?> timeout;
  private long lastGeneration=1, revision;
  private boolean camera, offered, localReady, remoteReady;
  private volatile boolean answered;
  private int incomingIce, outgoingIce;
  private final long requestTimeoutMs,videoTimeoutMs;
  private final boolean autoAcceptVideo;

  public CallVideoCoordinator(String id, boolean caller, CallVideoConsent consent,
      CallVideoActions.Eligibility eligibility, ICallMedia.Video media, Wire wire, Observer observer) {
    this(id,caller,consent,eligibility,media,wire,observer,CallVideoProtocol.REQUEST_TIMEOUT_MS,CallVideoProtocol.VIDEO_TIMEOUT_MS,false);
  }
  public CallVideoCoordinator(String id, boolean caller, CallVideoConsent consent,
      CallVideoActions.Eligibility eligibility, ICallMedia.Video media, Wire wire, Observer observer,
      boolean autoAcceptVideo) {
    this(id,caller,consent,eligibility,media,wire,observer,CallVideoProtocol.REQUEST_TIMEOUT_MS,CallVideoProtocol.VIDEO_TIMEOUT_MS,autoAcceptVideo);
  }
  CallVideoCoordinator(String id,boolean caller,CallVideoConsent consent,CallVideoActions.Eligibility eligibility,
      ICallMedia.Video media,Wire wire,Observer observer,long requestTimeoutMs,long videoTimeoutMs){
    this(id,caller,consent,eligibility,media,wire,observer,requestTimeoutMs,videoTimeoutMs,false);
  }
  CallVideoCoordinator(String id,boolean caller,CallVideoConsent consent,CallVideoActions.Eligibility eligibility,
      ICallMedia.Video media,Wire wire,Observer observer,long requestTimeoutMs,long videoTimeoutMs,boolean autoAcceptVideo){
    if(!CallProtocol.validCallId(id)||consent==null||eligibility==null||media==null||wire==null)
      throw new IllegalArgumentException("Missing video boundary");
    if(requestTimeoutMs<=0||videoTimeoutMs<=0)throw new IllegalArgumentException("Invalid deadline");
    this.requestTimeoutMs=requestTimeoutMs;this.videoTimeoutMs=videoTimeoutMs;
    this.autoAcceptVideo=autoAcceptVideo;
    this.callId=id;this.caller=caller;this.consent=consent;this.eligibility=eligibility;
    this.media=media;this.wire=wire;this.observer=observer;
    worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(64),
      r->{Thread t=new Thread(r,"call-video-coordinator");t.setDaemon(true);return t;});
    deadlines=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"call-video-deadline");t.setDaemon(true);return t;});
    deadlines.setRemoveOnCancelPolicy(true);
    snapshot=new Snapshot(consent,false);
    media.setListener(new ICallMedia.VideoListener(){
      public void onIce(long gen,String candidate,String mid,int index){post(()->{
        if(!current(gen)||++outgoingIce>128)return;
        send("ICE",gen,"candidate",candidate,"sdpMid",mid,"sdpMLineIndex",index);
      });}
      public void onReady(long gen){post(()->{
        if(!current(gen))return;
        localReady=true;send("MEDIA_READY",gen);activate(gen);
      });}
      public void onError(long gen,String ignored){post(()->{if(current(gen))fail("failed",true);});}
      public void onCameraStopped(long gen){post(()->{
        if(!current(gen))return;consent.cameraOff(callId);camera=false;state();
      });}
    });
  }
  public Snapshot snapshot(){return snapshot;}
  public boolean isForCall(String expected){return !closed.get()&&callId.equals(expected);}
  public ICallMedia.Video media(){return media;}
  /** Lightweight pre-admission check; no native work or consent mutation. */
  public boolean permits(CallProtocol.Frame f){
    if(closed.get()||f==null||!callId.equals(f.callId)||!CallVideoProtocol.valid(f))return false;
    String id=(String)f.body.get("request");long gen=f.negotiationGeneration;
    switch(f.type){
      case "VIDEO_REQUEST":return consent.phase()==CallVideoConsent.Phase.Voice||consent.phase()==CallVideoConsent.Phase.Waiting;
      case "VIDEO_ACCEPT":return consent.canPeerAccept(callId,id);
      case "VIDEO_DECLINE":return gen==0&&Objects.equals(id,consent.requestId())&&consent.generation()==0;
      case "OFFER":return !caller&&consent.canAuthorizeGeneration(callId,id,gen);
      case "ANSWER":return caller&&Objects.equals(id,consent.requestId())&&gen==consent.generation()&&!answered;
      case "VIDEO_STATE":return Objects.equals(id,consent.requestId())
        &&consent.canRemoteCameraState(callId,gen,((Number)f.body.get("revision")).longValue());
      case "MEDIA_READY":return !remoteReady&&gen>=2&&gen==consent.generation()&&Objects.equals(id,consent.requestId());
      case "ICE":case "ERROR":
        return gen>=2&&gen==consent.generation()&&Objects.equals(id,consent.requestId());
      default:return false;
    }
  }
  private interface Job { void run() throws Exception; }
  private void post(Job job) {
    if(closed.get())return;
    try { worker.execute(()->{
      if(closed.get())return;
      try{job.run();}catch(Exception ignored){fail("failed",true);}
      publish();
    }); } catch(RejectedExecutionException ignored) {
      // Overflow invalidates consent immediately. Dedicated deadline executor
      // supplies cleanup even if the bounded media worker cannot accept work.
      close();
    }
  }
  private void publish(){
    snapshot=new Snapshot(consent,camera);
    if(observer!=null)try{observer.changed(snapshot);}catch(Exception ignored){}
  }
  private boolean current(long gen){return !closed.get()&&gen>=2&&consent.generation()==gen;}
  private boolean eligible(){return !closed.get()&&eligibility.mayCapture(callId);}
  private void send(String type,long gen,Object... values)throws IOException {
    CallProtocol.Frame f=new CallProtocol.Frame(type,callId,1,gen);
    f.protocolVersion=2;f.body=new LinkedHashMap<String,Object>();
    if(gen>=2){
      if(!"VIDEO_STATE".equals(type))f.body.put("media","video");
      f.body.put("request",consent.requestId());
    }
    for(int i=0;i<values.length;i+=2)f.body.put((String)values[i],values[i+1]);
    if(!CallVideoProtocol.valid(f))throw new IOException("Invalid local video frame");
    // Wire assigns the shared call sequence under its serialized send boundary.
    wire.send(f);
  }
  private void cancelDeadline(){if(timeout!=null){timeout.cancel(false);timeout=null;}}
  private void deadline(String request,long gen,long delay){
    cancelDeadline();timeout=deadlines.schedule(()->post(()->{
      if(!Objects.equals(request,consent.requestId())||gen!=consent.generation())return;
      if(gen==0){send("VIDEO_DECLINE",0,"request",request);consent.declineUpgrade(callId,request);}
      else fail("timeout",true);
    }),delay,TimeUnit.MILLISECONDS);
  }
  /** Audio must be established first, including for an accepted video INVITE. */
  public void audioConnected(){post(()->{
    consent.setConnected(callId,true);
    if(!caller&&consent.requestId()!=null)deadline(consent.requestId(),0,videoTimeoutMs);
    beginIfCaller();
  });}
  public void requestVideo(){post(()->{
    String id=UUID.randomUUID().toString();
    if(consent.requestUpgrade(callId,id,eligible())!=CallVideoConsent.Result.Ready)return;
    try{send("VIDEO_REQUEST",0,"request",id);deadline(id,0,requestTimeoutMs);}
    catch(Exception e){consent.declineUpgrade(callId,id);throw e;}
  });}
  public void acceptUpgrade(String id){post(()->{
    if(consent.acceptUpgrade(callId,id,eligible())!=CallVideoConsent.Result.Ready)return;
    send("VIDEO_ACCEPT",0,"request",id);
    if(!caller)deadline(id,0,videoTimeoutMs);
    beginIfCaller();
  });}
  public void declineUpgrade(String id){post(()->{
    if(!Objects.equals(id,consent.requestId()))return;
    send("VIDEO_DECLINE",0,"request",id);consent.declineUpgrade(callId,id);cancelDeadline();
  });}
  public void cameraOff(){
    consent.cameraOff(callId); // invalidates capture immediately, before queue drain
    post(()->setCamera(false));
  }
  public void cameraOn(){post(()->{
    if(consent.cameraOn(callId,eligible())==CallVideoConsent.Result.Ready)setCamera(true);
  });}
  public void switchCamera(){post(()->{
    long gen=consent.generation();
    if(!camera||!current(gen)||!eligible())return;
    media.switchCamera(gen);state();
  });}
  public void revokeCapture(){cameraOff();}
  /** Effects for CallVideoActions, which has already mutated the consent policy. */
  public void proposalAcceptedLocally(String id){post(()->{
    if(!Objects.equals(id,consent.requestId())||consent.generation()!=0)return;
    send("VIDEO_REQUEST",0,"request",id);deadline(id,0,requestTimeoutMs);
  });}
  public void upgradeAcceptedLocally(String id){post(()->{
    if(!Objects.equals(id,consent.requestId()))return;
    send("VIDEO_ACCEPT",0,"request",id);
    if(!caller)deadline(id,0,videoTimeoutMs);
    beginIfCaller();
  });}
  public void upgradeDeclinedLocally(String id){post(()->{
    send("VIDEO_DECLINE",0,"request",id);cancelDeadline();
  });}
  public void cameraChosenLocally(boolean on){post(()->setCamera(on));}
  private void state()throws IOException {
    if(revision==Long.MAX_VALUE){fail("failed",true);return;}
    send("VIDEO_STATE",consent.generation(),"camera",camera,"revision",++revision);
  }
  private void setCamera(boolean on)throws Exception {
    long gen=consent.generation();if(!current(gen))return;
    if(on){
      if(!consent.canCapture(callId,eligible(),true,true))return;
      media.startCamera(gen);camera=true;
    }else {media.stopCamera(gen);camera=false;}
    state();
  }
  private void initialize(long gen)throws Exception {
    offered=answered=localReady=remoteReady=camera=false;
    incomingIce=outgoingIce=0;revision=0;
    media.initialize(gen,()->consent.canCapture(callId,eligible(),true,true));
    deadline(consent.requestId(),gen,videoTimeoutMs);
  }
  private void beginIfCaller()throws Exception {
    if(!caller||consent.generation()!=0||consent.requestId()==null)return;
    if(lastGeneration==Long.MAX_VALUE){consent.declineUpgrade(callId,consent.requestId());return;}
    long gen=lastGeneration+1;
    if(!consent.authorizeGeneration(callId,consent.requestId(),gen))return;
    lastGeneration=gen;initialize(gen);
    String sdp=media.createOffer(gen);offered=true;send("OFFER",gen,"sdp",sdp);
  }
  private void activate(long gen)throws Exception {
    if(!current(gen)||!answered||!localReady||!remoteReady)return;
    consent.markMediaReady(callId,gen);cancelDeadline();setCamera(true);
  }
  /** Owner supplies authenticated channel/sequence admission. Invalid video
   * input never changes consent, generation, camera, or native descriptions. */
  public void receive(CallProtocol.Frame original){
    if(original==null)return;
    final CallProtocol.Frame f=original.copy();
    post(()->handle(f));
  }
  /** Called only by the signaling reader, outside controller locks. Admission
   * is serialized after preceding video work, so ICE arriving immediately after
   * OFFER cannot race its generation. The future completes before native work;
   * the shared call sequence is consumed in wire order. */
  public void receiveAdmitted(CallProtocol.Frame original,Admission admission)throws IOException {
    if(closed.get()||original==null)return;
    CallProtocol.Frame f=original.copy();CompletableFuture<Boolean> done=new CompletableFuture<Boolean>();
    post(()->{
      boolean accepted=permits(f)&&admission.admit(f);done.complete(accepted);
      if(accepted)handle(f);
    });
    try{done.get(20,TimeUnit.SECONDS);}
    catch(Exception error){throw new IOException("Video signaling admission unavailable");}
  }
  private void handle(CallProtocol.Frame f)throws Exception {
    if(!callId.equals(f.callId)||!CallVideoProtocol.valid(f))return;
    String id=(String)f.body.get("request");long gen=f.negotiationGeneration;
    switch(f.type){
      case "VIDEO_REQUEST":{
        String old=consent.requestId();
        CallVideoConsent.Result result=consent.receiveRequest(callId,id);
        if(result==CallVideoConsent.Result.Prompt){
          if(old!=null)send("VIDEO_DECLINE",0,"request",old);
          if(autoAcceptVideo&&consent.acceptUpgrade(callId,id,eligible())==CallVideoConsent.Result.Ready){
            send("VIDEO_ACCEPT",0,"request",id);
            if(!caller)deadline(id,0,videoTimeoutMs);
            beginIfCaller();
          }else deadline(id,0,requestTimeoutMs);
        }else if(result==CallVideoConsent.Result.Declined||result==CallVideoConsent.Result.Busy
            ||result==CallVideoConsent.Result.Unsupported)send("VIDEO_DECLINE",0,"request",id);
        return;
      }
      case "VIDEO_ACCEPT":
        if(consent.peerAccepted(callId,id)==CallVideoConsent.Result.Ready){
          if(!caller)deadline(id,0,videoTimeoutMs);
          beginIfCaller();
        }return;
      case "VIDEO_DECLINE":
        if(consent.generation()==0&&consent.declineUpgrade(callId,id)==CallVideoConsent.Result.Declined)cancelDeadline();return;
      default: break;
    }
    if(!Objects.equals(id,consent.requestId()))return;
    if("OFFER".equals(f.type)){
      if(caller||offered||consent.generation()!=0||gen<=lastGeneration)return;
      if(!consent.authorizeGeneration(callId,id,gen))return;
      lastGeneration=gen;initialize(gen);offered=true;
      String sdp=media.createAnswer(gen,(String)f.body.get("sdp"));answered=true;
      send("ANSWER",gen,"sdp",sdp);return;
    }
    if(!current(gen))return;
    switch(f.type){
      case "ANSWER":
        if(caller&&offered&&!answered){media.setRemoteAnswer(gen,(String)f.body.get("sdp"));answered=true;activate(gen);}break;
      case "ICE":
        if(++incomingIce>128){fail("failed",true);return;}
        media.addIce(gen,(String)f.body.get("candidate"),(String)f.body.get("sdpMid"),0);break;
      case "MEDIA_READY":remoteReady=true;activate(gen);break;
      case "VIDEO_STATE":consent.remoteCameraState(callId,gen,((Number)f.body.get("revision")).longValue(),(Boolean)f.body.get("camera"));break;
      case "ERROR":fail("failed",false);break;
      default:break;
    }
  }
  private void fail(String code,boolean notify){
    long gen=consent.generation();String request=consent.requestId();
    if(gen>=2){
      if(notify)try{send("ERROR",gen,"code",code);}catch(Exception ignored){}
      consent.failVideo(callId,gen);camera=false;
      try{media.dispose(gen);}catch(Exception ignored){}
    }else if(request!=null){
      if(notify)try{send("VIDEO_DECLINE",0,"request",request);}catch(Exception ignored){}
      consent.declineUpgrade(callId,request);
    }
    offered=answered=localReady=remoteReady=false;
    cancelDeadline();publish();
  }
  /** Consent is revoked synchronously; native disposal stays off the caller. */
  @Override public void close(){
    if(!closed.compareAndSet(false,true))return;
    long gen;
    synchronized(consent){gen=consent.generation();consent.end();}
    camera=false;publish();
    cancelDeadline();deadlines.shutdown();worker.getQueue().clear();
    try{worker.execute(()->{try{if(gen>=2)media.dispose(gen);}finally{worker.shutdown();}});}
    catch(RejectedExecutionException ignored){worker.shutdown();}
  }
  /** Test/owner quiescence, never invoked from callbacks or controller locks. */
  public boolean awaitIdle(long timeoutMs)throws Exception {
    if(closed.get())return worker.awaitTermination(timeoutMs,TimeUnit.MILLISECONDS);
    Future<?> marker=worker.submit(()->{});marker.get(timeoutMs,TimeUnit.MILLISECONDS);return true;
  }
}
