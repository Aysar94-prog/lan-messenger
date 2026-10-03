# Android video calling — execution handoff

CURRENT resume: A07-SIGNALING-HANDOFF.md. Capability probe and authenticated
call/channel/replay boundaries now implemented and verified; production video
advertising remains disabled until remaining v2 coordinator/session binding.
Latest full runner has ended, failing only late Windows UI port reservation.
Do not poll old session 22253 or rerun audio listening. Next A07 video negotiation
effects/timers/state convergence, then diagnostics/UI/FGS and actual phone gates.

Latest resume point: A06-MEDIA-HANDOFF.md. Production native video adapter source
now exists; controller/session/signaling and visible video controls are not yet
enabled. Fresh call checks 408/0, foundations 289/0, adapter JVM boundaries 41/0.
No physical-camera acceptance or signed video APK. Usage near limit; preserve
source checkpoint and inspect the latest full runner result before repeating it.

CURRENT: user accepts audio test complete and forbids wasting time repeating it.
A02b confirmed VP8/separate secured video PC; exact contract in A02b-CONTRACT.md.
Next: final-contract AT01 fixtures, then Android A04 consent and AT02. Historical
listening blocks below are superseded by this explicit user disposition.
scrcpy is approved for interface checks; separate UI from audio forwarding.
Current source checkpoint: A1-CONSENT-HANDOFF.md. AT01 confirmed validator 100/0;
AT02 consent 64/0 and effects 27/0. CallUi exposes call-bound distinct video
commands; actual A07 signaling/native binding and video controls still Pending.
Next dependency-ordered production step A05/EGL, then A06 separate VP8 camera PC.
Usage 81%/20%; near-limit handoff saved. No signed video candidate yet.

Latest human correction: "i didnt hear any thing" supersedes the selected
both-audible answer. Listening acceptance is FAILED/not demonstrated, not PASS.
See PAIRED-FEASIBILITY.md for decoded samples/energy and post-test earpiece route.
Retest with a clear live chat prompt, explicit speech and checked playback route.
No production release or media migration is accepted on RTP counters alone.

Latest continuation 2026-10-03: AP01 paired with real Windows native endpoint;
separate video-only PC qualifies on generated motion/G722/failure counters in
both call/video-offer directions. Single-PC rollback failed audio and is rejected.
See ../../windows/video-calling/PAIRED-FEASIBILITY.md for exact artifacts, private
evidence paths and remaining gates. A02b final contract not yet frozen; no signed
production video candidate. Historical table below predates these paired runs.
R05 is now explicitly authorized. Its dependency-ordered small tasks are in
../../windows/video-calling/R05-MIGRATION.md. Do not repeat the permission request.
Latest full regression failure and verification are in PAIRED-FEASIBILITY.md;
do not suppress/fix unrelated group logic inside video scope. The 90-second
physical audio-only listening window completed with test cleanup and unchanged
production package in evidence paired-ca605368b1924f24b67bd105976f33bc. Human
audible outcome requested, not inferred. No media test process remains running.

Last updated: 2026-10-03. Android execution was authorized by the user; USB adb
was explicitly requested. Continue only the frozen plan named below. Update this
record after each phase and before stopping if account usage approaches its limit.

## Approved plan and boundaries

- `.ai-planner/sessions/20261002-223910-bd586a/planning/plan-v007.md`
- SHA-256: `4ea7877d6643b5c89246b075d7a301d73b38bea28f644303383fa42f7ca0efbf`
- One shared `master` branch at `D:\LAN-Messenger\source`; outputs under
  `D:\LAN-Messenger\outputs`. Local commits only. Preserve unrelated untracked
  `.ai-planner` records; do not edit other plans or the frozen plan.
- Current task is Android. Windows WT01 evidence is an external dependency for
  Both A02b. No production video media starts before A02b passes.
- Use USB serial `R8YY80A8VLB` (SM-A075F) for current tests. Recheck device
  inventory before every resumed session; do not use the offline Wi-Fi endpoint.

## Small-task execution status

| Task / platform | Status | Dependencies / notes | Acceptance / test evidence |
| --- | --- | --- | --- |
| A01 Android | Complete | Execution authorized; source ownership mapped | `A0-OWNERSHIP.md`: consent/state diagrams, EGL lease and teardown design, rollback boundary |
| A02 Android | Complete (provisional) | A01 | `A0-DRAFT-CONTRACT.md`; separate CALLCAPS, candidates and limits; no final codec/mechanism selected |
| AT01 Android testing | Initial pass complete; rerun required after A02b | A02 | `tests/video-contract/run.ps1`: 43 passed, 0 failed; draft model only, production version-1 framing/admission checked |
| AP01 Android | Implemented; Android-local verified, ready for WT01 | A02 | Separate APK built/signed/installed by USB; real G722 + encoded/decoded moving VP8/VP9/H264; no Windows pairing yet |
| A02b Both | Pending external dependency | A02, AP01, Windows WT01 | Needs paired encoded video/decoded motion and continuing G722 including upgrade; Android loopback cannot close gate |
| A03 Android | Source implemented; OS/UI acceptance Pending | A02 | Call-bound camera permission preparation; 18 pure-Java boundary checks pass; `A1-PERMISSION.md` |
| A04 / AT02 Android | Pending | A03, A02b | Production consent UI/media races await shared gate |
| A05–A07d / AT03 Android | Pending | A02b, A04, AT01 | No production video media/signaling/diagnostics changes |
| A08–A09d / AT04 Android | Pending | A07 | No production renderer/self-view/diagnostics UI changes |
| A10–A11 / AT05 Android | Pending | A07, A09 | No production manifest/foreground service changes |
| A12–A13 / AT06 Android | Pending | Prior test gates | No release/version change or signed production candidate |

## Phase handoffs

### A0 — Android deliverables done; Both A02b still pending

A01/A02 and the initial AT01 pass are available for review. Draft code is isolated
under `tests/video-contract`; prototype code under `tests/video-feasibility/android`.
Neither normal production compilation nor `tests/run.ps1` includes the prototype.
Build key is disposable and outside source. AP01 was built, v2/v3 signed with a
throwaway key, and installed alongside production using USB. Activity command
access requires the shell's DUMP permission. It releases media on backgrounding,
Stop and test failure. Generated video is 320×240 at a nominal 15 fps; actual
negotiated stats are recorded rather than inferred from requested codec/rate.

USB device: SM-A075F, Android 16, arm64-v8a, serial R8YY80A8VLB. Production package
remained versionCode 69 / versionName 2.2.42 with identical first/last install
timestamps (2026-09-22 01:45:25 / 2026-10-02 20:01:23). Test sources, package and
production jar were checked for isolation. No production signing input was used.

Local generated-frame runs proved audio-only setup, upgrade, decoded motion in
both directions, real RTP encode/decode counters, then continuing two-way G722
after stopping video. These are single-phone native loopback results, not LAN
pairing, audible audio quality, physical-camera or packaged production acceptance.

| Trial | Decoded frames A/B during video | Evidence JSON filename |
| --- | --- | --- |
| VP8 re-offer | 61/61 | `c395ddec0116427184b97d7254f7b265-loopback.json` |
| VP8 inactive then activate | 62/64 | `c7d6b3246f144c20ad6a477f93cad943-loopback.json` |
| VP9 profile-id=0 re-offer | 63/63 | `2bf2b3a14b4e4efab40d0e72fa197f5c-loopback.json` |
| H264 constrained baseline re-offer | 58/58 | `b6c10fe079d54d58839fb835a5d1d28d-loopback.json` |
| Final test APK VP8 re-offer smoke | 61/62 | `83583b749ab64c33b2a7d4e59b39bf67-loopback.json` |

All files are under `D:\LAN-Messenger\outputs\.build\video-feasibility\evidence`.
H264 actually negotiated `profile-level-id=42e01f;packetization-mode=1` with
level-asymmetry-allowed=1. No codec/upgrade choice is frozen by these results.

Latest test APK:
`D:\LAN-Messenger\outputs\.build\video-feasibility\android-c37503bf2401465ba1938ae9a64b2d46\VideoFeasibility.apk`
SHA-256 `67bf2d236189002f13669e770afdfa7a968b7fa9397f8314046aac5fa6692092`.
Earlier prototype APKs remain in separate build folders; the first one was faulty
and is superseded. The initial test manifest lacked ACCESS_NETWORK_STATE, causing
a native abort; fixed. Explicit audio addTransceiver left the callee's sender
unmatched by Unified Plan and produced one-way G722; fixed using reusable addTrack.
Failure evidence is retained, not counted as acceptance.

Next gate: Windows W00/W01/W02/WT01 supplies a separate offline-restored prototype
and paired video + G722 measurements with this endpoint, in both directions and
during upgrade. Follow the pairing recipe in `tests/video-feasibility/android/README.md`.
Only then confirm/re-freeze A02b, rerun AT01, and start A04 onward.

### A1 — A03 prepared; A04 and AT02 awaiting A02b

Permission code has no live video call-control entry yet. It checks hardware and
current permission and binds the result to both call ID and unique action/request
identity; OS request codes are not reused across Activity recreation within the
process. It survives permission-dialog pause but discards actions after hangup,
Offline, replacement or Activity/service unbind. 18 pure-Java tests pass. Physical
OS/UI acceptance remains Pending. See `A1-PERMISSION.md`; no CAMERA manifest or
foreground-service changes are made before the planned A10 task.

### A2–A5 — not started

Await A0 shared contract gate for consent/media-dependent work. Do not mark these
phases complete on source compilation or simulated evidence.

## Usage / continuation notes

Account usage at initial execution check: primary 21%, secondary 3%; no limit hit.
Latest check: primary 44%, secondary 7%; stop is due to the A02b dependency, not
account exhaustion. Handoff and per-phase notes are saved even below the limit.
Recheck periodically and save exact completed work, commands, evidence paths,
failures and next steps here before approaching the limit.

## Build / regression evidence (current source)

- Android production: all 44 Java sources compile against android-34 + cached AAR;
  D8 produces production DEX. Test harness/draft classes are absent from the
  production jar. Output: `outputs/.build/video-feasibility/production-compile`.
  No signed production APK/candidate or version bump was made (A13 is not reached).
- Voice-call `CallCheck`: 408 passed, 0 failed.
- Draft video checks: 43 passed, 0 failed; A03 permission checks: 18 passed, 0 failed.
- Windows Release build: 0 errors, existing CS1998 warning in ChatWindowVoice.cs.
- Full `tests/run.ps1`: failed at Windows voice-device checks (11 passed, 5 failed),
  recording device could not open (`mmresult 1`). No Windows audio code was changed.
- Supplementary runner used original script in memory, skipping only the already
  failed Windows recording-device check; repository runner was not edited. Voice,
  draft/scheduler, cross-language voice transfer, attachment/resume, integration,
  group messaging, images, upload policy, direct/manual/Fast transfer, mixed-source
  and delete-conversation checks passed. It stopped at a Java peer startup timeout
  in `group_membership.py` (empty stderr), repeated in a targeted retry. A remaining
  runner invocation passed `group_migration_broadcast.py` in both directions and
  several ownership-transfer scenarios, then failed another Java peer startup
  timeout (`ownership_transfer.py`, peer Fifth, empty stderr). These tests were
  not changed. The separate unaffected Offline checks passed across same/cross-
  platform pairs and interrupted-transfer paths. Windows UI checks passed messaging,
  attachments, stable cards, history paging/cache and real voice playback/seek, then
  stalled at the next microphone-recording action. Its verified owned process was
  terminated (Windows CIM Terminate returned 0); the UI runner is incomplete and
  exited 1. Recording/lifecycle UI checks must be rerun with a working Windows mic.
  This is not a full-suite pass.
- Regression output root: `outputs/.build/video-feasibility/production-regression`.
- Test endpoint received explicit Stop and was force-stopped after verification;
  no test process remains. Camera app-op has no capture access entry: these trials
  used generated video, even though the test app has camera permission available.

## Resume boundary

Further R01 audit: newer upstream M155 ZIP has verified digest, pinned core and
bundled notices. Still not selected: transitive IJG/zlib terms require clarification
of user's MIT/Apache/BSD policy scope; security disposition remains Pending.
Windows audit handoff holds exact evidence. No prototype native load or Android
change; A02b remains Pending. No release/build acceptance added.

R01 execution update (2026-10-03): user authorized replacement feasibility work.
Windows libwebrtc archive SHA256 matches published checksum, but exact core
revision, static notices and security disposition are unresolved; input stays
HOLD. No replacement DLL executed or vendored. R02–R04/A02b remain Pending. See
windows/video-calling/R01-AUDIT-HANDOFF.md. Android code/installed package unchanged;
USB device remains R8YY80A8VLB. Latest usage 13% five-hour / 10% weekly, not near limit.

Latest decision (2026-10-03): user rejected the restricted library and requested
an MIT/Apache/BSD replacement without geographic terms. Windows research now
recommends libwebrtc BSD core + MIT native wrapper for feasibility, not yet
selected or executed. See windows/video-calling/REPLACEMENT-RESEARCH.md for the
small-task amendment and approval boundary. Existing Windows SIPSorcery references
are still present until a safe voice-compatible migration is proven. A02b remains
Pending; Android production video must not bypass it.

Continuation 2026-10-03: Windows W00/W01 inventory and candidate review started.
Physical integrated Windows webcam exists (formats/capture still Pending). W02
now requires clarification of full package license coverage: exact VP8 candidate
and existing SIPSorcery core carry additional geographic-use restrictions not
captured by the README's short BSD label. No candidate selected/vendored or new
prototype executed. See `windows/video-calling/W0-FEASIBILITY.md` for evidence,
task statuses and the user decision needed. USB phone remains R8YY80A8VLB on
same LAN. Usage 4% five-hour / 8% weekly; not near limit. Android source and
production package are unchanged in this continuation.
End checks: existing Windows Release build passed (0 errors, existing CS1998);
draft fixtures 43/0 and A03 permission fixtures 18/0; diff check passed. Full-suite
failure/incomplete results above remain open and were not rerun for docs-only edits.

Android execution is waiting for Windows WT01 and Both A02b. The frozen contract
has not been changed. A04 cannot proceed until evidence selects a proven common
codec and upgrade mechanism; AT01 then needs its post-gate rerun. A03 has no live
video controls or OS/UI acceptance yet. A2–A5, signed candidate and video releases
remain Pending. No push or branch/remote change was made.

Continuation 2026-10-03, accepted license policy: the user explicitly accepted
permissive transitive terms without geographic restrictions. Windows R01 reviewed
all 25 delivered M155 binary NOTICE components and dispositioned five recent
WebRTC advisories using pinned source/official fixes, not milestone assumptions.
The independent metadata checker passed assertions for 40,995 archive entries.
R01 still needs safe offline packaging: unused third-party header trees cannot
be cleared by the binary NOTICE alone. No native prototype/device media executed;
R02/R03/R04, WT01 and A02b remain Pending. Android source, signer, package and
previous automated/device evidence unchanged. Resume in Windows R01 handoff.

Final continuation milestone: R01 filtered offline input packaged (106 MB,
complete binary notices, unrelated header trees excluded). R02 native EXE and
net9 C ABI probe passed; G722 codec factory confirmed, invalid buffers rejected.
No actual audio/video session or new USB/device interaction yet. Resume R02c/d
in `windows/video-calling/R02-HANDOFF.md`, then paired R03/R04 for WT01/A02b.
Existing Windows build passed; tests/run.ps1 rerun stopped at the same five
recording mmresult 1 failures, later tests not run. Production and Android builds
not changed by this milestone. Usage 27% five-hour / 12% weekly, not near limit.

Continuation requested Android release and remaining work: added REMAINING.md
with all 13 pending implementation tasks, five test groups, AT01 rerun and Both
A02b dependency. No hours/near-completion claim. Advanced required Windows R02
codec proof: native VP8 generated encode/decode passed twice, net9 ABI once; each
20 encoded/20 decoded/19 decoded checksum changes/zero drops. No LAN, capture,
RTP or Android device command was used; not WT01/A02b. Next is native endpoint
ownership, SDP/ICE/audio, then generated paired media upgrade. Production Android
code/signing/version unchanged. Usage 36% five-hour / 13% weekly, not near limit.
# Latest A05 checkpoint — 2026-10-03

Continue from [A05 resource handoff](A2-RESOURCE-HANDOFF.md), not historical
audio/listening blockers. Source/DEX build, fresh call tests 408/0 and explicit
video foundations 289/0 passed. Full regression stopped at disk exhaustion.
A06 production camera/video adapter is next; no signed video release yet.
