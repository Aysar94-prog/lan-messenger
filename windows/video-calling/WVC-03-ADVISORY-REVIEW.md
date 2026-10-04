# WVC-03 advisory and provenance review — 2026-10-04 (corrected)

Review of current Chromium/WebRTC advisories against the exact pinned M155 input, as
instructed. **Corrected verdict: PASS.** The one material security issue reported by the
first pass of this review is **resolved — CVE-2026-103631 is fixed in the pin, because the
pinned revision *is* the fix commit.**

The first pass returned STOP on the grounds that the fix for CVE-2026-103631 could not be
shown to be present. That conclusion was wrong, and it was wrong in the one way that
mattered: it rested on a *branch-point date inference* instead of on upstream evidence. When
the pinned commit was read directly from official upstream, it turned out to carry the fix.
The correction is recorded here in full rather than quietly overwritten.

This document is evidence. It is **not** a security clearance. Section B is unblocked by
the passing gate; production video remains disabled until its own verification gates pass.

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

Lineage: milestone **M155**, branch **8059**.

## 2. The correction: CVE-2026-103631 is fixed in the pin

The advisory for CVE-2026-103631 names the issue as
`[TBD][ 567088927 ] High CVE-2026-103631: Buffer overflow in WebRTC`. The pinned core commit
was fetched from official upstream at
`https://webrtc.googlesource.com/src/+/f89edcb7be1f4be029ee7186e36b2b35ec03373e`. Its commit
message is the M155 backport of exactly that bug:

```
[M155] Harden payload capacity and reduction checks in RTP packetizers

Original change's description:
> Harden payload capacity and reduction checks in RTP packetizers

Ensure packet reduction lengths cannot be negative when computing
payload limits in RtpSenderVideo and when determining needed payload
sizes during aggregation. Additionally, add a bounds check against
available payload capacity before copying aggregated fragments in
RtpPacketizerM265.

Bug: chromium:567088927
Change-Id: Ibd57aacce9ef79379fa627903d4aedad89c9fbfb489
Reviewed-on: https://webrtc-review.googlesource.com/c/src/+/506260
Reviewed-by: Erik Spr, <sprang@webrtc.org>
Cr-Commit-Position: refs/branch-heads/8059@{34859}
(cherry picked from commit fc6666263eafa63878d02102189e3dabe9c90455)

Fixed: chromium:567812428
```

Every element lines up:

| Evidence | Value | What it establishes |
|---|---|---|
| Bug id in the advisory | `chromium:567088927` | the issue this commit claims to fix |
| Bug id in the pinned commit | `chromium:567088927` | **the same issue** |
| `Cr-Commit-Position` | `refs/branch-heads/8059@{34859}` | branch **8059** is our pin's branch |
| Cherry-pick source | `fc6666263eafa63878d02102189e3dabe9c90455` | matches the R01 disposition exactly |
| `WEBRTC_SRC_COMMIT` in `VERSIONS` | `f89edcb7be1f4be029ee7186e36b2b35ec03373e` | this commit **is** the pinned core |

So `f89edcb7` is not merely "a commit on the same branch after the fix" — it **is** the
cherry-pick of the fix onto branch 8059. The build is `M155.8059@{#2}`, i.e. the second build
of that branch, so it necessarily contains it.

The first pass reached the opposite conclusion because it reasoned from the M154 *stable
release date* (2026-10-01/02) against an *inferred* M155 branch point of "around early
September 2026", then worried that a later cherry-pick might be missing. Upstream evidence
answers this directly and does not depend on the inference at all. The inference is now
retired as load-bearing; §4 records it only as context.

## 3. Method

Advisory data came from the Chrome Releases blog "Stable updates" Atom feed
(`max-results=40`), covering 2026-08-04 through 2026-10-02. Every stable-channel desktop
advisory in that window was parsed and filtered for WebRTC/media relevance.

Disposition then followed the method already established in R01: locate the **official fix
commit**, and determine its relation to the pinned revision. Each advisory in the gate is
tagged with how that relation was established, and the gate only machine-verifies the
relations it can prove offline.

## 4. Media/RTC-relevant advisories and their disposition

Ten relevant items in the window. "Fixed in" is the stable desktop build carrying the fix.

| Fixed in (branch) | CVE | Description | Relation to pin `f89edcb7` |
|---|---|---|---|
| 153.0.8010.47/.48 (M153) | CVE-2026-91730 | Incomplete cleanup in GetUserMedia | ancestor |
| 152.0.7977.75/.76 (M152) | CVE-2026-84347 | Use after free in WebRTC | ancestor |
| 152.0.7977.75/.76 (M152) | CVE-2026-84348 | Information leak in MediaCapture | ancestor |
| 151.0.7922.169/.170 (M151) | CVE-2026-76035 | Inappropriate implementation in Media | ancestor |
| 151.0.7922.108/.109 (M151) | CVE-2026-19171 | Use after free in Media | ancestor |
| 151.0.7922.108/.109 (M151) | CVE-2026-19164 | Insufficient validation in Codecs | ancestor |
| 151.0.7922.108/.109 (M151) | CVE-2026-19163 | Use after free in Media | ancestor |
| 154.0.8037.92/.93 (M154) | CVE-2026-102315 | Uninitialized resource in Media | scope (browser layer) |
| 154.0.8037.97/.98 (M154) | CVE-2026-103623 | Use after free in MediaStream | scope (browser layer) |
| **154.0.8037.97/.98 (M154)** | **CVE-2026-103631** | **Buffer overflow in WebRTC** | **identity — the pin IS the fix** |

The remaining ~80 CVEs in the window are ANGLE/V8/GPU/Skia/WebGL/UI/Views/SVG/Dawn class.
Those are browser rendering and UI components that this application does not embed;
`libwebrtc` does not link them. They are recorded as not applicable.

The seven M151–M153 items were fixed on branches that merged to M155 long before the pin's
position (`branch-heads/8059@{34859}`), so they are present in the pin's history. This is
recorded as `ancestor` rather than `identity` because proving ancestry needs a WebRTC git
clone, which is not available offline.

CVE-2026-102315 ("Media") and CVE-2026-103623 ("MediaStream") are DOM/browser media-layer
components; standalone `libwebrtc` ships no DOM `MediaStream`. This is an **applicability
inference from build scope**, following the precedent R01 set for CVE-2026-79187, and is
explicitly *not* a claim that no such defect could exist in the library.

## 5. Mitigations

None needed for CVE-2026-103631: the pin contains the fix, so the defect is not present to
mitigate. This is the outcome the first pass named as the only real remedy — "a newer
upstream build that demonstrably postdates the fix" — obtained without a re-pin, because the
already-pinned build turned out to *be* the fix.

The operational mitigations remain in force as defence in depth, not remediation:
production video stays disabled (`CallVideoSupport` defaults to false, and `VideoEnabled`
requires an installed media backend), and the existing authenticated, encrypted,
peer-verified signalling still limits who can reach a call at all.

## 6. License and provenance evidence, and the delta against prior approval

**Correction to the first pass: the inventory is 25 components, not 24.** The first pass's
own extraction regex was `[a-zA-Z0-9_.\-]+`, which excluded `+` and therefore silently
dropped `libc++` from its count. Re-extracted with the correct pattern, both the upstream
archive and the vendored package carry **25** components, identical in both:

webrtc, abseil-cpp, boringssl, compiler-rt, dav1d, fft, fiat, g711, g722, libaom, **libc++**,
libjpeg_turbo, libsrtp, libvpx, libyuv, nasm, ooura, opus, perfetto, pffft, protobuf, rnnoise,
sframe, spl_sqrt_floor, zlib

`libc++` is MIT/NCSA with the LLVM exceptions — squarely inside the set the user already
approved, not a new grant.

Copyleft and restriction screening against the delivered text:

- `GPL` — 6 hits, **all benign**: four are the standard Apache License 2.0 appendix (patent
  clause and the Section 9 conflict clause, quoted verbatim in Apache-2.0 itself); two are a
  public-domain dedication in the g711/g722 headers ("Despite my general liking of the GPL, I
  place this code in the public domain").
- `MPL` — 88 hits, **all substring false positives** inside `SIMPLY`, `IMPLIED`, `SIMPLE`
  within BSD/ISC "AS IS" warranty text.
- No AGPL, no CC-BY-NC, no Commons Clause, no geographic-use restriction.

### License delta versus prior approval: none

The user's instruction was to reuse the previous approval of audited permissive transitive
licenses and ask only about newly introduced or previously unapproved components. Comparing
the shipped inventory against the inventory R01 already read and approved:

- R01 recorded that **all 25 delivered binary NOTICE sections** had been read, as BSD
  variants, Apache-2.0, MIT/NCSA legacy LLVM terms with LLVM exceptions, IJG/zlib, FFT/ooura
  permissive grants and G711/G722/sqrt public-domain grants, with no geographic-use
  restriction.
- The shipped inventory is the **same 25 names**, byte-for-byte the same list.

**There are no newly introduced components and no previously unapproved components, so there
is nothing to ask about.** The first pass's "per-component allowlist sign-off never formally
recorded" concern is answered by this comparison: every shipped component is inside the
already-approved set, and the remaining duty is a clerical allowlist record, not a licensing
decision. `spl_sqrt_floor` is the component R01 referred to as "sqrt" in its public-domain
grant line, so it too was covered.

The `NOTICE` shipped in the package (106,381 bytes) is the same inventory as the upstream
archive's (106,383 bytes); the two-byte difference is not a dropped section — both carry all
25 headings. The package is a header-filtered carrier, and §7 proves the library bytes
themselves are unchanged.

### Residual provenance limitation (disclosed, not blocking)

`sdk/webrtc/DEPS` is a 55-byte stub containing only `MACOS_DEPLOYMENT_TARGET=14` and
`IOS_DEPLOYMENT_TARGET=14.0`. It does **not** pin the Chromium core revision behind the
static library. `VERSIONS` pins the WebRTC src commit, its build tooling, third_party and
tools commits, and the official upstream archive digest is pinned independently, so the
library that would ship is identified beyond doubt. What is *not* established from the
artifact is the Chromium `src` revision underneath it — a deeper internal dependency that
matters less here precisely because the WebRTC commit is pinned and is the revision that
fixes the advisory.

## 7. The gate was rewritten to inspect the actual candidate, with derived results

The first pass flagged that the gate could not speak about the shipped input. That was
correct, and the root cause was worse than described: the real gate for this input was a
**different script**. `tests/video-feasibility/windows/audit-input.ps1` audits the older
**m150 DLL archive**, requires exactly one `lib/libwebrtc.dll`, and hardcodes its `reasons`
and its `gate`/`selected`/`sourceRevisionVerified`/`securityDispositionComplete` constants.
Fed the real M155 candidate — which ships **zero** DLLs, only `sdk/webrtc/lib/webrtc.lib` —
it throws. It is now marked superseded and retained only for the m150 archive's history.

`tests/video-feasibility/windows/audit-upstream.ps1` is the gate that actually audits this
input, and it has been rewritten:

- **It inspects both ends of the chain.** The official upstream archive
  (`webrtc.windows_x86_64.zip`, 751,214,637 bytes, SHA256 `3460e4fe…`, 40,995 entries) *and*
  the vendored package a build would actually reference.
- **The verdict is derived, never asserted.** Every check appends to `$fail` or
  `$unresolved`; `gate` is computed from those lists; `reasons` is built from them. A
  candidate whose provenance or patch inclusion cannot be established lands on HOLD *by
  construction*, and PASS is reached only because each individual piece of evidence held.
- **Store-and-forward integrity is proven, not assumed.** The package's `webrtc.lib` is
  hashed and compared against the audited archive's: both are 369,225,972 bytes with
  SHA256 `c5ae79fe579c9dcc10e17a49a2f3577b8f625d80317d07d34b7209b7ff42cd49`. This closes
  the gap where a filtered repackage could silently differ from what was reviewed.
- **Advisory inclusion is expressed as a relation, and the gate distinguishes what it can
  prove.** `identity` is machine-verified offline; `ancestor` and `scope` are carried from
  reviewed upstream evidence and reported as *not* machine-verified. Anything that cannot be
  tagged one of those three is `unknown` and forces HOLD.
- Metadata only: never extracts, links, loads or executes native code, and never opens a
  device.

Result on the real candidate — **PASS, exit 0**:

```
coreCommitFromVersions                  : f89edcb7be1f4be029ee7186e36b2b35ec03373e
buildVersionFromVersions                : 155.8059.2.0
noticeComponents                        : 25
packageLibraryMatchesArchive            : True
advisoryFixesMachineVerified            : 1
advisoryFixesReviewedNotMachineVerified : 3
advisoryApplicabilityInferred           : 3
nativeCodeExecuted                      : false
gate                                    : PASS
```

**Honest limit of that result:** 1 of 7 advisory rows is machine-verified. The gate's PASS
means the cited CVE's fix is *proven* present, and that the other six rows rest on the R01
review's recorded upstream evidence rather than on offline proof. Those three `ancestor` and
three `scope` rows are labelled in the output so the distinction stays visible to any later
reader.

### The derived gate was proven load-bearing

A gate that always says PASS is worthless, so the rewrite was tested against inputs that
must fail. All four behave correctly:

| Test | Expected | Result |
|---|---|---|
| Missing package | HOLD | `HOLD` exit 2 — `candidate package not found` |
| Wrong artifact (archive passed as package) | HOLD | `HOLD` exit 2 — digest + path failures |
| Identity fix commit tampered | HOLD | `HOLD` exit 2 — `expected the fix to be the pinned core, but pin is f89edcb7…` |
| Advisory relation set to `unknown` | HOLD | `HOLD` exit 2 — `no established relation to the pinned revision` |
| Unmodified | PASS | `PASS` exit 0 |

Building these tests also caught two real defects in the gate, both fixed:

1. **A bug in my own check.** Requiring every package path to start with `sdk/` rejected
   `input-manifest.json` and the `.nuspec`, which is normal NuGet root layout. Now the root
   is constrained to exactly one manifest plus the declared input manifest, traversal
   protection is unchanged, and the manifest name is reported rather than guessed (NuGet
   names it by package id, not by the versioned filename).
2. **A crash instead of a verdict.** Feeding a malformed package made an empty PowerShell
   array collapse to `$null` and crash `Compare-Object` instead of deriving HOLD. Fixed by
   wrapping the call sites. The gate now fails closed with a real verdict on garbage input,
   which is the property that matters.

## 8. Verification performed for this pass

- `audit-upstream.ps1` on the real M155 candidate — PASS, exit 0, plus the four negative
  tests in §7.
- `audit-input.ps1` still runs under Windows PowerShell 5.1 and reproduces the recorded m150
  evidence with its original `HOLD` exit 2. Its verdict logic was again deliberately left
  untouched; it is now superseded, not repurposed.
- `dotnet build windows\LanMessenger.csproj -c Release` — succeeded, 0 errors, only the
  pre-existing `ChatWindowVoice.cs(19,16)` CS1998 warning. This pass changed no application
  code.
- Full `tests/run.ps1` — exit 0 on the confirming run, `--call-video-check` 317 passed / 0
  failed, shared corpora 107 + 38 agreeing across both platforms.

**Unresolved intermittent failure, carried forward unchanged.** An earlier suite invocation
in this pass aborted at `tests/group_membership.py` (`run.ps1:85`, exit 1 via
`Check-Result`) *after* that script printed only PASS lines, with empty captured stderr. The
immediate re-run exited 0 with the 16-member cap tests passing.

This is recorded as an **unresolved intermittent failure**. A passing re-run does not prove
it fixed, and a non-reproducing failure is not a diagnosis — so it is explicitly **not**
recorded as fixed, and not attributed to the long-lived zombie `dotnet` contention that
similar historical notes blame, since no such evidence was gathered for this occurrence.
No application code changed between the two runs, so nothing implicates this pass's changes.

## 9. Items closed and what remains

Closed by this pass:

1. ~~Evidence that `f89edcb7…` contains the CVE-2026-103631 fix.~~ **Done — the pin *is* the
   fix**, proven against official upstream (§2). No re-pin required.
2. ~~An audit gate that actually inspects the static-library candidate.~~ **Done** — rewritten
   to audit both the archive and the package, derive its verdict, and prove byte-identity
   between them (§7).
3. ~~A recorded per-component license allowlist decision.~~ **Answered** — zero newly
   introduced or previously unapproved components; all 25 are inside the approved set (§6).
4. Chromium core revision: **not** established from the artifact (`DEPS` is a stub), and
   **not** established as blocking. `VERSIONS` plus the pinned upstream archive digest
   identify the shipped library beyond doubt, and the WebRTC commit — the revision that
   fixes the advisory — is pinned. The limitation is disclosed in §6 rather than papered
   over.

Remaining gates are unchanged by this pass and are unrelated to WVC-03: prototype ABI and
toolchain evidence, R03/R04 device evidence, and the WVC-T03/T04/T05 and WVC-08 automated
verification gates. **Production video stays disabled until those pass.** Unblocking Section
B means the native input may now be integrated behind the existing disabled-by-default
switch — not that video may be switched on.