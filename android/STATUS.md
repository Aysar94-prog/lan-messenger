# Android status

## Voice Messages implemented in source (Phase 1 / A01-A11, not a release)

Android Phase 1 of the shared Voice Messages feature (`plan-v003`, tracked at
[PLAN-VOICE-MESSAGES-ANDROID.md](../PLAN-VOICE-MESSAGES-ANDROID.md)): record, send, receive and
play short voice clips, reusing the existing encrypted Normal attachment store with a
`voice-<message-id>.lanvoice.wav` marker filename — no new LM4 wire frame, matching the Windows
implementation exactly (see [Windows status](../windows/STATUS.md)). Fixed format: RIFF/WAVE,
16 kHz mono 16-bit PCM, 640-byte/20 ms frames, max 300 s/9.6 MB. Picked up after another agent
(a concurrently running opencode/OAK session) spent several hours hardening the shared I01-I04
contract layer (`tests/voice_messages/check_contract.py`, `validation-contract.md`) but had not
yet written any Android production code.

`AudioRecord`/`AudioTrack` capture/playback (one dedicated thread per adapter blocking in
`read()`/`write()` — architecturally simpler than the Windows `winmm` P/Invoke adapters, which
need to guard against an OS-invoked native callback on an arbitrary thread; Android's model has
no such callback to race against); a durable, encrypted, capped (10-entry) draft registry with
startup reconciliation (including a real duplicate-Send bug found and fixed on **both**
platforms during the Android verification pass — see below); transactional Send; Candidate/
Fetching/Playable/Invalid/Unavailable receiver cards; one active inline player app-wide with
seven-step seeking (±10 s buttons) and no plaintext playback file; audio-focus handling (an
Android-specific addition beyond Windows' equivalent task, which has no system-wide audio
session model); voice folded into the existing automatic-media scheduler under the shared nine
fixed rules; accessibility coverage that in one respect **exceeds** the Windows implementation
(`setAccessibilityLiveRegion` proactively announces ticking labels to TalkBack — Windows'
equivalent task explicitly could not achieve this without a custom `AccessibleObject` subclass,
and accepted it as a documented limitation instead).

**A01-A11 (all 11 implementation tasks) are code-complete.** Build/verification: this sandboxed
environment has a real Android SDK (`android.jar`, `build-tools 35.0.0`, `d8`, and the existing
signing key), so every task was verified by running the actual `javac -source 8 -target 8` +
`d8` steps `android/build.ps1` uses directly against every production source file — real
compile+dex confidence throughout, not a blind port, the same rigor Windows only got once its
own build was unblocked mid-session. `android/build.ps1` itself was **not** run directly for
this work (its own known pre-existing issue — `$ErrorActionPreference='Stop'` aborting on
javac's benign deprecation note on stderr, documented further down this file — predates Voice
Messages and is unrelated to it); the equivalent javac/d8 steps were run manually instead, same
as every prior release's verification in this file already had to work around.

AT01 (`tests/VoiceMessagesCheck.java`, real production classes against the shared manifest) and
AT02 (`tests/voice_drafts_android.py`, 8 real crash-boundary scenarios including actual process
kills and on-disk corruption) both pass. AT04 is partial (`tests/voice_scheduler_android.py`,
real end-to-end automatic-download scheduling, plus a deterministic fairness-counter unit test)
— the scheduler's exact admission-ordering edge case is untested for the same black-box-timing-
fragility reason recorded on the Windows side. AT06 (architecture-boundary + canonical-fixture-
source checks, `tests/voice_architecture_check.py`, now covering both platforms in one script)
passes, and the **complete `tests/run.ps1` regression suite passed clean, zero regressions**
from any A01-A10 change. **AT03 and AT05 (permission/lifecycle edge cases needing real
`AudioRecord`/`AudioTrack` device behavior, and one-player-enforcement/export/accessibility UI
interaction tests) were not attempted** — Windows only got its equivalent WT03/WT05 coverage
because its sandboxed environment happened to expose a real `waveIn`/`waveOut` device; no
equivalent has been confirmed for Android's audio stack here, and Android's `android.jar` is a
compile-only stub that cannot actually execute real device code even where it links. **Manual
two-device acceptance on physical hardware is Pending, cannot be performed by an agent** —
same limitation already recorded throughout this file for every other Android feature.

This is **platform-local only; interoperability with the Windows Phase 2 work (developed
concurrently in this same repository) has not yet been verified** — that is Phase 3 of the
plan, not yet started.

## Offline controls (G1–G3) — built as 2.1.1 local APK

`PeerEngine.goOffline()` stops a reusable session while retaining identity, contacts, groups, local history, cached attachments, partial transfers and queued direct/group sends; `close()` remains terminal (G1). G2 moves sole engine ownership into a bindable service, reads a separate persisted default-Online request before startup (independent of people-list filters), and shares notification/people-menu transitions. Offline is a strict no-LAN boundary: no discovery/listening/connections, Refresh/Add by IP, verification, group capability queries or remote downloads. Refresh/Add by IP are disabled while Offline; verification explains that Online is required. An interrupted transfer retains progress for resume after reconnect; remote peers turn gray after approximately 12 seconds. Offline demotes the connected-device foreground service and releases the multicast lock; chats/profile/queued sends remain accessible while the Activity stays bound. Explicit Offline and engine-load/network-bind failures clear the started-service lifetime, so an actual-Offline service is bound-only and may be destroyed after unbind while the persisted Online request remains available for Retry online. Foreground and multicast lock are held only during networking. `START_NOT_STICKY` avoids OS-driven background restarts; Activity startup reads the persisted preference and requests Online explicitly when selected, otherwise only binds locally. Service/UI behavior has **not** been exercised on a device or emulator. No LM4 wire/compatibility change.

G3 automated check: fixed `goOffline()` for a unit test that intentionally exercises storage without starting the engine (executors are then absent). Cross-platform `tests/offline_lifecycle.py` checks accepted idle socket closure, Add-by-IP/refresh and remote-download gating, rejected discovery while Offline, delayed avatar sync, group capability refusal, Fast direct/ordinary direct/encrypted ordinary cache receiver interruption and Fast sender interruption, stable partial state, no premature completion marker, integrity and duplicate-free retry. `tests/android_service_lifecycle.py` guards the bound-only cleanup on engine-load failure, bind failure, and unbind while actual Offline. Android SDK compilation, APK packaging/signature verification, and Windows SDK 9 build passed after the final service-lifetime correction. Historical source-based physical checks passed on two Android devices for sender-side and receiver-side Offline transitions during transfer, Online resume, integrity, and duplicate avoidance. Historical source-based ADB checks confirmed no crash/ANR and no remaining Started service after removing the Offline Activity from Recents; reopening preserved local content and Online recovery succeeded. None of these device checks accepts the packaged 2.1.1 APK.

Prior Android releases: **2.2.2** (2026-09-29, versionCode 29, replayable voice messages, drag
seek bar, sender-sees-own-voice-as-player), **2.2.1** (2026-09-29, versionCode 28, Send/Save-
button scroll fix), **2.2.0** (2026-09-29, versionCode 27, the first release packaging the Voice
Messages Phase 1 work), **2.1.1** (2026-09-27, versionCode 26). See the
[platform comparison](../PROJECT_STATUS.md).

Reviewed 2026-09-29. Current Android local APK build: **2.2.2**, versionCode 29 — a same-day
follow-up addressing real device-testing feedback on 2.2.1's voice messages, all in
`VoicePlayer.java`/`VoicePlayback.java`/`VoiceCard.java`/`VoiceUi.java`/`MainActivity.java`:
1) `VoicePlayer.resume()` was missing the end-of-track position reset that `play()` already had,
so pressing Play again on a finished voice message did nothing — `togglePlayback` always calls
`resume()` for the same active key, so a completed clip could never actually be replayed. Fixed
by mirroring `play()`'s `if (playPosition >= dataEnd()) { playPosition = info.dataOffset;
epoch++; }` reset in `resume()` too, so a voice message can now be replayed any number of times.
2) The fixed ±10s buttons are replaced with a draggable WhatsApp-style seek bar
(`VoicePlayback.buildSeekBar`/`updateSeekBar`), wired to `VoicePlayer.seek(long)`'s existing
absolute-position API, in both the received-message Playable card and the own-draft preview row.
3) The sender's own sent voice message now renders as a proper voice-message player (duration,
Play/Pause, seek bar, Save) instead of a generic file entry — `MainActivity.java`'s render() gate
no longer excludes `mine` messages from `VoiceCard.addVoiceCard`; this reverses the earlier
Phase-1 design decision documented in `VoiceCard.java`'s A07 comment and mirrored on Windows in
`ChatWindowVoiceCard.cs`'s W07 comment, per explicit user feedback that it should match WhatsApp
instead. `outputs/LanMessenger-2.2.2.apk` (131,415 bytes), SHA-256
`36f066f30d906bf13b65bdd98c42606a1af33d5619823b894241346f5e221fe5` (manifest:
`outputs/SHA256SUMS-Android-2.2.2.txt`). Signed with the original development key — confirmed
byte-identical signer certificate (`keytool -printcert`, SHA-256
`7F:4A:07:94:3D:01:DA:12:66:E4:F2:D3:CE:75:71:65:C9:61:9C:9A:4E:F7:4E:74:1C:58:F8:EE:E0:8D:41:61`)
to 2.2.1/2.2.0/2.1.1, so an existing install upgrades cleanly without uninstalling. `apksigner
verify` confirms v2/v3 with one signer. Built the same way as 2.2.0/2.2.1: `android/build.ps1`'s
exact pipeline run manually, command by command, rather than the script itself — its own known
pre-existing issue (`$ErrorActionPreference='Stop'` aborting on javac's benign deprecation note
on stderr, noted further down this file) predates Voice Messages and is unrelated to it; every
tool, path and signing input was still the project's own, and `android/build.ps1` itself was
not modified. Verified: real-toolchain `javac`/`d8` compile clean; no device/emulator acceptance
has been performed yet for this specific build — the fixes directly target the three issues the
user reported from real-device testing of 2.2.1, but that testing itself is still outstanding.

The Windows implementation still deliberately does *not* render the sender's own sent voice
message as a player (`ChatWindowVoiceCard.cs`'s W07 comment) and still only offers ±10s seek
steps (`ChatWindowVoicePlayback.cs`'s W08 comment) — this pass was Android-only, addressing the
user's Android device testing; Windows parity for these three UX points has not been raised with
the user and is not yet planned.

**Post-release icon pass (2026-09-29, same day)**: user feedback after 2.2.2 asked for WhatsApp-
style iconography — a triangle Play button and a microphone icon for the record trigger, instead
of text labels. Changed all voice transport buttons from text to Unicode glyphs: `▶`/`⏸`
(`VoicePlayback.PLAY_ICON`/`PAUSE_ICON`, used by both `VoiceCard.java`'s Playable row and
`VoiceUi.java`'s draft preview), `⏹` for Stop-recording, and `🎤` for the compose row's
"Record voice" trigger in `MainActivity.java` (content description text is unchanged for
accessibility — only the visible label changed). No layout, wire, or storage change. Verified
through the real Android toolchain (`javac -source 8 -target 8` + `d8`), 0 errors. Packaged as
**Android 2.2.3** (versionCode 30). Same signer as every prior release. No device/emulator
acceptance yet for this build.

**Second replay-bug fix (2026-09-29, same day)**: after actually installing and testing 2.2.3 on
a real device, the user confirmed the UI correctly detects completion (the button does flip back
to `▶`) but tapping it again still produces no audio — so the 2.2.2 `VoicePlayer.resume()`
position-reset fix alone was not sufficient. Root cause: a plain `AudioTrack.play()` call after
the track naturally drained (buffer underrun, never explicitly `stop()`ped) does not reliably
resume producing audio on every device — this is a known `AudioTrack` streaming-mode quirk, only
observable on real hardware, not from source review. Fixed by having `resume()` call
`track.stop()`+`track.flush()` before `track.play()` whenever it's restarting from the end (the
same clean-reset pattern `seek()` already used for its own pause+flush). Verified through the
real Android toolchain, 0 errors. Packaged as **Android 2.2.4** (versionCode 31), same signer as
every prior release. Still no device/emulator acceptance recorded here for this specific build —
depends on the user re-testing on the same device that surfaced the underlying quirk.

2.0.0's headline change was group membership no longer being fixed after creation (see below). It also still carries everything packaged in 0.8.12: the people-screen side menu and the `Show offline users` filter; Delete conversation / Delete app data (mirrors Windows); and a contact-forget notice plus group-leave + owner re-invite (mirrors the same Windows addition — see [Windows status](../windows/STATUS.md)). 2.0.0 briefly also shipped a join-request feature; 2.0.1 removed it. **2.1.0's headline change is group ownership transfer** — see below.

## Implemented

- **2.0.0** (foundation, still current): group membership is no longer fixed after creation — mirrors the Windows foundation exactly (see [Windows status](../windows/STATUS.md) for the full design). `Group.members` is now the live active roster with a `membersVersion` counter, propagated to every active member as a full snapshot (new `LM4\tMEMBERSUPDATE`/`MEMBERSUPDATEACK` frame in `PeerEngine.java`/`GroupSync.java`) adopted whenever strictly newer. `GROUP` invites stay 6 fields while a group's version is 0 and switch to 7 (with the version) once mutated; the dispatch (`m.length==6||m.length==7`) accepts either. Any owner-initiated growth (`GroupSync.addMember`, used by `reinviteMember`) requires a fresh `LM4\tCAPS` query (mirroring `FILECAPS`) of everyone who'd be in the group afterward — never cached — refusing with a clear error otherwise; leaving is never gated by this. Already-saved groups (the old `Group.left`-overlapping shape) migrate once automatically on load. `MainActivity.showMembers()` sources departed members from the `allKnownMembers()` API, its Re-invite button runs on a background thread (`reinviteMember` does real network I/O), and each active member's row carries a "Synced"/"Catching up" tag (`PeerEngine.memberAckedVersion(groupId, peerId)` compared against `Group.membersVersion`). See `PLAN-GROUP-MEMBERSHIP.md` (source root).
- **2.0.1**: 2.0.0 briefly also shipped a join-request feature (any verified contact of a group's owner could ask to join by ID, with an owner accept/ignore queue in `showMembers()`, via `requestJoin`/`acceptJoinRequest`/`ignoreJoinRequest`, `JOIN_REQUEST_COOLDOWN_MS`, new `LM4\tJOINREQUEST`/`JOINREQUESTACK` frames and `J`/`Q` storage rows). **That feature has been removed** by explicit request — all of the above is gone from `PeerEngine.java`/`GroupSync.java`/`MainActivity.java`. The mutable-membership foundation above (`addMember`, capability checks, migration, `reinviteMember`, sync status) is untouched. A saved data file that still has old `J`/`Q` rows from before the removal loads fine (those rows are now silently skipped rather than rejected). Alongside the removal: `confirmDeleteConversation`'s dialog now says "Leave group?" (not "Delete conversation?") for a group, now also reachable via a new "Leave group" item in the open chat's overflow menu (`chatMenu`), not just the conversation-list long-press; and a new "Hide groups" side-menu toggle was added — see below.
- **2.1.0**: group ownership can now be handed off, closing the 2.0.1 gap noted above (nothing used to stop the owner from leaving their own group, silently orphaning it). New `PeerEngine.transferOwnership(groupId, newOwnerId)` (delegating to `GroupSync.transferOwnership`) — owner-only, requires a fresh live `CAPS>=2` from *every* current active member (refuses outright, nothing mutated, if anyone can't support it), and refuses if any member hasn't yet acked their first invite (the `GROUP` frame's sender-must-equal-claimed-owner invariant makes it impossible to deliver a first-time invite on behalf of a different owner mid-handoff). `Group.owner` had to become mutable (it was `final`) since it now updates immediately, everywhere, as part of adopting the transfer's snapshot — but the *old* owner must stay the delivery/leave-acceptance authority for this one change (not the new owner) until every other member has actually caught up, tracked via a new durable `pendingOwnershipHandoff` set (persisted as a new `O` row) that survives a restart; `deliver()`'s broadcast gate and `handleLeave`'s accept check are both widened to check it alongside `g.owner.equals(id)`. `MEMBERSUPDATE` gains a 6th (owner) field once a group has ever transferred (tracked via a new persisted `everTransferredOwnership`/`T` row); `CAPS` bumps its reply from `1` to `2`; `addMember` requires `CAPS>=2` (not just `>=1`) for any group that's ever transferred. `confirmDeleteConversation` shows a "Choose a new admin" picker instead of the normal confirmation when the owner tries to leave a non-empty group; the people-list row and open-chat heading both show "Leaving — waiting for members to catch up" (composer/send disabled) until the deferred departure actually completes. See [PLAN-GROUP-OWNERSHIP-TRANSFER.md](../PLAN-GROUP-OWNERSHIP-TRANSFER.md) — including why a simpler "wait for just the new owner" design (an earlier draft, caught on external review before any code was written) doesn't work, and the accepted limitation that remains (a member who never comes back online blocks the departure indefinitely).
- 0.8.12: long-press a conversation row for "Delete conversation" — clears its history/attachments and, for a contact, revokes verification and removes the peer record entirely (a group is left); a rediscovered forgotten contact reappears as a brand-new, unverified device. "Delete app data" in the side menu wipes every conversation/contact/group/attachment while keeping identity, display name, profile picture, and also resets the daily upload counter (`deleteConversation`/`deleteAllData` in `PeerEngine.java`).
- 0.8.12: deleting a contact now also notifies them. `deleteConversation`/`deleteAllData` queue a peer id in a persisted `forgotten` set; `deliver()` sends them a new `LM4\tFORGET` frame (retried until acked, same shape as `SEEN`/`SEENACK`) whenever that peer is next reachable, which calls `revoke` on their side and raises a new `forgottenCallback`, wired in `MessengerService.java` to a notification ("Removed you as a contact..."). Deleting a group is still a leave (unaffected for other members), but it now queues a `LM4\tLEAVE` frame to the group's owner (persisted `pendingLeaves`); the owner records the departed member in a new `Group.left` field (an optional 7th, backward-compatible `G` storage field, via `GroupSync.handleLeave`) and `deliver()`'s invite-resend loop skips anyone in `left`. `showMembers()` in `MainActivity.java` now shows departed members distinctly with an owner-only "Re-invite" button, calling the new `reinviteMember(groupId, memberId)`, which clears the Acknowledged/Left flags so the next delivery cycle resends the `GROUP` invite and they rejoin with the same member list — no re-verification needed. An old build that doesn't understand `FORGET`/`LEAVE` simply never acks; the sender keeps retrying rather than erroring.
- Messaging, groups, verification, blue/gray presence, attachment draft before Send, and local chat clear.
- Automatic inline ordinary photos and built-in camera capture with preview before sending.
- Initially render newest 10 messages, then 20 more when scrolling upward; MainActivity has a 16 MiB thumbnail LRU cache.
- Aggregate daily upload limits: unlimited below 2 GiB; 30 MiB/s at 2-5 GiB, 20 MiB/s at 5-10 GiB, and 10 MiB/s at 10 GiB and above. Usage appears in profile.
- Aggregated network reads/writes, timeout-watchdog cleanup, and encrypted FETCHSTREAM resume for the older private-cache path.
- Since 0.8.7: ACTION_CREATE_DOCUMENT destination choice, direct single-copy manual download, ACTION_VIEW opening, and 256 KiB direct resume.
- Fast file payload uses unencrypted TCP data with authenticated TLS control; ordinary files use TLS.
- Foreground service for background receipt, subject to Android battery and network restrictions.
- People-screen side menu opened from the header bar. It holds Profile and About, which moved out of the tools row, plus `Show offline users` and `Hide groups` switches.
- `Show offline users` is an Android-local `SharedPreferences` flag in `lan_messenger_ui`, default false. With it off, the filter runs in exactly one place: while the people screen builds its visible row list, and only for direct peers. An offline direct peer contributes no row. This is a display filter, not a state or routing rule. Engine state is unchanged — the peer, its messages, its unread count and any queued send are untouched — and nothing about it is filtered on the wire or in any protocol field. An incoming deep link and an already-open chat do not go through the list at all, so they bypass list visibility rather than being filtered by it, and either still reaches a hidden offline peer. The preference is read off the UI thread before the first render so the list never flashes unfiltered rows, and it is part of the list render signature, together with an explicit invalidation on toggle, so the rows and the empty-state hint always match the flag.
- `Hide groups` (post-2.0.0) is a second, independent Android-local `SharedPreferences` flag (`hide_groups`, same file), default false, mirroring `Show offline users` exactly: a display-only skip of every group row while the people screen builds its visible list, no engine/wire/routing effect, same deep-link/open-chat bypass, same read-before-first-render and signature/invalidation treatment. `PeopleListView.readHideGroups`/`setHideGroups`; the filter itself lives in `MainActivity.render()` alongside the existing offline-peer skip.

## Limits and unimplemented behavior

- The chosen Android document provider must support reading, writing, and seeking. Some cloud providers cannot support direct resume.
- Newest-10 is a UI construction optimization; `messages()` still scans engine-held message records.
- Complete view trees for several chats are not retained. Switching reconstructs the UI, helped by the message records and thumbnail cache.
- On a changed chat signature, the visible cards are rebuilt. This does not fully mirror Windows's current-card reuse.
- Daily limits are local to the app/device, not centralized enforcement against a modified app.
- Both the offline-peer filter and the Hide-groups filter are presentation-time only, live in the people-list presentation layer, and are Android-local. Windows has no equivalent setting, neither flag is ever sent on the wire, and this is a deliberate asymmetry rather than a protocol or compatibility gap. Because they never leave the Activity, neither can constrain `PeerEngine` state, message routing, or wire behavior.
- The side menu opens from its header-bar button and closes via the scrim, its Close button, choosing an action, or Back. It has no slide animation, no edge-swipe or drag gesture, and it stays open across a pause/resume cycle.
- If the preference write fails, the value still applies for the current session and is lost on restart.

## Verification and handoff

- Mutable group membership foundation: APK build and signature verification passed against the current working tree. Full `tests/run.ps1` passed, including `tests/group_membership.py` (cross-platform) — convergence on add/remove/re-invite, a device offline through several changes catching up in one step, a group shrinking to just the owner and regrowing past the creation-only `<3` floor, a live (never-cached) capability check that a reachable-but-uncooperative or genuinely unreachable peer both fail identically, an already-confirmed member later going uncooperative blocking further growth until they cooperate again, leaving never gated by anyone's capability, migration of an old-shape saved group on both platforms, and the 16-member cap enforced against the live roster (a real 16-process scenario, re-pointed at direct `REINVITE` add after the join-request layer it originally rode on was removed). New harness commands used: `REINVITE`, `LEFT` (now via `allKnownMembers`), `LEGACY`, `ROSTER`. No manual click-through of `showMembers()`'s updated display was performed in this environment.
- 2.0.1 join-request removal + Leave-group relabel (list + in-chat) + Hide-groups toggle: signed APK build and signature verification passed, and the full `tests/run.ps1` suite passed. Removing `addMember`'s join-request-auto-clear hook and `deleteAllData`'s join-request snapshot/clear/rollback fragments required surgical edits (not blanket deletion) to avoid disturbing the surrounding capability-check-then-mutate-then-save structure — verified byte-for-byte behaviorally unchanged by `group_membership.py`/`group_migration_broadcast.py` passing unmodified. The Hide-groups toggle and Leave-group relabel/in-chat entry point are Android UI changes with no automated interaction test (same limitation noted throughout this file for Android UI) — compile-verified via the signed build only. This environment has a long-lived, unkillable zombie `dotnet` process that intermittently causes transient TLS/timeout contention specifically under the heaviest 16-17-real-process scenario in a full-suite run — every such failure seen while preparing this release was independently reproduced-and-confirmed-clean on an isolated rerun before being treated as environmental rather than a regression.
- 2.1.0 group ownership transfer: signed APK build and signature verification passed, and the full `tests/run.ps1` suite passed, including the new `tests/ownership_transfer.py` (cross-platform, exercises both the Java and C# engine) — a basic transfer converges everywhere and the old owner's departure completes automatically once everyone has caught up, after which the new owner can grow the group; a transfer is refused outright if any member can't answer a fresh `CAPS>=2`; transferring to yourself or a non-member is refused; the pending handoff survives the old owner's own restart and still completes afterward; a transfer is refused while any member is still mid-onboarding. Not built: a dedicated test for a still-lagging member's `LEAVE` arriving specifically during the pending window — reliably forcing that exact race needs an artificial mid-flight pause this harness can't provide. The new "Choose a new admin" picker and "Leaving — waiting for…" state are Android UI changes with no automated interaction test (same limitation as every other Android UI change in this file) — compile-verified via the signed build only.
- 0.8.12 (forget-notice / group re-invite): APK build and signature verification with the original signing key passed against the current working tree, versionCode 22 / versionName 0.8.12. The full `tests/run.ps1` suite passed, including the extended `tests/delete_conversation.py`, which exercises the real Java engine for both new behaviors: contact delete followed by the other side's own verification being auto-revoked once the `FORGET` notice arrives (new `VERIFIED` harness command), and a non-owner group member leaving, the owner seeing them recorded in `left` (new `LEFT` harness command), re-inviting them (new `REINVITE` harness command), and confirming they rejoin and receive new group messages again. No manual click-through of `showMembers()`'s new "Re-invite" button was performed in this environment.
- APK build and signature verification with the original signing key passed.
- Java/C# engine interoperability, resume/disconnect, integrity, and daily-policy tests passed.
- A 1025 MiB Fast test passed on a computer with a 64 MiB Java test heap; that is not a physical-phone benchmark.
- Real-device acceptance is pending for destination chooser, file opening, camera, background behavior, and Wi-Fi throughput.
- Voice Messages (Phase 1): `VoiceMessages.java` (transport-independent PCM/WAV/marker/seek core), `VoiceDrafts.java` (draft registry + `sendVoiceDraft`, static methods on `PeerEngine`), `VoiceRecorder.java`/`VoicePlayer.java` (`AudioRecord`/`AudioTrack` adapters), `VoiceUi.java` (own-draft record/send UI), `VoiceCard.java` (receiver cards), `VoicePlayback.java` (shared one-active-player controller), `TransferManager.java` (`queueAutomaticMedia`, the renamed/extended scheduler). Tests: `tests/VoiceMessagesCheck.java` (AT01), `tests/voice_drafts_android.py` (AT02), `tests/voice_scheduler_android.py` + `tests/VoiceSchedulerCheck.java` (AT04, partial), `tests/voice_architecture_check.py` (AT06). Progress tracker: [PLAN-VOICE-MESSAGES-ANDROID.md](../PLAN-VOICE-MESSAGES-ANDROID.md).
- UI/cache: `src/net/lanmsg/chat/MainActivity.java` (`showMembers()`'s sync status, `confirmDeleteConversation`'s Leave-group wording and its `showTransferOwnershipPicker`, the Hide-groups filter and the "Leaving…"/"Leaving — waiting for…" rendering in `render()`). Side menu: `PeopleListView.java` (`readHideGroups`/`setHideGroups`). SAF/service: `MessengerService.java`. Direct transfer: `DirectFileTransfer.java`, `DownloadDestination.java`. Legacy encrypted path: `ResumableTransfer.java`, `ResumeStore.java`. Upload tiers: `DailyUploadPolicy.java`. Groups (live membership, `CAPS`, `addMember`, migration, `memberAckedVersion`, ownership transfer): `GroupSync.java`/`PeerEngine.java`.
- Current Android package: `D:/LAN-Messenger/outputs/LanMessenger-2.1.1.apk`; install over the existing app without uninstalling, after checking `outputs/SHA256SUMS-Android-2.1.1.txt`. The 2.1.0 APK is the prior package, retained for reference; test outputs: `D:/LAN-Messenger/outputs/.build/release-tests-087`.
- Last Android code commit: `7d9d99c`; the people side menu, delete-conversation/delete-app-data, forget-notice/group-re-invite, the 2.0.0 group-membership foundation + (later removed) join-request layer, the 2.0.1 join-request removal + Leave-group relabel, and the 2.1.0 group ownership-transfer feature committed locally on `master`, not pushed.

### People side menu and offline filter: implementation, automated verification, device acceptance

Implemented and packaged in 0.8.12 (code, not yet accepted on a device):

- `MainActivity.java` only. `android/STATUS.md` and `PROJECT_STATUS.md` are the other changed files. No engine, protocol or Windows file was touched, and no dependency was added.
- The content view is now one full-screen `FrameLayout` stage holding the existing chrome column, so the menu overlays the whole screen including the header bar without altering the layout each screen builds.
- Menu contents: Profile, About, `Show offline users`, and a one-line explanation. Profile and About are the same actions as before, moved out of the tools row; Refresh, Add by IP, New group and the avatar stayed on that row.
- The one filter is a single skip inside `render()` while it builds the visible conversation list, and it applies to direct peers only. `PeerEngine.peers()`, `messages()`, `unread()`, `groups()` and `pending()` are unchanged, and the header status line still reports the real online and queued counts. The deep-link path calls `showChat` directly and never reads the flag, so an incoming deep link or an already-open chat bypasses list visibility instead of being filtered.
- Back closes an open menu first, then falls through to the previous chat-to-people and exit behavior.

Automated verification passed (no device or emulator was used):

- The project's `android/build.ps1` pipeline completed: javac, jar, d8, aapt, zipalign, apksigner sign, apksigner verify. It was not the unmodified script that ran to the end — see the known build-script issue below; the successful run used a temporary copy with only that one line neutralized, and the working-tree `android/build.ps1` is otherwise unchanged (its output filename now points at `LanMessenger-0.8.12.apk`). Verified v2 and v3 APK signature schemes, one signer, `net.lanmsg.chat` versionCode 22 / versionName 0.8.12, minSdk 26, targetSdk 34. No signing material was read or printed.
- The compiled `MainActivity.class` from that build was disassembled with `javap` and contains `openMenu`, `closeMenu`, `barButton`, `menuItem`, `readShowOffline`, `setShowOffline`, the `stage`/`menuOverlay`/`menuOpen`/`showOffline` fields, the `lan_messenger_ui` and `show_offline_users` keys, and the new user-visible strings. The same check on the `onBackPressed` and `setShowOffline` bytecode confirms menu-close-before-navigation and the persisted write.
- Full `tests/run.ps1` suite passed, including the Java and C# engine tests, all interoperability, transfer, resume, group and policy suites, and the Windows native UI tests. This is regression cover for the unchanged engine, not cover for the new Android UI.
- Known build-script issue, pre-existing and not caused by this change: under Windows PowerShell 5.1, `build.ps1` sets `$ErrorActionPreference = 'Stop'` on line 7, so javac's `Note: Some input files use or override a deprecated API.` on stderr aborts the script even though javac exits 0. A direct invocation of the unmodified `android/build.ps1` therefore stopped at that point and produced no APK. The unmodified script as of commit `0c66207` produces the same note, and the same note is produced with and without this change. The verification build therefore ran the script with only that one line neutralized, through a temporary copy placed in `android/` and deleted afterwards, so every tool, path and signing input was the project's own. `build.ps1` itself was not modified.

2.0.1 addendum: a second, independent `Hide groups` switch was added to the same menu, same pattern (`PeopleListView.readHideGroups`/`setHideGroups`, a new `hide_groups` key in the same `lan_messenger_ui` preferences file, the same single-skip-in-`render()` shape, this time for group rows). Verified by a clean signed APK build only — no `javap` bytecode check or device acceptance was performed for this specific addition.

Device acceptance of this historical 0.8.12 menu/filter work was not started at the time: no phone or emulator was used for those UI checks, and the packaged 2.1.1 APK remains unaccepted. Still to check by hand: the menu opens, closes and overlays the header correctly; both switches hide and restore their rows live, independently of each other; Back closes the menu without leaving the screen; both flags survive an app restart; an all-hidden list shows the correct hint; a deep link still opens a hidden peer's or group's chat; unread counts, avatars, Refresh, Add by IP and New group behave with rows hidden.

## Next proposed check

Acceptance on two physical phones: destination choice, connection interruption/resume, Open, background receipt, and throughput. Engine tests alone do not complete this acceptance check.

Device/click-through acceptance of the mutable-membership foundation's UI (Members dialog sync status, Re-invite), the 2.0.1 changes (Leave-group wording and its new in-chat entry point, the Hide-groups toggle), and the 2.1.0 ownership-transfer UI (the "Choose a new admin" picker, the "Leaving — waiting for…" state) is still outstanding — none of this has been exercised on a real device or emulator. See `PLAN-GROUP-MEMBERSHIP.md` and `PLAN-GROUP-OWNERSHIP-TRANSFER.md`.
