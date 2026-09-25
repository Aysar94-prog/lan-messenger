package net.lanmsg.chat;

import java.io.*;
import java.net.Socket;
import java.util.UUID;
import javax.net.ssl.SSLSocket;

/** Profile-picture storage (own and peers') and the AVATAR push/pull exchanged during delivery. */
final class AvatarSync {
  private AvatarSync(){}
  static File avatarPath(PeerEngine e){return new File(e.file.getParentFile(),"avatar.sec");}
  static byte[] avatar(PeerEngine e){synchronized(e){File path=avatarPath(e);if(!path.exists())return null;try{return e.protector.unprotect(SecureIdentity.readFile(path));}catch(Exception ex){return null;}}}
  static void setAvatar(PeerEngine e,byte[] data)throws IOException {
    synchronized(e){
      File path=avatarPath(e);if(data==null){if(path.exists()&&!path.delete())throw new IOException("Cannot remove profile picture");e.notifyChanged();return;}
      File tmp=new File(path+"."+UUID.randomUUID()+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(e.protector.protect(data));out.getFD().sync();}catch(Exception ex){throw new IOException(ex);}PeerEngine.atomicReplace(tmp,path);
    }
    e.notifyChanged();
  }
  // A peer's photo, once they've sent it over a verified connection — never fetched or guessed, only what they pushed us.
  static File peerAvatarPath(PeerEngine e,String peerId){return new File(new File(e.file.getParentFile(),"avatars"),peerId+".sec");}
  static byte[] peerAvatar(PeerEngine e,String peerId){synchronized(e){File path=peerAvatarPath(e,peerId);if(!path.exists())return null;try{return e.protector.unprotect(SecureIdentity.readFile(path));}catch(Exception ex){return null;}}}
  static void setPeerAvatar(PeerEngine e,String peerId,byte[] data)throws IOException {
    File path=peerAvatarPath(e,peerId);if(!path.getParentFile().exists()&&!path.getParentFile().mkdirs())throw new IOException("Cannot create avatar storage");
    if(data==null||data.length==0){if(path.exists()&&!path.delete())throw new IOException("Cannot remove contact picture");return;}
    File tmp=new File(path+"."+UUID.randomUUID()+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(e.protector.protect(data));out.getFD().sync();}catch(Exception ex){throw new IOException(ex);}PeerEngine.atomicReplace(tmp,path);
  }
  static final int MAX_AVATAR_SIZE=2_000_000;
  static void handleAvatar(PeerEngine e,Socket s,String senderId,String hash,String lengthText)throws IOException {
    int length;try{length=Integer.parseInt(lengthText);}catch(NumberFormatException ex){return;}
    if(length<0||length>MAX_AVATAR_SIZE)return;
    byte[] data=length>0?AttachmentStore.readBytes(s,length):new byte[0];
    try{if(length>0&&!SecureIdentity.hash(data).equals(hash))return;}catch(Exception ex){throw new IOException(ex);}
    synchronized(e){PeerEngine.Peer p=e.peers.get(senderId);if(p==null||!p.trusted())return;}
    setPeerAvatar(e,senderId,length>0?data:null);
    synchronized(e){PeerEngine.Peer p=e.peers.get(senderId);if(p==null)return;String old=p.receivedAvatarHash;p.receivedAvatarHash=hash;try{e.save();}catch(IOException ex){p.receivedAvatarHash=old;throw ex;}}
    PeerEngine.write(s,"LM4\tAVATARACK\t"+hash);
  }
  // Pushes our profile picture to a trusted peer only when it differs from what they last
  // acknowledged; called as the final step of deliver() for that peer.
  static void pushAvatarIfChanged(PeerEngine e,PeerEngine.Peer p){
    if(!p.trusted())return;
    byte[] avatarData=avatar(e);String hash;try{hash=avatarData!=null?SecureIdentity.hash(avatarData):"";}catch(Exception ex){return;}
    if(p.sentAvatarHash.equals(hash))return;
    try(Socket s=e.connect(p.host,p.port)){
      String fingerprint=SecureIdentity.remote((SSLSocket)s);if(!e.trusted(p.id,fingerprint))return;PeerEngine.write(s,e.hello());String[] h=PeerEngine.read(s).split("\t",-1);if(!e.validHello(h)||!h[2].equals(p.id))return;e.recordCertificate(h[2],fingerprint,SecureIdentity.remotePublicKey((SSLSocket)s));if(!PeerEngine.read(s).equals("LM4\tREADY")||!e.trusted(p.id,fingerprint))return;
      PeerEngine.write(s,"LM4\tAVATAR\t"+e.id+"\t"+hash+"\t"+(avatarData!=null?avatarData.length:0));
      if(avatarData!=null&&avatarData.length>0){OutputStream out=new PeerEngine.NetworkTimeoutOutputStream(s);out.write(avatarData);out.flush();}
      if(!PeerEngine.read(s).equals("LM4\tAVATARACK\t"+hash)||!e.trusted(p.id,fingerprint))return;
      synchronized(e){PeerEngine.Peer current=e.peers.get(p.id);if(current==null)return;String old=current.sentAvatarHash;current.sentAvatarHash=hash;try{e.save();}catch(IOException ex){current.sentAvatarHash=old;throw ex;}}e.notifyChanged();
    }catch(Exception ex){return;}
  }
}
