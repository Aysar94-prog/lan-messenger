# A06 native media source checkpoint — 2026-10-03

Superseded run status: session 22253 has finished. Large groups, migration,
ownership, transfers and Offline ultimately passed. Windows UI then failed at
Program.cs:315, error 10048, because the user's released Windows app occupies
port 43872. It was not stopped. Full suite is not PASS. Resume current source
from A07-SIGNALING-HANDOFF.md, not the historical running-session notes below.

Platform Android; execution authorized. Frozen plan-v007 and A02b VP8/separate
secured video-only PC selection unchanged. Audio listening accepted complete;
do not repeat it. User-approved disk cleanup completed; space no longer blocks.

## Small tasks / acceptance

| Task | Status | Dependency / acceptance |
|---|---|---|
| A06 separate native video PC | Source implemented | A05, A02b; same Android native factory/ADM as audio, one independent video-only Unified-Plan PC, no STUN/TURN or data-channel creation. Actual production media acceptance Pending. |
| A06 VP8/source/track/ICE | Source implemented | Select VP8 via transceiver codec preferences, validate secure video-only local/remote SDP, bound/deduplicate ICE (128), remote sink delivery. Initial answer applies offer before addTrack to reuse offered transceiver. |
| A06 camera | Source implemented | Explicit start after secure PC Connected, external consent/foreground gate and current CAMERA permission; Camera2/Camera1 fallback, front preference, stop/on/switch, borrowed frame forwarding rechecks eligibility. Physical acceptance Pending. |
| A06 failure/ownership | Source implemented | Independent video errors, generation-scoped cleanup; bounded worker/event queues; no blocking controller/socket work on native callbacks. Parent closes video before native factory; unfinished cleanup poisons/retains factory/context rather than freeing live native resources. |
| A07 binding / AT03 | Pending | Controller must authenticate call/peer/request/generation before invoking video; build fixtures and real native/device checks. No CALLCAPS advertised and no camera feature enabled yet. |

Implementation: android/src/net/lanmsg/chat/WebRtcCallVideo.java; lazy adapter
exposed by WebRtcCallMedia.video(). Initialization/SDP/render binding never opens
a camera. Audio adapter methods still use original audio-only PC. Camera capture
defaults 640x480/20 fps, selected VP8 only; physical formats/adaptation not verified.
Renderer leases retain service EGL independently. Camera failures affect video,
not the healthy audio connection. FrameSink callbacks must never retain frames or
perform controller/socket work. Camera state/revision UI notifications await A07.

Fresh compile before final review: 49 production Java sources passed, output
`D:\LAN-Messenger\outputs\.build\video-feasibility\android-a06-530c5ddb2d6d4fb591d63fb31537c3ad`.
Final 49-source production compile and D8 (minimum API 26) passed at
`D:\LAN-Messenger\outputs\.build\video-feasibility\a06-final-caf78a663ea94363832f2566d8578477`.
Final classes.dex SHA-256:
`5cab5e6b66483f422cd97963abf5562120f1364f728c5e05f4777802f864a82c`.
Only production sources and pinned SDK classes are in that DEX; no test class.
Fresh call checks passed 408/0. Video contract/fake groups passed 289/0; new
explicit run-native-boundary.ps1 passed 41/0 against the production video adapter
and real pinned SDK Java classes on the JVM (no native factory/camera).
Boundary checks exercise failed initialization cleanup across 20 lifetimes,
generation rejection, duplicate/capped sinks, camera-free binding, stale calls,
idempotent shutdown and sanitized SDP callback failures. git diff --check passed.
The frozen plan SHA-256 remains unchanged.
Full runner active at outputs/.build/video-feasibility/a06-regression, session
22253 during this checkpoint. Do not start a competing regression until it ends.

Latest full-run observation: microphone checks 20/0 and transfer/resume suites
passed, including the previously disk-blocked 128 MiB transfer. Basic group
membership checks passed; large 16-member group is still running and has emitted
TLS/network timeout diagnostics while retrying pairing. No full-suite PASS yet;
later migration/ownership/Offline/UI checks cannot be claimed run. This is the
same unrelated large-group area seen in earlier checkpoints; no app fix outside
video scope. Adapter source saved locally in commit 3eb84ec; no push.

Usage checkpoint: 95% five-hour / 23% weekly; saved before additional integration.
No production release/signing/version/installation changes. Production remains
2.2.42/code69. A06 source is NOT evidence of packaged physical-camera acceptance.

## Next exact work

Finish A06 native tests/review (timeouts, cleanup failure, camera-off/restart,
remote track delivery, repeated lifetimes) and then A07 CALLCAPS/v2 controller
and session integration. Enforce bilateral consent and eligible foreground camera
FGS before exposing actual capture (A10 later); never call the adapter with a
permissive test gate in production. A07d/A08–A11, release gates and authorized
Windows migration remain open. Physical production camera and scrcpy UI checks
have NOT occurred. Keep AP01 test-only; do not copy its Activity into production.
