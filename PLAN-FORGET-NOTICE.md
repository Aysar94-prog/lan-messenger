# Plan: notify a deleted contact, and let a group owner re-invite someone who left

## Goal (final, confirmed with the user)

The two-tier menu stays exactly as shipped in 0.8.11 — **Clear conversation** (wipes local history/attachments only, contact/group untouched, unchanged) and **Delete conversation** (forget a contact / leave a group). Two gaps get fixed:

1. Deleting a contact is currently silent — the other device never finds out. It should now revoke *their* verification of *you* automatically, with an on-device notice, delivered whenever they're next reachable (queued/retried, not "now or never").
2. Deleting a group conversation is "leave," and today that's a dead end — the owner's engine never re-sends an already-acknowledged invite, so there is no way back in. The owner needs a way to bring a departed member back.

Confirmed scope boundaries:
- Leaving a group still doesn't touch other (non-owner) members' state — only the **owner** learns you left, since only the owner can (re-)send invites.
- Rejoining is **owner-initiated** (a "Re-invite" action in the existing Members dialog), not something the departed member can request themselves.
- `Delete app data` queues a forget-notice for every contact it wipes, same as single-contact delete, for consistency.

## Design

### A) Contact forget notice

- New wire frame `LM4\tFORGET\t{senderId}`, sent inside the existing per-peer `Deliver()` loop (its own short connection, same shape as the existing `SEEN`/`SEENACK` exchange) — only proceeds if the recipient still replies `READY` to `Hello` (i.e. they still trust the sender's cert; if not, nothing to revoke, retry later).
- Reply `LM4\tFORGETACK`. On receipt, the sender drops the pending entry (persisted `forgotten` set of peer ids).
- Receiving side, in `Receive()`'s dispatch: reaching this frame already proved `Trusted(senderId, fingerprint)`, so call existing `Revoke(senderId)`, then raise a new `Forgotten` event (peer id) so the UI can show a notice ("X has removed you as a contact. Verify again to keep chatting.").
- `DeleteConversation`/`DeleteAllData`: when the target was a peer, add its id to `forgotten` in the same locked/rolled-back block that removes the peer record.
- New persisted row: `F\t{peerId}`.

### B) Group leave + owner re-invite

- The `Group` record gains a `Left` field (comma-joined member ids), parallel to the existing `Acknowledged`. Storage row `G` gains an optional 6th data field (old 5-field rows still load fine, `Left` defaults to empty — same pattern already used for `Message` rows growing over past releases).
- New wire frame `LM4\tLEAVE\t{groupId}\t{leavingMemberId}`, sent to the group's **owner** — queued/retried the same way as `FORGET` (a new persisted `pendingLeaves` map of `groupId -> ownerId`, captured at delete time since the local group record is gone right after).
- Reply `LM4\tLEAVEACK\t{groupId}`.
- Owner-side handling: if the owner still has that group and is actually its owner, add the leaving member to `Left` (persisted, rollback on save failure). **Not** removed from `Members` — membership itself stays exactly as created (this app's existing "membership is fixed" rule survives untouched); `Left` is just an auxiliary "currently inactive" marker.
- `Deliver()`'s existing per-owner invite-resend condition (`Members.Contains(peer) && !Acknowledged.Contains(peer)`) gets a `&& !Left.Contains(peer)` guard, so a departed member is never auto-re-invited.
- New owner-only engine method `ReinviteMember(groupId, memberId)`: clears that id from both `Acknowledged` and `Left`. The very next `Deliver()` cycle then sees them as "not yet acknowledged" again and resends the `GROUP` invite normally — the departed member's `AcceptGroup`/`acceptGroup` already succeeds for this (their local `groups` dict has no entry for that id anymore, so it's treated as a fresh invite, same members list as before).
- UI: the existing Members dialog (`ShowMembers`/`showMembers`) lists departed members distinctly and gives the owner a "Re-invite" action next to them.

## Tasks

| ID | Platform | Task | Depends on | Status |
|----|----------|------|------------|--------|
| T1 | Both | Design above — locked in | — | Done |
| T2 | Windows | `Group` record `+Left`; `Storage.cs` `F`/6-field-`G` load+save; `Conversations.cs`: `DeleteConversation`/`DeleteAllData` populate `forgotten`/`pendingLeaves`, new `ReinviteMember`; `PeerEngine.cs`: `Deliver()` sends `FORGET`/`LEAVE`, resend-guard, `Receive()` dispatches `FORGET`/`LEAVE`, new `Forgotten` event | T1 | Done |
| T3 | Android | Mirror T2 in `PeerEngine.java` / `GroupSync.java` / `AvatarSync.java`-shaped helpers as needed | T1 | Done |
| T4 | Windows | `ChatWindowDialogs.cs`: `ShowMembers` shows "left" members + Re-invite button; wire `engine.Forgotten` to a tray notice next to the existing `engine.Received` wiring | T2 | Done |
| T5 | Android | `showMembers`/Members alert equivalent gets a Re-invite action; wire a `forgotten` notice in `MessengerService` | T3 | Done |
| T6 | Both | Harness: `VERIFIED\t{peerId}` command (peers/verification isn't exposed by `STATE` today); confirm `GROUP`/`STATE` already expose enough to see `Left` for testing, add a command if not | T2, T3 | Done |
| T7 | Both | Extend `tests/delete_conversation.py`: contact delete → other side auto-unverifies (online and offline-then-reconnect cases); group delete → owner sees them as left, re-invite brings them back with full history-forward sync resuming | T6 | Done |
| T8 | Both | Update `PROJECT_STATUS.md`, `windows/STATUS.md`, `android/STATUS.md`, `README.md` | T2–T7 | Done |

All tasks complete. Full `tests/run.ps1` passed clean (all six new `delete_conversation.py` scenarios plus every prior suite, including the native Windows UI screenshot tests). One test bug was found and fixed along the way: the final "Delete app data" check in `delete_conversation.py` raced against `b`/`c`'s still-live background connections to `a` — their `remember()` call could re-add a peer in the instant after `DeleteAllData()`'s lock released, before the empty-state assertion ran. Fixed by stopping `b`/`c` before issuing `DELETEALL` instead of after (not an app bug — `a`'s engine was correctly wiped; the race was purely in the test's timing assumption).

## Acceptance criteria

- Deleting a verified contact revokes their verification of you within one delivery cycle once both are reachable; works even if they were offline at delete time.
- Deleting a group leaves it locally immediately; the owner (once reachable) sees you as departed.
- The owner can bring a departed member back with one action; the member's engine rejoins with the current member list, no re-verification needed (safety-code verification is per-device, not per-group, and is untouched by any of this).
- An old build that doesn't understand `FORGET`/`LEAVE` never acks; sender retries indefinitely (same tolerance already accepted elsewhere in this app for queued items) rather than erroring.
- Full `tests/run.ps1` stays green throughout.

## Verification

Same process as the last two rounds: Windows build + full `tests/run.ps1` after T2/T4, Android compile-check + full Android SDK build (signed APK) + full `tests/run.ps1` after T3/T5, then the extended `tests/delete_conversation.py` (T7) covering both new behaviors end to end on real engines, then docs (T8), then local commits (no push).
