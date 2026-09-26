# Plan: group owner must hand off before leaving

## Goal

Today the group owner is fixed forever (documented, explicit non-goal in `PLAN-GROUP-MEMBERSHIP.md`) —
and the UI doesn't actually stop them from clicking "Leave group" on their own group. If they do, the
group silently orphans for everyone else: the leave notice is queued to be sent *to the owner*, which is
themselves, so it never goes anywhere; nobody else is ever told; the departed owner stays listed as
member/owner forever; and since only the owner can add/remove members, the group is permanently frozen
for everyone remaining (ordinary messaging between the remaining members still works — it's the roster
that's stuck).

Fix: **before an owner can leave a group with any other active member in it, they must hand off ownership
to one of those members first.** A single-member (owner-only) group has nobody to hand off to, so leaving
it stays exactly as simple as it is today (a local delete).

## Design

### The trust problem, and why the fix is what it is

Membership updates (`MEMBERSUPDATE`) are currently authorized by one check on the receiving side: *does
this frame's sender match the owner I already have on record for this group?* That's the only thing
stopping an arbitrary member from rewriting the roster. It works today because the owner never changes.

Once ownership can change, a receiver who's offline through the handoff has a bootstrapping problem: their
local record still says the *old* owner is the owner, so a legitimate update from the *new* owner would
fail that same check, forever, unless something reaches them still validated by the *old* owner's authority
first.

**Resolution**: reuse the existing versioned-snapshot mechanism exactly as-is, just widened to also carry
who the owner is. The handoff itself is authorized by the outgoing owner (who every receiver already
trusts) — the same "retried every delivery cycle until every member acks the current version" guarantee
that already makes offline-through-several-changes convergence work today also makes offline-through-a-
handoff work, with no new cryptographic machinery. The one accepted limitation, consistent with this app's
existing "must update to stay current" tolerance elsewhere (e.g. a group that's been mutated requires every
device in it to be current): if a member is offline until *after* the ex-owner's own device has also gone
away for good (uninstalled, never reconnects), they're stuck on the stale owner — same category of edge
case as "an old build can't be taught to parse a new frame after the fact," not a new kind of risk.

### Wire

- `MEMBERSUPDATE` gains a 6th field: `LM4\tMEMBERSUPDATE\t{groupId}\t{version}\t{membersCsv}\t{ownerId}`.
  Dispatch accepts 5 or 6 fields (5 = owner unchanged, defaults to the receiver's own currently-stored
  owner) — mirrors the existing 6-or-7-field pattern already used for `GROUP`.
- `HandleMembersUpdate`/`handleMembersUpdate`'s authorization is unchanged in shape: still
  `sender == the receiver's own currently-recorded Owner`. What changes is that a successful adopt also
  updates the locally-stored `Owner` field from the frame (when present), so from that point on, the new
  owner is who future frames are checked against on that device.
- `CAPS`'s reply version bumps from `1` to `2` for a build that supports ownership transfer (i.e. the
  6-field `MEMBERSUPDATE`/7-or-8-field... no — `GROUP`'s shape is untouched by this feature, only
  `MEMBERSUPDATE` gains a field). `TransferOwnership` requires a **fresh** `CAPS >= 2` from every current
  active member (not just the incoming owner) before proceeding — once transferred, *every* member needs
  to understand the 6-field form to keep converging, so this mirrors `AddMember`'s existing "check
  everyone who'd be affected, live, every time" philosophy exactly. A group that's never had an ownership
  transfer keeps working with old builds exactly as it does today (5-field `MEMBERSUPDATE`, or none at all
  if it's never been mutated); only a group where a transfer has actually happened requires every device in
  it to be current — same shape as the existing `GROUP`/`MEMBERSUPDATE` version-0-vs-mutated distinction.

### Engine

- New op, owner-only: `TransferOwnership(groupId, newOwnerId)` / `transferOwnership(e, groupId, newOwnerId)`.
  - `newOwnerId` must be a currently active member, and not the caller.
  - Fresh live `CAPS` check (`>= 2`) of every current active member (same pattern as `AddMember`, refuses
    outright with a clear error and mutates nothing if anyone fails).
  - Sets `Group.Owner = newOwnerId`, bumps `MembersVersion` by 1 (an ownership change is a real, broadcast-
    worthy membership-record change, same as an add/remove), and schedules the versioned broadcast (now
    carrying the owner field) the same way any other mutation does.
  - No change to `Members` itself — this is purely who's the owner, not who's in the group.
- `DeleteConversation`/`deleteConversation` (the existing "leave" path) is unchanged in mechanism — it
  already reads `oldGroup.Owner` to decide who to send the `LEAVE` notice to. Since a successful transfer
  updates the local `Owner` field immediately, calling leave right after a transfer correctly targets the
  *new* owner, with zero changes needed to the leave path itself.
- `HandleLeave`/`handleLeave` (owner-side leave processing) is unchanged — after a transfer, the new owner
  is simply the one now receiving and processing the ex-owner's `LEAVE`, exactly like any other member
  leaving.

### UI

- "Leave group" (the conversation-list action and the new in-chat action, both platforms) checks: is the
  local user the owner, and are there other active members?
  - **No other active members** (owner-only group): behaves exactly as today — a plain local leave/delete,
    nothing to hand off.
  - **Other active members exist**: instead of the normal confirm dialog, show a "Choose a new admin
    before you leave" picker listing the other active members (verified contacts only — same requirement
    `AddMember` already has for anyone touched by a membership operation). Confirm triggers
    `TransferOwnership`; on success, immediately continue into the existing leave confirmation/flow. On
    failure (a live capability check fails for someone), show the existing kind of clear error and leave
    nothing changed — the owner is still the owner, still in the group, and can retry or pick someone else.
- Members dialog / showMembers(): no structural change needed — the "Admin" tag already reflects whoever
  `Group.Owner` currently is, so it updates correctly once a transfer lands.

## Tasks

| ID | Platform | Task | Depends on | Status |
|----|----------|------|------------|--------|
| T1 | Windows | `TransferOwnership`; `MEMBERSUPDATE` 6th field (send + `HandleMembersUpdate` accept 5-or-6); `CAPS` reply bump to `2`; leave-flow owner-only-local-delete special case preserved | — | Not started |
| T2 | Android | Mirror T1 in `GroupSync.java`/`PeerEngine.java` | — | Not started |
| T3 | Windows | UI: intercept "Leave group" (list action + in-chat action) for the owner when other active members exist; new picker dialog; wire to `TransferOwnership` then the existing leave flow | T1 | Not started |
| T4 | Android | Mirror T3 in `MainActivity.java` (`confirmDeleteConversation`'s call sites, `chatMenu`) | T2 | Not started |
| T5 | Both | Test coverage: transfer succeeds and every member converges (including one offline through the transfer, catching up in one step); transfer refused if a member doesn't support `CAPS >= 2` (nothing mutated); owner-only group still leaves as a plain local delete with no transfer prompt; after transfer + leave, the new owner can add/remove members and the ex-owner's own device correctly rejects further frames for that group (they've left) | T1–T4 | Not started |
| T6 | Both | Update `PLAN-GROUP-MEMBERSHIP.md` and the status docs | T1–T5 | Not started |

## Acceptance criteria

- The owner can never leave a group that has other active members without first successfully
  transferring ownership to one of them.
- An owner-only group (no other active members) leaves exactly as it does today — no picker, no
  transfer, no behavior change.
- A transfer requires a fresh, live capability check of every current active member; refusal leaves
  everything unchanged (owner unchanged, members unchanged, nothing broadcast).
- After a successful transfer, every reachable/eventually-reconnecting member converges to the new owner
  in one step, the same convergence guarantee the mutable-membership foundation already provides for
  roster changes — including a member who was offline for the transfer itself.
- A group that's never had an ownership transfer is completely unaffected for old, not-yet-updated
  builds — same as today's `GROUP`/`MEMBERSUPDATE` version-0-vs-mutated split. Only a group that's
  actually had a transfer requires every device in it to be current.
- Full `tests/run.ps1` stays green throughout.

## Verification

Windows build + Android build + full `tests/run.ps1` after T1/T2, then again after T3/T4, then after T5.
Commit locally only (no push), per the standing instruction.
