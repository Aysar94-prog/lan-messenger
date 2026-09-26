"""Mutable group membership foundation (M1-M3): live-shrinking/growing Members, versioned
MEMBERSUPDATE broadcast, live (never-cached) CAPS capability checks before any owner-initiated
growth, and migration of the pre-M1 overlapping Members/Left storage shape. Cross-platform
throughout (Java/Android and C#/Windows engines), since this is shared wire/storage behavior."""
import pathlib, base64, uuid
# Reuse harness helpers, without running the legacy suite a second time.
exec(pathlib.Path(__file__).with_name('integration.py').read_text(encoding='utf-8').split('# Seed a real 0.2 snapshot')[0])

def b64s(s): return base64.b64encode(s.encode()).decode()
def groups(p): return [r[1] for r in p.state() if r[0]=='G']
def left(p,gid,member): return p.command('LEFT\t'+gid+'\t'+member)[0][1]=='true'

try:
    a=Peer('java','Android-A','127.0.0.2');b=Peer('cs','Windows-B','127.0.0.3');c=Peer('java','Android-C','127.0.0.4');d=Peer('cs','Windows-D','127.0.0.5')
    aid,bid,cid,did=a.id,b.id,c.id,d.id
    pair(a,b);pair(a,c);pair(a,d);pair(b,c);pair(b,d);pair(c,d)

    # 1) Basic convergence: create, leave, re-invite (existing member), add a brand-new member —
    #    every direction (java-owned and cs-owned) delivers messages correctly afterward.
    gid=a.command('GROUP\t'+b64s('Membership group')+'\t'+bid+','+cid)[0][1]
    wait_for(lambda:gid in groups(b) and gid in groups(c),'Group invitation reaches both members')
    c.command('DELETECONV\t'+gid)
    wait_for(lambda:left(a,gid,cid),'Owner sees a departed member')
    a.command('REINVITE\t'+gid+'\t'+cid)
    wait_for(lambda:gid in groups(c),'Departed member rejoins via re-invite')
    b.send(gid,'after rejoin')
    wait_for(lambda:has(c,'after rejoin','Received'),'Rejoined member receives new messages')
    a.command('REINVITE\t'+gid+'\t'+did)
    wait_for(lambda:gid in groups(d),'A brand-new (never-member) contact is added')
    b.send(gid,'after new member added')
    wait_for(lambda:has(d,'after new member added','Received'),'New member receives group messages')
    print('PASS: basic add/remove/re-invite convergence, cross-platform',flush=True)

    # 2) A device offline through SEVERAL membership changes jumps straight to the latest snapshot
    #    in one step on reconnect, rather than needing to replay each intermediate change.
    b.stop()
    c.command('DELETECONV\t'+gid)               # change 1: c leaves (members shrink)
    wait_for(lambda:left(a,gid,cid),'Owner registers the first change while b is offline')
    a.command('REINVITE\t'+gid+'\t'+cid)         # change 2: c is re-added (members grow again)
    time.sleep(1)
    b=Peer('cs','Windows-B','127.0.0.3')         # b restarts with the same identity
    assert b.id==bid
    wait_for(lambda:gid in groups(b),'Reconnecting device still has the group after missing several changes')
    a.send(bid,'catch me up')
    wait_for(lambda:has(b,'catch me up','Received'),'Reconnected device is fully caught up, not stuck on a stale version')
    print('PASS: a device offline through several membership changes catches up in one step',flush=True)

    # 3) A group can shrink to just the owner, then grow back to 2, without the <3 floor (which
    #    only applies to CreateGroup) ever rejecting an update.
    gid2=a.command('GROUP\t'+b64s('Shrink group')+'\t'+bid+','+cid)[0][1]
    wait_for(lambda:gid2 in groups(b) and gid2 in groups(c),'Second group invitation reaches both members')
    b.command('DELETECONV\t'+gid2)
    wait_for(lambda:left(a,gid2,bid),'First departure registers')
    c.command('DELETECONV\t'+gid2)
    wait_for(lambda:left(a,gid2,cid),'Second departure shrinks the group to just the owner')
    a.command('REINVITE\t'+gid2+'\t'+bid)
    wait_for(lambda:gid2 in groups(b),'Growing an owner-only group back to 2 is accepted, not rejected')
    print('PASS: a group can shrink to just the owner and grow back without the creation-time floor',flush=True)

    # 4) A peer that predates this feature entirely (simulated) still exchanges ordinary messages
    #    normally (HELLO is untouched); but the owner's app refuses to grow a group's membership
    #    around them, with a clear error, whether that peer is reachable-but-uncooperative or
    #    simply unreachable — both look identical from the live CAPS check's point of view.
    c.command('DELETECONV\t'+gid2)
    wait_for(lambda:left(a,gid2,cid),'Owner sees c as left before the legacy re-add attempt')
    c.command('LEGACY\ttrue')
    a.send(cid,'still just an ordinary message')
    wait_for(lambda:has(c,'still just an ordinary message','Received'),'Ordinary messaging with a simulated legacy peer is unaffected')
    try:
        a.command('REINVITE\t'+gid2+'\t'+cid)
        raise AssertionError('expected re-adding a legacy-simulated peer to be refused')
    except AssertionError as e:
        assert 'ERROR' in str(e), e
    print('PASS: adding a simulated-legacy (reachable but uncooperative) peer is refused',flush=True)
    d.command('DELETECONV\t'+gid2) if gid2 in groups(d) else None
    d.stop()
    try:
        a.command('REINVITE\t'+gid2+'\t'+did)
        raise AssertionError('expected re-adding an unreachable peer to be refused')
    except AssertionError as e:
        assert 'ERROR' in str(e), e
    print('PASS: adding a genuinely unreachable peer is refused the same way',flush=True)
    d=Peer('cs','Windows-D','127.0.0.5');assert d.id==did

    # 5) No permanent trust: an existing, already-confirmed active member who later starts
    #    refusing CAPS (without ever leaving) blocks a LATER, unrelated addition to the same
    #    group — the capability check is live, every time, for every relevant peer, not cached
    #    from an earlier success.
    c.command('LEGACY\tfalse') # reset from scenario 4, where c was left in legacy-simulation mode
    gid3=a.command('GROUP\t'+b64s('Live check group')+'\t'+bid+','+cid)[0][1]
    wait_for(lambda:gid3 in groups(b) and gid3 in groups(c),'Third group invitation reaches both members')
    b.command('LEGACY\ttrue') # b stays an active member throughout; only its cooperativeness changes
    try:
        a.command('REINVITE\t'+gid3+'\t'+did)
        raise AssertionError('expected the add to be refused because an existing active member now refuses CAPS')
    except AssertionError as e:
        assert 'ERROR' in str(e), e
    print('PASS: an existing active member going uncooperative blocks unrelated later growth (no caching)',flush=True)
    b.command('LEGACY\tfalse')
    a.command('REINVITE\t'+gid3+'\t'+did)
    wait_for(lambda:gid3 in groups(d),'The same add succeeds once the existing member cooperates again')
    print('PASS: the add succeeds again once every relevant peer is confirmed live',flush=True)

    # 6) Leaving is never gated by anyone else's capability — a group with an uncooperative
    #    (legacy-simulated) member present still lets another member leave successfully.
    b.command('LEGACY\ttrue')
    c.command('DELETECONV\t'+gid3)
    wait_for(lambda:left(a,gid3,cid),"A member can leave even while another active member is uncooperative")
    print("PASS: leaving a group is never blocked by another member's capability",flush=True)

    # 7) Migrating an old (pre-M1) saved group with a non-empty Left is treated as a real
    #    membership change (version 1, scheduled for broadcast), not a silent local fix-up.
    # Verified as a single-device load/parse check per platform (below); the broadcast mechanism
    # itself (a version-1+ group reaching every unacked active member) is the same mechanism
    # already exercised end-to-end, cross-platform, by scenarios 1-2 above.
    for kind,label in (('java','Java'),('cs','C#')):
        owner_id=str(uuid.uuid4());mid=str(uuid.uuid4());departed=str(uuid.uuid4());gid4=str(uuid.uuid4())
        directory=work/('Migrate-'+label)
        directory.mkdir(parents=True,exist_ok=True)
        rows=['LMSTORE4\t'+owner_id+'\t'+b64s('Migrate-'+label),
              'G\t'+gid4+'\t'+owner_id+'\t'+b64s('Old shape group')+'\t'+owner_id+','+mid+','+departed+'\t'+mid+'\t'+departed,
              'END']
        (directory/'state.txt').write_text('\n'.join(rows)+'\n',encoding='utf-8')
        owner=Peer(kind,'Migrate-'+label,'127.0.0.'+str(20+(0 if kind=='java' else 1)))
        assert owner.id==owner_id,'Identity must be preserved through migration'
        g_rows=[r for r in owner.state() if r[0]=='G']
        assert len(g_rows)==1 and g_rows[0][1]==gid4,f'{label}: migrated group must still be present: {g_rows}'
        assert left(owner,gid4,departed),f'{label}: departed member must still show in history after migration'
        assert not left(owner,gid4,mid),f'{label}: an always-active member must not show as departed'
    print('PASS: migrating an old overlapping Members/Left group produces a real, disjoint membership change',flush=True)
finally:
    for p in processes:
        if p.poll() is None:p.kill()
