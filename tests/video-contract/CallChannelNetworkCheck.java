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
      System.out.println("CallChannelNetworkCheck PASS="+pass+" FAIL=0 (real TLS/fake media; no device audio/video)");
    } finally {ca.shutdown();cb.shutdown();a.close();b.close();}
  }
}
