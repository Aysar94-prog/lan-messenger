# Handoff — Phase A2: audio ownership, routing, policy (2026-10-01)

Android voice calls per plan-v006. A04–A07 (the automatable parts) are done; A07p proximity and
the real WebRTC media path still need a device.

## What changed

### New: `AudioOwnership.java` (A05)
One arbitration point between voice calls and voice messages, service-owned like the controller.
Pure Java, no Android dependency, so the rules are testable on the desktop.

Ownership states: `FREE`, `CALL_RINGING`, `CALL_CAPTURE`, `VOICE_RECORDING`, `VOICE_PLAYBACK`.

The rules that are not obvious from the enum:
- Recording is refused while a call rings, connects, or captures. Capture is the stronger claim.
- A call may start while voice playback is active, but voice *recording* interrupts playback first.
- A call in progress blocks playback outright. Playback is refused, not queued.
- `releaseIf(expected)` is the only release used on the voice side, so a late stop of a stopped
  player cannot release a claim a call has since taken.

### New: `CallRoutePolicy.java` (A07)
Route *selection*, separated from route *application*. The Android side only applies the decision.
Rules encoded here:
- Default is earpiece. Speaker is never an automatic first choice.
- A wired headset is preferred over earpiece when present; speaker is the last resort.
- A lost route is held for `ROUTE_RECOVERY_MS` (3 s) before falling back, so a headset that
  reconnects mid-call does not bounce the audio to the speaker and back.
- A user-selected route is never silently overridden while it is missing. The user picks again.
- Proximity is active only on the earpiece route.
- No route at all is a distinct failure (`noRouteAvailable`), not a silent fallback.

### `CallController.java` (A05 wiring)
- `setAudioOwner(...)`, plus claims at the two points that own the device: `onInvite` claims ring,
  `accept` claims capture.
- `startCall` refuses while a voice recording holds the device.
- Release on every exit: `endCall`, `onOffline`, `shutdown` (all three force-release, so a stale
  claim cannot outlive the call), and `onPeerRevoked` inherits the release via `endCall`.
- `cancelCurrentLocked` disposes media but does not release; the cancellation is always followed by
  `endCall` or a fresh session, both of which release.

### `VoiceUi.java` / `VoicePlayback.java` (A05 consumer side)
- `startVoiceRecording` claims `VOICE_RECORDING` up front, and releases it on every early-return
  path (draft creation failure, writer open failure, permission not yet granted, recorder start
  failure). The claim is held across the whole recording.
- `stopVoiceRecording` deliberately does **not** release on entry. The claim is released at the end
  of the stop thread and only on the success path. A failed release leaves the claim held rather
  than handing a device we could not release to a competing capture. The draft is invalidated on
  that path either way, so nothing user-visible is lost.
- `togglePlayback` claims `VOICE_PLAYBACK` and refuses with a toast if a call owns the device.
- `stopActivePlayer` releases via `releaseIf(VOICE_PLAYBACK)`.

### `AndroidManifest.xml` (A06)
- `foregroundServiceType="connectedDevice|microphone"` on `MessengerService`.
- `+FOREGROUND_SERVICE_MICROPHONE`.

## Test evidence

`CALLCHECK PASS=142 FAIL=0` (up from 94). New suites:
- `testAudioOwnership` — 24 assertions on the ownership state machine.
- `testControllerAudioOwnershipLifecycle` — claims and releases through the real controller
  (invite → ring claim, decline → release, shutdown → release, recording blocks outgoing call).
- `testRoutePolicy` — 19 assertions on default route, headset preference, the 3 s recovery window,
  user-route protection, proximity, and the no-route failure.

Full compile: 37 production + 2 test files, 0 errors.

## Not done (needs a device)

- **A07p proximity.** `CallRoutePolicy.proximityActive()` decides *when* proximity applies, but
  nothing turns the screen off and the `SensorManager` listener is not written.
- **A04 real media.** `MessengerService` still installs `FakeCallMedia.Factory`. The
  `WebRtcCallMedia` adapter is written but only reflection-wired, so its org.webrtc calls are
  unverified at runtime.
- **A07 apply.** Nothing calls `CallRoutePolicy.reconcile`; `AudioManager` routing is unwired.
- **Foreground-service behavior.** The manifest declares the microphone type; nothing requests
  `startForeground` with that type at the right moment, and the API 34 runtime check is untested.

## Resume point

Next is either (a) applying `CallRoutePolicy` to `AudioManager` plus the proximity sensor, or
(b) swapping in `WebRtcCallMedia` and running `android\prepare-webrtc.ps1` + `android\build-voice.ps1`.
Both are device-dependent for verification. Note `build-voice.ps1` has known typos
(`[I0.Path]`, `preapare-webrtc.ps1`, `$Arm640nly`) and its default version (3.0.0/34) does not
match the manifest (2.2.6/33) — fix before any packaging.
