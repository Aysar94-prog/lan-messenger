# LAN Messenger project status and platform comparison

Reviewed 2026-09-25 against source at commit 07f2e54. Do not infer feature parity from version numbers.

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
| Windows | 0.8.8 | Photo/card repaint fix during scroll and chat switching | Build and native UI tests passed; user confirmation on the affected PC is pending |
| Android | 0.8.7, versionCode 21 | Chosen download destination, open downloaded file, plaintext Fast payload | Signed APK and engine tests passed; physical SAF/viewer/Wi-Fi checks pending |

Windows 0.8.8 interoperates with Android 0.8.7. New direct-to-destination manual downloads require at least 0.8.7 on both peers.

## Feature comparison

| Feature | Windows | Android |
|---|---|---|
| Discovery, verification, queued messages, delivery/read state | Implemented | Implemented |
| Groups, avatars, unread count | Implemented | Implemented |
| Clear chat locally while retaining contact/group and user-saved file | Implemented | Implemented |
| Blue online / gray offline presence | Implemented | Implemented |
| Attachment draft followed by explicit Send | Implemented | Implemented |
| Automatic inline ordinary photos | Implemented within preview/codec limits | Implemented within preview/codec limits |
| Ordinary attachments up to 1 GiB; Fast offers up to 1 TiB | Implemented | Implemented |
| Manual Download asks destination, writes one receiver copy, Open launches it | Implemented | Implemented; destination provider must support seeking |
| Fast payload plaintext with authenticated TLS control | Implemented | Implemented |
| Manual download resume at 256 KiB boundaries | Implemented | Implemented |
| Background operation | Tray process until Exit | Foreground service subject to OS/battery limits |
| Built-in camera capture | Not implemented | Implemented |
| Initially show newest 10 messages, load 20 older on upward scroll | Not implemented | Implemented at UI level |
| Attachment-thumbnail cache across chat changes | Not implemented; avatar cache and current-chat card reuse only | 16 MiB Activity LRU cache |
| Retain unchanged cards on status updates | Implemented | Visible cards are rebuilt when the view signature changes |
| Keep complete UI trees for multiple active chats | Not implemented | Not implemented |
| Daily aggregate upload tiers: unlimited below 2 GiB, then 30/20/10 MiB/s at 2/5/10 GiB | Not implemented by earlier Android-only scope | Implemented |
| Comprehensive anti-flood policy | Not implemented beyond basic connection/transfer limits | Not implemented beyond basic limits and daily upload policy |

Automatic ordinary images are the exception to destination selection and use an app-private cache. Fast image offers remain manual.

## Performance and compatibility limits

Message records are resident in each engine's memory. Android's 10-message improvement limits UI construction, but `messages()` still scans engine-held records; it is not a disk query for only 10 records. There is no fully implemented "ask only for new records" path across all UI/storage flows.

The automatic-photo legacy paths differ: Android has encrypted FETCHSTREAM with 256 KiB resume, while Windows uses FETCH with 100 MiB cached segments. The new manual FETCHDIRECT path works on both.

## Proposed work, not implemented

1. Obtain user acceptance of the Windows 0.8.8 repaint fix on the affected PC.
2. Follow [the Windows pagination/cache plan](windows/PLAN-CHAT-PERFORMANCE.md): newest 10 messages, progressive history, bounded thumbnail and chat-view caches.
3. Measure and consider retention/update of active chat UI on both platforms.
4. Add Windows upload tiers only if the user expands the previously Android-only scope.
5. Validate Android destination choice, pause/resume, Open, background operation, and throughput on physical phones.

The repository has a GitHub origin. Recent release commits are local; no push was made for those releases. Remote refs were not refreshed for this status audit.
