"""A modern offer-only peer must not hide a legacy peer that actually has the file."""
import pathlib
import hashlib
exec(pathlib.Path(__file__).with_name('integration.py').read_text(encoding='utf-8').split('# Seed a real 0.2 snapshot')[0])

def rows(peer,group):return peer.command('CONV\t'+group)
def message(peer,group,name):return next((r for r in rows(peer,group) if len(r)>7 and r[7]==b64(name)),None)
def send(peer,group,name,data):peer.command('FILE\t'+group+'\t'+b64(name)+'\t'+base64.b64encode(data).decode())
def fetch(peer,group,name,data):
    mid=message(peer,group,name)[1]
    peer.command('DOWNLOAD\t'+group+'\t'+mid)
    result=peer.command('FILEHASH\t'+group+'\t'+mid)[0]
    assert result[1:]==[hashlib.sha256(data).hexdigest(),str(len(data))]

try:
    a=Peer('java','Modern-Source','127.0.0.2')
    b=Peer('cs','Legacy-Source','127.0.0.3')
    c=Peer('java','Offer-Only','127.0.0.4')
    d=Peer('java','Receiver','127.0.0.6')
    peers=[a,b,c,d]
    for index,peer in enumerate(peers):
        for other in peers[index+1:]:pair(peer,other)
    group=b.command('GROUP\t'+b64('Mixed file sources')+'\t'+','.join(p.id for p in [a,c,d]))[0][1]
    wait_for(lambda:all(any(r[0]=='G' and r[1]==group for r in p.state()) for p in [a,c,d]),'Mixed group reaches all members')
    data=bytes(range(256))*1024+b'final bytes'
    send(b,group,'legacy-original.bin',data)
    wait_for(lambda:all(message(p,group,'legacy-original.bin') for p in [a,c,d]),'Legacy original offers file to modern members')
    fetch(d,group,'legacy-original.bin',data)
    assert c.command('HASFILE\t'+group+'\t'+message(c,group,'legacy-original.bin')[1])[0][1]=='false'
    print('PASS: legacy original remains downloadable with modern offer-only members online',flush=True)
    send(a,group,'legacy-relay.bin',data)
    wait_for(lambda:all(message(p,group,'legacy-relay.bin') for p in [b,c,d]),'Modern original offers relay test file')
    fetch(b,group,'legacy-relay.bin',data)
    a.stop()
    fetch(d,group,'legacy-relay.bin',data)
    assert c.command('HASFILE\t'+group+'\t'+message(c,group,'legacy-relay.bin')[1])[0][1]=='false'
    print('PASS: offline original uses legacy relay when an online modern member has only the offer',flush=True)
finally:
    for process in processes:
        if process.poll() is None:process.kill()
    for process in processes:
        try:process.wait(timeout=5)
        except subprocess.TimeoutExpired:pass
