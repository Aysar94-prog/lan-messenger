package net.lanmsg.chat;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class AttachmentRangeTest {
  public static void main(String[] args)throws Exception {
    File dir=Files.createTempDirectory("lan-range-").toFile();
    try(PeerEngine engine=new PeerEngine(dir,"Range Test",new TestProtector(dir))){
      byte[] data=new byte[2*1024*1024+7];new Random(42).nextBytes(data);
      PeerEngine.Message message=new PeerEngine.Message(UUID.randomUUID().toString(),engine.id,UUID.randomUUID().toString(),"",System.currentTimeMillis(),"Queued");
      File attachment=new File(dir,"range.sec");
      engine.storeAttachmentStreamAt(message,new ByteArrayInputStream(data),data.length,null,attachment);
      for(int offset:new int[]{0,16,1024*1024,2*1024*1024}){
        try(InputStream stream=engine.openAttachmentPlaintext(attachment,offset)){
          if(!Arrays.equals(stream.readAllBytes(),Arrays.copyOfRange(data,offset,data.length)))throw new AssertionError("Range differs at "+offset);
        }
      }
      System.out.println("PASS: encrypted attachment ranges match the original bytes");
    }
  }
}
