# WVC-03 advisory and provenance review — 2026-10-04

Review of current Chromium/WebRTC advisories against the exact pinned M155 input, as
instructed. **Verdict: STOP.** One material security issue is unresolved, so per the
instruction "if any material issue remains unresolved, stop and report it", no production
package, native bridge or adapter work was started, and production video stays disabled.

This document is evidence only. It is **not** a security clearance and does not select the
candidate. The R01 gate remains HOLD.

## 1. Pinned identity, established from the artifact rather than assumed

Read from `sdk/webrtc/VERSIONS` inside `LanMessenger.TestOnly.WebRtc.Native.155.8059.2.nupkg`
(SHA256 `0131cad1d573250a1b9423b4e36bdfdc5946a46e43efc2c8d48e6fc03efafb6a`, 105,698,751
bytes, 3,343 entries, 369,225,972-byte `sdk/webrtc/lib/webrtc.lib`):

| Field | Value |
|---|---|
| `WEBRTC_BUILD_VERSION` | `155.8059.2.0` |
| `WEBRTC_VERSION` | `155.8059.2` |
| `WEBRTC_READABLE_VERSION` | `M155.8059@{#2}` |
| `WEBRTC_SRC_COMMIT` | `f89edcb7be1f4be029ee7186e36b2b35ec03373e` |
| `WEBRTC_SRC_BUILD_COMMIT` | `e6aa79b579ec7536ac9cbb92b47c1d3c2ecec984` |
| `WEBRTC_SRC_BUILDTOOLS_COMMIT` | `c202b4a9dac30e789ed6e3b2354efa94357a56f3` |
| libcxx / libcxxabi / libunwind | `97b436da…` / `14024f8f…` / `24a407d5…` |
| `WEBRTC_SRC_THIRD_PARTY_COMMIT` | `74ec8c2ee533c9eb78e660876c6fa12d47626176` |
| `WEBRTC_SRC_TOOLS_COMMIT` | `bbae40a5511ca4ea79ab1db39e0ed272295d0dc3` |

`package-upstream.ps1` copies `NOTICE`, `VERSIONS` and `DEPS` verbatim from the upstream
archive, so these are upstream-authored values, not locally authored.

Lineage: milestone **M155**, branch **8059**. For reference, Chrome M155 for iOS published
`155.0.8059.24` on 2026-09-30, while desktop stable at the time of review was M154
(`154.0.8037.97/.98`). The pinned WebRTC build is therefore from the *next* branch relative
to rolled-out desktop stable.

## 2. Method

Advisory data was taken from the Chrome Releases blog "Stable updates" Atom feed
(`max-results=40`), covering 2026-08-04 through 2026-10-02. Every stable-channel desktop
advisory in that window was parsed and filtered for WebRTC/media relevance.

Chromium branch points are approximately 4 weeks ahead of stabilisation, so the M155 branch
point from M154 is **inferred** to be around early September 2026. That inference could not
be verified offline and is the weakest link in the applicability reasoning below.

## 3. Media/RTC-relevant advisories in the window

Ten relevant items. "Fixed in" is the stable desktop build carrying the fix.

| Date fixed | Fixed in (branch) | CVE | Description |
|---|---|---|---|
| 2026-09-15 | 153.0.8010.47/.48 (M153) | CVE-2026-91730 | Incomplete cleanup in GetUserMedia |
| 2026-09-01 | 152.0.7977.75/.76 (M152) | CVE-2026-84347 | Use after free in WebRTC |
| 2026-09-01 | 152.0.7977.75/.76 (M152) | CVE-2026-84348 | Information leak in MediaCapture |
| 2026-08-18 | 151.0.7922.169/.170 (M151) | CVE-2026-76035 | Inappropriate implementation in Media |
| 2026-08-06 | 151.0.7922.108/.109 (M151) | CVE-2026-19171 | Use after free in Media |
| 2026-08-06 | 151.0.7922.108/.109 (M151) | CVE-2026-19164 | Insufficient validation of untrusted input in Codecs |
| 2026-08-06 | 151.0.7922.108/.109 (M151) | CVE-2026-19163 | Use after free in Media |
| 2026-09-29 | 154.0.8037.92/.93 (M154) | CVE-2026-102315 | Uninitialized resource in Media |
| 2026-10-02 | 154.0.8037.97/.98 (M154) | CVE-2026-103623 | Use after free in MediaStream |
| **2026-10-02** | **154.0.8037.97/.98 (M154)** | **CVE-2026-103631** | **Buffer overflow in WebRTC** |

The remaining ~80 CVEs in the window are ANGLE/V8/GPU/Skia/WebGL/Skia/UI/Views/SVG/Dawn
class. Those are browser rendering and UI components that this application does not embed;
`libwebrtc` does not link them. They are recorded as not applicable.

## 4. Applicability

- **M151–M153 items (7 CVEs).** All merged into branches that predate the inferred M155
  branch point, so M155 should contain them. Treated as already fixed in the pin. Not
  independently verified against commit `f89edcb7`.
- **M154 items landed 2026-09-29 (CVE-2026-102315, "Uninitialized resource in Media").**
  Merged to M154 nine days before the inferred M155 branch point, so likely carried into
  M155. Likely fixed; not independently verified.
- **CVE-2026-103631, "Buffer overflow in WebRTC" — NOT RESOLVED.** Reported 2026-09-28,
  shipped in M154 stable on 2026-10-01/02, i.e. **after** the inferred M155 branch point.
  Inclusion in M155 would require a separate cherry-pick, and no offline evidence
  establishes that the cherry-pick landed before build `155.8059.2` was cut. The reward
  status is `[TBD]`, consistent with a very recent finding.

  Applicability to this project is **high**, not speculative: a WebRTC buffer overflow is
  reached through RTP/media frame handling, which is precisely what a calling feature does.

  The advisory itself calls out this exact situation: Google will "retain restrictions if
  the bug exists in a third party library that other projects similarly depend on, but
  haven't yet fixed." Standalone `webrtc-sdk/libwebrtc` releases on its own cadence from
  Chrome stable, so a Chrome stable fix date does **not** establish that any given
  libwebrtc artifact contains it.

## 5. Mitigations

No source-level mitigation is available to us for CVE-2026-103631: we do not build the
library, so we cannot patch it, and we cannot filter untrusted RTP at a layer that avoids
the defect without reimplementing media handling. The only real mitigation is a newer
upstream build that demonstrably postdates the fix.

Operational mitigations available in the meantime: keep production video disabled (already
done — `CallVideoSupport` defaults to false and `VideoEnabled` requires an installed media
backend), and keep the existing authenticated, encrypted, peer-verified signalling, which
limits who can reach a call at all. These reduce exposure; they do not remediate the defect.

## 6. License and provenance evidence

`sdk/webrtc/NOTICE` is present and complete at 106,381 characters, enumerating 24
components: webrtc, abseil-cpp, boringssl, compiler-rt, dav1d, fft, fiat, g711, g722,
libaom, libjpeg_turbo, libsrtp, libvpx, libyuv, nasm, ooura, opus, perfetto, pffft,
protobuf, rnnoise, sframe, spl_sqrt_floor, zlib.

Copyleft and restriction screening was performed against the delivered text:

- `GPL` — 6 hits, **all benign**: four are the standard Apache License 2.0 appendix
  (patent clause and the Section 9 conflict clause, quoted verbatim in Apache-2.0 itself);
  two are a public-domain dedication in the g711/g722 headers ("Despite my general liking
  of the GPL, I place this code in the public domain").
- `MPL` — 88 hits, **all substring false positives** inside `SIMPLY`, `IMPLIED`, `SIMPLE`
  within BSD/ISC "AS IS" warranty text.
- No AGPL, no CC-BY-NC, no Commons Clause, no geographic-use restriction.

So the components are permissively licensed and this addresses the earlier R01 concern that
"delivered static-component notices are not established". Note it does **not** by itself
complete the user's transitive-license allowlist sign-off, which was raised in R01 and never
formally recorded; it establishes the *facts* that sign-off would rest on.

Provenance gap that remains: `sdk/webrtc/DEPS` is a 55-byte stub containing only
`MACOS_DEPLOYMENT_TARGET=14` and `IOS_DEPLOYMENT_TARGET=14.0`. It does **not** pin the
Chromium core revision behind the static library. `VERSIONS` pins WebRTC and its build
tooling commits, which resolves the original "mutable `m150_release`" complaint, but the
Chromium `src` revision of this binary is still not established from the artifact.

## 7. Process defect found: the gate does not audit the candidate

`tests/video-feasibility/windows/audit-input.ps1` cannot audit the M155 input:

- It defaults to `libwebrtc-win-x64-release.zip`, which is the **m150** DLL archive, not the
  M155 static package that R02/R05 actually build against.
- It requires exactly one `lib/libwebrtc.dll` (line 23). The M155 package contains **zero**
  DLLs; it ships `sdk/webrtc/lib/webrtc.lib`. Fed the real candidate it throws rather than
  reporting.
- Its three `reasons` are hardcoded m150-era strings, and `gate`, `selected`,
  `sourceRevisionVerified` and `securityDispositionComplete` are hardcoded constants.

Two runnability defects were fixed in this pass so the script runs at all under Windows
PowerShell 5.1 (missing `System.IO.Compression.FileSystem` load; `.NET 5+`-only
`SHA256.HashData` and `Convert.ToHexString`). It now runs and reproduces the recorded
evidence exactly, including the expected `HOLD` exit code 2:

```
archiveSha256            : 4cd8fce2939b67b034200b124e4559cf343387a047118755925a0551328df84d
publishedChecksumMatches : true
dllSha256                : f3e2a50471e52dd9e0a1c597ba42232086063675621172940d7ce91703ed2273
architecture             : x64
entryCount               : 45
bundledThirdPartyNoticeFiles : []      # m150 archive only; the M155 package has a 106 KB NOTICE
gate                     : HOLD        (exit 2)
```

**The verdict logic was deliberately left untouched.** Editing `gate = 'HOLD'` into a pass
would be self-approval, which the instruction explicitly forbids.

## 8. Verification performed for this review

- `audit-input.ps1` runs under Windows PowerShell 5.1 and reproduces the recorded evidence,
  still exiting 2 `HOLD`.
- `dotnet build windows\LanMessenger.csproj -c Release` — succeeded, 0 errors, only the
  pre-existing `ChatWindowVoice.cs(19,16)` CS1998 warning. This pass changed no application
  code.
- Full `tests/run.ps1` — **exit 0** on the confirming run, 514 lines of output, every
  harness `FAIL=0`, `--call-video-check` 317 passed / 0 failed, shared corpora 107 + 38
  agreeing across both platforms.

**One suite run in this pass did fail, and it is recorded rather than smoothed over.** An
earlier invocation aborted at `tests/group_membership.py` (`run.ps1:85`, exit 1 via
`Check-Result`) *after* that script had printed only PASS lines, with empty captured
stderr. The immediate re-run of the same suite exited 0 with the 16-member cap tests
passing. This matches the historically flaky case already noted on WVC-T03. It is recorded
as **occurred once, did not reproduce** — explicitly **not** as fixed, because a
non-reproducing failure is not a diagnosis. No application code changed between the two
runs, so nothing here implicates this review's changes.

## 9. What would be needed to pass

1. Evidence that WebRTC commit `f89edcb7be1f4be029ee7186e36b2b35ec03373e` contains the
   CVE-2026-103631 fix — or a re-pin to a build demonstrably newer than the fix, with fresh
   archive checksum, notice and DEPS review.
2. An audit gate that actually inspects the static-library candidate, replacing the m150 DLL
   path. Until then the gate cannot speak about the input being shipped.
3. A recorded per-component license allowlist decision over the 24 components in §6.
4. Confirmation of the Chromium core revision for the static library, or an explicit,
   documented acceptance that `VERSIONS` commits are sufficient provenance.