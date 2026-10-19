package net.lanmsg.chat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class CallCapabilitiesCheck {
  static int pass;
  interface Action {void run()throws Exception;}
  static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);++pass;}
  static void rejects(Action action,String label)throws Exception {try{action.run();throw new AssertionError(label);}catch(IOException expected){++pass;}}
  static String read(byte[] bytes)throws IOException {return CallCapabilities.readReply(new ByteArrayInputStream(bytes),0,()->0,ms->{});}
  public static void main(String[] args)throws Exception {
    check(CallCapabilities.REQUEST.equals("LM4\tCALLCAPS"),"group CAPS token unchanged/separate");
    check(CallCapabilities.response(true,false).equals("LM4\tCALLCAPS\t2\tVP8"),"confirmed bytes");
    check(CallCapabilities.response(false,false)==null,"unfinished production integration not advertised");
    check(CallCapabilities.response(true,true)==null,"simulated legacy suppresses response");
    check(CallCapabilities.supports(read((CallCapabilities.RESPONSE+"\n").getBytes(StandardCharsets.UTF_8)),true,false,1),"bounded exact reply accepted");
    check(!CallCapabilities.supports("LM4\tCAPS\t2",true,false,1),"group capability does not imply video");
    check(!CallCapabilities.supports("LM4\tFILECAPS\tSTREAM1",true,false,1),"attachment capability does not imply video");
    check(!CallCapabilities.supports(CallCapabilities.RESPONSE,false,false,1),"unverified reply rejected");
    check(!CallCapabilities.supports(CallCapabilities.RESPONSE,true,true,1),"local simulated legacy rejects video");
    check(!CallCapabilities.supports(CallCapabilities.RESPONSE,true,false,10000),"exact deadline rejected");
    check(!CallCapabilities.supports(CallCapabilities.RESPONSE,true,false,-1),"negative elapsed rejected");
    check(!CallCapabilities.supports(CallCapabilities.RESPONSE+"\r",true,false,1),"CR/trailing fields rejected");
    rejects(()->read(new byte[]{(byte)0xc3,10}),"malformed UTF8 rejected");
    rejects(()->read(new byte[129]),"oversized unterminated reply bounded");
    rejects(()->read(new byte[0]),"absent response fails voice-only");
    byte[] maximum=new byte[129];java.util.Arrays.fill(maximum,(byte)'x');maximum[128]=10;
    check(read(maximum).length()==128,"128 byte boundary accepted by reader but unsupported");
    final long[] nanos={0};final int[] lastTimeout={0};
    InputStream drip=new ByteArrayInputStream((CallCapabilities.RESPONSE+"\n").getBytes(StandardCharsets.UTF_8)) {
      public synchronized int read(){nanos[0]+=TimeUnit.SECONDS.toNanos(1);return super.read();}
    };
    rejects(()->CallCapabilities.readReply(drip,0,()->nanos[0],ms->lastTimeout[0]=ms),"drip does not reset absolute deadline");
    check(lastTimeout[0]<=1000,"remaining socket timeout shrinks to whole transaction deadline");
    System.out.println("CallCapabilitiesCheck PASS="+pass+" FAIL=0 (A07 capability boundary; no camera)");
  }
}
