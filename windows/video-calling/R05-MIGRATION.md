# R05 production migration execution plan — 2026-10-03

Platform: Windows / Both legacy compatibility. User explicitly approved replacing
the Windows production calling library in this chat after paired feasibility.
This extends the previously approved R01–R04 boundary; it does not waive R03,
A02b, physical acceptance, notices or original Android release gates.
Frozen plan-v007 and its SHA256 remain unchanged.

| Small task / platform | Status | Dependencies | Work / notes | Acceptance / testing |
|---|---|---|---|---|
| R05.1 Windows native audio decision | Planned | R02 offline clean publish, R03 | Use Google native CoreAudio ADM with checked startup, COM/thread ownership, mute and system-default route. Retain unrelated voice-message winmm code; do not delete CallAudioIo before dependency review. | Camera-free creation; no capture until connected consent; mute/unmute, device loss, repeated teardown and system route work. |
| R05.2 Both baseline compatibility | Pending | R02, AP01 | Pair replacement behind test-only ICallMedia with actual archived/current 2.2.42 production call path, both directions. Preserve authenticated CALLCONNECT, exact v1 frames and G722. | Actual audio send/receive and human audible acceptance separated; no video SDP or camera permission. Must pass before switching production factory. |
| R05.3 Windows production bridge | Pending | R05.2, R01 production input clearance | Implement production-owned bounded C ABI and managed adapter, not reference prototype project/DLL. Native ownership, exception boundary, queued/cancelled operations, stale handles, copied stats. | Test-only code/generator absent from production binary; failure never unloads callback code while live; disposal/stress pass. |
| R05.4 Both final media/wire contract | Pending | Paired VP8 fallback evidence, R05.2 | A02b selects separate video-only secured PC and VP8; finish exact CALLCAPS, media discriminator, request/generation/identity binding, timeouts/collision/failure fixtures. | Cross-language byte/rejection fixtures; old voice compatibility unchanged; authenticated call and consent bind video PC. |
| R05.5 Windows dependency switch | Pending | R05.3, R05.4 | Pin production SDK/binary/notices, offline MSBuild/publish integration; replace factory and remove SIPSorcery reference only after verified replacement. Audit actual native imports and published graph. | Offline clean-folder restore/build/publish; no restricted RTC package in restored graph/output/notices/runtime. No prototype inclusion; no architecture silently dropped. |
| R05.6 Windows / Both verification | Pending | R05.5 | Production controller/voice regressions, UI responsive calling, archived Android voice directions, mute/route/hangup/Offline, device-loss tests. | Evidence names exact revision/artifacts; no release if acceptance fails. Update platform handoff/comparison, local commit only. |

Android next: A04/AT02, A05–A07d/AT03, A08–A09d/AT04,
A10–A11/AT05, A12/AT06/A13, in dependency order. A signed Android video release
must include production consent, capture/rendering and lifecycle implementation;
the separate feasibility APK is never presented as that release.

Current verified choice: VP8/separate video PC passed generated decoded motion,
G722 and tested isolated failures both directions. Single-PC native video rollback
failed healthy audio; rejected. Detailed evidence in PAIRED-FEASIBILITY.md.
Current source releases remain 2.2.42. No R05 application change yet.
