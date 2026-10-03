# Unrestricted RTC replacement research and proposed amendment

Date: 2026-10-03. Scope: Windows / Both interoperability.
User instruction: reject the restricted library and search for an MIT,
Apache-2.0 or BSD alternative without geographic restrictions.

## Decision and execution boundary

Execution update: the user authorized R01–R04, then explicitly accepted
separately audited permissive transitive licenses (including IJG/zlib) on
2026-10-03 with "اقبل واكمل". Geographic restrictions remain excluded.
This does not approve production migration, waive notices/security review,
or select the earlier m150 binary. The better-pinned M155 static input is now
being audited for a project-owned direct native bridge; see R01-AUDIT-HANDOFF.md.
The original recommendation/approval language below is historical research.

SIPSorcery 10.0.17, SIPSorcery.VP8 and SIPSorceryMedia.FFmpeg are **rejected for
the new selection**. A video codec change alone does not remove the restricted
RTC core. Existing source references, vendored historical inputs and releases
have not been deleted or changed; removing them safely requires a verified
replacement, not breaking existing voice calls during research.

Recommended feasibility candidate: Google libwebrtc (BSD-3-Clause) through
webrtc-sdk/libwebrtc's Windows C++ wrapper (MIT), with a small project-owned C ABI
bridge for .NET P/Invoke. This is a research recommendation, **not** a finished
license/security/interop approval or production dependency selection.

The user's instruction amends the earlier SIPSorcery-dependent selection scope.
The original frozen plan-v007 and its manifest/hash remain untouched. The new
implementation tasks below require explicit start authorization; this turn
performs research and prepares records only.

## Official-source comparison

| Candidate | Examined license / restrictions | Fit and disposition |
|---|---|---|
| Google libwebrtc + webrtc-sdk/libwebrtc | Full Google WebRTC license is standard BSD-3-Clause; tagged wrapper license is standard MIT. Neither examined text has a geographic-use restriction. SDK identifies Apache-2.0 patches. Exact included third-party components still require audit. | Preferred feasibility candidate. Native Windows binaries exist; real audio/video stack, transceivers and stats APIs. C++ API is not a drop-in C# NuGet replacement; requires a C ABI bridge and controlled native ownership. |
| Microsoft MixedReality-WebRTC 2.0.2 | Examined wrapper license is MIT without geographic terms. Includes third-party notices; root license alone does not clear all bundled components. | C# API and native Windows DLL are convenient, but Microsoft explicitly deprecated it and archived the repo on 2022-03-22. Not recommended as a new production foundation without taking on substantial core updates/security maintenance. |
| libdatachannel | Official license is MPL-2.0. | Excluded: does not match the user's MIT/Apache/BSD allowlist, even though it supports Windows media transport. Do not silently relax the requested license policy. |
| Historical WebRtc.NET URL | URL inspected redirects to an unrelated trading-bot repository. | Excluded due to repository provenance drift; no package or code downloaded/executed. |

## Exact promising Windows release

Official GitHub release metadata inspected via API:

- `webrtc-sdk/libwebrtc`, tag `libwebrtc.m150.7871.03`, published 2026-09-17.
- Tag points to commit `070aa6d763c16027ba53c0965107658c837a4dae`.
- `libwebrtc-win-x64-release.zip`: 8,812,157 bytes; matching `.shasum` asset.
- `libwebrtc-win-arm64-release.zip`: 7,628,773 bytes; matching `.shasum` asset.
- The inspected release has **no x86 Windows asset**. README's general platform
  list is not proof of an x86 binary for this version. No retained architecture
  may be silently dropped; any required x86 target needs a reproducible source
  build or explicit user-approved architecture decision.
- Windows binary archives were not downloaded, restored, loaded or executed.
  Hash verification, actual DLL/headers/import-lib contents, build provenance,
  runtime imports and third-party notices are therefore Pending.
- README examples still refer to m144 while release is m150: pin exact tagged
  headers/build recipe, not an unversioned README command or guessed ABI.

Tagged `include/rtc_peerconnection.h` exposes CreateOffer/CreateAnswer,
SetLocalDescription/SetRemoteDescription, AddTrack/AddTransceiver, signaling
state, OnRenegotiationNeeded, OnTrack and GetStats. These demonstrate an API seam,
not successful negotiation or encoded media. G722 availability in the actual
binary, codec preferences, camera consent and audio-only SDP must be verified.
Do not assume common WebRTC ancestry makes old/new call compatibility automatic.

## Small-task amendment proposal

| ID / platform | Status | Dependencies | Work / notes | Acceptance and testing |
|---|---|---|---|---|
| R00 Windows research | Complete | User rejection/search instruction | Review official licenses and current release metadata; reject restricted candidates. | Evidence and limitations recorded here; no application changes. |
| R01 Windows dependency audit | Pending | Explicit execution start; R00 | Acquire exact tag/archive into review outputs, compare published checksum and compute SHA256; inventory all linked/runtime deps, complete license texts, notices, patent notices and current security advisories. Enforce requested license policy on delivered inputs; flag any other license rather than assuming consent. | No geographic clauses; no unresolved license/security/provenance issue; exact manifest and reproducible offline native inputs under vendor/nuget. W02 does not pass on the root MIT/BSD label alone. |
| R02 Windows prototype bridge | Pending | R01 | Build a separate test-only native bridge + net9 test driver under tests/video-feasibility/windows; no production references. Export a bounded C ABI, owned handles, callback disposal and copied frames; first use generated video/audio. | Offline build/publish, clean-folder native load, repeated disposal and frame ownership tests pass; normal tests/run.ps1 does not discover/build prototype. Outputs only under outputs/.build/video-feasibility. |
| R03 Both legacy voice feasibility | Pending | R02; AP01 | Force/verify negotiated G722, camera-free audio setup, actual two-way packet/decode/audio continuity and hangup/Offline. Pair with existing Android and approved baseline voice builds before replacing production adapter. USB adb for control; actual media over LAN. | Preserve v1 framing/CALLCONNECT and codec behavior; real device acceptance distinct from generated counters. Failure prevents production replacement. |
| R04 Both video feasibility | Pending | R03 | Pair new Windows prototype with AP01, generated motion both directions, candidate codec tests, connected reoffer and fallbacks, collision/failure rollback. | Decoded moving video and continuing two-way G722 through mid-call upgrade. SDP alone fails. Supplies WT01/A02b; contract remains provisional until gate passes. |
| R05 Windows production migration | Pending | R03, R04, A02b; explicit implementation approval | Replace SIPSorcery-backed WebRtcCallMedia behind existing ICallMedia seam; remove its package/reference only after replacement tests. Decide explicitly whether to retain project winmm custom audio through bridge or use native audio devices; preserve mute/route/hangup semantics either way. | No SIPSorcery dependency in restored graph, clean production output, notices or runtime imports; legacy voice and UI responsiveness tests pass, package architecture gates respected. Existing controllers/protocol and unrelated features unchanged except approved video tasks. |
| R06 Windows / Both regression | Pending | R05 | Offline restore/publish, production build/full tests, physical audio/camera and old/new interoperability; update status and handoff. Resume original A04+ / W03+ tasks using confirmed adapter. | Separate automated, prototype and physical results; working microphone needed to close known hardware regression; no release before original device gates. |

This proposal substitutes the Windows RTC feasibility dependency and adds a
legacy-voice replacement gate. It does not waive A02b, consent, G722 compatibility,
offline packaging, real-camera acceptance, Android signing continuity or any
unrelated scope boundary. No code migration/build/release has begun.

## Sources and handoff

- [Google WebRTC full license](https://webrtc.googlesource.com/src/+/refs/heads/main/LICENSE)
- [SDK core license](https://raw.githubusercontent.com/webrtc-sdk/webrtc/m150_release/LICENSE)
- [SDK project and patch licensing](https://github.com/webrtc-sdk/webrtc)
- [Exact wrapper MIT license](https://raw.githubusercontent.com/webrtc-sdk/libwebrtc/libwebrtc.m150.7871.03/LICENSE)
- [Exact Windows release](https://github.com/webrtc-sdk/libwebrtc/releases/tag/libwebrtc.m150.7871.03)
- [Tagged peer API](https://raw.githubusercontent.com/webrtc-sdk/libwebrtc/libwebrtc.m150.7871.03/include/rtc_peerconnection.h)
- [Microsoft deprecation statement](https://github.com/microsoft/MixedReality-WebRTC)
- [Microsoft MIT license](https://raw.githubusercontent.com/microsoft/MixedReality-WebRTC/master/LICENSE)
- [libdatachannel license/platform statement](https://libdatachannel.org/)

Next handoff: obtain start approval for R01–R04 feasibility first. Do not treat
this search as automatic authorization to replace production audio libraries.
Current USB device, hardware inventory and AP01 evidence are preserved. Existing
full-suite failures remain open; no test claim arises from documentation research.
