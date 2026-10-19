package net.lanmsg.chat;

import java.util.Locale;

/** Allowlisted video measurements. No signaling/peer/frame/log field exists. */
public final class CallVideoDiagnostics {
  private CallVideoDiagnostics(){}
  public static final class Snapshot {
    public final String codec;
    public final Double sentFps,receivedFps,sentKbps,receivedKbps,lossPercent;
    public final long measuredAtNanos;
    public Snapshot(String codec,Double sentFps,Double receivedFps,Double sentKbps,
        Double receivedKbps,Double lossPercent,long measuredAtNanos){
      this.codec="VP8".equals(codec)?codec:null;
      this.sentFps=metric(sentFps,1000);this.receivedFps=metric(receivedFps,1000);
      this.sentKbps=metric(sentKbps,1_000_000);this.receivedKbps=metric(receivedKbps,1_000_000);
      this.lossPercent=metric(lossPercent,100);this.measuredAtNanos=measuredAtNanos;
    }
    private static Double metric(Double value,double maximum){
      return value!=null&&Double.isFinite(value)&&value>=0&&value<=maximum?value:null;
    }
    public static Snapshot unavailable(){return new Snapshot(null,null,null,null,null,null,0);}
    public Snapshot fresh(long nowNanos){
      return measuredAtNanos!=0&&nowNanos>=measuredAtNanos&&nowNanos-measuredAtNanos<=3_000_000_000L?this:unavailable();
    }
  }
  /** Adapter normalizes just one inbound/outbound video RTP stream here. */
  public static final class Counters {
    public long timestampUs;
    public String codec;
    public Long sentFrames,receivedFrames,sentBytes,receivedBytes,receivedPackets,lostPackets;
  }
  public static final class Sampler {
    private Counters previous;
    private static Double rate(Long now,Long old,double seconds,double scale){
      return now!=null&&old!=null&&now>=0&&old>=0&&now>=old?(now-old)/seconds*scale:null;
    }
    public synchronized Snapshot sample(Counters value,long nowNanos){
      if(value==null){previous=null;return Snapshot.unavailable();}
      Counters old=previous;previous=copy(value);
      double seconds=old==null?0:(value.timestampUs-old.timestampUs)/1_000_000.0;
      if(seconds<0.25||seconds>5)return new Snapshot(value.codec,null,null,null,null,null,nowNanos);
      Double loss=null;
      if(value.receivedPackets!=null&&old.receivedPackets!=null&&value.lostPackets!=null&&old.lostPackets!=null
          &&old.receivedPackets>=0&&old.lostPackets>=0&&value.receivedPackets>=old.receivedPackets&&value.lostPackets>=old.lostPackets){
        double received=value.receivedPackets-old.receivedPackets,lost=value.lostPackets-old.lostPackets;
        if(received+lost>0)loss=100*lost/(received+lost);
      }
      return new Snapshot(value.codec,rate(value.sentFrames,old.sentFrames,seconds,1),
        rate(value.receivedFrames,old.receivedFrames,seconds,1),rate(value.sentBytes,old.sentBytes,seconds,0.008),
        rate(value.receivedBytes,old.receivedBytes,seconds,0.008),loss,nowNanos);
    }
    private static Counters copy(Counters input){
      Counters c=new Counters();c.timestampUs=input.timestampUs;c.codec=input.codec;c.sentFrames=input.sentFrames;
      c.receivedFrames=input.receivedFrames;c.sentBytes=input.sentBytes;c.receivedBytes=input.receivedBytes;
      c.receivedPackets=input.receivedPackets;c.lostPackets=input.lostPackets;return c;
    }
    public synchronized void reset(){previous=null;}
  }
  private static String metric(Double value,String unit){return value==null?"Unavailable":String.format(Locale.ROOT,"%.1f %s",value,unit);}
  public static String display(Snapshot s){
    if(s==null)s=Snapshot.unavailable();
    return "Codec: "+(s.codec==null?"Unavailable":s.codec)+"\nSend: "+metric(s.sentFps,"fps")+" / "+metric(s.sentKbps,"kbps")+
      "\nReceive: "+metric(s.receivedFps,"fps")+" / "+metric(s.receivedKbps,"kbps")+"\nRecent receive loss: "+metric(s.lossPercent,"%");
  }
  /** Version is constrained, not arbitrary build text that might contain secrets. */
  public static String report(Snapshot s,String version){
    String safe=version!=null&&version.matches("[0-9]{1,4}(?:\\.[0-9]{1,4}){1,3}")?version:"Unavailable";
    return "LAN Messenger Android "+safe+"\n"+display(s)+"\nClipboard text remains until replaced or cleared.";
  }
}
