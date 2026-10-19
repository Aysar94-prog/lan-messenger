package net.lanmsg.chat;

/** AT02 fake permission/media eligibility tests. No camera or Android runtime. */
public final class CallVideoConsentCheck {
  private static int pass;
  private static final String CALL="00000000-0000-0000-0000-000000000001";
  private static final String OTHER="00000000-0000-0000-0000-000000000002";
  private static final String LOW="00000000-0000-0000-0000-000000000003";
  private static final String HIGH="00000000-0000-0000-0000-000000000004";
  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message); ++pass;
  }
  private static boolean capture(CallVideoConsent c) { return c.canCapture(CALL,true,true,true); }
  public static void main(String[] args) {
    CallVideoConsent receiveOnly=new CallVideoConsent(CALL,true,false,true);
    check(receiveOnly.acceptInitialReceiveOnly(OTHER)==CallVideoConsent.Result.Ignored,"receive-only bound to call");
    check(receiveOnly.acceptInitialReceiveOnly(CALL)==CallVideoConsent.Result.Ready,"trusted receive-only answer");
    receiveOnly.setConnected(CALL,true);
    check(receiveOnly.authorizeGeneration(CALL,CALL,2),"receive-only permits video negotiation");
    check(receiveOnly.markMediaReady(CALL,2),"receive-only ready");
    check(!capture(receiveOnly),"receive-only never authorizes camera implicitly");
    check(receiveOnly.cameraOn(CALL,false)==CallVideoConsent.Result.Denied,"locked new camera denied");
    check(!capture(receiveOnly),"denied camera remains off");
    check(receiveOnly.cameraOn(CALL,true)==CallVideoConsent.Result.Ready,"later explicit eligible camera action");
    check(capture(receiveOnly),"explicit camera consent works after receive-only answer");
    check(receiveOnly.acceptInitialReceiveOnly(CALL)==CallVideoConsent.Result.Ignored,"duplicate receive-only answer ignored");
    CallVideoConsent incoming=new CallVideoConsent(CALL,true,false,true);
    check(!capture(incoming),"invitation does not capture");
    check(incoming.acceptInitial(OTHER,true,true)==CallVideoConsent.Result.Ignored,"foreign call action");
    check(incoming.acceptInitial(CALL,true,false)==CallVideoConsent.Result.Denied,"denied permission retains choice");
    check(incoming.acceptInitial(CALL,false,false)==CallVideoConsent.Result.Voice,"answer with voice needs no camera");
    check(incoming.acceptInitial(CALL,true,true)==CallVideoConsent.Result.Ignored,"repeated initial accept");
    incoming.setConnected(CALL,true);
    check(!capture(incoming)&&incoming.phase()==CallVideoConsent.Phase.Voice,"voice answer stays camera-free");
    CallVideoConsent caller=new CallVideoConsent(CALL,true,true,true);
    check(!capture(caller),"outgoing video invitation alone cannot capture");
    check(caller.peerAnswered(CALL,false)==CallVideoConsent.Result.Voice,"callee voice selection clears video");
    caller.setConnected(CALL,true);
    check(!capture(caller),"audio connected not video consent");
    check(caller.requestUpgrade(CALL,HIGH,false)==CallVideoConsent.Result.Denied,"revoked permission before request");
    check(caller.requestUpgrade(CALL,HIGH,true)==CallVideoConsent.Result.Ready,"explicit local upgrade");
    check(caller.requestUpgrade(CALL,HIGH,true)==CallVideoConsent.Result.Busy,"repeated tap serialized");
    check(!caller.authorizeGeneration(CALL,HIGH,2),"peer consent required");
    check(caller.peerAccepted(CALL,LOW)==CallVideoConsent.Result.Ignored,"stale request accept");
    check(caller.peerAccepted(CALL,HIGH)==CallVideoConsent.Result.Ready,"matching peer accept");
    check(caller.peerAccepted(CALL,HIGH)==CallVideoConsent.Result.Ignored,"duplicate peer accept");
    check(!caller.authorizeGeneration(CALL,HIGH,1),"audio generation cannot authorize video");
    check(caller.authorizeGeneration(CALL,HIGH,2),"new video generation");
    check(!capture(caller),"SDP authorization not media readiness");
    check(!caller.markMediaReady(CALL,3),"late future callback");
    check(caller.markMediaReady(CALL,2),"matching media callback");
    check(capture(caller),"all consent eligibility conditions allow capture");
    check(!caller.canCapture(CALL,false,true,true),"permission rechecked at capture");
    check(!caller.canCapture(CALL,true,false,true),"camera availability rechecked");
    check(!caller.canCapture(CALL,true,true,false),"background acquisition prohibited");
    caller.cameraOff(CALL);
    check(!capture(caller)&&caller.phase()==CallVideoConsent.Phase.Video,"camera-off does not destroy video or audio");
    check(caller.remoteCameraState(CALL,2,1,true)&&!capture(caller),"peer camera state cannot command local capture");
    check(!caller.remoteCameraState(CALL,2,1,false),"duplicate camera revision");
    check(!caller.remoteCameraState(CALL,1,2,false),"foreign camera generation");
    check(caller.cameraOn(CALL,false)==CallVideoConsent.Result.Denied,"camera-on denial keeps off");
    check(caller.cameraOn(CALL,true)==CallVideoConsent.Result.Ready&&capture(caller),"explicit camera-on");
    check(caller.cameraOn(CALL,true)==CallVideoConsent.Result.Busy,"camera-on repeated tap");
    caller.revokeCapture(CALL);
    check(!capture(caller)&&caller.remoteCameraOn(),"revocation stops only local camera");
    check(!caller.failVideo(CALL,3),"foreign video failure cannot tear down active video");
    check(caller.failVideo(CALL,2)&&caller.phase()==CallVideoConsent.Phase.Voice,"video failure returns to voice");
    check(!caller.markMediaReady(CALL,2)&&!capture(caller),"late callback cannot resurrect failed video");
    check(caller.requestUpgrade(CALL,LOW,true)==CallVideoConsent.Result.Ready,"retry requires fresh request");
    caller.peerAccepted(CALL,LOW);
    check(!caller.authorizeGeneration(CALL,LOW,2),"failed generation never reused");
    check(caller.authorizeGeneration(CALL,LOW,3),"retry uses newer generation");
    caller.end();
    check(!caller.markMediaReady(CALL,3)&&!capture(caller),"hangup discards media callback");
    check(caller.cameraOn(CALL,true)==CallVideoConsent.Result.Ignored,"permission result after hangup");
    CallVideoConsent collision=new CallVideoConsent(CALL,true,true,false);
    collision.setConnected(CALL,true); collision.requestUpgrade(CALL,HIGH,true);
    check(collision.receiveRequest(CALL,LOW)==CallVideoConsent.Result.Prompt,"lower peer request wins collision");
    check(collision.peerAccepted(CALL,HIGH)==CallVideoConsent.Result.Ignored,"losing request reply ignored");
    check(!collision.authorizeGeneration(CALL,LOW,2),"losing consent not transferred");
    check(collision.acceptUpgrade(CALL,LOW,false)==CallVideoConsent.Result.Denied,"winning request still needs permission");
    check(collision.acceptUpgrade(CALL,LOW,true)==CallVideoConsent.Result.Ready,"winning prompt explicitly accepted");
    check(collision.authorizeGeneration(CALL,LOW,2),"winning consent authorizes one setup");
    check(collision.receiveRequest(CALL,HIGH)==CallVideoConsent.Result.Busy,"no concurrent negotiation");
    CallVideoConsent receiver=new CallVideoConsent(CALL,true,false,false);
    receiver.setConnected(CALL,true);
    check(receiver.receiveRequest(CALL,LOW)==CallVideoConsent.Result.Prompt&&!capture(receiver),"remote upgrade never captures");
    check(receiver.receiveRequest(CALL,LOW)==CallVideoConsent.Result.Ignored,"duplicate remote request");
    check(receiver.receiveRequest(CALL,HIGH)==CallVideoConsent.Result.Declined,"higher collision loses");
    check(receiver.declineUpgrade(CALL,HIGH)==CallVideoConsent.Result.Ignored,"stale decline cannot cancel winner");
    check(receiver.declineUpgrade(CALL,LOW)==CallVideoConsent.Result.Declined&&!capture(receiver),"decline preserves voice");
    check(receiver.receiveRequest(CALL,LOW)==CallVideoConsent.Result.Ignored,"retired request not reused");
    check(receiver.requestUpgrade(CALL,LOW,true)==CallVideoConsent.Result.Ignored,"retired local request not reused");
    CallVideoConsent legacy=new CallVideoConsent(CALL,false,true,false);
    legacy.setConnected(CALL,true);
    check(legacy.requestUpgrade(CALL,LOW,true)==CallVideoConsent.Result.Unsupported,"legacy stays voice-only");
    check(legacy.receiveRequest(CALL,LOW)==CallVideoConsent.Result.Unsupported,"legacy refuses video");
    CallVideoConsent initial=new CallVideoConsent(CALL,true,false,true);
    check(initial.acceptInitial(CALL,true,true)==CallVideoConsent.Result.Ready,"explicit incoming video acceptance");
    check(!initial.authorizeGeneration(CALL,CALL,2),"initial video waits for audio connection");
    initial.setConnected(CALL,true);
    check(initial.authorizeGeneration(CALL,CALL,2)&&initial.markMediaReady(CALL,2)&&capture(initial),"initial video after consent and audio");
    initial.setConnected(CALL,false);
    check(!capture(initial)&&initial.phase()==CallVideoConsent.Phase.Voice,"offline disconnect cancels video authorization");
    CallVideoConsent race=new CallVideoConsent(CALL,true,true,false);
    race.setConnected(CALL,true);
    java.util.concurrent.atomic.AtomicInteger admitted=new java.util.concurrent.atomic.AtomicInteger();
    java.util.List<Thread> threads=new java.util.ArrayList<Thread>();
    for(int i=0;i<8;i++) {
      Thread t=new Thread(() -> { if(race.requestUpgrade(CALL,LOW,true)==CallVideoConsent.Result.Ready)admitted.incrementAndGet(); });
      threads.add(t); t.start();
    }
    for(Thread t:threads)try { t.join(3000); }catch(InterruptedException e){throw new AssertionError(e);}
    check(admitted.get()==1,"concurrent repeated taps admit exactly one action");
    race.declineUpgrade(CALL,LOW);
    for(int i=1;i<128;i++) {
      String id=String.format("00000000-0000-0000-0000-%012d",i+100);
      if(race.requestUpgrade(CALL,id,true)!=CallVideoConsent.Result.Ready)throw new AssertionError("request cap too early");
      race.declineUpgrade(CALL,id);
    }
    check(race.requestUpgrade(CALL,HIGH,true)==CallVideoConsent.Result.Unsupported,"request history bounded without evicting replay guard");
    check(race.phase()==CallVideoConsent.Phase.Voice,"resource cap retains voice");
    System.out.println("CallVideoConsentCheck PASS="+pass+" FAIL=0 (AT02 pure policy; device/UI integration separate)");
  }
}
