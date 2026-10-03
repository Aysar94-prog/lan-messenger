# A07 authenticated signaling boundary checkpoint — 2026-10-03

Android implementation authorized; original plan-v007/hash unchanged. This is a
small-task A07 checkpoint, NOT completion of video controller integration.
Audio listening remains accepted complete; do not repeat it. No signed APK yet.

| Small task / platform | Status | Dependencies / acceptance |
|---|---|---|
| Call capability transaction / Android | Implemented | A02b: separate CALLCAPS, verified TLS HELLO/READY, fresh query each time, total monotonic deadline and 128-byte strict UTF-8 reply; legacy/offline/unverified/error means voice-only. No camera or permission side effect. |
| Capability advertising / Android | Safely disabled pending binding | PeerEngine.callVideoSupport defaults false; only service/controller readiness may enable after v2 is fully integrated. Real TLS test enables a fake responder explicitly, not production support. Group CAPS and FILECAPS bytes/handlers unchanged. |
| Authenticated frame admission / Android | Implemented | Exact peer/call/protocol version, positive increasing integral sequence; invalid role/state does not consume sequence or refresh heartbeat. Wrong recipient INVITE rejected. Controller currently accepts v1 only; helper separately tests v2 syntax. |
| Call channel binding / Android | Implemented | CALLCONNECT cid retained through PeerEngine/Service/CallChannel; opening INVITE must match; frames and socket-close callbacks must belong to the exact owned channel. Stale/busy channels cannot control or end another call. Incoming opening has an absolute 15-second watchdog, canceled after admission/close. |
| v2 session/audio/video orchestration / Android | Pending | Next: v2 invitation/voice answer, audio gen=1, accepted request UUID/generation binding, original-caller video offer, explicit consent effects, bounded native worker, rollback/timers/camera-state convergence. |
| UI / FGS / native device / release | Pending | A07d/A08–A11 and AT03–AT06/A12/A13 remain; A06 adapter has no physical-camera acceptance. Do not enable capture with a permissive test gate in production. |

Relevant source: CallCapabilities, CallFrameAdmission, PeerEngine, CallController,
CallChannel, MessengerService and CallVideoProtocol. No wire-version change for
current callers; no new enabled video buttons, APK installation or signer change.
CallHandler now hands off peer, handshake cid and authenticated socket. Socket
close handling is identity-bound; an owned ringing channel loss also ends its
own call. No unrelated group/Windows application changes.

## Verification

- Production Java compile and D8 passed (51 production sources). Call checks
  expanded from 408 to 417 and passed. Confirmed/draft/consent/fake foundations
  289/0, frame admission 17/0, capability reader/deadline 18/0; native adapter JVM
  boundaries 41/0 (no native camera). Android Offline structural check passed.
- New actual verified TLS loopback capability checks: 8/0 (unverified/default
  disabled, fresh success, no cached success, local/remote simulated legacy,
  revoked verification and Offline). Actual call-channel checks with fake media
  exercise both-role framing, call binding, foreign frames and unrelated channel
  closure and mismatched opening INVITE: final 8/0. Final network runner output
  `a07-network-d519540ccdbe4ac8a782a4292f4c51f1`; every worker/socket closed.
- Current Java production classes passed existing integration.py and features.py
  against the unchanged Windows harness: secure messages, group/attachments,
  capability-dependent ordinary behavior, relays, clear and avatars. This is NOT
  production video or archived-binary voice acceptance.
- Earlier full a06-regression completed group size-16, migration, ownership,
  transfers and Offline suites, then failed WindowsUi/Program.cs:315 with socket
  error 10048 while reserving port 43872. Read-only check found the user's released
  LanMessenger-Windows-2.2.42 process listening there. It was not stopped or
  reconfigured. Full suite is NOT PASS. Earlier group timeouts were retried and
  that group suite ultimately passed. No unrelated fix performed.

Explicit runners: tests/video-contract/run.ps1, run-native-boundary.ps1 and
run-network-boundary.ps1. Normal tests/run.ps1 includes the new production
capability helper only; test APK/prototype code stays out of the normal runner.
Latest A07 build root:
`D:\LAN-Messenger\outputs\.build\video-feasibility\a07-admission-54be754abc024e51855a7cb0caca7a0c`.
Final production classes.dex SHA-256 after deadline/channel review:
`0619a193568abb6fb7649e16cfe4365f33f6f7624887a33289427fd729ee14bd`.
No test sources in production DEX. Final call regression 417/0; no full runner
remains active from the A06 checkpoint. Earlier intermediate hashes superseded.

## Next handoff

Implement the remaining A07 video coordinator/controller/session path off native
callbacks and controller locks. Use CallVideoConsent and CallVideoActions; every
OFFER/ANSWER/ICE/READY/STATE/ERROR must bind to the accepted request and exact
generation before admission. Video failure clears only video; initial audio must
connect first, and retry requires fresh bilateral consent/new generation. Add
collision, reordered/stale answers/ICE, timeout and failure fixtures before
capability readiness is enabled. Then diagnostics, renderers/self-view and camera
FGS eligibility/lifecycle, actual USB phone acceptance and signed candidate.

Usage window reset during this turn (6% five-hour / 24% weekly at start); no
near-limit pause currently needed. User permits USB adb and scrcpy UI checks;
computer-use skill guidance still must be read before Windows viewer actions.
