"""A real second device must adopt the owner's migrated pre-M1 group roster."""
import pathlib
import subprocess

exec(pathlib.Path(__file__).with_name('integration.py').read_text(encoding='utf-8').split('# Seed a real 0.2 snapshot')[0])


def roster(peer, group_id):
    rows = peer.command('ROSTER\t' + group_id)
    assert len(rows) == 1 and rows[0][0] == 'ROSTER', rows
    return int(rows[0][1]), set(rows[0][2].split(','))


try:
    for owner_kind, member_kind, suffix in (('java', 'cs', 'A'), ('cs', 'java', 'B')):
        owner_key, member_key, departed_key = 'MigrationOwner-' + suffix, 'MigrationMember-' + suffix, 'MigrationDeparted-' + suffix
        owner_ip, member_ip, departed_ip = ('127.0.0.30', '127.0.0.31', '127.0.0.32') if suffix == 'A' else ('127.0.0.33', '127.0.0.34', '127.0.0.35')
        owner = Peer(owner_kind, owner_key, owner_ip)
        member = Peer(member_kind, member_key, member_ip)
        departed = Peer('java', departed_key, departed_ip)
        owner_id, member_id, departed_id = owner.id, member.id, departed.id
        pair(owner, member)
        pair(owner, departed)
        group_id = owner.command('GROUP\t' + b64('Legacy migration broadcast') + '\t' + member_id + ',' + departed_id)[0][1]
        wait_for(lambda: group_id in [row[1] for row in member.state() if row[0] == 'G'], 'Member has original group before migration')
        assert roster(member, group_id) == (0, {owner_id, member_id, departed_id})

        owner.stop()
        member.stop()
        departed.stop()
        subprocess.run([java, '-cp', java_classes, 'net.lanmsg.chat.LegacyGroupState', str(work / owner_key), group_id, departed_id], check=True)

        # Restart the member alone first to prove it still holds the stale three-person roster.
        member = Peer(member_kind, member_key, member_ip)
        assert member.id == member_id
        assert roster(member, group_id) == (0, {owner_id, member_id, departed_id})
        owner = Peer(owner_kind, owner_key, owner_ip)
        assert owner.id == owner_id
        assert roster(owner, group_id) == (1, {owner_id, member_id})
        assert owner.command('LEFT\t' + group_id + '\t' + departed_id)[0][1] == 'true'
        wait_for(lambda: roster(member, group_id) == (1, {owner_id, member_id}), 'Migrated owner broadcasts corrected roster to existing ' + member_kind + ' member')
        member.send(group_id, 'after migration broadcast ' + suffix)
        wait_for(lambda: has(owner, 'after migration broadcast ' + suffix, 'Received'), 'Remaining devices exchange group messages after migration')
        owner.stop()
        member.stop()
    print('PASS: pre-M1 migration broadcasts its corrected roster to a real second device in both platform directions', flush=True)
finally:
    for process in processes:
        if process.poll() is None:
            process.kill()
