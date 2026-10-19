"""Engine-only Offline lifecycle on loopback; no UI or persistence preference is exercised."""
import pathlib
import hashlib
import socket
import time

exec(pathlib.Path(__file__).with_name('integration.py').read_text(encoding='utf-8').split('# Seed a real 0.2 snapshot')[0])


def network(peer):
    return peer.command('NETWORK')[0][1]


def reachable(peer):
    try:
        with socket.create_connection((peer.ip, 44972), timeout=.3):
            return True
    except OSError:
        return False


def count(peer, text):
    return sum(row[0] == 'M' and row[5] == b64(text) for row in peer.state())


def closed_after_offline(connection):
    connection.settimeout(2)
    try:
        # TLS can send an alert before closing a client that never handshook.
        while connection.recv(1024):
            pass
        return True
    except (ConnectionResetError, ConnectionAbortedError, OSError) as error:
        if isinstance(error, socket.timeout):
            return False
        return True


def offline_network_checks(peer, other):
    # A connected but stalled inbound control client must be closed, not just the listener.
    stalled = socket.create_connection((peer.ip, 44972), timeout=2)
    try:
        time.sleep(.3)  # Let the accept loop register the idle inbound socket.
        peer.command('OFFLINE')
        assert network(peer) == 'Offline' and not reachable(peer)
        assert closed_after_offline(stalled), 'accepted control socket survived Offline'
        peer.command('REFRESH')
        phantom = str(uuid.uuid4())
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as packet:
            packet.sendto(('LM4\tHELLO\t' + phantom + '\t' + b64('Offline phantom') + '\t44972').encode(),
                          (peer.ip, 44971))
        time.sleep(.2)
        assert not any(row[0] == 'P' and row[1] == phantom for row in peer.state()), 'discovery changed Offline state'
        try:
            peer.command('ADD\t' + other.ip + ':44972')
            raise AssertionError('Add by IP succeeded while Offline')
        except AssertionError as error:
            assert 'ERROR' in str(error), error
    finally:
        stalled.close()


try:
    for first, second in [('java', 'java'), ('cs', 'cs'), ('cs', 'java'), ('java', 'cs')]:
        print('PAIR:', first, second, flush=True)
        a = Peer(first, 'A-' + first + second, '127.0.0.80')
        b = Peer(second, 'B-' + first + second, '127.0.0.81')
        for attempt in range(3):
            try:
                pair(a, b)
                break
            except AssertionError:
                if attempt == 2:
                    raise
                time.sleep(1)
        wait_for(lambda: any(row[0] == 'P' and row[1] == b.id and row[2] == 'True' for row in a.state())
                 if first == 'cs' else any(row[0] == 'P' and row[1] == b.id and row[2] == 'true' for row in a.state()),
                 'peer present before Offline')
        for _ in range(3):
            offline_network_checks(b, a); b.command('OFFLINE')
            assert network(b) == 'Offline' and not reachable(b)
            a.send(b.id, 'offline direct')
            assert has(a, 'offline direct', 'Queued')
            b.command('ONLINE'); b.command('ONLINE')
            assert network(b) == 'Online' and reachable(b)
            wait_for(lambda: has(b, 'offline direct', 'Received'), 'queued direct delivered')
        wait_for(lambda: count(b, 'offline direct') == 3, 'each queued direct delivered once')
        avatar = hashlib.sha256(b.key.encode()).digest()
        b.command('OFFLINE')
        b.command('SETAVATAR\t' + base64.b64encode(avatar).decode())
        assert a.command('PEERAVATAR\t' + b.id)[0][1] == 'NONE', 'avatar sent while Offline'
        b.command('ONLINE')
        wait_for(lambda: a.command('PEERAVATAR\t' + b.id)[0][1] != 'NONE', 'avatar synchronized on retry')
        b.command('OFFLINE')
        a.command('OFFLINE')
        a.send(b.id, 'both offline')
        assert has(a, 'both offline', 'Queued') and not reachable(a) and not reachable(b)
        a.command('ONLINE'); b.command('ONLINE')
        wait_for(lambda: has(b, 'both offline', 'Received'), 'both peers restarted')
        assert count(b, 'both offline') == 1
        b.command('OFFLINE')
        with socket.socket() as blocker:
            blocker.bind((b.ip, 44972)); blocker.listen()
            try:
                b.command('ONLINE')
                raise AssertionError('expected bind failure')
            except AssertionError as failure:
                assert 'ERROR' in str(failure), failure
            assert network(b) == 'Offline'
        b.command('ONLINE')
        assert reachable(b), 'bind retry did not reopen listener'
        b.command('RAPID')
        assert network(b) == 'Online' and reachable(b), 'overlapping transitions left the listener closed'
        a.send(b.id, 'after rapid transitions')
        wait_for(lambda: has(b, 'after rapid transitions', 'Received'), 'delivery after overlapping transitions')
        assert count(b, 'after rapid transitions') == 1
        b.command('DISPOSE')
        assert network(b) == 'Offline' and not reachable(b)
        try:
            b.command('ONLINE')
            raise AssertionError('terminal disposal restarted')
        except AssertionError as failure:
            assert 'ERROR' in str(failure), failure
        a.stop(); b.stop()
    owner = Peer('cs', 'GroupOwner', '127.0.0.82')
    member = Peer('java', 'GroupMember', '127.0.0.83')
    third = Peer('cs', 'GroupThird', '127.0.0.84')
    pair(owner, member); pair(owner, third); pair(member, third)
    group_id = owner.command('GROUP\t' + b64('Offline group') + '\t' + member.id + ',' + third.id)[0][1]
    wait_for(lambda: any(row[0] == 'G' and row[1] == group_id for row in member.state()), 'group ready')
    owner.command('OFFLINE')
    try:
        owner.command('TRANSFEROWNER\t' + group_id + '\t' + member.id)
        raise AssertionError('remote capability action succeeded Offline')
    except AssertionError as error:
        assert 'ERROR' in str(error), error
    owner.command('ONLINE')
    member.command('OFFLINE')
    owner.command('SEND\t' + group_id + '\t' + b64('queued group'))
    wait_for(lambda: has(third, 'queued group', 'Received'), 'online member gets group message')
    time.sleep(13)
    assert any(row[0] == 'P' and row[1] == member.id and row[2].lower() == 'false' for row in owner.state()), 'presence did not expire'
    assert count(member, 'queued group') == 0
    member.command('ONLINE')
    wait_for(lambda: has(member, 'queued group', 'Received'), 'offline member gets group message after restart')
    wait_for(lambda: any(row[0] == 'P' and row[1] == member.id and row[2].lower() == 'true'
                         for row in owner.state()), 'immediate rediscovery after presence expiry')
    assert count(member, 'queued group') == 1
    owner.stop(); member.stop(); third.stop()
    sender = Peer('java', 'TransferSender', '127.0.0.85')
    receiver = Peer('java', 'TransferReceiver', '127.0.0.86')
    pair(sender, receiver)
    source = work / 'offline-payload.bin'
    source.write_bytes(bytes(range(256)) * 262144)
    sender.command('SLOWMS\t40')
    for kind in ('fast-direct', 'ordinary-direct', 'ordinary-cache'):
        sender.command(('FASTFILE' if kind == 'fast-direct' else 'FILEPATH') + '\t' + receiver.id + '\t' + str(source))
        expected = {'fast-direct': 1, 'ordinary-direct': 2, 'ordinary-cache': 3}[kind]
        wait_for(lambda: len(receiver.command('CONV\t' + sender.id)) >= expected, kind + ' offer ready')
        message_id = receiver.command('CONV\t' + sender.id)[-1][1]
        target = work / ('offline-' + kind + '.bin')
        direct = kind != 'ordinary-cache'
        start = ('DOWNLOADTOASYNC\t' + sender.id + '\t' + message_id + '\t' + str(target)) if direct else ('DOWNLOADASYNC\t' + sender.id + '\t' + message_id)
        retry = ('DOWNLOADTO\t' + sender.id + '\t' + message_id + '\t' + str(target)) if direct else ('DOWNLOAD\t' + sender.id + '\t' + message_id)
        receiver.command('TRACK')
        receiver.command(start)
        wait_for(lambda: int(receiver.command('TRANSFERSTATE')[0][1]) > 2 * 1024 * 1024,
                 kind + ' transfer in progress')
        receiver.command('OFFLINE')
        wait_for(lambda: receiver.command('TRANSFERSTATE')[0][5] == '0', kind + ' worker paused')
        assert not reachable(receiver) and receiver.command('HASFILE\t' + sender.id + '\t' + message_id)[0][1] == 'false'
        partial_path = target if direct else work / receiver.key / 'attachments' / (sender.id + '-' + message_id + '.sec.resume')
        assert partial_path.exists(), kind + ' has no partial'
        partial = partial_path.stat().st_size
        assert partial > 0 and (not direct or partial < source.stat().st_size)
        time.sleep(1)
        assert partial_path.stat().st_size == partial, kind + ' wrote after Offline completed'
        assert receiver.command('HASFILE\t' + sender.id + '\t' + message_id)[0][1] == 'false', 'premature completion marker'
        try:
            receiver.command(retry)
            raise AssertionError('download started Offline')
        except AssertionError as error:
            assert 'ERROR' in str(error), error
        receiver.command('ONLINE')
        receiver.command(retry)
        if direct:
            assert hashlib.sha256(target.read_bytes()).digest() == hashlib.sha256(source.read_bytes()).digest()
        else:
            receiver.command('EXPORT\t' + sender.id + '\t' + message_id + '\t' + str(target))
            assert hashlib.sha256(target.read_bytes()).digest() == hashlib.sha256(source.read_bytes()).digest()
        assert receiver.command('HASFILE\t' + sender.id + '\t' + message_id)[0][1] == 'true'
        receiver.command(retry)
        assert target.stat().st_size == source.stat().st_size, 'retry appended duplicate bytes'
    sender.command('FASTFILE\t' + receiver.id + '\t' + str(source))
    wait_for(lambda: len(receiver.command('CONV\t' + sender.id)) >= 4, 'outbound fast offer ready')
    message_id = receiver.command('CONV\t' + sender.id)[-1][1]
    target = work / 'sender-offline.bin'
    receiver.command('TRACK')
    receiver.command('DOWNLOADTOASYNC\t' + sender.id + '\t' + message_id + '\t' + str(target))
    wait_for(lambda: int(receiver.command('TRANSFERSTATE')[0][1]) > 2 * 1024 * 1024,
             'outbound fast stream in progress')
    sender.command('OFFLINE')
    assert not reachable(sender)
    receiver.command('CANCEL\t' + sender.id + '\t' + message_id)
    wait_for(lambda: receiver.command('TRANSFERSTATE')[0][5] == '0', 'outbound stream stopped')
    partial = target.stat().st_size
    assert 0 < partial < source.stat().st_size
    time.sleep(1)
    assert target.stat().st_size == partial and receiver.command('HASFILE\t' + sender.id + '\t' + message_id)[0][1] == 'false'
    sender.command('ONLINE')
    receiver.command('DOWNLOADTO\t' + sender.id + '\t' + message_id + '\t' + str(target))
    assert hashlib.sha256(target.read_bytes()).digest() == hashlib.sha256(source.read_bytes()).digest()
    sender.stop(); receiver.stop()
    print('PASS: interrupted Fast/ordinary direct and encrypted cache transfers retain partials and retry once', flush=True)
    print('PASS: reusable lifecycle in same/cross-platform engine pairs', flush=True)
finally:
    for process in processes:
        if process.poll() is None:
            process.kill()
