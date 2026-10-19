# Android video release remaining checklist — 2026-10-03

Latest cleanup repair: Android2.2.44/code71 supersedes2.2.43 for testing.
Receiver-track double disposal reproduced/fixed; three native media lifetimes,
following voice setup/cleanup, pure385/JVM50/TLS8+19/call417 pass. Signed APK
USB-installed without clearing data. Exact two-phone acceptance still pending;
both endpoints must update. Full regression result and current repair phases:
`CLEANUP-FIX-HANDOFF.md`. Windows/R05/Both gates are unchanged.

## Current continuation (supersedes the historical table below)

| Android task | Source / package status | Remaining acceptance |
|---|---|---|
| A04/A07 | Per-call actions and v2 coordinator/controller implemented |37 pure coordinator checks,19 actual TLS/fake-media channel checks pass. Production packet/UI call pairing remains. |
| A05/A06 | Shared EGL and production video adapter implemented | Physical phone adapter camera/switch/stop and encoded VP8 local pairing passed separately in AP01; packaged controller integration remains. |
| A07d | Native nonblocking stats/allowlisted report implemented |13 pure metrics/sanitization checks pass; exact APK panel/clipboard acceptance remains. |
| A08/A08m/A09/A09d | Surfaces, drag/hide/reset and controls/copy implemented |9 placement checks; physical connected-call UI/rotation/clipboard remains. |
| A10/A11 | Camera FGS and foreground/background eligibility implemented | Online-with-CAMERA-denied passed on signed candidate; clean permission/privacy/thermal/recreation stress remains. |
| A12 | Build,375 then383 pure checks, native boundaries41, TLS8/19 pass | Full production regression still running; notices and full packaged acceptance not closed. |
| AT06/A13 |2.2.43/code70 arm64 original-key candidate built and USB-installed | Upgrade retains first-install timestamp and visible contacts/group; deeper data/connected video acceptance remains. Not final release closure. |

Exact candidate hash, adapter/phone evidence and active full-run log/session are
in A3-A4-CANDIDATE-HANDOFF.md. Previous stable release remains2.2.42; installed
test candidate is2.2.43. Windows2.2.42 cannot video-call; R05 and Windows production
video remain explicitly authorized but incomplete. Both B0–B2 remain Pending.

The following table records the earlier checkpoint, not current implementation.

Scope: Android section of unchanged frozen plan-v007, plus its required Both
A02b feasibility gate. Execution authorized. Original plan/hash remain untouched;
this is a continuation checklist, not a new media contract or broadened scope.

A01/A02 design, provisional AT01, Android-local AP01 and A03 permission preparation
are implemented/documented, not full device acceptance. Windows replacement R01
input prepared; real paired generated VP8/separate-video upgrade and two-way G722
passed both directions, including video failure/disposal voice continuity. See
../../windows/video-calling/PAIRED-FEASIBILITY.md. Final A02b contract, baseline
voice replacement and production/UI/camera acceptance remain separate gates;
there is still no signed production video release or honest completion-time estimate.

| Task / platform | Status | Dependencies | Work / acceptance and testing |
|---|---|---|---|
| A02b / Both | Selection/spec complete | AP01, WT01/R03/R04 | VP8 separate secured video PC selected from paired evidence; A02b-CONTRACT.md. Confirmed syntax fixtures 100/0; controller enforcement and C# mirror remain later work. |
| A04 / Android | Policy/commands implemented; integration pending | A03, A02b | Consent fixtures 64/0 and command fixtures 27/0. Actual controller binding and visible actions await A07/A09; OS acceptance not claimed. |
| A05 / Android | Interface/EGL/fake implemented; device acceptance pending | A04, A02b, AT01 | Resource fixtures 19/0 and fake adapter 18/0. Native factory/context ownership compiles; production video adapter awaits A06. See A2-RESOURCE-HANDOFF.md. |
| A06 / Android | Native adapter source implemented; physical acceptance pending | A05, A02b | WebRtcCallVideo: separate secure VP8 PC, bounded ICE, explicit gated camera/source/track/sinks, video-only errors and teardown. JVM worker/resource boundaries 41/0; actual encoded media/camera acceptance remains AT03. See A06-MEDIA-HANDOFF.md. |
| A07 / Android | Capability/authenticated envelope/channel boundaries implemented; coordinator Pending | A06, A02b | Fresh verified CALLCAPS, strict peer/cid/version/sequence and owned socket binding verified. Advertising disabled; v2 audio/video orchestration, consent effects, generations/collision/late ICE/rollback and camera-state convergence remain. See A07-SIGNALING-HANDOFF.md. |
| A07d / Android | Pending | A07 | Bounded diagnostics plus allowlisted copied report; missing/reset/unit/sanitization fixtures in AT03. |
| A08 / Android | Pending | A07 | Remote and self-view EGL rendering, orientation/reopening/aspect/mirroring without stale sinks. |
| A08m / Android | Pending | A08 | Drag/hide/reset preview with testable bounds, preserve transmission when hidden. |
| A09 / Android | Pending | A08m | Accessible prompts, camera/switch controls, existing voice controls, proximity behavior. |
| A09d / Android | Pending | A07d, A09 | Optional real/unavailable diagnostics; explicit copy/confirmation/clipboard preview with no signaling secrets. AT04 phone UI checks. |
| A10 / Android | Pending | A07, A09 | Optional camera hardware and foreground camera type promotion/demotion; in-use permission/background constraints. |
| A11 / Android | Pending | A10 | Lock/background/privacy/contention/Offline cleanup; eligible audio survives camera loss. AT05 phone lifecycle checks. |
| A12 / Android | Pending | AT01–AT05 | Relevant full regressions and APK manifest/native ABI/notices/size audit; report failures instead of treating build as acceptance. |
| A13 / Android | Pending | AT06 | Original-key locally signed candidate, chosen version/code, output checksum and local commit; preserve data and exclude .private. |

Five pending Android test groups: AT02 consent races; AT03 media/state/diagnostics;
AT04 screen/self-view/clipboard; AT05 device/service lifecycle; AT06 signed
upgrade/native/UI acceptance. AT01 also needs its post-A02b rerun. These are not
five quick unit tests: several require actual phone/OS behavior. A12 follows
AT01–AT05, and candidate upgrade acceptance precedes A13 completion as specified
in the frozen plan. Final Both B0–B2 production interoperability/release decisions
are separate and must not be implied complete by Android candidate preparation.

Next authorized step is A06 native verification and A07 controller integration.
Historical full regression stopped at disk exhaustion; user-approved temporary
test cleanup cleared space, and a fresh A06 full run has been started. Use
USB adb; never replace production app with a prototype. Current production release
remains 2.2.42/code69. Audio listening is accepted complete by the user.
