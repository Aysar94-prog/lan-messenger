package net.lanmsg.chat;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Confirmed A02b v2 syntax boundary. Authorization/roles/generation are enforced
 * separately by the service-owned controller. Never apply these new fields to v1. */
public final class CallVideoProtocol {
  private CallVideoProtocol() {}
  public static final String CALLCAPS = CallCapabilities.REQUEST;
  public static final String CAPABILITY = CallCapabilities.RESPONSE;
  public static final long REQUEST_TIMEOUT_MS = 30_000, VIDEO_TIMEOUT_MS = 15_000;
  public static final int MAX_CANDIDATE_BYTES = 4096, MAX_REQUESTS = 128;
  public static boolean capable(String response, boolean verified, boolean legacy, long elapsedMs) {
    return CallCapabilities.supports(response,verified,legacy,elapsedMs);
  }
  public static String capabilityResponse(boolean legacy) { return legacy ? null : CAPABILITY; }
  private static boolean keys(Map<String,Object> b, String... keys) {
    return b.keySet().equals(new HashSet<String>(Arrays.asList(keys)));
  }
  private static boolean integral(Object n) {
    return n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte;
  }
  private static boolean text(Object value, int max) {
    if (!(value instanceof String)) return false;
    String s=(String)value;
    return !s.isEmpty() && s.getBytes(StandardCharsets.UTF_8).length<=max
      && s.indexOf('\0')<0 && s.indexOf('\r')<0 && s.indexOf('\n')<0;
  }
  private static boolean request(Object value) {
    return value instanceof String && CallProtocol.validCallId((String)value);
  }
  private static boolean video(Map<String,Object> b) { return "video".equals(b.get("media")); }
  private static boolean media(Map<String,Object> b) {
    return "audio".equals(b.get("media")) || video(b);
  }
  private static boolean mediaKeys(Map<String,Object> b, String... fields) {
    if (!media(b)) return false;
    List<String> names=new ArrayList<String>(Arrays.asList(fields));
    names.add("media");
    if (video(b)) { names.add("request"); if (!request(b.get("request"))) return false; }
    return keys(b,names.toArray(new String[0]));
  }
  public static boolean valid(CallProtocol.Frame f) {
    if (f==null || f.protocolVersion!=2 || !CallProtocol.validCallId(f.callId)
        || f.senderSequence<=0 || f.negotiationGeneration<0 || f.body==null || f.type==null) return false;
    Map<String,Object> b=f.body; long gen=f.negotiationGeneration;
    switch(f.type) {
      case "INVITE": return gen==0 && keys(b,"caller","callee","media") && media(b)
          && request(b.get("caller")) && request(b.get("callee"));
      case "ACCEPT": return gen==0 && keys(b,"media") && media(b);
      case "VIDEO_REQUEST": case "VIDEO_ACCEPT": case "VIDEO_DECLINE":
        return gen==0 && keys(b,"request") && request(b.get("request"));
      case "OFFER": case "ANSWER":
        return mediaKeys(b,"sdp") && generation(b,gen)
          && validSdp(b.get("sdp"),video(b));
      case "ICE":
        return mediaKeys(b,"candidate","sdpMid","sdpMLineIndex") && generation(b,gen)
          && text(b.get("candidate"),MAX_CANDIDATE_BYTES)
          && ((String)b.get("candidate")).startsWith("candidate:")
          && text(b.get("sdpMid"),64) && integral(b.get("sdpMLineIndex"))
          && ((Number)b.get("sdpMLineIndex")).longValue()==0;
      case "VIDEO_STATE":
        return gen>=2 && keys(b,"request","camera","revision") && request(b.get("request"))
          && b.get("camera") instanceof Boolean && integral(b.get("revision"))
          && ((Number)b.get("revision")).longValue()>=0;
      case "MEDIA_READY": return mediaKeys(b) && generation(b,gen);
      case "ERROR": return mediaKeys(b,"code") && generation(b,gen)
          && Arrays.asList("failed","timeout","unsupported").contains(b.get("code"));
      case "RINGING": case "DECLINE": case "BUSY": case "CANCEL": case "HANGUP":
      case "PING": case "PONG": return gen==0 && b.isEmpty();
      case "REMOTE_SPEAKER": return gen==0 && keys(b,"speaker") && b.get("speaker") instanceof Boolean;
      case "REMOTE_CAMERA": return gen>=2 && keys(b,"request","camera","facing")
        && request(b.get("request")) && b.get("camera") instanceof Boolean
        && Arrays.asList("front","rear","keep").contains(b.get("facing"));
      default: return false;
    }
  }
  private static boolean generation(Map<String,Object> b,long gen) { return video(b) ? gen>=2 : gen==1; }

  public static boolean validSdp(Object value, boolean video) {
    if (!(value instanceof String)) return false;
    String sdp=(String)value;
    if (!sdp.startsWith("v=0\r\n") || sdp.indexOf('\0')>=0
        || sdp.getBytes(StandardCharsets.UTF_8).length>CallProtocol.MAX_SDP_BYTES) return false;
    String[] lines=sdp.split("\r\n",-1);
    String[] section=null; boolean fingerprint=false, codec=false; int mLines=0;
    for(String line:lines) {
      if (line.indexOf('\r')>=0 || line.indexOf('\n')>=0) return false;
      if (line.startsWith("m=")) {
        ++mLines; section=line.split(" +");
        if (section.length<4 || !section[0].equals(video?"m=video":"m=audio")
            || !section[1].matches("[1-9][0-9]{0,4}")
            || Integer.parseInt(section[1])>65535 || !section[2].equals("UDP/TLS/RTP/SAVPF")) return false;
        for(int i=3;i<section.length;i++) if (!section[i].matches("[0-9]{1,3}")
            || Integer.parseInt(section[i])>127) return false;
      }
      if (line.startsWith("a=fingerprint:")) {
        if (!line.matches("a=fingerprint:sha-256 [a-fA-F0-9]{2}(?::[a-fA-F0-9]{2}){31}")) return false;
        fingerprint=true;
      }
      if (line.startsWith("a=rtpmap:")) {
        String[] mapping=line.substring(9).split(" +");
        if (mapping.length!=2 || section==null) return false;
        boolean offered=false;
        for(int i=3;i<section.length;i++) if (mapping[0].equals(section[i])) offered=true;
        if (!offered) return false;
        if (mapping[1].equals(video?"VP8/90000":"G722/8000")) codec=true;
        if (video && !Arrays.asList("VP8/90000","rtx/90000","red/90000","ulpfec/90000").contains(mapping[1])) return false;
      }
    }
    return mLines==1 && fingerprint && codec;
  }
}
