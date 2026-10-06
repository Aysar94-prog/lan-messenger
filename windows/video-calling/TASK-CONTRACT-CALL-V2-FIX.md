# Task Contract — Windows call v2 regressions (Option 1)

Status: **Approved for execution** (user chose Option 1 — keep `VideoEnabled = true`, land T1–T5 as one change).
Level: OAK Level 2 (localized multi-file defect fix, protocol boundary touched on both ports).
Date: 2026-10-05. Diagnosis and evidence: `PLAN-WINDOWS-CALL-REGRESSIONS.md`.

---

## 1. Goal

Restore working two-way calls on both directions now that the CALLCAPS probe succeeds and every call
runs on protocol version 2, without reverting the v2 enabler: accept the transport profile that
SIPSorcery/libwebrtc actually emit, and make an accepted call fail loudly instead of silently
stranding the user in an un-endable `Connecting` state.

## 2. Non-goals

- Do **not** revert or gate `callController.VideoEnabled` (`windows/ChatWindowCalls.cs:38-42`).
- Do **not** revert the HELLO-ordering fix in `windows/PeerEngine.cs:236-238` — it is a genuine bug
  fix, not part of this regression.
- Do **not** touch the native bridge, `CallVideoNativeMedia`, `CallVideoCoordinator`, ICE/DTLS
  negotiation, the audio codec work, or the unrelated dirty-tree items.
- Do **not** fix T6 (clear `callView` on terminal state) or any other observation in PLAN section 7.
- No release build artifact, no `<Version>` bump.
- Android changes are limited to the one mirrored validator clause (port parity, enforced by the
  shared corpus).
- Local commits only; **do not push**.

## 3. Root causes

**Bug 1 — Windows→Android dies with `EndReason.MediaError`.**
`windows/CallVideoProtocol.cs:158` requires the m-line transport to be exactly
`UDP/TLS/RTP/SAVPF`. SIPSorcery 10.0.17 emits `UDP/TLS/RTP/SAVP`. `CallVideoProtocol.Valid` returns
false, so `CallController.SendFrameTo` throws `IOException` at `CallController.cs:667`, and the bare
`catch` at `CallController.cs:544` converts it into `EndCallLocked(MediaError)` — discarding the
exception, which is why no cause ever reached the log.

**Bug 2 — Android→Windows cannot be answered or ended.**
`CallSignaling.Make` (`windows/CallSignaling.cs:207-208`) never allocates `Frame.B`, so
`CallSignaling.Accept` (`:217`) returns a frame with `B == null`. `CallController.cs:222` then does
`accept.B!["media"] = ...` → `NullReferenceException`, thrown *after* `State = Connecting` (`:218`)
and *before* `NotifyCallback` (`:226`). Net effect: no ACCEPT on the wire, no snapshot published, and
all three watchdogs already cancelled. `ChatWindowCalls.cs:150` is fire-and-forget
(`_ = callController.AcceptAsync()`), so the exception is dropped entirely. Same defect at
`CallController.cs:783` (`SendAnswerLocked`, the "Accept with video" path).

Android does not have Bug 2 because `android/.../CallProtocol.java:117` allocates `body` eagerly in
the `Frame` constructor. `android/.../CallSignaling.java:288` relies on that.

**Shared trigger.** `ChatWindowCalls.cs:41 VideoEnabled = true` + the now-working CALLCAPS probe put
every call on v2 for the first time, exposing two latent v2 bugs that were unreachable at v1. All 30
lines of the user's `call-diagnostics.log` show `v2=True`.

---

## 4. Implementation units

### U1 — Accept `SAVP` alongside `SAVPF` (Both ports, one commit)

| | |
|---|---|
| Files | `windows/CallVideoProtocol.cs:158`, `android/src/net/lanmsg/chat/CallVideoProtocol.java:94` |
| Change | Windows: `if (section[2] != "UDP/TLS/RTP/SAVPF" && section[2] != "UDP/TLS/RTP/SAVP") return false;`<br>Android: `\|\| !(section[2].equals("UDP/TLS/RTP/SAVPF") \|\| section[2].equals("UDP/TLS/RTP/SAVP"))) return false;` |
| Constraint | **Exact-string acceptance only.** `TCP/TLS/RTP/SAVPF` and `TCP/TLS/RTP/SAVP` must stay refused — DTLS over TCP changes the transport and this codebase does not implement it. The port-parity warning at `CallVideoProtocol.cs:11-14` requires both files in the same commit. |
| Proves | `dotnet build tests\CsharpHarness\CsharpHarness.csproj -c Release` then `dotnet <out>\CsharpHarness.dll --call-frame-fixture-check tests\video-contract\fixtures <verdict>` exits 0 and accepts the new rows. |

### U2 — Pin the accepted transports in the shared corpus (Both ports)

| | |
|---|---|
| Files | `tests/video-contract/fixtures/call-frames.txt`, `windows/FakeCallVideoMedia.cs:205-228`, `android/src/net/lanmsg/chat/FakeCallMedia.java:81,161` |
| Change | Add `#SAMPLE`s and records: audio `SAVP` (valid), video `SAVP` (valid), video `TCP/TLS/RTP/SAVP` (invalid), video `SAVPX` (invalid). Switch the two fakes to emit `SAVP`, matching what a real stack produces. |
| Corpus format | `name\|expectParse\|expectValid\|payload`; `#SAMPLE NAME<TAB>text` with `@NAME@` substitution. `expectValid` applies only to a parsed `protocolVersion == 2` frame. SDP keeps its CRLF as the JSON escape `\r\n`. |
| Consumers | `tests/video-contract/run.ps1` → `net.lanmsg.chat.SharedFrameFixtureCheck` (Android) and `dotnet CsharpHarness.dll --call-frame-fixture-check` (Windows), then `Compare-Verdicts` diffs the two verdict files three-way against the hand-authored expectation. |
| Why the fakes matter | This is exactly why the suite stayed green: `FakeCallVideoMedia` hardcoded `SAVPF`, so no test ever fed the validator what SIPSorcery emits. Switching the fakes to `SAVP` makes the entire existing Windows suite exercise the real transport. |
| Proves | `tests\video-contract\run.ps1 -CsharpHarness <built dir>` reports `Shared frame corpus : N records agree between Android and Windows`. |

### U3 — Allocate the ACCEPT body in the builder, not in `Make` (Windows)

| | |
|---|---|
| File | `windows/CallSignaling.cs:217` (covers both `AcceptAsync` `CallController.cs:222` and `SendAnswerLocked` `CallController.cs:783`, which share the builder) |
| Change | `public static CallProtocol.Frame Accept(string callId, long seq) { var f = Make(CallProtocol.ACCEPT, callId, seq); f.B = new(); return f; }` |
| Constraint | Do **not** allocate in `Make`. `Make` is shared by every v1 builder. Today `Serialize` (`:23`) guards with `frame.B is { Count: > 0 }`, so an empty body would not reach the wire — but that guard is a property of `Serialize`, not of the builders, and the archived 2.2.42 / current-Android voice path requires v1 frames to stay byte-identical for no benefit. `Accept` is the one builder whose only consumer needs the body, and it is the site Android already gets for free. |
| Proves | New `CallVideoCheck` case asserts `CallSignaling.Accept(...).B != null` and that a v1 ACCEPT still serializes to a root with no `"b"` key. |

### U4 — Stop discarding the exception that kills a call (Windows)

| | |
|---|---|
| Files | `windows/CallController.cs:544`, `:556`, `:562`, `windows/ChatWindowCalls.cs:150`, `windows/ChatWindowCalls.cs:232-238` |
| Change | Replace the three bare `catch { }` handlers with a logged path that records the exception type and message before calling `EndCallLocked(MediaError)`. Observe the `AcceptAsync` task at `ChatWindowCalls.cs:150` and route `IOException` through the existing `ReportVideoOutcome` shape (`ChatWindowCalls.cs:204-213`), which already catches `IOException` and shows a message. Extend the diagnostic line at `:234-236` with a `cause=` field. |
| Sink decision | Reuse the existing `callDiagnosticPath` file (`ChatWindowCalls.cs:21`). A second log file would fragment exactly the evidence needed to debug this class of failure, and the plan's whole value came from one file. Note the existing writer swallows its own exceptions (`:238`) — keep that, a broken disk must not take the app down, but the `cause=` field must be written by the same append. |
| Constraint | Do not change the v1 frame sequence. `catch (Exception e)` must not swallow `OperationCanceledException` semantics or alter `EndCallLocked` ordering. |
| Proves | New `CallVideoCheck` case drives a failing `SendFrameTo` and asserts the call reaches a terminal state with `EndReason.MediaError` *and* that a cause string was recorded. |

### U5 — An accept must never strand a call (Windows)

| | |
|---|---|
| Files | `windows/CallController.cs:218-228`, `:778-794`, `windows/ChatWindowCalls.cs:150` |
| Change | Reorder so no failure path can leave the session in `Connecting` with every watchdog cancelled. Specifically: `try { SendFrameTo(t!, accept); } catch { }` at `:223` already swallows a send failure into a silent state change with a media timeout that may already have been replaced — the accept must either be on the wire or the call must end. Do not cancel the ring watchdog (`:220`) until the ACCEPT is successfully sent; on failure, `EndCallLocked` with a send failure reason so the peer is not left ringing. Mirror the same guard in `SendAnswerLocked` (`:778-794`). |
| Constraint | The v1 frame sequence must not change. `AcceptAsync` for v1 emits exactly the same ACCEPT bytes as before. |
| Proves | New `CallVideoCheck` case: a transport whose `Send` throws on the ACCEPT frame must end the call terminally and republish a snapshot, and `Snapshot()` must never report `Connecting` after the accept attempt returns. |

### Dependency graph

```
U1 ──┐
     ├──> U2 (corpus must accept what U1 now accepts)
U3 ──┼──> U4 ──> U5
     │
     └──> (all) ──> final verification ──> manual device gate
```

U1 and U3 are independent and touch disjoint files. U2 depends on U1 (a `SAVP` row fails before U1
lands). U4 depends on U3 (otherwise the accept throws before the logged path is ever reached). U5
depends on U4.

---

## 5. Cross-cutting constraints

1. **Port parity is enforced by the shared corpus.** Any change to `CallVideoProtocol` on either
   port must land in the same commit, per the warning at `CallVideoProtocol.cs:11-14`.
2. `SendFrameTo` clears the body for RINGING/DECLINE/BUSY/CANCEL/HANGUP/PING/PONG
   (`CallController.cs:665`) and allocates with `f.B ??= new()` (`:642`). Do not disturb either.
3. `CallProtocol.Frame.B` is nullable and every reader guards with `frame.B != null && …`. U3 removes
   one null but must not remove those guards — they are what make the nullable type honest.
4. The Android/Windows body-allocation divergence is the root of Bug 2. Fixing `Accept` on Windows
   restores parity with Android's eager `Frame` constructor; the divergence itself is out of scope.
5. **Dirty working tree.** 17 modified files, +869/−47 on top of `902e92d`, including the v2 enabler
   and unrelated audio/native/UI work. U1–U5 plus the v2 enabler form one inseparable commit under
   Option 1; do not try to isolate them. Nothing else from the dirty tree belongs in these commits —
   stage by path, never `git add -A`.
6. No release, no version bump. Local commit only, no push.

## 6. Acceptance criteria

### Automated (all must pass, 0 failures)

```
dotnet build windows\LanMessenger.csproj -c Release --configfile NuGet.Config
dotnet build tests\CsharpHarness\CsharpHarness.csproj -c Release --configfile NuGet.Config -o <out>
dotnet <out>\CsharpHarness.dll --call-video-check
dotnet <out>\CsharpHarness.dll --call-frame-fixture-check tests\video-contract\fixtures <verdict>
tests\video-contract\run.ps1 -CsharpHarness <out>
tests\run.ps1
```

`--call-video-check`, `--call-frame-fixture-check`, `--call-capability-fixture-check` and
`--voice-check` are the real flag names, read from `tests/CsharpHarness/Program.cs:7-19`.

### Manual — requires two physical devices (a Windows PC and an Android phone on the same LAN)

No automated substitute can observe the cross-device path, and the absence of `MediaError` is **not**
proof of a working call.

- MT1 Windows→Android voice call connects; audio is two-way.
- MT2 Android→Windows incoming call can be accepted and connects.
- MT3 Hang-up from Windows ends the call on **both** devices.
- MT4 Hang-up from Android ends it on Windows.
- MT5 "Accept with video" no longer silently no-ops.
- MT6 The new `cause=` field appears in `call-diagnostics.log` and names the real failure if any
  occurs.

## 7. Risks to re-verify during implementation

- **The disappearance of `MediaError` is not a fix.** Once `SAVP` is accepted, a real
  SIPSorcery/libwebrtc media session begins on a path that blocks the UI thread —
  `new RTCPeerConnection(null)` measured 192–1389 ms inside `CallController.gate`, reachable from a
  WinForms button handler via `ChatWindowCalls.cs:150 → AcceptAsync → StartMediaAsCalleeAsync →
  StartMediaLocked`. New symptoms are expected; report them, do not paper over them.
- `WebRtcCallMedia` is never exercised by the harness because `ICallMedia` is an interface and the
  harness substitutes `FakeCallVideoMedia`. A harness pass says nothing about SIPSorcery.
- `CameraEligible = _ => false` (`ChatWindowCalls.cs:43`) currently masks the second
  `CallSignaling.Accept` defect site at `CallController.cs:783`. U3 fixes the builder so both sites
  are safe, but the `SendAnswerLocked` path stays untested until camera eligibility is real.
- Do not treat "the log no longer shows `MediaError`" as MT1–MT4 passing.

## 8. Implementation TODO

| ID | Unit | Platform | Depends on | Status |
|----|------|----------|-----------|--------|
| U1 | Accept `SAVP` in `ValidSdp`, both ports | Both | — | **Done** |
| U2 | `SAVP` corpus rows + fakes emit `SAVP` | Both | U1 | **Done** |
| U3 | Allocate body in `CallSignaling.Accept` | Windows | — | **Done** |
| U4 | Log instead of discarding; observe `AcceptAsync` | Windows | U3 | **Done** |
| U5 | Accept cannot strand a call | Windows | U4 | **Done** (no automated test — see §9) |
| V | Automated verification block | Both | U1–U5 | **Done**, one pre-existing unrelated failure |
| M | Manual two-device block MT1–MT6 | Both | V | **Pending — needs two devices** |
| S | Update `windows/STATUS.md` + `android/STATUS.md` + `PROJECT_STATUS.md` | Both | V | **Done** |

## 9. Execution record

Delivered 2026-10-06. All of U1–U5 landed together with the v2 enabler, as Option 1 requires.

**Deviation from the contract, deliberate.** The contract's U3 rationale claimed that allocating the
body in `Make` would make `Serialize` emit `"b":{}` for every bodyless frame. That was wrong:
`CallSignaling.cs:23` guards with `frame.B is { Count: > 0 }`, so an empty body never reaches the wire
either way. The fix was still made in `Accept` rather than `Make`, for a different reason — `Make` is
shared by every v1 builder and changing it would need justification the bug does not require — but the
stated justification was incorrect and is corrected here.

**Deviation from the contract, forced.** The contract promised automated coverage for U4 and U5. U4's
cause-recording is covered indirectly (the `SAVP` fake makes 18 pre-existing checks fail against the
old validator). U5 has **no** automated test: `AcceptAsync`/`SendAnswerLocked` are reachable only
through `OnInvite`, which refuses a peer absent from `PeerEngine.Peers` with a matching fingerprint,
and establishing one requires two live `PeerEngine`s paired over a real loopback connection. That is
the machinery `tests/CsharpHarness` exists to avoid, so it was not added here. U5 rests on the device
gate.

**Test-quality evidence.** Reverting only the two source clauses (U1's `ValidSdp` line and U3's
`Accept` body) and rebuilding produces **18 failing pre-existing checks plus 4 failing new ones** —
the new `accept-wire` section and every coordinator/media-seam check that feeds the fake's SDP through
the validator. The suite is a genuine regression detector for both defects, not merely consistent with
the new code.

**Test counts.** `--call-video-check` 324 → 338. Shared frame corpus 107 → 113 records; capability
corpus 38, unchanged. Both verdicts agree across Android and Windows.

**Pre-existing failure, not fixed here.** `tests/group_membership.py` fails with
`Can't change this group's membership: FullMember… hasn't updated to a version that supports it`. It
reproduces identically in a clean `git worktree` at HEAD `902e92d` with no working-tree changes, so it
predates this work and is unrelated to it. Every test after it in `tests/run.ps1` passes when run
individually, and `tests/WindowsUi` passes once the running app releases port 43872.