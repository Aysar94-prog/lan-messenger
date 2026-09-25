# Android status

Reviewed 2026-09-25. Release: **0.8.7**, versionCode 21. See the [platform comparison](../PROJECT_STATUS.md).

Unreleased working-tree change: the people-screen side menu and the `Show offline users` filter. The `0.8.7` APK in outputs was rebuilt from this tree, so that artifact now also contains this change; versionCode is unchanged, so it still installs over an existing 0.8.7 install.

## Implemented

- Messaging, groups, verification, blue/gray presence, attachment draft before Send, and local chat clear.
- Automatic inline ordinary photos and built-in camera capture with preview before sending.
- Initially render newest 10 messages, then 20 more when scrolling upward; MainActivity has a 16 MiB thumbnail LRU cache.
- Aggregate daily upload limits: unlimited below 2 GiB; 30 MiB/s at 2-5 GiB, 20 MiB/s at 5-10 GiB, and 10 MiB/s at 10 GiB and above. Usage appears in profile.
- Aggregated network reads/writes, timeout-watchdog cleanup, and encrypted FETCHSTREAM resume for the older private-cache path.
- Since 0.8.7: ACTION_CREATE_DOCUMENT destination choice, direct single-copy manual download, ACTION_VIEW opening, and 256 KiB direct resume.
- Fast file payload uses unencrypted TCP data with authenticated TLS control; ordinary files use TLS.
- Foreground service for background receipt, subject to Android battery and network restrictions.
- People-screen side menu opened from the header bar. It holds Profile and About, which moved out of the tools row, plus a `Show offline users` switch.
- `Show offline users` is an Android-local `SharedPreferences` flag in `lan_messenger_ui`, default false. With it off, the filter runs in exactly one place: while the people screen builds its visible row list, and only for direct peers. An offline direct peer contributes no row; group rows are always kept. This is a display filter, not a state or routing rule. Engine state is unchanged — the peer, its messages, its unread count and any queued send are untouched — and nothing about it is filtered on the wire or in any protocol field. An incoming deep link and an already-open chat do not go through the list at all, so they bypass list visibility rather than being filtered by it, and either still reaches a hidden offline peer. The preference is read off the UI thread before the first render so the list never flashes unfiltered rows, and it is part of the list render signature, together with an explicit invalidation on toggle, so the rows and the empty-state hint always match the flag.

## Limits and unimplemented behavior

- The chosen Android document provider must support reading, writing, and seeking. Some cloud providers cannot support direct resume.
- Newest-10 is a UI construction optimization; `messages()` still scans engine-held message records.
- Complete view trees for several chats are not retained. Switching reconstructs the UI, helped by the message records and thumbnail cache.
- On a changed chat signature, the visible cards are rebuilt. This does not fully mirror Windows's current-card reuse.
- Daily limits are local to the app/device, not centralized enforcement against a modified app.
- The offline filter is presentation-time only, lives in the people-list presentation layer, and is Android-local. Windows has no equivalent setting, the flag is never sent on the wire, and this is a deliberate asymmetry rather than a protocol or compatibility gap. Because it never leaves the Activity, it cannot constrain `PeerEngine` state, message routing, or wire behavior.
- The side menu opens from its header-bar button and closes via the scrim, its Close button, choosing an action, or Back. It has no slide animation, no edge-swipe or drag gesture, and it stays open across a pause/resume cycle.
- If the preference write fails, the value still applies for the current session and is lost on restart.

## Verification and handoff

- APK build and signature verification with the original signing key passed.
- Java/C# engine interoperability, resume/disconnect, integrity, and daily-policy tests passed.
- A 1025 MiB Fast test passed on a computer with a 64 MiB Java test heap; that is not a physical-phone benchmark.
- Real-device acceptance is pending for destination chooser, file opening, camera, background behavior, and Wi-Fi throughput.
- UI/cache: `src/net/lanmsg/chat/MainActivity.java`. SAF/service: `MessengerService.java`. Direct transfer: `DirectFileTransfer.java`, `DownloadDestination.java`. Legacy encrypted path: `ResumableTransfer.java`, `ResumeStore.java`. Upload tiers: `DailyUploadPolicy.java`.
- Package: `D:/LAN-Messenger/outputs/LanMessenger-0.8.7.apk`; install over the existing app without uninstalling. Test outputs: `D:/LAN-Messenger/outputs/.build/release-tests-087`.
- Last Android code commit: `7d9d99c`. Windows-only commit `07f2e54` needs no Android update. The people side menu work is uncommitted in the working tree.

### People side menu and offline filter: implementation, automated verification, device acceptance

Implemented (code, not yet accepted on a device):

- `MainActivity.java` only. `android/STATUS.md` and `PROJECT_STATUS.md` are the other changed files. No engine, protocol or Windows file was touched, and no dependency was added.
- The content view is now one full-screen `FrameLayout` stage holding the existing chrome column, so the menu overlays the whole screen including the header bar without altering the layout each screen builds.
- Menu contents: Profile, About, `Show offline users`, and a one-line explanation. Profile and About are the same actions as before, moved out of the tools row; Refresh, Add by IP, New group and the avatar stayed on that row.
- The one filter is a single skip inside `render()` while it builds the visible conversation list, and it applies to direct peers only. `PeerEngine.peers()`, `messages()`, `unread()`, `groups()` and `pending()` are unchanged, and the header status line still reports the real online and queued counts. The deep-link path calls `showChat` directly and never reads the flag, so an incoming deep link or an already-open chat bypasses list visibility instead of being filtered.
- Back closes an open menu first, then falls through to the previous chat-to-people and exit behavior.

Automated verification passed (no device or emulator was used):

- The project's `android/build.ps1` pipeline completed: javac, jar, d8, aapt, zipalign, apksigner sign, apksigner verify. It was not the unmodified script that ran to the end — see the known build-script issue below; the successful run used a temporary copy with only that one line neutralized, and the working-tree `android/build.ps1` is unchanged from commit `7d9d99c`. Verified v2 and v3 APK signature schemes, one signer, `net.lanmsg.chat` versionCode 21 / versionName 0.8.7, minSdk 26, targetSdk 34. No signing material was read or printed.
- The compiled `MainActivity.class` from that build was disassembled with `javap` and contains `openMenu`, `closeMenu`, `barButton`, `menuItem`, `readShowOffline`, `setShowOffline`, the `stage`/`menuOverlay`/`menuOpen`/`showOffline` fields, the `lan_messenger_ui` and `show_offline_users` keys, and the new user-visible strings. The same check on the `onBackPressed` and `setShowOffline` bytecode confirms menu-close-before-navigation and the persisted write.
- Full `tests/run.ps1` suite passed, including the Java and C# engine tests, all interoperability, transfer, resume, group and policy suites, and the Windows native UI tests. This is regression cover for the unchanged engine, not cover for the new Android UI.
- Known build-script issue, pre-existing and not caused by this change: under Windows PowerShell 5.1, `build.ps1` sets `$ErrorActionPreference = 'Stop'` on line 7, so javac's `Note: Some input files use or override a deprecated API.` on stderr aborts the script even though javac exits 0. A direct invocation of the unmodified `android/build.ps1` therefore stopped at that point and produced no APK. The unmodified script as of commit `0c66207` produces the same note, and the same note is produced with and without this change. The verification build therefore ran the script with only that one line neutralized, through a temporary copy placed in `android/` and deleted afterwards, so every tool, path and signing input was the project's own. `build.ps1` itself was not modified.

Device acceptance: not started. No phone or emulator was used, so nothing here is confirmed on real hardware. Still to check by hand: the menu opens, closes and overlays the header correctly; the switch hides and restores offline rows live; Back closes the menu without leaving the screen; the flag survives an app restart; an all-offline list shows the correct hint; a deep link still opens a hidden peer's chat; unread counts, avatars, Refresh, Add by IP and New group behave with rows hidden.

## Next proposed check

Acceptance on two physical phones: destination choice, connection interruption/resume, Open, background receipt, and throughput. Engine tests alone do not complete this acceptance check.
