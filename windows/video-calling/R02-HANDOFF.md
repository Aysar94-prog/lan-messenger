# R02 Windows test-only native bridge handoff — 2026-10-03

Latest continuation: generated VP8 **codec-local** encode/decode now passes in
Windows native EXE (two fresh codec lifetimes) and through the net9 C ABI (one
lifetime). Each run: encoded=20, decoded=20, moving decoded checksums=19,
encoded bytes=898, dropped=0, 160x120 generated I420. No camera, microphone,
PeerConnection, ICE, RTP, DTLS-SRTP or cross-device transport was used. This proves
working codecs/ABI, not R03/R04, WT01 or Both A02b. R02 endpoint work remains
In progress/Pending as broken down below.

The bounded encoded callback retains its buffer through EncodedImage copies;
decoding is drained outside that callback (no codec reentry). Release guards run
before callback destruction on normal/early exits. Decoded Y-plane checksums
validate changing output, not just frame counters. C ABI catches exceptions and
rejects null/short/oversized buffers. Current pinned API requires OnFrameDropped
callback; removed obsolete VP8 frameDroppingOn field rather than version guessing.
Output DLL was rebuilt in the same explicit probe scratch directory; new SHA256
`ef629a6dfb701d421b1c9f554a013aedcb3301c4c896e5b09e098e51d745d8ba` supersedes
the older DLL hash below. Offline input package hash unchanged.

Next: implement opaque native endpoint ownership and real audio/SDP/ICE first,
then wire generated VP8 source/remote sink and upgrade/stats commands. Pair with
the separate Android AP01 APK using USB adb for control and LAN for media.
Do not extrapolate local codec success into an interoperability gate pass.
Current usage 36% five-hour / 13% weekly; not near limit.

End checks for this continuation: rebuilt native EXE and C ABI DLL, rebuilt/reran
net9 probe successfully. Android CallCheck rerun against the unchanged cached
production classes: PASS=408 FAIL=0 (not new APK/device acceptance). Diff whitespace
check passed. Full regression's latest recorded five Windows recording-device
failures remain open; no claim of a new full-suite pass.

R01 minimal offline input preparation completed for test feasibility, not for
production migration/release. R02 **In progress**: native/MSVC/.NET ABI smoke
proof completed; PeerConnection/media/callback ownership remains Pending.
R03/R04, Windows WT01 and Both A02b Pending. No production video implementation.

## Small-task status and acceptance

| Task | Platform / status | Dependency | Acceptance / remaining tests |
|---|---|---|---|
| R02a offline native link | Windows / Complete | R01 | MSVC C++20 links pinned M155 x64 static input and enumerates G722. Passed. |
| R02b managed ABI smoke | Windows / Complete | R02a | net9 x64 loads explicit test DLL, invokes bounded C ABI, rejects null/short/oversized buffer, releases caller allocation and DLL. Passed. |
| R02c endpoint ownership | Windows / Pending | R02b | Native factory/network/worker/signaling threads, opaque handles, queued callbacks with owned buffers, bounded frame copies, safe teardown; no allocations freed by the wrong CRT. Add repeat/stale/dispose tests. |
| R02d generated endpoint | Windows / Pending | R02c | Explicit audio/offer/answer/ICE/video upgrade/stats commands, generated moving I420, VP8 first, no automatic camera capture. Test codec preferences and decoded-motion counters. |
| R03 legacy voice | Both / Pending | R02d | Camera-free v1/G722 pairing against Android endpoint/baseline, counters plus actual audible acceptance separated. |
| R04 video upgrade | Both / Pending | R03 | Actual encoded/decode motion both ways, continuous G722, re-offer/inactive selection, rollback/collision failures; WT01/A02b only after paired evidence. |

## What changed / verified

- `tests/video-feasibility/windows/package-upstream.ps1` creates an offline
  filtered native SDK under vendor/nuget, preserves complete upstream NOTICE,
  VERSIONS, DEPS and static library, excludes unrelated Blink/FFmpeg/LLVM/ML trees.
  Only WebRTC-owned header trees and dependency trees named in the reviewed binary
  NOTICE are retained (abseil, BoringSSL, libyuv, libsrtp, libvpx).
- Package `LanMessenger.TestOnly.WebRtc.Native.155.8059.2.nupkg`, 105,698,751 bytes,
  3,341 upstream files; SHA256
  `0131cad1d573250a1b9423b4e36bdfdc5946a46e43efc2c8d48e6fc03efafb6a`.
  Packaging recipe is content-reproducible from pinned input; newly generated ZIP
  timestamps can change package hash, so any regenerated artifact needs a new
  recorded hash and explicit build pin review. Existing package is never overwritten.
- `build-probe.ps1` pins package hash, extracts only into unique outputs, obtains
  installed VS2022 x64 environment and builds test EXE plus exported C ABI DLL.
  No compiler/toolchain installed and no public package feed enabled.
- ABI discovery: `/MD` originally failed RuntimeLibrary mismatch. Upstream objects
  explicitly require `MT_StaticRelease`; `/MT` matches it and passes without
  suppressing linker checks. MSVC 14.44.35207, C++20, x64, NDEBUG, WEBRTC_WIN.
  `use_custom_libcxx=false` does not imply `/MD`. Keep all future native allocations
  inside the bridge; the managed caller owns probe output storage.
- First environment quoting/PATH attempts failed before successful compiler setup.
  Fixed command quoting and duplicate `PATH`/`Path` launch-environment handling.
  Both output directories preserved; no broad filesystem cleanup.
- Native factory report: `opus/48000,G722/8000,PCMU/8000,PCMA/8000`. G722 RTP clock
  is 8 kHz, distinct from decoded 16 kHz PCM. This is codec enumeration, **not**
  proof of audio packets, audible sound, real device acceptance or interop.
- C ABI catches C++ exceptions; .NET caller's three invalid-buffer checks passed.
  No PeerConnection, LAN traffic, audio/camera device or Android command executed.
- DLL output:
  `D:\LAN-Messenger\outputs\.build\video-feasibility\windows-probe-f76db67782324c3ea8bb7d7df5063854\lm-native-probe.dll`
  SHA256 `a5172f99f78212c063d985f17f92517c00f3d8303de157f06e98c40e009540e1`.

## Reproduction / end checks

Run `tests/video-feasibility/windows/build-probe.ps1` from the canonical checkout.
Its optional ExistingOutput only reuses an explicitly named direct probe scratch
directory; default creates a fresh one. No production project references the
package, probe source or .NET project. Normal tests/run.ps1 was not modified.

Existing Windows Release build passed: 0 errors, existing CS1998 warning.
Full `tests/run.ps1` rerun with TestRoot
`D:\LAN-Messenger\outputs\.build\video-feasibility\replacement-regression`:
Android Offline architecture check passed, C# harness build passed, voice pure
checks PASS=38 FAIL=0 SKIP=30; voice-device checks PASS=11 FAIL=5, all five
failures `Could not open the recording device (mmresult 1)`. Runner exited 1 at
that gate; later tests **not run**. No claim that the full suite passed.
Native EXE and net9 ABI probe passed, expected audit HOLD exit 2 verified,
wrong-input hash rejection verified, diff whitespace check passed.

Production app/signing/release version unchanged. Android prior A01/A02/A03/AP01
evidence unchanged; do not resume A04 before A02b. Resume R02c/R02d, then USB adb
paired R03/R04. Do not remove production SIPSorcery until migration approval and
voice-compatible device proof. Usage 27% five-hour / 12% weekly, not near limit.
