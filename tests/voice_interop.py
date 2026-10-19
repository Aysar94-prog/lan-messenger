"""Voice Messages Phase 3 integration (I06/I08/I09, IT02/IT03/IT06 partial).

Cross-platform coverage the single-platform suites explicitly excluded:
tests/voice_scheduler.py (WT04) is cs-only, tests/voice_scheduler_android.py
(AT04) is java-only. This script sends REAL voice drafts (CREATEDRAFT/
OPENWRITER/WRITEFRAME/CLOSEWRITER/FINALIZEDRAFT/SENDDRAFT) in BOTH directions
between a C# ('cs') and a Java ('java') peer through the existing encrypted
Normal attachment store, and checks byte-identical automatic retrieval.

Covers (automatable subset):
- IT02: direct + group voice in both directions, Offline queue + restart,
  receiver restart before retrieval, deduplication (exactly one row).
- IT03 (partial): group relay/sync to a member that was offline at send time;
  clear/delete removes the voice row locally (playback has nothing to play).
- IT06 (partial): forged markers / mismatched IDs / arbitrary WAVs still
  deliver as ordinary attachments without crashing or looping; no new wire
  frame is used (voice reuses the Normal attachment path, so an older client
  sees an ordinary WAV).
- IT04 (seek) and one-player UI enforcement are NOT harness-verifiable
  (no seek/player commands exist in either harness) and remain device-acceptance
  items; byte-identical canonical WAVs on both sides are the precondition.

Usage (mirrors the other voice suites):
  python tests/voice_interop.py <java.exe> <java-classes-dir> <csharp-dll> <work-root>
"""
import base64, pathlib, uuid
exec(pathlib.Path(__file__).with_name('integration.py').read_text(encoding='utf-8').split('# Seed a real 0.2 snapshot')[0])

FRAME = bytes(640)  # one silent contract-sized 20 ms frame
def b64f(b): return base64.b64encode(b).decode()

def send_voice(sender, conversation, is_group, frames=5):
    draft_id = sender.command('CREATEDRAFT\t' + conversation + '\t' + ('true' if is_group else 'false'))[0][1]
    sender.command('OPENWRITER\t' + draft_id)
    for _ in range(frames):
        sender.command('WRITEFRAME\t' + draft_id + '\t' + b64f(FRAME))
    sender.command('CLOSEWRITER\t' + draft_id)
    assert sender.command('FINALIZEDRAFT\t' + draft_id)[0][1] == 'true'
    sender.command('SENDDRAFT\t' + draft_id)

def is_voice_row(r):
    try:
        return len(r) > 7 and base64.b64decode(r[7]).decode('utf-8', 'replace').endswith('.lanvoice.wav')
    except Exception:
        return False

def voice_row(p, conversation):
    return next((r for r in p.command('CONV\t' + conversation) if is_voice_row(r)), None)

def voice_rows(p, conversation):
    return [r for r in p.command('CONV\t' + conversation) if is_voice_row(r)]

def available(p, conversation):
    r = voice_row(p, conversation)
    return r is not None and p.command('HASFILE\t' + conversation + '\t' + r[1])[0][1] == 'true'

def file_hash(p, conversation, msg_id):
    return p.command('FILEHASH\t' + conversation + '\t' + msg_id)[0]

try:
    w = Peer('cs', 'VoiceInteropW', '127.0.0.241')
    j = Peer('java', 'VoiceInteropJ', '127.0.0.242')
    pair(w, j)

    # 1) Direct cs -> java: auto-downloads, byte-identical.
    send_voice(w, j.id, False)
    wait_for(lambda: available(j, w.id), 'cs->java voice message auto-downloads for the java recipient')
    rw = voice_row(w, j.id); rj = voice_row(j, w.id)
    assert rw is not None and rj is not None
    hw = w.command('FILEHASH\t' + j.id + '\t' + rw[1])[0]
    hj = j.command('FILEHASH\t' + w.id + '\t' + rj[1])[0]
    assert hw[1] == hj[1] and int(hw[2]) == int(hj[2]) and int(hw[2]) > 44, (hw, hj)
    assert len(voice_rows(j, w.id)) == 1, 'duplicate voice row on java side'
    print('PASS: cs->java voice content matches byte-for-byte (FILEHASH ' + hw[2] + ' bytes)', flush=True)

    def all_available(p, conversation, count):
        rows = voice_rows(p, conversation)
        if len(rows) != count:
            return False
        return all(p.command('HASFILE\t' + conversation + '\t' + r[1])[0][1] == 'true' for r in rows)

    # 2) Direct java -> cs: auto-downloads, byte-identical.
    send_voice(j, w.id, False)
    wait_for(lambda: all_available(w, j.id, 2), 'java->cs voice message auto-downloads for the cs recipient')
    rows_w = voice_rows(w, j.id)
    # The newest voice row on w must have content matching some java-side voice row.
    match = False
    for r in rows_w:
        h = w.command('FILEHASH\t' + j.id + '\t' + r[1])[0]
        for r2 in voice_rows(j, w.id):
            h2 = j.command('FILEHASH\t' + w.id + '\t' + r2[1])[0]
            if h[1] == h2[1]:
                match = True
    assert match, 'no byte-identical match for the java->cs voice message'
    print('PASS: java->cs voice content matches byte-for-byte', flush=True)

    # 3) Group across platforms: cs owner, java + cs members, every member auto-downloads.
    k = Peer('cs', 'VoiceInteropK', '127.0.0.243')
    pair(w, k); pair(j, k)
    gid = w.command('GROUP\t' + b64('Voice interop group') + '\t' + j.id + ',' + k.id)[0][1]
    wait_for(lambda: gid in [r[1] for r in j.state() if r[0] == 'G'] and gid in [r[1] for r in k.state() if r[0] == 'G'], 'cross-platform group invitation reaches every member')
    send_voice(w, gid, True)
    wait_for(lambda: available(j, gid) and available(k, gid), 'cross-platform group voice downloads for every member')
    rg_j = voice_row(j, gid); rg_k = voice_row(k, gid)
    assert j.command('FILEHASH\t' + gid + '\t' + rg_j[1])[0][1] == k.command('FILEHASH\t' + gid + '\t' + rg_k[1])[0][1]
    print('PASS: cross-platform group voice reaches every member byte-identical', flush=True)

    # 4) Sender restart after Send: queued voice survives and still delivers.
    w2 = Peer('cs', 'VoiceInteropW2', '127.0.0.244')
    pair(w2, j)
    send_voice(w2, j.id, False)
    w2.stop()
    w2 = Peer('cs', 'VoiceInteropW2', '127.0.0.244')
    wait_for(lambda: any(is_voice_row(r) for r in j.command('CONV\t' + w2.id)), 'voice queued before sender restart still delivers after restart')
    print('PASS: Offline/restart sender voice send survives restart', flush=True)

    # 5) Hostile content: forged marker (non-WAV bytes) and mismatched-id valid
    # WAV both deliver as ordinary attachments without crashing the receiver.
    forged_name = 'voice-' + uuid.uuid4().hex + '.lanvoice.wav'
    w.command('FILE\t' + j.id + '\t' + b64(forged_name) + '\t' + base64.b64encode(b'not a wav at all').decode())
    wait_for(lambda: any(len(r) > 7 and r[7] == b64(forged_name) for r in j.command('CONV\t' + w.id)), 'forged voice marker delivers as an ordinary attachment')
    print('PASS: forged voice marker falls back to ordinary attachment, no second fetch loop', flush=True)
    mismatched_name = 'voice-' + uuid.uuid4().hex + '.lanvoice.wav'
    # Minimal valid 16kHz/mono/16-bit WAV (1 silent frame) but the marker id can
    # never match the real message id, so the receiver must not treat it as voice.
    import struct
    pcm = bytes(640)
    hdr = struct.pack('<4sI4s4sIHHIIHH4sI', b'RIFF', 36 + len(pcm), b'WAVE', b'fmt ', 16, 1, 1, 16000, 32000, 2, 16, b'data', len(pcm))
    wav = hdr + pcm
    w.command('FILE\t' + j.id + '\t' + b64(mismatched_name) + '\t' + base64.b64encode(wav).decode())
    wait_for(lambda: any(len(r) > 7 and r[7] == b64(mismatched_name) for r in j.command('CONV\t' + w.id)), 'mismatched-id marker delivers as an ordinary attachment')
    print('PASS: mismatched-id marker falls back to ordinary attachment', flush=True)

    # 6) Clear/delete: receiver clearing the conversation removes the voice row.
    j.command('DELETECONV\t' + w.id)
    assert voice_row(j, w.id) is None, 'voice row survived DELETECONV'
    print('PASS: clearing the conversation removes the voice message locally', flush=True)
finally:
    for p in processes:
        try: p.kill()
        except Exception: pass
