package net.lanmsg.chat;
import java.io.File;
import java.util.UUID;
public final class PeerDeliveryPacingCheck {
  static int pass;
  static void check(boolean value,String label){if(!value)throw new AssertionError(label);pass++;}
  public static void main(String[] args)throws Exception {
    PeerEngine.DeliveryPacing gate=new PeerEngine.DeliveryPacing();
    check(gate.claim("a",0,0,false),"initial peer probe");
    for(int now=2000;now<30000;now+=2000)check(!gate.claim("a",0,now,false),"idle tick skipped");
    check(gate.claim("a",0,30000,false),"bounded idle poll");
    check(gate.claim("a",0,30001,true),"new work bypasses idle delay");
    check(!gate.claim("a",0,31000,true),"work retries paced");
    check(gate.claim("a",0,32001,true),"work retry at boundary");
    check(gate.claim("a",1,32002,true),"new queue epoch delivered promptly");
    check(gate.claim("b",1,32002,false),"peers independent");
    gate.clear();check(gate.claim("a",1,32003,false),"restart clears old pacing");
    File root=new File(args[0]);PeerEngine e=new PeerEngine(root,"Pacing",new TestProtector(root));
    try{
      String peer=UUID.randomUUID().toString();
      check(!e.pendingDeliveryWork(peer),"idle engine has no urgent work");
      PeerEngine.Message sent=new PeerEngine.Message(UUID.randomUUID().toString(),e.id,peer,"test",1,"Queued");
      e.messages.add(sent);check(e.pendingDeliveryWork(peer),"queued sends urgent");
      sent.status="Delivered";check(!e.pendingDeliveryWork(peer),"delivered sends not urgent");
      PeerEngine.Message receipt=new PeerEngine.Message(UUID.randomUUID().toString(),peer,e.id,"test",1,"Read");
      e.messages.add(receipt);check(e.pendingDeliveryWork(peer),"read receipt urgent");e.messages.clear();
      e.forgotten.add(peer);check(e.pendingDeliveryWork(peer),"forget retry urgent");e.forgotten.clear();
      e.pendingLeaves.put(UUID.randomUUID().toString(),peer);check(e.pendingDeliveryWork(peer),"leave retry urgent");
      System.out.println("PeerDeliveryPacingCheck PASS="+pass+" FAIL=0");
    }finally{e.close();}
  }
}
