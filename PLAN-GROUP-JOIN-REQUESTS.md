# Plan: mutable group membership (foundation), then join requests with owner accept/ignore

## Goal (final, confirmed with the user)

Beyond the existing owner-initiated "Re-invite" (a departed member the owner picks from the Members
dialog), any verified contact of a group's **owner** — whether a past member or a total outsider — can
send a request to join, if they already know the group's ID (shared out of band; there is no group
discovery). Requests sit in a per-group queue the owner reviews with Accept / Ignore. Both Re-invite and
Accept resolve through the exact same underlying "add this person to the group" action.

Ownership itself does not change: the owner is fixed at creation, never transfers, has no "leave" of
their own, and there is no second admin. Ending a group entirely ("delete group") is explicitly **out of
scope** for this plan — a separate, undesigned future feature.

**This plan has a foundation layer (M-tasks) that must land before the join-request feature itself
(G-tasks).** Group membership is currently immutable after creation: `AcceptGroup`/`acceptGroup` reject
any incoming `GROUP` frame whose member list doesn't match byte-for-byte what a member already has
(`windows/Conversations.cs`, `GroupSync.java`), and nothing today tells existing members when someone
leaves — only the owner learns that, via the existing `LEAVE` frame. Both Re-invite and join-request
acceptance need to actually add someone to a *live* group whose other members already have it, which the
current code cannot do at all. The foundation below fixes that first, independent of join requests.

## Design

### Membership foundation (must land first)

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
- **Migrating already-saved groups**: today's on-disk `Group` rows overlap — a departed member sits in
  both `Members` and `Left` at once (this session's already-shipped forget-notice/leave behavior). On
  first load under the new model, each stored group is migrated once: the live `Members` becomes
  `oldMembers − oldLeft`, and `oldLeft`'s contents seed the new separate departed-history record
  unchanged — then the migrated result is saved so this only runs once per group, not on every load.
  Skipping this would leave every already-departed member looking like a current active member again.
  **If `oldLeft` was non-empty, this migration is itself a real membership change, not a no-op** — the
  remaining active members' own stored copies still show the departed person as active too, since `Left`
  was owner-only and never propagated before this feature existed. Leaving the migrated group's version at
  0 would be wrong: version-0 groups never get broadcast (they use the plain, no-propagation 6-field
  path), so those other devices would never learn the roster shrank. The rule: if `oldLeft` was empty,
  migration is a genuine no-op and version stays 0; if `oldLeft` was non-empty, the migrated group gets
  version 1 and is scheduled for a normal `MEMBERSUPDATE` broadcast to its now-active members, exactly like
  any other mutation, so everyone still holding the old, bloated list eventually converges to the
  corrected one.
- **`Left` stays exactly what it already is today: owner-only, local, never sent over the wire** (verified
  against the current code — `AcceptGroup` always constructs an incoming group with `Left=""`, and the
  existing `GROUP` frame never carries it). Its role is now purely historical bookkeeping for the owner's
  own Members-dialog/Re-invite UI, and it becomes disjoint from `Members` rather than overlapping it: once
  someone leaves, they're removed from the propagated `Members` and recorded in this separate history,
  instead of staying in `Members` with a flag next to their name.
- **`Group.MembersVersion`**: a plain monotonic integer per group, starting at 0 at creation, incremented
  by exactly 1 by the owner on every membership change (add or remove), regardless of cause.
- **One frame for every membership change, add or remove**: `LM4\tMEMBERSUPDATE\t{groupId}\t{version}\t{membersCsv}`
  — a full snapshot, not a delta. The owner sends it to every currently-active member whenever `Members`
  changes for any reason (direct invite, Re-invite, an accepted join request, or someone leaving). Reply
  `LM4\tMEMBERSUPDATEACK\t{groupId}\t{version}`.
- **Adoption rule**: a receiving member applies an incoming snapshot only if `version > their stored
  version` — strictly greater, no gap-checking, no ordering requirement. A device that missed five changes
  while offline just adopts the sixth (current) snapshot directly, replacing its entire local `Members`
  array and updating its stored version in one step. This is what makes "offline through several changes,
  then reconnects" trivial rather than something to special-case — there is no intermediate state to
  reconstruct, only ever the current one.
- **The `GROUP` invite only carries the version field once the group actually has one to carry.** Sending
  7 fields unconditionally would directly contradict "static groups are unaffected" — an old build's
  dispatch is `m.length==6` **exactly** (`PeerEngine.java:186`), so it would reject even a first, completely
  ordinary invite the moment the *sender* is on new code, regardless of whether that group is ever
  mutated. The actual rule: the owner sends the plain 6-field frame (today's exact wire shape) whenever
  `MembersVersion == 0` — i.e. this group has never been changed since creation — and only switches to the
  7-field form once `MembersVersion >= 1`. Since version 0 and "no version field, defaults to 0" already
  mean the same thing under the adoption rule, this loses no information; it's a pure wire-format match to
  today's shape for the common case. The receiving dispatch still accepts 6 *or* 7 fields either way
  (mirroring `MSG`/`OFFER`'s existing `m.length!=8&&m.length!=12&&m.length!=13` pattern at
  `PeerEngine.java:193`), so this direction (old build receiving a new build's still-unmutated-group
  invite) is genuinely, fully compatible — not just old-sender-to-new-receiver.
  **What remains genuinely one-directional**: once a group's version reaches 1 (it has actually been
  mutated at least once), its `GROUP`/`MEMBERSUPDATE` traffic requires every device in *that specific
  group* to be running this feature's release — an already-built old binary's dispatch cannot be taught,
  after the fact, to parse a 7-field frame. That's a hard limit with no cheap fix, unlike the version-0
  case above, which needed only a sending-side change.
- **Leave notice reaching everyone else (not just the owner)**: unchanged on the leaver's side — they
  still only send `LEAVE` to the owner, exactly as today. What changes is the owner's handling: on
  receipt, the owner removes the leaver from `Members`, records them in the separate history, bumps the
  version, and broadcasts the smaller snapshot to everyone still active. That broadcast — not a direct
  notice from the leaver — is how the rest of the group finds out.
- **`AddMember(groupId, memberId)` becomes the one real mutation**: adds to `Members` if there's room and
  they're not already present, bumps the version, and broadcasts. Both Re-invite and (later) Accept on a
  join request call this and nothing else — this was already the agreed intent, now actually wired to
  something that reaches every member instead of silently updating only the owner's own copy.
- **Capability check before any owner-initiated growth** (direct invite, Re-invite, accepted join
  request) — not just a documented requirement, an enforced one. `HELLO` itself is **not touched** —
  `validHello`/`ValidHello` require exactly 5 fields today, and `HELLO` is the very first exchange for
  *every* connection (discovery, direct connect, messaging, file transfer, all of it); adding a field there
  would make an old build silently drop all contact with a new one, for everything, not just groups. The
  actual mechanism reuses a pattern already in this codebase: `FILECAPS`
  (`m.length==2&&m[0].equals("LM4")&&m[1].equals("FILECAPS")` → reply `LM4\tFILECAPS\tSTREAM1`), a
  capability query sent only *after* the ordinary HELLO+trust handshake has already succeeded. New frame,
  same shape: `LM4\tCAPS` → reply `LM4\tCAPS\t{version}`. An old build that's never heard of `CAPS` falls
  through the existing final catch-all (does nothing, closes cleanly) exactly the way an unrecognized
  command already degrades today — no risk to anything else on the connection.
  `AddMember` triggers this query **fresh, every time, for every relevant peer** — every current active
  member, plus whoever's being added — with no shortcut for "already confirmed before." A prior success is
  not treated as durable proof of anything: the same device could have been downgraded, reinstalled, or
  restored from an old backup since the last check, so only a live reply at the moment of the actual
  action counts. A valid `{version} >= 1` reply on *this* attempt lets the action proceed; anything else —
  no reply, connection failure, garbage, or a version below 1, for *any* one of the relevant peers —
  refuses the whole action outright (clear error, nothing mutated). `Peer.protocolVersion` may still be
  kept as a **last-known, diagnostic-only** value (e.g. shown in the UI for troubleshooting) — it is never
  read by `AddMember`'s actual gating decision. This never blocks processing an incoming `LEAVE` — that
  member has already left regardless of what the owner's app can confirm about anyone else; see the
  explicit note on this under Leaving, below.
- **A departed (or never-a-member) recipient safely ignores stray/late traffic for a group.** A member who
  left has already deleted their own local group record entirely, same as today; `groups.TryGetValue`
  fails, so `AllowedGroup`/`allowedGroup` already rejects any late `MSG`/`OFFER` for that group, exactly
  like today's existing "unknown group" rejection — no new code needed there. The new `MEMBERSUPDATE`
  frame follows the same rule explicitly: it only ever applies to a group the recipient already has a
  local record for (it updates, never creates) — a stray or late `MEMBERSUPDATE` for a group the recipient
  has no record of (because they left, or were never a member) is simply ignored, the same as an unknown
  group id is today. Only a `GROUP` frame can ever establish a brand-new local record.
- **Old builds**: one that entirely predates this feature and so doesn't send/understand `MEMBERSUPDATE`
  at all simply never acks it; the owner's per-member version tracking just keeps retrying — the same
  tolerance already accepted elsewhere in this app. (The `GROUP` frame's own backward compatibility is
  handled explicitly above, not by this general tolerance.)

### States, per (group, person) — join-request layer, built on the foundation above

- **Active member**: in `Members`. Sends/receives; can leave.
- **Departed member**: recorded in the owner's separate history, no longer in `Members`. Can be
  Re-invited by the owner, or can send a new join request themselves.
- **Outsider**: never been a member. Can send a join request if they know the group ID and are a
  verified contact of the owner.
- **Pending request**: recorded in the owner's per-group request queue. Grants no permissions. Exactly
  one outstanding pending entry can exist per (group, person) — a repeat request while already pending is
  a no-op (no duplicate entry, no repeat notice to the owner).
- **Owner**: fixed at creation, forever. Sole approver. No leave button; no transfer.

### Operations

1. **Direct invite / Re-invite** — owner picks a verified contact (or a departed member) and adds them.
   Both call `AddMember(group, person)`; the two entry points stay as separate UI buttons (different
   initiative), one code path underneath.
2. **Send join request** — person enters a group ID (obtained out of band) and it's sent to the group's
   owner, who must already be one of their verified contacts. Delivery is guaranteed the same way
   `FORGET`/`LEAVE` are: retried every delivery cycle until acked, one outstanding intent per (owner,
   group) persisted on the requester's side (so an app restart before the ack arrives doesn't silently
   drop it — mirroring `forgotten`/`pendingLeaves`). No user-visible "pending" indicator is needed on the
   requester's side; if accepted, the group simply appears like any first-time invite does today.
3. **Accept** — re-validated at the moment of the decision, not from when the request was first sent:
   - Requester is still a verified contact of the owner. If not: request is cleared (nothing recoverable
     without a fresh verification and a fresh request).
   - Requester is not already a member (e.g. added another way meanwhile, such as a simultaneous direct
     invite). If already a member: request is cleared silently — moot, nothing to do.
   - Group has room (`Members.Length < 16`, now always accurate since `Members` is the live active
     roster). If full: the request is **kept as `Pending`** (recoverable — the owner can retry Accept
     later once a slot frees), not cleared.
   - Otherwise: `AddMember` runs and the request is removed.
4. **Ignore** — the request disappears from the owner's queue. No notice to the requester, no visible
   "ignored" record anywhere. Internally, the owner's record keeps only the timestamp of when it was
   ignored, solely to enforce the cooldown below; there is no way to Accept an ignored request directly —
   `Ignored → Accepted` is not a reachable transition. The only ways back to membership after an ignore
   are (a) a brand-new request from that person once the cooldown has passed, which becomes a fresh
   `Pending` entry, or (b) a direct Re-invite from the owner, which bypasses the request queue entirely.
5. **Cooldown** — after being ignored, the same person can send a new request for the same group only
   once **1 hour** has passed since the ignore. A request arriving before that is still acked at the wire
   level (so the requester's engine doesn't spin retrying forever) but is not added to the owner's visible
   queue and raises no notice.
6. **Leaving**: a member stops sending/receiving group traffic immediately on their own decision; the
   owner removes them from `Members`, records them in the separate history, and broadcasts the smaller
   snapshot (see foundation above) — they remain eligible for either Re-invite or a future join request.
   **This is never gated by anyone's capability** — unlike growth, a leave is a fact that already happened,
   not a request the owner can refuse. If some remaining active member can't be reached, or turns out not
   to support this feature at all, the leave itself still fully succeeds locally for the owner; that member
   simply stays behind on the old snapshot until they're reachable, and the app reflects that honestly
   (e.g. in the Members dialog) as "not yet synced to the current version" rather than implying the whole
   group is consistent. This is already what the per-member acked-version tracking in the foundation
   naturally shows — nothing new to compute, just something the UI must not paper over.
7. **Conflict**: a direct invite and a join request for the same person landing around the same time —
   `AddMember` is idempotent (adding an existing member is a no-op), and a join request is always cleared
   once its target becomes a member by any path, so this collapses to a single add with no duplicate
   effect and no error.
8. **Stale state**: every membership decision reads whatever `Members` a device currently has stored —
   never a cached or in-flight copy layered on top of it. This is *not* a claim that every device's copy
   is always current: a device that's offline, or simply hasn't reconnected to the owner since the last
   change, keeps whatever it last had until it does reconnect and catches up in one step (see the
   adoption rule above). The only guarantee is convergence among active members that are reachable or
   that eventually reconnect to the owner — not an instantaneous, system-wide "always up to date."

### Wire frames

- `LM4\tMEMBERSUPDATE\t{groupId}\t{version}\t{membersCsv}` / `LM4\tMEMBERSUPDATEACK\t{groupId}\t{version}`
  — foundation, see above.
- `GROUP` invite sends 6 fields (today's exact shape) while `MembersVersion==0`, 7 fields (with the
  version) once `>=1`; the receiving dispatch accepts either — foundation, see above for exactly why a
  naive "always send 7" would have silently broken interop for ordinary, never-mutated groups.
- `LM4\tCAPS` / `LM4\tCAPS\t{version}` — sent only after the existing HELLO+trust handshake already
  succeeded, mirroring the existing `FILECAPS`/`LM4\tFILECAPS\tSTREAM1` pattern exactly. `HELLO` itself is
  unchanged — foundation, see above for why touching it at all was the wrong approach.
- `LM4\tJOINREQUEST\t{groupId}` — requester → owner, over their existing peer connection (the requester
  must already be a verified contact of the owner to connect at all, checked the same way `AcceptGroup`
  already requires `Trusted(sender,fingerprint)`). Retried every delivery cycle, same shape as
  `FORGET`/`LEAVE`, until acked.
- `LM4\tJOINREQUESTACK\t{groupId}` — owner → requester, sent **only after** the request has been durably
  persisted into the owner's queue (after the storage save succeeds) — not merely on receipt over the
  wire. Confirms delivery only; it says nothing about the eventual Accept/Ignore decision, which happens
  asynchronously and produces no frame of its own (Ignore is silent; Accept produces the `MEMBERSUPDATE` /
  `GROUP` frames from the foundation, nothing new).
- An old build that doesn't understand `JOINREQUEST` simply never acks it; the sender keeps retrying
  rather than erroring — same tolerance already accepted elsewhere in this app.

### Storage

- `Group` gains `MembersVersion` (int). Owner additionally tracks, per active member, the last version
  they've acked (a small `Dictionary<string,int>`/`HashMap<String,Integer>`, mirroring the existing
  `Acknowledged` set but versioned) so `Deliver()` knows exactly who still needs the current snapshot.
- `Peer` gains `protocolVersion` (int, default 0), populated only by a successful `CAPS` reply — never by
  `HELLO`. Persisted as another optional trailing field on the existing `P` row (which has already grown
  5→7→8→10 fields over past releases the same way), so an older-format `P` row still loads fine with this
  defaulting to 0. **This stored value is last-known/diagnostic only** — `AddMember` never reads it to
  decide anything; every gating decision comes from a fresh `CAPS` query at the moment of the action.
- Owner's separate departed-history record (what `Left` already was) is unchanged in shape and purpose —
  still owner-only, still never serialized onto the wire.
- Owner side, new row per (group, requester): `J\t{groupId}\t{requesterId}\t{ignoredAtEpochMillis|""}` —
  an empty last field means currently `Pending`; a filled timestamp means `Ignored` at that time, kept
  only to enforce the 1-hour cooldown (pruned lazily whenever it's next checked and found expired, no
  eager cleanup pass needed). Same "new row prefix, no version-tag bump" pattern already used for `F`
  (forgotten) and `L` (pendingLeaves).
- Requester side: a small persisted set of (ownerId, groupId) pairs still awaiting a `JOINREQUESTACK` —
  same durability purpose as `forgotten`/`pendingLeaves`, no user-visible state attached to it.

## Tasks

| ID | Platform | Task | Depends on | Status |
|----|----------|------|------------|--------|
| G1 | Both | Design above — locked in | — | Done |
| M1 | Windows | Foundation, one atomic change (local model + propagation together, never separately): `Members` becomes the live, shrinking/growing active roster; `Group.MembersVersion`; owner-only `departedHistory`/`memberAcked` bookkeeping; load-time migration with the version-1-if-`oldLeft`-non-empty rule; `HandleLeave`, `AcceptGroup` (6-or-7-field `GROUP`), new `HandleMembersUpdate`, new `LM4\tCAPS` query (mirroring `FILECAPS`), `AddMember` with a fresh, never-cached capability check; test-only `SimulateLegacyBuild` toggle; `ShowMembers`/`ReinviteMember` updated to the new `AllKnownMembers` API and made async | G1 | **Done** |
| M2 | Android | Mirror M1 in `PeerEngine.java`/`GroupSync.java`, including the `m.length==6\|\|m.length==7` dispatch change, the new `CAPS` query, `simulateLegacyBuild`, and `MainActivity.showMembers()`/`reinviteMember` updated (background-threaded, since `addMember` now does blocking network I/O) | G1 | **Done** |
| M3 | Both | `tests/group_membership.py` (30 assertions, both platforms and cross-platform): convergence on add/remove/re-invite; offline catch-up; shrink-to-owner-then-regrow; live capability checks; leave without capability gating; single-device migration. `tests/group_migration_broadcast.py` additionally preserves real paired device identities, rewrites only the owner's saved group into the old overlapping `Members`/`Left` format, restarts an existing member with its stale version-0 roster, and verifies the owner's migration to version 1 broadcasts the corrected roster to that member and group messaging still works. Both Java-owner/C#-member and C#-owner/Java-member directions pass. Harness commands: `REINVITE`, `LEFT`, `LEGACY`, `ROSTER`; `LegacyGroupState` is a test-only saved-state fixture. Full `tests/run.ps1` passes. | M1, M2 | **Done** |
| G2 | Windows | Join-request layer: `RequestJoin(ownerId, groupId)`; `HandleJoinRequest`; `AcceptJoinRequest(groupId, requesterId)` (re-checks pending/membership/verified, then calls `AddMember`); `IgnoreJoinRequest(groupId, requesterId)`; `PendingJoinRequests(groupId)`; new `J` (owner queue) and `Q` (requester pending-send) storage rows; `Deliver()` sends `JOINREQUEST` (requester side) and `JOINREQUESTACK` after persisting (owner side); `Receive()` dispatches both. **Bug found and fixed during G7 testing**: `AddMember` itself now also clears any pending join request for the id being added, not just `AcceptJoinRequest` — otherwise a direct invite/Re-invite landing while a request was still pending left a stale, permanently-visible queue entry for someone already a member | M3 | **Done** |
| G3 | Android | Mirror G2 in `GroupSync.java`/`PeerEngine.java`, including the same `addMember`-clears-pending-request fix | M3 | **Done** |
| G4 | Windows | UI: "Request to join a group" action (pick a verified contact, paste a group ID); owner's request queue in/near the Members dialog with Accept/Ignore per entry; tag the owner's row distinctly (e.g. "Admin") in the Members dialog; the Members dialog shows the *union* of live `Members` and the separate departed-history record (not `Members` alone, now that departed people are no longer in it), so Re-invite keeps working for anyone shown as departed; each active member's row also reflects whether they're caught up to the current `MembersVersion` or lagging (from the existing per-member acked-version tracking) — never implying the group is fully consistent when it isn't. The Admin tag and departed-history union were already correct from the M1-era `ShowMembers` rewrite; G4 added the queue, sync status, `MemberAckedVersion` made public, and the new request dialog. **New `tests/WindowsUi/Program.cs` coverage**: a real second window instance drives the actual `ShowMembers` modal (via a `Timer` that fires once the dialog opens, reads its rendered text, then clicks the real "Accept" button) end-to-end against a live requester peer, confirming the queue renders and Accept truly adds the member — not just a build check. Two bugs were found and fixed in the *test itself*, not the product: (1) the Members dialog's "Admin" tag structurally never shows on your own row (self is always tagged "(you)", checked before the owner check), so asserting for it only works from a non-owner's viewpoint — dropped that assertion rather than restructure the scenario for no product-relevant gain; (2) the test's first draft reused an already-`Dispose()`d peer as a group member, so the fresh CAPS re-check `AcceptJoinRequest` performs against every existing active member always failed — fixed by using a still-live peer instead | G2 | **Done** |
| G5 | Android | Mirror G4 | G3 | **Done** — `MainActivity.showMembers()` gained the same owner-only join-request queue (Accept/Ignore, background-threaded like the existing Re-invite) and per-member sync status; new `requestJoinGroup()` dialog and toolbar button; `PeerEngine.memberAckedVersion(groupId, peerId)` added to mirror Windows. Verified via a full signed APK build (`android/build.ps1`, compile-clean) and the existing engine-level `tests/join_requests.py` (exercises `requestJoin`/`acceptJoinRequest`/`ignoreJoinRequest` directly). Unlike G4, there is no on-device/emulator interaction check for the Android UI itself — Android has no equivalent of the Windows native-UI reflection harness, so this is implemented-and-built, not device-verified |
| G6 | Both | Harness commands for join-request testing: `REQUESTJOIN`, `PENDINGREQUESTS`, `ACCEPTREQUEST`, `IGNOREREQUEST`, plus a test-only `BACKDATEIGNORE`/`DebugBackdateIgnoredJoinRequest` to exercise the 1-hour cooldown elapsing without a real wall-clock wait | G2, G3 | **Done** |
| G7 | Both | New `tests/join_requests.py` (33 assertions): full lifecycle both directions; duplicate request while pending is a no-op; ignore → cooldown blocks an immediate retry → succeeds once backdated past the cooldown; a direct invite while a request is pending collapses to one add and clears the queue (the bug above); a no-longer-verified requester's accept clears the request silently; an unacked join request survives a restart on the requester side; a full 16-member group keeps an over-capacity accept `Pending`, and it then succeeds once a departure frees a slot, with every other remaining original member converging and exchanging messages with the replacement. **Not built, verified by structural argument instead**: a live filesystem-permission-forced save failure proving `JOINREQUESTACK` truly waits on persistence — every frame handler in this codebase (including this one) already shares one dispatch shape where the ack line only runs after the handler returns *without throwing*, and `Save()`/`save()` failure always throws, so this is enforced by the same code structure already relied on, untested in isolation, for every other frame type (`FORGET`, `LEAVE`, `GROUP`, `MEMBERSUPDATE`). **Environment lesson from building the 16-member scenario**: 16+ real simultaneous local processes exercising active discovery/connection churn will transiently fail pairing, CAPS queries, and the shared `wait_for`'s 30s budget under real (not just theoretical) load — solved with retry-tolerant helpers, a staggered peer-startup, and a scenario-local longer poller, not by weakening the actual assertions | G6 | **Done** |
| G8 | Both | Update status docs | G2–G7 | **Done** |

## Acceptance criteria

- Every active member that is reachable, or that eventually reconnects to the owner, converges to the
  latest snapshot regardless of how many changes it missed while unreachable — never needing to replay
  each intermediate change. This is a convergence guarantee for reachable/reconnecting members, not a claim
  that every device is instantaneously up to date.
- `Left`/departed-history is never sent over the wire; only the current active `Members` and its version
  are.
- A group can shrink to just the owner, and later grow back from 2 members upward, without being
  rejected at any step; the `<3` floor exists only in `CreateGroup` itself and is never re-checked on the
  receiving side, for a first invite or any later update.
- A group that has never been mutated (`MembersVersion == 0`) sends and receives a plain 6-field `GROUP`
  invite, unchanged from today's wire shape — an old, not-yet-updated build is genuinely, fully
  unaffected by this feature for such a group, not merely "unaffected in theory." Only once a group has
  actually been mutated at least once does it require every device in *that specific group* to be running
  this release or later — an already-built old binary's dispatch cannot be taught to parse the 7-field
  form after the fact.
- The owner's app actively refuses to grow a group's membership (direct invite, Re-invite, or accepting a
  join request) unless every person who would end up in it — existing active members plus whoever's being
  added — answers a **fresh** `CAPS` query, at that exact moment, confirming support. A past success is
  never treated as durable proof; the device could have been downgraded, reinstalled, or restored from a
  backup since. This is enforced, not just documented: a group is never allowed to silently split into two
  inconsistent views because someone hadn't updated. `HELLO` itself is never changed — the check is a
  separate, independent exchange (mirroring the existing `FILECAPS` pattern) that an old build simply
  doesn't answer, with no effect on anything else a connection to that build already does today (discovery,
  direct connect, messaging, file transfer).
- Leaving a group is never blocked by anyone else's capability — it's a fact that already happened, not a
  request the owner can refuse. If a remaining member can't be reached or doesn't support this feature, the
  leave still fully succeeds locally, and that member's tracked sync state honestly shows them as lagging
  rather than the app implying the whole group is consistent.
- An already-saved group with the old overlapping `Members`/`Left` shape migrates correctly on first load
  under the new model, exactly once, and the Members dialog still shows (and can Re-invite) anyone who had
  already departed before the migration.
- A migration that actually shrinks a group's roster (it had a non-empty `Left`) is treated as a real
  membership change, not a silent local fix-up: it gets version 1 and a `MEMBERSUPDATE` broadcast, so
  every other still-active member — whose own stored copy still shows the departed person as active,
  since `Left` was never propagated before this feature — eventually converges to the corrected roster
  too. A migration that changes nothing (`Left` was empty) stays at version 0 with no broadcast.
- A legacy 6-field `GROUP` frame (no version field) is still accepted correctly, treated as version 0 —
  the dispatch accepts 6 or 7 fields, not 7 only.
- A departed member, or someone who was never a member, safely ignores a stray or late `MEMBERSUPDATE` (or
  any other group frame) for a group they have no local record for — the same as an unknown group id is
  rejected today.
- A verified contact of a group's owner — member, past member, or complete outsider — can send a join
  request given the group's ID, and it reliably reaches the owner (retried until acked) even across a
  restart on either side.
- The owner sees one queue entry per pending requester per group, never duplicated by retries or repeat
  clicks.
- Accept re-validates identity, membership, and capacity at the moment of the decision, not from when the
  request was sent; a stale request is never able to add someone who no longer qualifies.
- A full group keeps a request `Pending` rather than discarding it; accept becomes possible again once
  room exists.
- Ignore is silent and permanent for that specific request; the same person can try again only after the
  group's 1-hour cooldown, or be brought back directly by the owner via Re-invite at any time.
- The 16-member cap (owner included) is enforced against the live active roster, identically in spirit to
  `CreateGroup`'s existing rule.
- Full `tests/run.ps1` stays green throughout.

## Verification

Windows build + full `tests/run.ps1` after M1, Android compile-check + full Android SDK build (signed APK)
+ full `tests/run.ps1` after M2, then M3's foundation-only tests before G2/G3 begin at all. Same process
repeats for the join-request layer: Windows build + tests after G2/G4, Android build + tests after G3/G5,
then G7's full test set, then docs (G8), then local commits (no push).
