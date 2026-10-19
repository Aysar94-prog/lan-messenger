package net.lanmsg.chat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A02/AT01 draft boundary tests. These are not production video acceptance. */
public final class DraftVideoContractCheck {
  static int pass;
  static final String CALL = "00000000-0000-0000-0000-000000000001";
  static final String SDP = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n";
  static void check(boolean value, String label) {
    if (!value) throw new AssertionError(label);
    pass++;
  }
  static CallProtocol.Frame frame(String type, long seq, long gen, String key, Object value) {
    CallProtocol.Frame f = new CallProtocol.Frame(type, CALL, seq, gen);
    f.protocolVersion = 2;
    f.body.put(key, value);
    return f;
  }
  public static void main(String[] args) {
    CallProtocol.Frame voice = CallSignaling.invite(CALL, 1, "a", "b");
    String expected = "{\"v\":1,\"t\":\"INVITE\",\"cid\":\"" + CALL
      + "\",\"seq\":1,\"gen\":0,\"b\":{\"caller\":\"a\",\"callee\":\"b\"}}";
    byte[] wire = CallSignaling.serialize(voice);
    check(ByteBuffer.wrap(wire).getInt() == expected.getBytes(StandardCharsets.UTF_8).length,
      "version-1 big-endian framing");
    check(new String(wire, 4, wire.length - 4, StandardCharsets.UTF_8).equals(expected),
      "version-1 exact invitation bytes");
    check(CallSignaling.parse(wire).protocolVersion == 1, "version-1 parse");
    check(CallProtocol.allowedSender("OFFER", CallProtocol.State.Connected, false) == null,
      "production voice has no Connected re-offer before gate");
    check(CallProtocol.allowedSender("OFFER", CallProtocol.State.Connecting, false) != null,
      "production voice offer admission retained");
    check(CallSignaling.parse(Arrays.copyOf(wire, wire.length - 1)) == null, "truncated frame");
    String caps = DraftVideoContract.capabilityResponse(false);
    check(DraftVideoContract.capabilities(caps, true, false, 1).size() == 3, "draft candidates");
    check(DraftVideoContract.capabilities(caps, false, false, 1).isEmpty(), "unverified channel");
    check(DraftVideoContract.capabilities(null, true, false, 1).isEmpty(), "absent response");
    check(DraftVideoContract.capabilities(caps, true, false, 10_000).isEmpty(), "deadline timeout");
    check(DraftVideoContract.capabilities(caps, true, false, 9_999).size() == 3, "before deadline");
    check(DraftVideoContract.capabilityResponse(true) == null, "legacy token suppressed");
    check(DraftVideoContract.capabilities(caps, true, true, 1).isEmpty(), "legacy audio fallback");
    for (String bad : Arrays.asList("", "LM4\tPAIR", "LM4\tCAPS\t2", "LM4\tFILECAPS\tSTREAM1",
        "LM4\tCALLCAPS\t3\tVP8", "LM4\tCALLCAPS\t2\t", "LM4\tCALLCAPS\t2\tVP8,VP8",
        "LM4\tCALLCAPS\t2\tAV1", "LM4\tCALLCAPS\t2\tVP8\textra"))
      check(DraftVideoContract.capabilities(bad, true, false, 1).isEmpty(), "bad capability " + bad);
    DraftVideoContract.Boundary b = new DraftVideoContract.Boundary("peer", CALL);
    b.localConsent = true; b.permission = true;
    check(b.accept(frame("ACCEPT", 1, 0, "media", "audio"), "peer") && !b.camera,
      "answer with voice leaves camera stopped");
    b.request = CALL;
    check(b.accept(frame("VIDEO_DECLINE", 2, 0, "request", CALL), "peer") && !b.camera,
      "declined upgrade leaves camera stopped");
    check(!b.accept(frame("OFFER", 3, 1, "sdp", SDP), "peer"), "SDP needs bilateral consent");
    b.request = CALL; b.localConsent = true;
    check(!b.accept(frame("VIDEO_ACCEPT", 3, 0, "request", "stale"), "peer"), "stale request");
    b.permission = false;
    check(b.accept(frame("VIDEO_ACCEPT", 3, 0, "request", CALL), "peer") && !b.camera,
      "peer consent cannot bypass current permission");
    CallProtocol.Frame offer = frame("OFFER", 4, 1, "sdp", SDP);
    check(!b.accept(offer, "foreign"), "wrong authenticated peer");
    offer.callId = "00000000-0000-0000-0000-000000000002";
    check(!b.accept(offer, "peer"), "wrong call ID");
    offer.callId = CALL;
    check(b.accept(offer, "peer"), "authorized generation");
    check(!b.accept(offer, "peer"), "duplicate sequence");
    check(!b.accept(frame("OFFER", 5, 1, "sdp", SDP), "peer"), "stale offer generation");
    check(!b.accept(frame("ANSWER", 5, 0, "sdp", SDP), "peer"), "stale answer");
    check(!b.accept(frame("ICE", 5, 2, "candidate", "candidate:test"), "peer"), "future ICE");
    check(b.accept(frame("ANSWER", 5, 1, "sdp", SDP), "peer"), "matching answer");
    check(!b.accept(frame("ANSWER", 6, 1, "sdp", "garbage"), "peer"), "malformed SDP");
    check(!b.accept(frame("ANSWER", 6, 1, "sdp", Boolean.TRUE), "peer"), "wrong SDP field type");
    check(!b.accept(frame("ANSWER", 6, 1, "sdp", SDP + String.join("", Collections.nCopies(49_152, "x"))),
      "peer"), "SDP byte limit");
    String unicode = SDP + String.join("", Collections.nCopies(24_576, "é"));
    check(!DraftVideoContract.validSdp(unicode), "UTF-8 bytes not UTF-16 character count");
    byte[] v2 = CallSignaling.serialize(frame("ACCEPT", 7, 0, "media", "audio"));
    check(CallSignaling.parse(v2).protocolVersion == 2, "draft uses retained envelope parser");
    b.permission = true; b.updateCamera();
    check(!b.camera, "permission and consent cannot capture before authorized media");
    b.mediaReady = true; b.updateCamera(); check(!b.camera, "background state cannot capture");
    b.foreground = true; b.updateCamera(); check(b.camera, "all local capture conditions required");
    System.out.println("DraftVideoContractCheck PASS=" + pass + " FAIL=0 (provisional; no production video)");
  }
}
