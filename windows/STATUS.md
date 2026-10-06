# Windows status

2026-10-06 trusted/regular prompt bugfix (source only, not packaged yet): mirrors the same-day
Android fix. `StartCallToSelected()` checked `engine.TrustedCallMask(peerId)` (grants this device
gave to others, the wrong direction) instead of the grant the OTHER device reports holding over
this one. Fixed via a new `CheckTrustedGrantThenCall(peerId)` that runs
`engine.RefreshRemoteCallGrant(peerId)` + `RemoteCallGrantStatus(peerId)` on a background task
before deciding whether to show the prompt (`ConfirmTrustedCall`, extracted into its own method).
`dotnet build -c Release`: 0 errors. Not packaged or device-tested.

2026-10-06 user-requested test-candidate package 3.0.3, packaging the per-call trusted/regular
choice below: `<Version>` bumped to 3.0.3, published framework-dependent via `dotnet publish -c
Release`. 0 errors, one pre-existing unrelated warning. Zipped as
`outputs/LanMessenger-3.0.3-Windows.zip` (7,583,569 bytes; manifest
`outputs/SHA256SUMS-Windows-3.0.3.txt`). Not device-tested; needs a real paired call against the
matching Android 3.0.3 build before acceptance.

2026-10-06 per-call trusted/regular choice (source only, not packaged yet): mirrors the same-day
Android change. `StartCallToSelected()` now checks `engine.TrustedCallMask(peerId)` and, only
when non-zero, shows a small custom dialog ("Call as trusted", pre-selected default button vs
"Call normally") before placing the call. `CallSession.cs` gained `RegularCall`;
`CallController.cs`'s `StartCall` overload sends `invite.B["trust"]="ignore"` on the wire only for
"Call normally" (the common case is byte-identical to before); `OnInvite` reads the same key and
zeroes the trusted mask for that call; `SetRemoteCamera`/`SetRemoteSpeaker`'s existing re-check
guards now also refuse when `session.RegularCall`, alongside `ChatWindowCalls.cs`'s
`RecipientControlMask`/`RefreshRecipientGrant`. `dotnet build -c Release`: 0 errors, one
pre-existing unrelated warning. Not published/packaged/device-tested; needs a real paired call
against the matching Android change (see android/STATUS.md) before acceptance.

2026-10-06 user-requested test-candidate package 3.0.1, packaging the UI pass below
(selection-gated buttons, navy/blue restyle): `<Version>` bumped to 3.0.1, published
framework-dependent via `dotnet publish -c Release` (requires .NET Desktop Runtime 9, same shape
as every prior Windows release — not the one-off self-contained portable bundling 3.0.0 used).
0 errors, one pre-existing unrelated warning. Zipped as
`outputs/LanMessenger-3.0.1-Windows.zip` (7,582,902 bytes; manifest
`outputs/SHA256SUMS-Windows-3.0.1.txt`). Not run on this machine beyond the build itself; no
device acceptance claim.

2026-10-06 UI pass (source only, not a release, not re-packaged): (1) conversation action
buttons (`verify`, `members`, `leaveGroup`, `clear`, `callButton`, `fastTransfer`, `attach`,
`recordVoice`) are now hidden (`.Visible`), not just disabled, until a conversation is selected
— gated in `ChatWindowRender.cs`'s single `RenderCore()` alongside the existing `.Enabled`
assignments. (2) Classic/elegant restyle to match the same-pass Android restyle: `Program.cs`'s
`Accent`/`HeaderDark`/`Ink`/`BubbleMine`/`SeenBlue`/`PanelBg` constants and the matching teal
`Color.FromArgb(...)` literals in `CallView.cs` moved from the WhatsApp-green palette to a
navy-header/blue-accent palette; a new `StyleButton(Button,bool primary=false)` helper (reusing
the existing `RoundCorners` region-clip trick) gives the toolbar/action-row/Send/Attach buttons a
flat, pill-shaped look — light accent tint for ordinary actions, solid accent fill for the one
primary action per row. Accept/Decline/Hang-up on the call window keep their existing
green/red semantic colors; two minor file-attachment buttons (`saveFile`, `preview`) were not
restyled. `dotnet build -c Release`: 0 errors, one pre-existing unrelated warning
(`ChatWindowVoice.cs` CS1998). Not published, packaged, or device-verified — a source-only
checkpoint; packaging was not requested.

2026-10-06 user-authorized3.0.0 Portable x64 package: self-contained .NET9.0.9, native
video DLL and WebRTC notices bundled. Published0 errors, packaged native138/138,
WindowsCallUi11/11, startup pass with isolated data. No installer/.NET install required;
user identity/history still use LocalAppData/LanMessenger (no data migration). User confirms
closed camera cover, now open; image retest/listening/stress pending. No full acceptance claim.

2026-10-06 authorized paired video test: Connected/Video and changing phone-camera image
rendered on Windows; Windows local preview and phone remote view black, cause undetermined.
Clean hangup after about 92 seconds. Physical test exposed reversed Camera off/on command;
fixed and WindowsCallUi 11/11. Paired toggle retest, Windows-originated usable image,
listening quality and full stress gate remain pending; no final video release claim.

2026-10-06 paired continuation: user-authorized phone selection now includes Lap and ultra;
both devices Online. Android-originated voice-only call answered through Windows UI reached
Connected/Voice, then clean Windows hangup after about 39 seconds (cause=none). No camera
started; listening quality and paired moving-image acceptance remain pending. Older selection
blocker below is superseded. See execution evidence for the initial UI-recovery ringing timeout.

## 2026-10-06 Windows video completion execution checkpoint (development only)

Execution authorized by the user. Existing uncommitted Windows video/permissions work was retained
and completed with consent-gated real default-camera capture, local preview, bounded UI frame delivery,
native error propagation, serialized output-buffer/lifetime access, generation retry and unconditional
owner cleanup. Native video now produces a single VP8 m-line and ICE index 0, matching the existing
Android contract; its earlier audio+video SDP could not pass the shared production validator.
Camera toggles reuse the negotiated sender. Local camera failure preserves receive video and audio.
Initial unavailable-video answers fall back to voice; mid-call Accept/Decline video now appears and
starts/cancels coordinator work without duplicate signaling. Terminal call snapshots own their cause.

Automated: Windows Release 0 errors (existing CS1998); call/video 359/359; native ABI 39/39;
native managed/real-transport 138/138 (20 lifetimes, three generated ICE/DTLS/VP8 sessions);
call UI 9/9; dummy NativeAudioReadiness 24/24; shared frame/capability corpora 113+38 agree.
Local physical Windows default-camera preview and stop/reopen pass 2/2, with no remote peer.
Full tests/run.ps1 again stops on the recorded pre-existing FullMember capability failure in
group_membership; the later migration/ownership/Offline/UI checks are run separately.

Android SM-A075F was upgraded in place to original-signer 2.2.71/code98 dev candidate; first-install
time unchanged. Actual Windows↔Android calls remain pending: that phone's Direct connections list
currently exposes ultra while Windows Lap shows it Offline. The selected-device policy was preserved.
Default camera only; alternate selection, busy/unplug physical checks, all-DPI/10-minute stress and live
recipient-control acceptance remain open. Windows version remains 2.2.42; no final release or push.
See [execution evidence](video-calling/EVIDENCE-WVR-COMPLETION-20261006.md) and
[current completion plan](video-calling/PLAN-WINDOWS-VIDEO-READY-COMPLETION.md).

## 2026-10-06 call v2 regression repair (not a release)

Two real-device call regressions are fixed. Both were latent v2 bugs, unreachable while the CALLCAPS
probe failed and every call ran on protocolVersion 1; the v2 enabler at `ChatWindowCalls.cs:41`
(`VideoEnabled = true`) plus the HELLO-ordering fix at `PeerEngine.cs:236-238` made the probe succeed
and exposed them. Task contract, with the full diagnosis and evidence:
[video-calling/TASK-CONTRACT-CALL-V2-FIX.md](video-calling/TASK-CONTRACT-CALL-V2-FIX.md).

**Root cause 1 — Windows→Android died with `EndReason.MediaError`.** `CallVideoProtocol.ValidSdp`
required the m-line transport to be exactly `UDP/TLS/RTP/SAVPF`. SIPSorcery 10.0.17 — the Windows
adapter — emits `UDP/TLS/RTP/SAVP`. The validator refused it, `SendFrameTo` threw `IOException`, and
a bare `catch` turned that into `EndCallLocked(MediaError)` while discarding the exception. Both ports
now accept exactly the two legal profiles, `SAVP` and `SAVPF`; `TCP/TLS/RTP/SAVP`, `UDP/TLS/RTP/SAVPX`
and every other token stay refused, because DTLS over TCP is a different transport, not a different
profile.

**Root cause 2 — Android→Windows could be neither answered nor ended.** `CallSignaling.Make` never
allocated the frame body, so `CallSignaling.Accept` returned `B == null` and the controller's
`accept.B!["media"] = …` threw a `NullReferenceException` *after* the session had moved to `Connecting`
and *before* any snapshot, with the ring watchdog already cancelled. The accept therefore neither
reached the wire nor left any way out of the call. `CallSignaling.Accept` now allocates the body, the
same body Android's `Frame` constructor has always provided. A v1 ACCEPT still serializes to exactly
the same bytes — `Serialize` only emits `b` for a non-empty body — and that is now asserted.

Accept ordering is fixed in both answer paths (`AcceptAsync`, `SendAnswerLocked`): the ACCEPT goes on
the wire **before** the state moves to `Connecting` and before the ring watchdog is cancelled, and a
send that fails ends the call instead of stranding it. The three media-negotiation `catch` blocks now
record the cause instead of discarding it, through a single `FailMediaLocked` helper, exposed as
`LastFailureReason` and a `MediaFaulted` event. `call-diagnostics.log` gained a `cause=` field and an
`event=accept-failed` record for failures that produce no snapshot of their own, both written through
one tab-collapsing helper so a multi-line exception message cannot corrupt the file. The fire-and-forget
`_ = callController.AcceptAsync()` now has an observed continuation that reports a failed answer
instead of dropping it.

Verification, all on this tree:

| Check | Result |
|---|---|
| `dotnet build windows\LanMessenger.csproj -c Release` | 0 errors (1 pre-existing CS1998) |
| `dotnet build tests\CsharpHarness\CsharpHarness.csproj -c Release` | 0 errors |
| `CsharpHarness --call-video-check` | 338 passed, 0 failed (was 324; +14 new) |
| `CsharpHarness --call-frame-fixture-check` | 113 records, 0 failures (was 107; +6) |
| `tests/video-contract/run.ps1 -CsharpHarness …` | 113 frame + 38 capability records agree three-way |
| `tests/WindowsUi` | PASS |
| `tests/run.ps1` | PASS except `tests/group_membership.py` |

The new checks were proven to fail against the pre-fix code, not merely to pass against the new one:
reverting only the two source clauses makes **18 pre-existing** checks fail, because
`FakeCallVideoMedia` and Android's `FakeCallMedia` now emit `SAVP` instead of the `SAVPF` that agreed
with the validator by construction. That is the specific reason the suite stayed green while every real
call failed.

`tests/group_membership.py` fails on `Can't change this group's membership: FullMember… hasn't updated
to a version that supports it`. This is **pre-existing and unrelated**: it reproduces identically in a
clean `git worktree` at HEAD `902e92d` with no working-tree changes. It is not caused by this work and
is not fixed by it. Everything after it in `tests/run.ps1` passes when run individually.

Not done, deliberately: the reordering of the accept path has **no** automated test. `OnInvite` refuses
a peer absent from `PeerEngine.Peers` with a matching fingerprint, so reaching `AcceptAsync` needs two
live `PeerEngine`s paired over a real loopback connection — machinery `--call-video-check` deliberately
does not have. Its absence must not be read as that path being correct.

Physical acceptance is still outstanding and no claim of working calls is made: the disappearance of
`MediaError` is not evidence of a working call. MT1–MT6 in the task contract need two devices —
Windows→Android voice connects with two-way audio; Android→Windows incoming call can be accepted;
hang-up ends the call on both devices from either side; "Accept with video" no longer silently no-ops.
Once `SAVP` is accepted, a real SIPSorcery session begins on a path that blocks the UI thread
(`new RTCPeerConnection(null)` measured 192–1389 ms inside `CallController.gate`), so new symptoms are
possible. No release, no version bump, no commit, no push. Android changed only in the mirrored
validator clause and the fake's transport string.

## 2026-10-05 receive-only Windows video checkpoint

Windows source now enables the native video adapter when the verified ABI 1.1 bridge is present.
The bridge advertises a VP8 receive-only transceiver before the first offer; the managed adapter
now applies local offer/answer descriptions, polls and forwards real ICE candidates, reports media
readiness only after ICE reaches connected/completed, and labels remote offer versus answer
explicitly. ABI 1.1 adds a bounded native frame callback: decoded remote I420 is converted to BGRA,
copied at the managed boundary and rendered into the call window. This makes the intended first
usable direction Android-camera -> Windows display; Windows camera capture deliberately remains
disabled (`CameraEligible=false`) rather than substituting synthetic capture or claiming webcam
support. Probe/ringing still do not enumerate or open a camera.

The recipient-control bar from the preceding checkpoint can now become visible on a real v2 call:
Windows must be the original caller, the Android Slave grant must be freshly confirmed, Speaker is
available while connected, and Camera/Front/Rear require the live video leg. New native artifact:
`outputs/.build/video-media/bridge-c3bc54a8b16445298599dc029a100a99`; NativeBridge 39/39,
call/video 324/324, Windows Release build 0 errors with one pre-existing CS1998 warning. The updated
app was launched for user testing. No ADB device was connected, so Windows<->Android image/control
acceptance is pending and no release/parity claim is made. Android/wire unchanged; no commit/push.

The call window no longer falls back to the old compact layout when capability probing returns v1:
voice and video now share the larger arranged shell, centered contact initial and grouped action
bar. A v1 fallback is labelled `Voice call - video unavailable for this connection`, making a
failed/legacy capability result visible instead of looking like an old binary. Rebuilt and relaunched
for the next physical retry; visual acceptance remains pending.

## 2026-10-05 recipient controls and call-window layout

The existing `Permissions...` entry is retained. The call window now has a separate, visually
grouped `Control recipient` bar with Speaker on/off and, during live video, recipient Camera
on/off plus Front/Rear controls. Visibility matches Android's direction: Windows must be the
original caller (Master), the call must be Connected/v2, and the Android Slave must have returned
a fresh certificate-bound grant. Windows refreshes that grant on connection and every five seconds;
unknown, failed, expired, revoked, changed-certificate, callee-role and ended-call states hide all
controls. Every click rechecks the same confirmed direction, and `CallController.SetRemoteCamera`
and `SetRemoteSpeaker` were corrected to authorize from `RemoteControlDisplayMask` rather than the
opposite-direction local Masters store. Camera controls additionally require a live video leg.

Important current-product limit: Windows production video remains disabled and therefore negotiates
v1 voice calls. `REMOTE_*` is a v2 contract, so these controls intentionally do not appear in the
current voice-only runtime; showing them would be a nonfunctional compatibility bug. The UI and
authorization path are ready for the production video switch, but real Windows-to-Android control
acceptance remains blocked on Windows video completion. Automated: Release build succeeds (0 errors,
one pre-existing CS1998 warning); call/video harness 324/324 including focused role/state/scope/v1
visibility cases. Android and wire protocol unchanged. No release, commit or push.

## 2026-10-05 Masters / Slave call-permission lists

Windows source now exposes a `Permissions...` entry with the same directional meaning used by
Android: **Masters** lists verified devices that this Windows installation granted call access to,
and **Slave** lists verified devices that reported granting access to this Windows installation.
Masters is backed by the existing certificate-bound local grant store and opens the existing
trusted-call editor, so individual voice auto-answer, video auto-answer, remote-camera and
remote-speaker grants can be changed or revoked. Slave is deliberately read-only: it queries the
existing authenticated `CALLGRANTS/1` responder, refreshes online verified peers every five
seconds or on demand, distinguishes current, last-known/offline and unknown results, and never
treats the displayed remote mask as local authorization. Certificate changes/revocation discard
the cached presentation. No Android or wire-format change was needed.

Automated verification: Windows Release build succeeds with 0 errors and 0 warnings; the focused
Windows call/video harness passes 317/317. The native-window surface
was unavailable to the computer-control session, so final visual/device acceptance of the two
dialogs remains pending and is recorded separately from implementation and automated verification.
No package, release, commit or push was produced.

## 2026-10-05 Android-to-Windows distorted audio regression repair

The reported Android-to-Windows call audio was traced to uncommitted Windows audio changes
that switched the production preference from G722 to PCMU while leaving the winmm device at
16 kHz, and reduced the fixed PCM frame from 640 bytes/20 ms to 320 bytes. PCMU is an 8 kHz
codec, so decoding it into a 16 kHz playback path produced the observed timing distortion and
choppiness. Restored the established Android-compatible G722-first negotiation and the
640-byte 16 kHz/20 ms capture/playback contract. The first listening retry then isolated a
second, outbound-only defect: the Windows microphone passed 320 decoded PCM samples to
`SendAudio` as 320 RTP timestamp units. G722 has 16 kHz PCM but an RFC-defined 8 kHz RTP
clock, so each 20 ms packet must advance by 160, not 320; the wrong value advertised 40 ms
between packets and produced regular gaps on Android. `RtpDurationFor` now applies that G722
conversion (while leaving other codecs unchanged). A reflection-level focused check confirms
G722 320 samples => 160 RTP units and PCMU 320 => 320. Also repaired the adjacent malformed
`RequestVideoClicked`/`HangupClicked` binding that otherwise prevented the current tree from
building. Windows Release build succeeds with 0 errors and the one pre-existing CS1998 warning.
Android source and wire protocol are unchanged. A single Android-to-Windows call was initiated
after the first repair; user listening reported the Lap/Windows microphone still choppy, which
is the evidence that exposed the RTP-clock defect. Post-clock-fix audible acceptance remains
pending. No package, release, commit or push was produced.

User acceptance after the RTP-clock correction: audio became "much better" and the severe
cutting was removed, but the laptop microphone remained low and not fully clear. The production
winmm path supplies raw PCM and has no WebRTC AGC/noise suppression/AEC. A bounded microphone-
only conditioning pass initially used fixed gain; user listening still found it slightly low. The
current source therefore uses bounded adaptive microphone gain: meaningful speech targets RMS
5000, gain is limited to 1x..12x, rises gradually, falls faster for loud input and retains signed-
16-bit saturation. Silence below RMS 100 does not increase gain, which avoids lifting room noise
between speech. Optional diagnostics remain gated by `LANMESSENGER_AUDIO_DIAGNOSTICS` and record
timing/levels only, never audio. The captured diagnostic evidence showed stable 20 ms delivery but
very low raw laptop input (RMS 58.8, peak 2267), supporting level conditioning rather than another
packet-timing change. Focused RTP/gain/limiter checks and Release build pass; physical listening of
the adaptive version remains pending. This does not claim noise suppression or echo cancellation.

## 2026-10-05 native bridge crash/empty-offer repair

Captured original AV without WER registry changes. Missing codec factory dependencies
corrected; hardware-free dummy ADM and bounded async SDP completion now pass NativeBridge
39/39, readiness 24/24 and 20 audio offer/answer/teardown cycles. Release build: 0 errors.
Full tests/run.ps1 exit 0 (including Windows UI); call-video 317/0. Historical group-membership
flake remains unresolved, although this run passed. Code committed locally as 3943a59.
[Evidence and limitations](video-calling/BRIDGE-CRASH-REPAIR.md).
WVC-06/T02 remain Partial: no physical CoreAudio/authenticated replacement-audio acceptance.
WVC-08 blocked, production video disabled. No release/push.

## 2026-10-04 WVC-03 advisory review: corrected, gate PASS, input selected for Section B

Reviewed current Chromium advisories against the exact pinned M155 input. An earlier pass of
this review **stopped without selecting the input**; that pass was wrong, and the correction
is recorded here in full rather than quietly overwritten. Full evidence:
[WVC-03 advisory review](video-calling/WVC-03-ADVISORY-REVIEW.md). **No security clearance is
claimed and none was given** — what changed is that the blocking finding was a false one.

**The correction.** The stop was justified by CVE-2026-103631 (*High*, buffer overflow in
WebRTC) being absent from the pin. That conclusion came from an *inferred* M155 branch-point
date versus the M154 stable release date, and it is wrong. Fetching the pinned commit
`f89edcb7be1f4be029ee7186e36b2b35ec03373e` from official upstream
(`webrtc.googlesource.com/src/+/f89edcb7…`) shows it is titled
`[M155] Harden payload capacity and reduction checks in RTP packetizers`, carries
`Bug: chromium:567088927` — the advisory's own bug id — sits at
`refs/branch-heads/8059@{34859}` (our branch), and was cherry-picked from
`fc6666263eafa63878d02102189e3dabe9c90455`. **The pin *is* the fix**, so the build contains it
and no re-pin is needed. The R01 disposition table had in fact recorded this correctly at the
time; the later pass re-derived the same advisory as unresolved and did not check it.

**Second correction: 25 components, not 24.** The `NOTICE` inventory is 25, not 24. The
earlier count dropped `libc++` because its extraction regex character class excluded `+`.
`libc++` is MIT/NCSA with LLVM exceptions, inside the set already approved.

**Licence delta versus prior approval: zero.** The shipped inventory is the same 25 names R01
already read and approved (BSD variants, Apache-2.0, MIT/NCSA legacy LLVM terms with LLVM
exceptions, IJG/zlib, FFT/ooura permissive, G711/G722/sqrt public-domain; no geographic-use
restriction). There are no newly introduced and no previously unapproved components, so the
allowlist question the earlier pass left open is answered by comparison rather than by a new
approval. Copyleft screening still returns no AGPL/GPL/LGPL obligation, no CC-BY-NC and no
Commons Clause: the 6 `GPL` hits are the Apache-2.0 appendix plus a public-domain dedication,
and all 88 `MPL` hits are substrings of `SIMPLY`/`IMPLIED` inside BSD warranty text.

**The gate was rewritten and now derives its verdict.**
`tests/video-feasibility/windows/audit-upstream.ps1` — not `audit-input.ps1` — is the gate for
this input. The earlier pass identified `audit-input.ps1`'s defects but missed that it audits
the **m150 DLL archive** (defaults to `libwebrtc-win-x64-release.zip`, requires exactly one
`lib/libwebrtc.dll`, hardcodes m150-era `reasons` and constant
`gate`/`selected`/`securityDispositionComplete`). The M155 candidate ships zero DLLs — only
`sdk/webrtc/lib/webrtc.lib` — so that script throws on the real input. It is now marked
superseded and its verdict deliberately left untouched; editing `gate = 'HOLD'` to `PASS`
would have been self-approval.

`audit-upstream.ps1` now audits **both** the official upstream archive
(`webrtc.windows_x86_64.zip`, 751,214,637 bytes, SHA-256 `3460e4fe…`, 40,995 entries) and the
vendored package a build would reference, computes `gate` from accumulated `$fail`/`$unresolved`
lists instead of asserting it, builds `reasons` from those lists, and proves store-and-forward
integrity by hashing both copies of the library: 369,225,972 bytes, SHA-256 `c5ae79fe…`,
**byte-identical**. Advisory inclusion is expressed as a relation per advisory, and the gate
distinguishes what it can prove offline from what is carried from reviewed upstream evidence.
It reports `PASS`, exit 0. Metadata only: never extracts, links, loads or executes native code.

**The derived gate was proven load-bearing**, since a gate that always passes is worthless:
missing package, wrong artifact passed as package, tampered identity commit, and an advisory
relation set to `unknown` each return `HOLD` exit 2 with a specific reason. Building those
tests caught two real defects, both fixed — an over-strict path check that rejected the
nupkg's own root metadata (`input-manifest.json`, `.nuspec`), and a crash instead of a verdict
when a malformed package collapsed an empty array to `$null`.

**Honest limit of the PASS.** 1 of 7 advisory rows is machine-verified (CVE-2026-103631, by
identity). 3 are tagged `ancestor` and 3 `scope` — carried from R01's reviewed upstream
evidence, reported as *not* machine-verified, because proving ancestry needs a WebRTC git
clone that is not available offline. The gate labels the distinction in its own output so it
stays visible. Residual provenance limitation, disclosed not hidden: `sdk/webrtc/DEPS` is a
55-byte stub that does not pin the Chromium core revision, so that revision remains
unestablished from the artifact; `VERSIONS` plus the pinned archive digest identify the
shipped library beyond doubt, and the WebRTC commit — the revision the advisory fixes — is
pinned.

**Not performed, and still outstanding:** no production-named package, no native bridge or
adapter, no build against the static library. Production video **remains disabled**
(`CallVideoSupport` defaults false; `VideoEnabled` requires an installed media backend) until
the WVC-T03/T04/T05 and WVC-08 automated gates pass, and WVC-12/14/15/16 physical acceptance
needs a real webcam and two physical phones. Selection unblocks Section B integration; it does
not switch video on.

**Unresolved intermittent failure, carried forward.** `tests/group_membership.py` aborted once
in this pass (`run.ps1:85`, exit 1 via `Check-Result`) after printing only PASS lines, with
empty captured stderr; the immediate re-run exited 0. Recorded as an **unresolved intermittent
failure**, explicitly **not** as fixed — a passing re-run does not prove a non-reproducing
failure is resolved, and no diagnosis was obtained. Not attributed to the known zombie-`dotnet`
contention, because no evidence for that was gathered. No application code changed between the
two runs.

## 2026-10-04 implementation pass: managed v2 video coordination (not a release)

Implemented in source on top of local commit `f27f05b`. **No release was packaged and
no physical-device acceptance was performed or is claimed.** `<Version>` is still
`2.2.42` and the shipped Windows release still contains none of this. Checklist row by
row, with evidence levels separated: [Windows video calling plan](video-calling/PLAN-WINDOWS-VIDEO-CALLING.md).

**Implemented code, no device needed.** A strict v2 call-signalling stack that keeps v1
intact: `CallVideoProtocol.cs` (closed validator — `MaxDepth=16`, duplicate keys
rejected, exact key set per type, integral signed-64 counters, 64 KiB frame / 48 KiB SDP
/ 4 KiB ICE bounds, 128 ICE per generation), `CallCapabilities.cs`, `CallFrameAdmission.cs`,
a rewritten `CallSignaling.cs` (strict UTF-8, all v2 builders), and `PeerEngine.cs`
responders for `LM4\tCALLCAPS` and `LM4\tCALLGRANTS`. The coordination layer is
`CallVideoConsent.cs`, `CallVideoActions.cs`, `CallVideoCoordinator.cs`,
`CallVideoDiagnostics.cs`, `CallVideoPlacement.cs`, `CallVideoResources.cs` and
`CallCameraPermission.cs`, fronted by an `ICallVideoMedia` seam whose only
implementation is `FakeCallVideoMedia.cs`. `CallController` gained a v2 INVITE `media`
tag, audio-only answer, mid-call bilateral upgrade, decline, per-role trusted
auto-answer, Android's two-stage frame admission (video frames are intercepted before the
state/role table, and the heartbeat is refreshed only after admission succeeds), and
`CallSession` gained `VideoCapable`/`InvitedVideo`/`Video` plumbing. `CallView` gained a
video stage, a draggable preview clamped into the stage, and accept-with-video /
decline-video / camera / add-video controls; `ShowTrustedCallAccess` now offers all four
grant bits, each with its own consent prompt on first grant. `PlatformTarget=x64` pins
the architecture.

**Automated verification actually run.** `dotnet build windows\LanMessenger.csproj -c
Release` succeeds with 0 errors and only the one pre-existing `ChatWindowVoice.cs(19,16)
CS1998` warning. Full `tests/run.ps1` exits **0**. `CsharpHarness --call-video-check`
reports **317 passed, 0 failed** (up from 307; the harness now links `CallController`
and its collaborators, so controller-level behaviour is testable at all — it was not
before). `tests/video-contract/run.ps1` reports
`107 records agree between Android and Windows` (shared frame corpus) and
`38 records agree between Android and Windows` (shared capability corpus), with 0
failures against a hand-authored expectation on both platforms, plus 432 Android-side
video checks passing. The corpora are three-way — Android's verdict, Windows's verdict
and a hand-authored expectation must all match — which is the only interoperability
evidence this pass produced, and it is evidence about parsing and serialization, **not**
about moving pictures.

**Not done, and not implied.** No native video adapter, and now a specific reason: the
pinned M155 input is **held at the advisory gate** over an unresolved WebRTC buffer
overflow (see the review entry above), so it cannot be selected and
`ICallVideoMedia` has no production implementation. **Consequently this build advertises
voice only.**
`CallVideoSupport` defaults to false, and `CallController.VideoEnabled`'s getter is
`videoEnabled && VideoMediaFactory != null`, so no assignment can produce a build that
claims VP8 with nothing to serve it. A peer that probes this build gets no CALLCAPS
reply and offers voice, producing a clean v1 call; inbound v2 INVITEs are refused by
closing the transport; and the camera/video controls never appear, because
`VideoCapable` is false for every call. The v2 machinery is therefore exercised by the
fixtures and coordinator tests but is **not** on any path a real call currently takes.
Installing the adapter later means setting `VideoEnabled = true` *and* assigning the
factory — neither alone is enough, and the wiring site says so.

That gating corrects a defect in this same pass, recorded here because it is exactly
what the gating exists to prevent: both flags had defaulted to `true`, and the app shell
seeded the controller from the engine's default and then made the engine read the
result back — a self-referential pin to `true` with no path to `false`. Ten
`capability-honesty` checks now pin the rule, and three of them were confirmed to fail
against the old behaviour before being kept.

Section B (the native voice replacement, WVC-05–08) was deliberately not started;
voice stays on the proven SIPSorcery 10.0.17 + G722 + winmm adapter, untouched. Also
not done: webcam capture,
frame ownership, renderer selection and measurement, call lifecycle/tray/lock/sleep,
recipient-side grant visibility, candidate packaging, and every physical gate
(T05/T07/T08/T09). There is no WinForms UI test for calls — a pre-existing gap this
pass did not close — so the new stage, preview dragging and DPI behaviour are entirely
unverified on screen. The archived-2.2.42 compatibility claim rests on fixture parity
and on a plain v1 INVITE still being exactly `caller`/`callee` at generation 0, not on
a two-device call. `RuntimeIdentifier` was deliberately left unset: it drags in
win-x64 runtime/apphost packs the vendored offline feed does not carry (NU1101), and
`PlatformTarget` already fixes the architecture. That is recorded in
`LanMessenger.csproj` so it is not rediscovered as a defect.

One regression was caught and fixed during this pass, worth recording because it was
introduced by the work itself: answering an incoming call *with video* routes through
`CallVideoActions.AcceptVideo`, which lands in `SendAnswerLocked` rather than in
`AcceptAsync`. The first version of that method created no audio adapter, so a video
answer would have negotiated nothing and simply timed out. `StartMediaLocked()` is now
the single place any media adapter is created — which is also what makes "no camera
acquired during capability probing or ringing" a checkable property rather than an
aspiration.

2026-10-04 planning-only review: current continuation checklist is
[Windows video calling plan](video-calling/PLAN-WINDOWS-VIDEO-CALLING.md).
Native generated-video/G722 feasibility is complete; production Windows remains
voice-only SIPSorcery 2.2.42. Production bridge, authenticated v2 coordination,
physical webcam/rendering, trusted controls and packaged acceptance remain pending.
The new plan separates evidence levels and removes stale Android/audio-listening
gates. No application changes, build or new acceptance in this review.

2026-10-03 trusted-call/icon source checkpoint: the supplied logo is embedded as a
multi-resolution executable icon and used by the main window and tray. Verified
contacts now have a certificate-bound **Trusted call access** setting for automatic
voice-call answering. The grant is encrypted with peer state and is cleared by
verification revoke/remote forget, key mismatch (ineligible), contact deletion or
delete-all. Incoming calls still pass global allow, verified identity, busy,
rate-limit and Offline checks before the normal accept/media path. Release build
and framework-dependent publish passed; published EXE SHA-256
`c0d6f9adafe538a91772108296a07510fad8c6224df9c81bf6926a4063729a0e`.
No release or physical call acceptance is claimed. Remote speaker control and all
camera/video scopes remain visibly unavailable pending the shared control contract
and R05 Windows production video work.

User accepts audio testing complete and requests no repeated listening. A02b
shared video choice: VP8/separate secured video PC (Android A02b-CONTRACT.md).
R05 production migration is authorized but not implemented; production dependency
and Windows video acceptance remain separate. Older listening entries are historical.

Historical human listening correction (superseded by user acceptance above): no sound heard, overriding earlier selected
both-audible response. Replacement audible acceptance remains OPEN; no factory
switch or release accepted from packet counters alone.

Latest 2026-10-03: test-only native PeerConnection endpoint paired with physical
Android over LAN. Separate video-only PC passed generated VP8 motion both ways,
G722 continuity and tested video failure/disposal. Single-PC rollback interrupted
audio and is rejected. No production dependency migration or release change.
See [paired evidence/handoff](video-calling/PAIRED-FEASIBILITY.md); older R02
paragraphs below are historical checkpoints, not the latest paired result.
R05 production migration explicitly authorized, execution plan in
[R05 migration](video-calling/R05-MIGRATION.md). Native ↔ unchanged production
2.2.42 voice media passed both offer directions, G722 packets and legacy mute
state; authenticated production call-path checks remain pending. Listening was
subsequently accepted by the user and is not a renewed gate.
Native cap/fifth rejection and 20 teardown lifetimes passed. Windows Release
build passed. Latest full suite passed hardware audio 20/0, then failed the
16-member group reinvite capability; isolated retry failed full-group pairing.

## Replacement R01 execution audit (2026-10-03)

Latest R02 advance: generated VP8 codec-local encode/decode passed in two native
lifetimes and one net9 ABI lifetime: each 20 encoded/20 decoded/19 motion changes,
zero dropped frames. No devices, network or PeerConnection used; R03/R04 and
WT01/A02b remain Pending. Output hash and next endpoint tasks in R02 handoff.

Latest phase result: R01 filtered offline test input packaged with complete
binary notices; R02 started and native EXE + net9 x64 C ABI checks passed, including
G722 enumeration and three invalid-buffer checks. `/MT` matches upstream static
CRT; no linker checks suppressed. R02 endpoint/media work and R03/R04 remain
Pending; this is not audio/video interoperability. See
[R02 phase handoff](video-calling/R02-HANDOFF.md) for hashes, reproduction and tasks.
Windows Release build passed; full tests/run.ps1 rerun again failed five recording
device checks (mmresult 1), later tests not run. Production dependency unchanged.
The audit progress paragraphs below describe the earlier pre-packaging stage.

User explicitly accepted separately reviewed permissive transitive licenses
("اقبل واكمل"), retaining the no-geographic-restriction requirement. All 25
delivered binary NOTICE sections were reviewed, including IJG/zlib, LLVM
exceptions, BSD variants and public-domain grants. This closes the policy
question, not all distribution/security obligations. Five identified recent
WebRTC advisories have source-specific dispositions in the R01 handoff.
The new upstream metadata checker passed hash/provenance/path/notice/COFF
assertions for 40,995 entries. Offline packaging must not blindly redistribute
all headers: the archive also contains unused third-party headers beyond the
binary NOTICE inventory. R01 remains In progress; no media executed and R02+
remain Pending. Android A02b and production source/packages are unchanged.

Continued alternative audit found shiguredo's M155 input with matching official
SHA256, explicit pinned core/dependency revisions and bundled NOTICE. It resolves
the earlier missing-metadata concern but includes IJG/zlib transitive license
terms outside the literal three-license allowlist. Need user clarification of
allowlist scope before selection; full security review still Pending. No binary
loaded, library extracted/linked or production code changed.

User approved R01–R04 execution. Exact libwebrtc x64 archive checksum passed and
PE imports/architecture were inspected without loading it. Candidate remains
HOLD: core binary revision, complete static-component notices and version-specific
security disposition are unresolved. R02 prototype and paired R03/R04 have not
started. [R01 audit handoff](video-calling/R01-AUDIT-HANDOFF.md) records evidence,
read-only audit checker and the source-build/authoritative-evidence resume paths.
No production changes, vendor selection, release or compatibility claim.

## RTC replacement search (2026-10-03, user-directed)

User rejected the geographically restricted library and requested only
MIT/Apache-2.0/BSD alternatives without geographic restrictions. SIPSorcery core
and its media candidates are excluded from the new selection, not yet removed
from the existing app. Preferred research candidate: native libwebrtc (BSD) with
the webrtc-sdk MIT wrapper; exact Windows m150 release identified. Binary notices,
transitive license/security review and voice/video interoperability remain Pending.
[Replacement research and small-task amendment](video-calling/REPLACEMENT-RESEARCH.md)
records evidence and approval boundary. No application code, dependencies,
production behavior, package or compatibility claim changed in this research turn.

## Video feasibility prerequisites (2026-10-03 continuation)

W00/W01 are In progress; W02 is blocked on clarification of the exact package's
license coverage. WT01 and Both A02b remain Pending. PC physical integrated webcam
and driver were inventoried, but formats and actual capture are unverified.
Two exact codec candidates were downloaded to temporary review outputs only;
no new dependency was selected/vendored, no prototype or production code changed,
and no Windows–Android video interoperability is claimed. See
[W0 feasibility and handoff](video-calling/W0-FEASIBILITY.md) for task-level status,
package hashes, source/security observations, license evidence and resume steps.


## Release 2.2.42, real-device bugfix pass (2026-10-03): UI freeze on Call, missing ringtone

First actual manual use of the packaged 2.2.42 build on real Windows hardware surfaced two bugs
neither the automated suite nor the `windriver` console-driver call test could have caught (the
driver calls `CallController` directly, never through the WinForms UI; there is no UI test for
calls yet):

1. **Clicking "Call" froze the whole app ("Not Responding").** `ChatWindowCalls.cs`'s
   `StartCallToSelected()` — the Call button's click handler — called `CallController.StartCall`
   directly on the UI thread. `StartCall` holds its internal lock for the entire duration of
   `EngineTransportFactory.Open`, which performs a *synchronous* blocking TCP connect + TLS
   handshake (`engine.OpenCallConnectionAsync(...).GetAwaiter().GetResult()`) — genuine network
   I/O that can take seconds, or hang indefinitely against an unresponsive peer. This blocked the
   UI thread for that whole window, which Windows reports as "Not Responding" and which the user
   reasonably read as a crash. Fixed by moving the actual `StartCall` call onto a background
   thread (`Task.Run`); `CallController` is already internally thread-safe and its own snapshot
   callback already marshals back to the UI thread via `BeginInvoke`, so only the failure
   `MessageBox` needed to come back explicitly. `OnInvite` (the incoming-call path) was never
   affected — it runs on the engine's own accept-loop thread, never the UI thread.
2. **No ringtone at all for an incoming (or outgoing) call.** `CallView.cs` had no audio cue
   whatsoever — only the (easy-to-miss, non-modal) call window itself. First attempt added a
   repeating `System.Media.SystemSounds.Exclamation` played on a 1.8s `System.Windows.Forms.Timer`
   — the same mechanism already used for ordinary message notifications in `ChatWindowDialogs.cs`.
   **This did not actually work**: the user confirmed on real hardware that an incoming call still
   produced no audible ring even though a sound file was confirmed assigned to that event in the
   registry — the OS "system sound" event (`MessageBeep`) can apparently still be silently
   suppressed independent of both the assigned file and the speaker volume (sound-scheme state,
   per-event mute, or similar). Replaced entirely with a new `CallRingtone.cs`: synthesizes an
   actual two-tone (440Hz+480Hz) ring cadence as raw PCM and plays it directly through
   `CallAudioPlayback` — the same proven winmm output path real call audio already uses
   successfully — rather than depending on any OS sound-event mechanism. Started/stopped from the
   same `UpdateRingtone`/`OnCallStateChanged` hook in `ChatWindowCalls.cs` as before, just backed
   by a different, more reliable audio path. **Confirmed audible by the user on the same real
   hardware (2026-10-03)** — both this and bug 1 above are now user-confirmed fixed.

Rebuilt (`dotnet build -c Release`, 0 errors) and republished over the existing 2.2.42 package —
no version bump each time, since these correct a just-shipped release rather than add a feature.
Two successive rebuilds happened under this same entry (bug 1 + first ringtone attempt, then the
ringtone replacement above); only the final zip exists on disk: `outputs/LanMessenger-Windows-
2.2.42.zip` (7,210,458 bytes, SHA-256
`a3975ac0acff78a3c59dbd64a6c6985f25a70f2307155e2fd7a1cdffff6f0d87`), manifest updated at
`outputs/SHA256SUMS-Windows-2.2.42.txt`. Full `tests/run.ps1` suite passed clean (exit 0) against
the intermediate build (bug 1 fix); the final ringtone-replacement build was not separately
re-run through the full suite since `CallRingtone.cs`/`ChatWindowCalls.cs` have no automated
coverage either way (no WinForms UI test exists yet for calls) — a clean `dotnet build` is the
only automated signal available for this specific change, same as for bug 1's original (reverted)
fix attempt.

**Both fixes confirmed by the user on real hardware (2026-10-03): "yes both fixed, ringing works
now."** Call no longer freezes the app, and the incoming ring is now actually audible.

## Release 2.2.42 (2026-10-03): first packaged build with voice calls

Packaged the voice-call implementation below into an actual Windows release, numbered to match
the current Android release (2.2.42) by explicit user request rather than continuing Windows'
own independent counter. `windows/LanMessenger.csproj`'s `<Version>` and `Program.cs`'s
`AppVersion` bumped 2.2.0 → 2.2.42. No wire/storage change beyond the voice-call feature itself
(already cross-platform-verified below) — this is a packaging step.

`dotnet build -c Release`: 0 errors (the one pre-existing benign `CS1998` warning in
`ChatWindowVoice.cs`). Full `tests/run.ps1` suite passed clean, exit code 0 — every test
including the native Windows UI suite, with no recurrence of the `group_membership.py` flake on
this run. Published framework-dependent via `dotnet publish -c Release` (requires .NET Desktop
Runtime 9, no self-contained bundling), matching the shape of every prior Windows release.
Zipped flat (no parent folder) as `outputs/LanMessenger-Windows-2.2.42.zip` (7,209,116 bytes,
SHA-256 `5e26794554fbcc868fdd9907df248518e2030d14ac9b2978fd14a4f6317ed3a6`), manifest at
`outputs/SHA256SUMS-Windows-2.2.42.txt`.

Manual two-device physical acceptance of this exact packaged build remains Pending — the live
cross-platform call test below was run against the unpackaged `dotnet build` output via the
`windriver` console driver, not this zip. No reason to expect a difference (packaging doesn't
touch behavior), but it hasn't been separately exercised.

## Voice calls — implemented and verified by a real cross-platform call (2026-10-03)

Windows now has a complete, working voice-call implementation, wire-compatible with Android's:
`CallProtocol.cs`/`CallSignaling.cs` (same 4-byte-length+JSON framing, message types, timing/
limit constants, admission/state-machine rules as Android, ported line-for-line), `CallSession.cs`,
`CallChannel.cs` (the CALLCONNECT-handoff transport), `CallController.cs` (the state machine),
`CallSettings.cs`, `CallAudioIo.cs` (continuous winmm capture/playback reusing the project's
proven P/Invoke pattern), `WebRtcCallMedia.cs` (SIPSorcery-based `ICallMedia`, G722 audio codec —
SIPSorcery's bundled encoder does not actually support Opus despite its constructor signature
suggesting otherwise; G722 is a standard WebRTC fallback both sides offer, confirmed to interop),
`PeerEngine.Calls.cs` (the CALLCONNECT handoff and local-only call-log entry), `CallLogMarker.cs`,
and a minimal (correctness-first, not yet visually matching Android's reference teal-header/
hang-up-disc design) `CallView.cs`/`ChatWindowCalls.cs` UI. SIPSorcery 10.0.17 and its full
transitive dependency graph (92 packages) are vendored into `vendor/nuget` to preserve the
project's offline-only build policy.

**Verified by a real two-device call over the LAN** (Windows build machine ↔ a physical Android
phone, both directions) using a headless console driver exercising the production
`PeerEngine`/`CallController`/`WebRtcCallMedia` classes directly (there is no GUI-automation tool
for native WinForms): Windows→Android reached `Connected` with a live, ticking in-call timer on
the Android side and a clean `RemoteHangup` teardown with a real computed duration (33.5s);
Android→Windows reached `Connected` the same way (15.4s), auto-accepted on the Windows side.
Call-history entries ("X called you · duration") appeared correctly on Android for both
directions. This confirms G722 codec/SDP negotiation actually converges between SIPSorcery and
Android's libwebrtc — the single biggest flagged interop risk going in.

**One real bug found and fixed by this test**: `SecureChannel`'s constructor hardcodes a 6-second
`ReadTimeout`/`WriteTimeout` on the underlying network stream, sized for the ordinary quick
HELLO/READY/message handshake. The CALLCONNECT handoff reuses that same stream/instance for the
entire lifetime of the call-signaling channel, where frames are legitimately tens of seconds
apart (ringing wait, human accept/decline time, silence between heartbeats) — so any call died
with a spurious `SignalingLost`/socket-timeout the moment a human took longer than 6s to answer.
Fixed by adding `SecureChannel.UseLongLivedTimeouts()` (sets both timeouts to infinite) called
once, immediately after the CALLCONNECT line is written/read, on both the outgoing side
(`PeerEngine.Calls.cs`'s `OpenCallConnectionAsync`) and the incoming side (`PeerEngine.cs`'s
`Receive()` CALLCONNECT branch). This is a Windows-only bug (Android's equivalent handshake layer
never imposed this timeout) — no wire or Android-side change needed.

Remaining open items: `CallView.cs` does not yet visually match Android's reference call-screen
design (functional parity only); `tests/run.ps1` has no automated call-protocol/state-machine
test yet (W-phase test tasks from the plan below are not written); call quality under real-world
conditions (packet loss, multiple devices, degraded Wi-Fi) has not been stress-tested, only a
clean two-device LAN call.

Before picking up further call work, read the **Addendum (2026-10-02)** at the end of
`.ai-planner/sessions/20260930-091333-43c139/planning/plan-v006.md` (the shared voice-calls plan)
for the full decision record this implementation was built from, and the W0–W5 task breakdown for
what remains (test-writing tasks, UI-parity pass, stress testing are the main gaps).

## Voice Messages implemented in source (Phase 2 / W01-W10, WT01+WT06, not a release)

Windows Phase 2 of the shared Voice Messages feature (`plan-v003`, tracked at
[PLAN-VOICE-MESSAGES-WINDOWS.md](../PLAN-VOICE-MESSAGES-WINDOWS.md)): record, send, receive and
play short voice clips, reusing the existing encrypted Normal attachment store with a
`voice-<message-id>.lanvoice.wav` marker filename — no new LM4 wire frame. Fixed format:
RIFF/WAVE, 16 kHz mono 16-bit PCM, 640-byte/20 ms frames, max 300 s/9.6 MB. `waveIn`/`waveOut`
P/Invoke capture/playback (serialized native control, minimal-work native callbacks, idempotent
Stop/Dispose — avoids the classic MM-callback deadlock); a durable, encrypted, capped (10-entry)
draft registry with startup reconciliation; transactional Send (message id and marked filename
allocated together); Candidate/Fetching/Playable/Invalid/Unavailable receiver cards; one active
inline player app-wide with seven-step seeking (±10 s buttons) and no plaintext playback file;
voice folded into the existing automatic-media scheduler under the shared nine fixed rules;
full keyboard/accessibility coverage. W11 (this entry) is the only remaining code-adjacent task —
W10 verification is done; **manual two-device acceptance on physical hardware is Pending, cannot
be performed by an agent.**

Build: `dotnet build windows/LanMessenger.csproj` passes (0 errors). WT01
(`CsharpHarness --voice-check`, the real `VoicePcmAssembler`/`VoiceWav`/`VoiceSeek`/`VoiceMarker`
production classes against the shared `tests/voice_messages/vectors/manifest.json`) passes
(`PASS=38 FAIL=0 SKIP=30`). WT06 (`tests/voice_architecture_check.py`) confirms the
transport-independent PCM/WAV/marker/seek core has no WinForms/PeerEngine/attachment-store/LM4/
device-API dependency and that Windows carries no platform-local copy of the shared fixtures.
WT02-WT05 (fake-audio-input lifecycle faults, scheduler/dedup coverage, one-player-enforcement/
export) are not yet written. A full `tests/run.ps1` run surfaced one pre-existing failure in
`tests/group_membership.py`, unrelated to Voice Messages (traced to `AddMember`'s live
`QueryCapability` call returning 0 on a real network timeout under the heaviest 16-17-process
scenario — the same class of environmental contention already documented below for the
2.0.0-era group-membership work, not a regression from this feature).

This is **platform-local only; interoperability with the Android Phase 1 work (developed
concurrently by another agent in this same repository) has not yet been verified** — that is
Phase 3 of the plan, not yet started.

## Offline controls implemented in source (G1–G3, not a release)

The Windows engine has reusable `Start`/`GoOffline` and terminal `Dispose` (G1). G2 adds a default-Online persisted request read before `Shown` starts the network, one transition method shared by toolbar and tray, actual engine state and bind error in the status line, and local queued direct/group sends while Offline. Closing the window still hides to tray; Exit terminates. Offline closes LAN listeners/connections/discovery; Refresh and Add by IP are disabled while Offline, and verification, group capability checks and new remote downloads cannot reach LAN. Local identity, contacts, groups, history, cached attachments and partial transfers remain accessible; interrupted transfers resume on reconnect. Remote presence turns gray after approximately 12 seconds. A failed bind leaves actual state Offline despite an Online request; toolbar and tray show Retry online, which retries without flipping the persisted preference. The native UI suite covers offline preference/queued sends and blocked-port retry. No release or LM4 wire/compatibility change.

G3 automated check: cross-platform `tests/offline_lifecycle.py` covers accepted idle control socket shutdown, Offline Add-by-IP/refresh and remote-download gating, rejected discovery, delayed avatar sync, group capability refusal, three interrupted receiver transfer modes (Fast direct, ordinary direct, encrypted ordinary cache) plus interrupted Fast sender, partial stability, no early completion marker, integrity and duplicate-free retries. The full `tests/run.ps1` suite (including Windows native UI) and Android SDK source compilation passed with SDK 9 MSBuild and in-workspace test output; one prior full attempt failed under the 16-member capability stress scenario and passed on isolated rerun and final full rerun. This is loopback engine coverage, not physical LAN acceptance; Windows native UI blocked-port/retry is automated, but physical Windows/two-device acceptance: **Pending-Unavailable**.

Reviewed 2026-09-29. Release: **2.2.0** — the first Windows release to package the Voice
Messages Phase 2 work described in the section above (record/send/receive/play, ±10s seek
steps, one active inline player app-wide). No wire/storage change from 2.1.0; every other 2.1.0
capability (group ownership transfer, 2.0.1 carryover) is unchanged.

**Real bugs and UX changes from actual device testing of the first 2.2.0 build, fixed same day:**

1. **Choppy recorded audio — round 1.** `VoiceRecorder.cs`'s native waveIn buffer-ready callback
   dispatched each filled buffer independently to the thread pool
   (`ThreadPool.QueueUserWorkItem`), with no ordering guarantee between them — but
   `VoicePcmAssembler.Push` is a strictly sequential PCM stream, so two buffers processed out of
   order scrambles the recorded audio. Fixed by routing every buffer through one dedicated FIFO
   processing thread instead (mirroring the pattern already used on Android's
   `VoiceRecorder`/`VoicePlayer`), with careful `Stop()`/`Dispose()` draining so neither the tail
   of a recording gets silently dropped nor native buffer memory gets freed while the processing
   thread might still be using it.
2. **Sender sees their own sent voice message as a plain file.** `ChatWindowMessages.cs`'s
   `MessageCard` gate dropped the `!mine` condition, mirroring Android 2.2.2's identical fix — the
   sender now sees a proper voice-message player for a message they just sent, reversing the
   original W07 design choice. **Confirmed fixed by the user.**
3. **Choppy audio — round 2, the real root cause, in playback rather than capture.** After round
   1's recorder fix, the user reported the audio was still cutting out. `VoicePlayer.cs`'s
   `FillQueueLocked` refilled the fixed 4-slot native buffer pool by iterating the list from the
   start on every call, tracking only a *count* of in-flight buffers (`buffersInFlight`) rather
   than which specific slot was actually free — after more than one buffer had completed, this
   could pick a slot that was still genuinely queued/playing in the driver and overwrite it with
   fresh data mid-flight, corrupting live audio output. This is the bug round 1 should have looked
   for but didn't (the initial diagnosis wrongly assumed playback's buffer refill was inherently
   order-independent and therefore safe — true for *which chunk of `wav` comes next*, false for
   *which native buffer slot is safe to reuse*). Fixed by giving each buffer slot its own explicit
   `Busy` flag, checked by `FillQueueLocked` and cleared by the completion callback (and defensively
   by `Seek()`, in case a driver doesn't fire a completion callback for every buffer a
   `waveOutReset` flushes).
4. **WhatsApp-style icon buttons and a real seek bar**, matching Android 2.2.3/2.2.x: Play/Pause
   buttons now show `▶`/`⏸` glyphs (`ChatWindowVoicePlayback.cs`'s `PlayIcon`/`PauseIcon`) instead
   of text, and the fixed ±10s buttons are replaced by a draggable `TrackBar` scrubber
   (`BuildSeekBar`/`UpdateSeekBar`) wired to `VoicePlayer.Seek(long)`'s existing absolute-position
   API, in both `ChatWindowVoiceCard.cs`'s received Playable row and `ChatWindowVoice.cs`'s
   own-draft preview row. Since the button's icon always reflects `VoicePlayer.Playing`'s live
   state (already `false` the instant a clip finishes), it settles back on `▶` on its own — no
   separate "stuck on Pause" bug existed here to fix, but this was explicitly called out by the
   user as a requirement given Android's history with exactly that bug.

`dotnet build windows/LanMessenger.csproj -c Release` passed (0 errors, the one pre-existing
benign `CS1998` warning in `ChatWindowVoice.cs` noted above). Published framework-dependent via
`dotnet publish -c Release -o outputs/LanMessenger-Windows-2.2.0` (matching every prior Windows
release's shape — requires .NET Desktop Runtime 9) and zipped: `outputs/LanMessenger-Windows-2.2.0.zip`
(2,849,138 bytes, SHA-256 `40a7f09b…`; manifest: `outputs/SHA256SUMS-Windows-2.2.0.txt` —
superseded twice; see `PLAN-VOICE-MESSAGES-WINDOWS.md` for every intermediate hash). The native
`tests/WindowsUi` suite needed updating alongside round 4: it asserted the literal strings
`"Play"`/`"Pause"` and clicked a `"Forward 10 seconds"`-named button, both now gone — updated to
check the `▶`/`⏸` glyphs and to drive the new `TrackBar` via `Control.OnMouseUp` (simulating a
real drag-release, the same event `BuildSeekBar`'s handler is wired to). With that update, a full
run against this exact build passed every native Windows UI test, including every Voice Messages
one (arrival/auto-download, Play starting real playback, one-active-player enforcement,
pause/resume toggle, seek not crashing, force-stop-and-finalize on conversation
switch/tray-close/Offline), plus the voice-specific `CsharpHarness` checks (`--voice-check`,
`--voice-device-check`, `--voice-scheduler-check`, `--voice-draft-reconcile-check`) run directly —
the pre-existing `--voice-device-check` "double `Dispose()` after `Stop()`" case caught a real
non-idempotency bug in round 1's recorder fix (`BlockingCollection.Dispose()` isn't itself safe to
call twice) before it shipped. The full `tests/run.ps1` suite's own 16-member `group_membership.py`
stress scenario hit its already-documented, environmental TLS-handshake flake partway through
(reproduced in isolation, unrelated to any of this work) before ever reaching the native UI suite
in this pass — the voice-specific and native-UI checks above were run directly instead, rather
than waiting through the full suite's own long tail. As noted above, W11's manual two-device
physical acceptance is still **Pending** — three of the four items above came from the user's own
device testing of earlier 2.2.0 builds, not from any automated suite, which is exactly the class
of bug that Pending note has always been flagging as a real risk.

## Implemented

- **2.0.0** (foundation, still current): group membership is no longer fixed after creation. `Group.Members` is now the live active roster (it shrinks when someone leaves, grows when the owner adds someone) with a `MembersVersion` counter; changes propagate to every active member as a full snapshot (new `LM4\tMEMBERSUPDATE`/`MEMBERSUPDATEACK` frame), adopted whenever strictly newer — so a device offline through several changes catches up in one step. `GROUP` invites send the plain old 6 fields while a group's version is still 0 (so an unrelated, not-yet-updated device is genuinely unaffected) and 7 fields (with the version) once it's actually been mutated; the receiving dispatch accepts either. Any owner-initiated growth (`AddMember`, used by `ReinviteMember`) requires a **fresh** `LM4\tCAPS` capability query (mirroring the existing `FILECAPS` pattern) of everyone who'd be in the group afterward — never a cached/past result — refusing outright with a clear error if anyone doesn't answer. Leaving is never gated by this. Already-saved groups (the old `Group.Left`-overlapping shape) migrate once automatically on load. `ShowMembers` sources departed members from the `AllKnownMembers` API instead of `Group.Left`, its Re-invite button runs off the UI thread since `ReinviteMember` does real network I/O, and each active member's row carries a "Synced"/"Catching up" tag (comparing `MemberAckedVersion(groupId, peerId)` against `MembersVersion`) — never implying the whole group is consistent. See [PLAN-GROUP-MEMBERSHIP.md](../PLAN-GROUP-MEMBERSHIP.md).
- **2.0.1**: 2.0.0 briefly also shipped a join-request feature (any verified contact of a group's owner could ask to join by ID, with an owner accept/ignore queue in the Members dialog). **That feature has been removed** by explicit request — `RequestJoin`/`AcceptJoinRequest`/`IgnoreJoinRequest`, the `JOINREQUEST`/`JOINREQUESTACK` wire frames, the `J`/`Q` storage rows, and the Members-dialog queue/Request-to-join dialog are all gone. The mutable-membership foundation above (AddMember, capability checks, migration, Re-invite, sync status) is untouched. A saved data file that still has old `J`/`Q` rows from before the removal loads fine (those rows are now silently skipped rather than rejected). Two unrelated UI changes landed alongside the removal: `DeleteConversationConfirm`'s context-menu item and dialog now say "Leave group" (not "Delete conversation") for a group, with wording that no longer incorrectly claims verification is revoked — a group action was always just a leave, never a real delete; and a new "Leave group" toolbar button (next to Members in the chat header, enabled only for a group) makes the same action reachable from inside an open group chat, not just from the conversation list.
- **2.1.0**: group ownership can now be handed off, closing the 2.0.1 gap noted above. New `TransferOwnership(groupId, newOwnerId)` — owner-only, requires a fresh live `CAPS>=2` from *every* current active member (not just the incoming owner; refuses outright, nothing mutated, if anyone can't support it), and refuses if any member hasn't yet acked their first invite (the `GROUP` frame's `a[3]!=sender` invariant makes it impossible to deliver a first-time invite "on behalf of" a different owner mid-handoff). `Group.Owner` updates immediately, everywhere — the tricky part is that the *old* owner must stay the delivery/leave-acceptance authority for this one change (not the new owner) until every other member has actually caught up, tracked via a new durable `pendingOwnershipHandoff` set (persisted as a new `O` storage row) that survives a restart; `Deliver()`'s broadcast gate and `HandleLeave`'s accept check are both widened to check it alongside `Group.Owner==Id`. `MEMBERSUPDATE` gains a 6th (owner) field once a group has ever transferred (tracked via a new persisted `EverTransferredOwnership`/`T` row, same one-way shift as `GROUP`'s existing 6-vs-7-field split); `CAPS` bumps its reply from `1` to `2`; `AddMember` requires `CAPS>=2` (not just `>=1`) for any group that's ever transferred, since the wire shape has permanently changed for it. `DeleteConversationConfirm` shows a "Choose a new admin" picker instead of the normal confirmation when the owner tries to leave a non-empty group; the conversation-list row and chat heading both show "Leaving — waiting for members to catch up" (composer/send/leave-button disabled) until the deferred departure actually completes. See [PLAN-GROUP-OWNERSHIP-TRANSFER.md](../PLAN-GROUP-OWNERSHIP-TRANSFER.md) — including why a simpler "wait for just the new owner" design (an earlier draft, caught on external review before any code was written) doesn't work, and the accepted limitation that remains (a member who never comes back online blocks the departure indefinitely).
- 0.8.12: deleting a contact now also notifies them. `DeleteConversation`/`DeleteAllData` queue a peer id in a persisted `forgotten` set; `Deliver()` sends them a new `LM4\tFORGET` frame (retried until acked, same shape as `SEEN`/`SEENACK`) whenever that peer is next reachable, which calls `Revoke` on their side and raises a new `Forgotten` event — wired in `Program.cs` to a tray notice next to the existing `Received` wiring. Deleting a group is still a leave (unaffected for other members), but it now queues a `LM4\tLEAVE` frame to the group's owner (persisted `pendingLeaves`, keyed by the owner id captured before the local group record is dropped); the owner records the departed member in a new `Group.Left` field (a 7th, optional, backward-compatible `G` storage field) and `Deliver()`'s invite-resend loop skips anyone in `Left`. `ShowMembers` (`ChatWindowDialogs.cs`) now shows departed members distinctly with an owner-only "Re-invite" button, calling the new `ReinviteMember(groupId, memberId)`, which clears `Acknowledged`/`Left` for that id so the next delivery cycle resends the `GROUP` invite and they rejoin with the same member list — no re-verification needed. An old build that doesn't understand `FORGET`/`LEAVE` simply never acks; the sender keeps retrying rather than erroring.
- 0.8.11: right-click a conversation or group row for a "Delete conversation" option — clears its history/attachments and, for a contact, also revokes verification and removes the peer record entirely (a group is left). A rediscovered forgotten contact reappears as a brand-new, unverified device. A new "Delete app data" toolbar button wipes every conversation/contact/group/attachment on the device while keeping identity, display name and profile picture. Both ask for confirmation first. `PeerEngine.cs` also went through a purely internal file-split refactor in the same window (`ChatWindow` split across `ChatWindowRender.cs`/`ChatWindowMessages.cs`/`ChatWindowAttachments.cs`/`ChatWindowDialogs.cs`/`ChatControls.cs`; `PeerEngine` split into `Storage.cs`/`Avatars.cs`) — no behavior change, not user-visible.
- 0.8.10 hotfix: a conversation first opened with fewer than 10 messages now grows its visible window up to 10 as messages arrive. Empty-chat hints are removed before redraw/switch, preventing duplicate text. Native UI regressions and an independent five-to-six-message reproduction passed.

- Messaging, groups, device verification, blue/gray presence, attachment draft before Send, and local chat clear.
- Automatic inline ordinary photos within preview/codec limits.
- Since 0.8.7: choose destination before manual Download, stream directly to one receiver copy, open the saved file, and resume at 256 KiB boundaries.
- Fast file payload is plaintext on a separate TCP data connection. Authorization and device verification remain on TLS control; ordinary file payloads use TLS.
- Since 0.8.8: reduced photo/card repaint artifacts during scrolling and chat switching. Unchanged cards stay mounted during status updates.
- Background receipt while hidden in the Windows tray; Exit stops the process. Data is at `%LOCALAPPDATA%/LanMessenger`.
- Since 0.8.9: opening a conversation shows the newest 10 messages; scrolling to the top loads 20 older per step; switching away and back preserves visible count, loaded history, and scroll position. A 16 MiB LRU reuses decoded attachment thumbnails across chats (non-image files are skipped without a read), and live cards for up to 3 recent chats are retained and reattached. See [PLAN-CHAT-PERFORMANCE.md](PLAN-CHAT-PERFORMANCE.md).

## Not mirrored from Android

- No attachment-thumbnail LRU cache across chats — resolved in 0.8.9 (16 MiB `ThumbnailCache`, reference counted so eviction never disposes an image a live or cached card still displays).
- Android's daily upload accounting, 30/20/10 MiB/s tiers, and profile usage display are absent by the earlier Android-only scope.
- No built-in camera capture; users can attach an already saved image.
- Legacy encrypted auto-image cache still uses FETCH and 100 MiB parts rather than Android FETCHSTREAM. The new manual direct path is separate.
- Engine message scanning in memory is not an index: `PeerEngine.Messages` scans engine-held records, and rendering the newest 10 does not make the query inspect only 10 records. Measured open times stay single-digit ms up to 112 messages, so no engine recent-message index was added in 0.8.9 (plan W07).

## Verification and open checks

- Mutable group membership foundation: build (0 warnings, 0 errors) and the full `tests/run.ps1` suite passed, including `tests/group_membership.py` (cross-platform) — convergence on add/remove/re-invite, a device offline through several changes catching up in one step, a group shrinking to just the owner and regrowing past the creation-only `<3` floor, a live (never-cached) capability check that a reachable-but-uncooperative or genuinely unreachable peer both fail identically, an already-confirmed member later going uncooperative blocking further growth until they cooperate again, leaving never gated by anyone's capability, migration of an old-shape saved group on both platforms, and the 16-member cap enforced against the live roster (a real 16-real-process scenario, kept from the earlier join-request test coverage and re-pointed at direct `REINVITE` add instead of the now-removed accept-a-request path); plus `tests/group_migration_broadcast.py`, verifying the migration-triggered broadcast reaches a real, still-running second device with its own stale copy. No manual click-through of `ShowMembers`'s updated departed-member/Re-invite display was performed in this environment.
- 2.0.1 join-request removal + Leave-group relabel (list + in-chat) + Hide-groups toggle: build (0 warnings, 0 errors) and the full `tests/run.ps1` suite passed. Removing `AddMember`'s join-request-auto-clear hook and `DeleteAllData`'s join-request snapshot/clear/rollback fragments required surgical edits (not blanket deletion) to avoid disturbing the surrounding capability-check-then-mutate-then-save structure — verified byte-for-byte behaviorally unchanged by `group_membership.py`/`group_migration_broadcast.py` passing unmodified. Porting the 16-member capacity check out of the deleted `tests/join_requests.py` into `group_membership.py` initially dropped a `command_retry` wrapper the original had around the equivalent call (needed for transient contention under 17 real simultaneous processes) — found via a real failure, fixed by adding the same retry-tolerant wrapper back. This environment has a long-lived, unkillable zombie `dotnet` process from earlier in the session that intermittently causes transient TLS/timeout contention specifically under the heaviest 16-17-real-process scenario in a full-suite run (not in the code); every such failure was independently reproduced-and-confirmed-clean on an isolated rerun before being treated as environmental rather than a regression.
- 2.1.0 group ownership transfer: build (0 warnings, 0 errors) and the full `tests/run.ps1` suite passed, including the new `tests/ownership_transfer.py` — a basic transfer converges everywhere (every member's own owner field updates, not just the new owner's) and the old owner's departure completes automatically once everyone has caught up, after which the new owner can grow the group; a transfer is refused outright (owner unchanged, no pending handoff, no version bump) if any member can't answer a fresh `CAPS>=2`; transferring to yourself or a non-member is refused; the pending handoff (and its durable `O` row) survives the old owner's own restart and still completes afterward; a transfer is refused while any member is still mid-onboarding and succeeds once they finish. Not built: a dedicated test for a still-lagging member's `LEAVE` arriving specifically during the pending window (the `HandleLeave` widening) — reliably forcing that exact race needs an artificial mid-flight pause this harness's process-level start/stop granularity can't provide; the surrounding convergence scenarios already exercise the same code path indirectly. No manual click-through of the new "Choose a new admin" picker or "Leaving — waiting for…" state was performed in this environment.
- 0.8.12 (forget-notice / group re-invite): build (0 warnings, 0 errors) and the full `tests/run.ps1` suite passed, including the extended `tests/delete_conversation.py` — contact delete now also asserts the other side's own verification is auto-revoked once the `FORGET` notice reaches them (`VERIFIED` harness command); the group scenario has a non-owner member leave, the owner sees them in `Left` (`LEFT` harness command), re-invites them (`REINVITE` harness command), and confirms they rejoin and receive new group messages again. No manual click-through of `ShowMembers`' new "Re-invite" button was performed in this environment — only the engine methods and their call sites were exercised by the automated suite and a clean build.
- Windows 0.8.11 built (0 warnings, 0 errors) and passed the full test suite (`tests/run.ps1`): all prior C#/Java engine, integration, transfer and native UI coverage, plus the new `tests/delete_conversation.py` (peer forgotten and rediscovered as unverified, group left without disrupting other members, Delete app data wipes conversations/contacts/groups while keeping identity/name/avatar and resetting Android's daily upload usage). No manual click-through of the new Windows context menu / toolbar button was performed in this environment — only the engine methods and their call sites were exercised by the automated suite and a clean build.
- Windows 0.8.10 built and passed native UI tests, including the 0.8.9 coverage: 640x480 colored image; repeated wheel/programmatic scrolling, chat switching, and geometry checks; automatic images; destination cancel/open; notifications; seen receipts; plus the new paging/cache tests T01 (newest-10 open, +20 pages with preserved anchor, full-history load, arrivals, chat-switch card/scroll preservation) and T02 (12-image cache bounds, clear safety, white-box thumbnail hits/retirements/byte release). Final screenshots inspected.
- Before/after measurements via tests/MeasureWindows (loopback): large-chat paint 300→55 ms, cards built 112→10; medium paint 115→60 ms with 10 cards; first-open/revisit single-digit ms before and after (large revisit 13→3 ms). Repeat opens of the 112-message chat: 2-4 ms. Detailed record in outputs: `windows-chatperf-measure/baseline-0.8.8.txt`, `after-final.txt`, `comparison-0.8.8-vs-0.8.9.txt`.
- Windows 0.8.8 UI tests results are kept in `outputs/.build/windows-ui-tests`. The 0.8.8 repaint-fix confirmation on the affected PC/DPI setup is still the user's to confirm.
- Loopback measurements are not Wi-Fi guarantees; large-chat opening and real LAN throughput on actual hardware remain worth a check.

## Handoff pointers

- Voice Messages (Phase 2): `VoiceMessages.cs` (transport-independent PCM/WAV/marker/seek core), `VoiceDrafts.cs` (`partial class PeerEngine` draft registry + `SendVoiceDraft`), `VoiceRecorder.cs`/`VoicePlayer.cs` (`waveIn`/`waveOut` P/Invoke adapters), `ChatWindowVoice.cs` (own-draft record/send UI), `ChatWindowVoiceCard.cs` (receiver cards), `ChatWindowVoicePlayback.cs` (shared one-active-player controller), `Transfers.cs` (`QueueAutomaticMedia`, the renamed/extended scheduler). Tests: `tests/CsharpHarness/VoiceMessagesCheck.cs` (WT01), `tests/voice_architecture_check.py` (WT06). Progress tracker: [PLAN-VOICE-MESSAGES-WINDOWS.md](../PLAN-VOICE-MESSAGES-WINDOWS.md).
- UI: `ChatWindowRender.cs`/`ChatWindowMessages.cs`/`ChatWindowAttachments.cs`/`ChatWindowDialogs.cs`/`ChatControls.cs` (split out of `Program.cs`; `Program.cs` now holds only `Program.Main`, `ChatWindow`'s fields, constructor and `Send`). Delete conversation ("Leave group" for a group, now also intercepted by the ownership-transfer picker)/app data: `ChatWindowDialogs.cs` (`DeleteConversationConfirm`, `ShowTransferOwnershipPicker`, `DeleteAllDataConfirm`), engine side in `Conversations.cs` (`DeleteConversation`, `DeleteAllData`), context-menu label wiring in `Program.cs`.
- Forget-notice / group re-invite: `Conversations.cs` (`forgotten`/`pendingLeaves`, `HandleLeave`, `ReinviteMember`), `Storage.cs` (`F`/`L` rows), `PeerEngine.cs` (`Forgotten` event, `Deliver()`'s `FORGET`/`LEAVE` sending, `Receive()`'s `FORGET`/`LEAVE` dispatch), `Program.cs` (`engine.Forgotten` tray-notice wiring), `ChatWindowDialogs.cs` (`ShowMembers`'s departed-member rows and Re-invite button).
- Mutable group membership foundation: `Conversations.cs` (`Group.MembersVersion`, `departedHistory`, `memberAcked`, `AllKnownMembers`, `AcceptGroup`, `HandleMembersUpdate`, `QueryCapability`, `AddMember`, `MemberAckedVersion`, `SimulateLegacyBuild` test-only field), `Storage.cs` (`G` row's version field + migration, `D`/`V` rows; `J`/`Q` rows from the removed join-request feature are tolerated but ignored on load), `PeerEngine.cs` (`Receive()`'s `CAPS`/`MEMBERSUPDATE` dispatch, `Deliver()`'s versioned group-broadcast loop), `ChatWindowDialogs.cs` (`ShowMembers`'s sync-status rows). Plan: `PLAN-GROUP-MEMBERSHIP.md`.
- Group ownership transfer: `Conversations.cs` (`Group.EverTransferredOwnership`, `pendingOwnershipHandoff`/`PendingOwnershipHandoff`, `TransferOwnership`, `CheckOwnershipHandoffConvergence`, `MemberAckedVersionLocked`, the widened checks in `HandleLeave`/`AddMember`), `Storage.cs` (`T`/`O` rows), `PeerEngine.cs` (`CAPS` reply bump, `MEMBERSUPDATE` dispatch accepting 5-or-6 fields, `Deliver()`'s widened broadcast gate + owner field + convergence-check call), `ChatWindowRender.cs`/`ChatWindowDialogs.cs`/`Program.cs` (the "Choose a new admin" picker and "Leaving — waiting for…" state). Plan: `PLAN-GROUP-OWNERSHIP-TRANSFER.md`.
- New manual transfer: `DirectDownloads.cs`. Legacy/auto-photo transfer: `Transfers.cs`. Storage/avatars: `Storage.cs`/`Avatars.cs` (split out of `PeerEngine.cs`).
- Tests from repository root: `tests/WindowsUi` (includes paging T01 and cache T02), `tests/MeasureWindows` (chat-performance harness), `tests/direct_downloads.py`, `tests/transfers.py`, `tests/large_transfer.py`, `tests/delete_conversation.py`, `tests/group_membership.py`, `tests/group_migration_broadcast.py`, `tests/ownership_transfer.py` (all cross-platform).
- Results: `D:/LAN-Messenger/outputs/.build/windows-0810-ui`, earlier 0.8.9 results in `windows-ui-tests`, and `windows-chatperf-measure`. Package: `D:/LAN-Messenger/outputs/LanMessenger-Windows-2.1.0.zip` (requires .NET 9 Desktop Runtime).
- Windows 0.8.9 code commit: `2241347`; 0.8.10 hotfix, the 0.8.11 file-split/delete-conversation work, the 0.8.12 forget-notice/group-re-invite work, the 2.0.0 group-membership foundation + (later removed) join-request layer, the 2.0.1 join-request removal + Leave-group relabel, and the 2.1.0 group ownership-transfer feature committed locally on `master`, not pushed.

## Next planned work

- Confirm the 0.8.8/0.8.9 rendering and paging behavior on the affected PC/DPI setup.
- Optional: an engine recent-message index if real-device measurements later show scanning dominating open time (deferred by plan W07 with recorded evidence).
### WVC-T03 (Real audio transfer - device acceptance)
- Evidence template: windows/video-calling/EVIDENCE-WVC-T03-REAL-AUDIO.md
- Status: In Progress (awaiting two-device LAN test execution)
- WVC-06/T02: Partial - physical CoreAudio/device acceptance pending with documented HELLO/READY/CALLCONNECT trace
