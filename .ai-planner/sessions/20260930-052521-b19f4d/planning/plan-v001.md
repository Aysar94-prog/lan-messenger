# plan-v001 | version=1 | Extensible Voice and Video Calling

## Scope and architecture baseline

Build private, one-to-one, real-time voice calling between verified Android and Windows peers on the same LAN. Group calls, PSTN/SIP integration, recording, relaying through the internet, and video UI are outside the first release.

The voice implementation must leave a direct upgrade path to one-to-one video calls:

- Use a WebRTC-compatible media layer with encrypted DTLS-SRTP media, Opus audio, SDP offer/answer negotiation, ICE candidates, echo cancellation, noise suppression, and automatic gain control.
- Use the existing mutually authenticated TLS connection and verified peer identity for call signaling.
- Introduce a platform-neutral `CallManager` state machine and a replaceable `MediaSession` interface. UI code must not manipulate sockets, SDP, audio devices, or media tracks directly.
- Model calls as media-capable sessions from the beginning: `audio`, `video offered`, `local video enabled`, and `remote video available`. Version 1 negotiates audio only.
- Do not reuse the voice-message WAV/attachment pipeline for live media. Reuse only applicable permission, audio-device, lifecycle, and UI conventions.
- Support exactly one active or ringing call per device. A second invitation receives `BUSY`.
- Calls require both peers to be Online, directly reachable, verified, and call-capable. They are never queued for later delivery.
- Version 1 should work without internet, STUN, TURN, or cloud signaling. Exchange LAN host candidates only. Keep ICE-server configuration injectable for a later non-LAN product decision.
- Do not store media. Persist only an optional bounded call-history record after the privacy policy is explicitly approved.
- Going Offline, deleting/revoking the contact, losing trust, app/service shutdown, or network loss must terminate the call and release media resources.
- Existing messaging, transfers, voice messages, group protocol, and old clients must continue to work unchanged.

### Shared signaling contract

Define the contract before either platform implements UI:

| Signal | Purpose |
|---|---|
| `CALLCAPS` | Query live support, protocol version, media kinds, codecs, and optional features |
| `CALLINVITE` | Start a call with call ID, caller/callee IDs, media kind, sequence, expiry, and SDP offer |
| `CALLRINGING` | Confirm that the invitation is valid and being presented |
| `CALLACCEPT` | Accept with SDP answer |
| `CALLICE` | Deliver a trickled ICE candidate or end-of-candidates marker |
| `CALLDECLINE` | Decline with a normalized reason |
| `CALLCANCEL` | Caller cancels before connection |
| `CALLBUSY` | Receiver already has an active/ringing call |
| `CALLHANGUP` | Either party terminates an accepted call |
| `CALLACK` | Idempotent acknowledgment where required |

Protocol rules:

- Every signal carries `callId`, sender identity, target identity, monotonic per-call sequence, and protocol version.
- Payloads use bounded base64url-encoded UTF-8 JSON or another documented encoding that cannot inject tab/newline frame delimiters.
- Dedicated call-frame limits must safely accommodate SDP while remaining bounded; do not silently raise the existing general 16 KiB message-frame limit.
- Authenticate every signal against the TLS peer certificate and the locally verified contact record.
- Reject group IDs, self-calls, unknown peers, expired invitations, malformed SDP/candidates, oversized payloads, unsupported versions, replayed sequences, and mismatched call IDs.
- Duplicate signals must be idempotent. A repeated invite must not create another ringing UI or microphone session.
- Recommended timeouts: capability query 3 seconds, unanswered invite 30 seconds, media connection 15 seconds, disconnect grace 8 seconds. Final values are contract constants shared by both implementations.
- Decline reasons should include `declined`, `busy`, `timeout`, `cancelled`, `offline`, `unsupported`, `permission_denied`, `media_error`, `network_lost`, and `trust_revoked`.
- Keep existing `CAPS>=2` group behavior untouched; call capability negotiation should be independent.
- An old client must ignore unsupported call frames safely and remain fully usable for messaging.

## Android

### Android Phase A0 — Architecture and WebRTC feasibility gate

**Status:** Planned  
**Dependencies:** None  
**Notes:** This is a design/prototype gate, not product implementation.

Tasks:

- Create an ADR defining the shared state machine, signaling schema, limits, timeout ownership, glare handling, privacy behavior, and audio-only/video-ready boundary.
- Evaluate an Android WebRTC library compatible with minSdk 26 and targetSdk 34.
- Prove an offline LAN audio session between a minimal Android harness and a standards-compatible desktop WebRTC peer.
- Verify Opus negotiation, host ICE candidates, DTLS fingerprint validation, echo cancellation, speaker/earpiece routing, wired headset behavior, and clean teardown.
- Record dependency version, native ABI coverage, APK-size impact, license, update policy, and reproducible build requirements.
- Define `CallMediaSession` with methods/events for offer, answer, ICE, connect, mute, audio route, future camera track, future renderer attachment, failure, and disposal.
- Define call states: `Idle`, `CapabilityChecking`, `OutgoingInviting`, `IncomingRinging`, `Connecting`, `Connected`, `Ending`, and `Ended`.
- Define simultaneous-call glare resolution using deterministic peer-ID ordering so both platforms reach one outcome.

Acceptance criteria:

- The selected library handles audio and exposes video-track APIs without replacing the signaling protocol.
- The prototype works with no internet services.
- No raw media crosses the existing message/attachment channel.
- Failure and disposal release microphone, audio focus, peer connection, ICE workers, and native threads.
- The ADR and wire fixtures are approved before application integration.

Testing tasks:

- Unit-test every legal and illegal state transition.
- Validate representative and maximum-size encoded call frames.
- Test malformed SDP, malformed ICE, duplicate sequences, stale calls, and glare.
- Capture a dependency/ABI build report for supported Android architectures.

### Android Phase A1 — Call signaling in `PeerEngine`

**Status:** Planned  
**Dependencies:** A0 and frozen shared contract  
**Notes:** Preserve the existing short-lived authenticated TLS request pattern where practical.

Tasks:

- Add call-frame parsing and strict bounds without changing ordinary message-frame behavior.
- Add a call signaling dispatcher separate from message persistence and attachment scheduling.
- Add live `CALLCAPS` querying; never cache capability as permanent truth.
- Route accepted signals to a service-owned `CallManager`.
- Send signals asynchronously through the existing trusted peer address/certificate path.
- Track active call ID, peer ID, direction, state, last sequence, deadlines, and terminal reason in memory.
- Implement idempotency, timeout scheduling, busy handling, cancellation, hangup, and network-loss detection.
- Terminate calls from `goOffline()`, trust revocation, contact deletion, service destruction, or peer identity mismatch.
- Keep call signaling out of message history, unread counts, transfer queues, daily upload accounting, and group synchronization.

Acceptance criteria:

- Only verified direct peers can create call state.
- Unsupported/old peers yield a clear “Calling is not supported on this device” result.
- Repeated or reordered frames do not create duplicate calls or regress terminal state.
- Messaging and file transfer continue while a call is active.
- Offline mode opens no call/media socket and immediately ends an existing call.

Testing tasks:

- Extend Java harnesses for direct call signaling between two real `PeerEngine` instances.
- Test accept, decline, busy, cancel, timeout, duplicate invite, replay, glare, malformed payload, trust revocation, and Offline transition.
- Run all existing engine, interoperability, transfer, group, offline, and voice-message regression suites.

### Android Phase A2 — Service-owned media and audio lifecycle

**Status:** Planned  
**Dependencies:** A1

Tasks:

- Implement `AndroidCallMediaSession` behind `CallMediaSession`.
- Configure WebRTC audio with Opus and built-in acoustic echo cancellation/noise suppression where available.
- Acquire audio focus only while ringing/connecting/connected as appropriate; restore previous audio mode on every exit.
- Default to the earpiece for handset-sized devices and provide explicit speaker toggle.
- Handle wired headsets and Bluetooth routing. Add runtime Bluetooth permissions only if required for supported Android versions.
- Add mute without stopping the connection.
- Keep the media session owned by `MessengerService`, not `MainActivity`, so rotation and ordinary activity recreation do not end a call.
- Extend the foreground service declaration for microphone use and add `FOREGROUND_SERVICE_MICROPHONE` where required.
- Account for Android 14 background foreground-service restrictions: accepting a call must occur through a user-visible activity/notification path that legally starts microphone capture.
- Stop the existing voice-message recorder/player before starting a call. Disable voice-message recording during ringing or an active call.
- Make teardown idempotent across hangup, engine Offline, activity exit, permission denial, service destruction, audio-device loss, and media failure.

Acceptance criteria:

- A connected call survives rotation, chat switching, and Activity recreation.
- Backgrounding after acceptance keeps the call alive through a correctly declared foreground service.
- Microphone, audio focus, routes, and native resources are released after every terminal path.
- Voice-message capture and live-call capture can never own the microphone simultaneously.
- The media abstraction can later add local camera and remote video tracks without changing `CallManager`.

Testing tasks:

- Unit-test the manager with a fake media session.
- Add instrumentation tests for permission denial, audio-focus loss, Activity recreation, service destruction, and repeated disposal.
- Test speaker, earpiece, wired headset, Bluetooth where available, mute, and screen lock on physical devices.
- Run a 30-minute call while watching memory, threads, CPU, battery, and audio underruns.

### Android Phase A3 — Outgoing and incoming call UI

**Status:** Planned  
**Dependencies:** A2

Tasks:

- Add a Call button only for verified, direct contacts; never show it for groups.
- Disable or explain the action when Offline, peer is offline, capability check fails, or microphone permission is unavailable.
- Build outgoing UI with peer identity, avatar, “Calling/Ringing/Connecting” status, Cancel, and timeout result.
- Build incoming full call screen with peer identity, Accept, Decline, and safe behavior when the device is locked.
- Add an incoming-call notification using an appropriate call notification category/style, private lock-screen content, and explicit Accept/Decline actions.
- Add connected-call controls: duration, mute, speaker/audio route, and hangup.
- Show a compact ongoing-call banner when navigating within the app.
- Keep future video layout slots behind the media abstraction; do not ship inactive camera buttons.
- Provide precise failure messages for unsupported peer, busy, permission denial, network loss, and media setup failure.
- Ensure accessibility names, large touch targets, screen-reader state announcements, and RTL-safe layout even though the current app is LTR.

Acceptance criteria:

- The user can place, receive, accept, decline, cancel, mute, route, and end a call without entering an invalid state.
- A stale notification cannot accept an expired or already-ended call.
- Rotation and navigation preserve the correct service-owned state.
- No notification reveals message or call details beyond the approved privacy level.
- The UI never reports “Connected” until media connectivity is established.

Testing tasks:

- Add UI/instrumentation scenarios for outgoing, incoming, busy, cancel-versus-accept race, timeout, notification actions, Back, Home, rotation, and locked-screen entry.
- Manually test notification permission denied and microphone permission denied permanently.
- Verify TalkBack labels and focus order.

### Android Phase A4 — Reliability, security, and video-readiness exit gate

**Status:** Planned  
**Dependencies:** A3 and Windows W3

Tasks:

- Exercise Android-to-Android and Android-to-Windows calls under packet loss, Wi-Fi roaming, temporary disconnect, peer crash, and simultaneous hangup.
- Confirm media encryption from WebRTC statistics/logging without logging SDP secrets, ICE addresses in normal logs, or audio data.
- Add bounded diagnostic logging with call IDs redacted or shortened.
- Confirm no call state is restored as active after process death; a lost call becomes terminal.
- Document the video extension: camera permission, `foregroundServiceType="camera|microphone|connectedDevice"`, local/remote renderers, camera switch, enable/disable video renegotiation, video codecs, bandwidth adaptation, and audio-only fallback.
- Update `android/STATUS.md` and `PROJECT_STATUS.md` only after implementation is explicitly authorized and completed, separating source completion, automated verification, and physical-device acceptance.

Acceptance criteria:

- Android passes the shared interoperability matrix.
- Existing Android features show no regression.
- Two physical Android devices and at least one Android/Windows pair pass a 30-minute call.
- The video-readiness review finds no signaling or state-machine redesign requirement.

Testing tasks:

- Execute the full regression suite.
- Run physical-device tests across screen lock, backgrounding, headset changes, Wi-Fi interruption, peer force-stop, and Offline transitions.
- Record actual device models, Android versions, routes, duration, observed latency, and failures.

## Windows

### Windows Phase W0 — Desktop media-engine feasibility gate

**Status:** Planned  
**Dependencies:** Shared architecture baseline  
**Notes:** This is the highest-risk phase. Do not begin product UI until it passes.

Tasks:

- Evaluate maintained WebRTC-compatible options for .NET 9 WinForms and supported Windows architectures.
- Require Opus, DTLS-SRTP, ICE host candidates, audio capture/render, device enumeration, echo cancellation, video tracks/renderers, deterministic disposal, and acceptable licensing.
- Prefer a native WebRTC binding with a narrow C# adapter. Do not build a custom RTP/Opus stack; that would create a second media protocol and weaken the video path.
- If no suitable binding supports .NET 9 directly, compare a pinned native bridge against a WebView2 WebRTC host. Document packaging, sandbox, update, crash-isolation, and UI integration costs before choosing.
- Build an audio-only harness interoperating with the Android A0 harness entirely on LAN.
- Measure publish size, native DLL requirements, x64/ARM64 availability, startup latency, CPU, memory, and device cleanup.
- Define the same `ICallMediaSession` semantics used by Android, including future local/remote video-track attachment.

Acceptance criteria:

- Windows and Android exchange audio using the selected standards-compatible media engine.
- Published artifacts contain every required native dependency.
- Disposal works after normal hangup, initialization failure, peer crash, and repeated calls.
- The chosen engine exposes a credible video implementation path.
- An ADR records the chosen option and why rejected options failed.

Testing tasks:

- Add a repeatable smoke harness for 20 consecutive connect/hangup cycles.
- Test missing microphone, exclusive-device conflict, default-device change, malformed SDP, and peer disappearance.
- Scan published dependencies and record licenses before approval.

### Windows Phase W1 — Call signaling and call state manager

**Status:** Planned  
**Dependencies:** W0 and frozen shared contract

Tasks:

- Add the same bounded call frames and validation rules to `PeerEngine.cs`.
- Implement live call capability queries independently of group `CAPS`.
- Add `CallManager` as the sole owner of call state, timers, sequencing, idempotency, glare, and terminal reasons.
- Marshal call events safely from engine threads to the WinForms UI thread.
- Terminate a call when `GoOffline()`, trust revocation, contact deletion, application Exit, or certificate mismatch occurs.
- Keep tray hiding distinct from Exit: hiding the window must not end an accepted call.
- Keep signals and call state out of message storage, transfers, unread counts, and voice-message draft reconciliation.
- Enforce one ringing/active call app-wide and return `BUSY` for another invite.

Acceptance criteria:

- C# and Java implementations accept and reject the same protocol fixtures.
- All terminal actions are idempotent.
- An old peer remains usable for messaging and returns a clear unsupported outcome for calls.
- Call activity cannot bypass verification or certificate checks.

Testing tasks:

- Extend `CsharpHarness` with state-machine and protocol-vector modes.
- Add live Java/C# engine scenarios for every signal and race.
- Run existing Windows UI, voice-message, transfer, group, offline, and interoperability tests unchanged.

### Windows Phase W2 — Audio devices and media-session implementation

**Status:** Planned  
**Dependencies:** W1

Tasks:

- Implement `WindowsCallMediaSession` around the approved W0 engine.
- Enumerate microphones and speakers with stable default-device behavior.
- Add Opus/WebRTC audio processing, mute, output volume where supported, and device-change handling.
- Decide and document whether a mid-call default-device change is adopted automatically or offered to the user.
- Coordinate with `VoiceRecorder` and `VoicePlayer`: stop voice-message playback/capture before a call and prevent them from starting during a call.
- Ensure capture/render callbacks never block the WinForms thread.
- Make teardown idempotent and safe during callbacks, application Exit, Offline transition, native-engine failure, and partial initialization.
- Expose future video renderer/capture hooks without adding video controls in version 1.

Acceptance criteria:

- Audio is intelligible and continuous in both directions without the buffer-ordering problems previously found in voice messages.
- Ten sequential calls do not leak native handles, threads, or devices.
- Tray hiding preserves the call; explicit Exit ends it cleanly.
- Missing or busy audio devices produce actionable errors without crashing.

Testing tasks:

- Add fake-media tests for deterministic failures and callback races.
- Add real-device lifecycle checks similar to the existing voice recorder/player harness.
- Monitor process handles, working set, CPU, and media statistics during a 30-minute call.
- Test microphone/speaker unplug, default-device switch, mute, sleep/resume policy, and repeated Offline/Online transitions.

### Windows Phase W3 — WinForms and tray call UI

**Status:** Planned  
**Dependencies:** W2

Tasks:

- Add a Call button for verified direct contacts only.
- Create outgoing ringing, incoming ringing, connecting, connected, and ended views.
- Add Accept, Decline, Cancel, Mute, audio-device selection, and Hang Up controls.
- Show duration only after media connects.
- Present incoming calls when the main window is hidden using a tray notification plus an in-app ringing dialog after activation.
- Define notification-click behavior and prevent stale notifications from acting on terminal calls.
- Show an ongoing-call strip when the user returns to normal chats.
- Prevent closing to tray from ending the call. Explicit Exit must confirm if a call is active, then hang up and dispose before shutdown.
- Keep the UI layout ready for future local preview and remote video surfaces, but do not expose nonfunctional camera controls.
- Add accessible names and keyboard navigation.

Acceptance criteria:

- All call actions remain available and accurate whether the main window is visible or hidden.
- The UI cannot display two incoming dialogs or multiple active-call panels.
- Exit, Offline, deletion/revocation, and peer failure update the UI exactly once.
- UI state always derives from `CallManager`; it never invents its own call state.

Testing tasks:

- Extend native Windows UI tests for outgoing, incoming, accept, decline, busy, timeout, mute, hangup, tray hide/restore, stale notification, Offline, and Exit confirmation.
- Test keyboard-only operation and screen-reader names.
- Manually verify multi-monitor and high-DPI layouts.

### Windows Phase W4 — Cross-platform integration and release gates

**Status:** Planned  
**Dependencies:** Android A3 and Windows W3

Tasks:

- Create shared signaling fixtures consumed by Java and C# tests.
- Build a matrix covering Android caller/Windows receiver, Windows caller/Android receiver, same-platform calls, old peer, busy peer, simultaneous calls, and both hangup directions.
- Test active messaging, attachment transfer, and voice-message receipt during a call.
- Test Wi-Fi interruption, Windows network-interface change, Android backgrounding, peer process crash, trust revocation, and Offline transitions.
- Confirm both sides agree on terminal reason and release resources within the specified deadline.
- Perform security review of certificate binding, SDP/ICE validation, replay resistance, media encryption, logging, notification privacy, and denial-of-service bounds.
- Write the phase-two video plan using the completed abstraction: capability negotiation, adding a video transceiver, camera permissions, preview/render surfaces, camera switching, renegotiation, bandwidth adaptation, audio-only fallback, and test matrix.
- Update both platform status files and `PROJECT_STATUS.md` after authorized implementation; record implementation, automation, packaging, and physical acceptance separately.
- Build releases only after explicit user authorization.

Acceptance criteria:

- Every required Android/Windows direction passes on physical hardware.
- Calls remain LAN-only and work with internet disconnected.
- Existing full regression suites pass on both platforms.
- No release is marked accepted until physical two-device testing is recorded.
- Adding video requires new media tracks and UI surfaces, not a replacement signaling protocol or call state manager.

Testing tasks:

- Run protocol-vector tests, Java and C# harnesses, Android instrumentation, Windows native UI tests, and the complete existing project suite.
- Perform at least one 30-minute Android/Windows call and 20 repeated short calls.
- Measure connection time, audio latency, packet loss, CPU, memory, battery impact, and resource recovery.
- Test release packages—not only source builds—before final acceptance.

### Recommended execution order

1. Freeze the shared contract and complete A0/W0 feasibility prototypes in parallel.
2. Approve both media-engine ADRs only after Android/Windows audio interoperability works.
3. Implement signaling and state managers: A1 and W1.
4. Implement media lifecycle: A2 and W2.
5. Implement platform UI: A3 and W3.
6. Complete A4/W4 interoperability, security, regression, and physical-device gates.
7. Package platform releases only after explicit authorization.
8. Start video implementation as a separate planned feature after voice-call acceptance.

### Global definition of done

- One-to-one calls work in both directions between verified Android and Windows peers.
- Accept, decline, cancel, busy, timeout, mute, route/device selection, hangup, Offline, trust revocation, and network failure behave consistently.
- DTLS-SRTP protects media; authenticated TLS protects signaling.
- Calls remain usable while the UI changes screen or hides, within each platform’s lifecycle rules.
- No media is stored and no call is queued.
- Old clients and every existing feature remain compatible.
- Automated verification and physical-device acceptance are recorded separately.
- The architecture demonstrably supports later video tracks, camera controls, and video renderers without redesigning signaling or call ownership.
