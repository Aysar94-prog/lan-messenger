# W0 feasibility review and handoff — 2026-10-03

Scope: Windows prerequisites for Android's Both A02b gate, approved plan-v007
SHA256 `4ea7877d6643b5c89246b075d7a301d73b38bea28f644303383fa42f7ca0efbf`.
This is evidence/status, not a replacement plan. No production changes or codec
selection were made in this continuation.

## Task status / acceptance

| Task | Status | Dependency / remaining acceptance |
|---|---|---|
| W00 Windows | In progress | PC, camera driver, privacy, USB phone and LAN inventoried; available physical-camera formats still Pending. No camera capture trial performed. |
| W01 Windows | In progress | Exact candidate packages and pinned negotiation source inspected; decision matrix below. Native distribution/RIDs and performance remain unproven. |
| W02 Windows | Blocked on license clarification | Full license differs from short README label; deployment/use coverage or separate permission needs confirmation. No candidate vendored or selected; full security/transitive review and offline restore/publish remain Pending. |
| WT01 Windows testing | Pending | Requires completed W02 and AP01; no paired Windows–Android encoded-video evidence yet. |
| A02b Both | Pending | Requires WT01; Android A04 and production media remain gated. |

## W00 hardware evidence

- PC: Lenovo model `20W6000CIV`, x64, Windows 11 Pro `10.0.26300`.
- Physical USB integrated RGB webcam: device friendly name **Integrated Camera**,
  hardware vendor/product `VID_04F2&PID_B6D0`, interface `MI_00`. Camera driver
  manufacturer Realtek, version `10.0.22000.20261`, reported date 2022-11-07.
  The OS does not expose a more specific commercial webcam model in this inventory;
  do not invent one. Companion **Integrated IR Camera**, interface `MI_02`, driver
  label Realtek DMFT - IR, same version/date, is not RGB acceptance evidence.
- Current user's Windows webcam consent-store value: `Allow`. This is an inventory
  observation, not proof that actual capture succeeds or all desktop privacy gates
  permit it. Formats, capture, contention and privacy-denial trials still Pending.
- Android: Samsung SM-A075F, Android 16, arm64-v8a; USB adb serial `R8YY80A8VLB`.
  USB device listing contains this single device; wireless adb is not used.
- PC Wi-Fi and phone WLAN routes are on the same private `192.168.1.0/24` LAN.
  Other PC adapters are virtual/VPN/link-local; do not select them as test media LAN.
- Prior Windows microphone regression could not open recording device (`mmresult 1`).
  Generated PCM can measure prototype G722 continuity but cannot claim audible
  two-way physical-microphone acceptance. Existing AP01 uses generated video and
  does not establish physical camera or cross-platform acceptance.

## W01 candidate matrix (not a selection)

Baseline remains net9.0-windows WinForms, SIPSorcery 10.0.17, project-owned winmm
audio I/O and G722, framework-dependent packaging. Production csproj sets no RID
or PlatformTarget; this review does not narrow its architectures. Only this x64 PC
has been inventoried; other architecture acceptance remains Pending.

Both downloaded packages identify repository commit
`08c2ed0ad72c423ca06950dee2e842488a219884`, matching the existing SIPSorcery package.

| Candidate | Codec / pipeline | Deployment / limitations |
|---|---|---|
| SIPSorceryMedia.FFmpeg 10.0.17 | FFmpeg video encoder/decoder and camera source; SIPSorcery RTP/DTLS-SRTP; raw decoded frames can feed a separately selected renderer. Keep existing G722/winmm audio instead of replacing it. | NuGet expression LGPL-2.1-only. Exact package depends on FFmpeg.AutoGen 8.1.0, Abstractions 10.0.17 and Logging.Abstractions 10.0.11. Does **not** include native DLLs. Matching FFmpeg 8.1 shared ABI, codec-enabled build, exact DLL list, provenance, notices and per-RID support require independent verification; no machine-wide install or native download was made. |
| SIPSorcery.VP8 10.0.17 | Managed `Vpx.Net.VP8Codec` encode/decode with Abstractions; SIPSorcery RTP/DTLS-SRTP; generated I420 first. Physical capture could use separately reviewed Windows API/adapter; rendering remains separate. | net8/net9/net10 assets, no packaged native codec DLLs. Exact package depends on Abstractions 10.0.17 and Logging.Abstractions 10.0.11, already vendored. Upstream describes decoder as slow and inter-frame encoder as limited; Android interop, unsafe decoder behavior and performance must be measured, not inferred from browser examples. Full bundled license has additional restriction below. |
| Historical SIPSorceryMedia.Encoders native libvpx | VP8 encoder/decoder, separate capture and rendering needed. | Official repository archived 2026-06-28; obsolete/native provenance and ABI risks. Not selected or downloaded. |

Pinned RTCPeerConnection source has stable-state negotiation handling and
`createOffer` enumerates current media streams. `setRemoteDescription` rejects
an incoming offer while a local offer is outstanding, so production collision
serialization is necessary. `createOffer` resets Inactive tracks to their
DefaultStreamStatus: an inactive fallback must explicitly verify those semantics.
These source observations are **not** successful re-offer, Unified Plan, audio
continuity or video evidence. All three plan upgrade options remain provisional.

## W02 license and security findings

The **actual** `SIPSorcery.VP8 10.0.17` package's `LICENSE.md` contains BSD terms
plus an additional geographic-use restriction in section 2 covering use,
modification and distribution inside Israel and the Occupied Territories.
The existing `SIPSorcery 10.0.17` package carries the same restriction. The
README's short BSD description alone does not resolve it. No conclusion about
the user's actual location is inferred from the computer timezone. Need user
confirmation of applicable license coverage / separate permission for intended
use and deployment, or an explicitly approved scope/plan revision to use a
suitable alternative. Do not remove license text, assume an exception, switch
core RTC libraries, or downgrade packages silently.

FFmpeg wrapper's LGPL expression does not resolve the existing SIPSorcery core
license, nor does it establish a particular FFmpeg binary's GPL/LGPL configuration.
Therefore neither candidate is selected, added to vendor/nuget or referenced by
production or a new prototype project. W02's offline restore/publish proof is
not complete.

Security review started, not complete: official SIPSorcery advisories were checked
on 2026-10-03. GHSA-vgjc-mh6q-fwcf affects <=10.0.8 and is fixed in 10.0.9, below
the existing 10.0.17 baseline. Package release notes identify earlier SCTP/ICE
fixes, but those alone are not a complete security clearance. All advisories,
including September TURN/SIP advisories, transitive dependencies and any selected
native codec need version-specific disposition before W02 passes. No claim of
"no CVEs" is made. GitHub advisory REST endpoint returned 404; public official
advisory pages were readable instead.

## Reproducible review evidence / next handoff

Temporary review root:
`D:\LAN-Messenger\outputs\.build\video-feasibility\dependency-review`.
Packages obtained directly from official NuGet flat-container URLs, inspected
without loading codec assemblies or restoring from a public package source:

- `sipsorcery.vp8.10.0.17.nupkg` SHA256
  `1915ede60b468e4f1b9cfb4fdc73bd7ee9ccadcc17406f726830f80ab7b1260e`.
- `sipsorcerymedia.ffmpeg.10.0.17.nupkg` SHA256
  `2e76d75156d4ddec13b7528f6782dde5ed9954289699fba86c8b95acfdc966eb`.
- Extracted nuspec/license/README files and pinned `RTCPeerConnection.cs`,
  `VP8Codec.cs`, `WindowsVideoEndPoint.cs` retained there for review.
- Sources: [FFmpeg wrapper](https://github.com/sipsorcery-org/sipsorcery/tree/master/src/SIPSorceryMedia.FFmpeg),
  [managed VP8 package](https://www.nuget.org/packages/SIPSorcery.VP8),
  [official advisories](https://github.com/sipsorcery-org/sipsorcery/security/advisories),
  [FFmpeg security](https://ffmpeg.org/security.html),
  [archived encoder](https://github.com/sipsorcery-org/SIPSorceryMedia.Encoders).
  Current web README labels were cross-checked against exact downloaded packages.

Next: resolve license scope first; then finish webcam formats, W01 selection,
W02 security/provenance/vendor/offline proof and WT01 paired media. No phase is
falsely marked complete. Normal tests/run.ps1 and production project remain
unchanged. No camera opened, app installed, version changed, branch changed or
push made in this continuation. Usage snapshot: 4% five-hour / 8% weekly, not near
limit; notes saved nonetheless.

## End-of-continuation checks

- Existing Windows Release build (`--no-restore`): passed, 0 errors; existing
  CS1998 warning in ChatWindowVoice.cs. This is not W02 offline prototype proof.
- Android draft contract: 43 passed / 0 failed; A03 permission identity: 18 passed
  / 0 failed. Both remain distinct from OS/UI/device acceptance.
- `git diff --check`: passed. Only status/handoff documentation changed.
- Full production suite was not rerun for these documentation-only changes; the
  previous run remains failed/incomplete (Windows microphone recording failures
  and Java startup timeouts), as documented in Android's handoff. No full-pass
  claim and no release candidate.
