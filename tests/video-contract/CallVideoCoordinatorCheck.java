package net.lanmsg.chat;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Separate-PC coordinator tests: fake media only, not device acceptance. */
public final class CallVideoCoordinatorCheck {
  static int pass;
  static final String CALL="00000000-0000-0000-0000-000000000001";
  static final String FIRST="00000000-0000-0000-0000-000000000002";
  static final String SECOND="00000000-0000-0000-0000-000000000003";
  static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);pass++;}
  static final class Side implements AutoCloseable {
    final FakeCallMedia audio=new FakeCallMedia();
    final CallVideoConsent consent;
    final CallVideoCoordinator coordinator;
    final List<CallProtocol.Frame> sent=new CopyOnWriteArrayList<CallProtocol.Frame>();
    boolean eligible=true,failSend;
    Side(boolean caller,boolean initial){
      this(caller,initial,CallVideoProtocol.REQUEST_TIMEOUT_MS,CallVideoProtocol.VIDEO_TIMEOUT_MS);
    }
    Side(boolean caller,boolean initial,long requestTimeout,long videoTimeout){
      consent=new CallVideoConsent(CALL,true,caller,initial);
      coordinator=new CallVideoCoordinator(CALL,caller,consent,id->eligible,audio.video(),f->{
        if(failSend)throw new java.io.IOException("fake send");sent.add(f.copy());
      },null,requestTimeout,videoTimeout);
    }
    void idle()throws Exception{coordinator.awaitIdle(2000);coordinator.awaitIdle(2000);}
    FakeCallMedia.FakeVideo video(){return (FakeCallMedia.FakeVideo)audio.video();}
    public void close(){coordinator.close();}
  }
  static CallProtocol.Frame frame(String type,String id,long gen,Object... values){
    CallProtocol.Frame f=new CallProtocol.Frame(type,CALL,1,gen);f.protocolVersion=2;
    f.body=new LinkedHashMap<String,Object>();
    if(gen>=2){if(!type.equals("VIDEO_STATE"))f.body.put("media","video");f.body.put("request",id);}
    else f.body.put("request",id);
    for(int i=0;i<values.length;i+=2)f.body.put((String)values[i],values[i+1]);return f;
  }
  static void pump(Side from,Side to)throws Exception{
    from.idle();List<CallProtocol.Frame> items=new ArrayList<CallProtocol.Frame>(from.sent);from.sent.clear();
    for(CallProtocol.Frame f:items)to.coordinator.receive(f);to.idle();
  }
  static void connect(Side a,Side b)throws Exception{
    for(int i=0;i<5;i++){pump(a,b);pump(b,a);}
  }
  public static void main(String[] args)throws Exception{
    try(Side a=new Side(true,false);Side b=new Side(false,false)){
      a.coordinator.requestVideo();a.idle();
      check(a.sent.isEmpty(),"no request before audio connected");
      a.coordinator.audioConnected();b.coordinator.audioConnected();a.idle();b.idle();
      a.eligible=false;a.coordinator.requestVideo();a.idle();
      check(a.sent.isEmpty(),"no permission cannot request");
      a.eligible=true;a.coordinator.requestVideo();pump(a,b);
      String request=b.consent.requestId();
      check(request!=null&&b.consent.phase()==CallVideoConsent.Phase.Waiting,"peer request prompts only");
      check(!b.video().cameraActive(),"incoming request does not capture");
      b.coordinator.acceptUpgrade(SECOND);b.idle();check(b.sent.isEmpty(),"wrong request cannot accept");
      b.coordinator.acceptUpgrade(request);connect(b,a);connect(a,b);
      check(a.consent.generation()==2&&b.consent.generation()==2,"caller sole initial generation allocator");
      check(a.video().cameraActive()&&b.video().cameraActive(),"both consent and both ready capture");
      a.coordinator.cameraOff();connect(a,b);
      check(!a.video().cameraActive()&&!b.consent.remoteCameraOn(),"camera off converges independently");
      check(b.video().cameraActive(),"camera off leaves remote transmission");
      a.coordinator.switchCamera();a.idle();check(a.sent.isEmpty(),"switch while off acquires nothing");
      a.coordinator.cameraOn();connect(a,b);check(a.video().cameraActive(),"explicit camera on");
      a.coordinator.receive(frame("VIDEO_STATE",request,2,"camera",false,"revision",0L));a.idle();
      check(a.consent.remoteCameraOn(),"stale camera revision ignored");
      check(!a.coordinator.permits(frame("VIDEO_STATE",request,2,"camera",false,"revision",0L)),"stale camera revision rejected before sequence/heartbeat admission");
      check(!a.coordinator.permits(frame("MEDIA_READY",request,2)),"duplicate ready rejected before sequence/heartbeat admission");
      a.coordinator.receive(frame("ERROR",SECOND,2,"code","failed"));a.idle();
      check(a.consent.generation()==2,"foreign request failure cannot dispose active video");
      a.coordinator.receive(frame("ERROR",request,3,"code","failed"));a.idle();
      check(a.consent.generation()==2,"foreign generation ignored");
      a.video().fail();connect(a,b);
      check(a.consent.phase()==CallVideoConsent.Phase.Voice&&b.consent.phase()==CallVideoConsent.Phase.Voice,"video failure rolls back both sides only");
      a.audio.setMute(true);b.audio.setMute(true);check(true,"audio adapter remains live after video failure");
      a.coordinator.receive(frame("MEDIA_READY",request,2));a.idle();
      check(a.consent.phase()==CallVideoConsent.Phase.Voice,"late readiness cannot resurrect video");
      b.coordinator.requestVideo();pump(b,a);request=a.consent.requestId();
      a.coordinator.acceptUpgrade(request);connect(a,b);connect(b,a);
      check(a.consent.generation()==3&&b.consent.generation()==3,"callee request still uses original caller offer/new generation: "+a.consent.phase()+"/"+a.consent.generation()+" "+b.consent.phase()+"/"+b.consent.generation());
      b.eligible=false;b.coordinator.revokeCapture();connect(b,a);
      check(!b.video().cameraActive(),"revocation stops capture");
      b.coordinator.cameraOn();b.idle();check(!b.video().cameraActive(),"revoked permission cannot restart");
      a.coordinator.close();a.idle();
      check(!a.video().cameraActive()&&a.consent.phase()==CallVideoConsent.Phase.Ended,"close invalidates immediately and disposes");
      a.coordinator.receive(frame("MEDIA_READY",request,3));a.coordinator.cameraOn();
      check(a.consent.phase()==CallVideoConsent.Phase.Ended,"closed coordinator cannot resurrect");
    }
    try(Side a=new Side(true,true);Side b=new Side(false,true)){
      a.consent.peerAnswered(CALL,true);b.consent.acceptInitial(CALL,true,true);
      a.idle();check(a.sent.isEmpty()&&!a.video().cameraActive(),"initial video consent still waits for audio");
      a.coordinator.audioConnected();b.coordinator.audioConnected();connect(a,b);
      check(a.consent.generation()==2&&b.consent.generation()==2,"initial request bound to cid");
      check(CALL.equals(a.consent.requestId())&&a.video().cameraActive(),"initial both-video acceptance activates separate PC");
    }
    try(Side a=new Side(true,false)){
      a.coordinator.audioConnected();a.idle();a.failSend=true;a.coordinator.requestVideo();a.idle();
      check(a.consent.phase()==CallVideoConsent.Phase.Voice,"failed request write retires proposal");
      a.failSend=false;
      a.consent.requestUpgrade(CALL,SECOND,true);
      a.coordinator.receive(frame("VIDEO_REQUEST",FIRST,0));a.idle();
      check(FIRST.equals(a.consent.requestId()),"lower collision UUID wins");
      a.coordinator.receive(frame("VIDEO_ACCEPT",FIRST,0));a.idle();
      check(a.consent.generation()==0,"losing explicit consent not transferred to winning UUID");
      a.coordinator.acceptUpgrade(FIRST);a.idle();
      check(a.consent.generation()==2,"new explicit acceptance authorizes winning request");
    }
    try(Side a=new Side(true,false,80,80)){
      a.coordinator.audioConnected();a.idle();a.coordinator.requestVideo();a.idle();String retired=a.consent.requestId();
      Thread.sleep(200);a.idle();check(a.consent.phase()==CallVideoConsent.Phase.Voice,"request deadline preserves voice");
      a.coordinator.receive(frame("VIDEO_ACCEPT",retired,0));a.idle();
      check(a.consent.generation()==0,"accept after request expiry cannot allocate");
    }
    try(Side a=new Side(true,true,80,80)){
      a.consent.peerAnswered(CALL,true);a.coordinator.audioConnected();a.idle();
      Thread.sleep(200);a.idle();check(a.consent.phase()==CallVideoConsent.Phase.Voice,"negotiation deadline disposes video only");
      a.coordinator.receive(frame("MEDIA_READY",CALL,2));a.idle();check(!a.video().cameraActive(),"ready after negotiation expiry cannot capture");
    }
    try(Side a=new Side(false,true,80,80)){
      a.consent.acceptInitial(CALL,true,true);a.coordinator.audioConnected();a.idle();Thread.sleep(200);a.idle();
      check(a.consent.phase()==CallVideoConsent.Phase.Voice,"callee initial acceptance has bounded offer wait");
    }
    try(Side a=new Side(true,true)){
      a.consent.peerAnswered(CALL,true);a.coordinator.audioConnected();a.idle();
      String peer="00000000-0000-0000-0000-000000000099";
      CallFrameAdmission admission=new CallFrameAdmission(CALL,peer,2,0);
      CallProtocol.Frame bad=frame("ICE",SECOND,2,"candidate","candidate:1 1 UDP 1 127.0.0.1 12345 typ host","sdpMid","0","sdpMLineIndex",0);bad.senderSequence=100;
      a.coordinator.receiveAdmitted(bad,f->admission.admit(f,peer,true));
      CallProtocol.Frame good=frame("MEDIA_READY",CALL,2);good.senderSequence=2;
      AtomicInteger admitted=new AtomicInteger();
      a.coordinator.receiveAdmitted(good,f->{boolean ok=admission.admit(f,peer,true);if(ok)admitted.incrementAndGet();return ok;});
      check(admitted.get()==1,"invalid request does not consume shared sequence");
      check(!a.video().cameraActive(),"premature remote-ready cannot capture before answer");
      for(int i=0;i<129;i++){
        a.coordinator.receive(frame("ICE",CALL,2,"candidate","candidate:"+i+" 1 UDP 1 127.0.0.1 12345 typ host","sdpMid","0","sdpMLineIndex",0));
        if(i%8==0)a.idle();
      }
      a.idle();check(a.consent.phase()==CallVideoConsent.Phase.Voice,"ICE overflow rolls video back to voice");
    }
    System.out.println("CallVideoCoordinatorCheck PASS="+pass+" FAIL=0 (fake media only)");
  }
}
