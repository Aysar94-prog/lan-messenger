package net.lanmsg.chat;
public final class CallVideoDiagnosticsCheck {
  static int pass;
  static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);pass++;}
  public static void main(String[] args){
    CallVideoDiagnostics.Sampler sampler=new CallVideoDiagnostics.Sampler();
    CallVideoDiagnostics.Counters c=new CallVideoDiagnostics.Counters();c.codec="VP8";c.timestampUs=1000000;
    c.sentFrames=20L;c.receivedFrames=18L;c.sentBytes=1000L;c.receivedBytes=900L;c.receivedPackets=100L;c.lostPackets=1L;
    check(sampler.sample(c,100).sentFps==null,"first sample has no invented rates");
    c.timestampUs=2000000;c.sentFrames=40L;c.receivedFrames=36L;c.sentBytes=2000L;c.receivedBytes=1800L;c.receivedPackets=199L;c.lostPackets=2L;
    CallVideoDiagnostics.Snapshot s=sampler.sample(c,200);
    check(s.sentFps==20&&s.receivedFps==18,"frame deltas normalized per second");
    check(s.sentKbps==8&&s.receivedKbps==7.2,"byte deltas normalized to kbps");
    check(s.lossPercent==1,"recent loss delta not cumulative percentage");
    check(s.fresh(3_000_000_201L).codec==null,"stale snapshot unavailable");
    c.timestampUs=3000000;c.sentFrames=1L;c.lostPackets=0L;
    s=sampler.sample(c,300);check(s.sentFps==null&&s.lossPercent==null,"counter reset unavailable");
    c.timestampUs=2000000;check(sampler.sample(c,400).receivedFps==null,"clock reversal unavailable");
    c.timestampUs=9000000;check(sampler.sample(c,500).receivedFps==null,"long sampling gap unavailable");
    s=new CallVideoDiagnostics.Snapshot("192.168.1.1 SDP secret",Double.NaN,Double.POSITIVE_INFINITY,-1.0,1e20,101.0,600);
    String report=CallVideoDiagnostics.report(s,"secret peer 10.0.0.1");
    check(!report.contains("secret")&&!report.contains("192.168")&&!report.contains("10.0.0.1"),"allowlist drops arbitrary codec/build strings");
    check(s.sentFps==null&&s.receivedFps==null&&s.sentKbps==null&&s.receivedKbps==null&&s.lossPercent==null,"invalid metrics unavailable");
    check(CallVideoDiagnostics.report(null,"2.2.43").startsWith("LAN Messenger Android 2.2.43"),"safe build version retained");
    check(CallVideoDiagnostics.display(null).contains("Unavailable"),"missing native metrics honest");
    sampler.reset();check(sampler.sample(c,700).sentFps==null,"per-call reset clears history");
    System.out.println("CallVideoDiagnosticsCheck PASS="+pass+" FAIL=0");
  }
}
