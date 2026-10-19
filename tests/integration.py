"""Run against the actual Java and C# peer engines, without a host/server."""
import base64, pathlib, socket, subprocess, sys, time, uuid, threading, queue

java, java_classes, dotnet_dll, work = sys.argv[1:]
work = pathlib.Path(work) / ('peers-' + uuid.uuid4().hex)
work.mkdir(parents=True)
processes=[]
def b64(s):return base64.b64encode(s.encode()).decode()
class Peer:
    def __init__(self,kind,key,ip):
        self.kind,self.key,self.ip=kind,key,ip
        (work/key).mkdir(parents=True,exist_ok=True)
        self.error_path=work/key/('stderr-'+uuid.uuid4().hex+'.log')
        self.error_log=self.error_path.open('w',encoding='utf-8')
        command=([java,'-Xms16m','-Xmx64m','-cp',java_classes,'net.lanmsg.chat.PeerHarness'] if kind=='java' else ['dotnet',dotnet_dll])
        self.p=subprocess.Popen(command+[str(work/key),key,ip,'44972','44971'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.error_log,text=True,encoding='utf-8')
        processes.append(self.p)
        self.output=queue.Queue()
        def read_output():
            for line in self.p.stdout:self.output.put(line.rstrip('\r\n'))
            self.output.put(None)
        threading.Thread(target=read_output,daemon=True).start()
        line=self.readline('startup',30)
        assert line.startswith('READY\t'),(line,self.error_path)
        self.id=line.split('\t')[1]
    def readline(self,operation,timeout=90):
        try:line=self.output.get(timeout=timeout)
        except queue.Empty:raise AssertionError(f'{self.key} timed out during {operation}; stderr: {self.error_path}')
        assert line is not None,(self.key,self.p.poll(),self.error_path.read_text(encoding='utf-8')[-4000:])
        return line
    def command(self,line):
        self.p.stdin.write(line+'\n');self.p.stdin.flush();result=[]
        while True:
            row=self.readline(line.split('\t',1)[0])
            if row=='END':break
            result.append(row.split('\t'))
        assert not any(r[0]=="ERROR" for r in result),result
        return result
    def state(self):return self.command('STATE')
    def send(self,to,text):return self.command('SEND\t'+to+'\t'+b64(text))
    def stop(self):
        self.p.stdin.write('STOP\n');self.p.stdin.flush();self.p.wait(timeout=10)
        self.error_log.close()
def wait_for(predicate,label):
    end=time.monotonic()+30
    while time.monotonic()<end:
        if predicate(): print('PASS:',label,flush=True);return
        time.sleep(.3)
    raise AssertionError(label)
def has(peer,text,status):return any(r[0]=='M' and r[4]==status and r[5]==b64(text) for r in peer.state())
def announce(source,target):
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as s:
        s.bind((source.ip,0));s.sendto(('LM4\tHELLO\t'+source.id+'\t'+b64(source.key)+'\t44972').encode(),(target.ip,44971))
def pair(a,b):
    try:a.command('ADD\t'+b.ip+':44972')
    except Exception:
        print(b.command('ERROR'),flush=True)
        raise
    b.command('ADD\t'+a.ip+':44972')
    ca=a.command('CODE\t'+b.id)[0][1]
    cb=b.command('CODE\t'+a.id)[0][1]
    assert ca==cb, 'Safety code mismatch between platforms'
    a.command('VERIFY\t'+b.id+'\t'+ca)
    b.command('VERIFY\t'+a.id+'\t'+cb)
    print('PASS: matching safety code and explicit verification on both devices',flush=True)

class WireProxy:
    def __init__(self,target,tamper=False):
        self.captured=bytearray();self.tamper=tamper;self.listener=socket.socket();self.listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);self.listener.bind(('127.0.0.5',44972));self.listener.listen(1)
        self.thread=threading.Thread(target=self.run,args=(target,),daemon=True);self.thread.start()
    def run(self,target):
        source=None;dest=None
        try:
            source,_=self.listener.accept();dest=socket.create_connection((target,44972));source.settimeout(10);dest.settimeout(10)
            def pump(left,right,corrupt):
                changed=False
                try:
                    f=left.makefile('rb')
                    while True:
                        header=f.read(5)
                        if len(header)!=5:break
                        payload=f.read(int.from_bytes(header[3:5],'big'))
                        record=header+payload;self.captured.extend(record)
                        if corrupt and not changed and header[0]==23:
                            record=record[:-1]+bytes([record[-1]^1]);changed=True
                        right.sendall(record)
                except OSError:pass
                finally:
                    try:right.shutdown(socket.SHUT_RDWR)
                    except OSError:pass
            back=threading.Thread(target=pump,args=(dest,source,False),daemon=True);back.start();pump(source,dest,self.tamper);back.join(2)
        except OSError:pass
        finally:
            if source:source.close()
            if dest:dest.close()
            self.listener.close()
    def finish(self):self.thread.join(12)

# Seed a real 0.2 snapshot to exercise non-destructive migration.
legacy_a=str(uuid.uuid4());legacy_b=str(uuid.uuid4());legacy_text='Legacy queued message';legacy_reply='Legacy Windows queued message';legacy_history='Saved conversation from 0.2'
for name,identifier in [('Android-A',legacy_a),('Windows-B',legacy_b)]:
    directory=work/name;directory.mkdir()
    rows=['LMSTORE2\t'+identifier+'\t'+b64(name)]
    if name=='Android-A':
        rows+=['P\t'+legacy_b+'\t'+b64('Windows-B')+'\t127.0.0.3\t44972', 'M\t'+str(uuid.uuid4())+'\t'+legacy_a+'\t'+legacy_b+'\t1700000000000\t'+b64(legacy_text)+'\tQueued\t1']
    else:
        rows+=['P\t'+legacy_a+'\t'+b64('Android-A')+'\t127.0.0.2\t44972', 'M\t'+str(uuid.uuid4())+'\t'+legacy_b+'\t'+legacy_a+'\t1700000000000\t'+b64(legacy_reply)+'\tQueued\t1']
    rows+=['M\t'+str(uuid.uuid4())+'\t'+legacy_a+'\t'+legacy_b+'\t1699999999999\t'+b64(legacy_history)+'\t'+('Delivered' if name=='Android-A' else 'Received')+'\t1']
    rows+=['END'];(directory/'state.txt').write_text('\n'.join(rows)+'\n',encoding='utf-8')

try:
    a=Peer('java','Android-A','127.0.0.2');b=Peer('cs','Windows-B','127.0.0.3');aid,bid=a.id,b.id
    assert aid==legacy_a and bid==legacy_b and has(a,legacy_text,"Queued") and has(b,legacy_reply,"Queued")
    assert has(a,legacy_history,"Delivered") and has(b,legacy_history,"Received")
    print("PASS: 0.2 identity, contacts and pending messages preserved during encrypted migration",flush=True)
    announce(a,b);announce(b,a)
    wait_for(lambda:any(r[0]=='P' and r[1]==bid for r in a.state()) and any(r[0]=='P' and r[1]==aid for r in b.state()),'UDP discovery across Java and C#')
    a.send(bid,'Before verification')
    time.sleep(3)
    assert has(a,'Before verification','Queued') and not has(b,'Before verification','Received')
    print('PASS: unverified peers cannot exchange messages',flush=True)
    pair(a,b)
    wait_for(lambda:has(a,legacy_text,'Delivered') and has(b,legacy_text,'Received') and has(b,legacy_reply,'Delivered') and has(a,legacy_reply,'Received'),'Migrated 0.2 pending messages deliver in both directions after verification')
    a.send(bid,'مرحبا من أندرويد 👋\nsecond line')
    wait_for(lambda:has(b,'مرحبا من أندرويد 👋\nsecond line','Received') and has(a,'مرحبا من أندرويد 👋\nsecond line','Delivered'),'Android to Windows Unicode delivery + acknowledgement')
    existing=next(r for r in a.state() if r[0]=='M' and r[4]=='Delivered' and r[5]==b64(legacy_text))
    before=len([r for r in b.state() if r[0]=='M']);notice=b.command('NOTIFYCOUNT')[0][1]
    result=a.command('RAW\t'+b.ip+'\t44972\t'+aid+'\t'+bid+'\t'+existing[1]+'\t'+existing[5])
    assert base64.b64decode(result[0][1]).decode()=='LM4\tACK\t'+existing[1]+'\t'+bid
    assert len([r for r in b.state() if r[0]=='M'])==before and b.command('NOTIFYCOUNT')[0][1]==notice
    print('PASS: duplicate TLS retransmission does not duplicate history or notifications',flush=True)
    wrong='Message for another recipient'
    result=a.command('RAW\t'+b.ip+'\t44972\t'+aid+'\t'+str(uuid.uuid4())+'\t'+str(uuid.uuid4())+'\t'+b64(wrong))
    assert base64.b64decode(result[0][1]).decode()=='REJECTED' and not has(b,wrong,'Received')
    assert b.command('NOTIFYCOUNT')[0][1]==notice
    print('PASS: wrong-recipient TLS message rejected without notification',flush=True)
    secret='WIRE-CONFIDENTIAL-MARKER-9327'
    proxy=WireProxy(b.ip)
    a.command('RAW\t127.0.0.5\t44972\t'+aid+'\t'+bid+'\t'+str(uuid.uuid4())+'\t'+b64(secret));proxy.finish()
    assert has(b,secret,'Received') and secret.encode() not in proxy.captured and b64(secret).encode() not in proxy.captured
    assert len(proxy.captured)>1000
    print('PASS: captured TLS wire contains no plaintext/base64 message',flush=True)
    proxy=WireProxy(b.ip,True)
    try:a.command('RAW\t127.0.0.5\t44972\t'+aid+'\t'+bid+'\t'+str(uuid.uuid4())+'\t'+b64('tampered message'))
    except AssertionError:pass
    else:raise AssertionError('Tampered TLS record was accepted')
    proxy.finish();assert not has(b,'tampered message','Received')
    print('PASS: modified TLS ciphertext rejected',flush=True)
    before=len([r for r in b.state() if r[0]=='M'])
    attacker=Peer('java','Unknown-device','127.0.0.4')
    result=attacker.command('RAW\t'+b.ip+'\t44972\t'+aid+'\t'+bid+'\t'+str(uuid.uuid4())+'\t'+b64('forged message'))
    assert base64.b64decode(result[0][1]).decode()=='LM4\tPAIR'
    assert len([r for r in b.state() if r[0]=='M'])==before
    changed=b.command('CODE\t'+aid)[0][1]
    try:b.command('VERIFY\t'+aid+'\t'+changed)
    except AssertionError:pass
    else:raise AssertionError('Changed key was trusted without revoking old verification')
    attacker.stop();pair(a,b)
    print('PASS: impersonated ID with different TLS key blocked; changed-key verification rejected',flush=True)
    b.stop();a.send(bid,'Saved while Windows is offline');assert has(a,'Saved while Windows is offline','Queued')
    a.stop();a=Peer('java','Android-A','127.0.0.2');assert a.id==aid and has(a,'Saved while Windows is offline','Queued')
    b=Peer('cs','Windows-B','127.0.0.3');assert b.id==bid
    wait_for(lambda:has(b,'Saved while Windows is offline','Received') and has(a,'Saved while Windows is offline','Delivered'),'Java outbox, contacts and identity survive restart; delayed delivery')
    a.stop();b.send(aid,'Saved while Android is offline');b.stop();b=Peer('cs','Windows-B','127.0.0.3');assert b.id==bid and has(b,'Saved while Android is offline','Queued')
    a=Peer('java','Android-A','127.0.0.2');assert a.id==aid
    wait_for(lambda:has(a,'Saved while Android is offline','Received') and has(b,'Saved while Android is offline','Delivered'),'C# outbox survives restart; Windows to Android delayed delivery')
    b.stop()
    c=Peer('java','Android-C','127.0.0.4');pair(c,a);c.send(aid,'Phone to phone, no laptop')
    wait_for(lambda:has(a,'Phone to phone, no laptop','Received') and has(c,'Phone to phone, no laptop','Delivered'),'Java to Java direct phone-to-phone engine delivery')
    for key in ['Android-A','Windows-B','Android-C']:
        for suffix in ['state.txt','state.txt.bak']:
            stored=(work/key/suffix).read_bytes()
            assert stored.startswith(b'LMSEC3\n')
            assert b'Saved while' not in stored and b64('Saved while Android is offline').encode() not in stored
    print('PASS: primary and backup histories are encrypted on disk',flush=True)
    print('ALL SECURE PEER INTEGRATION TESTS PASSED',flush=True)
finally:
    for p in processes:
        if p.poll() is None:p.kill()
    for p in processes:
        try:p.wait(timeout=5)
        except subprocess.TimeoutExpired:pass
