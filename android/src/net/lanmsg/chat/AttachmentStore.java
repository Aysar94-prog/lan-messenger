package net.lanmsg.chat;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.BiConsumer;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Encrypted attachment storage: validation, chunked encrypt/decrypt streaming, plaintext read-back. */
final class AttachmentStore {
  private AttachmentStore(){}
  static void validateFile(String name,long size,String hash)throws IOException {if(size<0||size>PeerEngine.MAX_FAST_FILE_SIZE||(name.isEmpty()?(size!=0||!hash.isEmpty()):(!name.equals(PeerEngine.safeFileName(name))||!hash.matches("[0-9a-f]{64}"))))throw new IOException("Invalid attachment metadata");}
  static File attachmentPath(PeerEngine e,PeerEngine.Message m)throws IOException {if(!PeerEngine.uuid(m.from)||!PeerEngine.uuid(m.id))throw new IOException("Invalid attachment ID");return new File(new File(e.file.getParentFile(),"attachments"),m.from+"-"+m.id+".sec");}
  // `totalBytes` is read as an exact count, not "until EOF" — required for a network source, which
  // has no natural end-of-stream mid-connection (more protocol frames follow after it). A
  // ByteArrayInputStream/file source works the same way since its length is already known too.
  // Encrypts a stream of plaintext into the attachment file without ever buffering the whole
  // content in memory — required now that attachments can be up to MAX_FILE_SIZE. A random
  // per-attachment AES-256-CBC key/IV (itself wrapped by the small, one-shot OS storage protector)
  // replaces protecting the whole blob at once. Confidentiality-only at rest (no per-chunk
  // authentication); the existing end-to-end SHA-256 hash still catches corruption/tampering,
  // same as before, and the TLS channel content travels over is itself authenticated.
  static String storeAttachmentStream(PeerEngine e,PeerEngine.Message m,InputStream source,long totalBytes,BiConsumer<Long,Long> onProgress)throws IOException {return storeAttachmentStreamAt(e,m,source,totalBytes,onProgress,attachmentPath(e,m));}
  static String storeAttachmentStreamAt(PeerEngine e,PeerEngine.Message m,InputStream source,long totalBytes,BiConsumer<Long,Long> onProgress,File destination)throws IOException {
    File path=destination;if(!path.getParentFile().exists()&&!path.getParentFile().mkdirs())throw new IOException("Cannot create attachment storage");
    File tmp=new File(path+"."+UUID.randomUUID()+".tmp");
    try{
      byte[] key=new byte[32],iv=new byte[16];SecureRandom random=new SecureRandom();random.nextBytes(key);random.nextBytes(iv);
      byte[] combined=new byte[48];System.arraycopy(key,0,combined,0,32);System.arraycopy(iv,0,combined,32,16);
      byte[] header;try{header=e.protector.protect(combined);}catch(Exception ex){throw new IOException(ex);}
      MessageDigest digest=MessageDigest.getInstance("SHA-256");
      // Cipher driven manually (update()/doFinal()), not via CipherOutputStream: that class's
      // close() cascades into closing the underlying FileOutputStream, which would leave nothing
      // open for the fsync below to act on (surfaces as a SyncFailedException on a closed fd).
      try(FileOutputStream fileOut=new FileOutputStream(tmp)){
        fileOut.write(PeerEngine.ATTACHMENT_MAGIC);fileOut.write(ByteBuffer.allocate(4).putInt(header.length).array());fileOut.write(header);
        Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));
        byte[] buffer=new byte[PeerEngine.CHUNK_SIZE];long total=0;
        while(total<totalBytes){
          int want=(int)Math.min(PeerEngine.CHUNK_SIZE,totalBytes-total);
          int n=source.read(buffer,0,want);
          if(n<=0)throw new EOFException("Attachment transfer ended early");
          digest.update(buffer,0,n);
          byte[] encrypted=cipher.update(buffer,0,n);
          if(encrypted!=null&&encrypted.length>0)fileOut.write(encrypted);
          total+=n;if(onProgress!=null)onProgress.accept(total,totalBytes);
        }
        byte[] finalBlock=cipher.doFinal();
        if(finalBlock!=null&&finalBlock.length>0)fileOut.write(finalBlock);
        fileOut.getFD().sync();
      }
      PeerEngine.atomicReplace(tmp,path);
      StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
      return hex.toString();
    }catch(GeneralSecurityException e2){tmp.delete();throw new IOException(e2);}
    catch(IOException e2){tmp.delete();throw e2;}
  }
  static void storeAttachment(PeerEngine e,PeerEngine.Message m,byte[] data)throws IOException {storeAttachmentStream(e,m,new ByteArrayInputStream(data),data.length,null);}
  // Opens the attachment's decrypted plaintext as a stream, transparently handling both the
  // current streaming format and attachments stored by versions before it (a single whole-file
  // Unprotect — the only way to read those, since they predate the per-attachment key/IV header).
  static InputStream openAttachmentPlaintext(PeerEngine e,File path)throws IOException {return openAttachmentPlaintext(e,path,0);}
  // CBC can begin at a 16-byte boundary using the preceding ciphertext block as its IV.
  // The protocol's 100 MiB offsets are aligned, so later segments need no prefix decrypt.
  static InputStream openAttachmentPlaintext(PeerEngine e,File path,long offset)throws IOException {
    if(offset<0||offset%16!=0)throw new IOException("Invalid attachment offset");
    if(path.length()>=PeerEngine.ATTACHMENT_MAGIC.length){
      FileInputStream probe=new FileInputStream(path);
      try{
        byte[] head=new byte[PeerEngine.ATTACHMENT_MAGIC.length];readFully(probe,head);
        if(Arrays.equals(head,PeerEngine.ATTACHMENT_MAGIC)){
          byte[] lenBuf=new byte[4];readFully(probe,lenBuf);int headerLen=ByteBuffer.wrap(lenBuf).getInt();
          if(headerLen<1||headerLen>65536)throw new IOException("Invalid attachment header");byte[] header=new byte[headerLen];readFully(probe,header);
          byte[] raw;try{raw=e.protector.unprotect(header);}catch(Exception ex){throw new IOException(ex);}
          if(raw.length!=48)throw new IOException("Invalid attachment key");
          byte[] key=Arrays.copyOfRange(raw,0,32),iv=Arrays.copyOfRange(raw,32,48);
          long encryptedAt=probe.getChannel().position();
          if(offset>0){
            if(offset>path.length()-encryptedAt-16)throw new EOFException();
            probe.getChannel().position(encryptedAt+offset-16);readFully(probe,iv);
            probe.getChannel().position(encryptedAt+offset);
          }
          Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));
          return new CipherInputStream(new BufferedInputStream(probe,256*1024),cipher);
        }
      }catch(Exception ex){probe.close();if(ex instanceof IOException)throw (IOException)ex;throw new IOException(ex);}
      probe.close();
    }
    try{ByteArrayInputStream plain=new ByteArrayInputStream(e.protector.unprotect(SecureIdentity.readFile(path)));if(plain.skip(offset)!=offset)throw new EOFException();return plain;}catch(IOException ex){throw ex;}catch(Exception ex){throw new IOException(ex);}
  }
  static void readFully(InputStream in,byte[] buffer)throws IOException{int at=0;while(at<buffer.length){int n=in.read(buffer,at,buffer.length-at);if(n<0)throw new EOFException();at+=n;}}
  // Small attachments and callers that need a byte[] (thumbnails, exports below a threshold,
  // tests). Not used for the actual network send/receive path — that streams, see below.
  static byte[] readAttachment(PeerEngine e,PeerEngine.Message m)throws IOException {
    try(InputStream plain=TransferManager.openContent(e,m);ByteArrayOutputStream buffer=new ByteArrayOutputStream()){
      byte[] chunk=new byte[PeerEngine.CHUNK_SIZE];int n;while((n=plain.read(chunk))!=-1)buffer.write(chunk,0,n);
      byte[] data=buffer.toByteArray();
      if(data.length!=m.fileSize||!SecureIdentity.hash(data).equals(m.fileHash))throw new IOException("Attachment integrity check failed");
      return data;
    }catch(Exception ex){if(ex instanceof IOException)throw (IOException)ex;throw new IOException(ex);}
  }
  // Decrypts straight to `destination` (e.g. a SAF export OutputStream, or the network) without
  // ever buffering the whole attachment in memory. Verifies size+hash only once fully streamed,
  // matching readAttachment's guarantee. Caller owns/closes `destination`.
  static void readAttachmentStream(PeerEngine e,PeerEngine.Message m,OutputStream destination,BiConsumer<Long,Long> onProgress)throws IOException {
    MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(Exception ex){throw new IOException(ex);}
    long total=0;
    try(InputStream plain=TransferManager.openContent(e,m)){
      byte[] buffer=new byte[PeerEngine.CHUNK_SIZE];int n;
      while((n=plain.read(buffer))!=-1){
        digest.update(buffer,0,n);destination.write(buffer,0,n);
        total+=n;if(onProgress!=null)onProgress.accept(total,(long)m.fileSize);
      }
    }
    StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
    if(total!=m.fileSize||!hex.toString().equals(m.fileHash))throw new IOException("Attachment integrity check failed");
  }
  static byte[] readBytes(Socket s,int size)throws IOException {byte[] data=new byte[size];InputStream in=s.getInputStream();int at=0;long end=System.nanoTime()+PeerEngine.transferTimeoutNanos(size);while(at<size){if(System.nanoTime()>end)throw new IOException("Attachment timeout");int n=in.read(data,at,Math.min(65536,size-at));if(n<0)throw new EOFException();at+=n;}return data;}
  // Streams exactly `size` bytes from the socket into the attachment store, reporting progress —
  // the network-receive counterpart to storeAttachmentStream's file/byte[] sources. The socket's
  // own SO_TIMEOUT (set at connect time) already bounds each individual read, so a stalled
  // connection is caught without needing a separate per-chunk timeout mechanism here.
  static String storeAttachmentFromSocket(PeerEngine e,PeerEngine.Message m,Socket s,long size,String messageId)throws IOException {
    return storeAttachmentStream(e,m,s.getInputStream(),size,(done,total)->e.reportProgress(messageId,done,total));
  }
  static void sendAttachmentToSocket(PeerEngine e,PeerEngine.Message m,Socket s,String messageId)throws IOException {
    OutputStream guarded=new PeerEngine.NetworkTimeoutOutputStream(s);
    readAttachmentStream(e,m,guarded,(done,total)->e.reportProgress(messageId,done,total));
    guarded.flush();
  }
}
