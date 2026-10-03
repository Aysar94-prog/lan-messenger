package net.lanmsg.chat;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Real TLS boundaries, persistence and delivery; no device stability claim. */
public final class DirectConnectionsCheck {
  static int passed;
  static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);passed++;}
  interface Action{void run()throws Exception;}
  static void denied(Action action,String label)throws Exception{try{action.run();throw new AssertionError(label);}catch(IOException expected){passed++;}}
  static PeerEngine make(File root,String name)throws Exception{File dir=new File(root,name);return new PeerEngine(dir,name,new TestProtector(dir));}
  static void pair(PeerEngine a,PeerEngine b)throws Exception {
    a.addAddress(b.bind+":"+b.port);a.verify(b.id,a.pairingCode(b.id));
  }
  static int freeUdp()throws Exception{try(DatagramSocket s=new DatagramSocket(0)){return s.getLocalPort();}}
  static void fast(PeerEngine sender,PeerEngine receiver,File root,String label)throws Exception {
    byte[] payload=("fast "+label).getBytes("UTF-8");File source=new File(root,label+".source"),dest=new File(root,label+".download");Files.write(source.toPath(),payload);
    sender.queueFastFile(receiver.id,"",source.getPath(),payload.length,label+".bin");
    PeerEngine.Message message=null;long deadline=System.currentTimeMillis()+8000;
    while(message==null&&System.currentTimeMillis()<deadline){for(PeerEngine.Message m:receiver.messages(sender.id))if(m.fileName.equals(label+".bin"))message=m;if(message==null)Thread.sleep(50);}
    check(message!=null,"fast metadata "+label);receiver.downloadTo(message,dest.getPath());check(Arrays.equals(payload,Files.readAllBytes(dest.toPath())),"fast payload "+label);
  }
  public static void main(String[] args)throws Exception{
    File root=Files.createTempDirectory(Paths.get(args[0]),"direct-check-").toFile();
    PeerEngine a=make(root,"a"),b=make(root,"b"),c=make(root,"c");
    try{
      PeerEngine.DeliveryPacing pace=new PeerEngine.DeliveryPacing();check(pace.claim("selected",0,0,false,8000),"first direct probe");check(!pace.claim("selected",0,7999,false,8000),"direct probe bounded");check(pace.claim("selected",0,8000,false,8000),"direct presence before legacy 12s expiry");
      b.start("127.0.0.2",0,freeUdp());c.start("127.0.0.3",0,freeUdp());
      a.start("127.0.0.1",0,freeUdp());pair(a,b);pair(b,a);pair(a,c);pair(c,a);
      int aPort=a.port;a.goOffline();
      Map<String,String> targets=new LinkedHashMap<>();targets.put(b.id,"127.0.0.2:"+b.port);a.configureDirect(true,targets);
      a.start("127.0.0.1",aPort,freeUdp());
      check(a.discovery==null,"no discovery socket");a.announce();
      check(a.directOnly(),"mode enabled");check(!a.directHostAllowed(c.bind),"unselected IP denied");
      final PeerEngine first=a;
      denied(()->first.connect(c.bind,c.port),"outbound blocked before connect");
      denied(()->first.configureDirect(false,targets),"live reconfiguration rejected");
      a.queue(b.id,"selected delivery");a.queue(c.id,"blocked delivery");
      long deadline=System.currentTimeMillis()+8000;
      while(System.currentTimeMillis()<deadline&&b.messages(a.id).isEmpty())Thread.sleep(50);
      check(b.messages(a.id).size()==1,"selected TLS message delivered");check(c.messages(a.id).isEmpty(),"other queued message not delivered");
      byte[] payload="selected attachment".getBytes("UTF-8");a.queueFile(b.id,"direct.txt",payload);
      deadline=System.currentTimeMillis()+8000;while(System.currentTimeMillis()<deadline&&b.messages(a.id).size()<2)Thread.sleep(50);
      PeerEngine.Message attachment=b.messages(a.id).stream().filter(m->!m.fileName.isEmpty()).findFirst().orElseThrow(()->new AssertionError("attachment metadata"));
      b.downloadAttachment(attachment);check(Arrays.equals(payload,b.readAttachment(attachment)),"selected attachment bytes delivered");
      java.util.concurrent.atomic.AtomicBoolean call=new java.util.concurrent.atomic.AtomicBoolean();b.callHandler=(id,callId,socket)->{call.set(true);try{socket.close();}catch(Exception ignored){}};
      try(Socket socket=a.openCallConnection(b.id,UUID.randomUUID().toString())){deadline=System.currentTimeMillis()+2000;while(!call.get()&&System.currentTimeMillis()<deadline)Thread.sleep(20);check(call.get(),"selected authenticated call channel");}
      denied(()->first.openCallConnection(c.id,UUID.randomUUID().toString()),"unselected call blocked");
      fast(a,b,root,"outgoing");fast(b,a,root,"incoming");
      c.queue(a.id,"unselected incoming");Thread.sleep(2500);check(a.messages(c.id).size()==1,"unselected incoming blocked");
      check(a.peers().stream().filter(p->p.id.equals(c.id)).noneMatch(p->p.online()),"unselected shown offline");
      check(a.directCandidateAllowed("candidate:1 1 UDP 1 127.0.0.2 4000 typ host"),"selected ICE allowed");
      check(!a.directCandidateAllowed("candidate:1 1 UDP 1 127.0.0.3 4000 typ host"),"unselected ICE blocked");
      String sdp=a.directMediaSdp("v=0\r\nc=IN IP4 0.0.0.0\r\na=candidate:1 1 UDP 1 127.0.0.3 4000 typ host\r\n");check(!sdp.contains("candidate:"),"SDP candidates filtered");
      denied(()->first.directMediaSdp("c=IN IP4 127.0.0.3\r\n"),"SDP destination blocked");
      a.revoke(b.id);check(!a.directHostAllowed(b.bind),"revoked device blocked");a.verify(b.id,a.pairingCode(b.id));
      a.goOffline();String aId=a.id;a.close();a=make(root,"a");check(a.id.equals(aId)&&a.directOnly(),"mode and identity survive reload");check(a.directAddress(b.id).equals("127.0.0.2:"+b.port),"target persists");
      Map<String,String> wrong=new LinkedHashMap<>();wrong.put(b.id,"127.0.0.3:"+c.port);a.configureDirect(true,wrong);a.start("127.0.0.1",aPort,freeUdp());
      final PeerEngine current=a;denied(()->current.connect(c.bind,c.port),"wrong certificate at selected address blocked");
      a.goOffline();a.configureDirect(false,targets);a.start("127.0.0.1",aPort,freeUdp());check(a.discovery!=null,"normal discovery restored");
      denied(()->PeerEngine.localAddress("255.255.255.255"),"broadcast rejected");denied(()->PeerEngine.localAddress("192.168.1.999"),"invalid IP rejected");denied(()->PeerEngine.localAddress("192.168.1.1:0"),"invalid port rejected");
      a.goOffline();a.configureDirect(true,targets);a.deleteAllData();check(a.directTargets.isEmpty()&&a.directOnly(),"delete all clears targets and keeps restriction");a.close();
      Files.write(new File(root,"a/direct-connections.sec").toPath(),new byte[]{1,2,3});a=make(root,"a");check(a.directOnly()&&!a.directSettingsProblem.isEmpty(),"corrupt settings fail closed with local recovery");
      a.configureDirect(false,Collections.emptyMap());check(!a.directOnly()&&a.directSettingsProblem.isEmpty(),"explicit recovery restores normal mode");
      System.out.println("DirectConnections PASS="+passed+" FAIL=0");
    }finally{a.close();b.close();c.close();}
  }
}
