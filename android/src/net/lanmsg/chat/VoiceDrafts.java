package net.lanmsg.chat;

import java.io.*;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

// Voice Messages (Phase 1 / Android), A02: the durable application-private draft registry.
// Recording/playback device access (A03) and UI (A04) are separate, later tasks; this file only
// persists draft metadata and encrypted PCM/WAV bytes, matching AttachmentStore.java's existing
// encryption construction (per-file random AES-256-CBC key/IV wrapped by PeerEngine's protector).
// Same shape as GroupSync.java/AttachmentStore.java: static methods taking the engine instance,
// synchronized(e) for anything touching engine state, thin delegating wrappers on PeerEngine
// itself. Mirrors windows/VoiceDrafts.cs (W02) method-for-method.
final class VoiceDrafts {
  private VoiceDrafts() {}

  static File voiceDraftPath(PeerEngine e, String draftId) throws IOException {
    if (!PeerEngine.uuid(draftId)) throw new IOException("Invalid draft ID");
    return new File(new File(e.file.getParentFile(), "voice-drafts"), draftId + ".sec");
  }

  static List<PeerEngine.VoiceDraft> voiceDraftsFor(PeerEngine e, String conversation) {
    synchronized (e) {
      ArrayList<PeerEngine.VoiceDraft> result = new ArrayList<>();
      for (PeerEngine.VoiceDraft d : e.voiceDrafts.values()) if (d.conversationId.equals(conversation)) result.add(d.copy());
      result.sort(Comparator.comparingLong(d -> d.createdAt));
      return result;
    }
  }

  static PeerEngine.VoiceDraft getVoiceDraft(PeerEngine e, String draftId) {
    synchronized (e) { PeerEngine.VoiceDraft d = e.voiceDrafts.get(draftId); return d == null ? null : d.copy(); }
  }

  // Registry rule 7/8: a draft whose conversation can no longer send is Preview/Delete-only and
  // is never retargeted -- this only ever reports whether Send is currently allowed; it never
  // changes conversationId.
  static boolean voiceDraftSendable(PeerEngine e, String draftId) {
    synchronized (e) {
      PeerEngine.VoiceDraft d = e.voiceDrafts.get(draftId);
      if (d == null || !d.state.equals(PeerEngine.VoiceDraft.FINALIZED)) return false;
      return d.isGroup ? e.groups.containsKey(d.conversationId) : e.peers.containsKey(d.conversationId);
    }
  }

  // Ten-step durable write order, step 1: create and durably register draft ownership before any
  // microphone frame is accepted. The 10-draft cap blocks only new creation; every existing draft
  // stays fully available for Preview/Send/Delete/recovery (Registry rule 3).
  static String createVoiceDraft(PeerEngine e, String conversation, boolean isGroup) throws IOException {
    synchronized (e) {
      if (e.voiceDrafts.size() >= PeerEngine.VOICE_DRAFT_CAP)
        throw new IOException("Voice draft limit reached (" + PeerEngine.VOICE_DRAFT_CAP + "). Send or delete an existing draft first.");
      String id = UUID.randomUUID().toString(); long at = System.currentTimeMillis();
      PeerEngine.VoiceDraft d = new PeerEngine.VoiceDraft(id, conversation, isGroup, at, at, PeerEngine.VoiceDraft.RECORDING, 0, 0);
      e.voiceDrafts.put(id, d);
      try { e.save(); } catch (IOException ex) { e.voiceDrafts.remove(id); throw ex; }
      return id;
    }
  }

  // Steps 2-3: begin accepting microphone frames and write normalized PCM straight to
  // application-private storage. Exactly one writer per draft (mirrors the one-recorder rule);
  // the caller (A03/A04) drives writeFrame per assembled VoicePcmFrame.
  static VoiceDraftWriter openVoiceDraftWriter(PeerEngine e, String draftId) throws IOException {
    synchronized (e) {
      PeerEngine.VoiceDraft d = e.voiceDrafts.get(draftId);
      if (d == null) throw new IOException("Unknown voice draft.");
      if (!d.state.equals(PeerEngine.VoiceDraft.RECORDING)) throw new IOException("Draft is not in a recording state.");
      if (!e.openVoiceDraftWriters.add(draftId)) throw new IOException("Draft already has an open writer.");
      File path = voiceDraftPath(e, draftId);
      if (!path.getParentFile().exists() && !path.getParentFile().mkdirs()) throw new IOException("Cannot create voice draft storage");
      return new VoiceDraftWriter(e, draftId, path);
    }
  }

  static void closeVoiceDraftWriter(PeerEngine e, String draftId) { synchronized (e) { e.openVoiceDraftWriters.remove(draftId); } }

  // Keeps the in-memory byte-size figure current (for live UI feedback) as frames land.
  // Deliberately in-memory only, not persisted -- see windows/VoiceDrafts.cs's identical note: a
  // Recording-state entry is unconditionally diagnosed Invalid by reconcile() on the next startup
  // regardless of its recorded byte size (no writer ever survives a process exit), so durably
  // rewriting the whole encrypted store on every ~20 ms frame would buy zero recovery benefit at
  // a real cost -- a 5-minute recording is up to ~15,000 frames.
  static void recordVoiceDraftProgress(PeerEngine e, String draftId, long byteSize) {
    synchronized (e) { PeerEngine.VoiceDraft d = e.voiceDrafts.get(draftId); if (d != null) d.byteSize = byteSize; }
  }

  // Steps 4-6: safely finalize the WAV, flush and validate the completed file, and mark the
  // draft recoverable only after that validation actually passes. A failure here -- including one
  // raised by the caller via invalidateVoiceDraft below, e.g. a terminal PCM DeviceFailure --
  // leaves a diagnosed invalid entry (crash-outcome 3), never a phantom playable/sendable draft.
  static VoiceWavValidation finalizeVoiceDraft(PeerEngine e, String draftId) throws IOException {
    synchronized (e) {
      PeerEngine.VoiceDraft d = e.voiceDrafts.get(draftId);
      if (d == null) throw new IOException("Unknown voice draft.");
      String beforeState = d.state; long beforeUpdated = d.updatedAt, beforeBytes = d.byteSize, beforeDuration = d.durationMs;
      File path = voiceDraftPath(e, draftId);
      byte[] pcm;
      try { pcm = readVoiceDraftPlaintext(e, path); }
      catch (IOException ex) { markInvalidBestEffort(e, d); throw ex; }
      byte[] wav;
      try { wav = VoiceWav.buildCanonical(pcm); }
      catch (IOException ex) { markInvalidBestEffort(e, d); throw ex; }
      VoiceWavValidation validation = VoiceWav.validate(wav);
      if (!validation.pass) {
        d.state = PeerEngine.VoiceDraft.INVALID; d.updatedAt = System.currentTimeMillis();
        try { e.save(); } catch (IOException ex) { restore(d, beforeState, beforeUpdated, beforeBytes, beforeDuration); }
        return validation;
      }
      writeVoiceDraftPlaintextAtomic(e, path, wav);
      d.state = PeerEngine.VoiceDraft.FINALIZED; d.byteSize = wav.length; d.durationMs = validation.info.durationMs; d.updatedAt = System.currentTimeMillis();
      try { e.save(); } catch (IOException ex) { restore(d, beforeState, beforeUpdated, beforeBytes, beforeDuration); throw ex; }
      return validation;
    }
  }
  private static void markInvalidBestEffort(PeerEngine e, PeerEngine.VoiceDraft d) {
    d.state = PeerEngine.VoiceDraft.INVALID; d.updatedAt = System.currentTimeMillis();
    try { e.save(); } catch (IOException ignored) {}
  }
  private static void restore(PeerEngine.VoiceDraft d, String state, long updatedAt, long byteSize, long durationMs) {
    d.state = state; d.updatedAt = updatedAt; d.byteSize = byteSize; d.durationMs = durationMs;
  }

  // Explicit path for a terminal recording failure (PCM DeviceFailure, or the odd-byte-at-end
  // failure from VoicePcmAssembler.end()) that must never be finalized: diagnose the entry as
  // Invalid immediately rather than leaving it stuck in Recording state forever.
  static void invalidateVoiceDraft(PeerEngine e, String draftId) {
    synchronized (e) {
      PeerEngine.VoiceDraft d = e.voiceDrafts.get(draftId);
      if (d == null) return;
      d.state = PeerEngine.VoiceDraft.INVALID; d.updatedAt = System.currentTimeMillis();
      try { e.save(); } catch (IOException ignored) {}
    }
  }

  // Preview/Delete-only drafts, drafts the user chooses to discard, and step 10's post-Send
  // cleanup all go through here. Best-effort file delete: the registry row is the durable source
  // of truth, so a leftover encrypted file with no registry row is harmless orphan data, never a
  // recoverable-but-untracked draft.
  static void deleteVoiceDraft(PeerEngine e, String draftId) throws IOException {
    synchronized (e) {
      PeerEngine.VoiceDraft removed = e.voiceDrafts.remove(draftId);
      if (removed == null) return;
      try { e.save(); } catch (IOException ex) { e.voiceDrafts.put(draftId, removed); throw ex; }
      try { voiceDraftPath(e, draftId).delete(); } catch (IOException ignored) {}
    }
  }

  // Associates a draft with an in-flight Send so a crash between "queued the message" and
  // "removed the registry entry" (step 10) can be told apart from an ordinary still-recording
  // draft during reconciliation.
  private static void markVoiceDraftSendTransaction(PeerEngine e, String draftId, String transactionId) {
    synchronized (e) {
      PeerEngine.VoiceDraft d = e.voiceDrafts.get(draftId);
      if (d == null) return;
      d.sendTransactionId = transactionId; d.updatedAt = System.currentTimeMillis();
      try { e.save(); } catch (IOException ignored) {}
    }
  }

  // Startup reconciliation: every registry entry must resolve to exactly one of the three allowed
  // crash outcomes (contract.md "Crash-boundary reconciliation"). A Recording entry left open by
  // a crash can never safely resume (no writer survives a process exit), so it is diagnosed
  // Invalid rather than silently treated as finalized or quietly dropped. A Finalized entry is
  // re-validated against its actual bytes on disk, since a crash between step 5 (validate) and
  // step 6 (mark recoverable) is exactly the boundary this guards.
  static void reconcile(PeerEngine e) throws IOException {
    synchronized (e) {
      boolean changed = false;
      for (PeerEngine.VoiceDraft d : e.voiceDrafts.values()) {
        if (d.state.equals(PeerEngine.VoiceDraft.RECORDING)) { d.state = PeerEngine.VoiceDraft.INVALID; d.updatedAt = System.currentTimeMillis(); changed = true; continue; }
        if (!d.state.equals(PeerEngine.VoiceDraft.FINALIZED)) continue;
        try {
          byte[] wav = readVoiceDraftPlaintext(e, voiceDraftPath(e, d.id));
          VoiceWavValidation validation = VoiceWav.validate(wav);
          if (!validation.pass) { d.state = PeerEngine.VoiceDraft.INVALID; d.updatedAt = System.currentTimeMillis(); changed = true; }
        } catch (Exception ex) { d.state = PeerEngine.VoiceDraft.INVALID; d.updatedAt = System.currentTimeMillis(); changed = true; }
      }
      if (changed) e.save();
    }
  }

  // Ten-step durable write order, steps 7-10: allocate the message id and final marked filename
  // together (the marker embeds this exact id -- VoiceMarker.fileName), import the finalized WAV
  // into the encrypted Normal store, durably save the queued message, then remove the registry
  // entry (best-effort plaintext delete) only after both are durable. Voice Messages always use
  // Normal storage (contract.md Decision 2) -- never Fast, never an original-file reference --
  // matching how queueContentStream is called below.
  static void sendVoiceDraft(PeerEngine e, String draftId, String caption) throws IOException {
    String conversation; byte[] wav;
    synchronized (e) {
      PeerEngine.VoiceDraft d = e.voiceDrafts.get(draftId);
      if (d == null) throw new IOException("Unknown voice draft.");
      if (!d.state.equals(PeerEngine.VoiceDraft.FINALIZED)) throw new IOException("This recording is not ready to send.");
      boolean sendable = d.isGroup ? e.groups.containsKey(d.conversationId) : e.peers.containsKey(d.conversationId);
      if (!sendable) throw new IOException("This conversation can no longer receive messages; the recording can only be previewed or deleted.");
      conversation = d.conversationId;
      wav = readVoiceDraftPlaintext(e, voiceDraftPath(e, draftId));
    }
    String id = UUID.randomUUID().toString();
    markVoiceDraftSendTransaction(e, draftId, id);
    String markedName = VoiceMarker.fileName(id);
    e.queueContentStream(conversation, caption == null ? "" : caption, markedName, new ByteArrayInputStream(wav), wav.length, null, true, id);
    deleteVoiceDraft(e, draftId);
  }

  // A08: lets the UI preview a finalized (or invalid, for diagnostics) draft's own decrypted WAV
  // bytes without exposing the private on-disk encoding.
  static byte[] readVoiceDraftWav(PeerEngine e, String draftId) throws IOException {
    synchronized (e) {
      if (!e.voiceDrafts.containsKey(draftId)) throw new IOException("Unknown voice draft.");
      return readVoiceDraftPlaintext(e, voiceDraftPath(e, draftId));
    }
  }

  // --- Encrypted plaintext read/write, matching AttachmentStore.java's construction ---

  static byte[] readVoiceDraftPlaintext(PeerEngine e, File path) throws IOException {
    try (FileInputStream f = new FileInputStream(path)) {
      byte[] magic = new byte[PeerEngine.VOICE_DRAFT_MAGIC.length]; AttachmentStore.readFully(f, magic);
      if (!Arrays.equals(magic, PeerEngine.VOICE_DRAFT_MAGIC)) throw new IOException("Invalid voice draft file.");
      byte[] lenBuf = new byte[4]; AttachmentStore.readFully(f, lenBuf); int headerLen = ByteBuffer.wrap(lenBuf).getInt();
      if (headerLen < 1 || headerLen > 65536) throw new IOException("Invalid voice draft header.");
      byte[] header = new byte[headerLen]; AttachmentStore.readFully(f, header);
      byte[] raw; try { raw = e.protector.unprotect(header); } catch (Exception ex) { throw new IOException(ex); }
      if (raw.length != 48) throw new IOException("Invalid voice draft key.");
      byte[] key = Arrays.copyOfRange(raw, 0, 32), iv = Arrays.copyOfRange(raw, 32, 48);
      Cipher cipher;
      try { cipher = Cipher.getInstance("AES/CBC/PKCS5Padding"); cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv)); }
      catch (GeneralSecurityException ex) { throw new IOException(ex); }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (CipherInputStream cis = new CipherInputStream(f, cipher)) {
        byte[] buf = new byte[64 * 1024]; int n;
        while ((n = cis.read(buf)) > 0) out.write(buf, 0, n);
      }
      return out.toByteArray();
    }
  }

  static void writeVoiceDraftPlaintextAtomic(PeerEngine e, File path, byte[] plaintext) throws IOException {
    File tmp = new File(path + "." + UUID.randomUUID() + ".tmp");
    try {
      byte[] key = new byte[32], iv = new byte[16]; SecureRandom random = new SecureRandom(); random.nextBytes(key); random.nextBytes(iv);
      byte[] combined = new byte[48]; System.arraycopy(key, 0, combined, 0, 32); System.arraycopy(iv, 0, combined, 32, 16);
      byte[] header; try { header = e.protector.protect(combined); } catch (Exception ex) { throw new IOException(ex); }
      try (FileOutputStream out = new FileOutputStream(tmp)) {
        out.write(PeerEngine.VOICE_DRAFT_MAGIC); out.write(ByteBuffer.allocate(4).putInt(header.length).array()); out.write(header);
        Cipher cipher; try { cipher = Cipher.getInstance("AES/CBC/PKCS5Padding"); cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv)); }
        catch (GeneralSecurityException ex) { throw new IOException(ex); }
        byte[] encrypted; try { encrypted = cipher.doFinal(plaintext); } catch (GeneralSecurityException ex) { throw new IOException(ex); }
        out.write(encrypted);
        out.getFD().sync();
      }
      PeerEngine.atomicReplace(tmp, path);
    } catch (IOException ex) { tmp.delete(); throw ex; }
  }
}

// An open, in-progress recording session for one draft. One instance per openVoiceDraftWriter
// call; the caller (A03/A04) feeds it VoicePcmAssembler frame payloads as they are produced and
// calls finish() on normal Stop or abort() on any interruption that must not finalize.
final class VoiceDraftWriter {
  private final PeerEngine engine;
  private final String draftId;
  private final File path, tmpPath;
  private FileOutputStream fileOut;
  private Cipher cipher;
  private long written;
  private boolean closed;

  VoiceDraftWriter(PeerEngine engine, String draftId, File path) throws IOException {
    this.engine = engine; this.draftId = draftId; this.path = path;
    tmpPath = new File(path + "." + UUID.randomUUID() + ".tmp");
    byte[] key = new byte[32], iv = new byte[16]; SecureRandom random = new SecureRandom(); random.nextBytes(key); random.nextBytes(iv);
    byte[] combined = new byte[48]; System.arraycopy(key, 0, combined, 0, 32); System.arraycopy(iv, 0, combined, 32, 16);
    byte[] header;
    try { header = engine.protector.protect(combined); } catch (Exception ex) { throw new IOException(ex); }
    fileOut = new FileOutputStream(tmpPath);
    try {
      fileOut.write(PeerEngine.VOICE_DRAFT_MAGIC); fileOut.write(ByteBuffer.allocate(4).putInt(header.length).array()); fileOut.write(header);
      cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
    } catch (GeneralSecurityException | IOException ex) {
      try { fileOut.close(); } catch (IOException ignored) {}
      tmpPath.delete();
      throw ex instanceof IOException ? (IOException) ex : new IOException(ex);
    }
  }

  void writeFrame(byte[] pcm) throws IOException {
    if (closed) throw new IOException("Draft writer already closed.");
    byte[] encrypted = cipher.update(pcm);
    if (encrypted != null && encrypted.length > 0) fileOut.write(encrypted);
    written += pcm.length;
    VoiceDrafts.recordVoiceDraftProgress(engine, draftId, written);
  }

  // Recording stopped normally (explicit Stop or a safe lifecycle-triggered stop): flush the
  // encrypted file to disk and publish it atomically. Finalization (WAV build + validation) is a
  // separate step -- see VoiceDrafts.finalizeVoiceDraft -- matching steps 3 and 4 of the ten-step
  // order.
  void finish() throws IOException {
    if (closed) throw new IOException("Draft writer already closed.");
    closed = true;
    try {
      byte[] finalBlock; try { finalBlock = cipher.doFinal(); } catch (GeneralSecurityException ex) { throw new IOException(ex); }
      if (finalBlock != null && finalBlock.length > 0) fileOut.write(finalBlock);
      fileOut.getFD().sync();
    } finally { fileOut.close(); }
    PeerEngine.atomicReplace(tmpPath, path);
    VoiceDrafts.closeVoiceDraftWriter(engine, draftId);
  }

  // A terminal PCM failure (device loss, odd-byte-at-end) or an unrecoverable local error:
  // discard the partial file. The registry entry is separately marked Invalid by the caller via
  // VoiceDrafts.invalidateVoiceDraft, since only the caller knows why the writer aborted. Safe to
  // call more than once or after finish() -- unlike finish(), never throws.
  void abort() {
    if (closed) return;
    closed = true;
    try { fileOut.close(); } catch (IOException ignored) {}
    tmpPath.delete();
    VoiceDrafts.closeVoiceDraftWriter(engine, draftId);
  }
}
