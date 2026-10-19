# R01 Windows input audit / handoff — 2026-10-03

Execution of R01–R04 was explicitly approved by the user. R01 is In progress;
the inspected prebuilt input is **HOLD**, not selected. R02/R03/R04 and Both A02b
remain Pending. No production library change or prototype media execution.

Latest result: R01 minimal offline test input preparation completed after
accepted policy/review/filtering. Original m150 binary remains HOLD. M155 filtered
input and R02 ABI/link smoke are recorded in R02-HANDOFF.md, including package
hash and exact static-CRT ABI correction. R02 endpoint work still Pending;
no actual media or production migration. Earlier HOLD/next-step text below is
historical audit progression, not the latest packaged-input status.

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

### Accepted policy and completed review continuation

User's "اقبل واكمل" explicitly permits separately audited permissive transitive
licenses, still without geographic-use restrictions. The earlier decision request
below is resolved. No production migration or security waiver was approved.

All 25 delivered binary NOTICE sections have now been read: BSD variants,
Apache-2.0, MIT/NCSA legacy LLVM terms and LLVM exceptions, IJG/zlib, FFT/ooura
permissive grants and G711/G722/sqrt public-domain grants. No geographic-use
restriction was found in those examined texts. Preserve complete NOTICE,
copyrights, patent notices and required IJG acknowledgement when distributing.
This is scoped to the delivered binary notice inventory, not every bundled header.

Recent advisory source dispositions (reviewed 2026-10-03):

| Advisory / issue | Pinned-input disposition and evidence |
|---|---|
| CVE-2026-79187 / 523296105 | Official Chromium fix `5643da4a3d7eca3d0b9b523647c7e14e37f294c4` modifies only Blink media-stream adapter/map files. Its iframe/main-thread disposal defect is browser-layer code, absent from this standalone library. This is an applicability inference from the changed paths and build scope, not a claim about all UAF defects. |
| CVE-2026-87430 / 542449805 | Fix `437408bd428c915b11f44df4f35cf1834acd61c9` is in the pinned core's ancestor log; H264 stride rounding corrected. H264 is also disabled by this input's build recipe. |
| CVE-2026-87579 / 504690157 | Fix `caf9532b632ed80d6c0b73576d1330fb38aec248` is in the pinned core's ancestor log; validates H264 resolution. H264 disabled. |
| CVE-2026-87630 / 502783118 | Fix `424a6bd0b7f93659204ecccff1d61d63c25937e2` is in the pinned core's ancestor log; moves oversized RTP payload guard earlier. |
| CVE-2026-103631 / 567088927 | The pinned core revision itself is the M155 backport of payload-capacity/reduction checks (original `fc6666263eafa63878d02102189e3dabe9c90455`). This is not merely a version-date inference. |

No claim of exhaustive vulnerability freedom. Production needs ongoing component
security review, toolchain/ABI verification and R03/R04 actual-device evidence.
Official release recipe has `rtc_use_h264=false` and Windows
`use_custom_libcxx=false`/`use_custom_libcxx_for_host=false`; no codec substitution.
Native input has no H264/H265 encoder support; VP8 is the first paired candidate.

`tests/video-feasibility/windows/audit-upstream.ps1` independently checks pinned
SHA256, safe/non-aliased paths, version/core pins, all 25 notice component names,
library size/signature and absence of executable DLL/EXE files. Assertions passed:
40,995 entries, static library 369,225,972 bytes. It reports HOLD (exit 2), never
extracts/links/loads native code, and is not part of the production test runner.
The caller must capture native exit status explicitly because PowerShell's
native-command preference can map a nonzero child status to its own exit 1.

Remaining R01 work: prepare a minimal reproducible offline native input with
complete notices and hash manifest under vendor/nuget. ZIP is stored rather than
compressed and carries 372 MB of headers, including unused FFmpeg headers;
binary NOTICE does not clear redistribution of every such header. Do not blindly
vendor/extract/repackage all headers or label them all BSD. Review required header
closure/licenses or use a documented distributor-supported minimal SDK subset.
Only then select input and begin R02 linking. About 3.6 GB free; no user files
deleted. Old m150 wrapper remains HOLD and is not the direct M155 bridge ABI.

Sources: [Blink fix](https://chromium.googlesource.com/chromium/src/+/5643da4a3d7eca3d0b9b523647c7e14e37f294c4),
[pinned RTP fix](https://webrtc.googlesource.com/src/+/f89edcb7be1f4be029ee7186e36b2b35ec03373e),
[September advisory](https://chromereleases.googleblog.com/2026/09/stable-channel-update-for-desktop_0808145027.html),
[October advisory](https://chromereleases.googleblog.com/2026/10/stable-channel-update-for-desktop.html).

### Newer upstream build reviewed after the initial HOLD

Continued safe alternatives rather than stopping at the first binary gap:
official shiguredo-webrtc-build release `m155.8059.2.0` (2026-10-01), Google
libwebrtc core BSD and build project Apache-2.0. This is a candidate input for a
project-owned bridge, not the m150 wrapper ABI and not yet selected.

- Downloaded `webrtc.windows_x86_64.zip`, 751,214,637 bytes, temporary outputs
  `D:\LAN-Messenger\outputs\.build\video-feasibility\upstream-m155-review`.
- SHA256 `3460e4fe9b7ddf01d3071f54f5eca84c528d1f961de7224f9b345b6b466d90fa`
  matches official GitHub release asset digest (equality assertion passed).
- Unlike the earlier input, archive includes `webrtc/VERSIONS` and a 106,383-byte
  `webrtc/NOTICE`. Its pinned Google core revision is
  `f89edcb7be1f4be029ee7186e36b2b35ec03373e`, matching tagged project VERSION;
  build/buildtools/third-party source revisions are also listed in VERSIONS.
- Library `webrtc/lib/webrtc.lib` is 369,225,972 bytes; no large library/header
  extraction or link/load attempted before license gate. This is a static native
  input, not a ready-to-P/Invoke DLL. ABI/build settings still require review.
- NOTICE component headings include WebRTC, abseil, BoringSSL, compiler-rt,
  dav1d, fft, fiat, G711/G722, libaom, libc++, libjpeg_turbo, libsrtp, libvpx,
  libyuv, nasm, ooura, opus, perfetto, pffft, protobuf, rnnoise, sframe,
  spl_sqrt_floor and zlib. This enumeration is not complete license clearance.
- The complete libjpeg_turbo NOTICE section was inspected: it includes **IJG**
  and Modified BSD terms and discusses **zlib** terms. Archive also has a separate
  zlib NOTICE component. IJG/zlib are not literally MIT/Apache-2.0/BSD identifiers.
  No geographic clause was observed in that examined section; no blanket claim
  about all unread component licenses. Need clarify whether user's allowlist
  applies strictly to every transitive component or to main library with separately
  reviewed permissive transitive licenses without geographic restrictions.
  Do not silently treat "BSD-style" as identical to BSD-3-Clause.
- Security patch/applicability review remains Pending even for newer M155; newer
  milestone alone is not proof. No prototype, selected vendor input or production
  code was built from it.
- Disk free after download about 3.6 GB. A full Chromium/WebRTC source checkout
  and rebuild cannot responsibly be assumed to fit; no files were deleted to
  manufacture space. Only small metadata was read directly inside the ZIP.

Required user decision now: license policy scope for transitive components.
If exact three-license allowlist applies to every shipped component, this ready
archive cannot be selected as-is; investigate excluding/replacing out-of-policy
components or another stack, without assuming such a custom build is possible.
If separately audited permissive licenses are allowed, finish their full review,
security disposition and offline manifest before R02. No bypass of either gate.

[Pinned alternative release](https://github.com/shiguredo-webrtc-build/webrtc-build/releases/tag/m155.8059.2.0),
[tagged core version](https://github.com/shiguredo-webrtc-build/webrtc-build/blob/m155.8059.2.0/VERSION).

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

## 2026-10-04 gate outcome: PASS, selected for Section B integration only

Appended later; everything above is the original m150-era record and is left as written.

- `selected` = **true**, for the purpose the gate was blocking: building the Section B native
  bridge and adapter against the pinned M155 static library.
- `securityDispositionComplete` = **true** for the advisories in the reviewed window.
- `sourceRevisionVerified` = **true** for the WebRTC source revision, which is what the
  advisory fixes turn on.
- Previous state was `selected=false`, `securityDispositionComplete=false`.

The gate that produced this state is `tests/video-feasibility/windows/audit-upstream.ps1`,
**not** `audit-input.ps1`. The latter audits the m150 DLL archive and cannot read the M155
candidate at all; it is now marked superseded and its hardcoded verdict was deliberately left
untouched. `audit-upstream.ps1` was rewritten to audit both the official upstream archive and
the vendored package, derive its verdict from evidence rather than assert it, and prove the
package's `webrtc.lib` is byte-identical to the audited archive's. It reports PASS / exit 0
and returns HOLD / exit 2 on four tested failure inputs.

Key correction to the record above: the disposition table's CVE-2026-103631 row was right and
was not acted on in time. That row already recorded that the pinned core revision **is** the
M155 backport of the fix. A later pass re-derived the same CVE as unresolved from a
branch-point date inference, which was wrong. Reading the pinned commit directly from
`webrtc.googlesource.com` confirms it: `[M155] Harden payload capacity and reduction checks in
RTP packetizers`, `Bug: chromium:567088927`, `refs/branch-heads/8059@{34859}`. Full evidence
and the licence-delta answer (zero new components) are in
[WVC-03-ADVISORY-REVIEW.md](WVC-03-ADVISORY-REVIEW.md).

Selection does **not** enable video. Production video stays disabled until the WVC-T03/T04/T05
and WVC-08 automated gates pass, and WVC-12/14/15/16 physical acceptance remains outstanding.
