# Windows video calling — audited continuation plan

Revision: 2026-10-04. Source baseline: local commit `ce80380`.
Platform: **Windows**, with explicitly marked **Both** interoperability tasks.
This revision is a documentation review only: no application changes, build,
new test execution or device acceptance are claimed.

## Scope and authority

Deliver production Windows one-to-one voice/video calls using the approved native
replacement, compatible with current Android video and legacy voice peers.
Include the previously requested trusted recipient controls as a dependent phase;
do not enable those controls before their grants and media paths are implemented.
Prior R05 migration and permissive transitive-license approvals remain valid.
This review does not execute implementation; wait for the user's next start instruction.

This is the current Windows continuation plan. Preserve frozen
`.ai-planner/sessions/20261002-223910-bd586a/planning/plan-v007.md` and its hash.
R05, R01/R02 and feasibility handoffs remain historical evidence, not current
completion checklists. Do not restart completed Android phases from their older wording.

Non-goals: group video, screen sharing, recording, internet relay, call hold,
automatic reconnection, Android background-camera changes, Android Wi-Fi repair,
Windows Direct-connections UI, or unrelated attachment/compose changes.

## What is actually done

| Area / platform | Implemented or established | Verification and limits | Status |
|---|---|---|---|
| Windows current application | Version 2.2.42; SIPSorcery 10.0.17 voice-only `WebRtcCallMedia`; authenticated CALLCONNECT/v1; trusted voice auto-answer | No production native replacement, CALLCAPS/video coordinator, webcam capture or video renderer | Existing baseline, video NOT implemented |
| Windows dependency research | Native WebRTC M155 `m155.8059.2.0` selected for feasibility; pinned upstream core and notices reviewed; user accepted permissive transitive licenses | Dated audit is not a current security clearance. Older M150 wrapper remains on hold | Research/prototype input complete; production clearance pending |
| Windows native prototype | C ABI audio/video endpoints, G722, generated VP8, bounded handles, explicit CoreAudio startup, static CRT | Offline prototype publish; codec motion; cap/fifth rejection; 20 repeated teardown lifetimes | Feasibility implemented, not a production library |
| Both native paired media | Separate video-only secured PeerConnection passed VP8 moving frames in both caller directions; tested video failure/disposal preserved G722 packets | Physical Android endpoint but generated pictures, not physical Windows webcam. Test-only drivers, not authenticated production calls | Media feasibility PASS |
| Both legacy media | Native ↔ unchanged Windows 2.2.42 G722 media passed both offer directions and legacy mute checks | Media adapter exercise only; actual HELLO/READY/CALLCONNECT application path still needs testing | Partial compatibility proof |
| Both architecture | One-PC video rollback interrupted audio; separate-PC approach passed tested isolation | Single-PC production approach rejected | Decision settled |
| Both wire | Android A02b contract defines VP8-only independent video PC, consent, identity/generation binding and deadlines | Windows v1 implementation has not adopted it; additive trusted controls need reconciled fixtures | Core contract confirmed; Windows implementation pending |
| Android current application | Production video coordinator/capture/rendering exists; latest test candidate 2.2.67/code94 includes fresh-grant recipient visibility | Physical call/control acceptance remains distinct. SM-A075F Wi-Fi fault unresolved | Do not redo Android from zero |
| Audio listening | User accepts audio testing complete and requests no repeated listening | Continue automated continuity/mute/route checks; historic no-sound entries are not a renewed listening gate | Accepted by user |
| Windows regression baseline | Prior build/publish and targeted tests exist | Historical full-suite group-cap/reinvite failure is not a full-suite pass; reproduce and classify during execution | Known baseline issue |

Evidence references: [paired feasibility](PAIRED-FEASIBILITY.md),
[R01 audit](R01-AUDIT-HANDOFF.md), [R02 handoff](R02-HANDOFF.md),
[hardware inventory](W0-FEASIBILITY.md),
[Android contract](../../android/video-calling/A02b-CONTRACT.md),
[trusted access plan](../../PLAN-TRUSTED-CALL-ACCESS-AND-APP-ICON.md).

Read-only verification during this review confirmed:

- Frozen plan SHA-256: `4ea7877d6643b5c89246b075d7a301d73b38bea28f644303383fa42f7ca0efbf`.
- Test-only native package SHA-256: `0131cad1d573250a1b9423b4e36bdfdc5946a46e43efc2c8d48e6fc03efafb6a`.
- Hardened prototype endpoint DLL SHA-256: `e4f9e92fcdead1c4e6a136b8539569fc45d1ecad63677de69f71ee4a0a779c72`.
- Paired separate-PC runs `paired-c77d4e0320f149419c4b9b4053677b6b`,
  `paired-74257b8f512140d1b93f9d5e9c6a3181` and hardened
  `paired-d10513aabd9b4ecfb48531a527d43d41` retain passing results.

## Fixed design constraints

1. Keep original audio PC/G722 alive independently of video. Video errors dispose
   video only; audio failure remains fatal. No single-PC upgrade/rollback retry.
2. Use fresh verified TLS CALLCAPS per call, no cross-call cache. Unsupported,
   unknown and simulated-legacy peers use unchanged voice v1. Capability is not a grant.
3. Match A02b strictly: 64 KiB frame bound, 48 KiB SDP, 4 KiB ICE, 128 ICE per
   generation; integral signed-64 counters; peer/call/request/generation binding;
   original caller alone offers; audio generation 1; monotonically new video generations.
   Reject bad frames before heartbeat/deadline/accepted-sequence advancement.
4. Probe deadline 10 seconds, video consent 30 seconds, video negotiation 15 seconds.
   One outstanding request; lower UUID wins collision; no consent transfer;
   bounded retired-request tracking and no identifier/counter reuse.
5. Camera acquisition requires local consent/live OS permission or an explicit,
   certificate-bound trusted grant within the agreed policy. Never acquire during
   capability probing/ringing. Remote VIDEO_STATE is information, not authority.
6. Separate protocol parsing, call policy, native transport, capture and rendering.
   Marshal UI updates; never block the UI or call-controller locks on native work.
7. Preserve legacy signaling, voice messages, ringtone and TLS dependencies.
   Removing SIPSorcery does not authorize deleting BouncyCastle or all winmm code.
8. Use production-owned sources/package/integration. Do not ship the test-only
   NativeProbe project, generated source, feasibility DLL or throwaway Android signer.

## Small-task execution checklist

Status key: **Done** = supported by evidence above; **Partial** = reusable proof but
acceptance remains; **Pending** = not implemented; **Decision** = resolve before dependents.
Every row includes its own acceptance/testing gate. Execute in dependency order.

### A. Lock the baseline, contract and production inputs

| ID / platform | Status | Depends on | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| WVC-01 Windows baseline | Partial | None | Record clean/dirty source, current build/test baseline, exact archives, current factory and dependency graph. Preserve user changes. Inventory existing failures separately. | Reproducible baseline report; no group-suite pass asserted from targeted checks. |
| WVC-02 Windows architecture | Decision | 01 | Prototype is x64; application has no explicit RID/PlatformTarget. Decide supported production architectures and packaging before integration. Inventory RGB webcam formats/driver; distinguish IR camera. | Document architecture support with user approval for any support reduction; unsupported binaries fail clearly. Physical camera enumeration evidence. |
| WVC-03 Windows production dependency | Pending | 02 | Pin M155 production input, repeat source-specific advisory review, retain provenance/NOTICE/VERSIONS/DEPS, required static-component notices and redistributable/import inventory. Keep old M150 input excluded. | Reviewable pinned hashes/license disposition; offline input available; no geographically restricted RTC dependency; no claim of exhaustive security clearance. |
| WVC-04 Both contract reconciliation | Partial | 01 | Adopt A02b; reconcile actual Android CALLGRANTS, REMOTE_CAMERA and REMOTE_SPEAKER with the earlier provisional trusted-plan names. Freeze support/fallback behavior and exact rejection semantics. | Signed-off field/state table reflecting current Android source; no invented command names; no protocol downgrade. |
| WVC-T01 Both wire fixtures | Pending | 04 | Add shared valid/invalid v1/v2 fixtures early, independent of final packaging. Cover lengths, numeric types, unknown keys, media SDP, ICE, identity, sequence, request retirement, collisions and deadlines. | Both parsers accept/reject same fixtures; serialization comparisons respect JSON key-order semantics; legacy voice fixtures preserved. |

### B. Replace Windows voice safely before activating video

| ID / platform | Status | Depends on | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| WVC-05 Windows production native ABI | Pending | 03 | Promote proven ownership concepts into production code: bounded inputs/outputs, safe opaque handles, exception boundary, copied data, explicit lifetimes. No reuse of stale handles. | Null/oversize/stale/double-destroy tests; cap rejection; repeated teardown; no callbacks after owner disposal. |
| WVC-06 Windows native audio | Partial | 05 | Implement production CoreAudio ownership/COM thread initialization, checked Init/Start recording/playout, mute and default route. Prototype proves approach, not final integration. | Camera-free voice creation; capture only on connected/accepted path; startup failures visible; mute/device loss/default-route/teardown tests. |
| WVC-07 Windows managed adapter | Pending | 05,06 | Implement `ICallMedia` replacement with bounded asynchronous operations, cancellation, callback ownership and available copied stats. Keep factory switching separate. | Fake/native tests cover timeout, race, dispose during operation and UI responsiveness; no native waits under controller locks. |
| WVC-T02 Both authenticated voice gate | Pending | 07,01 | Use actual application HELLO/READY/CALLCONNECT with replacement behind controlled factory selection. Pair archived Windows 2.2.42 and current Android, both caller directions. | G722 send/receive, mute, hangup, Offline, busy and identity rejection pass. Automated media evidence, no repeated manual listening request. |
| WVC-08 Windows offline integration/switch | Pending | T02,03 | Integrate production package/build; switch default factory only after gate. Remove SIPSorcery reference and RTC transitive artifacts after dependency-use review. Retain independent TLS/voice-message code. | Clean-folder offline restore/build/publish per supported architecture; runtime imports resolve; no prototype/restricted RTC package in output or restored graph. |
| WVC-T03 Windows voice regression | Pending | 08 | Run controller, engine, UI and production voice suites; reproduce known unrelated full-group failure and classify separately. | Replacement introduces no new voice failures; remaining baseline failure explicitly documented, not silently waived. |

### C. Add authenticated v2 and isolated video coordination

| ID / platform | Status | Depends on | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| WVC-09 Windows capabilities/parser | Pending | 04,T01,08 | Implement fresh CALLCAPS responder/probe and strict v2 parser while preserving v1. Keep FILECAPS/group contracts unchanged. | Legacy fallback, probe timeout/size, fragmented frames, malformed/replayed/wrong-peer input tests pass; invalid input cannot prolong call. |
| WVC-10 Windows consent/controller | Pending | 09 | Add video invitation, audio-only answer, mid-call bilateral upgrade, cancel/decline/timeouts, caller-only offers and deterministic collision policy. | Fake-media state tests cover both upgrade initiators, simultaneous requests, late acceptance, stale callbacks, permission denial and no camera before consent. |
| WVC-11 Windows video transport | Partial | 05,10 | Production separate video-only PC, VP8 SDP, authenticated fingerprints and bounded ICE. Bind all callbacks/readiness/errors to active call/request/generation. Use test-generated input only in test harness. | Production adapter loopback and fake failures keep audio alive; new retry needs fresh bilateral consent; stale generations cannot revive video. |
| WVC-T04 Both generated production interop | Pending | 11,T01 | Exercise real production coordinator/signaling with a test-only injected frame source, both caller directions and both upgrade initiators. | Moving VP8 frames both ways; correct original-caller offer ownership; failure isolation and v1 fallback. Explicitly label generated, not webcam acceptance. |

### D. Capture, rendering and user-facing call controls

| ID / platform | Status | Depends on | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| WVC-12 Windows webcam capture | Pending | 02,11 | Enumerate physical RGB cameras; select stable device IDs; acquire after consent, release on video end. Handle no camera, privacy denial, unplug, busy camera and switching. Start from proven 320x240/15 fps media baseline; choose final profile by measurement. | Actual Lenovo RGB capture and another available camera if possible; formats/rotation validated; no unintended IR selection or acquisition on probe/ring; switching failure does not kill audio. |
| WVC-13 Windows frame ownership | Pending | 11 | Export bounded owned/copied frames with validated dimensions/strides/pixel format/rotation. Latest-frame bounded queue; dispose dropped buffers. | Slow-consumer and malformed-frame tests; bounded memory; no use-after-free across callbacks/UI disposal. |
| WVC-14 Windows renderer decision | Decision | 13 | Measure smallest suitable WinForms rendering path against alternatives only as needed. Record CPU/memory, scaling and threading; freeze performance thresholds before judging acceptance. | Correct color/aspect/rotation and responsive UI at target resolution/DPI; renderer choice documented, not selected from prototype checksum counters. |
| WVC-15 Windows video UI | Pending | 10,12,14 | Video invite/accept/audio-only flows, remote view, local preview, camera toggle/device choice, clear errors and audio fallback. Movable/hideable/resettable preview; hiding preview must not imply stopping camera. | Keyboard/focus/accessibility and 100/125/150/200% DPI tests; video failure visible with usable voice controls; preview resets inside window bounds. |
| WVC-16 Windows lifecycle | Pending | 12,15 | Define and implement active-call minimize/tray behavior with visible camera indicator/local stop. Lock stops camera; sleep/device loss/end releases capture. Wake does not silently reacquire without eligible policy. Android app-switch capture-stop policy remains outside scope. | Minimize/restore, tray, lock/unlock, sleep/wake, permission/device loss, hangup/Offline/app exit tests; no hidden residual capture; audio behavior recorded separately. |
| WVC-17 Windows quality/diagnostics | Pending | 11,14,16 | Add copied transport/media stats, bounded adaptation and allowlisted diagnostics/copy. Measure CPU/memory/frame rate/drops and audio continuity on degraded LAN. | No SDP, ICE/IP details, certificates, keys or private content in copied report; target thresholds fixed before test; adaptation cannot starve audio. |
| WVC-T05 Windows capture/UI stress | Pending | 15,16,17 | Physical webcam run at least 10 minutes; 20 start/stop lifetimes; rapid toggle/switch/resize and slow-renderer stress. Proposed shutdown target <=2 seconds, finalize against measured baseline. | No crash, leaked camera handle, growing frame queue or post-dispose update; record actual durations/resource trends and unmet targets. |

### E. Previously requested trusted recipient controls

| ID / platform | Status | Depends on | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| WVC-18 Both control contract | Partial | 04,10 | Match Android: CALLGRANTS optional v1 query returns mask 0..15; REMOTE_SPEAKER gen0 with boolean speaker; REMOTE_CAMERA active video generation with request/camera/facing (front/rear/keep). Define Windows route/camera mapping honestly: desktop devices are not handset front/rear or earpiece. | Fixtures and explicit unsupported behavior agreed; capability alone never grants control; no arbitrary webcam advertised as front/rear. |
| WVC-19 Windows grants/enforcement | Pending | 18,16 | Enable video/camera/speaker scopes only as implemented. Bind owner grants to verified ID/certificate; enforce normal incoming admission and recipient-side scope checks on every action. Preserve voice-only baseline. | Independent-bit, unknown/forged peer, changed key, revoke/delete/global Offline/busy tests; no escalation from video grant to camera/speaker grant. |
| WVC-20 Windows recipient visibility | Pending | 19 | Recipient controls shown only for fresh confirmed grants FROM recipient TO caller (Slave direction), during eligible connected call. Unknown/failure/revoked/expired hides. Mirror Android freshness policy only through reconciled contract, not stale informational cache. | No grant => no control; camera-only/speaker-only => only that control; refresh, expiry, revoke and action recheck tested. Master-direction local grant cannot authorize controlling peer. |
| WVC-21 Windows control UI/override | Pending | 20,15 | Add disclosure, immediate local override/revoke and supported camera/route controls. Track requested vs confirmed state; no premature success display. | Both-direction Android pairing, deny/revoke/override/device loss; local controls remain available. Separate broader Master/Slave list UI is not implicitly added. |
| WVC-T06 Both trusted controls | Pending | 21 | Test actual authenticated production commands with zero grants, each bit, combinations and mid-call revoke; both caller directions. | Camera remains off absent appropriate authorization; forbidden controls hidden AND rejected; failed video/control request preserves audio. |

### F. Candidate, physical interoperability and release gates

| ID / platform | Status | Depends on | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| WVC-22 Windows candidate packaging | Pending | T03,T04,T05,T06 | Produce test candidate from exact source with pinned production native inputs/notices; inspect architecture/load behavior. This is BEFORE final acceptance, not final release. | Candidate hashes, source revision, output path and import/notice manifest recorded; offline launch on clean supported Windows environment. |
| WVC-T07 Both physical calls | Pending | 22 | Actual Windows webcam ↔ current signed Android production candidate. Start with stable SM-S908E; SM-A075F remains separate Wi-Fi-risk coverage. Both callers, both upgrades, initial video/audio-only, decline/busy/hangup, camera switch and video-only failure. | Physical moving pictures both ways, policy/UI acceptance and audio continuity; exact packages recorded. A07 failure cannot be presented as repaired or hidden by S22 success. |
| WVC-T08 Both compatibility/degraded LAN | Pending | 22,T07 | Current Windows/Android, archived Windows 2.2.42, and archived Android voice on spare/test environment when available. Never downgrade preserved phones. Add loss/latency/disconnect, Android Direct-mode selected-peer scenario and malformed input. | Voice-only peers never receive video SDP; actual v1 compatibility passes both directions; unavailable old Android acceptance stays Pending. Direct-mode pairing does not add Windows Direct UI. |
| WVC-23 Windows final acceptance | Pending | T07,T08 | Consolidate all gates and open issues, classify baseline regressions, obtain release decision for any residual issue. Update Windows status/platform comparison without implying Android Wi-Fi acceptance. | Code, automated results, physical acceptance and known limitations independently stated; no failed mandatory gate silently accepted. |
| WVC-24 Windows final package | Pending | 23 | Build authorized final artifacts into outputs, retain notices/provenance, local commit; push only if requested. | Exact final hashes, version and supported architectures; no private/test inputs; archive reproducible inputs. |
| WVC-T09 Both exact-final smoke | Pending | 24 | Launch exact final package and repeat representative physical voice/video/control/legacy smoke; if rebuild occurs, smoke new hashes again. | Final-package evidence, not merely earlier candidate evidence; no release-complete claim before this gate. |

## Critical path and practical checkpoints

`01–04 → T01 → 05–07 → T02 → 08/T03 → 09–11 → T04 → 12–17/T05`

Trusted controls branch: `04/10 → 18–21 → T06`.
Both branches join at `22 → T07/T08 → 23 → 24 → T09`.
Independent input/fixture/UI-design preparation can overlap, but no gate is skipped.
Cross-language fixtures occur early; candidate packaging precedes physical tests;
final packaging follows acceptance. This removes the older package/test dependency cycle.

Checkpoint 1: replacement voice works through authenticated production calls.
Checkpoint 2: production video signaling works with generated test input.
Checkpoint 3: real webcam, rendering/lifecycle and fresh-grant controls pass.
Checkpoint 4: packaged physical/legacy interoperability and exact-final smoke pass.
Do not estimate a reliable completion date before checkpoint 1 and webcam measurements.

## Acceptance evidence discipline

For every executed row record status, source revision, test command/result, exact
binary hashes, backend/version, architecture, device/OS, duration and limitations.
Separate simulated/fake, generated-media, physical-camera and human UI evidence.
Keep failed runs and sanitized summaries; do not copy raw SDP/candidates into public docs.
No new listening approval is required, but packet continuity alone is not webcam/UI acceptance.

Minimum physical matrix: both original caller directions; initial video and voice
then upgrade from either participant; simultaneous upgrade; audio-only answer;
camera denial/busy/unplug/switch; hangup/Offline; video timeout/malformed video SDP;
minimize/tray/lock/sleep; no grant/individual scopes/revoke; legacy voice fallback.
Video failures must leave audio connected where audio transport itself remains healthy.

Decisions still needed during execution: supported architectures, measured video
profile/render thresholds, Windows route/front-rear semantics and availability of
an archived Android test endpoint. These are not excuses to redo settled media research.

## Mapping from older plans

| Older item | Current disposition |
|---|---|
| W00 / W01 / WT01 | Hardware inventory and native media feasibility Partial/Done; production webcam and authenticated voice still pending. New 01–03, T02,12. |
| W02 / R01 / R02 | Prototype audit/package done; production provenance/security/offline integration still 03,05,08. |
| R05.1–R05.3 | Prototype audio evidence reusable; production bridge/adapter and actual call-path gate 05–07/T02. |
| R05.4 / B01 | A02b core confirmed; additive controls/fixtures 04/T01/18. No final-package dependency. |
| W03/W04/WT02 | Production capability/strict parser/consent coordinator 09–10. |
| W05/W06/WT03 | Native separate-PC path 11 and physical capture12; obsolete SIPSorcery video implementation wording removed. |
| W07/W08/W08m/WT04 | Frame ownership, measured renderer and UI 13–15. |
| W09/W10/W10d/WT05 | Lifecycle, performance, safe diagnostics16–17/T05. |
| Trusted T08–T10 | Dependent controls18–21/T06; do not assume every historical Android trust/history proposal is implemented. |
| W11/W12/WT06, B02–B06/BT01–BT03 | Regression/candidate/physical/legacy/final sequence T03,22–24/T07–T09. |

After execution update this checklist, `windows/STATUS.md` and `PROJECT_STATUS.md`;
update Android status only for actual Android changes or new Android acceptance evidence.
