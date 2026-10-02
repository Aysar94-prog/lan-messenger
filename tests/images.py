"""Photos auto-download for group members. A disguised non-image (double extension) remains a
manual offer on both platforms. A real video now auto-downloads like a photo on Android (2026-10-02
product change, explicitly requested: "the receiver must treat any video like photos") but Windows
has no equivalent feature yet and still treats it as a manual offer -- platforms are independent
and do not imply feature parity, same as every other platform-exclusive feature in this project."""
import pathlib
exec(pathlib.Path(__file__).with_name('integration.py').read_text(encoding='utf-8').split('# Seed a real 0.2 snapshot')[0])
def file(p,g,name):
    return next((r for r in p.command('CONV\t'+g) if len(r)>7 and r[7]==b64(name)),None)
def available(p,g,name):
    r=file(p,g,name)
    return r is not None and p.command('HASFILE\t'+g+'\t'+r[1])[0][1]=='true'
try:
    a=Peer('java','Images-A','127.0.0.2');b=Peer('cs','Images-B','127.0.0.3');c=Peer('java','Images-C','127.0.0.4')
    pair(a,b);pair(a,c);pair(b,c)
    g=a.command('GROUP\t'+b64('Automatic pictures')+'\t'+b.id+','+c.id)[0][1]
    wait_for(lambda:all(any(r[0]=='G' and r[1]==g for r in p.state()) for p in [b,c]),'Image group invitation reaches recipients')
    png='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jk1sAAAAASUVORK5CYII='
    a.command('FILE\t'+g+'\t'+b64('group-picture.PNG')+'\t'+png)
    wait_for(lambda:available(b,g,'group-picture.PNG') and available(c,g,'group-picture.PNG'),'Both group recipients fetch photos without DOWNLOAD commands')
    a.command('FILE\t'+g+'\t'+b64('video.mp4')+'\t'+base64.b64encode(b'video fixture').decode())
    a.command('FILE\t'+g+'\t'+b64('photo.png.exe')+'\t'+png)
    wait_for(lambda:all(file(p,g,'video.mp4') and file(p,g,'photo.png.exe') for p in [b,c]),'Non-image offers arrive')
    time.sleep(3)
    assert all(not available(p,g,'photo.png.exe') for p in [b,c]),'a disguised non-image must never auto-download on either platform'
    for p in [b,c]:
        expect_auto=(p.kind=='java') # Android auto-downloads video like a photo; Windows does not yet.
        assert available(p,g,'video.mp4')==expect_auto,f'video.mp4 auto-download on {p.kind} should be {expect_auto}'
    print('PASS: a disguised non-image stays manual on both platforms; a real video now auto-downloads on Android (matching a photo) but stays manual on Windows, which has no equivalent feature yet',flush=True)
finally:
    for p in processes:
        if p.poll() is None:p.kill()
    for p in processes:
        try:p.wait(timeout=5)
        except subprocess.TimeoutExpired:pass

