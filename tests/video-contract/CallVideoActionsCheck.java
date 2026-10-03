package net.lanmsg.chat;

import java.io.IOException;

/** AT02 service command tests with fake permission and media/signaling effects. */
public final class CallVideoActionsCheck {
  static int pass;
  static final String CALL="00000000-0000-0000-0000-000000000001";
  static final String OTHER="00000000-0000-0000-0000-000000000002";
  static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);++pass;}
  static final class Fake implements CallVideoActions.Eligibility,CallVideoActions.Effects {
    boolean permission=true,foreground=true,hardware=true,online=true,fail;
    int answers,requests,accepts,declines,cameraOn,cameraOff;
    String request;
    public boolean mayCapture(String expected){return CALL.equals(expected)&&permission&&foreground&&hardware&&online;}
    void verify()throws IOException {if(fail)throw new IOException("fake signaling failure");}
    public void answer(String id,boolean video)throws IOException{verify();++answers;}
    public void request(String id,String value)throws IOException{verify();++requests;request=value;}
    public void accept(String id,String value)throws IOException{verify();++accepts;}
    public void decline(String id,String value)throws IOException{verify();++declines;}
    public void camera(String id,boolean on)throws IOException{verify();if(on)++cameraOn;else++cameraOff;}
  }
  public static void main(String[] args)throws Exception {
    Fake fake=new Fake();
    CallVideoConsent initial=new CallVideoConsent(CALL,true,false,true);
    CallVideoActions actions=new CallVideoActions(initial,fake,fake);
    fake.permission=false;
    check(actions.acceptVideo(CALL)==CallVideoConsent.Result.Denied&&fake.answers==0,"permission denial emits no acceptance");
    check(actions.answerWithVoice(CALL)==CallVideoConsent.Result.Voice&&fake.answers==1,"voice answer independent of camera permission");
    check(actions.acceptVideo(CALL)==CallVideoConsent.Result.Ignored&&fake.answers==1,"stale repeated accept emits nothing");
    CallVideoConsent consent=new CallVideoConsent(CALL,true,true,false);consent.setConnected(CALL,true);
    actions=new CallVideoActions(consent,fake,fake);
    check(actions.requestVideo(CALL)==CallVideoConsent.Result.Denied&&fake.requests==0,"revoked permission between calls");
    fake.permission=true;fake.foreground=false;
    check(actions.requestVideo(CALL)==CallVideoConsent.Result.Denied,"background cannot initiate capture intent");
    fake.foreground=true;fake.hardware=false;
    check(actions.requestVideo(CALL)==CallVideoConsent.Result.Denied,"missing camera preserves audio");
    fake.hardware=true;fake.online=false;
    check(actions.requestVideo(CALL)==CallVideoConsent.Result.Denied,"offline action denied");
    fake.online=true;
    check(actions.requestVideo(OTHER)==CallVideoConsent.Result.Ignored,"replacement call identity checked");
    fake.fail=true;
    try{actions.requestVideo(CALL);throw new AssertionError("send failure not propagated");}catch(IOException expected){++pass;}
    check(consent.phase()==CallVideoConsent.Phase.Voice,"failed proposal returns to voice");
    fake.fail=false;
    check(actions.requestVideo(CALL)==CallVideoConsent.Result.Ready&&fake.requests==1,"exactly one eligible request emitted");
    check(actions.requestVideo(CALL)==CallVideoConsent.Result.Busy&&fake.requests==1,"repeated taps not sent twice");
    consent.peerAccepted(CALL,fake.request);consent.authorizeGeneration(CALL,fake.request,2);consent.markMediaReady(CALL,2);
    actions.turnCameraOff(CALL);
    check(fake.cameraOff==1&&!consent.canCapture(CALL,true,true,true),"camera off explicit and audio remains connected");
    fake.permission=false;
    check(actions.turnCameraOn(CALL)==CallVideoConsent.Result.Denied&&fake.cameraOn==0,"current permission checked for camera on");
    fake.permission=true;fake.fail=true;
    try{actions.turnCameraOn(CALL);throw new AssertionError("camera failure not propagated");}catch(IOException expected){++pass;}
    check(!consent.canCapture(CALL,true,true,true),"failed on effect revokes capture intent");
    fake.fail=false;
    check(actions.turnCameraOn(CALL)==CallVideoConsent.Result.Ready&&fake.cameraOn==1,"camera on is distinct from voice mute");
    consent.end();
    check(actions.turnCameraOn(CALL)==CallVideoConsent.Result.Ignored,"late permission result after hangup");
    actions.turnCameraOff(CALL);check(fake.cameraOff==1,"terminal command has no effect");
    CallVideoConsent remote=new CallVideoConsent(CALL,true,false,false);remote.setConnected(CALL,true);remote.receiveRequest(CALL,OTHER);
    actions=new CallVideoActions(remote,fake,fake);
    check(actions.acceptUpgrade(CALL,CALL)==CallVideoConsent.Result.Ignored,"stale upgrade choice");
    fake.permission=false;
    check(actions.acceptUpgrade(CALL,OTHER)==CallVideoConsent.Result.Denied&&fake.accepts==0,"remote request cannot bypass permission");
    check(actions.declineUpgrade(CALL,OTHER)==CallVideoConsent.Result.Declined&&fake.declines==1,"decline upgrade leaves voice");
    check(actions.acceptUpgrade(CALL,OTHER)==CallVideoConsent.Result.Ignored,"late accept after decline");
    CallVideoConsent retry=new CallVideoConsent(CALL,true,false,true);
    actions=new CallVideoActions(retry,fake,fake);
    fake.permission=true;fake.fail=true;
    try{actions.acceptVideo(CALL);throw new AssertionError("initial answer failure ignored");}catch(IOException expected){++pass;}
    fake.fail=false;
    check(actions.answerWithVoice(CALL)==CallVideoConsent.Result.Voice,"failed initial video answer permits voice fallback");
    check(actions.isForCall(CALL)&&!actions.isForCall(OTHER),"UI provider bound to one call");
    retry.end();check(!actions.isForCall(CALL),"ended UI provider invalidated");
    System.out.println("CallVideoActionsCheck PASS="+pass+" FAIL=0 (AT02 fake effects; production A07 binding/UI acceptance pending)");
  }
}
