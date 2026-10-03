package net.lanmsg.chat;
import java.io.File;
/** Real authenticated TLS permission-direction and lifecycle checks. */
public final class PermissionDevicesCheck {
  static int pass;
  static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);pass++;}
  public static void main(String[] args)throws Exception {
    for(int mask=0;mask<16;mask++)check(PeerEngine.parseCallGrant("LM4\tCALLGRANTS\t1\t"+mask)==mask,"valid scope mask");
    for(String bad:new String[]{"","LM4\tCALLGRANTS\t1\t16","LM4\tCALLGRANTS\t1\t-1","LM4\tCALLGRANTS\t1\t01","LM4\tCALLGRANTS\t2\t1","LM4\tCALLGRANTS\t1\t1\textra","LM4\tCALLGRANTS\t1\t1.0"})check(PeerEngine.parseCallGrant(bad)==-1,"malformed reply rejected");
    File root=new File(args[0]),ad=new File(root,"a"),bd=new File(root,"b");
    PeerEngine a=new PeerEngine(ad,"Master",new TestProtector(ad)),b=new PeerEngine(bd,"Slave",new TestProtector(bd));
    try{
      a.start("127.0.0.2",0,0);b.start("127.0.0.3",0,0);
      a.addAddress("127.0.0.3:"+b.port);b.addAddress("127.0.0.2:"+a.port);
      check(!a.refreshRemoteCallGrant(b.id),"unverified query rejected");
      a.verify(b.id,a.pairingCode(b.id));b.verify(a.id,b.pairingCode(a.id));
      b.setTrustedCallMask(a.id,15);check(a.refreshRemoteCallGrant(b.id),"real verified query succeeds");
      check(a.remoteCallGrant(b.id).mask==15&&a.trustedCallMask(b.id)==0,"asymmetric remote grant not local authority");
      a.setTrustedCallMask(b.id,4);check(b.refreshRemoteCallGrant(a.id)&&b.remoteCallGrant(a.id).mask==4,"mutual partial scopes");
      PeerEngine stored=new PeerEngine(ad,"Reload",new TestProtector(ad));check(stored.trustedCallMask(b.id)==4,"local grant persists");stored.close();
      PeerEngine.Peer bp=a.peers.get(b.id);String original=bp.fingerprint;bp.fingerprint="changed";
      check(a.remoteCallGrant(b.id)==null,"certificate mismatch invalidates remote status");bp.fingerprint=original;
      check(a.refreshRemoteCallGrant(b.id),"fresh status after certificate restored");
      b.setTrustedCallMask(a.id,0);check(a.refreshRemoteCallGrant(b.id)&&a.remoteCallGrant(b.id).mask==0,"remote revoke removes membership");
      b.simulateLegacyBuild=true;check(!a.refreshRemoteCallGrant(b.id),"legacy unknown extension fails safely");
      b.simulateLegacyBuild=false;check(a.refreshRemoteCallGrant(b.id),"following connection remains usable");
      b.goOffline();check(!a.refreshRemoteCallGrant(b.id)&&a.remoteCallGrant(b.id)!=null,"offline retains informational last-known status");
      a.revoke(b.id);check(a.remoteCallGrant(b.id)==null,"local revoke clears remote cache");
      PeerEngine reload=new PeerEngine(ad,"Reload",new TestProtector(ad));
      check(reload.remoteCallGrant(b.id)==null,"session cache is not restored as current authorization");reload.close();
      System.out.println("PermissionDevicesCheck PASS="+pass+" FAIL=0 (real TLS)");
    }finally{a.close();b.close();}
  }
}
