# LAN Messenger project status and platform comparison

Reviewed 2026-09-26. Windows and Android are now on a unified version number, **2.0.0** (Android versionCode 23), released together. Do not infer feature parity from matching version numbers — they are aligned as a numbering convention, not a claim that every feature exists identically on both platforms; see the feature comparison below for the actual per-feature state.

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
| Windows | 2.0.0 | Group membership is no longer fixed after creation: live roster with versioned propagation, migration of old-shape saved groups, a live capability check before any growth, and the join-request UI (request queue with Accept/Ignore, per-member sync status) in the Members dialog | Build (0 warnings/errors) and full `tests/run.ps1` suite passed, including `tests/group_membership.py`, `tests/group_migration_broadcast.py`, `tests/join_requests.py`, and a new `tests/WindowsUi` scenario that drives the real Members dialog and its Accept button end-to-end; no other manual click-through in this environment |
| Android | 2.0.0, versionCode 23 | Same group-membership and join-request feature, mirrored | Signed/verified APK (`LanMessenger-2.0.0.apk`) and full `tests/run.ps1` suite passed; the join-request UI is compile-verified only — no device/emulator interaction check; other physical SAF/viewer/Wi-Fi checks also pending |

Windows 2.0.0 interoperates with Android 2.0.0, and both remain wire-compatible with the prior 0.8.12 pair for any group that has never had its membership changed — see below. New direct-to-destination manual downloads require at least 0.8.7 on both peers; the version numbers are kept aligned across platforms as a release convention, not a signal that every release changes both sides equally.

This release folds in the join-request layer (G1–G8, previously tracked as "in progress" below) on both platforms: group membership is no longer fixed after creation. `Members` is now a live roster that shrinks when someone leaves and grows when the owner adds someone, propagated to every active member as a versioned snapshot (new `MEMBERSUPDATE` frame) so a device that's been offline through several changes catches up in one step rather than replaying each one. Any growth (a direct invite, Re-invite, or an accepted join request) requires a **fresh, live capability check** of everyone who'd be in the group afterward — a past success is never trusted as durable, since the same device could have been downgraded or restored from a backup since. A group that's never been mutated stays on the exact old wire shape, so an unrelated, not-yet-updated device is genuinely unaffected; only a group that has actually changed requires every device in it to be current. Already-saved groups migrate once, automatically, from the old shape where a departed member sat in the member list and a separate flag at the same time.

On top of that foundation, any verified contact of a group's owner can send a request to join (given the group's ID, shared out of band), which the owner reviews in a queue in the Members dialog — Accept adds them via the same capability-checked path as a direct invite; Ignore silently drops the request, with a 1-hour cooldown before the same person can request again. Each active member's row also shows the owner whether they're caught up to the latest membership snapshot or lagging. See [PLAN-GROUP-JOIN-REQUESTS.md](PLAN-GROUP-JOIN-REQUESTS.md) for the full design — the entire plan (G1–G8) is done on both platforms: the engine layer (M1–M3, G2–G3, G6–G7), tested cross-platform (`tests/group_membership.py`, `tests/group_migration_broadcast.py`, `tests/join_requests.py` — 63 assertions combined, including a real 16-member group at capacity), and the UI (G4–G5). Windows's UI was verified with a real end-to-end interaction test (`tests/WindowsUi`, driving the actual Members dialog and its Accept button); Android's UI is compile-verified via a signed APK build but has not been exercised on a device or emulator.

## Feature comparison

| Feature | Windows | Android |
|---|---|---|
| Discovery, verification, queued messages, delivery/read state | Implemented | Implemented |
| Groups, avatars, unread count | Implemented | Implemented |
| Clear chat locally while retaining contact/group and user-saved file | Implemented | Implemented |
| Delete conversation: forgets a contact (revokes verification, removes peer record) or leaves a group | Implemented; right-click a conversation row | Implemented; long-press a conversation row |
| Delete app data: wipes every conversation/contact/group/attachment, keeps identity/name/avatar | Implemented; toolbar button | Implemented; side-menu item, also resets daily upload usage |
| Forgetting a contact also notifies them: their verification of you is auto-revoked, with an on-device notice | Implemented | Implemented |
| Leaving a group lets the owner see who departed and re-invite them back into the same group | Implemented; "Re-invite" in Members dialog | Implemented; "Re-invite" in Members dialog |
| Group membership can change after creation (not just re-invite): live roster, versioned propagation, migration of old-shape saved groups, a live capability check before any growth, and requesting to join a group by ID with an owner accept/ignore queue | Implemented, engine + UI | Implemented, engine + UI (UI not yet device-verified) |
| Blue online / gray offline presence | Implemented | Implemented |
| People-screen side menu | Not implemented | Implemented on Android; Profile and About live in it |
| Hide offline direct peers in the people list | Not implemented | Implemented on Android as an Android-local persisted `Show offline users` flag, default off; a people-list display filter for direct-peer rows only, group rows are never hidden; it does not change engine state, routing or the wire, and deep links and open chats bypass the list rather than being filtered |
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
