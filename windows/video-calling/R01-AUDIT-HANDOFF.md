# R01 Windows input audit / handoff — 2026-10-03

Execution of R01–R04 was explicitly approved by the user. R01 is In progress;
the inspected prebuilt input is **HOLD**, not selected. R02/R03/R04 and Both A02b
remain Pending. No production library change or prototype media execution.

## Verified input evidence

Official release `webrtc-sdk/libwebrtc`, `libwebrtc.m150.7871.03`, wrapper commit
`070aa6d763c16027ba53c0965107658c837a4dae`.

- Windows x64 ZIP downloaded to
  `D:\LAN-Messenger\outputs\.build\video-feasibility\libwebrtc-review`.
- ZIP SHA256 `4cd8fce2939b67b034200b124e4559cf343387a047118755925a0551328df84d`
  **matches the official release's .shasum**.
- DLL SHA256 `f3e2a50471e52dd9e0a1c597ba42232086063675621172940d7ce91703ed2273`.
- PE machine x64 (0x8664); one DLL plus import library, headers and wrapper MIT
  license. Root archive has no core BSD license, wrapper NOTICE, core NOTICE or
  generated static third-party notices. Source notices were inspected separately,
  not assumed to cover an unknown binary revision.
- PE imports are Windows OS DLLs: KERNEL32, ole32, MFPlat, OLEAUT32, ADVAPI32,
  WS2_32, USER32, dwmapi, GDI32, dxgi, d3d11, WINMM,
  api-ms-win-shcore-scaling-l1-1-1, msdmo, CRYPT32 and IPHLPAPI.
  This does not list or clear statically linked third-party components.
- Visual Studio Build Tools 2022 / MSVC 14.44 are installed. No toolchain installed.
- USB adb still lists only SM-A075F serial R8YY80A8VLB; no camera opened or app
  replaced. Android AP01 and all prior Android evidence remain preserved.

## Audit findings / selection boundary

1. Tagged workflow's push path passes an empty core commit input; tagged .gclient
   uses mutable `m150_release`. The build script supports an explicit core commit,
   but the public release does not identify one. Wrapper tag/hash and ZIP checksum
   alone cannot establish the exact static core/codec/crypto source revisions.
2. Tagged build script copies only the wrapper LICENSE and headers into the ZIP.
   Source wrapper NOTICE adds Apache-2.0 attribution; core BSD and patch notices
   are separate. Need a component manifest and complete notices for actual build.
3. Google vendor advisories include CVE-2026-79187 (WebRTC use-after-free) and
   CVE-2026-87430 (WebRTC overflow). These are investigation leads, **not proof
   this standalone DLL is affected**. Release date and Chrome version alone do
   not establish fixes/applicability in this binary. Security disposition Pending.
4. Core source branch currently points at
   `0385653a83f21acf3c916466d4088b29fe2f160b` (2026-10-01). Its current DEPS lists
   BoringSSL `f91f1447397c6719f9774dfb8e67329378e1f3d3`; that revision's full LICENSE
   was examined and uses Apache-2.0, with BSD Go-test attribution explicitly
   described as not included in compiled libcrypto/libssl. **Do not confuse
   current source dependencies with verified prebuilt archive dependencies.**
   Other static codecs/crypto/dependencies still require delivered-build review.
5. GitHub official release workflow run 35187460293 succeeded, but its log download
   API returned HTTP403 (repository-admin rights required). No attempt to bypass
   access control. Core README references LICENSE_THIRD_PARTY, but that file is
   absent from the current core root (raw retrieval HTTP404); it is not clearance.

## Reproducible verification

`tests/video-feasibility/windows/audit-input.ps1` is an R01 read-only archive
checker, **not the R02 media prototype**. It validates both pinned/published ZIP
hashes, entry traversal, DLL count, PE bounds/signature and x64 machine, computes
DLL hash and emits a HOLD report. Expected exit 2 means candidate selection is
not approved. It does not load DLLs, use a public restore source, modify production,
open devices or join normal tests/run.ps1. Outputs stay in the review root.
Executed as a separate pwsh process: checksum/PE assertions passed, 45 archive
entries, no bundled third-party notice file, expected HOLD exit 2 verified.

## Next steps / authority

Do not vendor/select or execute this DLL as a passed R01 dependency. Two in-scope
ways to resolve the gap: obtain authoritative exact core/dependency revision and
security/notice evidence for the archive; or create a reproducible local source
build with explicitly pinned wrapper/core/DEPS and complete license/security audit.
The latter still must disposition the current security advisories and audit all
static licenses before passing R01. Do not silently downgrade core, relax license
allowlist, waive security, or replace production voice. Any required external
maintainer contact needs explicit user authorization; no contact was sent.

Usage snapshot: 13% five-hour / 10% weekly; not near limit. Notes saved regardless.
Full production suite is unchanged and its prior microphone/startup failures
remain open. No new production build/release is justified by this read-only audit.

Sources:
[exact release](https://github.com/webrtc-sdk/libwebrtc/releases/tag/libwebrtc.m150.7871.03),
[tagged build recipe](https://github.com/webrtc-sdk/libwebrtc/blob/libwebrtc.m150.7871.03/build/libwebrtc_win_build.cmd),
[Google August advisory](https://chromereleases.googleblog.com/2026/08/stable-channel-update-for-desktop_0256176589.html),
[Google September advisory](https://chromereleases.googleblog.com/2026/09/stable-channel-update-for-desktop_0808145027.html).
