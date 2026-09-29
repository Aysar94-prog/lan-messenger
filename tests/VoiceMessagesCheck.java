package net.lanmsg.chat;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

// Voice Messages Android, AT01: runs the real A01 production classes (VoicePcmAssembler,
// VoiceWav, VoiceSeek, VoiceMarker) against the shared, frozen tests/voice_messages/vectors/
// manifest.json fixtures -- the same canonical file windows/ uses (WT01), so both platforms are
// proven against identical vectors. Mirrors tests/CsharpHarness/VoiceMessagesCheck.cs's
// vector-type handling exactly. Only wav-validation/pcm-frames/seek/marker categories are
// AT01's concern (A01's scope); the newer receiver/registry/scheduler categories belong to
// later tasks (AT02/AT04+) once their production code exists, and are skipped here.
//
// Invoked directly: `java -cp <dir> net.lanmsg.chat.VoiceMessagesCheck <manifestDir>`.
public final class VoiceMessagesCheck {
  static int pass, fail, skip;
  static final List<String> failures = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    String manifestDir = args[0];
    String text = new String(Files.readAllBytes(Paths.get(manifestDir, "manifest.json")), StandardCharsets.UTF_8);
    Map<String, Object> root = MiniJson.asObject(MiniJson.parse(text));
    List<Object> vectors = MiniJson.asArray(root.get("vectors"));

    for (Object vObj : vectors) {
      Map<String, Object> v = MiniJson.asObject(vObj);
      String id = MiniJson.asString(v.get("id"));
      String category = MiniJson.asString(v.get("category"));
      Map<String, Object> exp = MiniJson.asObject(v.get("expectations"));

      if (category.equals("wav-validation")) {
        byte[] data = fixtureBytes(manifestDir, v);
        if (data == null) { skip++; continue; }
        VoiceWavValidation validation = VoiceWav.validate(data);
        boolean wantPass = MiniJson.asString(exp.get("validation")).equals("pass");
        if (validation.pass != wantPass) { record(false, id, "validation pass=" + validation.pass + " want=" + wantPass + " (reason=" + validation.failureReason + ")"); continue; }
        if (validation.pass) {
          VoiceWavInfo info = validation.info;
          List<String> mism = new ArrayList<>();
          checkLong(exp, "duration_ms", info.durationMs, mism);
          checkLong(exp, "sample_rate", info.sampleRate, mism);
          checkLong(exp, "channels", info.channels, mism);
          checkLong(exp, "bits_per_sample", info.bitsPerSample, mism);
          checkLong(exp, "data_bytes", info.dataBytes, mism);
          checkLong(exp, "total_bytes", info.totalBytes, mism);
          record(mism.isEmpty(), id, String.join("; ", mism));
        } else record(true, id, "");
      } else if (category.equals("pcm-frames")) {
        byte[] data = fixtureBytes(manifestDir, v);
        if (data == null) { skip++; continue; }
        runPcmVector(id, data, exp);
      } else if (category.equals("seek")) {
        VoiceWavInfo info = new VoiceWavInfo(VoiceWav.SAMPLE_RATE, VoiceWav.CHANNELS, VoiceWav.BITS_PER_SAMPLE,
            MiniJson.asLong(exp.get("data_bytes")), MiniJson.asLong(exp.get("data_start")), 0, MiniJson.asLong(exp.get("duration_ms")));
        VoiceSeekResult result = VoiceSeek.resolve(info, MiniJson.asLong(exp.get("requested_ms")));
        List<String> mism = new ArrayList<>();
        checkLong(exp, "effective_ns", result.effectiveNs, mism);
        checkLong(exp, "aligned_byte", result.alignedByte, mism);
        String wantState = MiniJson.asString(exp.get("state"));
        if (!wantState.equals(result.state)) mism.add("state got=" + result.state + " want=" + wantState);
        record(mism.isEmpty(), id, String.join("; ", mism));
      } else if (category.equals("marker")) {
        String filename = MiniJson.asString(exp.get("filename"));
        String store = exp.containsKey("store") ? MiniJson.asString(exp.get("store")) : "Normal";
        Object attachmentIdObj = exp.get("attachment_message_id");
        String attachmentId = attachmentIdObj instanceof String ? (String) attachmentIdObj : "";
        String extracted = VoiceMarker.tryParse(filename);
        String classification = VoiceMarker.classify(filename, store, attachmentId);
        List<String> mism = new ArrayList<>();
        if (exp.containsKey("marker_parses")) {
          boolean wantParses = MiniJson.asBool(exp.get("marker_parses"));
          if (wantParses != (extracted != null)) mism.add("marker_parses got=" + (extracted != null) + " want=" + wantParses);
        }
        Object extractedIdObj = exp.get("extracted_message_id");
        if (extractedIdObj instanceof String && !extractedIdObj.equals(extracted)) mism.add("extracted_message_id got=" + extracted + " want=" + extractedIdObj);
        String wantClassRaw = MiniJson.asString(exp.get("classification"));
        String wantClass = wantClassRaw.equals("candidate") ? VoiceMarker.CANDIDATE : VoiceMarker.ORDINARY_ATTACHMENT;
        if (!classification.equals(wantClass)) mism.add("classification got=" + classification + " want=" + wantClassRaw);
        record(mism.isEmpty(), id, String.join("; ", mism));
      } else {
        skip++;
      }
    }

    System.out.println("VOICECHECK\tPASS=" + pass + "\tFAIL=" + fail + "\tSKIP=" + skip);
    for (String f : failures) System.out.println("VOICECHECK-FAIL\t" + f);
    System.exit(fail == 0 ? 0 : 1);
  }

  static void record(boolean ok, String id, String detail) {
    if (ok) pass++;
    else { fail++; failures.add(id + ": " + detail); }
  }

  static void checkLong(Map<String, Object> exp, String key, long got, List<String> mism) {
    if (exp.containsKey(key)) {
      long want = MiniJson.asLong(exp.get(key));
      if (want != got) mism.add(key + " got=" + got + " want=" + want);
    }
  }

  static byte[] fixtureBytes(String manifestDir, Map<String, Object> vector) throws Exception {
    Object sourceObj = vector.get("source");
    if (sourceObj == null) return null;
    Map<String, Object> source = MiniJson.asObject(sourceObj);
    if (source.containsKey("recipe")) return null; // recipe-only (e.g. the 9.6 MB max-boundary case) -- not generated here.
    Path path = Paths.get(manifestDir, MiniJson.asString(source.get("path")));
    long offset = MiniJson.asLong(source.get("offset"));
    long length = MiniJson.asLong(source.get("length"));
    byte[] buf = new byte[(int) length];
    try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "r")) {
      f.seek(offset);
      f.readFully(buf);
    }
    if (source.containsKey("sha256")) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(buf);
      StringBuilder hex = new StringBuilder();
      for (byte b : hash) hex.append(String.format("%02x", b));
      String want = MiniJson.asString(source.get("sha256"));
      if (!hex.toString().equals(want)) throw new IOException("sha256 mismatch reading fixture bytes at " + path + "[" + offset + ":" + (offset + length) + "]");
    }
    return buf;
  }

  static void runPcmVector(String id, byte[] data, Map<String, Object> exp) {
    VoicePcmAssembler asm = new VoicePcmAssembler();
    List<VoicePcmFrame> frames = new ArrayList<>();
    List<String> events = new ArrayList<>();
    List<Object> wantEventsRaw = exp.containsKey("events") ? MiniJson.asArray(exp.get("events")) : Collections.emptyList();
    List<String> wantEvents = new ArrayList<>();
    for (Object e : wantEventsRaw) wantEvents.add(MiniJson.asString(e));

    try {
      if (id.equals("pcm-overflow")) {
        frames.addAll(asm.push(data));
        if (asm.terminallyFailed()) events.add(VoiceMessages.EVT_DEVICE_FAILURE);
      } else if (wantEvents.contains(VoiceMessages.EVT_DISCONTINUITY)) {
        int half = data.length / 2;
        frames.addAll(asm.push(Arrays.copyOfRange(data, 0, half)));
        events.add(asm.discontinuity());
        frames.addAll(asm.push(Arrays.copyOfRange(data, half, data.length)));
        VoicePcmEndResult end = asm.end(); if (end.finalFrame != null) frames.add(end.finalFrame); events.add(end.event);
      } else if (wantEvents.contains(VoiceMessages.EVT_RESTART)) {
        int half = data.length / 2;
        frames.addAll(asm.push(Arrays.copyOfRange(data, 0, half)));
        VoicePcmEndResult end1 = asm.end(); if (end1.finalFrame != null) frames.add(end1.finalFrame); events.add(end1.event);
        events.add(asm.restart());
        frames.addAll(asm.push(Arrays.copyOfRange(data, half, data.length)));
        VoicePcmEndResult end2 = asm.end(); if (end2.finalFrame != null) frames.add(end2.finalFrame); events.add(end2.event);
      } else if (exp.containsKey("callback_plan")) {
        int pos = 0;
        for (Object cbObj : MiniJson.asArray(exp.get("callback_plan"))) {
          Map<String, Object> cb = MiniJson.asObject(cbObj);
          int n = (int) MiniJson.asLong(cb.get("deliver_bytes"));
          frames.addAll(asm.push(Arrays.copyOfRange(data, pos, pos + n)));
          pos += n;
        }
        if (!wantEvents.isEmpty()) { VoicePcmEndResult end = asm.end(); if (end.finalFrame != null) frames.add(end.finalFrame); events.add(end.event); }
      } else {
        frames.addAll(asm.push(data));
        if (!wantEvents.isEmpty()) { VoicePcmEndResult end = asm.end(); if (end.finalFrame != null) frames.add(end.finalFrame); events.add(end.event); }
      }
    } catch (IllegalStateException e) { record(false, id, "unexpected exception: " + e.getMessage()); return; }

    List<String> mism = new ArrayList<>();
    if (exp.containsKey("frame_count")) { long want = MiniJson.asLong(exp.get("frame_count")); if (want != frames.size()) mism.add("frame_count got=" + frames.size() + " want=" + want); }
    long totalBytes = 0; for (VoicePcmFrame f : frames) totalBytes += f.payload.length;
    if (exp.containsKey("total_bytes")) { long want = MiniJson.asLong(exp.get("total_bytes")); if (want != totalBytes) mism.add("total_bytes got=" + totalBytes + " want=" + want); }
    if (exp.containsKey("terminal_failure")) { boolean want = MiniJson.asBool(exp.get("terminal_failure")); if (want != asm.terminallyFailed()) mism.add("terminal_failure got=" + asm.terminallyFailed() + " want=" + want); }
    if (!wantEvents.isEmpty() && !wantEvents.equals(events)) mism.add("events got=" + events + " want=" + wantEvents);
    if (!frames.isEmpty()) {
      if (exp.containsKey("first_sequence")) { long want = MiniJson.asLong(exp.get("first_sequence")); if (want != frames.get(0).sequence) mism.add("first_sequence got=" + frames.get(0).sequence + " want=" + want); }
      if (exp.containsKey("last_sequence")) { long want = MiniJson.asLong(exp.get("last_sequence")); if (want != frames.get(frames.size() - 1).sequence) mism.add("last_sequence got=" + frames.get(frames.size() - 1).sequence + " want=" + want); }
      if (exp.containsKey("first_timestamp_ns")) { long want = MiniJson.asLong(exp.get("first_timestamp_ns")); if (want != frames.get(0).timestampNs) mism.add("first_timestamp_ns got=" + frames.get(0).timestampNs + " want=" + want); }
      if (exp.containsKey("last_timestamp_ns")) { long want = MiniJson.asLong(exp.get("last_timestamp_ns")); if (want != frames.get(frames.size() - 1).timestampNs) mism.add("last_timestamp_ns got=" + frames.get(frames.size() - 1).timestampNs + " want=" + want); }
    }
    record(mism.isEmpty(), id, String.join("; ", mism));
  }
}
