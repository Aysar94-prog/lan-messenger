package net.lanmsg.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Voice Messages (Phase 1 / Android). Transport-independent per tests/voice_messages/contract.md
// (I01), pcm-contract.md (I02) and validation-contract.md (I04). This file must not depend on
// Android UI, PeerEngine, the attachment store, LM4 types, or any device API (AudioRecord/
// AudioTrack/MediaRecorder) -- only platform adapters (later tasks) may touch those. Constants
// and behavior mirror the frozen contract exactly, and mirror windows/VoiceMessages.cs (W01)
// method-for-method so both platforms consume the same shared fixtures identically.
//
// Event/classification/state kinds use plain String constants (not Java enums), matching this
// codebase's existing convention (see PeerEngine.Message.status, "Normal"/"Fast" store names)
// rather than introducing a new idiom.
final class VoiceMessages {
  private VoiceMessages() {}

  static final String EVT_END = "End";
  static final String EVT_DISCONTINUITY = "Discontinuity";
  static final String EVT_DEVICE_FAILURE = "DeviceFailure";
  static final String EVT_RESTART = "Restart";
}

final class VoicePcmFrame {
  final int sequence;
  final long timestampNs;
  final byte[] payload;
  VoicePcmFrame(int sequence, long timestampNs, byte[] payload) {
    this.sequence = sequence; this.timestampNs = timestampNs; this.payload = payload;
  }
}

final class VoicePcmEndResult {
  final VoicePcmFrame finalFrame; // nullable
  final String event;
  VoicePcmEndResult(VoicePcmFrame finalFrame, String event) { this.finalFrame = finalFrame; this.event = event; }
}

// Assembles raw (possibly fragmented, possibly oversized) device callback bytes into complete
// PCM frames per pcm-contract.md. One instance covers exactly one epoch; restart() begins a new
// one. Frames are handed to the caller synchronously via push()'s return value, which the caller
// is expected to consume promptly (e.g. write to disk) -- "pending capacity" (eight frames)
// bounds how many complete frames a single push can produce before the producer must be
// considered to have overrun its consumer; producing a ninth frame within one push is the
// overflow case in pcm-contract.md's Capacity items 1-3, verified against
// tests/voice_messages/vectors/manifest.json's pcm-overflow vector.
final class VoicePcmAssembler {
  static final int FRAME_BYTES = 640;
  static final int FRAME_SAMPLES = 320;
  static final int MAX_FRAMES_PER_PUSH = 8;
  static final long MAX_TOTAL_BYTES = 9_600_000;
  private static final long NS_PER_SAMPLE = 62500; // 1e9 / 16000 Hz

  private final byte[] carry = new byte[FRAME_BYTES];
  private int carryLen;
  private int nextSequence;
  private long completedSamples;
  private long acceptedBytes;
  private boolean ended;
  private boolean terminallyFailed;

  boolean terminallyFailed() { return terminallyFailed; }
  boolean ended() { return ended; }
  int pendingOddBytes() { return carryLen % 2; }

  // Splits `fragment` into complete 640-byte frames, carrying any partial trailing bytes
  // (0..639) over to the next push. Rejects input beyond the 9,600,000-byte maximum instead of
  // truncating it, per contract.md's "Maximum PCM data" / pcm-contract.md Capacity item 5. If
  // producing a frame would exceed the eight-frame-per-push capacity, the epoch fails terminally
  // (terminallyFailed() becomes true) and this returns only the frames already produced before
  // the overflow -- callers must check terminallyFailed() after every push.
  List<VoicePcmFrame> push(byte[] fragment) {
    if (terminallyFailed) throw new IllegalStateException("epoch already terminally failed");
    if (ended) throw new IllegalStateException("stream already ended; restart() begins a new epoch");
    if (acceptedBytes + fragment.length > MAX_TOTAL_BYTES)
      throw new IllegalStateException("input exceeds the 9,600,000-byte PCM maximum; rejected, never truncated");
    acceptedBytes += fragment.length;
    List<VoicePcmFrame> frames = new ArrayList<>();
    int i = 0;
    while (i < fragment.length) {
      int take = Math.min(FRAME_BYTES - carryLen, fragment.length - i);
      System.arraycopy(fragment, i, carry, carryLen, take);
      carryLen += take;
      i += take;
      if (carryLen == FRAME_BYTES) {
        if (frames.size() >= MAX_FRAMES_PER_PUSH) { terminallyFailed = true; carryLen = 0; return frames; }
        frames.add(emitFrame(Arrays.copyOf(carry, FRAME_BYTES)));
        carryLen = 0;
      }
    }
    return frames;
  }

  private VoicePcmFrame emitFrame(byte[] payload) {
    int samples = payload.length / 2;
    VoicePcmFrame frame = new VoicePcmFrame(nextSequence++, (completedSamples + samples) * NS_PER_SAMPLE, payload);
    completedSamples += samples;
    return frame;
  }

  // Marks a gap in the sample stream without closing the epoch; sequence/timestamp continue.
  String discontinuity() {
    if (terminallyFailed || ended) throw new IllegalStateException("epoch not accepting events");
    return VoiceMessages.EVT_DISCONTINUITY;
  }

  // Normal termination. Any pending even-length partial buffer becomes a final short (but
  // complete-sample) frame; an odd trailing byte (an incomplete sample) is fatal -- the epoch
  // fails terminally and no truncated frame is emitted.
  VoicePcmEndResult end() {
    if (terminallyFailed) throw new IllegalStateException("epoch already terminally failed");
    if (ended) throw new IllegalStateException("stream already ended");
    if (carryLen % 2 != 0) {
      terminallyFailed = true; carryLen = 0;
      return new VoicePcmEndResult(null, VoiceMessages.EVT_DEVICE_FAILURE);
    }
    ended = true;
    if (carryLen == 0) return new VoicePcmEndResult(null, VoiceMessages.EVT_END);
    VoicePcmFrame finalFrame = emitFrame(Arrays.copyOf(carry, carryLen));
    carryLen = 0;
    return new VoicePcmEndResult(finalFrame, VoiceMessages.EVT_END);
  }

  // Device loss mid-recording: terminal for this epoch; the draft becomes invalid.
  String deviceFailure() {
    terminallyFailed = true; carryLen = 0;
    return VoiceMessages.EVT_DEVICE_FAILURE;
  }

  // Closes the current epoch and resets sequence/timestamp/carry state for a new one.
  String restart() {
    nextSequence = 0; completedSamples = 0; acceptedBytes = 0; carryLen = 0; ended = false; terminallyFailed = false;
    return VoiceMessages.EVT_RESTART;
  }
}

// Only two outcomes: a marker that parses, matches the attachment's own message id, and names
// the Normal store is a Candidate; anything else (parse failure, id mismatch, or a non-Normal
// store) is an ordinary attachment from the start -- it is never shown as pending voice UI at
// all. "Invalid marked content" is a distinct, later-stage receiver state reached only when a
// genuine Candidate's retrieved bytes fail WAV validation -- it is not a possible outcome of
// this classification.
final class VoiceMarker {
  static final String CANDIDATE = "Candidate";
  static final String ORDINARY_ATTACHMENT = "OrdinaryAttachment";

  private static final String PREFIX = "voice-";
  private static final String SUFFIX = ".lanvoice.wav";

  private VoiceMarker() {}

  static String fileName(String messageId) { return PREFIX + messageId + SUFFIX; }

  // Parses the marker syntax only; does not validate that the id matches the attachment's actual
  // message id (callers cross-check that, and content validation is separate -- see contract.md
  // "Candidate classification remains separate from content validation"). Returns null if the
  // filename does not match the marker pattern.
  static String tryParse(String fileName) {
    if (!fileName.startsWith(PREFIX) || !fileName.endsWith(SUFFIX)) return null;
    String id = fileName.substring(PREFIX.length(), fileName.length() - SUFFIX.length());
    if (id.isEmpty()) return null;
    return id;
  }

  // Classifies a received attachment for receiver-UI purposes. A forged store (anything but
  // Normal -- Voice Messages never use Fast, contract.md Decision 2) or a mismatched id falls
  // straight back to an ordinary attachment, exactly like an unmarked filename -- per
  // tests/voice_messages/vectors/manifest.json's marker-forged/marker-mismatched vectors, neither
  // case is ever shown as a voice Candidate.
  static String classify(String fileName, String store, String attachmentMessageId) {
    String extractedId = tryParse(fileName);
    if (extractedId == null) return ORDINARY_ATTACHMENT;
    if (!store.equals("Normal") || !extractedId.equals(attachmentMessageId)) return ORDINARY_ATTACHMENT;
    return CANDIDATE;
  }
}

final class VoiceWavInfo {
  final int sampleRate, channels, bitsPerSample;
  final long dataBytes, dataOffset, totalBytes, durationMs;
  VoiceWavInfo(int sampleRate, int channels, int bitsPerSample, long dataBytes, long dataOffset, long totalBytes, long durationMs) {
    this.sampleRate = sampleRate; this.channels = channels; this.bitsPerSample = bitsPerSample;
    this.dataBytes = dataBytes; this.dataOffset = dataOffset; this.totalBytes = totalBytes; this.durationMs = durationMs;
  }
}

final class VoiceWavValidation {
  final boolean pass;
  final String failureReason; // nullable
  final VoiceWavInfo info; // nullable
  private VoiceWavValidation(boolean pass, String failureReason, VoiceWavInfo info) {
    this.pass = pass; this.failureReason = failureReason; this.info = info;
  }
  static VoiceWavValidation fail(String reason) { return new VoiceWavValidation(false, reason, null); }
  static VoiceWavValidation ok(VoiceWavInfo info) { return new VoiceWavValidation(true, null, info); }
}

// Bounded streaming WAV validation and canonical WAV read/write per contract.md's 14-item
// checklist. Only the fixed contract format (16 kHz mono 16-bit PCM) is ever accepted.
final class VoiceWav {
  static final int SAMPLE_RATE = 16000;
  static final int CHANNELS = 1;
  static final int BITS_PER_SAMPLE = 16;
  static final int BLOCK_ALIGN = 2;
  static final int BYTE_RATE = 32000;
  static final long MAX_DATA_BYTES = 9_600_000;
  static final long MAX_TOTAL_BYTES = 9_600_044;
  static final int CANONICAL_HEADER_BYTES = 44;

  private VoiceWav() {}

  // Builds a canonical 44-byte-header WAV around already-validated contract-format PCM data.
  static byte[] buildCanonical(byte[] pcmData) throws IOException {
    if (pcmData.length > MAX_DATA_BYTES) throw new IOException("PCM data exceeds the 9,600,000-byte maximum.");
    if (pcmData.length % BLOCK_ALIGN != 0) throw new IOException("PCM data length must be a whole number of samples.");
    int total = CANONICAL_HEADER_BYTES + pcmData.length;
    byte[] buf = new byte[total];
    ascii(buf, 0, "RIFF"); u32(buf, 4, total - 8); ascii(buf, 8, "WAVE");
    ascii(buf, 12, "fmt "); u32(buf, 16, 16); u16(buf, 20, 1); u16(buf, 22, CHANNELS);
    u32(buf, 24, SAMPLE_RATE); u32(buf, 28, BYTE_RATE); u16(buf, 32, BLOCK_ALIGN); u16(buf, 34, BITS_PER_SAMPLE);
    ascii(buf, 36, "data"); u32(buf, 40, pcmData.length);
    System.arraycopy(pcmData, 0, buf, CANONICAL_HEADER_BYTES, pcmData.length);
    return buf;
  }

  private static void ascii(byte[] buf, int offset, String s) {
    byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(bytes, 0, buf, offset, bytes.length);
  }
  private static void u32(byte[] buf, int offset, long v) {
    buf[offset] = (byte) v; buf[offset + 1] = (byte) (v >> 8); buf[offset + 2] = (byte) (v >> 16); buf[offset + 3] = (byte) (v >> 24);
  }
  private static void u16(byte[] buf, int offset, int v) {
    buf[offset] = (byte) v; buf[offset + 1] = (byte) (v >> 8);
  }
  private static long readU32(byte[] data, int offset) {
    return (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8) | ((data[offset + 2] & 0xFFL) << 16) | ((data[offset + 3] & 0xFFL) << 24);
  }
  private static int readU16(byte[] data, int offset) {
    return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
  }

  // Streaming, bounds-checked validation. Reads only the bytes it needs. Every arithmetic step
  // that could overflow is checked; on any structural problem this returns fail() with a stable
  // reason instead of throwing.
  static VoiceWavValidation validate(byte[] data) {
    if (data.length == 0) return VoiceWavValidation.fail("empty file");
    if (data.length < 12) return VoiceWavValidation.fail("missing RIFF identifier");
    if (data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F') return VoiceWavValidation.fail("missing RIFF identifier");
    if (data[8] != 'W' || data[9] != 'A' || data[10] != 'V' || data[11] != 'E') return VoiceWavValidation.fail("missing WAVE identifier");
    if (data.length > MAX_TOTAL_BYTES) return VoiceWavValidation.fail("file exceeds the maximum stored size");

    boolean haveFmt = false, haveData = false;
    int fmtChannels = 0, fmtBits = 0; long fmtRate = 0, fmtByteRate = 0; int fmtBlockAlign = 0, fmtTag = 0;
    long dataOffset = 0, dataLen = 0;
    long offset = 12;
    while (offset + 8 <= data.length) {
      String id = new String(data, (int) offset, 4, StandardCharsets.US_ASCII);
      long declaredSize = readU32(data, (int) offset + 4);
      long bodyStart = offset + 8;
      long bodyEnd = bodyStart + declaredSize;
      if (declaredSize > Integer.MAX_VALUE || bodyEnd < bodyStart || bodyEnd > data.length) return VoiceWavValidation.fail("truncated data chunk");
      if (id.equals("fmt ")) {
        if (haveFmt) return VoiceWavValidation.fail("duplicate fmt chunk");
        if (haveData) return VoiceWavValidation.fail("reordered chunks");
        if (declaredSize < 16) return VoiceWavValidation.fail("truncated data chunk");
        fmtTag = readU16(data, (int) bodyStart);
        fmtChannels = readU16(data, (int) bodyStart + 2);
        fmtRate = readU32(data, (int) bodyStart + 4);
        fmtByteRate = readU32(data, (int) bodyStart + 8);
        fmtBlockAlign = readU16(data, (int) bodyStart + 12);
        fmtBits = readU16(data, (int) bodyStart + 14);
        haveFmt = true;
      } else if (id.equals("data")) {
        if (haveData) return VoiceWavValidation.fail("duplicate data chunk");
        if (!haveFmt) return VoiceWavValidation.fail("reordered chunks");
        dataOffset = bodyStart; dataLen = declaredSize; haveData = true;
      }
      // Unknown/optional chunks are skipped via their checked size; RIFF padding (chunks are
      // word-aligned) is honored below.
      long advance = declaredSize + (declaredSize % 2);
      long next = bodyStart + advance;
      if (next < bodyStart) return VoiceWavValidation.fail("truncated data chunk");
      offset = next;
    }
    if (!haveFmt || !haveData) return VoiceWavValidation.fail(!haveFmt ? "missing fmt chunk" : "missing data chunk");
    if (fmtTag != 1) return VoiceWavValidation.fail("unsupported audio format");
    if (fmtChannels != CHANNELS || fmtRate != SAMPLE_RATE || fmtBits != BITS_PER_SAMPLE) return VoiceWavValidation.fail("unsupported sample rate");
    if (fmtBlockAlign != BLOCK_ALIGN || fmtByteRate != BYTE_RATE) return VoiceWavValidation.fail("incorrect block alignment");
    if (dataLen % BLOCK_ALIGN != 0) return VoiceWavValidation.fail("data too short for a complete frame");
    if (dataLen == 0) return VoiceWavValidation.fail("data too short for a complete frame");
    if (dataLen > MAX_DATA_BYTES) return VoiceWavValidation.fail("file exceeds the maximum stored size");
    // Trailing bytes after the last parsed chunk (beyond data+padding) are rejected: the fixed
    // contract format never carries extra chunks after `data`.
    long expectedEnd = dataOffset + dataLen + (dataLen % 2);
    if (expectedEnd < data.length) return VoiceWavValidation.fail("trailing bytes after data chunk");

    long durationMs = dataLen * 1000 / BYTE_RATE;
    VoiceWavInfo info = new VoiceWavInfo((int) fmtRate, fmtChannels, fmtBits, dataLen, dataOffset, data.length, durationMs);
    return VoiceWavValidation.ok(info);
  }
}

final class VoiceSeekResult {
  final long effectiveNs, alignedByte;
  final String state; // "ready" or "completed"
  VoiceSeekResult(long effectiveNs, long alignedByte, String state) {
    this.effectiveNs = effectiveNs; this.alignedByte = alignedByte; this.state = state;
  }
}

// Implements contract.md's seven-step seek procedure exactly.
final class VoiceSeek {
  private VoiceSeek() {}

  static VoiceSeekResult resolve(VoiceWavInfo info, long requestedMs) {
    // 1. Clamp the requested time to the validated duration.
    long clampedMs = Math.max(0, Math.min(requestedMs, info.durationMs));
    // 2. Convert time using checked arithmetic.
    long requestedSamples = Math.multiplyExact(clampedMs, (long) VoiceWav.SAMPLE_RATE) / 1000;
    // 3. Resolve the byte position within the validated WAV data range.
    long byteOffset = Math.multiplyExact(requestedSamples, (long) VoiceWav.BLOCK_ALIGN);
    if (byteOffset > info.dataBytes) byteOffset = info.dataBytes;
    // 4. Align downward to a complete two-byte sample (already even by construction, but
    //    enforced defensively).
    byteOffset -= byteOffset % VoiceWav.BLOCK_ALIGN;
    long alignedByte = Math.addExact(info.dataOffset, byteOffset);
    // 5/6. Invalidate old output and reset playback sequence/timestamp state: caller-side
    // (player) responsibility once given the resolved position below.
    long sampleOffset = byteOffset / VoiceWav.BLOCK_ALIGN;
    long effectiveNs = Math.multiplyExact(sampleOffset, 62500L);
    String state = byteOffset >= info.dataBytes ? "completed" : "ready";
    // 7. Report the effective aligned position to the UI.
    return new VoiceSeekResult(effectiveNs, alignedByte, state);
  }
}
