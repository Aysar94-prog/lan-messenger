package net.lanmsg.chat;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/** Stores only a protected destination reference; file bytes live at the user's destination. */
final class DownloadDestination {
  final String reference;final boolean complete,fast;
  DownloadDestination(String reference,boolean complete,boolean fast){this.reference=reference;this.complete=complete;this.fast=fast;}
  static final DownloadDestination NONE=new DownloadDestination("",false,false);
  static File path(PeerEngine e,PeerEngine.Message m)throws IOException{return new File(e.attachmentPath(m)+".destination");}
  static DownloadDestination get(PeerEngine e,PeerEngine.Message m){
    String key=m.from+"/"+m.id;DownloadDestination cached=e.destinations.get(key);if(cached!=null)return cached;
    try{
      File file=path(e,m);DownloadDestination value=NONE;
      if(file.exists()){
        String[] fields=new String(e.protector.unprotect(SecureIdentity.readFile(file)),StandardCharsets.UTF_8).split("\t",-1);
        if(fields.length!=4||!fields[0].equals("LMDST1"))throw new IOException("Invalid destination record");
        value=new DownloadDestination(PeerEngine.dec(fields[1]),fields[2].equals("1"),fields[3].equals("1"));
      }
      e.destinations.put(key,value);return value;
    }catch(Exception failure){return NONE;}
  }
  static void save(PeerEngine e,PeerEngine.Message m,DownloadDestination value)throws IOException {
    try{
      File file=path(e,m);file.getParentFile().mkdirs();File temp=new File(file+".tmp");
      byte[] bytes=e.protector.protect(("LMDST1\t"+PeerEngine.enc(value.reference)+"\t"+(value.complete?"1":"0")+"\t"+(value.fast?"1":"0")).getBytes(StandardCharsets.UTF_8));
      try(FileOutputStream output=new FileOutputStream(temp)){output.write(bytes);output.getFD().sync();}
      PeerEngine.atomicReplace(temp,file);e.destinations.put(m.from+"/"+m.id,value);
    }catch(Exception failure){throw new IOException("Cannot save download location",failure);}
  }
  static void forget(PeerEngine e,PeerEngine.Message m)throws IOException{path(e,m).delete();e.destinations.remove(m.from+"/"+m.id);}
  interface FileHandle extends Closeable {
    long size()throws IOException;void position(long offset)throws IOException;void truncate(long length)throws IOException;
    int read(byte[] buffer,int count)throws IOException;void write(byte[] buffer,int count)throws IOException;void sync()throws IOException;
  }
  static FileHandle local(String path)throws IOException {
    final RandomAccessFile file=new RandomAccessFile(path,"rw");
    return new FileHandle(){
      public long size()throws IOException{return file.length();}
      public void position(long at)throws IOException{file.seek(at);}
      public void truncate(long size)throws IOException{file.setLength(size);}
      public int read(byte[] bytes,int count)throws IOException{return file.read(bytes,0,count);}
      public void write(byte[] bytes,int count)throws IOException{file.write(bytes,0,count);}
      public void sync()throws IOException{file.getFD().sync();}
      public void close()throws IOException{file.close();}
    };
  }
}
