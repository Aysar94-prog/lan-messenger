# Plan: mutable group membership (foundation)

## History note

This design originally also covered a join-request feature (any verified contact of a group's owner
could ask to join, with an owner accept/ignore queue) built on top of this foundation. That feature was
built, tested, and shipped in release 2.0.0, then removed by explicit user request shortly after — the
owner can still add someone to a group directly (`AddMember`/`addMember`, used by Re-invite), but there is
no self-service "request to join" path any more. This document has been trimmed to the foundation that
remains: the live, mutable membership model itself. The removed feature's design and task history is not
preserved elsewhere; see the commit that removed it for the exact diff.

## Goal

Group membership is no longer fixed after creation. The owner can add a verified contact to an existing
group at any time (not just at creation), and a departed member can be brought back ("Re-invite"). Every
membership change propagates to every active member as a versioned snapshot, so a device that was offline
through several changes catches up in one step rather than replaying each one. Any growth requires a
**fresh, live capability check** of everyone who'd be in the group afterward — a past success is never
trusted as durable, since a device could have been downgraded, reinstalled, or restored from a backup
since. Leaving a group is never gated by this.

Ownership itself does not change: the owner is fixed at creation, never transfers, has no "leave" of
their own, and there is no second admin. Ending a group entirely ("delete group") remains out of scope —
a separate, undesigned future feature. (Leaving a group as a non-owner member is a separate, already
existing action, labeled "Leave group" in the UI, not "delete conversation".)

## Design

- **`Members` becomes the single source of truth for who's active, and it actually changes now.** Adding
  someone appends to it; someone leaving removes them from it.
- **The `<3` floor lives only in `CreateGroup`, and nowhere else — not even "first invite."** It's a
  client-side-only UX nicety at the moment of creation (don't let someone create a pointless 2-person
  "group"). `AcceptGroup`/`acceptGroup`'s *receiving-side* validation drops the `<3` check entirely, for
  every kind of incoming list — a brand-new invite into a group that's shrunk to just the owner is
  legitimately 2 people, and treating that invite as a "creation" would wrongly reject it. This isn't a new
  gap: the existing `members.Contains(Id) && members.Contains(sender)` + distinctness checks already
  structurally guarantee at least 2 entries on their own, so no explicit numeric floor is needed there at
  all. The only remaining receiving-side check is the `>16` ceiling, applied identically whether it's a
  first invite or a later `MEMBERSUPDATE`.
- **Migrating already-saved groups**: an older on-disk `Group` row shape could overlap — a departed member
  sitting in both `Members` and `Left` at once. On first load under this model, each such stored group is
  migrated once: the live `Members` becomes `oldMembers − oldLeft`, and `oldLeft`'s contents seed the
  separate departed-history record unchanged — then the migrated result is saved so this only runs once
  per group, not on every load. **If `oldLeft` was non-empty, this migration is itself a real membership
  change, not a no-op** — the remaining active members' own stored copies still show the departed person
  as active too, since `Left` was owner-only and never propagated before this feature existed. The rule:
  if `oldLeft` was empty, migration is a genuine no-op and version stays 0; if `oldLeft` was non-empty, the
  migrated group gets version 1 and is scheduled for a normal `MEMBERSUPDATE` broadcast to its now-active
  members, exactly like any other mutation, so everyone still holding the old, bloated list eventually
  converges to the corrected one.
- **`Left`/departed-history stays owner-only, local, never sent over the wire.** Its role is purely
  historical bookkeeping for the owner's own Members-dialog/Re-invite UI, and it is disjoint from
  `Members` rather than overlapping it: once someone leaves, they're removed from the propagated
  `Members` and recorded in this separate history, instead of staying in `Members` with a flag next to
  their name.
- **`Group.MembersVersion`**: a plain monotonic integer per group, starting at 0 at creation, incremented
  by exactly 1 by the owner on every membership change (add or remove), regardless of cause.
- **One frame for every membership change, add or remove**: `LM4\tMEMBERSUPDATE\t{groupId}\t{version}\t{membersCsv}`
  — a full snapshot, not a delta. The owner sends it to every currently-active member whenever `Members`
  changes for any reason (direct add, Re-invite, or someone leaving). Reply
  `LM4\tMEMBERSUPDATEACK\t{groupId}\t{version}`.
- **Adoption rule**: a receiving member applies an incoming snapshot only if `version > their stored
  version` — strictly greater, no gap-checking, no ordering requirement. A device that missed five changes
  while offline just adopts the sixth (current) snapshot directly, replacing its entire local `Members`
  array and updating its stored version in one step.
- **The `GROUP` invite only carries the version field once the group actually has one to carry.** The
  owner sends the plain 6-field frame (the original wire shape) whenever `MembersVersion == 0` — i.e. this
  group has never been changed since creation — and only switches to the 7-field form once
  `MembersVersion >= 1`. The receiving dispatch accepts 6 *or* 7 fields either way, so a not-yet-updated
  build receiving a new build's still-unmutated-group invite is genuinely, fully compatible.
  **What remains genuinely one-directional**: once a group's version reaches 1 (it has actually been
  mutated at least once), its `GROUP`/`MEMBERSUPDATE` traffic requires every device in *that specific
  group* to be running this feature's release — an already-built old binary's dispatch cannot be taught,
  after the fact, to parse a 7-field frame.
- **Leave notice reaching everyone else (not just the owner)**: unchanged on the leaver's side — they
  still only send `LEAVE` to the owner. What changes is the owner's handling: on receipt, the owner
  removes the leaver from `Members`, records them in the separate history, bumps the version, and
  broadcasts the smaller snapshot to everyone still active. That broadcast — not a direct notice from the
  leaver — is how the rest of the group finds out.
- **`AddMember(groupId, memberId)` is the one real mutation**: adds to `Members` if there's room and
  they're not already present, bumps the version, and broadcasts. Re-invite calls this and nothing else.
- **Capability check before any owner-initiated growth** — not just a documented requirement, an enforced
  one. `HELLO` itself is **not touched** — `validHello`/`ValidHello` require exactly 5 fields, and `HELLO`
  is the very first exchange for *every* connection (discovery, direct connect, messaging, file transfer);
  adding a field there would make an old build silently drop all contact with a new one, for everything,
  not just groups. The actual mechanism mirrors the existing `FILECAPS` pattern: a capability query sent
  only *after* the ordinary HELLO+trust handshake has already succeeded — `LM4\tCAPS` → reply
  `LM4\tCAPS\t{version}`. An old build that's never heard of `CAPS` falls through the existing final
  catch-all (does nothing, closes cleanly).
  `AddMember` triggers this query **fresh, every time, for every relevant peer** — every current active
  member, plus whoever's being added — with no shortcut for "already confirmed before." A valid
  `{version} >= 1` reply on *this* attempt lets the action proceed; anything else — no reply, connection
  failure, garbage, or a version below 1, for *any* one of the relevant peers — refuses the whole action
  outright (clear error, nothing mutated). This never blocks processing an incoming `LEAVE` — that member
  has already left regardless of what the owner's app can confirm about anyone else.
- **A departed (or never-a-member) recipient safely ignores stray/late traffic for a group.** A member who
  left has already deleted their own local group record entirely; `groups.TryGetValue`/lookup fails, so
  `AllowedGroup`/`allowedGroup` already rejects any late `MSG`/`OFFER` for that group, exactly like today's
  existing "unknown group" rejection. `MEMBERSUPDATE` follows the same rule explicitly: it only ever
  applies to a group the recipient already has a local record for (it updates, never creates) — a stray or
  late `MEMBERSUPDATE` for a group the recipient has no record of is simply ignored. Only a `GROUP` frame
  can ever establish a brand-new local record.
- **Old builds**: one that entirely predates this feature and so doesn't send/understand `MEMBERSUPDATE`
  at all simply never acks it; the owner's per-member version tracking just keeps retrying.

### Storage

- `Group` gains `MembersVersion` (int). Owner additionally tracks, per active member, the last version
  they've acked (a small `Dictionary<string,int>`/`HashMap<String,Integer>`) so `Deliver()` knows exactly
  who still needs the current snapshot.
- Owner's separate departed-history record is owner-only, never serialized onto the wire.

## Tasks

| ID | Platform | Task | Status |
|----|----------|------|--------|
| M1 | Windows | Foundation: `Members` becomes the live, shrinking/growing active roster; `Group.MembersVersion`; owner-only `departedHistory`/`memberAcked` bookkeeping; load-time migration with the version-1-if-`oldLeft`-non-empty rule; `HandleLeave`, `AcceptGroup` (6-or-7-field `GROUP`), `HandleMembersUpdate`, `LM4\tCAPS` query, `AddMember` with a fresh, never-cached capability check; test-only `SimulateLegacyBuild` toggle; `ShowMembers`/`ReinviteMember` updated to the `AllKnownMembers` API and made async | **Done** |
| M2 | Android | Mirror M1 in `PeerEngine.java`/`GroupSync.java` | **Done** |
| M3 | Both | `tests/group_membership.py`: convergence on add/remove/re-invite; offline catch-up; shrink-to-owner-then-regrow; live capability checks; leave without capability gating; single-device migration; migration-triggered broadcast (`tests/group_migration_broadcast.py`); the 16-member cap enforced against the live roster. Full `tests/run.ps1` passes. | **Done** |

## Acceptance criteria

- Every active member that is reachable, or that eventually reconnects to the owner, converges to the
  latest snapshot regardless of how many changes it missed while unreachable.
- `Left`/departed-history is never sent over the wire; only the current active `Members` and its version
  are.
- A group can shrink to just the owner, and later grow back from 2 members upward, without being
  rejected at any step.
- A group that has never been mutated (`MembersVersion == 0`) sends and receives a plain 6-field `GROUP`
  invite, unchanged from the original wire shape.
- The owner's app actively refuses to grow a group's membership (direct add or Re-invite) unless every
  person who would end up in it answers a **fresh** `CAPS` query, at that exact moment, confirming
  support.
- Leaving a group is never blocked by anyone else's capability.
- An already-saved group with an old overlapping `Members`/`Left` shape migrates correctly on first load,
  exactly once, and the Members dialog still shows (and can Re-invite) anyone who had already departed.
- A legacy 6-field `GROUP` frame (no version field) is still accepted correctly, treated as version 0.
- A departed member, or someone who was never a member, safely ignores a stray or late `MEMBERSUPDATE`.
- The 16-member cap (owner included) is enforced against the live active roster.
- Full `tests/run.ps1` stays green throughout.
