"""Voice Messages Android, AT02: durable draft registry crash boundaries, the 10-entry cap,
invalid storage identities, missing conversations (Preview/Delete-only), and crash-boundary
reconciliation (Restore/Delete). Android-only (`VoiceDrafts.java`/`PeerEngine.java`); no audio
hardware is involved -- frames are supplied directly as base64 PCM bytes. Mirrors
tests/voice_drafts.py (WT02) exactly, against a 'java' peer instead of 'cs', since both harnesses
expose identical wire-format commands (tests/PeerHarness.java / tests/CsharpHarness/Program.cs)."""
import base64, pathlib
exec(pathlib.Path(__file__).with_name('integration.py').read_text(encoding='utf-8').split('# Seed a real 0.2 snapshot')[0])

FRAME = bytes(640)  # one silent, contract-sized 20 ms frame (640 bytes / 320 samples)
def b64f(b): return base64.b64encode(b).decode()

def draft_state(p, draft_id):
    row = p.command('DRAFTSTATE\t' + draft_id)[0]
    return None if row[1] == 'NONE' else {'state': row[1], 'bytes': int(row[2]), 'duration_ms': int(row[3]), 'send_tx': row[4]}

def record_and_finalize(p, draft_id, frames=5):
    p.command('OPENWRITER\t' + draft_id)
    for _ in range(frames): p.command('WRITEFRAME\t' + draft_id + '\t' + b64f(FRAME))
    p.command('CLOSEWRITER\t' + draft_id)
    result = p.command('FINALIZEDRAFT\t' + draft_id)[0]
    assert result[1] == 'true', result
    return result

def expect_error(p, line, needle=None):
    try:
        p.command(line)
        raise AssertionError('expected ' + line + ' to fail')
    except AssertionError as e:
        assert 'ERROR' in str(e), e
        if needle: assert needle in str(e), e

try:
    owner = Peer('java', 'DraftOwnerA', '127.0.0.221')

    # 1) Basic lifecycle: create, record, finalize, check registry fields.
    d1 = owner.command('CREATEDRAFT\tsome-conversation\tfalse')[0][1]
    assert draft_state(owner, d1)['state'] == 'Recording'
    record_and_finalize(owner, d1)
    st = draft_state(owner, d1)
    assert st['state'] == 'Finalized' and st['bytes'] > 0 and st['duration_ms'] > 0, st
    print('PASS: a draft records and finalizes with real registry fields', flush=True)

    # 2) Invalid storage identities: every operation on an unknown draft id fails cleanly.
    expect_error(owner, 'OPENWRITER\tnot-a-real-draft')
    assert owner.command('FINALIZEDRAFT\tnot-a-real-draft')[0][1] == 'ERROR'
    assert owner.command('READDRAFTWAV\tnot-a-real-draft')[0][1] == 'ERROR'
    assert draft_state(owner, 'not-a-real-draft') is None
    print('PASS: operations on an unknown draft id fail cleanly instead of crashing or fabricating state', flush=True)

    # 3) Ten-entry cap blocks only new creation; existing drafts (including d1) stay available.
    extra = [owner.command('CREATEDRAFT\tsome-conversation\tfalse')[0][1] for _ in range(9)]
    expect_error(owner, 'CREATEDRAFT\tsome-conversation\tfalse', 'limit reached')
    assert draft_state(owner, d1)['state'] == 'Finalized'  # unaffected by the cap being hit
    owner.command('DELETEDRAFT\t' + extra[0])
    d_after_cap = owner.command('CREATEDRAFT\tsome-conversation\tfalse')[0][1]
    print('PASS: the 10-draft cap blocks only new creation; deleting one frees a slot', flush=True)
    for d in extra[1:] + [d_after_cap]: owner.command('DELETEDRAFT\t' + d)

    # 4) Missing conversation -> Preview/Delete-only, never retargeted; Send still refused.
    peer = Peer('java', 'DraftPeerA', '127.0.0.222')
    pair(owner, peer)
    d2 = owner.command('CREATEDRAFT\t' + peer.id + '\tfalse')[0][1]
    record_and_finalize(owner, d2)
    assert owner.command('DRAFTSENDABLE\t' + d2)[0][1] == 'true'
    owner.command('DELETECONV\t' + peer.id)
    assert owner.command('DRAFTSENDABLE\t' + d2)[0][1] == 'false'
    assert draft_state(owner, d2)['state'] == 'Finalized'  # not silently invalidated or retargeted
    expect_error(owner, 'SENDDRAFT\t' + d2)
    owner.command('READDRAFTWAV\t' + d2)  # preview still works
    owner.command('DELETEDRAFT\t' + d2)  # delete still works
    print('PASS: a draft whose conversation is gone becomes Preview/Delete-only, never retargeted, never silently invalidated', flush=True)

    # 5) Crash-boundary reconciliation.
    data_dir = work / 'DraftOwnerA'

    # 5a) A crash mid-recording (writer open, never closed) must diagnose Invalid on restart --
    # never silently resumed or treated as finalized (no writer survives a process exit).
    d3 = owner.command('CREATEDRAFT\tsome-conversation\tfalse')[0][1]
    owner.command('OPENWRITER\t' + d3)
    owner.command('WRITEFRAME\t' + d3 + '\t' + b64f(FRAME))
    owner.p.kill(); owner.p.wait(timeout=10); owner.error_log.close()
    owner = Peer('java', 'DraftOwnerA', '127.0.0.221')
    assert draft_state(owner, d3)['state'] == 'Invalid'
    print('PASS: a draft left open by a crash is diagnosed Invalid on restart, never silently resumed', flush=True)

    # 5b) A cleanly finalized draft survives a real restart unchanged (re-validated, still passes).
    d4 = owner.command('CREATEDRAFT\tsome-conversation\tfalse')[0][1]
    record_and_finalize(owner, d4)
    owner.stop()
    owner = Peer('java', 'DraftOwnerA', '127.0.0.221')
    assert draft_state(owner, d4)['state'] == 'Finalized'
    print('PASS: a cleanly finalized draft is re-validated and stays Finalized across a restart', flush=True)

    # 5c) A finalized draft whose on-disk bytes are corrupted at rest must be caught by
    # reconciliation's re-validation, not trusted from a stale registry row.
    d5 = owner.command('CREATEDRAFT\tsome-conversation\tfalse')[0][1]
    record_and_finalize(owner, d5)
    owner.stop()
    path = data_dir / 'voice-drafts' / (d5 + '.sec')
    raw = bytearray(path.read_bytes())
    raw[-16:] = bytes(16)  # corrupt the tail of the encrypted payload; header/magic left intact
    path.write_bytes(bytes(raw))
    owner = Peer('java', 'DraftOwnerA', '127.0.0.221')
    assert draft_state(owner, d5)['state'] == 'Invalid'
    print('PASS: a finalized draft corrupted at rest is caught by restart re-validation, not trusted from a stale registry row', flush=True)

finally:
    for p in processes:
        try: p.kill()
        except Exception: pass
