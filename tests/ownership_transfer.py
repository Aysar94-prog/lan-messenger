"""Group ownership transfer: the owner must hand off to another current active member before they can
leave a non-empty group, and their own departure only completes once every other active member has
actually caught up -- not just the incoming owner. See PLAN-GROUP-OWNERSHIP-TRANSFER.md for the design
this exercises, in particular why the old owner (not the new one) must stay the delivery/leave-acceptance
authority for the one in-flight change until full convergence. Cross-platform throughout."""
import pathlib, base64, time
exec(pathlib.Path(__file__).with_name('integration.py').read_text(encoding='utf-8').split('# Seed a real 0.2 snapshot')[0])

def b64s(s): return base64.b64encode(s.encode()).decode()
def groups(p): return [r[1] for r in p.state() if r[0]=='G']
def owner_of(p,gid):
    rows=p.command('OWNER\t'+gid)
    return rows[0][1] if rows else None
def pending_handoff(p,gid): return p.command('PENDINGHANDOFF\t'+gid)[0][1]=='true'
def roster(p,gid):
    rows=p.command('ROSTER\t'+gid)
    assert len(rows)==1 and rows[0][0]=='ROSTER',rows
    return int(rows[0][1]),set(rows[0][2].split(','))

try:
    a=Peer('java','Owner','127.0.0.60');b=Peer('cs','NewAdmin','127.0.0.61');c=Peer('java','Third','127.0.0.62')
    aid,bid,cid=a.id,b.id,c.id
    pair(a,b);pair(a,c);pair(b,c)
    gid=a.command('GROUP\t'+b64s('Handoff group')+'\t'+bid+','+cid)[0][1]
    wait_for(lambda:gid in groups(b) and gid in groups(c),'Owner+two-member group ready')

    # 1) Basic transfer: every member converges (owner field updates everywhere), the old owner's own
    #    departure completes automatically once everyone (not just the new owner) has caught up, and
    #    the new owner can then manage the group (add a fresh member) -- proving real authority moved.
    a.command('TRANSFEROWNER\t'+gid+'\t'+bid)
    wait_for(lambda:owner_of(b,gid)==bid,'New owner adopts itself as owner')
    wait_for(lambda:owner_of(c,gid)==bid,'Third member converges to the new owner too')
    wait_for(lambda:not pending_handoff(a,gid),'Old owner\'s deferred departure completes once everyone has caught up')
    wait_for(lambda:gid not in groups(a),'Old owner has actually left locally')
    d=Peer('java','Fourth','127.0.0.63');did=d.id;pair(b,d);pair(c,d)
    b.command('REINVITE\t'+gid+'\t'+did)
    wait_for(lambda:gid in groups(d),'The new owner can grow the group after taking over')
    print('PASS: a basic transfer converges everywhere and hands off real authority',flush=True)

    # 2) A second group: refuse the transfer outright if any current member cannot answer a fresh,
    #    live CAPS>=2 query -- nothing is mutated (owner unchanged, no pending handoff, no version
    #    bump) on refusal, mirroring AddMember's existing all-or-nothing capability check.
    gid2=a.command('GROUP\t'+b64s('Capability group')+'\t'+bid+','+cid)[0][1]
    wait_for(lambda:gid2 in groups(b) and gid2 in groups(c),'Second group invitation reaches both members')
    c.command('LEGACY\ttrue')
    versionBefore,_=roster(a,gid2)
    try:
        a.command('TRANSFEROWNER\t'+gid2+'\t'+bid)
        raise AssertionError('expected transfer to be refused while a member cannot answer CAPS>=2')
    except AssertionError as e:
        assert 'ERROR' in str(e),e
    assert owner_of(a,gid2)==aid,'Refused transfer must not change the owner'
    assert not pending_handoff(a,gid2),'Refused transfer must not start a pending handoff'
    assert roster(a,gid2)[0]==versionBefore,'Refused transfer must not bump the version'
    c.command('LEGACY\tfalse')
    print('PASS: a transfer is refused outright (nothing mutated) if any member cannot support it',flush=True)

    # 3) Refuse choosing someone who isn't a current member, or yourself.
    try:
        a.command('TRANSFEROWNER\t'+gid2+'\t'+aid)
        raise AssertionError('expected transferring to yourself to be refused')
    except AssertionError as e:
        assert 'ERROR' in str(e),e
    try:
        a.command('TRANSFEROWNER\t'+gid2+'\tnot-a-real-member-id')
        raise AssertionError('expected transferring to a non-member to be refused')
    except AssertionError as e:
        assert 'ERROR' in str(e),e
    print('PASS: transferring to yourself or a non-member is refused',flush=True)

    # 4) The pending handoff survives the old owner's own restart -- proving the durable O row (not
    #    just an in-memory flag) is what drives this, matching every other durable-intent record in
    #    this codebase (pendingLeaves, pendingJoinRequests before it).
    a.command('TRANSFEROWNER\t'+gid2+'\t'+bid)
    a.stop();a=Peer('java','Owner','127.0.0.60');assert a.id==aid
    wait_for(lambda:pending_handoff(a,gid2) or gid2 not in groups(a),'Pending handoff (or its completion) survives an old-owner restart')
    wait_for(lambda:owner_of(b,gid2)==bid,'New owner converges after the restart')
    wait_for(lambda:owner_of(c,gid2)==bid,'Remaining member converges after the restart')
    wait_for(lambda:gid2 not in groups(a),'Old owner completes its deferred departure after restarting')
    print('PASS: a pending handoff survives the old owner restarting and still completes',flush=True)

    # 5) A group where a member is still mid-onboarding (never acked anything yet) refuses a transfer
    #    -- the GROUP frame's sender-must-equal-claimed-owner invariant makes it impossible to deliver
    #    a first-time invite "on behalf of" a different owner during a pending handoff.
    gid3=a.command('GROUP\t'+b64s('Onboarding group')+'\t'+bid+','+cid)[0][1]
    wait_for(lambda:gid3 in groups(b) and gid3 in groups(c),'Third group invitation reaches both members')
    e=Peer('java','Fifth','127.0.0.64');eid=e.id;pair(a,e)
    a.command('REINVITE\t'+gid3+'\t'+eid)
    try:
        a.command('TRANSFEROWNER\t'+gid3+'\t'+bid)
        raise AssertionError('expected transfer to be refused while a member has never acked anything yet')
    except AssertionError as ex:
        assert 'ERROR' in str(ex),ex
    wait_for(lambda:gid3 in groups(e),'The still-onboarding member finishes joining afterward')
    a.command('TRANSFEROWNER\t'+gid3+'\t'+bid)
    wait_for(lambda:not pending_handoff(a,gid3),'The same transfer succeeds once everyone has finished onboarding')
    print('PASS: a transfer is refused while anyone is still mid-onboarding, and succeeds once they finish',flush=True)

    print('PASS: all ownership-transfer scenarios complete',flush=True)
finally:
    for p in processes:
        if p.poll() is None:p.kill()
