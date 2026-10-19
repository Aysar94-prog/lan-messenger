package net.lanmsg.chat;

import java.io.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Resumable encrypted storage; finalized bytes retain the existing LMATCS1 format. */
final class ResumeStore implements Closeable {
  static final int BLOCK=256*1024;
  static final class StorageFailure extends IOException {
    StorageFailure(String message,Throwable cause){super(message,cause);}
  }
  final File path;final long size;final RandomAccessFile file;
  byte[] key,initialIv;long start,done;boolean finished;
  ResumeStore(File path,long size,SecureIdentity.Protector protector)throws IOException {
    this.path=path;this.size=size;
    File parent=path.getParentFile();if(!parent.exists()&&!parent.mkdirs())throw new IOException("Cannot create download folder");
    file=new RandomAccessFile(path,"rw");
    try{
      if(file.length()==0){
        byte[] raw=new byte[48];new SecureRandom().nextBytes(raw);
        byte[] protectedKey=protector.protect(raw);
        file.write(PeerEngine.ATTACHMENT_MAGIC);file.writeInt(protectedKey.length);file.write(protectedKey);file.getFD().sync();
      }
      file.seek(0);byte[] magic=new byte[PeerEngine.ATTACHMENT_MAGIC.length];file.readFully(magic);
      if(!Arrays.equals(magic,PeerEngine.ATTACHMENT_MAGIC))throw new IOException("Invalid saved download header");
      int length=file.readInt();if(length<1||length>65536)throw new IOException("Invalid saved download key");
      byte[] protectedKey=new byte[length];file.readFully(protectedKey);byte[] raw=protector.unprotect(protectedKey);
      if(raw.length!=48)throw new IOException("Invalid saved download key");
      key=Arrays.copyOfRange(raw,0,32);initialIv=Arrays.copyOfRange(raw,32,48);start=file.getFilePointer();
      long stored=file.length()-start,complete=(size/16+1)*16;
      if(stored>complete)throw new IOException("Saved download is larger than the offered file");
      finished=stored==complete;
      done=finished?size:Math.min(stored/BLOCK*BLOCK,size/BLOCK*BLOCK);
      // A crash may leave a partial block. Retain every complete encrypted block.
      if(!finished)file.setLength(start+done);
    }catch(Exception failure){file.close();throw new StorageFailure("Cannot open saved download: "+failure.getMessage(),failure);}
  }
  MessageDigest digest()throws IOException {
    try{
      MessageDigest digest=MessageDigest.getInstance("SHA-256");
      if(done==0&&!finished)return digest;
      try(FileInputStream input=new FileInputStream(path)){
        input.getChannel().position(start);
        Cipher cipher=Cipher.getInstance(finished?"AES/CBC/PKCS5Padding":"AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(initialIv));
        try(InputStream plain=new CipherInputStream(new BufferedInputStream(input,BLOCK),cipher)){
          byte[] block=new byte[BLOCK];long left=done;
          while(left>0){int count=(int)Math.min(left,block.length);PeerEngine.readTransferBlock(plain,block,count);digest.update(block,0,count);left-=count;}
          if(plain.read()!=-1)throw new IOException("Saved download length does not match");
        }
      }
      return digest;
    }catch(Exception failure){throw new StorageFailure("Cannot read saved download: "+failure.getMessage(),failure);}
  }
  void append(byte[] plain,int count)throws IOException {
    if(finished||count<0||count>BLOCK||done+count>size||count!=Math.min(BLOCK,size-done))throw new IOException("Invalid download block");
    long before=start+done;
    try{
      byte[] iv=initialIv.clone();
      if(done>0){file.seek(before-16);file.readFully(iv);}
      boolean last=done+count==size;
      Cipher cipher=Cipher.getInstance(last?"AES/CBC/PKCS5Padding":"AES/CBC/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));
      byte[] encrypted=cipher.doFinal(plain,0,count);
      file.seek(before);file.write(encrypted);done+=count;finished=last;
    }catch(Exception failure){
      try{file.setLength(before);}catch(IOException ignored){}
      throw new StorageFailure("Cannot save download; check available storage: "+failure.getMessage(),failure);
    }
  }
  void sync()throws IOException {try{file.getFD().sync();}catch(IOException failure){throw new StorageFailure("Cannot finish saving download",failure);}}
  public void close()throws IOException {file.close();}
}
