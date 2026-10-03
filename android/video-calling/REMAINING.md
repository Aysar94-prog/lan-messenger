# Android video release remaining checklist — 2026-10-03

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
| A02b / Both | Pending | AP01, WT01/R03/R04 | Select proven common codec/upgrade from actual encoded video plus continuous G722; local loopback alone insufficient. Re-run AT01. |
| A04 / Android | Pending | A03, A02b | Explicit video accept/voice answer/upgrade decline/camera-on consent; denied or stale actions keep audio and camera off. AT02 fake race/permission tests. |
| A05 / Android | Pending | A04, A02b, AT01 | Media interface and service-owned EGL; renderer release order and sink lifetimes tested. |
| A06 / Android | Pending | A05, A02b | Selected codec, capturer/source/track, authorized SDP and secure video. Legacy voice remains camera/video-m-line free. |
| A07 / Android | Pending | A06, A02b | Invitation/consent/generations/collision/late ICE/rollback and camera-state convergence; fake failure scenarios in AT03. |
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

Next authorized step is offline endpoint packaging then A02b contract/fixtures,
because A04/production media cannot bypass A02b. Use USB adb for AP01, never replace
production app for a prototype. Current production release remains 2.2.42/code69.
