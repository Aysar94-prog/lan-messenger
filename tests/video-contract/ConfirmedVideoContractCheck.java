package net.lanmsg.chat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Post-A02b AT01: actual production v2 validator and original v1 wire. */
public final class ConfirmedVideoContractCheck {
  static int pass;
  static final String CALL="00000000-0000-0000-0000-000000000001";
  static final String PEER="00000000-0000-0000-0000-000000000002";
  static final String REQUEST="00000000-0000-0000-0000-000000000003";
  static final String FINGERPRINT=String.join(":",Collections.nCopies(32,"00"));
  static final String VIDEO="v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=fingerprint:sha-256 "+FINGERPRINT+"\r\na=rtpmap:96 VP8/90000\r\n";
  static final String AUDIO=VIDEO.replace("m=video","m=audio").replace("VP8/90000","G722/8000");
  static void check(boolean ok,String what) { if(!ok)throw new AssertionError(what); ++pass; }
  static CallProtocol.Frame frame(String type,long gen,Object... fields) {
    CallProtocol.Frame f=new CallProtocol.Frame(type,CALL,1,gen); f.protocolVersion=2;
    for(int i=0;i<fields.length;i+=2)f.body.put((String)fields[i],fields[i+1]); return f;
  }
  static byte[] wire(String json) {
    byte[] bytes=json.getBytes(StandardCharsets.UTF_8);
    return ByteBuffer.allocate(bytes.length+4).putInt(bytes.length).put(bytes).array();
  }
  static String envelope(String version,String seq,String gen,String extra) {
    return "{\"v\":"+version+",\"t\":\"PING\",\"cid\":\""+CALL+"\",\"seq\":"+seq+",\"gen\":"+gen+extra+"}";
  }
  public static void main(String[] args) {
    String caps=CallVideoProtocol.capabilityResponse(false);
    check(caps.equals("LM4\tCALLCAPS\t2\tVP8"),"selected codec only");
    check(CallVideoProtocol.capable(caps,true,false,9999),"fresh verified capability");
    check(!CallVideoProtocol.capable(caps,true,false,10000),"capability deadline");
    check(!CallVideoProtocol.capable(caps,false,false,0),"unauthenticated capability");
    check(!CallVideoProtocol.capable(caps,true,true,0),"simulated legacy capability");
    check(!CallVideoProtocol.capable(caps,true,false,-1),"negative capability time");
    check(CallVideoProtocol.capabilityResponse(true)==null,"legacy token suppressed");
    for(String bad:Arrays.asList("", "LM4\tCAPS\t2", "LM4\tFILECAPS\tSTREAM1",
      "LM4\tCALLCAPS\t2\tVP9",caps+",H264",caps+"\textra",caps+"\n"))
      check(!CallVideoProtocol.capable(bad,true,false,0),"capability fallback");
    for(String type:Arrays.asList("OFFER","ANSWER")) {
      CallProtocol.Frame f=frame(type,2,"media","video","request",REQUEST,"sdp",VIDEO);
      check(CallVideoProtocol.valid(f),"video SDP syntax");
      check(CallVideoProtocol.valid(CallSignaling.parse(CallSignaling.serialize(f))),"v2 wire roundtrip");
      check(f.copy().protocolVersion==2,"copy preserves version");
      f.body.put("peerSecret","not allowed"); check(!CallVideoProtocol.valid(f),"unknown SDP key");
      f.body.remove("peerSecret"); f.negotiationGeneration=1;
      check(!CallVideoProtocol.valid(f),"video cannot use audio generation");
      check(CallVideoProtocol.valid(frame(type,1,"media","audio","sdp",AUDIO)),"audio-only v2 SDP");
      check(!CallVideoProtocol.valid(frame(type,1,"media","audio","request",REQUEST,"sdp",AUDIO)),"audio no video request discriminator");
    }
    for(String bad:Arrays.asList(VIDEO+"m=audio 9 UDP/TLS/RTP/SAVPF 9\r\n",VIDEO+"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n",
      VIDEO.replace("VP8/90000","H264/90000"),VIDEO.replace("VP8/90000","VP9/90000"),
      VIDEO.replace("rtpmap:96","rtpmap:97"),VIDEO.replace("sha-256","sha-1"),
      VIDEO.replace(FINGERPRINT,"bad"),VIDEO.replace("UDP/TLS/RTP/SAVPF","RTP/AVP"),
      VIDEO.replace("m=video 9","m=video 0"),VIDEO.replace("m=video 9","m=video 99999"),
      VIDEO.replace("\r\n","\n"),VIDEO+"\0",VIDEO+String.join("",Collections.nCopies(49152,"x")),
      VIDEO+String.join("",Collections.nCopies(24576,"é"))))
      check(!CallVideoProtocol.validSdp(bad,true),"invalid video media boundary");
    check(!CallVideoProtocol.validSdp(AUDIO,true),"wrong media connection");
    check(!CallVideoProtocol.validSdp(VIDEO,false),"video forbidden on audio PC");
    CallProtocol.Frame ice=frame("ICE",2,"media","video","request",REQUEST,
      "candidate","candidate:test","sdpMid","0","sdpMLineIndex",0);
    check(CallVideoProtocol.valid(ice),"video ICE syntax");
    check(CallVideoProtocol.valid(CallSignaling.parse(CallSignaling.serialize(ice))),"integral ICE wire index");
    for(Object bad:Arrays.asList(0.0,-1,1,"0",Long.MAX_VALUE)) {
      ice.body.put("sdpMLineIndex",bad); check(!CallVideoProtocol.valid(ice),"bad ICE index");
    }
    ice.body.put("sdpMLineIndex",0);
    for(String bad:Arrays.asList("", "not-a-candidate", "candidate:test\r\nsecret", "candidate:\0",
      "candidate:"+String.join("",Collections.nCopies(4096,"x")))) {
      ice.body.put("candidate",bad); check(!CallVideoProtocol.valid(ice),"candidate byte/control limit");
    }
    for(String type:Arrays.asList("VIDEO_REQUEST","VIDEO_ACCEPT","VIDEO_DECLINE")) {
      check(CallVideoProtocol.valid(frame(type,0,"request",REQUEST)),"request shape");
      check(!CallVideoProtocol.valid(frame(type,2,"request",REQUEST)),"request has no generation");
      check(!CallVideoProtocol.valid(frame(type,0,"request","foreign")),"request UUID");
    }
    check(CallVideoProtocol.valid(frame("INVITE",0,"caller",CALL,"callee",PEER,"media","video")),"video invitation");
    check(CallVideoProtocol.valid(frame("ACCEPT",0,"media","audio")),"answer with voice");
    check(!CallVideoProtocol.valid(frame("ACCEPT",0,"media","receive-only")),"receive-only not selected");
    check(CallVideoProtocol.valid(frame("VIDEO_STATE",2,"request",REQUEST,"camera",true,"revision",1L)),"camera state shape");
    check(!CallVideoProtocol.valid(frame("VIDEO_STATE",2,"request",REQUEST,"camera",true,"revision",1.0)),"fractional revision");
    check(CallVideoProtocol.valid(frame("ERROR",2,"media","video","request",REQUEST,"code","failed")),"recoverable video error");
    check(CallVideoProtocol.valid(frame("MEDIA_READY",1,"media","audio")),"audio readiness routed separately");
    for(String type:Arrays.asList("RINGING","DECLINE","BUSY","CANCEL","HANGUP","PING","PONG")) {
      check(CallVideoProtocol.valid(frame(type,0)),"unchanged control shape");
      check(!CallVideoProtocol.valid(frame(type,0,"secret","x")),"control body allowlist");
    }
    for(String bad:Arrays.asList(envelope("2.0","1","0",""),envelope("2.1","1","0",""),
      envelope("2","1.0","0",""),envelope("2","1","0.0",""),envelope("2","9223372036854775808","0",""),
      envelope("2","1","0",",\"extra\":1"),envelope("2","1","0",",\"seq\":2"),
      envelope("2","01","0",""),envelope("2","1","0","")+"junk"))
      check(CallSignaling.parse(wire(bad))==null,"strict wire counters/envelope");
    CallProtocol.Frame large=CallSignaling.parse(wire(envelope("2","9007199254740993","0","")));
    check(large!=null&&large.senderSequence==9007199254740993L,"counter precision beyond double range");
    byte[] invalid=wire(envelope("2","1","0","")); invalid[10]=(byte)0xff;
    check(CallSignaling.parse(invalid)==null,"malformed UTF-8 rejected");
    String deep=envelope("2","1","0",",\"b\":{\"nested\":"+String.join("",Collections.nCopies(20,"["))+"0"+String.join("",Collections.nCopies(20,"]"))+"}");
    check(CallSignaling.parse(wire(deep))==null,"bounded JSON depth");
    CallProtocol.Frame v1=CallSignaling.invite(CALL,1,CALL,PEER);
    String expected="{\"v\":1,\"t\":\"INVITE\",\"cid\":\""+CALL+"\",\"seq\":1,\"gen\":0,\"b\":{\"caller\":\""+CALL+"\",\"callee\":\""+PEER+"\"}}";
    check(Arrays.equals(CallSignaling.serialize(v1),wire(expected)),"v1 invitation bytes retained");
    check(CallSignaling.parse(wire(expected)).protocolVersion==1,"v1 parse retained");
    System.out.println("ConfirmedVideoContractCheck PASS="+pass+" FAIL=0 (A02b/AT01 syntax; controller admission separate)");
  }
}
