package net.lanmsg.chat;
import java.io.*;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
public class DailyUploadPolicyTest {
  static class Clock implements DailyUploadPolicy.Time {
    LocalDate day=LocalDate.of(2026,9,23);long time=1;
    public LocalDate day(){return day;} public long nanos(){return time;}
    public void sleep(long nanos){time+=nanos;}
  }
  static final OutputStream SINK=new OutputStream(){public void write(int b){} public void write(byte[] b,int o,int n){}};
  static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
  static void seed(File dir,TestProtector protector,LocalDate day,long bytes)throws Exception{
    try(FileOutputStream out=new FileOutputStream(new File(dir,"daily-upload.sec"))){out.write(protector.protect(("LMUPLOAD1\t"+day+"\t"+bytes).getBytes(StandardCharsets.UTF_8)));}
  }
  public static void main(String[] args)throws Exception{
    File dir=new File(args[0]);dir.mkdirs();TestProtector protector=new TestProtector(dir);Clock clock=new Clock();
    long g=DailyUploadPolicy.GIB,m=DailyUploadPolicy.MIB;
    check(DailyUploadPolicy.rateFor(2*g-1)==0,"first 2 GiB unlimited");
    check(DailyUploadPolicy.rateFor(2*g)==30*m,"2 GiB threshold");
    check(DailyUploadPolicy.rateFor(5*g)==20*m,"5 GiB threshold");
    check(DailyUploadPolicy.rateFor(10*g)==10*m,"10 GiB threshold");
    for(long boundary:new long[]{2*g,5*g,10*g}){
      seed(dir,protector,clock.day,boundary-3);DailyUploadPolicy policy=new DailyUploadPolicy(dir,protector,clock);
      long start=clock.time;policy.write(SINK,new byte[6],0,6);policy.flush();
      check(policy.sentToday()==boundary+3,"split writes count exactly");
      check(clock.time>start,"crossing threshold is paced");
      check(new DailyUploadPolicy(dir,protector,clock).sentToday()==boundary+3,"counter survives restart");
    }
    seed(dir,protector,clock.day,10*g);DailyUploadPolicy policy=new DailyUploadPolicy(dir,protector,clock);
    byte[] chunk=new byte[256*1024];long start=clock.time;
    java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
    Runnable upload=()->{try{for(int i=0;i<40;i++)policy.write(SINK,chunk,0,chunk.length);}catch(Throwable e){failure.set(e);}};
    Thread a=new Thread(upload),b=new Thread(upload);a.start();b.start();a.join();b.join();
    if(failure.get()!=null)throw new AssertionError(failure.get());
    check(policy.sentToday()==10*g+20*m,"two streams share one byte counter");
    check(clock.time-start>=2_000_000_000L,"two streams share 10 MiB/s, not 10 each");
    long before=policy.sentToday();
    try{policy.write(new OutputStream(){public void write(int value)throws IOException{throw new IOException("failed");}},new byte[1],0,1);throw new AssertionError("write should fail");}catch(IOException expected){}
    check(policy.sentToday()==before,"failed write not credited as completed");
    policy.flush();clock.day=clock.day.minusDays(1);check(policy.sentToday()==before,"clock rollback does not reset");
    clock.day=clock.day.plusDays(2);check(policy.sentToday()==0&&policy.limitBytesPerSecond()==0,"midnight resets counter and tier");
    policy.write(SINK,new byte[7],0,7);policy.flush();
    check(new DailyUploadPolicy(dir,protector,clock).sentToday()==7,"new day persists");
    try(FileOutputStream out=new FileOutputStream(new File(dir,"daily-upload.sec"))){out.write(new byte[]{1,2,3});}
    try{new DailyUploadPolicy(dir,protector,clock);throw new AssertionError("corrupt counter accepted");}catch(IOException expected){}
    System.out.println("PASS: daily tiers, exact boundaries, shared concurrent cap, restart, midnight, rollback, failed write and corrupt storage");
  }
}

