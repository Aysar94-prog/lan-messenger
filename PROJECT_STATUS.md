# LAN Messenger project status and platform comparison

Reviewed 2026-09-29. Current releases are **Windows 2.1.0** and **Android 2.2.1** (versionCode 28). Releases are independent; see the feature comparison below for the actual per-feature state.

## Working arrangement

G1–G3 offline product controls are implemented in source and included in the 2.1.1 Android APK, but not in the Windows 2.1.0 or prior Android 2.1.0 release packages. Windows toolbar/tray and Android people-menu/notification controls share their respective engine transitions. A separate persisted default-Online request is distinct from actual state (bind failures remain Offline and can be retried). Go Offline enforces a strict no-LAN boundary while retaining local history, contacts, groups, cached attachments, partial transfers and queued direct/group sends; in-flight transfers interrupt and resume on reconnection. Remote presence ages out after approximately 12 seconds. Android uses one service-owned engine: actual Offline is bound-only/non-foreground while the Activity is bound, and engine-load/network-bind failures clear the started-service lifetime so it may stop after unbind; foreground and multicast lock are held only while networking. Its connection preference does not change people-list filters. LM4 protocol and compatibility are unchanged. Historical source-based Android physical-device/two-device LAN checks passed for both transfer directions and ADB service cleanup; these were not performed on the packaged 2.1.1 APK. Device acceptance of that APK remains pending.

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
| Windows | 2.1.0 | Group ownership transfer: an owner must hand off to another active member before leaving a non-empty group; the departure completes automatically once every member has caught up. Plus everything from 2.0.1 (join-request removal, "Leave group" relabel) | Build (0 warnings/errors) and full `tests/run.ps1` suite passed, including the new `tests/ownership_transfer.py`, `tests/group_membership.py` and `tests/group_migration_broadcast.py`; no manual click-through in this environment |
| Android | 2.2.1, versionCode 28 | Same-day fix for a real bug found via actual device testing of 2.2.0: the voice-draft preview row and the received-message Playable card row could lay out past the visible width with no scroll container, leaving Send/Save genuinely unreachable on a real phone. Otherwise identical to 2.2.0: Voice Messages Phase 1 (A01-A11, in source — see the Feature comparison below; platform-local only, interoperability not yet verified) plus everything from 2.1.1 (Offline controls G1-G3, 2.1.0 ownership transfer, 2.0.1 carryover) | Locally built and signed APK (`outputs/LanMessenger-2.2.1.apk`, 127,319 bytes, SHA-256 `bbc7ffba…`; manifest: `outputs/SHA256SUMS-Android-2.2.1.txt`). Original-key signer continuity confirmed directly via `keytool -printcert` (byte-identical certificate SHA-256 to 2.2.0/2.1.1) and `apksigner verify` (v2/v3, one signer). Built by running `android/build.ps1`'s exact pipeline manually (its own pre-existing, unrelated stderr-abort issue is noted in `android/STATUS.md`); every tool, path and signing input was still the project's own. No further device acceptance beyond the manual testing that found the bug it fixes. |

Windows 2.1.0 interoperates with Android 2.2.1, and both remain wire-compatible with the 2.0.x pair for any group that has never had its membership changed and never had its ownership transferred — see below. New direct-to-destination manual downloads require at least 0.8.7 on both peers; platform releases are independent and do not imply feature parity. Voice Messages (Android 2.2.x) has no Windows release yet — it is source-only on the Windows side (see the Feature comparison below) — and is platform-local/not interoperability-tested regardless.

2.0.0 shipped group membership no longer fixed after creation: `Members` is now a live roster that shrinks when someone leaves and grows when the owner adds someone, propagated to every active member as a versioned snapshot (new `MEMBERSUPDATE` frame) so a device that's been offline through several changes catches up in one step rather than replaying each one. Any growth (a direct add or Re-invite) requires a **fresh, live capability check** of everyone who'd be in the group afterward — a past success is never trusted as durable, since the same device could have been downgraded or restored from a backup since. A group that's never been mutated stays on the exact old wire shape, so an unrelated, not-yet-updated device is genuinely unaffected; only a group that has actually changed requires every device in it to be current. Already-saved groups migrate once, automatically, from the old shape where a departed member sat in the member list and a separate flag at the same time. See [PLAN-GROUP-MEMBERSHIP.md](PLAN-GROUP-MEMBERSHIP.md) for the full design.

2.0.0 also briefly shipped a join-request feature (any verified contact of a group's owner could ask to join, with an owner accept/ignore queue). **That feature was removed** by explicit request in 2.0.1 — the wire frames, storage rows, engine methods and UI it added are all gone from both platforms; the mutable-membership foundation above is unaffected and remains fully in place. A device still running the exact 2.0.0 build that shipped join-requests will simply never get a response to a `JOINREQUEST` frame it sends (same tolerance as any other frame an older/newer build doesn't understand) — group messaging, add, and re-invite are unaffected. Two further, unrelated UI changes landed alongside the removal: "Delete conversation" is labeled "Leave group" when the target is a group (it was always actually a leave, never a real delete, and the old wording incorrectly implied verification was revoked), now reachable both from the conversation list and from inside an open group chat; and Android gained a "Hide groups" side-menu toggle, mirroring the existing "Show offline users" toggle.

**2.1.0**: group ownership can now be handed off. Previously the owner was fixed forever and the UI didn't actually stop them from leaving their own group — doing so silently orphaned it for everyone else (nobody was ever told, and since only the owner could change membership, the roster was permanently frozen). Now, "Leave group" for the owner of a non-empty group shows a "Choose a new admin" picker instead of the normal confirmation; picking someone calls the new `TransferOwnership`, which requires a fresh, live capability check (`CAPS>=2`) of *every* current active member — refusing outright, nothing changed, if anyone can't support it or hasn't finished onboarding yet. The owner's own departure is deferred (shown as "Leaving — waiting for members to catch up", composer disabled) until every other member has actually caught up to the handoff, not just the incoming owner — the old owner stays the sole delivery/leave-acceptance authority for that one change in the meantime, via a durable `pendingOwnershipHandoff` record that survives an app restart. This mirrors the existing versioned-snapshot convergence guarantee rather than inventing new trust machinery; see [PLAN-GROUP-OWNERSHIP-TRANSFER.md](PLAN-GROUP-OWNERSHIP-TRANSFER.md) for why a simpler "wait for just the new owner" design (an earlier draft) doesn't work, and for the accepted limitation that remains (a member who never comes back online blocks the departure indefinitely — a signed, relayable handoff proof would remove this, but is a separate future enhancement, not built speculatively). A group that's never had an ownership transfer is completely unaffected for old builds; only a group that's actually used this feature requires every device in it to be current, same shape as the existing membership-mutation compatibility split. An owner-only group still leaves with a plain, immediate local delete — nothing to hand off.

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
| Group ownership can be handed off; the owner must do so before leaving a non-empty group (owner-only groups still leave with a plain local delete) | Implemented | Implemented (not yet device-verified) |
| Blue online / gray offline presence | Implemented | Implemented |
| Go Online/Go Offline control (strict no-LAN Offline; local history, queued direct/group sends and resumable transfer partials retained; persisted default-Online request separate from actual state/bind failure) | Implemented in source, not in Windows 2.1.0 package; toolbar and tray share transition and show Retry online after bind failure; Refresh/Add by IP disabled Offline; Close hides to tray, Exit terminates | Included in Android 2.1.1 APK, device acceptance pending; people menu and notification share one service-owned engine; Refresh/Add by IP disabled Offline, verification explains Online requirement; Offline bound-only/non-foreground while Activity bound; foreground/multicast only during networking; `START_NOT_STICKY` avoids OS-driven restart |
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
| Voice messages: record, send, receive and play short voice clips (reuses the existing encrypted Normal attachment store; no new wire frame) | Implemented in source (Phase 2, W01-W11, WT01-WT06 all written; WT04's exact admission-ordering edge case untested) | Implemented in source (Phase 1, A01-A11, AT01/AT02/AT06; AT04 partial, same untested edge case as Windows; AT03/AT05 not attempted, no confirmed audio device in this environment) |

Automatic ordinary images are the exception to destination selection and use an app-private cache. Fast image offers remain manual.

Offline automation (not device acceptance): Android SDK source compilation and full `tests/run.ps1` (including Windows native UI and cross-platform `tests/offline_lifecycle.py`) passed with SDK 9 MSBuild and in-workspace test output. The loopback runner checks accepted idle socket closure, Offline gating, delayed avatar/group work, interrupted Fast/ordinary/cache transfers, partial stability, integrity and duplicate-free retry. One earlier full-suite 16-member capability stress failure passed on isolated rerun and final full rerun. A 2.1.1 APK has since been built, signed and independently verified (see Current releases above). Windows source-only Offline controls have no Windows 2.1.0 package acceptance; Android 2.1.1 APK device/emulator acceptance is **Pending-Unavailable**. Historical Android source-based two-device Offline checks passed but do not constitute 2.1.1 APK acceptance; remote peers age out after approximately 12 seconds, not instantaneously.

Voice messages (in source on both platforms, not released): record/send/receive/play short voice
clips per `plan-v003` — see [windows/STATUS.md](windows/STATUS.md) and
[android/STATUS.md](android/STATUS.md) for the full write-ups. Both platforms' implementation
tasks (W01-W11 / A01-A11) are code-complete and pass their full local regression suites with
zero regressions; both used real compile/build verification throughout, not blind ports. A real
duplicate-Send bug in the draft registry's crash-boundary reconciliation was found during the
Android verification pass and fixed on **both** platforms in the same session (see either
status file's A05/W02 entry). Remaining on both: the scheduler's exact 3-consecutive-voice-then-
1-image admission-ordering (WT04/AT04's untested edge case, deliberately not forced black-box
against real async timing), device-lifecycle interaction tests needing real/simulated audio
hardware (Windows got WT03/WT05 coverage because its sandboxed environment happened to expose a
real `waveIn`/`waveOut` device; no Android equivalent has been confirmed here), and manual
two-device acceptance on physical hardware. **Platform-local only; interoperability between the
Windows and Android implementations has not yet been verified** (Phase 3 of the plan, not yet
started) — this row will be revised once both platform exit gates and the integration phase
complete.

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
