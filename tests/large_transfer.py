"""Optional >1 GiB fast-path acceptance test, with Java heap capped at 64 MiB."""
import pathlib, hashlib
exec(pathlib.Path(__file__).with_name('integration.py').read_text(encoding='utf-8').split('# Seed a real 0.2 snapshot')[0])
try:
    a=Peer('cs','Large-Windows','127.0.0.2');b=Peer('java','Large-Java','127.0.0.3');pair(a,b)
    source=work/'above-normal-limit.bin';size=1024*1024*1024+1024*1024
    with source.open('wb') as f:
        f.write(b'large-transfer-regression');f.seek(size-1);f.write(b'X')
    start=time.monotonic();a.command('FASTFILE\t'+b.id+'\t'+str(source));prepare=time.monotonic()-start
    wait_for(lambda:len(b.command('CONV\t'+a.id))==1,'1025 MiB offer delivered without payload')
    row=b.command('CONV\t'+a.id)[0];assert int(row[8])==size
    assert b.command('HASFILE\t'+a.id+'\t'+row[1])[0][1]=='false'
    start=time.monotonic();b.command('DOWNLOAD\t'+a.id+'\t'+row[1]);duration=time.monotonic()-start
    b.command('VERIFYFILE\t'+a.id+'\t'+row[1])
    assert b.command('HASFILE\t'+a.id+'\t'+row[1])[0][1]=='true'
    print('PASS: 1025 MiB fast transfer, full integrity verified, Java -Xmx64m',flush=True)
    print('MEASURE: preparation %.3fs; download + encrypted storage + full verification %.3fs (%.1f MiB/s)'%(prepare,duration,1025/duration),flush=True)
    # Only this test's generated files, never app/user originals.
    a.command('CLEAR\t'+b.id);b.command('CLEAR\t'+a.id);source.unlink()
finally:
    for p in processes:
        if p.poll() is None:p.kill()
    for p in processes:
        try:p.wait(timeout=5)
        except subprocess.TimeoutExpired:pass

