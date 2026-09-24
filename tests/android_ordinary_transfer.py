"""Android-engine to Android-engine ordinary attachment across multiple encrypted segments."""
import hashlib
import pathlib
import subprocess
import time

exec(pathlib.Path(__file__).with_name('integration.py').read_text(encoding='utf-8').split('# Seed a real 0.2 snapshot')[0])

try:
    sender=Peer('java','Ordinary-Sender','127.0.0.2')
    receiver=Peer('java','Ordinary-Receiver','127.0.0.3')
    pair(sender,receiver)
    source=work/'ordinary-205m.bin'
    block=bytes(range(256))*4096
    with source.open('wb') as output:
        for _ in range(205):output.write(block)
    sender.command('FILEPATH\t'+receiver.id+'\t'+str(source))
    def offer():
        rows=receiver.command('CONV\t'+sender.id)
        return next((row for row in rows if row[0]=='M' and len(row)>8 and row[8]==str(source.stat().st_size)),None)
    wait_for(lambda:offer() is not None,'Android ordinary file offer arrives')
    message_id=offer()[1]
    started=time.monotonic()
    receiver.command('DOWNLOAD\t'+sender.id+'\t'+message_id)
    elapsed=time.monotonic()-started
    exported=work/'ordinary-export.bin'
    receiver.command('EXPORT\t'+sender.id+'\t'+message_id+'\t'+str(exported))
    with source.open('rb') as original, exported.open('rb') as copy:
        assert hashlib.file_digest(original,'sha256').digest()==hashlib.file_digest(copy,'sha256').digest()
    print('PASS: 205 MiB ordinary Android-to-Android transfer crosses two encrypted range boundaries',flush=True)
    print('MEASURE: 205 MiB download and verification %.2fs (%.1f MiB/s loopback)'%(elapsed,205/elapsed),flush=True)
finally:
    for process in processes:
        if process.poll() is None:process.kill()
    for process in processes:
        try:process.wait(timeout=5)
        except subprocess.TimeoutExpired:pass
