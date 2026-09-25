"""Delete conversation forgets a contact/leaves a group; Delete app data wipes everything but identity/profile."""
import pathlib, base64, hashlib
# Reuse harness helpers, without running the legacy suite a second time.
exec(pathlib.Path(__file__).with_name('integration.py').read_text(encoding='utf-8').split('# Seed a real 0.2 snapshot')[0])

def conv(p,id): return p.command('CONV\t'+id)
def groups(p): return [r[1] for r in p.state() if r[0]=='G']
def send_file(p,id,name,data): return p.command('FILE\t'+id+'\t'+b64(name)+'\t'+base64.b64encode(data).decode())

try:
    a=Peer('java','Phone-A','127.0.0.2');b=Peer('cs','Windows-B','127.0.0.3');c=Peer('java','Phone-C','127.0.0.4')
    aid,bid,cid=a.id,b.id,c.id
    pair(a,b);pair(a,c);pair(b,c)

    # 1) Deleting a direct conversation forgets the contact, not just its history.
    a.send(bid,'Before delete');send_file(a,bid,'note.txt',b'delete-test-attachment')
    wait_for(lambda:has(b,'Before delete','Received'),'Message delivered before delete')
    a.command('DELETECONV\t'+bid)
    assert not any(r[0]=='P' and r[1]==bid for r in a.state()) and conv(a,bid)==[]
    print('PASS: deleted conversation drops the contact, its messages and its attachments',flush=True)

    announce(b,a);announce(a,b)
    wait_for(lambda:any(r[0]=='P' and r[1]==bid for r in a.state()),'Deleted contact reappears as a fresh, unverified peer')
    b.send(aid,'After delete, before re-verify')
    time.sleep(3)
    assert has(b,'After delete, before re-verify','Queued') and not has(a,'After delete, before re-verify','Received')
    print('PASS: a forgotten contact must be re-verified before messages are accepted again',flush=True)
    pair(a,b)
    wait_for(lambda:has(a,'After delete, before re-verify','Received'),'Re-verified contact exchanges messages again')

    # 2) Deleting a group conversation leaves it, without disturbing other members.
    gid=a.command('GROUP\t'+b64('Delete test group')+'\t'+bid+','+cid)[0][1]
    wait_for(lambda:gid in groups(b) and gid in groups(c),'Group invitation reaches both members')
    a.command('DELETECONV\t'+gid)
    assert gid not in groups(a) and conv(a,gid)==[]
    b.send(gid,'Sent after a left the group')
    wait_for(lambda:has(c,'Sent after a left the group','Received'),'Remaining members still exchange group messages')
    time.sleep(2)
    assert gid not in groups(a) and conv(a,gid)==[]
    print('PASS: deleting a group conversation leaves it locally',flush=True)

    # 3) Delete app data wipes every conversation/contact/group, but keeps identity, name and avatar.
    a.command('SETAVATAR\t'+base64.b64encode(hashlib.sha256(b'avatar-bytes').digest()).decode())
    name_before=a.command('NAME')[0][1]
    avatar_before=a.command('OWNAVATAR')[0]
    a.command('DELETEALL')
    assert a.state()==[] and groups(a)==[]
    a.stop();a=Peer('java','Phone-A','127.0.0.2')
    assert a.id==aid,'Identity must survive Delete app data'
    assert a.command('NAME')[0][1]==name_before,'Profile name must survive Delete app data'
    assert a.command('OWNAVATAR')[0]==avatar_before,'Profile picture must survive Delete app data'
    assert a.command('USAGE')[0][1]=='0','Daily upload usage resets with Delete app data'
    assert a.state()==[] and groups(a)==[]
    print('PASS: Delete app data wipes conversations/contacts/groups but keeps identity, name, avatar and resets usage',flush=True)
finally:
    for p in processes:
        if p.poll() is None:p.kill()
