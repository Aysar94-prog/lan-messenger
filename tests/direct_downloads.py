"""Direct destination interoperability and single-copy storage."""
import hashlib, pathlib
from pathlib import Path
exec(pathlib.Path(__file__).with_name('integration.py').read_text(encoding='utf-8').split('# Seed a real 0.2 snapshot')[0])
def digest(p):
    with open(p,'rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
try:
    a=Peer('java','Direct-Java','127.0.0.2');b=Peer('cs','Direct-Windows','127.0.0.3');pair(a,b)
    source=work/'payload.bin';source.write_bytes(bytes(range(256))*131073);wanted=digest(source)
    for sender,receiver in [(a,b),(b,a)]:
        for fast in [True,False]:
            if fast:sender.command('FASTFILE\t'+receiver.id+'\t'+str(source))
            elif sender.kind=='java':sender.command('FILEPATH\t'+receiver.id+'\t'+str(source))
            else:sender.command('FILE\t'+receiver.id+'\t'+b64('small.bin')+'\t'+base64.b64encode(b'ordinary direct payload').decode())
            # Select the newest incoming offer, excluding this peer's outgoing history.
            wait_for(lambda:len([r for r in receiver.command('CONV\t'+sender.id) if r[2]==sender.id])>= (1 if fast else 2),'offer delivered')
            rows=[r for r in receiver.command('CONV\t'+sender.id) if r[2]==sender.id];mid=rows[-1][1]
            target=work/(receiver.key+str(fast)+'.bin')
            assert receiver.command('HASFILE\t'+sender.id+'\t'+mid)[0][1]=='false'
            receiver.command('DOWNLOADTO\t'+sender.id+'\t'+mid+'\t'+str(target))
            assert digest(target)==(wanted if fast or sender.kind=='java' else hashlib.sha256(b'ordinary direct payload').hexdigest())
            assert receiver.command('HASFILE\t'+sender.id+'\t'+mid)[0][1]=='true'
            attachment=work/receiver.key/'attachments'/(sender.id+'-'+mid+'.sec')
            assert not attachment.exists() and not Path(str(attachment)+'.parts').exists()
            assert Path(str(attachment)+'.destination').exists()
            print('PASS: direct single-copy',sender.kind,'->',receiver.kind,'fast=',fast,flush=True)
    # RAW payload can be read with a plain socket, while a wrong token yields no data.
    for sender,receiver in [(b,a)]:
        mid=next(r[1] for r in receiver.command('CONV\t'+sender.id) if r[2]==sender.id and r[7]==b64(source.name))
        assert receiver.command('RAWDIRECTTEST\t'+sender.ip+'\t44972\t'+sender.id+'\t'+mid)[0][1]==wanted
    c=Peer('java','Direct-Android-2','127.0.0.4');pair(a,c)
    a.command('FASTFILE\t'+c.id+'\t'+str(source))
    wait_for(lambda:len(c.command('CONV\t'+a.id))==1,'Android to Android offer')
    mid=c.command('CONV\t'+a.id)[0][1]
    assert c.command('RAWDIRECTTEST\t'+a.ip+'\t44972\t'+a.id+'\t'+mid)[0][1]==wanted
    print('PASS: both senders expose plaintext only after valid one-use token; bad token and reuse rejected',flush=True)
    target=work/'android-resumed.bin'
    c.command('TRACK');c.command('DROPAT\t3145728');c.command('DOWNLOADTOASYNC\t'+a.id+'\t'+mid+'\t'+str(target))
    wait_for(lambda:c.command('HASFILE\t'+a.id+'\t'+mid)[0][1]=='true','Android direct transfer recovers dropped connection')
    state=c.command('TRANSFERSTATE')[0];assert state[3]=='0' and state[4]=='1',state
    assert digest(target)==wanted
    c.stop();c=Peer('java','Direct-Android-2','127.0.0.4')
    assert c.command('HASFILE\t'+a.id+'\t'+mid)[0][1]=='true'
    c.command('CLEAR\t'+a.id);assert target.exists(),'Clear deleted the user-selected file'
    print('PASS: destination survives restart and clear preserves the user file',flush=True)

finally:
    for p in processes:
        if p.poll() is None:p.kill()
