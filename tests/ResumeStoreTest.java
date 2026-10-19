package net.lanmsg.chat;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public class ResumeStoreTest {
  public static void main(String[] args)throws Exception {
    File dir=Files.createTempDirectory("lan-resume-store-").toFile();
    TestProtector protector=new TestProtector(dir);
    try(PeerEngine engine=new PeerEngine(dir,"Resume storage",protector)){
      for(int size:new int[]{0,1,15,16,17,ResumeStore.BLOCK-1,ResumeStore.BLOCK,ResumeStore.BLOCK+7,2*ResumeStore.BLOCK+123}){
        byte[] original=new byte[size];new Random(size).nextBytes(original);
        File path=new File(dir,size+".resume");
        try(ResumeStore saved=new ResumeStore(path,size,protector)){
          if(size>ResumeStore.BLOCK)saved.append(original,ResumeStore.BLOCK);
        }
        if(size>ResumeStore.BLOCK)try(FileOutputStream torn=new FileOutputStream(path,true)){torn.write(new byte[5]);}
        try(ResumeStore saved=new ResumeStore(path,size,protector)){
          long wanted=size>ResumeStore.BLOCK?ResumeStore.BLOCK:0;
          if(saved.done!=wanted)throw new AssertionError("Partial block was not discarded");
          MessageDigest digest=saved.digest();
          while(!saved.finished){int count=(int)Math.min(ResumeStore.BLOCK,size-saved.done);byte[] block=Arrays.copyOfRange(original,(int)saved.done,(int)saved.done+count);saved.append(block,count);digest.update(block);}
          if(!Arrays.equals(digest.digest(),MessageDigest.getInstance("SHA-256").digest(original)))throw new AssertionError("Resumed hash differs");
          saved.sync();
        }
        try(InputStream plain=engine.openAttachmentPlaintext(path)){
          if(!Arrays.equals(original,plain.readAllBytes()))throw new AssertionError("Legacy decoder cannot read resumed ciphertext");
        }
        try(ResumeStore saved=new ResumeStore(path,size,protector)){
          if(!saved.finished||saved.done!=size)throw new AssertionError("Completed download not recovered");
          if(!Arrays.equals(saved.digest().digest(),MessageDigest.getInstance("SHA-256").digest(original)))throw new AssertionError("Completed hash differs");
        }
      }
      byte[] legacy=new byte[ResumeStore.BLOCK+19];new Random(21).nextBytes(legacy);
      PeerEngine.Message message=new PeerEngine.Message(UUID.randomUUID().toString(),UUID.randomUUID().toString(),engine.id,"",0,"Received","","legacy.bin",legacy.length,SecureIdentity.hash(legacy));
      engine.messages.add(message);engine.running=true;String key=message.from+"/"+message.id;engine.downloads.put(key,true);
      engine.storeAttachmentStreamAt(message,new ByteArrayInputStream(legacy),legacy.length,null,engine.segmentPath(message,0));
      try(ResumeStore migrated=new ResumeStore(new File(engine.attachmentPath(message)+".resume"),legacy.length,protector)){
        ResumableTransfer.importOldParts(engine,message,key,migrated);
        if(!migrated.finished||!PeerEngine.hex(migrated.digest().digest()).equals(message.fileHash))throw new AssertionError("Old checkpoint import failed");
      }
    }
    System.out.println("PASS: encrypted resume, torn block recovery, padding boundaries, completed recovery and legacy decoder compatibility");
  }
}
