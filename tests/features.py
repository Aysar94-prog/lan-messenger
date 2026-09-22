"""0.4 interoperability: real engines, isolated loopback processes and data."""
import pathlib, hashlib, os
# Reuse harness helpers, without running the legacy suite a second time.
exec(pathlib.Path(__file__).with_name('integration.py').read_text(encoding='utf-8').split('# Seed a real 0.2 snapshot')[0])

def conv(p,id): return p.command('CONV\t'+id)
def file_message(p,id,name): return next((r for r in conv(p,id) if len(r)>9 and r[7]==b64(name)),None)
def send_file(p,id,name,data): return p.command('FILE\t'+id+'\t'+b64(name)+'\t'+base64.b64encode(data).decode())
def groups(p): return [r[1] for r in p.state() if r[0]=='G']
def raw(a,b,recipient,text,group='',name='',data=b'',size=None,digest=None,message_id=None):
    message_id=message_id or str(uuid.uuid4())
    line='RAW\t'+b.ip+'\t44972\t'+a.id+'\t'+recipient+'\t'+message_id+'\t'+b64(text)+'\t'+group+'\t'+b64(name)+'\t'+str(len(data) if size is None else size)+'\t'+(hashlib.sha256(data).hexdigest() if digest is None and name else (digest or ''))
    if data:line+='\t'+base64.b64encode(data).decode()
    return a.command(line)

def verify_file(p,conversation,name,data):
    record=file_message(p,conversation,name);assert record is not None
    result=p.command('FILEHASH\t'+conversation+'\t'+record[1])[0]
    assert result[1:]==[hashlib.sha256(data).hexdigest(),str(len(data))]
    return record

try:
    a=Peer('java','Phone-A','127.0.0.2');b=Peer('cs','Windows-B','127.0.0.3');c=Peer('java','Phone-C','127.0.0.4')
    aid,bid,cid=a.id,b.id,c.id
    pair(a,b);pair(b,c);pair(a,c)
    gid=b.command('GROUP\t'+b64('Team مجموعة')+'\t'+aid+','+cid)[0][1]
    wait_for(lambda:gid in groups(a) and gid in groups(c),'Windows-created group invitation reaches both Java peers')
    b.send(gid,'Welcome to the group 👋')
    wait_for(lambda:has(a,'Welcome to the group 👋','Received') and has(c,'Welcome to the group 👋','Received') and has(b,'Welcome to the group 👋','Delivered'),'Group fanout reaches every member with aggregate acknowledgement')
    a.send(gid,'Reply from phone')
    wait_for(lambda:has(b,'Reply from phone','Received') and has(c,'Reply from phone','Received'),'Non-owner member can reply directly to all members')
    assert len([r for r in conv(b,gid) if r[5]==b64('Welcome to the group 👋')])==1
    png=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jk1sAAAAASUVORK5CYII=')
    data=bytes(range(256))*1024
    send_file(a,bid,'photo.png',png)
    wait_for(lambda:file_message(b,aid,'photo.png') is not None,'Java image reaches Windows')
    verify_file(b,aid,'photo.png',png)
    send_file(b,aid,'document.bin',data)
    wait_for(lambda:file_message(a,bid,'document.bin') is not None,'Windows binary file reaches Java')
    verify_file(a,bid,'document.bin',data)
    # Bigger than one streaming chunk (1 MiB) on both platforms, so this exercises the actual
    # multi-chunk loop in the new streaming encrypt/store/send/receive/decrypt path, not just a
    # single-chunk payload small enough to hide a chunking bug.
    large_data=bytes(range(256))*12000
    send_file(a,bid,'large.bin',large_data)
    wait_for(lambda:file_message(b,aid,'large.bin') is not None,'A multi-chunk (~3 MB) attachment streams correctly end to end')
    verify_file(b,aid,'large.bin',large_data)
    send_file(a,gid,'group.bin',data)
    wait_for(lambda:file_message(b,gid,'group.bin') is not None and file_message(c,gid,'group.bin') is not None,'Encrypted group attachment reaches all members')
    group_file=verify_file(b,gid,'group.bin',data);verify_file(c,gid,'group.bin',data)
    send_file(b,aid,'empty.txt',b'')
    wait_for(lambda:file_message(a,bid,'empty.txt') is not None,'Empty attachment accepted with validated hash')
    verify_file(a,bid,'empty.txt',b'')
    # Reject over-limit metadata before allocating a body, unsafe paths and corrupt content.
    for kwargs in [dict(name='huge.bin',size=10485761,digest='a'*64),dict(name='../escape.txt',data=b'x'),dict(name='corrupt.bin',data=b'bad',digest='0'*64),dict(text='Unknown group',group=str(uuid.uuid4()))]:
        params=dict(text='',recipient=bid);params.update(kwargs)
        try:result=raw(a,b,**params)
        except AssertionError:pass
        else:assert base64.b64decode(result[0][1]).decode()=='REJECTED'
    assert file_message(b,aid,'corrupt.bin') is None
    print('PASS: oversized, unsafe-name, corrupt-file and unknown-group frames rejected',flush=True)
    outsider=Peer('java','Outsider','127.0.0.6');pair(outsider,b)
    result=raw(outsider,b,bid,'Unauthorized group message',group=gid)
    assert base64.b64decode(result[0][1]).decode()=='REJECTED' and not has(b,'Unauthorized group message','Received')
    outsider.stop()
    result=a.command('RAWGROUP\t'+b.ip+'\t44972\t'+gid+'\t'+aid+'\t'+b64('Replaced group')+'\t'+','.join(sorted([aid,bid,cid])))
    assert base64.b64decode(result[0][1]).decode()=='REJECTED'
    assert any(r[0]=='G' and r[1]==gid and r[2]==b64('Team مجموعة') for r in b.state())
    blob=work/'Windows-B'/'attachments'/(aid+'-'+group_file[1]+'.sec');original=blob.read_bytes();blob.write_bytes(original[:-1]+bytes([original[-1]^1]))
    try:b.command('FILEHASH\t'+gid+'\t'+group_file[1])
    except AssertionError:pass
    else:raise AssertionError('Corrupt stored attachment was accepted')
    finally:blob.write_bytes(original)
    print('PASS: non-member group messages, group replacement and corrupt stored attachments rejected',flush=True)
    # Clearing one conversation must neither clear another nor resurrect on retry.
    notice=b.command('NOTIFYCOUNT')[0][1]
    b.command('CLEAR\t'+gid);assert conv(b,gid)==[] and gid in groups(b)
    assert file_message(b,aid,'photo.png') is not None and file_message(c,gid,'group.bin') is not None
    result=raw(a,b,bid,'',gid,'group.bin',data,message_id=group_file[1])
    assert base64.b64decode(result[0][1]).decode()=='LM4\tACK\t'+group_file[1]+'\t'+bid
    assert conv(b,gid)==[] and b.command('NOTIFYCOUNT')[0][1]==notice
    assert not (work/'Windows-B'/'attachments'/(aid+'-'+group_file[1]+'.sec')).exists()
    b.stop();b=Peer('cs','Windows-B','127.0.0.3');assert conv(b,gid)==[] and gid in groups(b)
    verify_file(b,aid,'photo.png',png)
    a.command('CLEAR\t'+bid);assert conv(a,bid)==[] and len(conv(a,gid))>0
    a.stop();a=Peer('java','Phone-A','127.0.0.2');assert conv(a,bid)==[] and gid in groups(a)
    print('PASS: local clear removes only selected history/blobs, persists tombstones and leaves contacts/groups/other devices intact',flush=True)
    # Offline recipient: each member gets its own durable queue; creator restart keeps invitations.
    c.stop();b.send(gid,'Offline group delivery');send_file(b,gid,'offline.bin',data)
    wait_for(lambda:has(a,'Offline group delivery','Received'),'Online member receives while another member is offline')
    b.stop();b=Peer('cs','Windows-B','127.0.0.3');assert gid in groups(b)
    c=Peer('java','Phone-C','127.0.0.4')
    wait_for(lambda:has(c,'Offline group delivery','Received') and has(b,'Offline group delivery','Delivered') and file_message(c,gid,'offline.bin') is not None,'Group queue and attachment survive sender restart and reconnect')
    verify_file(c,gid,'offline.bin',data)
    # Java creator, Windows recipient, and local queue cancellation.
    gid2=a.command('GROUP\t'+b64('Phone-created group')+'\t'+bid+','+cid)[0][1]
    wait_for(lambda:gid2 in groups(b) and gid2 in groups(c),'Java-created group interoperates with Windows')
    b.stop();a.send(bid,'Cancelled before delivery');send_file(a,bid,'cancelled.bin',data);a.command('CLEAR\t'+bid)
    b=Peer('cs','Windows-B','127.0.0.3');time.sleep(4)
    assert not has(b,'Cancelled before delivery','Received') and file_message(b,aid,'cancelled.bin') is None
    print('PASS: clearing cancels unsent text and attachment queues',flush=True)
    # Read receipts and unread counts: a message is "Seen" only after the recipient opens the conversation.
    before_unread=int(a.command('UNREAD\t'+cid)[0][1])
    c.send(aid,'Unread receipt check')
    wait_for(lambda:has(a,'Unread receipt check','Received'),'Message arrives before being marked read')
    assert int(a.command('UNREAD\t'+cid)[0][1])==before_unread+1 and not has(c,'Unread receipt check','Seen')
    a.command('READ\t'+cid)
    assert int(a.command('UNREAD\t'+cid)[0][1])==before_unread
    wait_for(lambda:has(a,'Unread receipt check','Seen') and has(c,'Unread receipt check','Seen'),'Marking a conversation read sends a seen receipt back to the sender')
    print('PASS: unread count clears on read and the sender sees a seen receipt',flush=True)
    # Group seen only aggregates once every member has read the message.
    gid3=a.command('GROUP\t'+b64('Seen aggregate group')+'\t'+bid+','+cid)[0][1]
    wait_for(lambda:gid3 in groups(b) and gid3 in groups(c),'Java-created seen-test group interoperates with Windows and the other phone')
    a.send(gid3,'Group seen aggregate check')
    wait_for(lambda:has(b,'Group seen aggregate check','Received') and has(c,'Group seen aggregate check','Received'),'Group message reaches both members before either reads it')
    b.command('READ\t'+gid3)
    wait_for(lambda:has(b,'Group seen aggregate check','Seen'),'First group member seen receipt is sent')
    time.sleep(2)
    assert has(a,'Group seen aggregate check','Delivered'),'Aggregate must stay Delivered until every member has read it'
    c.command('READ\t'+gid3)
    wait_for(lambda:has(a,'Group seen aggregate check','Seen'),'Aggregate becomes Seen only once every member has read the group message')
    print('PASS: group seen status aggregates only once every member has read the message',flush=True)
    # 0.6.0 group relay: an offline original sender's message and attachment still reach a returning member via another member.
    d=Peer('java','Phone-D','127.0.0.7');pair(a,d);pair(b,d);pair(c,d)
    gid4=a.command('GROUP\t'+b64('Relay group')+'\t'+bid+','+cid+','+d.id)[0][1]
    wait_for(lambda:gid4 in groups(b) and gid4 in groups(c) and gid4 in groups(d),'Relay-test group invitation reaches all members')
    c.stop()
    a.send(gid4,'Relay while C is away')
    relay_png=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jk1sAAAAASUVORK5CYII=')
    send_file(a,gid4,'relay.png',relay_png)
    wait_for(lambda:has(b,'Relay while C is away','Received') and file_message(b,gid4,'relay.png') is not None,'Group message and attachment reach an online member directly')
    verify_file(b,gid4,'relay.png',relay_png)
    a.stop()
    c=Peer('java','Phone-C','127.0.0.4');assert c.id==cid
    wait_for(lambda:has(c,'Relay while C is away','Received') and file_message(c,gid4,'relay.png') is not None,"Member who reconnects receives an offline sender's message and attachment from another member")
    verify_file(c,gid4,'relay.png',relay_png)
    print('PASS: group relay delivers a message and attachment from an offline original sender via another member',flush=True)
    # Two members (B and D) both independently relay/sync the same item to C; it must not duplicate.
    before_count=len([r for r in c.state() if r[0]=='M' and r[5]==b64('Relay while C is away')])
    time.sleep(4)
    assert len([r for r in c.state() if r[0]=='M' and r[5]==b64('Relay while C is away')])==before_count==1
    print('PASS: the same relayed message arriving from two sources is not duplicated',flush=True)
    # A stale, already-expired group message is rejected on arrival and never stored, even from a verified member.
    stale_text='Stale expired group message'
    stale_line='RAWTIME\t'+b.ip+'\t44972\t'+d.id+'\t'+bid+'\t'+str(uuid.uuid4())+'\t'+b64(stale_text)+'\t'+gid4+'\t'+b64('')+'\t0\t\t1700000000000'
    result=d.command(stale_line)
    assert base64.b64decode(result[0][1]).decode()=='REJECTED' and not has(b,stale_text,'Received')
    print('PASS: an expired group message is rejected on arrival and never stored',flush=True)
    # Local purge: an already-expired group message seeded on disk is removed before syncing, at startup.
    purge_dir=work/'Purge-Test';purge_dir.mkdir()
    purge_id=str(uuid.uuid4());old_group=str(uuid.uuid4())
    rows=['LMSTORE4\t'+purge_id+'\t'+b64('Purge-Test')]
    rows+=['G\t'+old_group+'\t'+purge_id+'\t'+b64('Purge Group')+'\t'+purge_id+'\t']
    rows+=['M\t'+str(uuid.uuid4())+'\t'+purge_id+'\t'+bid+'\t1700000000000\t'+b64('Old expired local message')+'\tQueued\t1\t'+old_group+'\t\t0\t\t\t1']
    rows+=['END']
    (purge_dir/'state.txt').write_text('\n'.join(rows)+'\n',encoding='utf-8')
    pt=Peer('java','Purge-Test','127.0.0.8')
    assert not has(pt,'Old expired local message','Queued')
    pt.stop()
    print('PASS: an already-expired group message is purged locally at startup, before any sync',flush=True)
    # Profile pictures (0.7.1+ sharing): pushed only to already-verified peers, over the same
    # verified connection as everything else, never fetched or guessed. Uses b/c (verified with
    # each other since the start of this file and never stopped/replaced), not a (stopped above).
    avatar_hash=hashlib.sha256(png).hexdigest()
    b.command('SETAVATAR\t'+base64.b64encode(png).decode())
    wait_for(lambda:c.command('PEERAVATAR\t'+bid)[0][1:]==[avatar_hash,str(len(png))],'A verified device pushes its profile picture and the other side receives it')
    b.command('CLEARAVATAR')
    wait_for(lambda:c.command('PEERAVATAR\t'+bid)[0][1]=='NONE','Clearing a profile picture removes it from the other side too')
    # Files are protected separately and never stored as raw plaintext.
    for directory in work.glob('*/attachments'):
        for path in directory.glob('*.sec'):
            stored=path.read_bytes();assert png not in stored and data not in stored
    print('ALL 0.4 GROUP / ATTACHMENT / CLEAR TESTS PASSED',flush=True)
finally:
    for p in processes:
        if p.poll() is None:p.kill()
    for p in processes:
        try:p.wait(timeout=5)
        except subprocess.TimeoutExpired:pass
