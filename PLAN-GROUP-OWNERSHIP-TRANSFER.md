# Plan: group owner must hand off before leaving

## Revision note

The first draft of this plan (below is the corrected version) proposed that the old owner could stop
tracking a group the moment it broadcast the ownership change, on the theory that the new owner would
then take over re-delivery to anyone still lagging. That was wrong, on external review, twice over:

1. **The broadcast might never even go out.** `Deliver()` only pushes `MEMBERSUPDATE` for a group where
   `g.Owner==Id` on the *sending* device. If the transfer flips the old owner's own local `Group.Owner`
   immediately, its own delivery loop stops treating that group as its responsibility on the very next
   cycle — possibly before anyone (least of all someone offline at that instant) ever receives it.
2. **The new owner cannot rescue a lagging member either.** `HandleMembersUpdate`/`handleMembersUpdate`'s
   entire authorization model is "does this frame's sender match the receiver's *own currently stored*
   owner?" (`windows/Conversations.cs`, `if(old.Owner!=sender...)return;`). A lagging member's stored
   owner is still the *old* one. A `MEMBERSUPDATE` sent by the *new* owner fails that check on their
   machine, forever — the new owner's own ack does not transfer trust to anyone else. The same is true of
   `GROUP` (used for a member who's never acked anything yet): `AcceptGroup`/`acceptGroup` additionally
   requires the frame's claimed owner field to equal the connecting sender's own identity
   (`a[3]!=sender` throws) — so nobody can ever deliver "on behalf of" a different claimed owner, even for
   a first-time invite.

The corrected design below keeps the *old* owner as the sole authority for this one broadcast until
every other current active member has actually caught up — reusing the exact retry-until-acked mechanism
that already makes ordinary membership changes converge, rather than inventing a new trust mechanism.
Getting this right required tracing the actual authorization checks in `Conversations.cs`/`PeerEngine.cs`
line by line; the reasoning below cites the specific checks it depends on so it can be verified against
the real source, not just read as prose.

## Goal

Today the group owner is fixed forever (documented, explicit non-goal in `PLAN-GROUP-MEMBERSHIP.md`) —
and the UI doesn't actually stop them from clicking "Leave group" on their own group. If they do, the
group silently orphans for everyone else: the leave notice is queued to be sent *to the owner*, which is
themselves, so it never goes anywhere; nobody else is ever told; the departed owner stays listed as
member/owner forever; and since only the owner can add/remove members, the group is permanently frozen
for everyone remaining (ordinary messaging between the remaining members still works — it's the roster
that's stuck).

Fix: **before an owner can leave a group with any other active member in it, they must hand off ownership
to one of those members first**, and **the owner's own departure does not complete until every other
current active member has actually caught up to the handoff** — a single-member (owner-only) group has
nobody to hand off to, so leaving it stays exactly as simple as it is today (a local delete).

## Design

### Why "wait for the new owner only" is not enough, and what actually works

The only way a receiver ever accepts new group data is by trusting the *sender's identity* against its
*own already-stored* owner. There is no signature chain, no way for a third party (including the new
owner) to vouch for a change on the old owner's behalf. That means the **old owner is the only party who
can ever deliver this specific change**, to anyone who hasn't received it yet — exactly as true for an
ownership handoff as it already is for an ordinary roster change today. The fix is not a new trust
mechanism; it's making sure the old owner's engine keeps doing exactly what it already does (retry
delivery every cycle to everyone still behind) for as long as it takes, and only completing the owner's
own local departure once that's actually finished.

Concretely, this means the *old owner's own local bookkeeping* needs to separate two things that are
currently the same field:

- **What the wire says the owner is** (`Group.Owner`) — this should update immediately, everywhere,
  including the old owner's own copy, the moment the transfer is initiated. Every other receiver adopts
  this the normal way (accept the snapshot, store the new owner) the instant they receive+ack it — nothing
  new needed there.
- **Whether this device is still the one obligated to keep delivering, and to keep accepting a
  still-lagging member's `LEAVE`, for this one group** — this needs to stay true on the old owner's device
  until every other current active member has acked, *even though* `Group.Owner` no longer says it's the
  owner. This is tracked separately: a small persisted set,
  `pendingOwnershipHandoff` (a `HashSet<string>`/`Set<String>` of group ids, same durability pattern as
  `pendingLeaves`), the group id is added at the moment of transfer and removed once convergence is
  detected.

Three call sites need to check `pendingOwnershipHandoff`, not just `g.Owner==Id`, while a group is in it:

1. **`Deliver()`/`deliver()`'s broadcast-loop filter** — currently
   `Groups.Where(g=>g.Owner==Id&&g.Members.Contains(peer.Id)&&MemberAckedVersion(g.Id,peer.Id)<g.MembersVersion)`
   (`windows/PeerEngine.cs`). Widen to
   `(g.Owner==Id||pendingOwnershipHandoff.Contains(g.Id))&&...` — otherwise the old owner's own engine
   stops trying to deliver the transfer to laggards the instant it's initiated, per problem 1 above.
2. **`HandleLeave`/`handleLeave`'s acceptance check** — currently
   `if(!groups.TryGetValue(groupId,out var g)||g.Owner!=Id||!g.Members.Contains(memberId))return;`. If a
   member who hasn't yet caught up to the transfer sends `LEAVE` (they still think the old owner is the
   owner, so that's who they contact), the old owner needs to still accept and process it — otherwise that
   departure is silently dropped. Widen the reject condition to
   `g.Owner!=Id && !pendingOwnershipHandoff.Contains(groupId)`.
3. **Nothing else is loosened.** `AddMember`/`addMember` and `ReinviteMember`/`reinviteMember` keep their
   exact existing `g.Owner!=Id` check unchanged — the old owner must *not* be able to initiate any *new*
   growth once they've handed off, only finish delivering the one change already in flight and accept
   the leaves it unblocks.

### A required precondition, to avoid a real dead end

A member who has never acked *anything* for this group yet (`MemberAckedVersion < 0`, i.e. still waiting
on their very first invite) can only ever be reached via a `GROUP` frame, never `MEMBERSUPDATE`
(`Deliver()`'s `neverAcked` branch). `AcceptGroup`/`acceptGroup` requires the frame's claimed owner field
to equal the actually-connecting sender's identity (`a[3]!=sender` throws, unconditionally, new record or
not) — so there is no way to deliver a first-time invite "on behalf of" a different owner than whoever is
connecting. If a transfer were initiated while such a member exists, the old owner could never correctly
deliver their first invite for the rest of the pending window (they'd either have to keep lying about the
owner, which the wire format rejects, or never send it at all).

**Fix**: `TransferOwnership`/`transferOwnership` refuses (clear error, nothing mutated) if any current
active member other than the caller has `MemberAckedVersion < 0`. This is a narrow, easy-to-explain
precondition ("finish onboarding everyone before handing off") rather than a protocol change.

### Wire

- `MEMBERSUPDATE` gains a 6th field: `LM4\tMEMBERSUPDATE\t{groupId}\t{version}\t{membersCsv}\t{ownerId}`.
  Dispatch accepts 5 or 6 fields (5 = owner unchanged, defaults to the receiver's own currently-stored
  owner) — mirrors the existing 6-or-7-field pattern already used for `GROUP`. `HandleMembersUpdate`'s
  authorization check is unchanged in shape (`sender == receiver's own currently-recorded Owner`); a
  successful adopt additionally updates the locally-stored `Owner` field when the 6th field is present.
  Confirmed from `windows/PeerEngine.cs`: the `MEMBERSUPDATEACK` reply is written unconditionally after
  `HandleMembersUpdate` returns (whether it silently no-oped due to a stale/mismatched sender or actually
  adopted new data) — only a thrown exception (malformed data) skips the ack. This matters for the
  redelivery case: a receiver who has *already* adopted the transfer will reject a *redundant* resend of
  the same version from the old owner's identity (their stored owner has already moved on), but still acks
  it, so the old owner correctly learns "this peer is caught up" and stops retrying — no separate fix
  needed for that case.
- `CAPS`'s reply version bumps from `1` to `2` for a build that supports the 6-field `MEMBERSUPDATE`.
  `TransferOwnership` requires a **fresh** `CAPS >= 2` from every current active member (not just the
  incoming owner) before proceeding, mirroring `AddMember`'s existing "check everyone who'd be affected,
  live, every time" philosophy. A group that's never had an ownership transfer keeps working with old
  builds exactly as today; only a group where a transfer has actually happened requires every device in it
  to be current — same shape as the existing `GROUP`/`MEMBERSUPDATE` version-0-vs-mutated distinction.

### Engine

- New op, owner-only: `TransferOwnership(groupId, newOwnerId)` / `transferOwnership(e, groupId, newOwnerId)`.
  - `newOwnerId` must be a currently active member, and not the caller.
  - Refuses if any current active member other than the caller has never acked anything yet (see
    precondition above).
  - Fresh live `CAPS >= 2` check of every current active member (refuses outright, clear error, mutates
    nothing if anyone fails).
  - Atomically: bumps `MembersVersion` by 1, sets `Group.Owner = newOwnerId` (this is what gets broadcast
    and what every other receiver adopts), and adds `groupId` to the persisted `pendingOwnershipHandoff`
    set — durable so a restart mid-handoff resumes correctly.
  - No change to `Members` itself — this is purely who's the owner, not who's in the group.
- Convergence check: after each successful `MEMBERSUPDATEACK` is recorded in `Deliver()`/`deliver()` for a
  group that's in `pendingOwnershipHandoff` (or on a lightweight periodic pass, e.g. in `TimerLoop`), test
  whether every current active member other than the caller now has
  `MemberAckedVersion >= the transfer's version`. Once true: remove the group from
  `pendingOwnershipHandoff`, then run the *existing* `DeleteConversation`/`deleteConversation` leave logic
  unchanged — it already reads `oldGroup.Owner` to address the `LEAVE` notice, which by now correctly
  reads `newOwnerId` (updated at transfer time), so no changes are needed there.
- `HandleLeave`/`handleLeave`: widen the reject condition as described above
  (`g.Owner!=Id && !pendingOwnershipHandoff.Contains(groupId)`), so a still-lagging member's departure is
  correctly processed during the pending window. No other change — the removed member still goes through
  the same roster-shrink-and-broadcast path as any other leave.
- Storage: one new persisted row, e.g. `O\t{groupId}` per pending handoff (mirrors the shape of existing
  single-field rows like `F`/forgotten); loaded into `pendingOwnershipHandoff` at startup so a restart
  mid-handoff resumes delivering and re-checking convergence exactly where it left off.

### UI

- "Leave group" (the conversation-list action and the in-chat action, both platforms) checks: is the local
  user the owner, and are there other active members?
  - **No other active members**: behaves exactly as today — a plain local leave/delete, nothing to hand
    off.
  - **Other active members exist**: instead of the normal confirm dialog, show a "Choose a new admin
    before you leave" picker listing the other active members who have already acked at least once
    (excluding anyone still mid-onboarding, matching the engine precondition, with a clear explanation if
    that's the only reason the list is short/empty). Confirm calls `TransferOwnership`. On failure (capability
    check or the onboarding precondition), show the existing kind of clear error; nothing has changed, the
    owner is still the owner and can retry or pick someone else.
  - **While a group is in `pendingOwnershipHandoff`**: show it as "Leaving — waiting for {name(s)} to catch
    up" instead of a normal chat (composer disabled, no new sends), durable across restarts, and completing
    automatically (no dialog needs to stay open) once every other member has acked.
- Members dialog / `showMembers()`: no structural change — the "Admin" tag already reflects whoever
  `Group.Owner` currently is, so it updates correctly the moment the transfer's snapshot is adopted, for
  every receiver including the old owner's own (now historical, no-longer-authoritative-for-new-changes)
  copy.

### Accepted limitation

If a member never comes back online again after a transfer starts (device lost, app uninstalled, without
ever having sent `LEAVE` first), the old owner's departure can never complete — there is no third party
who can ever vouch for the handoff to that member, by design (see "why wait for the new owner only is not
enough" above). This is the same category of accepted limitation as "an old build can't be taught to parse
a new frame after the fact," not a new kind of risk, but it is a real, indefinite wait in the worst case.
A future, separate enhancement — a transfer statement signed by the old owner
(`identity.Sign`/`SecureIdentity.Sign`, the same mechanism already used to let any member relay a message
on behalf of an offline original sender via `SYNCREQ2`/`META`) that any member could relay and a lagging
member could verify against the old owner's already-on-file public key — would remove this limitation, but
is out of scope for this pass; do not build it speculatively.

## Tasks

| ID | Platform | Task | Depends on | Status |
|----|----------|------|------------|--------|
| T1 | Windows | `TransferOwnership` (onboarding precondition + fresh `CAPS>=2` check + atomic version/owner/pending-set update); `MEMBERSUPDATE` 6th field (send + `HandleMembersUpdate` accept 5-or-6 + adopt owner field); `CAPS` reply bump to `2`; widen `Deliver()`'s broadcast filter and `HandleLeave`'s accept check for `pendingOwnershipHandoff`; convergence check + deferred leave; new persisted `O`/`T` rows | — | **Done** |
| T2 | Android | Mirror T1 in `GroupSync.java`/`PeerEngine.java` (`Group.owner` had to become mutable, non-final, to support this) | — | **Done** |
| T3 | Windows | UI: intercept "Leave group" (list action + in-chat action) for the owner when other active members exist; new "Choose a new admin" picker dialog; "Leaving — waiting for members to catch up" durable state (composer/send/leave-button disabled, conversation-list row and heading both reflect it); wire to `TransferOwnership` | T1 | **Done** |
| T4 | Android | Mirror T3 in `MainActivity.java` (`confirmDeleteConversation`'s call sites, `chatMenu`, the people-list row and open-chat heading) | T2 | **Done** |
| T5 | Both | New `tests/ownership_transfer.py`: a basic transfer converges everywhere (owner field updates on every member, not just the new owner) and the old owner's own departure completes automatically once everyone has caught up, after which the new owner can grow the group; a transfer is refused outright (owner unchanged, no pending handoff, no version bump) if any current member can't answer a fresh, live `CAPS>=2`; transferring to yourself or a non-member is refused; the pending handoff (and its durable `O` row) survives the old owner's own restart and still completes afterward; a transfer is refused while any member is still mid-onboarding (never acked anything) and succeeds once they finish. **Not built, deliberately scoped out**: a still-lagging member's `LEAVE` arriving *during* the pending window specifically (the `HandleLeave` widening) has no dedicated test — reliably forcing that exact race with this harness's process-level granularity would need an artificial mid-flight pause the harness doesn't support; the surrounding convergence/durability scenarios already exercise the same code path indirectly (every member's `LEAVE`/`MEMBERSUPDATE` traffic during a live transfer goes through it), so this is a coverage gap noted rather than silently skipped, not a correctness gap. | T1–T4 | **Done** |
| T6 | Both | Update `PLAN-GROUP-MEMBERSHIP.md` and the status docs | T1–T5 | **Done** |

## Acceptance criteria

- The owner can never leave a group that has other active members without first successfully
  initiating a transfer to one of them.
- The owner's own local departure never completes until every other current active member has acked the
  transfer, however long that takes — verified by the old owner's engine still correctly delivering and
  still correctly accepting a lagging member's `LEAVE`, for this one group, throughout.
- An owner-only group (no other active members) leaves exactly as it does today — no picker, no transfer.
- A transfer requires a fresh, live `CAPS>=2` check of every current active member, and refuses if anyone
  hasn't yet acked their first invite; either refusal leaves everything unchanged (owner, members, nothing
  broadcast).
- A group that's never had an ownership transfer is completely unaffected for old, not-yet-updated builds
  — same as today's `GROUP`/`MEMBERSUPDATE` version-0-vs-mutated split. Only a group that's actually had a
  transfer requires every device in it to be current.
- The old owner cannot initiate any *new* membership growth once a transfer has been initiated (only the
  in-flight handoff itself, and accepting leaves it unblocks, continue) — `AddMember`/`ReinviteMember`
  remain strictly gated on `g.Owner==Id`, unchanged.
- Full `tests/run.ps1` stays green throughout.

## Verification

Windows build + Android build + full `tests/run.ps1` after T1/T2, then again after T3/T4, then after T5.
Commit locally only (no push), per the standing instruction.
