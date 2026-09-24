# Android status

Reviewed 2026-09-25. Release: **0.8.7**, versionCode 21. See the [platform comparison](../PROJECT_STATUS.md).

## Implemented

- Messaging, groups, verification, blue/gray presence, attachment draft before Send, and local chat clear.
- Automatic inline ordinary photos and built-in camera capture with preview before sending.
- Initially render newest 10 messages, then 20 more when scrolling upward; MainActivity has a 16 MiB thumbnail LRU cache.
- Aggregate daily upload limits: unlimited below 2 GiB; 30 MiB/s at 2-5 GiB, 20 MiB/s at 5-10 GiB, and 10 MiB/s at 10 GiB and above. Usage appears in profile.
- Aggregated network reads/writes, timeout-watchdog cleanup, and encrypted FETCHSTREAM resume for the older private-cache path.
- Since 0.8.7: ACTION_CREATE_DOCUMENT destination choice, direct single-copy manual download, ACTION_VIEW opening, and 256 KiB direct resume.
- Fast file payload uses unencrypted TCP data with authenticated TLS control; ordinary files use TLS.
- Foreground service for background receipt, subject to Android battery and network restrictions.

## Limits and unimplemented behavior

- The chosen Android document provider must support reading, writing, and seeking. Some cloud providers cannot support direct resume.
- Newest-10 is a UI construction optimization; `messages()` still scans engine-held message records.
- Complete view trees for several chats are not retained. Switching reconstructs the UI, helped by the message records and thumbnail cache.
- On a changed chat signature, the visible cards are rebuilt. This does not fully mirror Windows's current-card reuse.
- Daily limits are local to the app/device, not centralized enforcement against a modified app.

## Verification and handoff

- APK build and signature verification with the original signing key passed.
- Java/C# engine interoperability, resume/disconnect, integrity, and daily-policy tests passed.
- A 1025 MiB Fast test passed on a computer with a 64 MiB Java test heap; that is not a physical-phone benchmark.
- Real-device acceptance is pending for destination chooser, file opening, camera, background behavior, and Wi-Fi throughput.
- UI/cache: `src/net/lanmsg/chat/MainActivity.java`. SAF/service: `MessengerService.java`. Direct transfer: `DirectFileTransfer.java`, `DownloadDestination.java`. Legacy encrypted path: `ResumableTransfer.java`, `ResumeStore.java`. Upload tiers: `DailyUploadPolicy.java`.
- Package: `D:/LAN-Messenger/outputs/LanMessenger-0.8.7.apk`; install over the existing app without uninstalling. Test outputs: `D:/LAN-Messenger/outputs/.build/release-tests-087`.
- Last Android code commit: `7d9d99c`. Windows-only commit `07f2e54` needs no Android update.

## Next proposed check

Acceptance on two physical phones: destination choice, connection interruption/resume, Open, background receipt, and throughput. Engine tests alone do not complete this acceptance check.
