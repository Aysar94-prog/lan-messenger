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

## Voice calls A00–A04 — real WebRTC media, validated on a physical device (2026-10-01)

The WebRTC AAR (`io.github.webrtc-sdk:android:150.7871.01`, Maven Central) is downloaded and cached
by `android/prepare-webrtc.ps1`, and `android/build-voice.ps1` is the single AAR-aware build
pipeline (javac + d8 + aapt + apksigner, native `.so` packaged under `lib/<abi>/`). The AAR is now a
**hard dependency**: `WebRtcCallMedia` uses direct `org.webrtc` imports, so there is no
build-without-WebRTC mode. `android/build.ps1` is a thin wrapper over `build-voice.ps1` that keeps
its signing-key contract and its refuse-to-overwrite guard. WebRTC added roughly 12 MB to the APK
(arm64-only build: 6 MB; all ABIs: ~47 MB).

Validated on a physical Samsung SM-S908E (Android 16 / SDK 36, arm64-v8a) over network ADB: the
native library loads, hardware AEC/NS is used, and a loopback self-test drove two real
peer connections through SDP offer/answer (Opus), ICE (5 + 3 host candidates), DTLS-SRTP and
bidirectional audio to `packetsLost=0`, plus mute — **SELFTEST PASS**. The self-test was an
`exported="true"` no-permission receiver, since removed from both source and manifest and kept
only as `state/on-device/CallSelfTest.java.txt`; it is not in the installed package.

Runtime testing found and fixed real defects that static review had missed: an SDP result-plumbing
bug after the reflection-to-typed rewrite, and — most importantly — `PeerConnection.Observer`
callbacks arriving on the WebRTC signaling thread, where any blocking listener work deadlocked the
media thread against the caller. Ten build-pipeline bugs were also fixed, including `continue`
inside `ForEach-Object` silently terminating `build-voice.ps1` with exit code 0, native libs
packaged with backslashes (unrecognised by Android's loader), `-VersionName`/`-VersionCode` never
reaching the manifest, and an aapt quirk that rejects a `-M` file not named exactly
`AndroidManifest.xml`. See `state/handoff-A04-device.md`.

`tests/CallCheck.java` passes **142/142**; 37 production files compile clean against the AAR. The
device APK is `outputs/LanMessenger-2.2.7.apk` (versionCode 34), installed over 2.2.6 / 33. **No
real two-party call has been made** — a loopback proves the media stack but not the LAN signalling
path, and the Windows peer does not exist yet. Audio was never listened to, and A07 route
application, A05 arbitration under a live call, and runtime microphone foreground-service
behaviour all remain unverified on device.

## Voice calls A08–A10 — call UI, notifications, entry point (2026-10-01)

A call can now be placed and answered. The subsystem existed but had no way in: no entry point, and
a rejected invitation told the caller nothing. Phase A08–A10 closes that and four holes found while
wiring it.

**Entry point (A08).** `chatMenu` offers **Call** for a direct contact only — group calls are out of
scope, so a group row never shows it. `startCallTo` gates in the order a user can act on (not
online → unverified → offline → microphone permission) and every refusal is a specific message. The
permission is requested before the invitation and the pending peer ID survives the grant, so the
call is placed exactly once. The channel is opened on a background thread. `CallController.TransportFactory`
makes the controller mint the call ID and open the matching socket *inside its own lock*, so an
INVITE can never precede the connection carrying it. Turning "Allow incoming calls" off during a
ring **declines that call immediately** rather than letting it ring out its timeout.

**In-call UI (A09).** `CallView` overlays the Activity's `stage`, so it covers the people list and
the chat alike; the duration clock rides the existing 1 s `tick`. State, duration, mute, route and
quality are separate lines, a quality hint appears only for `Reduced` and is announced once, and a
terminal banner lingers 2500 ms. Back closes the overlay and falls back to a **return-to-call bar**
on the people screen — a call is not tied to a conversation, so leaving the chat must not hide the
only controls for ending it; Back never hangs up by accident. `CallUi` is service-owned and retains
the terminal snapshot so "Declined" survives the controller's return to Idle. `CallController` gained
`addListener`/`removeListener`, so the Activity and the service's notification both observe the state
stream without displacing the single callback slot, and a throwing listener is isolated.

**Notifications (A10).** Two channels: `calls_ringing` (HIGH) and `calls_active` (LOW, silent);
only the ringing channel may alert. Accept/Decline are explicit service intents carrying the call ID,
and **Accept opens `MainActivity`** rather than accepting in the background, because accepting means
granting a visible permission flow. `accept(expectedCallId)` re-checks the call ID *and* the live
preference, so a stale notification action cannot accept a replaced call or bypass a withdrawal.
"Allow incoming calls" lives in the people side menu, bound to the service's persisted setting, and
states that turning it off does not stop outgoing calls.

**Bugs found and fixed.** The incoming INVITE was being dropped entirely (`onFrame` returned early
on a null session and nothing called `onInvite`). A policy decline sent nothing, so the caller rang
out 30 s and reported "No answer" — now `DECLINE`, with `BUSY` for throttling and occupancy and
still silence for identity failure. `sendFrame` counted an outbound write as inbound evidence, so a
dead channel never tripped the liveness check. And `onInvite` never validated the call ID: a
non-UUID would create a session whose own frames the peer's parser rejects, so such a call could only
ever expire.

`tests/CallCheck.java` passes **229/229** (was 142). **Release is `outputs/LanMessenger-2.2.9.apk`**
(versionCode 36), 6,283,697 bytes, SHA-256 `2f8a12ad…b15a517d`, signed and verified (v2 + v3) and
installed over 2.2.8 (which confirms the same signing key). Release notes and
`SHA256SUMS-Android-2.2.9.txt` written. Verified on
device: no FATAL; WebRTC native stack OK (hwAEC/hwNS); both call notification channels created
(`calls_ringing` importance 4, `calls_active` importance 2); the **"Allow incoming calls" switch
toggles and persists across `force-stop` + restart**; the **Call action appears** in a verified
direct peer's menu; tapping it on an offline peer produced the correct `"Lap is offline."` and no
notification. See `state/handoff-A08-A10.md`.

**Still unverified, and it is the important part: a real two-party call has never been placed.**
Only one Android device exists (`192.168.1.4:5555`); there is no emulator (`emulator.exe` absent,
no AVDs, no nested virt) and the Windows peer has no call support. So the INVITE/RINGING exchange,
the overlay mid-call, notification actions in flight, network mute round-trip and the terminal
banner are covered by unit test and compilation, not by a live call. Speaker output is unverifiable
by adb, Bluetooth/wired route precedence is deliberately not claimed, and A07p proximity blanking,
A06 runtime microphone FGS promotion and A05 arbitration under a live call remain untested.

Also fixed while releasing: the About screen read its version from a hardcoded literal that had gone
stale (it showed 2.2.6 in a 2.2.8 build). It now reads `versionName` from the installed package, so
the manifest is the only place a version is declared and the two cannot drift.

Note: `outputs/LanMessenger-2.2.6.apk` was destroyed by an earlier build; `build-voice.ps1` now
refuses to overwrite a release artifact, which is why the release numbers stepped 2.2.6 → 2.2.7 →
2.2.8 → 2.2.9 rather than being rebuilt in place. `2.2.6-voice-dev.apk` survives as the voice build
of that version. **2.2.6 was not rebuilt**; 2.2.9 supersedes it.

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

**Third replay-bug fix (race condition in the second fix, 2026-09-29, same day)**: the user
tested 2.2.4 and reported a new symptom — pressing Play now starts audio and then "stops
immediately", rather than the earlier silent no-op. Root cause: `resume()`'s `stop()`+`flush()`+
`play()` sequence ran *outside* the `synchronized(lock)` block, after `notifyAll()` had already
woken the write thread. The write thread could race ahead — write a fresh chunk into the track —
before (or while) `flush()` ran on another thread, wiping out (or racing against) that write; the
audible result is exactly "plays a fragment, then goes silent." Fixed by moving the entire
`stop()`/`flush()`/`play()` reset sequence *inside* the synchronized block, strictly before
`notifyAll()`, so the write thread can only ever wake up after the track has already been reset
and is already in the PLAYING state — eliminating the race rather than just narrowing it.
Verified through the real Android toolchain, 0 errors. Packaged as **Android 2.2.5**
(versionCode 32), same signer as every prior release. Still awaiting the user's re-test.

**Fifth pass — stop reusing the drained AudioTrack at all (2026-09-29, same day)**: the user
tested 2.2.5 and reported the same failure ("it play once... why i need to leave conversation to
play rec again"). Three straight attempts at reusing the same `AudioTrack` past natural
completion (position reset alone, then `stop()`+`flush()` outside the lock, then the same reset
correctly ordered inside the lock) all failed on this device — strong evidence this specific
`AudioTrack` cannot be reliably revived after it drains, at least on this hardware/OS combo,
regardless of how carefully the reset is sequenced. The one thing that *always* worked was
leaving the conversation and coming back, because that path throws away the old player entirely
and builds a brand-new `VoicePlayer`/`AudioTrack` from scratch. So instead of continuing to fight
the reuse case, `VoicePlayer` now exposes `isFinished()` (`!playing && playPosition >= dataEnd()`)
and `VoicePlayback.togglePlayback` checks it: a finished player is never resumed in place — it
falls through to the same "different key" branch that already builds a fresh player, i.e. tapping
Play again on a finished voice message now does in-app exactly what leaving and re-entering used
to do, with no navigation needed. `resume()` itself is back to a plain pause/resume toggle (no
`dataEnd()`/track reset left in it — that responsibility moved to the caller). Verified through
the real Android toolchain, 0 errors. Packaged as **Android 2.2.6** (versionCode 33), same signer
as every prior release. Still awaiting the user's re-test, but this is the first fix in this
chain built on a mechanism (fresh player construction) already independently confirmed to work
on the user's own device, rather than on a new theory about the failure.

## Voice calls A09 — call screen rebuilt to the Messenger reference layout (2026-10-02, 2.2.30)

The user supplied a reference call screen and asked for the app's call screen to look like it. It
did not: every label was stacked and centred in one dark slab with wide full-width text buttons,
there was no picture of who was being called, the toggles were crowded to one side, and ending the
call was a rectangle in a stack rather than a single unmistakable target. `CallView.build` is now
the reference layout: a dark teal header (`BAR` = `#0E524C`) carrying the peer's name as an
uppercase headline with the timer or status line under it and a 🔽 minimise control at its right;
the peer's synced picture (`avatarView`, falling back to the same coloured initial disc the people
list uses) in the window between; one large red `hangupCircle` disc — a 📞 rotated 135° inside a red
circle, because there is no hang-up emoji and 📴 reads as *phone switched off* — floating just above
a teal `controlBar` holding 💬 / 🔈|🔊 / 🎤|🔇 in three weight-1 slots. Ring screens use the same
frame with worded **Accept**/**Decline** and **Cancel**, and the minimised bar is the same teal.
The 💬 control opens that peer's conversation and leaves the call running; the Activity's
`Return / End` call bar keeps the call visible from there.

**Four defects the on-device acceptance run found, all of which had been live before this work:**

1. **The floating hang-up disc covered Accept and Decline while ringing.** It was floated at the
   same offset over the action zone in every state, so it sat on top of both buttons — tapping
   **Accept** hung the ringing call up instead of answering it (`LOCAL_HANGUP (was
   IncomingRinging)` in `LANCALL`). It is now added only for a non-ringing state; there is also
   nothing for it to mean while ringing, since Decline and Cancel already stop a ringing call.
2. **A minimised call could not be reopened.** Tapping the bar set `collapsed = false` and
   re-rendered, but `render()` compares call ID, state, mute and route against what the overlay was
   built for, and the bar matched all of them, so it decided there was nothing to rebuild and the
   bar stayed exactly where it was — a running call with no way back into it. It now hides and
   re-renders (`hide` then `render`).
3. **Back abandoned a live call.** `onBackPressed` called `CallView.hide()` while the call was still
   running, leaving the only full-screen indication that a call existed gone; the chat screen that
   remained looks exactly like the call had ended. Back now toggles — expand a minimised call,
   minimise a full one — via `CallView.backWhileLive`. A call deliberately off screen (opened with
   💬) is left alone so Back still navigates.
4. **The call clock started when the call was created, not when it was answered.**
   `CallSession.elapsedMs()` added `now - createdAtMs` to the connected duration, so a call picked
   up four seconds after ringing already showed `0:04`. It now measures from `connectedAtMs`, and
   the stored duration is only a floor for a snapshot taken before the connect time was set.

**Wording.** `SIGNALING_LOST` said **"Connection lost"**, which the user asked what it meant: it is
the signalling TCP pipe dying without either side hanging up — the far phone's app killed or
crashed, or it dropped off Wi-Fi — but the wording reads as *your* connection failing and gives no
way to act. It is now **"Disconnected"**, and `CallUi.endHint` gives **every** end reason a plain
sentence beneath the label (e.g. *"Lost the link to the other phone. Its app closed, or it went off
Wi-Fi."*), because a label alone could not distinguish the cases. Also fixed: the `Return / End`
bar is rebuilt when its words change and not only when it appears, so it no longer reads "Incoming
call" beside a running timer.

**Verification.** `tests/CallCheck.java` **PASS=352 FAIL=0** (50 new checks: `N143`–`N153` for the
answer-time clock and the end-reason sentences). Layout is presentation-only and cannot be asserted
from pure-Java tests, so acceptance is a two-device driver
(`outputs/.build/accept-layout.ps1`) that places a real call from SM-A075F to SM-ultraaysar and
asserts the hierarchy: all four regions present, bar order and spacing, the picture above the disc
and the disc above the bar, no wide `Hang up`/`Minimize` button surviving, toggles flipping glyph
and accessibility label, minimise/restore/Back (`M1`–`M8`), chat-without-ending, and the end reason
plus its sentence persisting. Packaged as **Android 2.2.30** (versionCode 57), same signer as every
prior release. Live audio quality, proximity routing and microphone foreground promotion are still
**not** assessed on device.

**Defects found after the redesign, one of them reported by the user afterwards.**

5. **The call screen passed every tap it did not use to the conversation behind it.** `attach()`
   adds the overlay to the stage at `-1,-1`, but a background colour does not make a view swallow
   touches and `FrameLayout.onTouchEvent` returns `false`, so anything that missed a child — the
   wall around the picture, the header padding, the gaps beside the disc — went back to `stage` and
   from there to the chat underneath. Tapping beside the avatar opened the conversation behind the
   call. The full-screen panel's holder is now clickable; `buildCollapsed` and `buildTerminal`
   deliberately are not, because the chat is meant to stay usable under those two.
6. **💬 did nothing.** It called `hide()` then `showChat()`, which ends in `render()`, which rebuilt
   the full-screen panel over the chat in the same tap. `CallView.dismissedCallId` now records the
   request, keyed by call ID so it lapses when that call ends or a new one starts.
7. **The `Return / End` bar froze at `0:00`.** Its rebuild key was `call.state`, an enum that stops
   changing at `Connected`, while the text beside it is a duration that changes every second — so it
   was built once and never refreshed. Now keyed on the words it actually renders.
8. **`LOCAL_DECLINE` told the decliner that the *other* phone declined.** It is produced only on the
   phone that pressed Decline, so the sentence was the wrong way round. **`SIGNALING_LOST`** named
   the other phone, but it is raised by heartbeat timeout, a local send failure and a local channel
   close alike — all of which happen when *this* phone drops — which sent users with dead Wi-Fi to
   check the far end. Both are side-correct or side-neutral now (`N152`, `N154`, `N155`).

`CallCheck` **PASS=354 FAIL=0**. The two-device acceptance run has **not** produced a valid result
since these fixes: the last attempt failed its online gate on both phones before any call was
placed. See [HANDOFF.md](../HANDOFF.md) for what is still open — chiefly the per-second TalkBack
announcement of the duration clock, which is a regression this work introduced.

## Second pass on the same work (2026-10-02, 2.2.31)

**"Group members" was offered on a direct conversation.** `chatMenu` added it unconditionally, so
opening the ⋮ menu on a one-to-one chat listed an item that could not work: `showMembers` looks for
a group whose id matches the open conversation, finds none, and falls through to a toast telling
the user the obvious. It is now guarded on the open conversation actually being a group. The menu
dispatch also used to end in `else showMembers()`, so **any** title not matched above it silently
opened the members dialog — adding one item meant forgetting to route it, and the wrong dialog
appeared instead of nothing. Every title is now routed explicitly. Verified on a device: a direct
chat offers Call / Verify device / Clear conversation, a group offers Group members / Clear
conversation / Leave group and no Call.

**TalkBack read the clock out loud once a second.** The status line carries the duration and was a
permanently-live accessibility region, so a screen-reader user heard "0:04", "0:05", "0:06" for the
whole call — the one thing that stops them hearing the person they are on a call with. The clock is
still *shown* every second; it is the announcement that is now gated to state transitions
(`CallUi.isNewStateAnnouncement`).

**The return-to-call bar rebuilt three views every second.** Keying on the rendered words fixed the
frozen `0:00`, but the words include the duration, so every tick of the clock did a `removeAllViews`
plus three new Buttons and four LayoutParams on the UI thread for the length of a call. The summary
is now built once and retargeted in place; the bar still rebuilds its buttons when the peer changes.

**Smaller items.** The hang-up disc's accessibility label was overwritten with a bare "Hang up",
so the specific "End the call with *name*" never reached a screen reader. `isCollapsed()` was dead
code and is gone. `N147` asserted an exact `"0:03"` against a value recomputed from the wall clock,
so it passed only inside a one-second window — it now accepts `0:03` or `0:04`, and a new `N146`
places the reported bug directly: a call that rang 20 seconds and was answered must read 0:00, not
0:20.

**Coverage.** The three decisions above — when to announce, when to keep the call screen dismissed,
and what the return-to-call bar shows — were moved out of `CallView` and `MainActivity` into
`CallUi`, because the pure-Java harness cannot load either class (`NoClassDefFoundError:
android/content/Context`), which is why they had no coverage at all. `N156`–`N166` now pin them.

`CallCheck` **PASS=370 FAIL=0**. **2.2.31** (versionCode 58) installed on SM-ultraaysar only.

**Not verified on device: the tap-through fix.** The other phone (192.168.1.44) is off the network —
100% packet loss — so no call could be placed from ultra and the call overlay never appeared.
`startCallTo` refuses an offline peer before creating a call, so this is not something to work
around. The fix is one line (`holder.setClickable(true)`) and two independent analyses agree on both
the mechanism and the fix, but it has not been exercised on hardware.

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

## Voice Calls — Phase A0 foundation (2026-10-01, plan-v006)

Phase A0 shared architecture and contracts (B00/B01) are code-complete. These are pure-Java
design contracts — no WebRTC dependency, no networking integration with PeerEngine, no UI, no
production packaging. All classes compile with the existing javac/d8 pipeline; all 75 unit
tests pass.

New source files in `src/net/lanmsg/chat/`:

- `CallProtocol.java` — 14 signaling message types (INVITE through PONG), 6 call states,
  quality-indicator constants, invitation-limiter (5/peer/60s rolling window), glare
  resolution, and all timing/defaults from plan-v006.
- `CallSignaling.java` — 4-byte big-endian length + UTF-8 JSON frame serialization and
  parsing, with convenience builders for every message type.
- `CallSession.java` — Immutable call snapshots (caller/callee direction, state, mute,
  audio route, quality, duration) for thread-safe UI consumption.
- `CallController.java` — Main orchestrator: state-machine enforcement, signaling dispatch,
  media lifecycle (via injectable `ICallMedia`), invitation throttling, ring/media-setup
  timeouts, Connected heartbeat, quality polling, Offline cleanup, and engine-shutdown.
- `ICallMedia.java` — Media-adapter interface + Factory (Opus, DTLS-SRTP, SDP/ICE, mute,
  capture/playback, WebRTC cumulative statistics). A fake implementation (`FakeCallMedia`)
  ships alongside for testing.
- `CallSettings.java` — Incoming-call preference (default: enabled), persisted atomically
  per device in a separate `call-settings.txt` with a generation counter to reject stale
  writes. Corrupt settings disable incoming calls for that session.
- `CallQualityMonitor.java` — Pure-Java evaluator consuming WebRTC-style cumulative stats;
  first/last-sample deltas over a 5-second rolling window; 3 consecutive degraded evaluations
  to enter Reduced; Normal immediately on recovery.
- `WebRtcCallMedia.java` — Production media adapter using org.webrtc reflection-based API.
  Uses webrtc-sdk:android:150.7871.01 AAR (PeerConnectionFactory, AudioSource/AudioTrack,
  SDP offer/answer, ICE, stats). Requires native libjingle_peerconnection_so.so at runtime.
  Uses a dedicated `HandlerThread` and a single-thread callback executor, with a process-global
  refcounted `PeerConnectionFactory` and `JavaAudioDeviceModule`. LAN-only ICE (no STUN/TURN).
  **Installed in the service** (`MessengerService` probes the native stack, then installs this
  factory, falling back to `FakeCallMedia` only if the probe fails). `probe()` reported
  `WebRTC native stack OK in 20 ms; hwAEC=true hwNS=true` on the SM-S908E.
- `CallUi.java` — Service-owned call view-model (no Android dependency). Plan-v006 wording for
  state/detail/end labels (`Declined` for both manual and policy decline, never disclosing the
  preference), duration formatting, the Reduced quality hint, accept/decline/cancel/hangup/mute, and
  an observer list so an Activity can bind without displacing the service's notification. Retains
  the terminal snapshot so the end reason outlives the controller's return to Idle.
- `CallChannel.java` — Owns the call socket, one reader thread and a serialized writer for both
  directions. Implements `Transport` and `Closeable`; the opening INVITE is routed to `onInvite`
  while later frames go to the controller.
- `CallNotifier.java` — `calls_ringing` (IMPORTANCE_HIGH, the only alerting channel) and
  `calls_active` (IMPORTANCE_LOW, silent); ringtone playback; Accept/Decline service intents
  carrying the call ID; Accept opens `MainActivity` so the permission flow is visible.
- `CallView.java` — In-Activity call overlay on `stage`. Rebuilt per state change; duration on the
  1 s tick; terminal banner expiring after 2500 ms; one-shot quality announcement.
- `CallRoute.java` — Carries a route decision to `AudioManager`: `MODE_IN_COMMUNICATION`, and the
  built-in speaker or earpiece on API 31+. Only switches when the user chose the speaker, so a
  headset the platform itself selected is never overridden.
- `AudioOwnership.java` — Arbitration between voice calls and voice messages. Service-owned,
  pure Java. Recording is refused while a call rings/connects/captures; playback is refused while
  a call owns the device; a call may start over playback but recording interrupts it first.
  Guarded `releaseIf(owner)` is the only release used by the voice side.
- `CallRoutePolicy.java` — Route *selection*, separated from route application. Earpiece is the
  default and speaker is never an automatic first choice; a wired headset outranks the earpiece;
  a lost route is held for the 3 s `ROUTE_RECOVERY_MS` window before falling back; a user-selected
  route is never silently overridden while missing; proximity applies only on earpiece; no route
  at all is a distinct failure rather than a silent fallback.

Test: `tests/CallCheck.java` — **229 unit tests** covering protocol transitions, signaling
serialize/parse round-trips (including oversized/malformed rejection), settings
persistence/reload/generation, quality degradation/recovery via deterministic sequences,
invitation-limiter bounds/window-expiry/prune, controller lifecycle/shutdown, incoming-call
dispatch, decline, disabled-policy rejection, mute, frame-parse edge cases, glare/duplicate
INVITE idempotency, settings corruption, audio-ownership state machine and its controller
lifecycle, and route selection including the recovery window; plus (A08–A10) invitation replies
never being silent (policy → DECLINE, throttled/occupied → BUSY, identity mismatch → silence),
malformed call ID refused, RINGING confirming the ring window, policy withdrawal of a ringing
call, stale-`accept(callId)` refusal, uniform terminal wording, listener fan-out surviving
rebind and isolating a throwing listener, audio route in the snapshot, and UI label
distinctness/duration formatting.
`CALLCHECK PASS=229 FAIL=0`.

Modified: `PeerEngine.java` (+CALLCONNECT protocol, +callHandler, +onRevoke,
+openCallConnection), `MessengerService.java` (+CallController +CallSettings +AudioOwnership,
lifecycle wiring), `VoiceUi.java` / `VoicePlayback.java` (+audio-ownership claims and releases,
including the awaited release on the recording stop thread), `AndroidManifest.xml`
(+MODIFY_AUDIO_SETTINGS, +extractNativeLibs=true, +microphone feature optional,
`foregroundServiceType="connectedDevice|microphone"`, +FOREGROUND_SERVICE_MICROPHONE).

Not yet done (all device-dependent): A04 real media install, A07p proximity sensor,
`AudioManager` route application, runtime microphone foreground-service behavior.

Build: `prepare-webrtc.ps1` downloads the WebRTC AAR; `build-voice.ps1` produces an
integrated APK with native .so libraries. See
`.ai-planner/sessions/20260930-091333-43c139/state/` for all handoffs and the A00
evaluation report.

## Third pass on the same work (2026-10-02, 2.2.34)

**A cold start could not answer an incoming call.** Tapping **Accept** did nothing at all — no
`ACCEPT` frame, no toast, no error, no visual change, and the call rang out. **Decline worked**, and
every screenshot looked correct, so nothing about the screen hinted at it.

Two references to one view-model were involved. `CallView.render` reads the service's instance via
`activity.host.calls()`, while `acceptCall` read the Activity's own `callUi` field and returned
silently when that was null. The field is bound only from `onResume` and `onServiceConnected`, but
the service does not create its `CallUi` until it builds the LAN stack — seconds after both of those
— so neither attempt found one, and **nothing ever asked again**. Decline survived because it
captures the instance it was built with. The same defect made the notification's Accept action inert
and made Back navigate away from a live call instead of minimising it, for as long as the user did
not leave and return.

Fixed three ways: `render()` retries the bind on its one-second pass; the Accept button acts on the
model that drew it (`CallUi.resolveForAccept`, `N179`-`N183`); and no path returns silently any more
— the notification action and `onBackPressed` fall back to the service's instance and, failing that,
say so instead of doing nothing. Verified on both phones from a cold start, with one tap and no
re-entry (`accept-coldstart.ps1`, `X0`-`X6`, all pass on 2.2.34).

**The peer's name could go stale for a whole call.** The call screen's rebuild test was a chain of
`||` clauses over call ID, state, mute and route, and the peer's *name* was not among them: a contact
renamed mid-call kept the old name, the old initial on the picture, and three accessibility labels
naming them wrongly until something unrelated forced a rebuild. Replaced by a single
`CallUi.overlayKey` string (`N171`-`N178`).

**TalkBack fix completed.** The earlier fix gated a new announcement on state transitions but left
the status line as a permanent live region being rewritten once a second, so the clock was still
read aloud every second and the new code merely added a second announcement path. The live region is
now removed from both the full screen and the collapsed bar, the announcement moved above the
collapsed/full split and the rebuild test (both return early, so transitions that rebuild — including
answering — were never spoken), and `CallUi.stateSpokenLabel` says "Connected" rather than the clock
value `stateLabel` would give (`N167`-`N170`).

`tests/CallCheck.java` **PASS=387 FAIL=0**. Build 2.2.34 (versionCode 61) installed on both phones,
same signer as every prior release.
## Fourth pass on the same work (2026-10-02, 2.2.36)

**The conversation control did nothing.** Tapping 💬 on the call screen opened the conversation and
then lost it: `showChat` ends in `frame`, which ends in `CallView.forgetOverlay`, and that cleared the
dismissal the button had just recorded, so the next `render` rebuilt the full call panel over the
conversation in the same tap. A comment above the button already described this exact failure and
claimed it was fixed; `forgetOverlay` undid it. `forgetOverlay` now drops only view references --
`collapsed` and `dismissedCallId` record what the user asked for, not anything about the discarded
tree -- and `CallView.returnToCall` lifts the dismissal from the Return control, which without it
would have had nowhere to go. Verified on both phones: the conversation opens, the call screen stays
down, and the call keeps running.

**A live call had no way back from the conversation.** `callBar` was built only into the people
screen, and its comment claimed it "stays across the top of every screen". With the conversation
control fixed, opening the chat led somewhere with no call controls at all -- indistinguishable from
the call having ended. `buildCallBar` is now shared by both screens.

**The minimised call is now a floating, draggable bar.** It was a full-width strip anchored to the
foot of the stage, which is exactly where the message box and its keyboard appear, so a call and
typing could not both be used. It is sized to itself, floats, and can be dragged anywhere: a
`GestureDetector` tells a tap (reopen) from a drag (move) with a 6 dp slop, the click listener is kept
for accessibility activation, and the hang-up button is a child so it takes its own touches first.
Every position is pulled back inside the stage (`CallUi.clampBarLeft`/`clampBarTop`, `N184`-`N192`),
which is skipped before the first layout pass when neither size is known. The position is kept outside
the view tree -- the bar is rebuilt on every state change, so a position held in the view would be
lost each time -- and persisted so it survives calls and restarts.

`tests/CallCheck.java` **PASS=396 FAIL=0**. Build 2.2.36 (versionCode 63) installed on SM-ultraaysar;
SM-A075F went `unauthorized` on adb before it could be installed, so the two-device run of this round
is outstanding.