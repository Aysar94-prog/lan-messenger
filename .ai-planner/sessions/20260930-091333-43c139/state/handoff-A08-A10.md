# Handoff — A08/A09/A10 (call UI, call notifications, MainActivity binding)

**Task:** VC-ANDROID · **Plan:** plan-v006.md rows A08–A10
**Date:** 2026-10-01
**Status:** Implemented, compiled, unit-tested (229/229), built, installed on device. **Two-device call test still impossible** — see Blocked.

---

## What was built

The call subsystem existed but had no entry point. Nothing could place a call, nothing could
answer one, and a rejected invitation told the caller nothing at all. This phase makes a call
placeable and answerable and closes four holes found while wiring it.

### A08 — Entry point and admission
- `MainActivity.chatMenu` offers **Call** for a direct contact only. Group calls are out of scope, so
  a group row never shows the action.
- `startCallTo` gates in the open, in the order a user can act on: not online → peer unverified →
  peer offline → microphone permission. Every refusal is a specific message rather than a generic
  failure. The permission is requested *before* the invitation and the pending peer ID is
  remembered across the grant, so the call is placed exactly once on grant.
- `host.startCall(peerId)` runs on a background thread: it opens an authenticated TLS channel and
  writes the first signaling frame, which must never happen on the UI thread.
- `CallController.TransportFactory` — the controller mints the call ID and opens the matching socket
  *inside its own lock*, so the INVITE can never precede the connection that carries it. The
  service passes a factory rather than an open transport for exactly this reason.
- `setAllowIncoming(false)` during `IncomingRinging` **declines the ringing call immediately** and
  withdraws its notification, instead of letting it ring out its timeout.

### A09 — In-call UI
- `CallView` is an overlay on the Activity's `stage`, so it covers the people list and the chat
  alike. It is rebuilt per state change and its duration clock advances on the existing 1 s `tick`.
- Distinct lines: state word, duration, detail (mute), route, quality. A quality hint appears only
  for `Reduced`, and is announced once rather than every tick.
- A terminal banner survives 2500 ms so a decline is legible, then the overlay withdraws.
- `MainActivity.onBackPressed` closes the overlay and falls back to the return-to-call bar. Back
  never hangs up by accident.
- A **return-to-call bar** appears on the people screen whenever a call is live, with Return and
  End. A call is not tied to a conversation, so leaving the chat must not hide the only controls
  for ending it.
- `CallUi` is the **service-owned** view-model. It retains the terminal snapshot so "Declined"
  survives the controller's immediate return to Idle. `CallController` gained
  `addListener`/`removeListener`, so the Activity's UI and the service's notification both observe
  the state stream without displacing the single `setCallback` slot. A listener that throws is
  isolated.

### A10 — Notifications and preference
- Two channels: `calls_ringing` (IMPORTANCE_HIGH) and `calls_active` (IMPORTANCE_LOW, silent).
  Only the ringing channel may alert.
- Ringing notification carries Accept / Decline as explicit `MessengerService` service intents
  carrying the call ID. **Accept opens `MainActivity`** rather than accepting in the background —
  accepting means granting a visible permission flow, so it is never done behind the user's back.
- `CallController.accept(expectedCallId)` re-checks both the call ID *and* the live preference, so a
  stale notification action cannot accept a replaced call or bypass a policy withdrawal.
- "Allow incoming calls" switch in the people side menu, bound to the service's persisted setting.
  It shows `callSettingsError()` and states that turning it off does not stop outgoing calls.

---

## Bugs found and fixed while wiring

1. **Incoming INVITE was dropped entirely.** `onFrame` returned early when `session == null` and
   nothing called `onInvite` — a call could never arrive.
2. **A rejected invitation told the caller nothing.** Policy decline and busy now send `DECLINE`
   and `BUSY` respectively; a caller no longer rings out 30 s and reports "No answer" for what was
   a policy decision. Identity/verification failure still sends nothing — answering would confirm
   the sender's guess.
3. **`sendFrame` updated `lastInboundSignalMs` on outbound writes**, so a dead channel never tripped
   the liveness check. Inbound evidence is now recorded first and an outbound write is not liveness.
4. **`onInvite` never validated the call ID.** A non-UUID call ID produced a session whose own
   frames the peer's parser rejects — such a call could only ever expire. Now refused before a
   session exists (test `N35`).

---

## Verification

**Automated — `tests/CallCheck.java`: PASS=229 FAIL=0** (was 142/142; +87 this phase).

New coverage: invitation replies are never silent (policy → DECLINE, throttled → BUSY, occupied →
BUSY, identity mismatch → silent); malformed call ID refused; RINGING confirms the ring window;
disabling mid-ring declines and goes idle; stale `accept(callId)` refused and live accept succeeds;
terminal wording uniform (`DECLINED` and `LOCAL_DECLINE` both "Declined", nothing discloses the
preference); listener fan-out survives rebinding and isolates a throwing listener; audio route
carried in the snapshot; UI label distinctness and duration formatting.

**Build:** `.\android\build.ps1 -Arm64Only -VersionName 2.2.8 -VersionCode 35` → 6 MB APK, signed
and verified (v2 + v3).

**Device (SM-S908E, Android 16 / SDK 36):**
- Installed; `versionCode=35 versionName=2.2.8`; no FATAL in logcat; WebRTC native stack OK in
  20 ms (hwAEC=true hwNS=true).
- Both notification channels created on device: `calls_ringing` mImportance=4, `calls_active`
  mImportance=2.
- **"Allow incoming calls" switch renders, toggles, and persists across `force-stop` + restart**
  (checked=true → toggled false → false after restart).
- **Call menu action appears** in a verified direct peer's overflow menu (alongside Verify device /
  Group members / Clear conversation).
- Tapping Call on an offline verified peer produced the correct refusal: `"Lap is offline."`
  (`MainActivity.startCallTo:378`), and no call notification was created.
- No FATAL EXCEPTION anywhere in the run.

---

## Blocked — still not verifiable

- **Only one Android device exists.** `adb devices -l` shows just `192.168.1.4:5555`. No emulator
  possible (`emulator.exe` absent, no AVDs, no nested virt). The PC is not a usable peer — the 20
  Windows `.cs` files contain no `CALLCONNECT`, `CALLCAPS`, or WebRTC reference.
  **Therefore: a real two-device call has never been placed.** Every path past `startCallTo`'s
  admission gates — the INVITE/RINGING exchange, the overlay mid-call, the Accept/Decline
  notifications in flight, mute round-trip over the network, the terminal banner — is verified by
  unit test and by compilation, not by a live call.
- **Speaker output unverifiable by adb** — no playback capture path exists.
- **Bluetooth / wired-headset route precedence** is not claimed; `CallRoute` only switches when the
  user explicitly chose the speaker, so a platform-selected headset is never overridden.
- **A07p** proximity-sensor screen blanking still unwired. `A06` runtime microphone
  foreground-service promotion and `A05` arbitration under a live call still untested.

## Known environment damage

`outputs/LanMessenger-2.2.6.apk` was destroyed earlier (voice build preserved as
`LanMessenger-2.2.6-voice-dev.apk`); `build-voice.ps1` now refuses to overwrite a release artifact.
2.2.6 was **not** rebuilt — 2.2.9 supersedes it.

**Released as 2.2.9, not 2.2.8.** While preparing the release the About dialog was found to display a
hardcoded `APP_VERSION="2.2.6"` inside a 2.2.8 build — a stale literal that had been drifting
silently. `MainActivity.appVersion()` now reads `versionName` from the installed package, so the
manifest is the only place a version is declared and the two cannot diverge. Verified on device: the
About dialog reads 2.2.9. Because the build refuses to overwrite an existing release artifact, the
fix required a new version number.

`outputs/LanMessenger-2.2.9.apk` — 6,283,697 bytes, versionCode 36, SHA-256
`2f8a12adf4a47c3934c9251119b10a55eece0373dee79564c16f08f8b15a517d`, signed v2+v3. Installed over
2.2.8, which itself confirms the signing key is unchanged (an upgrade with a different key fails).
`Release-Notes-Android-2.2.9.md` and `SHA256SUMS-Android-2.2.9.txt` written to `outputs/`.

## All work uncommitted in the working tree. Local commits only; do not push.