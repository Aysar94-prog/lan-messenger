package net.lanmsg.chat;

import java.util.*;
import java.util.concurrent.*;

public final class CallFrameAdmissionCheck {
  static int pass;
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);++pass;}
  public static void main(String[] args)throws Exception {
    String cid=UUID.randomUUID().toString(),peer=UUID.randomUUID().toString(),foreign=UUID.randomUUID().toString();
    CallFrameAdmission admission=new CallFrameAdmission(cid,peer,1,0);
    check(!admission.admit(null,peer,true),"null frame rejected");
    check(!admission.admit(CallSignaling.accept(cid,1),foreign,true),"wrong authenticated peer rejected");
    check(!admission.admit(CallSignaling.hangup(foreign,99),peer,true),"wrong call cannot terminate current call");
    CallProtocol.Frame v2=CallSignaling.hangup(cid,99);v2.protocolVersion=2;
    check(!admission.admit(v2,peer,true),"mixed version rejected");
    check(!admission.admit(CallSignaling.accept(cid,99),peer,false),"invalid role/state rejected");
    check(admission.lastAcceptedSequence()==0,"rejected frames do not consume sequence or prove heartbeat");
    check(admission.admit(CallSignaling.ringing(cid,1),peer,true),"fresh valid frame admitted");
    check(!admission.admit(CallSignaling.ringing(cid,1),peer,true),"duplicate does not refresh ringing deadline");
    check(!admission.admit(CallSignaling.accept(cid,0),peer,true),"zero sequence rejected");
    check(admission.admit(CallSignaling.accept(cid,3),peer,true),"sequence gaps allowed");
    check(!admission.admit(CallSignaling.ringing(cid,2),peer,true),"reordered older frame rejected");
    CallFrameAdmission incoming=new CallFrameAdmission(cid,peer,1,20);
    check(!incoming.admit(CallSignaling.hangup(cid,20),peer,true),"incoming INVITE sequence already consumed");
    check(incoming.admit(CallSignaling.offer(cid,21,0,"v=0\r\n"),peer,true),"legacy valid profile unchanged");
    CallFrameAdmission confirmed=new CallFrameAdmission(cid,peer,2,0);
    CallProtocol.Frame unknown=CallSignaling.ping(cid,1);unknown.protocolVersion=2;unknown.body.put("extra",true);
    check(!confirmed.admit(unknown,peer,true),"invalid v2 body rejected before native media");
    unknown.body.clear();check(confirmed.admit(unknown,peer,true),"valid v2 envelope admitted");
    CallFrameAdmission raced=new CallFrameAdmission(cid,peer,1,0);
    ExecutorService pool=Executors.newFixedThreadPool(8);
    try {
      List<Future<Boolean>> results=new ArrayList<>();
      for(int i=0;i<8;i++)results.add(pool.submit(()->raced.admit(CallSignaling.ping(cid,1),peer,true)));
      int successes=0;for(Future<Boolean> result:results)if(result.get())++successes;
      check(successes==1,"concurrent duplicate commits exactly once");
    } finally {pool.shutdownNow();}
    CallFrameAdmission exhausted=new CallFrameAdmission(cid,peer,1,Long.MAX_VALUE);
    check(!exhausted.admit(CallSignaling.ping(cid,Long.MIN_VALUE),peer,true),"counter wrap cannot resurrect signaling");
    System.out.println("CallFrameAdmissionCheck PASS="+pass+" FAIL=0 (A07 envelope boundary; video integration separate)");
  }
}
