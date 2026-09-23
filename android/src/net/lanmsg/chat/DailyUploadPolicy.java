package net.lanmsg.chat;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/** One device-wide payload budget shared by ordinary, fast and relayed uploads. */
public final class DailyUploadPolicy {
  static final long MIB=1024L*1024, GIB=1024*MIB;
  interface Time { LocalDate day(); long nanos(); void sleep(long nanos)throws InterruptedException; }
  static final class SystemTime implements Time {
    public LocalDate day(){return LocalDate.now();}
    public long nanos(){return System.nanoTime();}
    public void sleep(long nanos)throws InterruptedException{java.util.concurrent.TimeUnit.NANOSECONDS.sleep(nanos);}
  }
  final File file; final SecureIdentity.Protector protector; final Time clock;
  LocalDate day; long bytes,checkpointBytes,lastCheckpoint,nextWrite; boolean dirty;
  public DailyUploadPolicy(File directory,SecureIdentity.Protector protector)throws IOException{this(directory,protector,new SystemTime());}
  DailyUploadPolicy(File directory,SecureIdentity.Protector protector,Time clock)throws IOException{
    this.file=new File(directory,"daily-upload.sec");this.protector=protector;this.clock=clock;day=clock.day();
    if(file.exists())try{
      String[] fields=new String(protector.unprotect(SecureIdentity.readFile(file)),StandardCharsets.UTF_8).split("\t",-1);
      if(fields.length!=3||!fields[0].equals("LMUPLOAD1"))throw new IOException("Invalid upload usage");
      day=LocalDate.parse(fields[1]);bytes=Long.parseLong(fields[2]);if(bytes<0)throw new IOException("Invalid upload usage");
    }catch(Exception e){throw new IOException("Daily upload usage could not be read; keep app data for recovery.",e);}
    checkpointBytes=bytes;lastCheckpoint=clock.nanos();rollDay();
  }
  static long rateFor(long bytes){return bytes<2*GIB?0:bytes<5*GIB?30*MIB:bytes<10*GIB?20*MIB:10*MIB;}
  void rollDay(){
    LocalDate today=clock.day();
    // Moving the clock backwards must not grant another unlimited allowance.
    if(today.isAfter(day)){day=today;bytes=checkpointBytes=0;nextWrite=0;dirty=true;}
  }
  public synchronized long sentToday(){rollDay();return bytes;}
  public synchronized long limitBytesPerSecond(){rollDay();return rateFor(bytes);}
  public synchronized String summary(){
    rollDay();long rate=rateFor(bytes);
    return String.format(java.util.Locale.ROOT,"Files uploaded today: %.2f GiB\nUpload limit: %s\nResets at local midnight. Shared across all file uploads.",bytes/(double)GIB,rate==0?"Unlimited":(rate/MIB)+" MiB/s");
  }
  public synchronized void write(OutputStream out,byte[] buffer,int offset,int count)throws IOException {
    while(count>0){
      rollDay();
      long boundary=bytes<2*GIB?2*GIB:bytes<5*GIB?5*GIB:bytes<10*GIB?10*GIB:Long.MAX_VALUE;
      int n=(int)Math.min(count,Math.min(256*1024L,boundary-bytes));
      if(n<=0)throw new IOException("Upload accounting overflow");
      long rate=rateFor(bytes);
      if(rate>0){
        long now=clock.nanos(),cost=(n*1_000_000_000L+rate-1)/rate;
        long target=(nextWrite==0?now:Math.max(now,nextWrite))+cost;
        try{long remaining;while((remaining=target-clock.nanos())>0)clock.sleep(remaining);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Upload interrupted",e);}
        nextWrite=target;
      }else nextWrite=0;
      out.write(buffer,offset,n);
      bytes+=n;dirty=true;offset+=n;count-=n;
      if(bytes-checkpointBytes>=4*MIB||clock.nanos()-lastCheckpoint>=1_000_000_000L)flush();
    }
  }
  public synchronized void flush()throws IOException {
    rollDay();if(!dirty)return;
    File temp=new File(file+".tmp");
    try{
      byte[] plain=("LMUPLOAD1\t"+day+"\t"+bytes).getBytes(StandardCharsets.UTF_8);
      byte[] protectedBytes=protector.protect(plain);
      try(FileOutputStream out=new FileOutputStream(temp)){out.write(protectedBytes);out.getFD().sync();}
      PeerEngine.atomicReplace(temp,file);dirty=false;checkpointBytes=bytes;lastCheckpoint=clock.nanos();
    }catch(Exception e){temp.delete();throw new IOException("Could not save daily upload usage",e);}
  }
}
