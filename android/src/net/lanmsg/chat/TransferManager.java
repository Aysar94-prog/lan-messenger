package net.lanmsg.chat;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.net.ssl.SSLSocket;

/** Download/transfer bookkeeping: destinations, segment paths, image auto-download, FETCH serving. */
final class TransferManager {
  private TransferManager(){}
  static String savedDestination(PeerEngine e,PeerEngine.Message m){DownloadDestination value=DownloadDestination.get(e,m);if(value.complete)return value.reference;try{if(m.from.equals(e.id)&&sourcePath(e,m).exists())return new String(e.protector.unprotect(SecureIdentity.readFile(sourcePath(e,m))),StandardCharsets.UTF_8);}catch(Exception ignored){}return "";}
  static String pendingDestination(PeerEngine e,PeerEngine.Message m){DownloadDestination value=DownloadDestination.get(e,m);return value.complete?"":value.reference;}
  static void downloadTo(PeerEngine e,PeerEngine.Message m,String reference)throws IOException {if(!reference.equals(savedDestination(e,m)))DirectFileTransfer.download(e,m,reference);}
  static boolean isFastAttachment(PeerEngine e,PeerEngine.Message m)throws IOException{return sourcePath(e,m).exists()||DownloadDestination.get(e,m).fast;}
  static String downloadNote(PeerEngine e,PeerEngine.Message m){String value=e.downloadNotes.get(m.from+"/"+m.id);return value==null?"":value;}
  static void downloadNote(PeerEngine e,PeerEngine.Message m,String value){String old=e.downloadNotes.put(m.from+"/"+m.id,value);if(!value.equals(old))e.notifyChanged();}
  static File sourcePath(PeerEngine e,PeerEngine.Message m)throws IOException{return new File(AttachmentStore.attachmentPath(e,m)+".source");}
  static File partsPath(PeerEngine e,PeerEngine.Message m)throws IOException{return new File(AttachmentStore.attachmentPath(e,m)+".parts");}
  static File segmentPath(PeerEngine e,PeerEngine.Message m,int part)throws IOException{return new File(partsPath(e,m),part+".sec");}
  static boolean hasAttachment(PeerEngine e,PeerEngine.Message m){try{return !m.fileName.isEmpty()&&(DownloadDestination.get(e,m).complete||AttachmentStore.attachmentPath(e,m).exists()||sourcePath(e,m).exists()||new File(partsPath(e,m),"complete").exists());}catch(Exception ex){return false;}}
  static boolean retained(PeerEngine e,PeerEngine.Message m){synchronized(e){if(e.hidden.contains(m.from+"/"+m.id)||(m.ttlEligible&&System.currentTimeMillis()>m.time+PeerEngine.GROUP_TTL_MS))return false;for(PeerEngine.Message row:e.messages)if(row.from.equals(m.from)&&row.id.equals(m.id))return true;return false;}}
  static void queueImageDownloads(PeerEngine e){
    if(!e.running)return;java.util.ArrayList<PeerEngine.Message> images=new java.util.ArrayList<>();
    synchronized(e){for(PeerEngine.Message m:e.messages)if(!m.from.equals(e.id)&&PeerEngine.isImageAttachment(m))images.add(m.copy());}
    for(PeerEngine.Message m:images){
      String key=m.from+"/"+m.id;
      if(hasAttachment(e,m)||downloading(e,m)||e.imageAttempts.contains(key)||!retained(e,m))continue;
      boolean reachable=false;for(PeerEngine.Peer p:e.peers())if(p.trusted()&&p.online()&&(p.id.equals(m.from)||(!m.groupId.isEmpty()&&GroupSync.allowedGroup(e,m.groupId,p.id)))){reachable=true;break;}
      if(!reachable)continue;if(!e.imageSlots.tryAcquire())break;
      if(!e.imageAttempts.add(key)){e.imageSlots.release();continue;}
      Thread worker=new Thread(()->{try{downloadAttachment(e,m);}catch(Exception ignored){}finally{e.imageSlots.release();e.notifyChanged();}},"lan-image-download");worker.setDaemon(true);worker.start();
    }
  }
  static boolean downloading(PeerEngine e,PeerEngine.Message m){return e.downloads.containsKey(m.from+"/"+m.id);}
  static void cancelDownload(PeerEngine e,PeerEngine.Message m){String key=m.from+"/"+m.id;e.downloads.computeIfPresent(key,(k,value)->false);Socket socket=e.downloadSockets.get(key);if(socket!=null)try{socket.close();}catch(IOException ignored){}}
  static void deleteTransfer(PeerEngine e,PeerEngine.Message m){cancelDownload(e,m);e.downloadNotes.remove(m.from+"/"+m.id);try{DownloadDestination.forget(e,m);AttachmentStore.attachmentPath(e,m).delete();new File(AttachmentStore.attachmentPath(e,m)+".resume").delete();sourcePath(e,m).delete();deleteParts(e,m);}catch(Exception ignored){}}
  static void deleteParts(PeerEngine e,PeerEngine.Message m)throws IOException{File dir=partsPath(e,m);File[] children=dir.listFiles();if(children!=null)for(File child:children)child.delete();dir.delete();}
  static String hashStream(InputStream in)throws IOException{try{MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] b=new byte[256*1024];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);return PeerEngine.hex(hash.digest());}catch(java.security.GeneralSecurityException ex){throw new IOException(ex);}}
  static void queueFastFile(PeerEngine e,String conversation,String caption,String reference,long size,String name)throws IOException{
    if(size<0||size>PeerEngine.MAX_FAST_FILE_SIZE)throw new IOException("Fast transfer limit is 1 TiB.");
    caption=caption.trim();if(caption.length()>2000)throw new IOException("Caption too long.");
    java.util.ArrayList<String> recipients=new java.util.ArrayList<>();String group="";
    synchronized(e){PeerEngine.Group g=e.groups.get(conversation);if(g!=null){group=g.id;for(String member:g.members)if(!member.equals(e.id))recipients.add(member);}else if(e.peers.containsKey(conversation))recipients.add(conversation);else throw new IOException("Choose a conversation");}
    String hash;try(InputStream in=e.sourceOpener.open(reference)){if(in==null)throw new IOException("Source unavailable");try{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[262144];long total=0;int n;while((n=in.read(buffer))!=-1){total+=n;if(total>size)throw new IOException("Source changed");digest.update(buffer,0,n);}if(total!=size)throw new IOException("Source changed");hash=PeerEngine.hex(digest.digest());}catch(java.security.GeneralSecurityException error){throw new IOException(error);}}
    String mid=java.util.UUID.randomUUID().toString();long at=System.currentTimeMillis();name=PeerEngine.safeFileName(name);String signature="";
    try{if(!group.isEmpty())signature=java.util.Base64.getEncoder().encodeToString(e.identity.sign(PeerEngine.canonicalBytes(mid,group,e.id,at,caption,name,size,hash)));}catch(Exception ex){throw new IOException(ex);}
    java.util.ArrayList<PeerEngine.Message> batch=new java.util.ArrayList<>();for(String to:recipients)batch.add(new PeerEngine.Message(mid,e.id,to,caption,at,"Queued",group,name,size,hash,signature,!group.isEmpty()));
    File path=sourcePath(e,batch.get(0));path.getParentFile().mkdirs();
    try(FileOutputStream out=new FileOutputStream(path)){out.write(e.protector.protect(reference.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IOException(ex);}
    synchronized(e){e.messages.addAll(batch);try{e.save();}catch(IOException ex){e.messages.removeAll(batch);path.delete();throw ex;}}
    e.notifyChanged();e.queueEpoch.incrementAndGet();e.flush();
  }
  static InputStream openContent(PeerEngine e,PeerEngine.Message m)throws IOException{
    DownloadDestination destination=DownloadDestination.get(e,m);if(destination.complete)return e.sourceOpener.open(destination.reference);
    File source=sourcePath(e,m);
    if(source.exists())try{return e.sourceOpener.open(new String(e.protector.unprotect(SecureIdentity.readFile(source)),StandardCharsets.UTF_8));}catch(Exception ex){throw new IOException(ex);}
    if(AttachmentStore.attachmentPath(e,m).exists())return AttachmentStore.openAttachmentPlaintext(e,AttachmentStore.attachmentPath(e,m));
    if(!new File(partsPath(e,m),"complete").exists())throw new IOException("Download this attachment first.");
    return openParts(e,m);
  }
  static InputStream openParts(PeerEngine e,PeerEngine.Message m)throws IOException{return openParts(e,m,0);}
  static InputStream openParts(PeerEngine e,PeerEngine.Message m,int start)throws IOException{
    final int count=(int)Math.max(1,(m.fileSize+PeerEngine.SEGMENT_SIZE-1)/PeerEngine.SEGMENT_SIZE);
    return new InputStream(){int index=start;InputStream current;
      public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
      public int read(byte[] b,int offset,int length)throws IOException{
        if(length==0)return 0;
        while(true){if(current==null){if(index>=count)return -1;current=AttachmentStore.openAttachmentPlaintext(e,segmentPath(e,m,index++));}int n=current.read(b,offset,length);if(n>=0)return n;current.close();current=null;}
      }
      public void close()throws IOException{if(current!=null)current.close();}
    };
  }
  static void serveDownload(PeerEngine e,Socket s,String[] request,String peerId)throws Exception{
    if(!PeerEngine.uuid(request[2])||!PeerEngine.uuid(request[3]))return;
    long offset,count;try{offset=Long.parseLong(request[4]);count=Long.parseLong(request[5]);}catch(Exception ex){return;}
    PeerEngine.Message m=null;synchronized(e){for(PeerEngine.Message row:e.messages)if(row.from.equals(request[2])&&row.id.equals(request[3])&&(!row.groupId.isEmpty()?GroupSync.allowedGroup(e,row.groupId,peerId):row.from.equals(e.id)&&row.to.equals(peerId))){m=row;break;}}
    if(m==null||!retained(e,m)||!hasAttachment(e,m)||offset<0||count<0||count>PeerEngine.SEGMENT_SIZE||offset>m.fileSize||count>m.fileSize-offset||offset%PeerEngine.SEGMENT_SIZE!=0||count!=Math.min(PeerEngine.SEGMENT_SIZE,m.fileSize-offset)){PeerEngine.write(s,"LM4\tUNAVAILABLE");return;}
    if(isFastAttachment(e,m)){PeerEngine.write(s,"LM4\tFASTONLY");return;}
    if(!e.fileSlots.tryAcquire()){PeerEngine.write(s,"LM4\tBUSY");return;}
    boolean sourceReference=sourcePath(e,m).exists()||DownloadDestination.get(e,m).complete;
    File stored=AttachmentStore.attachmentPath(e,m);
    boolean storedSnapshot=!sourceReference&&stored.exists();
    boolean segmented=!sourceReference&&!storedSnapshot;
    try(InputStream source=segmented?openParts(e,m,(int)(offset/PeerEngine.SEGMENT_SIZE)):storedSnapshot?AttachmentStore.openAttachmentPlaintext(e,stored,offset):openContent(e,m)){
      long skip=segmented||storedSnapshot?0:offset;while(skip>0){long n=source.skip(skip);if(n==0){if(source.read()<0)throw new EOFException();n=1;}skip-=n;}
      s.setSoTimeout(PeerEngine.TRANSFER_READ_TIMEOUT_MS);
      PeerEngine.write(s,"LM4\tDATA\t"+m.id+"\t"+offset+"\t"+count);
      MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(Exception ex){throw new IOException(ex);}
      OutputStream out=new PeerEngine.NetworkTimeoutOutputStream(s);byte[] buffer=new byte[256*1024];long done=0;
      // The certificate is fixed for this TLS session. Recheck its pin, but do not hash and
      // format the certificate again for every small CipherInputStream read.
      String transferFingerprint=SecureIdentity.remote((SSLSocket)s);
      while(done<count){
        if(!retained(e,m)||!e.trusted(peerId,transferFingerprint))throw new IOException("Transfer no longer authorized");
        int wanted=(int)Math.min(buffer.length,count-done),n=0;
        // CipherInputStream may return just one small internal buffer. Fill a network block
        // before scheduling a watchdog, checking the quota and writing a TLS record batch.
        while(n<wanted){int got=source.read(buffer,n,wanted-n);if(got<0)throw new EOFException();if(got>0)n+=got;}
        digest.update(buffer,0,n);e.uploadPolicy.write(out,buffer,0,n);done+=n;
      }
      PeerEngine.write(s,"LM4\tPART\t"+PeerEngine.hex(digest.digest()));
    }finally{try{e.uploadPolicy.flush();}finally{e.fileSlots.release();}}
  }
  static void downloadAttachment(PeerEngine e,PeerEngine.Message m)throws IOException{
    if(m.from.equals(e.id)||hasAttachment(e,m))return;
    String key=m.from+"/"+m.id;if(e.downloads.putIfAbsent(key,true)!=null)return;
    try{
      downloadNote(e,m,"");
      if(ResumableTransfer.download(e,m,key))return;
      downloadNote(e,m,"Older sender: update both phones for continuous resume");
      partsPath(e,m).mkdirs();int parts=(int)Math.max(1,(m.fileSize+PeerEngine.SEGMENT_SIZE-1)/PeerEngine.SEGMENT_SIZE);
      for(int i=0;i<parts;i++){
        long offset=i*PeerEngine.SEGMENT_SIZE,count=Math.min(PeerEngine.SEGMENT_SIZE,m.fileSize-offset);File part=segmentPath(e,m,i),pending=new File(part+".pending");
        if(part.exists()){e.reportProgress(m.id,offset+count,m.fileSize);continue;}
        while(true){
          if(!e.running||!Boolean.TRUE.equals(e.downloads.get(key)))throw new IOException("Download paused");
          if(!retained(e,m))throw new IOException("Attachment cleared or expired");
          boolean fetched=false;
          for(PeerEngine.Peer p:e.peers()){
            if(!p.trusted()||(!m.groupId.isEmpty()?!GroupSync.allowedGroup(e,m.groupId,p.id):!p.id.equals(m.from)))continue;
            try(Socket s=e.connect(p.host,p.port)){
              String fp=SecureIdentity.remote((SSLSocket)s);if(!e.trusted(p.id,fp))continue;
              PeerEngine.write(s,e.hello());String[] h=PeerEngine.read(s).split("\t",-1);if(!e.validHello(h)||!h[2].equals(p.id)||!PeerEngine.read(s).equals("LM4\tREADY"))continue;
              PeerEngine.write(s,"LM4\tFETCH\t"+m.from+"\t"+m.id+"\t"+offset+"\t"+count);
              s.setSoTimeout(PeerEngine.TRANSFER_READ_TIMEOUT_MS);
              String response=PeerEngine.read(s);if(response.equals("LM4\tFASTONLY"))throw new DirectFileTransfer.DestinationRequired();
              if(!response.equals("LM4\tDATA\t"+m.id+"\t"+offset+"\t"+count))continue;
              final long base=offset;
              String hash=AttachmentStore.storeAttachmentStreamAt(e,m,s.getInputStream(),count,(done,total)->{
                if(!Boolean.TRUE.equals(e.downloads.get(key)))try{s.close();}catch(IOException ignored){}
                e.reportProgress(m.id,base+done,m.fileSize);
              },pending);
              if(!PeerEngine.read(s).equals("LM4\tPART\t"+hash))throw new IOException("Segment integrity check failed");
              if(!retained(e,m)||!e.trusted(p.id,fp))throw new IOException("Attachment no longer authorized");
              if(!pending.renameTo(part))throw new IOException("Cannot commit segment");
              fetched=true;break;
            }catch(DirectFileTransfer.DestinationRequired required){throw required;}
            catch(Exception ex){pending.delete();System.err.println("LAN Messenger download retry: segment "+i+" from "+p.id+": "+ex);}
          }
          if(fetched)break;
          try{Thread.sleep(2000);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IOException("Download paused",ex);}
        }
      }
      String hash;try(InputStream in=openParts(e,m)){hash=hashStream(in);}
      if(!hash.equals(m.fileHash)){deleteParts(e,m);throw new IOException("Source changed or attachment is corrupt. Retry download.");}
      if(!retained(e,m))throw new IOException("Attachment cleared or expired");
      if(!Boolean.TRUE.equals(e.downloads.get(key)))throw new IOException("Download paused");
      try(FileOutputStream out=new FileOutputStream(new File(partsPath(e,m),"complete"))){out.write(hash.getBytes(StandardCharsets.US_ASCII));}
      e.notifyChanged();
    }catch(IOException failure){downloadNote(e,m,String.valueOf(failure.getMessage()));throw failure;}
    finally{e.downloads.remove(key);e.downloadSockets.remove(key);e.lastReportedPercent.remove(m.id);e.notifyChanged();}
  }
}
