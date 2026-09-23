"""Actual ordinary/fast payload accounting and a shared 10 MiB/s cap over TLS."""
import pathlib
exec(pathlib.Path(__file__).with_name('integration.py').read_text(encoding='utf-8').split('# Seed a real 0.2 snapshot')[0])
def rows(p,other):return p.command('CONV\t'+other)
def usage(p):return int(p.command('USAGE')[0][1])
try:
    a=Peer('java','Budget-Android','127.0.0.2');b=Peer('cs','Budget-Windows','127.0.0.3');c=Peer('cs','Budget-Other','127.0.0.4')
    pair(a,b);pair(a,c)
    data=b'x'*(1024*1024)
    a.command('FILE\t'+b.id+'\t'+b64('ordinary.bin')+'\t'+base64.b64encode(data).decode())
    wait_for(lambda:len(rows(b,a.id))==1,'Normal offer reaches Windows')
    assert usage(a)==0,'Offers/preparation consumed upload budget'
    mid=rows(b,a.id)[0][1];b.command('DOWNLOAD\t'+a.id+'\t'+mid)
    assert usage(a)==len(data),'Normal payload not counted exactly'
    a.stop();a=Peer('java','Budget-Android','127.0.0.2');assert usage(a)==len(data),'Usage lost on restart'
    initial=10*1024**3;a.command('SEEDUSAGE\t'+str(initial))
    source=work/'fast.bin';size=32*1024**2
    with source.open('wb') as f:f.truncate(size)
    a.command('FASTFILE\t'+b.id+'\t'+str(source));a.command('FASTFILE\t'+c.id+'\t'+str(source))
    wait_for(lambda:len(rows(b,a.id))==2 and len(rows(c,a.id))==1,'Fast offers reach both recipients')
    assert usage(a)==initial,'Fast hashing or offers consumed budget'
    mb=next(r[1] for r in rows(b,a.id) if r[7]==b64('fast.bin'));mc=rows(c,a.id)[0][1]
    start=time.monotonic();b.command('DOWNLOADASYNC\t'+a.id+'\t'+mb);c.command('DOWNLOADASYNC\t'+a.id+'\t'+mc)
    time.sleep(.3);a.send(b.id,'Text during capped uploads')
    wait_for(lambda:has(b,'Text during capped uploads','Received'),'Text still arrives during capped uploads')
    def finished():
        return b.command('HASFILE\t'+a.id+'\t'+mb)[0][1]=='true' and c.command('HASFILE\t'+a.id+'\t'+mc)[0][1]=='true'
    wait_for(finished,'Both capped downloads finish')
    elapsed=time.monotonic()-start
    assert elapsed>=6.35,'Combined 64 MiB exceeded the shared 10 MiB/s cap'
    assert usage(a)==initial+2*size,'Fast payload totals incorrect'
    print('PASS: normal/fast accounting, offers excluded, persisted restart, shared concurrent cap; 64 MiB in %.3fs'%elapsed,flush=True)
finally:
    for p in processes:
        if p.poll() is None:p.kill()
    for p in processes:
        try:p.wait(timeout=5)
        except subprocess.TimeoutExpired:pass

