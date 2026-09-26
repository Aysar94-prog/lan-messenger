# LAN Messenger project status and platform comparison

Reviewed 2026-09-26. Windows and Android are now on a unified version number, **2.0.1** (Android versionCode 24), released together. Do not infer feature parity from matching version numbers — they are aligned as a numbering convention, not a claim that every feature exists identically on both platforms; see the feature comparison below for the actual per-feature state.

## Working arrangement

- One repository at `D:/LAN-Messenger/source` and one shared development branch, currently `master`.
- [Windows status](windows/STATUS.md), [Android status](android/STATUS.md), and this comparison are the current platform records. `HANDOFF.md` is historical context.
- Releases and build/test output live in `D:/LAN-Messenger/outputs`. Do not duplicate the source tree.
- Label new work Windows, Android, or Both. Update the affected status and this comparison when behavior or compatibility changes.
- A platform UI change does not force a release on the other platform. A shared protocol change needs review and interoperability tests on both.
- Separate implemented behavior, automated verification, and acceptance on a real device.
- For new requests, plan first. Do not implement or build a release until the user explicitly asks to start.

## Current releases

| Platform | Release | Latest change | Verification |
|---|---|---|---|
| Windows | 2.0.1 | First release build with the join-request feature actually removed (2.0.0's artifacts still had it); group membership foundation unaffected: live roster with versioned propagation, migration of old-shape saved groups, a live capability check before any growth, per-member sync status in the Members dialog. Group "Delete conversation" is labeled "Leave group", reachable from the conversation list and from inside an open group chat | Build (0 warnings/errors) and full `tests/run.ps1` suite passed, including `tests/group_membership.py` and `tests/group_migration_broadcast.py`; no manual click-through in this environment |
| Android | 2.0.1, versionCode 24 | Same removal + group-membership foundation, mirrored; group delete relabeled "Leave group" (list + in-chat); "Hide groups" side-menu toggle | Signed/verified APK (`LanMessenger-2.0.1.apk`) and full `tests/run.ps1` suite passed; not device/emulator-verified |

Windows 2.0.1 interoperates with Android 2.0.1, and both remain wire-compatible with the 2.0.0 pair for any group that has never had its membership changed — see below. New direct-to-destination manual downloads require at least 0.8.7 on both peers; the version numbers are kept aligned across platforms as a release convention, not a signal that every release changes both sides equally.

2.0.0 shipped group membership no longer fixed after creation: `Members` is now a live roster that shrinks when someone leaves and grows when the owner adds someone, propagated to every active member as a versioned snapshot (new `MEMBERSUPDATE` frame) so a device that's been offline through several changes catches up in one step rather than replaying each one. Any growth (a direct add or Re-invite) requires a **fresh, live capability check** of everyone who'd be in the group afterward — a past success is never trusted as durable, since the same device could have been downgraded or restored from a backup since. A group that's never been mutated stays on the exact old wire shape, so an unrelated, not-yet-updated device is genuinely unaffected; only a group that has actually changed requires every device in it to be current. Already-saved groups migrate once, automatically, from the old shape where a departed member sat in the member list and a separate flag at the same time. See [PLAN-GROUP-MEMBERSHIP.md](PLAN-GROUP-MEMBERSHIP.md) for the full design.

2.0.0 also briefly shipped a join-request feature (any verified contact of a group's owner could ask to join, with an owner accept/ignore queue). **That feature was removed** by explicit request — the wire frames, storage rows, engine methods and UI it added are all gone from both platforms; the mutable-membership foundation above is unaffected and remains fully in place. A device still running the exact 2.0.0 build that shipped join-requests will simply never get a response to a `JOINREQUEST` frame it sends (same tolerance as any other frame an older/newer build doesn't understand) — group messaging, add, and re-invite are unaffected. **2.0.1 is the first release build that actually contains this removal** (2.0.0's own build artifacts still have join-requests baked in). Two further, unrelated UI changes are also in 2.0.1: "Delete conversation" is labeled "Leave group" when the target is a group (it was always actually a leave, never a real delete, and the old wording incorrectly implied verification was revoked), now reachable both from the conversation list and from inside an open group chat; and Android gained a "Hide groups" side-menu toggle, mirroring the existing "Show offline users" toggle.

A group ownership-transfer feature (an owner must hand off to another member before leaving a non-empty group) is designed but not yet implemented — see [PLAN-GROUP-OWNERSHIP-TRANSFER.md](PLAN-GROUP-OWNERSHIP-TRANSFER.md); the design has a known open question (how a member who's offline through the handoff safely learns the new owner) still being resolved before implementation starts. Until it lands, the UI does not stop a group's owner from leaving their own group, which currently orphans it for every remaining member (no one else is ever notified, and since only the owner can change membership, the roster is frozen from that point on — ordinary messaging between the remaining members is unaffected).

## Feature comparison

| Feature | Windows | Android |
|---|---|---|
| Discovery, verification, queued messages, delivery/read state | Implemented | Implemented |
| Groups, avatars, unread count | Implemented | Implemented |
| Clear chat locally while retaining contact/group and user-saved file | Implemented | Implemented |
| Delete conversation: forgets a contact (revokes verification, removes peer record); labeled "Leave group" for a group (always just a leave, never a real delete) | Implemented; right-click a conversation row | Implemented; long-press a conversation row |
| Delete app data: wipes every conversation/contact/group/attachment, keeps identity/name/avatar | Implemented; toolbar button | Implemented; side-menu item, also resets daily upload usage |
| Forgetting a contact also notifies them: their verification of you is auto-revoked, with an on-device notice | Implemented | Implemented |
| Leaving a group lets the owner see who departed and re-invite them back into the same group | Implemented; "Re-invite" in Members dialog | Implemented; "Re-invite" in Members dialog |
| Group membership can change after creation (not just re-invite): live roster, versioned propagation, migration of old-shape saved groups, a live capability check before any growth | Implemented | Implemented |
| Blue online / gray offline presence | Implemented | Implemented |
| People-screen side menu | Not implemented | Implemented on Android; Profile and About live in it |
| Hide offline direct peers in the people list | Not implemented | Implemented on Android as an Android-local persisted `Show offline users` flag, default off; a people-list display filter for direct-peer rows only; it does not change engine state, routing or the wire, and deep links and open chats bypass the list rather than being filtered |
| Hide all group rows in the people list | Not implemented | Implemented on Android as a separate Android-local persisted `Hide groups` flag, default off, mirroring the offline-peer filter exactly (display-only, engine/wire unaffected, deep links and open chats bypass it) |
| Attachment draft followed by explicit Send | Implemented | Implemented |
| Automatic inline ordinary photos | Implemented within preview/codec limits | Implemented within preview/codec limits |
| Ordinary attachments up to 1 GiB; Fast offers up to 1 TiB | Implemented | Implemented |
| Manual Download asks destination, writes one receiver copy, Open launches it | Implemented | Implemented; destination provider must support seeking |
| Fast payload plaintext with authenticated TLS control | Implemented | Implemented |
| Manual download resume at 256 KiB boundaries | Implemented | Implemented |
| Background operation | Tray process until Exit | Foreground service subject to OS/battery limits |
| Built-in camera capture | Not implemented | Implemented |
| Initial show newest 10 messages, load 20 older on upward scroll | Implemented (paged, per-conversation view state) | Implemented at UI level |
| Attachment-thumbnail cache across chat changes | Implemented; 16 MiB reference-counted LRU, skips non-image files | 16 MiB Activity LRU cache |
| Retain unchanged cards on status updates | Implemented | Visible cards are rebuilt when the view signature changes |
| Keep complete UI trees for multiple active chats | Not implemented | Not implemented |
| Daily aggregate upload tiers: unlimited below 2 GiB, then 30/20/10 MiB/s at 2/5/10 GiB | Not implemented by earlier Android-only scope | Implemented |
| Comprehensive anti-flood policy | Not implemented beyond basic connection/transfer limits | Not implemented beyond basic limits and daily upload policy |

Automatic ordinary images are the exception to destination selection and use an app-private cache. Fast image offers remain manual.

## Performance and compatibility limits

Message records are resident in each engine's memory. Windows 0.8.9 renders only the newest 10 messages and pages backward, but `PeerEngine.Messages` still scans engine-held records; it is not a disk query for only 10 records. Measured before/after (tests/MeasureWindows, loopback): the 112-message chat opened in 8 ms on 0.8.8 and 2 ms on 0.8.9, so scanning was never the dominant cost and no engine recent-message index was added (plan W07). There is no fully implemented "ask only for new records" path across all UI/storage flows.

The automatic-photo legacy paths differ: Android has encrypted FETCHSTREAM with 256 KiB resume, while Windows uses FETCH with 100 MiB cached segments. The new manual FETCHDIRECT path works on both.

## Proposed work, not implemented

1. Confirm the Windows 0.8.10 rendering, paging, and repaint behavior on the affected PC/DPI setup; check large-chat opening and throughput on real LAN hardware.
2. Add a Windows engine recent-message index only if real-device measurements later show message scanning dominating open time (deferred by the finished pagination plan with recorded evidence).
3. Measure and consider retention/update of active chat UI on both platforms (Windows now caches up to 3 recent chats' cards; Android still rebuilds visible cards on signature changes).
4. Add Windows upload tiers only if the user expands the previously Android-only scope.
5. Validate Android destination choice, pause/resume, Open, background operation, and throughput on physical phones.

The repository has a GitHub origin. Recent release commits are local; no push was made for those releases. Remote refs were not refreshed for this status audit.
