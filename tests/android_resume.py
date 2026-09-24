"""Inject real socket loss and receiver restart below the old 100 MiB checkpoint."""
import hashlib
import pathlib
exec(pathlib.Path(__file__).with_name('integration.py').read_text(encoding='utf-8').split('# Seed a real 0.2 snapshot')[0])

def records(peer,conversation):return peer.command('CONV\t'+conversation)
def available(peer,conversation,mid):return peer.command('HASFILE\t'+conversation+'\t'+mid)[0][1]=='true'
def metrics(peer):return peer.command('TRANSFERSTATE')[0]
def offer(sender,receiver,source):
    old={row[1] for row in records(receiver,sender.id)}
    sender.command('FILEPATH\t'+receiver.id+'\t'+str(source))
    wait_for(lambda:any(row[1] not in old for row in records(receiver,sender.id)),'Ordinary file offered')
    return next(row[1] for row in records(receiver,sender.id) if row[1] not in old)
def export_matches(receiver,sender,mid,source,suffix):
    output=work/(suffix+'.bin')
    receiver.command('EXPORT\t'+sender.id+'\t'+mid+'\t'+str(output))
    with source.open('rb') as original, output.open('rb') as copy:
        assert hashlib.file_digest(original,'sha256').digest()==hashlib.file_digest(copy,'sha256').digest()

try:
    a=Peer('java','Resume-Sender','127.0.0.2');b=Peer('java','Resume-Receiver','127.0.0.3');pair(a,b)
    source=work/'resume-distinct-blocks.bin'
    # Every block differs: accidentally repeating an earlier range cannot pass the checksum.
    with source.open('wb') as output:
        for i in range(128):output.write(i.to_bytes(4,'big')+bytes([i])* (256*1024-4))
        output.write(b'non-aligned final block' * 7)
    mid=offer(a,b,source)
    assert not available(b,a.id,mid),'File downloaded before consent'
    b.command('TRACK');b.command('DROPAT\t3145728');b.command('DOWNLOADASYNC\t'+a.id+'\t'+mid)
    wait_for(lambda:available(b,a.id,mid),'Injected disconnect resumes automatically')
    result=metrics(b)
    assert result[3]=='0' and result[4]=='1',result
    export_matches(b,a,mid,source,'after-disconnect')
    print('PASS: socket loss below 100 MiB resumes without progress regression; final bytes match',flush=True)

    mid2=offer(a,b,source)
    b.command('TRACK');b.command('PAUSEAT\t5242880');b.command('DOWNLOADASYNC\t'+a.id+'\t'+mid2)
    wait_for(lambda:int(metrics(b)[1])>=5242880 and metrics(b)[5]=='0','Pause saves partial encrypted file')
    saved=int(metrics(b)[1]);partial=work/'Resume-Receiver'/'attachments'/(a.id+'-'+mid2+'.sec.resume')
    assert partial.exists() and not available(b,a.id,mid2)
    b.stop();b=Peer('java','Resume-Receiver','127.0.0.3')
    b.command('TRACK');b.command('DOWNLOAD\t'+a.id+'\t'+mid2)
    result=metrics(b)
    assert int(result[2])==saved and result[3]=='0',result
    assert not partial.exists(),'Partial was not committed'
    export_matches(b,a,mid2,source,'after-restart')
    print('PASS: receiver restart resumes at exactly the saved encrypted block, without restarting the file',flush=True)

    crash_mid=offer(a,b,source)
    b.command('TRACK');b.command('HALTAT\t3145728');b.command('DOWNLOADASYNC\t'+a.id+'\t'+crash_mid)
    wait_for(lambda:b.p.poll() is not None,'Receiver process terminated during an active download')
    b=Peer('java','Resume-Receiver','127.0.0.3')
    assert not available(b,a.id,crash_mid),'Incomplete file exposed after crash'
    b.command('TRACK');b.command('DOWNLOAD\t'+a.id+'\t'+crash_mid)
    assert int(metrics(b)[2])>=3145728 and metrics(b)[3]=='0'
    export_matches(b,a,crash_mid,source,'after-process-crash')
    print('PASS: abrupt receiver process exit preserves saved progress and final file integrity',flush=True)

    c=Peer('java','Wrong-Recipient','127.0.0.4');pair(a,c)
    for request in [(c,'0'),(b,'-1'),(b,'1'),(b,str(source.stat().st_size+262144))]:
        peer,offset=request
        reply=peer.command('RAWSTREAM\t'+a.ip+'\t44972\t'+a.id+'\t'+mid+'\t'+offset)
        assert base64.b64decode(reply[0][1]).decode()=='LM4\tUNAVAILABLE'
    print('PASS: new stream rejects wrong recipients and invalid offsets',flush=True)

    changing=work/'changed-source.bin';changing.write_bytes(b'A'*65537)
    a.command('FASTFILE\t'+b.id+'\t'+str(changing))
    wait_for(lambda:any(len(row)>7 and row[7]==b64(changing.name) for row in records(b,a.id)),'Changing-source offer arrives')
    changed=next(row[1] for row in records(b,a.id) if len(row)>7 and row[7]==b64(changing.name))
    changing.write_bytes(b'B'*65537)
    try:b.command('DOWNLOAD\t'+a.id+'\t'+changed)
    except AssertionError as failure:assert 'changed or is damaged' in str(failure)
    else:raise AssertionError('Changed file accepted')
    assert not available(b,a.id,changed)
    print('PASS: source corruption stops visibly instead of retrying forever',flush=True)

    mid3=offer(a,b,source)
    b.command('TRACK');b.command('CLEARAT\t3145728');b.command('DOWNLOADASYNC\t'+a.id+'\t'+mid3)
    wait_for(lambda:not records(b,a.id) and metrics(b)[5]=='0','Clear cancels active stream')
    assert not (work/'Resume-Receiver'/'attachments'/(a.id+'-'+mid3+'.sec.resume')).exists()
    assert not (work/'Resume-Receiver'/'attachments'/(a.id+'-'+mid3+'.sec')).exists()
    print('ALL ANDROID RESUME / DISCONNECT / RESTART / AUTHORIZATION TESTS PASSED',flush=True)
finally:
    for process in processes:
        if process.poll() is None:process.kill()
    for process in processes:
        try:process.wait(timeout=5)
        except subprocess.TimeoutExpired:pass
