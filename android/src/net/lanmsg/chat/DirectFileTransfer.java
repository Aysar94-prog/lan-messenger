package net.lanmsg.chat;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** User-selected single-copy downloads. Fast payloads use a one-use authorized plain socket. */
final class DirectFileTransfer {
  static final int BLOCK=256*1024;
  static final class DestinationRequired extends IOException {DestinationRequired(){super("Choose a download destination. Both devices need the latest version for Fast file.");}}
  static final class StorageFailure extends IOException {StorageFailure(Exception cause){super("Cannot write the selected destination: "+cause.getMessage(),cause);}}
  static void serve(PeerEngine e,Socket control,String[] request,String peerId,String fingerprint)throws Exception {
    if(!PeerEngine.uuid(request[2])||!PeerEngine.uuid(request[3]))return;
    long offset;try{offset=Long.parseLong(request[4]);}catch(NumberFormatException invalid){return;}
    PeerEngine.Message message=null;
    synchronized(e){for(PeerEngine.Message m:e.messages)if(m.from.equals(request[2])&&m.id.equals(request[3])&&(m.groupId.isEmpty()?m.from.equals(e.id)&&m.to.equals(peerId):e.allowedGroup(m.groupId,peerId))){message=m;break;}}
    if(message==null||!e.retained(message)||!e.hasAttachment(message)||offset<0||offset>message.fileSize||offset%BLOCK!=0){PeerEngine.write(control,"LM4\tUNAVAILABLE");return;}
    if(!e.fileSlots.tryAcquire()){PeerEngine.write(control,"LM4\tBUSY");return;}
    try(InputStream input=ResumableTransfer.openAt(e,message,offset)){
      String header="LM4\tDIRECT\t"+message.id+"\t"+offset+"\t"+(message.fileSize-offset);
      if(!e.isFastAttachment(message)){
        PeerEngine.write(control,header+"\tTLS");send(e,message,input,control,offset,peerId,fingerprint);return;
      }
      byte[] random=new byte[32];new SecureRandom().nextBytes(random);String token=PeerEngine.hex(random);
      try(ServerSocket listener=new ServerSocket()){
        listener.bind(new InetSocketAddress(e.bind,0));listener.setSoTimeout(15000);
        PeerEngine.write(control,header+"\tRAW\t"+listener.getLocalPort()+"\t"+token);
        long deadline=System.nanoTime()+15_000_000_000L;
        for(int attempt=0;attempt<4&&System.nanoTime()<deadline;attempt++){
          listener.setSoTimeout((int)Math.max(1,(deadline-System.nanoTime())/1_000_000));
          try(Socket raw=listener.accept()){
            raw.setSoTimeout(2000);raw.setTcpNoDelay(true);
            if(!raw.getInetAddress().equals(control.getInetAddress()))continue;
            String supplied;try{supplied=PeerEngine.read(raw);}catch(IOException invalid){continue;}
            if(!MessageDigest.isEqual(supplied.getBytes(StandardCharsets.US_ASCII),("LM4\tTOKEN\t"+token).getBytes(StandardCharsets.US_ASCII)))continue;
            if(!e.retained(message)||!e.trusted(peerId,fingerprint))return;
            listener.close(); // Consume the one-use authorization before any payload is sent.
            PeerEngine.write(raw,"LM4\tRAWREADY");send(e,message,input,raw,offset,peerId,fingerprint);return;
          }
        }
      }
    }finally{try{e.uploadPolicy.flush();}finally{e.fileSlots.release();}}
  }
  static void send(PeerEngine e,PeerEngine.Message m,InputStream input,Socket socket,long offset,String peer,String fingerprint)throws Exception {
    OutputStream output=new PeerEngine.NetworkTimeoutOutputStream(socket);byte[] buffer=new byte[BLOCK];
    for(long at=offset;at<m.fileSize;){
      if(!e.retained(m)||!e.trusted(peer,fingerprint))throw new IOException("Transfer no longer authorized");
      int count=(int)Math.min(buffer.length,m.fileSize-at);PeerEngine.readTransferBlock(input,buffer,count);
      e.uploadPolicy.write(output,buffer,0,count);at+=count;
    }
  }
  static void download(PeerEngine e,PeerEngine.Message m,String reference)throws IOException {
    String key=m.from+"/"+m.id;if(e.downloads.putIfAbsent(key,true)!=null)return;
    try{
      e.downloadNote(m,"");ResumableTransfer.active(e,m,key);
      DownloadDestination previous=DownloadDestination.get(e,m);
      boolean fresh=!previous.reference.equals(reference);
      boolean cached=e.hasAttachment(m)&&!previous.complete;
      try(DownloadDestination.FileHandle file=e.destinationOpener.open(reference)){
        if(fresh){file.truncate(0);DownloadDestination.save(e,m,new DownloadDestination(reference,false,e.isFastAttachment(m)));}
        if(cached){
          file.truncate(0);file.position(0);
          try(InputStream input=e.openContent(m)){
            byte[] buffer=new byte[BLOCK];long at=0;MessageDigest hash=sha();
            while(at<m.fileSize){ResumableTransfer.active(e,m,key);int count=(int)Math.min(buffer.length,m.fileSize-at);PeerEngine.readTransferBlock(input,buffer,count);file.write(buffer,count);hash.update(buffer,0,count);at+=count;e.reportProgress(m.id,at,m.fileSize);}
            if(!PeerEngine.hex(hash.digest()).equals(m.fileHash))throw new IOException("Stored file is damaged");
          }
          finish(e,m,key,file,reference,e.isFastAttachment(m));return;
        }
        long size=file.size();if(size>m.fileSize)throw new IOException("Destination changed; choose a new file");
        long done=size==m.fileSize?size:size/BLOCK*BLOCK;file.truncate(done);file.position(0);
        MessageDigest hash=sha();byte[] buffer=new byte[BLOCK];long scanned=0;
        while(scanned<done){int count=file.read(buffer,(int)Math.min(buffer.length,done-scanned));if(count<=0)throw new IOException("Cannot read saved download");hash.update(buffer,0,count);scanned+=count;}
        file.position(done);boolean fast=DownloadDestination.get(e,m).fast,request=fresh||done<m.fileSize;
        e.reportProgress(m.id,done,m.fileSize);
        while(request){
          ResumableTransfer.active(e,m,key);boolean oldPeer=false;
          for(PeerEngine.Peer peer:ResumableTransfer.candidates(e,m)){
            try(ResumableTransfer.Session session=new ResumableTransfer.Session(e,peer,m,key)){
              PeerEngine.write(session.socket,"LM4\tFETCHDIRECT\t"+m.from+"\t"+m.id+"\t"+done);
              String response;try{response=PeerEngine.read(session.socket);}catch(EOFException unsupported){oldPeer=true;continue;}
              if(response.equals("LM4\tBUSY")||response.equals("LM4\tUNAVAILABLE"))continue;
              String[] header=response.split("\t",-1);
              if(header.length<6||!header[0].equals("LM4")||!header[1].equals("DIRECT")||!header[2].equals(m.id)||!header[3].equals(""+done)||!header[4].equals(""+(m.fileSize-done)))throw new IOException("Invalid file response");
              fast=header[5].equals("RAW");if(!(fast&&header.length==8||header[5].equals("TLS")&&header.length==6))throw new IOException("Invalid transfer mode");
              try{DownloadDestination.save(e,m,new DownloadDestination(reference,false,fast));}catch(IOException failure){throw new StorageFailure(failure);}
              Socket raw=null;
              try{
                Socket payload=session.socket;
                if(fast){
                  int port;try{port=Integer.parseInt(header[6]);}catch(NumberFormatException invalid){throw new IOException("Invalid data port");}
                  if(port<1||port>65535||!header[7].matches("[0-9a-f]{64}"))throw new IOException("Invalid data authorization");
                  raw=new Socket();e.downloadSockets.put(key,raw);ResumableTransfer.active(e,m,key);
                  raw.bind(new InetSocketAddress(e.bind,0));raw.connect(new InetSocketAddress(peer.host,port),3000);raw.setSoTimeout(PeerEngine.TRANSFER_READ_TIMEOUT_MS);raw.setTcpNoDelay(true);
                  PeerEngine.write(raw,"LM4\tTOKEN\t"+header[7]);if(!PeerEngine.read(raw).equals("LM4\tRAWREADY"))throw new IOException("Data authorization failed");payload=raw;
                }
                e.downloadNote(m,fast?"Fast file · unencrypted":"");
                while(done<m.fileSize){
                  ResumableTransfer.active(e,m,key);if(!e.trusted(peer.id,session.fingerprint))throw new IOException("Device verification changed");
                  int count=(int)Math.min(buffer.length,m.fileSize-done);PeerEngine.readTransferBlock(payload.getInputStream(),buffer,count);ResumableTransfer.active(e,m,key);
                  try{file.write(buffer,count);}catch(IOException failure){try{file.truncate(done);file.position(done);}catch(IOException ignored){}throw new StorageFailure(failure);}
                  hash.update(buffer,0,count);done+=count;e.reportProgress(m.id,done,m.fileSize);
                }
                if(!e.trusted(peer.id,session.fingerprint))throw new IOException("Device verification changed");
                request=false;break;
              }finally{if(raw!=null){e.downloadSockets.remove(key,raw);raw.close();}}
            }catch(StorageFailure failure){throw failure;}
            catch(IOException network){ResumableTransfer.active(e,m,key);if(done==m.fileSize)throw network;e.downloadNote(m,"Connection interrupted; saved progress kept");}
          }
          if(request){if(oldPeer)throw new IOException("Update the sender to download directly to the chosen folder");ResumableTransfer.waitForRetry(e,m,key);}
        }
        if(!PeerEngine.hex(hash.digest()).equals(m.fileHash)){DownloadDestination.forget(e,m);throw new IOException("File changed or is damaged. Choose a new destination and ask the sender to send again.");}
        finish(e,m,key,file,reference,fast);
      }
    }catch(IOException failure){e.downloadNote(m,String.valueOf(failure.getMessage()));throw failure;}
    finally{e.downloads.remove(key);e.downloadSockets.remove(key);e.lastReportedPercent.remove(m.id);e.notifyChanged();}
  }
  static MessageDigest sha()throws IOException {try{return MessageDigest.getInstance("SHA-256");}catch(GeneralSecurityException failure){throw new IOException(failure);}}
  static void finish(PeerEngine e,PeerEngine.Message m,String key,DownloadDestination.FileHandle file,String reference,boolean fast)throws IOException {
    file.sync();synchronized(e){ResumableTransfer.active(e,m,key);DownloadDestination.save(e,m,new DownloadDestination(reference,true,fast));}
    // Keep only the selected external copy. Never delete originals or user-selected files.
    e.attachmentPath(m).delete();new File(e.attachmentPath(m)+".resume").delete();e.deleteParts(m);e.downloadNote(m,"");
  }
}
