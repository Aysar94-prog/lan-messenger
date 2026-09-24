"""Manual consent, bounded-memory fast transfer, restart/resume and chat latency."""
import pathlib, hashlib, statistics
exec(pathlib.Path(__file__).with_name('integration.py').read_text(encoding='utf-8').split('# Seed a real 0.2 snapshot')[0])
def records(p,conversation):return p.command('CONV\t'+conversation)
def available(p,conversation,mid):return p.command('HASFILE\t'+conversation+'\t'+mid)[0][1]=='true'
def digest(path):
    with open(path,'rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
try:
    a=Peer('java','Fast-Java','127.0.0.2');b=Peer('cs','Fast-Windows','127.0.0.3');pair(a,b)
    source=work/'large-original.bin'
    with source.open('wb') as f:
        block=bytes(range(256))*4096
        for _ in range(128):f.write(block)
    wanted=digest(source)
    started=time.monotonic();a.command('FASTFILE\t'+b.id+'\t'+str(source));prep=time.monotonic()-started
    wait_for(lambda:len(records(b,a.id))==1,'Fast file offer arrives without a payload')
    mid=records(b,a.id)[0][1]
    time.sleep(1);assert not available(b,a.id,mid)
    assert not (work/'Fast-Java'/'attachments'/(a.id+'-'+mid+'.sec')).exists()
    a.command('SLOWMS\t20')
    output=work/'downloaded.bin';b.command('DOWNLOADTOASYNC\t'+a.id+'\t'+mid+'\t'+str(output))
    time.sleep(.3)
    latencies=[]
    for i in range(5):
        text='Text during large download '+str(i);started=time.monotonic();a.send(b.id,text)
        deadline=started+5
        while not has(b,text,'Received'):
            assert time.monotonic()<deadline,'File transfer blocked chat'
            time.sleep(.02)
        latencies.append(time.monotonic()-started)
    deadline=time.monotonic()+45
    while not output.exists() or output.stat().st_size<8*1024*1024:
        assert time.monotonic()<deadline,'Direct destination checkpoint missing';time.sleep(.02)
    b.command('CANCEL\t'+a.id+'\t'+mid);time.sleep(.2)
    assert not available(b,a.id,mid),'Cancelled transfer committed early'
    checkpoint=output.stat().st_size
    b.stop();b=Peer('cs','Fast-Windows','127.0.0.3')
    a.command('SLOWMS\t0')
    started=time.monotonic();b.command('DOWNLOADTO\t'+a.id+'\t'+mid+'\t'+str(output));resume=time.monotonic()-started
    assert digest(output)==wanted
    assert not (work/'Fast-Windows'/'attachments'/(a.id+'-'+mid+'.sec.parts')).exists()
    print('PASS: manual consent, single destination copy, cancel/restart resumes direct destination',flush=True)
    print('MEASURE: Java prepare %.3fs; live-transfer text median %.3fs max %.3fs; resume from saved destination + verification %.3fs'%(prep,statistics.median(latencies),max(latencies),resume),flush=True)
    started=time.monotonic();b.command('FASTFILE\t'+a.id+'\t'+str(source));cs_prep=time.monotonic()-started
    wait_for(lambda:any(len(r)>7 and r[2]==b.id and r[7]==b64(source.name) for r in records(a,b.id)),'Windows fast offer arrives on Java')
    mid2=next(r[1] for r in records(a,b.id) if len(r)>7 and r[2]==b.id and r[7]==b64(source.name))
    assert not available(a,b.id,mid2)
    started=time.monotonic();output2=work/'java-download.bin';a.command('DOWNLOADTO\t'+b.id+'\t'+mid2+'\t'+str(output2));elapsed=time.monotonic()-started
    assert digest(output2)==wanted
    print('PASS: Windows to Java 128 MiB direct transfer under Java -Xmx64m',flush=True)
    print('MEASURE: C# prepare %.3fs; 128 MiB plaintext download + final verification %.3fs (%.1f MiB/s)'%(cs_prep,elapsed,128/elapsed),flush=True)
    # An independently verified third party still cannot fetch someone else's direct file.
    c=Peer('java','Other-Recipient','127.0.0.4');pair(c,b)
    response=c.command('RAWFETCH\t'+b.ip+'\t44972\t'+b.id+'\t'+mid2+'\t0\t104857600')
    assert base64.b64decode(response[0][1]).decode()=='LM4\tUNAVAILABLE'
    response=a.command('RAWFETCH\t'+b.ip+'\t44972\t'+b.id+'\t'+mid2+'\t1\t100')
    assert base64.b64decode(response[0][1]).decode()=='LM4\tUNAVAILABLE'
    print('PASS: verified wrong-recipient FETCH and misaligned range rejected',flush=True)
    # Same-size source mutation is caught by the offered whole-file hash before availability.
    changing=work/'changing.bin';changing.write_bytes(b'A'*65536)
    b.command('FASTFILE\t'+a.id+'\t'+str(changing))
    wait_for(lambda:any(len(r)>7 and r[7]==b64(changing.name) for r in records(a,b.id)),'Mutation-test offer arrives')
    changed_mid=next(r[1] for r in records(a,b.id) if len(r)>7 and r[7]==b64(changing.name))
    changing.write_bytes(b'B'*65536)
    try:a.command('DOWNLOADTO\t'+b.id+'\t'+changed_mid+'\t'+str(work/'changed-output.bin'))
    except AssertionError as error:assert 'changed or is damaged' in str(error)
    else:raise AssertionError('Changed source was accepted')
    assert not available(a,b.id,changed_mid)
    print('PASS: changed original cannot commit a complete download',flush=True)
    # Clearing an offer never deletes the external original.
    b.command('FASTFILE\t'+a.id+'\t'+str(source))
    wait_for(lambda:len([r for r in records(a,b.id) if len(r)>7 and r[2]==b.id and r[7]==b64(source.name)])==2,'Second offer arrives')
    a.command('CLEAR\t'+b.id);assert source.exists() and records(a,b.id)==[]
    print('ALL MANUAL / FAST TRANSFER TESTS PASSED',flush=True)
finally:
    for p in processes:
        if p.poll() is None:p.kill()
    for p in processes:
        try:p.wait(timeout=5)
        except subprocess.TimeoutExpired:pass
