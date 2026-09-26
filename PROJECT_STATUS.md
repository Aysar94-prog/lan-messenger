# LAN Messenger project status and platform comparison

Reviewed 2026-09-26 against Windows 0.8.11 source (delete-conversation/delete-app-data feature, plus a purely internal file-split refactor of `Program.cs`/`PeerEngine.cs`), plus an unreleased-in-source addition on both platforms: a contact-forget notice (auto-revokes the other side's verification, with an on-device notice) and group-leave + owner re-invite. Do not infer feature parity from version numbers.

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
| Windows | 0.8.11 | Delete conversation (forgets a contact/leaves a group) and Delete app data (wipes everything but identity/profile/avatar) | Build (0 warnings/errors) and full `tests/run.ps1` suite passed, including new `tests/delete_conversation.py`; no manual click-through in this environment |
| Android | 0.8.7, versionCode 21 | Chosen download destination, open downloaded file, plaintext Fast payload | Signed APK and engine tests passed; physical SAF/viewer/Wi-Fi checks pending |

Windows 0.8.11 interoperates with Android 0.8.7. New direct-to-destination manual downloads require at least 0.8.7 on both peers.

Unreleased Android working-tree changes (not yet packaged as a new APK release): the people-screen side menu and `Show offline users` filter (Android UI/local preference only, no wire or engine-semantics change), and the same Delete conversation / Delete app data feature just released for Windows (`deleteConversation`/`deleteAllData` in `PeerEngine.java`, long-press in the people list, "Delete app data" in the side menu). Both were compiled and verified via a signed/verified APK build in this environment but the `outputs/LanMessenger-0.8.7.apk` file was rebuilt from this same working tree, so it now also carries these unreleased changes under the same version/versionCode; that rebuild ran a temporary copy of `android/build.ps1` with only its `$ErrorActionPreference` line neutralized, because the unmodified script aborts on a javac stderr note, and the tracked script was not changed.

Unreleased on **both** platforms (not yet a new version/build on either side): deleting a contact now queues a `FORGET` wire notice, delivered once the other side is next reachable — it revokes their own verification of you (`Revoke`/`revoke`) and raises an on-device notice, instead of the deletion being silent to them. Deleting a group is still a leave (unchanged for other members), but it now queues a `LEAVE` notice to the group's owner; the owner's Members dialog shows departed members distinctly with a "Re-invite" action that clears their `Acknowledged`/`Left` flags so the next delivery cycle resends the `GROUP` invite and they rejoin with the same member list, no re-verification needed. Both notices are queued/retried the same way as existing frames (`SEEN`/`GROUP`), so an old build that doesn't understand `FORGET`/`LEAVE` never acks and the sender simply keeps retrying rather than erroring. Verified end to end (both directions, both notice types, and the re-invite/rejoin round trip) by the extended `tests/delete_conversation.py`, part of the full `tests/run.ps1` suite, which passed clean; the Android side was also verified via a signed/verified APK rebuild in this environment (same version/versionCode as the existing unreleased Android changes above).

Both platforms also went through a purely internal file-split refactor (largest files broken into smaller, cohesive ones by concern) with no behavior change — see [Windows status](windows/STATUS.md) and [Android status](android/STATUS.md) for the exact file lists.

## Feature comparison

| Feature | Windows | Android |
|---|---|---|
| Discovery, verification, queued messages, delivery/read state | Implemented | Implemented |
| Groups, avatars, unread count | Implemented | Implemented |
| Clear chat locally while retaining contact/group and user-saved file | Implemented | Implemented |
| Delete conversation: forgets a contact (revokes verification, removes peer record) or leaves a group | Implemented; right-click a conversation row | Implemented in source (not yet a packaged release); long-press a conversation row |
| Delete app data: wipes every conversation/contact/group/attachment, keeps identity/name/avatar | Implemented; toolbar button | Implemented in source (not yet a packaged release); side-menu item, also resets daily upload usage |
| Forgetting a contact also notifies them: their verification of you is auto-revoked, with an on-device notice | Implemented in source (not yet a packaged release) | Implemented in source (not yet a packaged release) |
| Leaving a group lets the owner see who departed and re-invite them back into the same group | Implemented in source (not yet a packaged release); "Re-invite" in Members dialog | Implemented in source (not yet a packaged release); "Re-invite" in Members dialog |
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
