"""Voice Messages Windows, WT04 (partial): a real voice message, sent through the actual
CreateVoiceDraft/SendVoiceDraft path (not a synthetic FILE offer), downloads automatically for
a direct recipient and every group member, same as an automatic image -- proving
IsVoiceCandidate/QueueAutomaticMedia's wiring end to end, not just in isolation (WT01) or on
the draft registry alone (WT02). Windows-only (CREATEDRAFT/SENDDRAFT are 'cs'-only commands);
cross-platform/interoperability is out of scope for this phase (see PLAN-VOICE-MESSAGES-
WINDOWS.md)."""
import base64, pathlib
exec(pathlib.Path(__file__).with_name('integration.py').read_text(encoding='utf-8').split('# Seed a real 0.2 snapshot')[0])

FRAME = bytes(640)
def b64f(b): return base64.b64encode(b).decode()

def send_voice(sender, conversation, is_group, frames=5):
    draft_id = sender.command('CREATEDRAFT\t' + conversation + '\t' + ('true' if is_group else 'false'))[0][1]
    sender.command('OPENWRITER\t' + draft_id)
    for _ in range(frames): sender.command('WRITEFRAME\t' + draft_id + '\t' + b64f(FRAME))
    sender.command('CLOSEWRITER\t' + draft_id)
    assert sender.command('FINALIZEDRAFT\t' + draft_id)[0][1] == 'true'
    sender.command('SENDDRAFT\t' + draft_id)

def voice_row(p, conversation):
    def is_voice(r): return len(r) > 7 and base64.b64decode(r[7]).decode('utf-8', 'replace').endswith('.lanvoice.wav')
    return next((r for r in p.command('CONV\t' + conversation) if is_voice(r)), None)
def available(p, conversation):
    r = voice_row(p, conversation)
    return r is not None and p.command('HASFILE\t' + conversation + '\t' + r[1])[0][1] == 'true'

try:
    a = Peer('cs', 'VoiceSchedA', '127.0.0.211'); b = Peer('cs', 'VoiceSchedB', '127.0.0.212'); c = Peer('cs', 'VoiceSchedC', '127.0.0.213')
    pair(a, b); pair(a, c)

    # 1) Direct: a real voice message auto-downloads for the recipient, with matching content.
    send_voice(a, b.id, False)
    wait_for(lambda: available(b, a.id), 'A real voice message downloads automatically for a direct recipient')
    ra = voice_row(a, b.id); rb = voice_row(b, a.id)
    assert a.command('FILEHASH\t' + b.id + '\t' + ra[1])[0][1] == b.command('FILEHASH\t' + a.id + '\t' + rb[1])[0][1]
    print('PASS: the downloaded voice content matches byte-for-byte (FILEHASH)', flush=True)

    # 2) Group: every member auto-downloads it, same as an automatic image.
    gid = a.command('GROUP\t' + b64('Voice scheduler group') + '\t' + b.id + ',' + c.id)[0][1]
    wait_for(lambda: gid in [r[1] for r in b.state() if r[0] == 'G'] and gid in [r[1] for r in c.state() if r[0] == 'G'], 'Group invitation reaches both members')
    send_voice(a, gid, True)
    wait_for(lambda: available(b, gid) and available(c, gid), 'A group voice message downloads automatically for every member')
    print('PASS: group voice-message automatic download reaches every member', flush=True)

    # 3) Mixed automatic pool: a voice message and an image sent together both eventually
    # download for the same recipient (neither type starves the other in this small batch).
    png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jk1sAAAAASUVORK5CYII='
    a.command('FILE\t' + b.id + '\t' + b64('mixed.png') + '\t' + png)
    send_voice(a, b.id, False)
    def image_row(p, conv): return next((r for r in p.command('CONV\t' + conv) if len(r) > 7 and r[7] == b64('mixed.png')), None)
    wait_for(lambda: available(b, a.id) and image_row(b, a.id) and b.command('HASFILE\t' + a.id + '\t' + image_row(b, a.id)[1])[0][1] == 'true',
             'A voice message and an image sent together both download automatically (neither starves the other)')
finally:
    for p in processes:
        try: p.kill()
        except Exception: pass
