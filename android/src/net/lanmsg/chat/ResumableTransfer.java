package net.lanmsg.chat;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import javax.net.ssl.SSLSocket;

/** STREAM1: a single pinned TLS stream, resumed at saved 256 KiB encrypted block boundaries. */
final class ResumableTransfer {
  static void active(PeerEngine e,PeerEngine.Message m,String key)throws IOException {
    if(!e.running||!Boolean.TRUE.equals(e.downloads.get(key)))throw new IOException("Download paused; saved progress kept");
    if(!e.retained(m))throw new IOException("Attachment cleared or expired");
  }
  static List<PeerEngine.Peer> candidates(PeerEngine e,PeerEngine.Message m){
    List<PeerEngine.Peer> peers=new ArrayList<>();
    for(PeerEngine.Peer p:e.peers())if(p.trusted()&&(m.groupId.isEmpty()?p.id.equals(m.from):e.allowedGroup(m.groupId,p.id)))peers.add(p);
    peers.sort((a,b)->Boolean.compare(b.id.equals(m.from),a.id.equals(m.from)));return peers;
  }
  static final class Session implements Closeable {
    final PeerEngine e;final String key;final Socket socket;final String fingerprint;
    Session(PeerEngine e,PeerEngine.Peer peer,PeerEngine.Message m,String key)throws IOException {
      this.e=e;this.key=key;socket=e.connect(peer.host,peer.port);
      try{
        e.downloadSockets.put(key,socket);active(e,m,key);
        fingerprint=SecureIdentity.remote((SSLSocket)socket);
        if(!e.trusted(peer.id,fingerprint))throw new IOException("Device verification changed");
        PeerEngine.write(socket,e.hello());String[] hello=PeerEngine.read(socket).split("\t",-1);
        if(!e.validHello(hello)||!hello[2].equals(peer.id)||!PeerEngine.read(socket).equals("LM4\tREADY"))throw new IOException("Peer handshake failed");
        socket.setSoTimeout(PeerEngine.TRANSFER_READ_TIMEOUT_MS);
      }catch(Exception failure){close();if(failure instanceof IOException)throw (IOException)failure;throw new IOException(failure);}
    }
    public void close()throws IOException {e.downloadSockets.remove(key,socket);socket.close();}
  }
  static boolean supports(PeerEngine e,PeerEngine.Peer p,PeerEngine.Message m,String key)throws IOException {
    try(Session s=new Session(e,p,m,key)){
      PeerEngine.write(s.socket,"LM4\tFILECAPS");
      try{return PeerEngine.read(s.socket).equals("LM4\tFILECAPS\tSTREAM1");}
      catch(EOFException oldPeer){active(e,m,key);return false;}
    }
  }
  static void waitForRetry(PeerEngine e,PeerEngine.Message m,String key)throws IOException {
    active(e,m,key);try{Thread.sleep(500);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IOException("Download paused",failure);}
  }
  static boolean download(PeerEngine e,PeerEngine.Message m,String key)throws IOException {
    Map<String,Boolean> capabilities=new HashMap<>();boolean supported=false;
    while(!supported){
      active(e,m,key);boolean oldPeer=false;
      for(PeerEngine.Peer peer:candidates(e,m))try{
        boolean value=supports(e,peer,m,key);capabilities.put(peer.id,value);
        if(value){supported=true;break;}
        // Prefer the original sender's available protocol over a modern group member
        // that may only have the offer and no file bytes to relay.
        if(peer.id.equals(m.from))return false;
        oldPeer=true;
      }catch(IOException failure){active(e,m,key);}
      if(!supported){if(oldPeer)return false;e.downloadNote(m,"Waiting for sender…");waitForRetry(e,m,key);}
    }
    File partial=new File(e.attachmentPath(m)+".resume");
    try(ResumeStore store=new ResumeStore(partial,m.fileSize,e.protector)){
      // Keep verified 0.8.5 checkpoints when upgrading an interrupted download.
      if(store.done==0&&!store.finished)importOldParts(e,m,key,store);
      e.downloadNote(m,store.done>0?"Restoring saved progress…":"");
      MessageDigest digest=store.digest();byte[] buffer=new byte[ResumeStore.BLOCK];
      e.reportProgress(m.id,store.done,m.fileSize);
      String lastPeer=null,lastFingerprint=null;
      while(!store.finished){
        active(e,m,key);boolean connected=false,legacyCandidate=false;
        for(PeerEngine.Peer peer:candidates(e,m)){
          try{
            Boolean capability=capabilities.get(peer.id);
            if(capability==null){capability=supports(e,peer,m,key);capabilities.put(peer.id,capability);}
            if(!capability){legacyCandidate=true;continue;}
            try(Session session=new Session(e,peer,m,key)){
              long offset=store.done;
              PeerEngine.write(session.socket,"LM4\tFETCHSTREAM\t"+m.from+"\t"+m.id+"\t"+offset);
              String response=PeerEngine.read(session.socket);
              if(response.equals("LM4\tBUSY")||response.equals("LM4\tUNAVAILABLE"))continue;
              if(!response.equals("LM4\tSTREAM\t"+m.id+"\t"+offset+"\t"+(m.fileSize-offset)))throw new IOException("Invalid stream response");
              connected=true;e.downloadNote(m,"");
              do{
                active(e,m,key);
                if(!e.trusted(peer.id,session.fingerprint))throw new IOException("Device verification changed");
                int count=(int)Math.min(buffer.length,m.fileSize-store.done);
                PeerEngine.readTransferBlock(session.socket.getInputStream(),buffer,count);
                active(e,m,key);
                store.append(buffer,count);digest.update(buffer,0,count);
                e.reportProgress(m.id,store.done,m.fileSize);
              }while(!store.finished);
              if(!e.trusted(peer.id,session.fingerprint))throw new IOException("Device verification changed");
              lastPeer=peer.id;lastFingerprint=session.fingerprint;
            }
            break;
          }catch(ResumeStore.StorageFailure failure){throw failure;}
          catch(IOException failure){
            active(e,m,key);
            if(store.finished)throw failure;
            e.downloadNote(m,"Connection interrupted; resuming saved progress…");
            System.err.println("Resuming download at "+store.done+": "+failure);
          }
        }
        // A capability response alone does not prove a peer has the attachment. If all
        // modern sources lack it, let legacy FETCH try an older relay. Once progress is
        // saved, keep the resumable path rather than silently starting over via FETCH.
        if(!store.finished&&!connected&&legacyCandidate&&store.done==0)return false;
        if(!store.finished){if(!connected)e.downloadNote(m,"Waiting for sender; saved progress kept");waitForRetry(e,m,key);}
      }
      e.downloadNote(m,"Checking completed file…");
      if(!PeerEngine.hex(digest.digest()).equals(m.fileHash)){
        store.close();partial.delete();
        throw new IOException("File changed or is damaged. Ask the sender to send it again; download stopped.");
      }
      store.sync();store.close();
      synchronized(e){
        active(e,m,key);
        if(lastPeer!=null&&!e.trusted(lastPeer,lastFingerprint)||lastPeer==null&&candidates(e,m).isEmpty())throw new IOException("Device verification changed");
        PeerEngine.atomicReplace(partial,e.attachmentPath(m));
      }
      e.deleteParts(m);e.downloadNote(m,"");e.notifyChanged();return true;
    }finally{if(!e.retained(m))partial.delete();}
  }
  static void importOldParts(PeerEngine e,PeerEngine.Message m,String key,ResumeStore store)throws IOException {
    byte[] buffer=new byte[ResumeStore.BLOCK];
    for(int part=0;!store.finished&&e.segmentPath(m,part).exists();part++){
      long length=Math.min(PeerEngine.SEGMENT_SIZE,m.fileSize-store.done);
      try(InputStream input=e.openAttachmentPlaintext(e.segmentPath(m,part))){
        while(length>0){active(e,m,key);int count=(int)Math.min(buffer.length,length);PeerEngine.readTransferBlock(input,buffer,count);store.append(buffer,count);length-=count;}
        if(input.read()!=-1)throw new IOException("Invalid saved segment length");
      }
    }
  }
  static void serve(PeerEngine e,Socket socket,String[] request,String peerId,String fingerprint)throws Exception {
    if(!PeerEngine.uuid(request[2])||!PeerEngine.uuid(request[3]))return;
    long offset;try{offset=Long.parseLong(request[4]);}catch(NumberFormatException failure){return;}
    PeerEngine.Message message=null;
    synchronized(e){for(PeerEngine.Message m:e.messages)if(m.from.equals(request[2])&&m.id.equals(request[3])&&(m.groupId.isEmpty()?m.from.equals(e.id)&&m.to.equals(peerId):e.allowedGroup(m.groupId,peerId))){message=m;break;}}
    if(message==null||!e.retained(message)||!e.hasAttachment(message)||offset<0||offset>message.fileSize||offset%ResumeStore.BLOCK!=0){PeerEngine.write(socket,"LM4\tUNAVAILABLE");return;}
    if(!e.fileSlots.tryAcquire()){PeerEngine.write(socket,"LM4\tBUSY");return;}
    try(InputStream source=openAt(e,message,offset)){
      PeerEngine.write(socket,"LM4\tSTREAM\t"+message.id+"\t"+offset+"\t"+(message.fileSize-offset));
      OutputStream output=new PeerEngine.NetworkTimeoutOutputStream(socket);byte[] buffer=new byte[ResumeStore.BLOCK];long at=offset;
      while(at<message.fileSize){
        if(!e.retained(message)||!e.trusted(peerId,fingerprint))throw new IOException("Transfer no longer authorized");
        int count=(int)Math.min(buffer.length,message.fileSize-at);
        PeerEngine.readTransferBlock(source,buffer,count);e.uploadPolicy.write(output,buffer,0,count);at+=count;
      }
    }finally{try{e.uploadPolicy.flush();}finally{e.fileSlots.release();}}
  }
  static InputStream openAt(PeerEngine e,PeerEngine.Message m,long offset)throws IOException {
    if(offset==m.fileSize)return new ByteArrayInputStream(new byte[0]);
    if(!e.sourcePath(m).exists()&&e.attachmentPath(m).exists())return e.openAttachmentPlaintext(e.attachmentPath(m),offset);
    boolean parts=!e.sourcePath(m).exists();
    InputStream source=parts?e.openParts(m,(int)(offset/PeerEngine.SEGMENT_SIZE)):e.openContent(m);
    try{
      long left=parts?offset%PeerEngine.SEGMENT_SIZE:offset;
      while(left>0){long skipped=source.skip(left);if(skipped<=0){if(source.read()<0)throw new EOFException();skipped=1;}left-=skipped;}
      return source;
    }catch(IOException failure){source.close();throw failure;}
  }
}
