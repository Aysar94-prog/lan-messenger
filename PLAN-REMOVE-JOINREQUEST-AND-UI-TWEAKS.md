# Plan: remove the join-request feature; relabel group delete-as-leave; Android "Hide groups" toggle

## Goal

Three changes requested after the 2.0.0 release:

1. Remove the "request to join a group" feature entirely (both platforms, engine + UI + wire + storage +
   tests + docs) — but **keep the mutable-group-membership foundation** it was built on top of: the live
   roster, `MembersVersion`/`membersVersion`, `AllKnownMembers`/`allKnownMembers`, `AddMember`/`addMember`
   (owner can still add an already-verified contact to an existing group directly), the live capability
   check before any growth, migration of old-shape saved groups, `ReinviteMember`/`reinviteMember`, and the
   per-member "Synced"/"Catching up" sync-status display. None of that is join-request-specific and none of
   it is being removed.
2. For "Delete conversation" on a **group**: the action has always actually been a *leave* (other members
   keep the group and their copies), never a real delete. Relabel it accordingly — menu item text and
   confirmation dialog title/wording — instead of calling it "Delete conversation" for both a contact and a
   group. A contact keeps the real "Delete conversation" label (it does revoke verification and remove the
   peer). The current Windows confirmation text is also factually wrong for a group today (it says "revokes
   verification", which never applied to leaving a group) — this gets fixed as part of the relabel.
3. Android: add a "Hide groups" toggle to the side menu, mirroring the existing "Show offline users" toggle
   exactly — same persisted-flag pattern, same display-only filter applied while `render()` builds the
   visible conversation list, no engine/wire/routing change.

## 1. Remove join-request feature — what's deleted vs. kept

Full inventory below (read/verified against current source; C#/Java are 1:1 mirrors throughout).

**Deleted (join-request-only):**

- Windows `windows/Conversations.cs`: `joinRequests`/`pendingJoinRequests` fields + `JoinRequestCooldownMs`;
  `RequestJoin`, `HandleJoinRequest`, `PendingJoinRequests`, `ClearJoinRequestLocked`,
  `DebugBackdateIgnoredJoinRequest`, `IgnoreJoinRequest`, `AcceptJoinRequest` (whole methods).
- Windows `windows/PeerEngine.cs`: the `JOINREQUEST` dispatch line in `Receive()`; the join-request
  send/retry `foreach` block in `Deliver()`.
- Windows `windows/Storage.cs`: `J`/`Q` row read/write; the `loadedJoinRequests`/`loadedPendingJoinRequests`
  locals and their restore-into-fields lines.
- Windows `windows/ChatWindowDialogs.cs`: the "Join requests" queue block inside `ShowMembers` (Accept/Ignore
  buttons, the `pending` array and its effect on dialog sizing); `RequestJoinGroup()` (whole method).
- Windows `windows/Program.cs`: the `requestJoin` button field and its toolbar wiring.
- Android mirrors in `GroupSync.java` (`JOIN_REQUEST_COOLDOWN_MS`, `requestJoin`, `handleJoinRequest`,
  `pendingJoinRequests`, `clearJoinRequestLocked`, `debugBackdateIgnoredJoinRequest`, `ignoreJoinRequest`,
  `acceptJoinRequest`), `PeerEngine.java` (fields, thin wrapper methods, `J`/`Q` row read/write, the
  `JOINREQUEST` dispatch line, the send/retry loop in `deliver()`), `MainActivity.java` (the join-request
  queue block inside `showMembers()`, `requestJoinGroup()`, the toolbar button wiring).
- Test harness commands: `REQUESTJOIN`/`PENDINGREQUESTS`/`ACCEPTREQUEST`/`IGNOREREQUEST`/`BACKDATEIGNORE` in
  both `tests/CsharpHarness/Program.cs` and `tests/PeerHarness.java`.
- `tests/join_requests.py` in full, and its invocation line in `tests/run.ps1`.
- The "G4" join-request scenario block in `tests/WindowsUi/Program.cs` (verify nothing later in that file
  references its `third`/`fourth`/`uiGroupId` locals before deleting).
- Docs: the join-request-specific sections/sentences in `PLAN-GROUP-JOIN-REQUESTS.md`, `PROJECT_STATUS.md`,
  `windows/STATUS.md`, `android/STATUS.md`, `README.md` (each has join-request prose interleaved with
  foundation prose in the same paragraph in several places — edited, not blanket-deleted).

**Surgically edited, not deleted (foundation code with a join-request hook grafted in) — the highest-risk
part of this removal:**

- `AddMember`/`addMember`: remove only the "auto-clear a pending join request when this person is added by
  any path" hook (a few lines inside the method, plus its rollback fragment in the `catch`) — the
  capability-check-then-mutate-then-save structure around it is untouched.
- `DeleteAllData`/`deleteAllData`: remove only the `joinRequests`/`pendingJoinRequests` snapshot, clear, and
  rollback fragments — the rest of the wipe-and-rollback logic (messages/peers/groups/departedHistory/
  memberAcked/attachments/avatars) is untouched.
- `ShowMembers`/`showMembers`: remove the queue block, but the per-member "Synced"/"Catching up" line stays
  (it reads `MemberAckedVersion`/`memberAckedVersion`, which is foundation, not join-request).

**Storage-format note:** removing the `J`/`Q` row types means a save file that already has them (only
possible from this dev environment's own local test runs — the release was never pushed, so no real device
has this data) would hit the loader's "unrecognized row" hard failure. Add a tolerant skip for unrecognized
single-letter row prefixes on load (or specifically skip stray `J`/`Q` rows) so an old local data directory
doesn't corrupt/crash on next open, rather than assuming no such file will ever be encountered.

**Test coverage gap to address:** `tests/join_requests.py`'s scenario 7 is the *only* place that exercises
"a full 16-member group's capacity cap enforced against an Accept, kept Pending, then succeeds once a
departure frees a slot" — a foundation (`AddMember`) behavior, just exercised via the join-request path.
Before deleting the file, port a capacity-only version of that check into `tests/group_membership.py` using
`REINVITE`/direct-add instead of `ACCEPTREQUEST`, so this foundation behavior keeps real coverage.

## 2. Relabel group "Delete conversation" as "Leave group"

- Windows `Program.cs`/`ChatWindowDialogs.cs`: the context-menu item and `DeleteConversationConfirm()`'s
  dialog title/body branch on whether the selected `ContactItem` is a group. Group wording: "Leave group?" /
  "Leave \"{name}\"? Other members keep the group and their own copies." (no verification-revocation
  language). Contact wording stays as today's real-delete text.
- Android `MainActivity.java`: `confirmDeleteConversation`'s dialog title branches the same way
  ("Leave group?" vs "Delete conversation?"); its message already branches partly (drop the
  verification-revocation clause for groups, which the message doesn't currently say, so just aligning
  title with the already-correct body).
- No engine change — `DeleteConversation`/`deleteConversation` already does the right thing per type (leave
  vs. real delete); this is UI wording only.

## 3. Android: "Hide groups" side-menu toggle

- `PeopleListView.java`: add a `Switch` below the existing `Show offline users` switch, same
  `SharedPreferences` (`lan_messenger_ui`) file, new boolean key (e.g. `hide_groups`), default off.
- `MainActivity.java`: apply the flag as one more skip condition in `render()`'s visible-row-building loop —
  same place, same shape as the existing offline-peer skip, but for group rows instead of offline direct
  peers. Deep links and an already-open group chat bypass this the same way the offline filter is bypassed
  (not filtered, since they don't go through the list). No engine, wire, or routing change; direct-peer rows
  are never affected by this toggle.
- Windows has no side menu (that's an existing, documented platform asymmetry) — this toggle is Android-only,
  matching the existing `Show offline users` precedent.

## Tasks

| ID | Platform | Task | Depends on | Status |
|----|----------|------|------------|--------|
| R1 | Windows | Remove join-request engine+UI+wire+storage (surgical edits to `AddMember`/`DeleteAllData`/`ShowMembers`; full deletion of `RequestJoin`/`HandleJoinRequest`/etc., `JOINREQUEST` dispatch/send-loop, `J`/`Q` rows with a tolerant-skip fallback for old rows, toolbar button) | — | **Done** |
| R2 | Android | Mirror R1 in `GroupSync.java`/`PeerEngine.java`/`MainActivity.java` | — | **Done** |
| R3 | Both | Remove test harness commands, `tests/join_requests.py` (after porting the 16-member-capacity check into `group_membership.py`), the `run.ps1` line, and the WindowsUi G4 block | R1, R2 | **Done** — the ported capacity check initially dropped the original's `command_retry` wrapper around the equivalent real-load-sensitive call, causing a real (non-flake) failure under the 17-real-process scenario; found and fixed by re-adding the retry wrapper |
| R4 | Both | Update docs: prune `PLAN-GROUP-JOIN-REQUESTS.md` (or rename/trim to a foundation-only design doc), `PROJECT_STATUS.md`, `windows/STATUS.md`, `android/STATUS.md`, `README.md` | R1–R3 | **Done** — renamed/trimmed to `PLAN-GROUP-MEMBERSHIP.md` (foundation-only), old file deleted; all four status/README docs updated to describe the removal as a post-2.0.0 change, not rewritten history |
| L1 | Windows | Relabel group delete as "Leave group" (menu item + confirmation dialog), fix the incorrect verification-revocation wording for groups | — | **Done** |
| L2 | Android | Relabel group delete confirmation dialog title the same way | — | **Done** |
| H1 | Android | "Hide groups" side-menu toggle, mirroring `Show offline users` | — | **Done** |

R1–R4 (the removal) can run in parallel with L1/L2/H1 (independent, no shared files in the critical path
except `MainActivity.java`/`ChatWindowDialogs.cs`, which will just get more than one edit).

## Acceptance criteria

- Full `tests/run.ps1` stays green throughout (no join-request test remnants left referencing deleted
  symbols; the ported 16-member-capacity check still passes).
- `AddMember`/`addMember`'s capability-check-then-mutate-then-save behavior, `ReinviteMember`/
  `reinviteMember`, migration, and the per-member sync-status display are byte-for-byte behaviorally
  unchanged (verified by `group_membership.py`/`group_migration_broadcast.py` passing unmodified).
- No dangling references to deleted symbols (`joinRequests`, `RequestJoin`, etc.) anywhere in either
  codebase; both build clean.
- An old local save file containing `J`/`Q` rows loads without crashing (tolerant skip), not silently
  corrupting other data.
- Deleting a contact still says "Delete conversation" / revokes verification; deleting a group now says
  "Leave group" and describes leaving, not deletion or verification revocation, on both platforms.
- Android's new "Hide groups" toggle hides only group rows from the people list when on, never affects
  engine state/unread counts/routing, and an incoming deep link or already-open group chat still works while
  the toggle is on (matches the existing offline-filter bypass behavior).

## Testing tasks

- Build both platforms clean after removal (Windows `dotnet build`, Android `build.ps1` signed APK).
- Full `tests/run.ps1` green, including the ported capacity check and a manual/native check that
  `ShowMembers`/`showMembers` no longer shows a join-request queue and no longer offers a "Request to join"
  action anywhere.
- New: a small Windows-native and/or Python assertion that a group's context-menu/dialog says "Leave group"
  and a contact's says "Delete conversation" (extend `tests/delete_conversation.py` or `tests/WindowsUi`).
- New: `tests/features.py`/an Android-side check (or a `PeopleListView`-focused native-style check, if one
  exists) that the "Hide groups" toggle hides only group rows and survives a restart, mirroring how the
  existing `Show offline users` toggle is covered today (if it has an automated check at all — confirm
  before assuming parity is required).

## Verification

Windows build + Android build + full `tests/run.ps1` after each of R1/R2, then after R3, then after
L1/L2/H1. Commit locally only (no push), per the standing instruction.
