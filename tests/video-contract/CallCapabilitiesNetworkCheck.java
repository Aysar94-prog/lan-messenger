package net.lanmsg.chat;
import java.io.File;

/** Real verified TLS engines, loopback only; no call camera/media. */
public final class CallCapabilitiesNetworkCheck {
  static int pass;
  static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);++pass;}
  public static void main(String[] args)throws Exception {
    File root=new File(args[0]),ad=new File(root,"a"),bd=new File(root,"b");
    PeerEngine a=new PeerEngine(ad,"Capabilities A",new TestProtector(ad));
    PeerEngine b=new PeerEngine(bd,"Capabilities B",new TestProtector(bd));
    try {
      a.start("127.0.0.2",0,0);b.start("127.0.0.3",0,0);
      a.addAddress("127.0.0.3:"+b.port);b.addAddress("127.0.0.2:"+a.port);
      check(!a.probeCallVideo(b.id),"unverified peer never probed for video");
      a.verify(b.id,a.pairingCode(b.id));b.verify(a.id,b.pairingCode(a.id));
      check(!a.probeCallVideo(b.id),"default unready responder falls back to voice");
      b.callVideoSupport=()->true;
      check(a.probeCallVideo(b.id),"fresh authenticated TLS capability exchange succeeds");
      b.callVideoSupport=()->false;
      check(!a.probeCallVideo(b.id),"prior capability success not cached");
      b.callVideoSupport=()->true;b.simulateLegacyBuild=true;
      check(!a.probeCallVideo(b.id),"remote simulated legacy falls back to voice");
      b.simulateLegacyBuild=false;a.simulateLegacyBuild=true;
      check(!a.probeCallVideo(b.id),"local legacy suppresses outgoing capability");
      a.simulateLegacyBuild=false;
      b.revoke(a.id);check(!a.probeCallVideo(b.id),"remote verification revocation fails safely");
      a.goOffline();check(!a.probeCallVideo(b.id),"offline probe fails without network work");
      System.out.println("CallCapabilitiesNetworkCheck PASS="+pass+" FAIL=0 (real TLS loopback; no device media)");
    } finally {a.close();b.close();}
  }
}
