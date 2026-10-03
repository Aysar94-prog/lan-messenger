package net.lanmsg.chat;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Test-only provisional A02 contract. Never compile into either production app. */
final class DraftVideoContract {
  static final long CAPABILITY_TIMEOUT_MS = 10_000;
  static final Set<String> CODECS = new HashSet<>(Arrays.asList("VP8", "VP9", "H264"));

  static Set<String> capabilities(String response, boolean authenticated, boolean legacy,
                                  long elapsedMs) {
    if (!authenticated || legacy || elapsedMs < 0 || elapsedMs >= CAPABILITY_TIMEOUT_MS
        || response == null || response.getBytes(StandardCharsets.UTF_8).length > 128)
      return Collections.emptySet();
    String[] fields = response.split("\t", -1);
    if (fields.length != 4 || !fields[0].equals("LM4") || !fields[1].equals("CALLCAPS")
        || !fields[2].equals("2")) return Collections.emptySet();
    Set<String> result = new LinkedHashSet<>();
    for (String codec : fields[3].split(",", -1))
      if (!CODECS.contains(codec) || !result.add(codec)) return Collections.emptySet();
    return result;
  }

  static String capabilityResponse(boolean simulateLegacy) {
    return simulateLegacy ? null : "LM4\tCALLCAPS\t2\tVP8,VP9,H264";
  }

  static boolean validSdp(String sdp) {
    return sdp != null && sdp.startsWith("v=0\r\n") && sdp.contains("\r\nm=")
      && sdp.getBytes(StandardCharsets.UTF_8).length <= CallProtocol.MAX_SDP_BYTES;
  }

  static final class Boundary {
    final String peer, call;
    long sequence, generation;
    boolean localConsent, peerConsent, permission, mediaReady, foreground, camera;
    String request;

    Boundary(String peer, String call) { this.peer = peer; this.call = call; }

    void updateCamera() {
      camera = localConsent && peerConsent && permission && mediaReady && foreground;
    }

    boolean accept(CallProtocol.Frame f, String authenticatedPeer) {
      if (f == null || f.protocolVersion != 2 || !peer.equals(authenticatedPeer)
          || !call.equals(f.callId) || f.senderSequence <= sequence
          || f.negotiationGeneration < 0 || f.body == null) return false;
      if (f.type.equals("ACCEPT")) {
        Object mode = f.body.get("media");
        if (!"audio".equals(mode) && !"video".equals(mode)) return false;
        peerConsent = "video".equals(mode);
        updateCamera();
      } else if (f.type.equals("VIDEO_ACCEPT") || f.type.equals("VIDEO_DECLINE")) {
        if (request == null || !request.equals(f.body.get("request"))) return false;
        peerConsent = f.type.equals("VIDEO_ACCEPT");
        updateCamera();
        if (!peerConsent) { localConsent = false; request = null; }
      } else if (f.type.equals("OFFER")) {
        if (!localConsent || !peerConsent || f.negotiationGeneration <= generation
            || !(f.body.get("sdp") instanceof String) || !validSdp((String)f.body.get("sdp"))) return false;
        generation = f.negotiationGeneration;
      } else if (f.type.equals("ANSWER") || f.type.equals("ICE")) {
        if (!localConsent || !peerConsent || generation == 0
            || f.negotiationGeneration != generation) return false;
        if (f.type.equals("ANSWER") && (!(f.body.get("sdp") instanceof String)
            || !validSdp((String)f.body.get("sdp")))) return false;
      } else return false;
      sequence = f.senderSequence;
      return true;
    }
  }
}
