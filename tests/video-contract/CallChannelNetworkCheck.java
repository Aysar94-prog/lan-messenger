package net.lanmsg.chat;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Real authenticated call sockets with fake media, never native audio/camera. */
public final class CallChannelNetworkCheck {
  static int pass;
  interface Condition {boolean ready();}
  static void waitFor(Condition condition,String label)throws Exception {
    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
    while(!condition.ready()){if(System.nanoTime()>deadline)throw new AssertionError(label);Thread.sleep(20);}++pass;
  }
  static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);++pass;}
  public static void main(String[] args)throws Exception {
    File root=new File(args[0]),ad=new File(root,"a"),bd=new File(root,"b");
    PeerEngine a=new PeerEngine(ad,"Channel A",new TestProtector(ad)),b=new PeerEngine(bd,"Channel B",new TestProtector(bd));
    CallSettings sa=new CallSettings(ad),sb=new CallSettings(bd);sa.setAllowIncoming(true);sb.setAllowIncoming(true);
    CallController ca=new CallController(a,sa),cb=new CallController(b,sb);
    ICallMedia.Factory readyFake=()->new FakeCallMedia(){
      private String secureAudio(){return "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 9\r\na=fingerprint:sha-256 "+
        String.join(":",java.util.Collections.nCopies(32,"00"))+"\r\na=rtpmap:9 G722/8000\r\n";}
      @Override public String createOffer(){return secureAudio();}
      @Override public String createAnswer(String sdp){return secureAudio();}
      @Override public void setRemoteDescription(String sdp)throws Exception {
        super.setRemoteDescription(sdp);startMedia(); // Test-only readiness after remote SDP, like native callbacks.
      }
    };
    ca.setMediaFactory(readyFake);cb.setMediaFactory(readyFake);
    AtomicReference<CallChannel> sent=new AtomicReference<>();
    try {
      a.start("127.0.0.2",0,0);b.start("127.0.0.3",0,0);
      a.addAddress("127.0.0.3:"+b.port);b.addAddress("127.0.0.2:"+a.port);
      a.verify(b.id,a.pairingCode(b.id));b.verify(a.id,b.pairingCode(a.id));
      ca.start();cb.start();b.callHandler=(peer,cid,socket)->CallChannel.adoptIncoming(socket,peer,cid,cb);
      java.net.Socket wrong=a.openCallConnection(b.id,UUID.randomUUID().toString());
      wrong.getOutputStream().write(CallSignaling.serialize(CallSignaling.invite(UUID.randomUUID().toString(),1,a.id,b.id)));
      wrong.getOutputStream().flush();Thread.sleep(100);
      check(cb.snapshot()==null,"opening INVITE cannot change authenticated CALLCONNECT id");wrong.close();
      String cid=ca.startCall(b.id,id -> {CallChannel channel=CallChannel.openOutgoing(a,b.id,id,ca);sent.set(channel);return channel;});
      waitFor(()->cb.snapshot()!=null&&cb.snapshot().state==CallProtocol.State.IncomingRinging,"bound incoming channel rings");
      check(cid.equals(cb.snapshot().callId),"CALLCONNECT call id retained through handoff");
      cb.accept(cid);
      waitFor(()->ca.snapshot()!=null&&ca.snapshot().state==CallProtocol.State.Connected,"caller connects with fake media");
      waitFor(()->cb.snapshot()!=null&&cb.snapshot().state==CallProtocol.State.Connected,"callee connects with fake media");
      sent.get().send(CallSignaling.serialize(CallSignaling.hangup(UUID.randomUUID().toString(),100)));
      Thread.sleep(100);check(cb.snapshot()!=null&&cb.snapshot().state==CallProtocol.State.Connected,"foreign call frame rejected on socket");
      String another=UUID.randomUUID().toString();
      java.net.Socket unrelated=a.openCallConnection(b.id,another);
      unrelated.getOutputStream().write(CallSignaling.serialize(CallSignaling.invite(another,1,a.id,b.id)));unrelated.getOutputStream().flush();
      Thread.sleep(100);unrelated.close();Thread.sleep(100);
      check(cb.snapshot()!=null&&cb.snapshot().state==CallProtocol.State.Connected,"rejected busy channel closure cannot end current call");
      sent.get().close();waitFor(()->cb.snapshot()==null,"owned channel closure ends its call");
      waitFor(()->ca.snapshot()==null,"owned caller channel closure ends its call");
      ca.configureVideo(true,id->true);cb.configureVideo(true,id->true);
      a.callVideoSupport=()->true;b.callVideoSupport=()->true;
      String videoCid=ca.startCall(b.id,(CallController.TransportFactory)id->CallChannel.openOutgoing(a,b.id,id,ca),true);
      waitFor(()->cb.snapshot()!=null&&cb.snapshot().state==CallProtocol.State.IncomingRinging,"v2 video invitation rings");
      check(cb.snapshot().videoCapable&&cb.snapshot().invitedVideo,"v2 invitation snapshot carries video consent request");
      cb.accept(videoCid,true);
      waitFor(()->ca.snapshot()!=null&&ca.snapshot().state==CallProtocol.State.Connected,"v2 caller secure fake audio connected");
      waitFor(()->cb.snapshot()!=null&&cb.snapshot().state==CallProtocol.State.Connected,"v2 callee secure fake audio connected");
      waitFor(()->ca.snapshot().video!=null&&ca.snapshot().video.localCamera,"initial v2 caller video ready after audio");
      waitFor(()->cb.snapshot().video!=null&&cb.snapshot().video.localCamera,"initial v2 callee video ready after audio");
      ca.video(videoCid).cameraOff();
      waitFor(()->!cb.snapshot().video.remoteCamera,"v2 camera off converges over authenticated socket");
      ca.video(videoCid).receive(videoError(videoCid,ca.snapshot().video.request,ca.snapshot().video.generation));
      waitFor(()->ca.snapshot().video.phase==CallVideoConsent.Phase.Voice,"local video-only failure preserves caller audio");
      check(ca.snapshot().state==CallProtocol.State.Connected,"video-only failure does not end voice session");
      ca.hangup();waitFor(()->cb.snapshot()==null,"v2 hangup tears down entire call");
      System.out.println("CallChannelNetworkCheck PASS="+pass+" FAIL=0 (real TLS/fake media; no device audio/video)");
    } finally {ca.shutdown();cb.shutdown();a.close();b.close();}
  }
  static CallProtocol.Frame videoError(String cid,String request,long gen){
    CallProtocol.Frame f=new CallProtocol.Frame("ERROR",cid,100,gen);f.protocolVersion=2;
    f.body.put("media","video");f.body.put("request",request);f.body.put("code","failed");return f;
  }
}
