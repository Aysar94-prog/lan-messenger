# Windows video-call completion plan

Date: 2026-10-06  
Status: **Execution authorized by the user on 2026-10-06; device/release gates remain open**
Primary platform: **Windows**; tasks marked **Both** are limited to Android interoperability.  
Source: `D:\LAN-Messenger\source`  
Release outputs: `D:\LAN-Messenger\outputs`

## Goal

Finish and validate one-to-one video calling on Windows against the current Android app, then produce
a Windows release candidate only after automated and physical gates pass. This plan does not add group
video, screen sharing, recording, relay/TURN, call hold, Android Wi-Fi repair, or unrelated parity work.

## Starting point and evidence

- Local commit `f4f3791` contains the independently buildable call-v2 regression repair: Windows and
  Android accept SDP `SAVP`/`SAVPF`, and Windows `ACCEPT` has a non-null body.
- That committed subset built with 0 errors and passed `--call-video-check` **317/317**.
- The working tree contains a much larger, mixed, uncommitted Windows implementation (native bridge,
  managed adapter, call controller/UI, recipient controls, tests and status records). It must be
  reviewed and separated before any new implementation is layered on it.
- `windows/LanMessenger.csproj` is still version `2.2.42`; no new Windows release is claimed.
- The existing MT1–MT6 two-device checklist remains the first physical gate for the repaired v2 call
  path. No physical Windows video acceptance is currently claimed.
- Keep the Android signing key private and preserve upgrade compatibility. Never archive `.private`.
- Local commits only. Do not push, rename branches/remotes, or create another source repository.

## Status definitions

- **Pending**: no execution accepted yet.
- **Ready**: prerequisites and procedure are known, but execution has not begun.
- **Blocked**: a dependency or required device is absent.
- **Done**: implemented and supported by the acceptance evidence named in the task.

Implemented code, automated verification, and physical acceptance must always be reported separately.

## Small-task execution plan

### WVR-01 — Freeze and classify the working tree

- Platform: **Windows** (with existing status-only Android changes preserved)
- Status: **Ready**
- Dependencies: none
- Work:
  - Record `git status`, `git diff --stat`, current commit and hashes of relevant native binaries.
  - Classify each modified/untracked path by feature and ownership: native media, real audio, video
    receive path, controller hardening, recipient controls, tests, evidence/status, unrelated files.
  - Compare the working implementation with `f4f3791`; do not overwrite or silently absorb earlier
    work. Keep `.ai-planner` artifacts out of application commits.
- Acceptance criteria:
  - Every relevant dirty path has an owner/feature classification and intended commit group.
  - No user change is lost, reverted or mixed into an unrelated commit.
- Testing tasks: read-only diff review; no build required.
- Notes: this gate precedes all edits because the tree currently contains about 1,189 changed lines
  across 17 tracked paths plus relevant untracked files.

### WVR-02 — Consolidate the call-v2 hardening and its tests

- Platform: **Windows**
- Status: **Pending**
- Dependencies: WVR-01
- Work:
  - Retain accept-send ordering, `NetworkFailure` handling, `accept-failed` diagnostics and per-call
    `LastFailureReason` reset at both outgoing start and incoming invite.
  - Integrate the 14 `AcceptWireChecks` assertions without accidentally requiring unrelated UI files.
  - Ensure every terminal diagnostic line contains `cause=`, clean endings use `cause=none`, and
    non-terminal lines do not carry a stale cause.
- Acceptance criteria:
  - Failed ACCEPT cannot strand the window in ringing/connecting.
  - A later clean call cannot inherit an older failure reason.
  - The harness compiles from the intended commit group, not only from the dirty tree.
- Testing tasks:
  - Release build, focused call/video harness, negative test against the old ACCEPT builder.
  - Deterministic diagnostics assertions for clean and failed call lifetimes.
- Notes: keep this commit logically separate from native camera/renderer work.

### WVR-03 — Close the native bridge and managed-adapter contract

- Platform: **Windows**
- Status: **Pending**
- Dependencies: WVR-01, WVR-02
- Work:
  - Audit ABI versioning, command bounds, callback lifetime, threading/COM ownership, repeated teardown,
    stale callbacks and DLL discovery.
  - Confirm offer/answer role handling and ICE delivery in both directions.
  - Preserve independent G722 audio: a video failure ends video only; an audio/signaling failure ends
    the call according to the existing contract.
- Acceptance criteria:
  - No callback reaches disposed managed/native state.
  - At least 20 create/start/stop/destroy cycles complete without crash, leak or handle growth.
  - Invalid command, malformed SDP and missing DLL failures are bounded and user-visible.
- Testing tasks:
  - Native ABI harness, native audio-readiness harness, call/video harness.
  - Failure injection for initialization, remote SDP, ICE, and teardown.
- Notes: do not replace the production SIPSorcery audio path unless the plan evidence proves the native
  replacement gate; video readiness does not require an unproven voice migration.

### WVR-04 — Complete real Windows camera capture

- Platform: **Windows**
- Status: **Pending**
- Dependencies: WVR-03; physical webcam for final acceptance
- Work:
  - Replace synthetic-only capture with a production camera source, enumerated explicitly.
  - Implement permission/busy/unplug handling, bounded format conversion, rotation/orientation and safe
    stop/restart. Do not label desktop cameras “front” or “rear” unless the device exposes trustworthy
    semantics.
  - Preserve the invariant that probing and ringing never acquire the camera.
- Acceptance criteria:
  - The selected webcam produces VP8 frames after explicit consent only.
  - Denied permission, busy camera and unplug cause an honest video-only failure while audio continues.
  - A second call can reopen the camera after normal and abnormal teardown.
- Testing tasks:
  - Fake-source unit tests plus physical default-camera, alternate-camera, busy, unplug and repeat-call
    checks.
- Notes: if only one camera is available, hide switching rather than presenting a non-functional control.

### WVR-05 — Complete remote rendering and frame ownership

- Platform: **Windows**
- Status: **Pending**
- Dependencies: WVR-03
- Work:
  - Feed decoded remote frames into the WinForms video surface with bounded ownership and UI-thread
    marshaling; prevent use-after-free and unbounded queues.
  - Define aspect-fit/crop behavior, resize handling, black/empty-frame behavior and disposal.
  - Keep the local preview clamped inside the call window at supported DPI values.
- Acceptance criteria:
  - Moving Android camera video renders continuously on Windows without blank/stale frames.
  - Memory remains bounded and the UI remains responsive during resize, minimize/restore and teardown.
  - Audio remains continuous if rendering fails or is deliberately disabled.
- Testing tasks:
  - Synthetic frame ownership/lifetime tests.
  - Physical 100/125/150/200% DPI checks and a 10-minute render/resize stress run.

### WVR-06 — Finish video call UX and consent semantics

- Platform: **Windows**
- Status: **Pending**
- Dependencies: WVR-02, WVR-04, WVR-05
- Work:
  - Enable video capability only when the production media backend is installed and usable.
  - Wire camera eligibility to real availability/permission instead of a hard-coded `false`.
  - Make **Accept with video** attempt video and, if unavailable, answer as voice with an accurate
    message. It must not leave the call ringing and must not imply a fallback already happened when it
    did not.
  - Validate start-video, stop-video, decline-video, local preview and caller/callee state labels.
- Acceptance criteria:
  - Voice accept, video accept and voice fallback each produce one unambiguous state transition.
  - No camera opens before consent; no failed video action kills healthy audio.
  - Buttons cannot issue duplicate/stale actions and remain keyboard/DPI accessible.
- Testing tasks:
  - Controller state-machine tests plus native Windows UI click-through.
  - Explicit camera-available, denied, busy and removed cases.
- Notes: the voice fallback is the recommended product decision. If the user chooses strict refusal
  instead, change the text and keep a separate working voice Accept action; never leave ambiguous state.

### WVR-07 — Validate trusted recipient controls

- Platform: **Both**
- Status: **Pending**
- Dependencies: WVR-06
- Work:
  - Retain caller-only, Connected-only controls gated by a fresh certificate-bound Slave-direction
    grant. Recheck at click time and hide immediately when refresh/revocation fails.
  - Validate remote camera on/off and available camera selection without inventing unsupported desktop
    front/rear semantics; validate speaker control only where Windows can honestly apply it.
- Acceptance criteria:
  - No control appears or executes without the correct fresh grant.
  - Revoke, expiry, identity/certificate change, Offline and disconnect remove authority promptly.
  - Unsupported route/camera operations report refusal instead of false success.
- Testing tasks:
  - The existing `CallRecipientControls` checks, grant expiry/revocation tests, and two-device UI checks.

### WVR-08 — Automated regression and interoperability gate

- Platform: **Both**
- Status: **Pending**
- Dependencies: WVR-02 through WVR-07
- Work: run verification from a clean, reproducible checkout/commit grouping.
- Acceptance criteria:
  - Windows Release build: 0 errors; warnings classified.
  - Native bridge and focused call/video suites: 0 failures.
  - Shared frame/capability corpora agree between Windows, Android and hand-authored expectations.
  - Full `tests/run.ps1` passes, or any pre-existing failure is reproduced at the clean base and recorded
    separately; it may not be described as fixed without evidence.
- Testing tasks:
  - `dotnet build windows\LanMessenger.csproj -c Release --configfile NuGet.Config`
  - NativeBridge, NativeAudioReadiness, `--call-video-check`, `tests/video-contract/run.ps1`,
    `tests/run.ps1`, and Windows UI tests.
- Notes: record exact command, commit, result count and artifact path for every run.

### WVR-09 — Two-device call-v2 regression gate (MT1–MT6)

- Platform: **Both**
- Status: **Blocked — requires Windows PC and Android phone on the same LAN**
- Dependencies: WVR-08
- Work: execute `MT-CHECKLIST-CALL-V2-FIX.md` with a Windows binary containing the fixes and an
  upgrade-safe Android dev build with versionCode greater than the installed code.
- Acceptance criteria:
  - MT1–MT5 PASS; MT6a PASS; MT6b PASS or explicitly “not attempted”.
  - Both call directions reach Connected, two-way audio works, and hang-up clears both devices.
  - No `MediaError` SAVP signature, null-body accept failure, stranded ring, or stale `cause=`.
- Testing tasks: capture Windows `call-diagnostics.log` and Android `adb logcat -s LANCALL` per call.
- Notes: this gate validates the repaired signaling/audio path before video-specific conclusions.

### WVR-10 — Physical Windows video interoperability gate

- Platform: **Both**
- Status: **Blocked — depends on WVR-09 and two devices**
- Dependencies: WVR-09
- Work:
  - Test Windows caller and callee, video accepted initially and added mid-call, each side stopping and
    restarting video, rotation/resize, hang-up from either side and immediate second call.
  - Exercise camera denied, busy, unplugged and network interruption while preserving audio where the
    failure is video-only.
- Acceptance criteria:
  - Android → Windows and Windows → Android show moving video and continuous two-way audio.
  - Consent, grant and role behavior matches the shared contract.
  - No crash, frozen call window, stale in-call state, orphaned camera light or resource leak.
  - A 10-minute call and three consecutive call lifetimes complete successfully.
- Testing tasks: logs from both devices, screenshots where useful, CPU/memory observations and exact
  hardware/app versions.

### WVR-11 — Status reconciliation and local commits

- Platform: **Both** for compatibility/status; code commits remain scoped by platform/feature
- Status: **Pending**
- Dependencies: each completed implementation/test unit; final pass after WVR-10
- Work:
  - Commit coherent units by explicit path. Never use `git add -A` with this mixed working tree.
  - Update `windows/STATUS.md` after Windows changes; update `android/STATUS.md` and
    `PROJECT_STATUS.md` only where actual interoperability/parity evidence changed.
- Acceptance criteria:
  - Status records distinguish source implementation, automated verification and physical acceptance.
  - Commit messages state known gaps and test results; working-tree leftovers remain attributable.
  - Commits are local only unless the user explicitly requests push.
- Testing tasks: `git diff --check`, staged-path review, and post-commit clean-checkout focused build.

### WVR-12 — Windows release candidate and final release

- Platform: **Windows**
- Status: **Blocked — requires WVR-08 through WVR-11 PASS and explicit release approval**
- Dependencies: WVR-08, WVR-09, WVR-10, WVR-11
- Work:
  - Choose the next Windows version, build/publish x64 into a unique staging directory, include the
    required native DLLs/licenses, create manifest and hashes, then package under
    `D:\LAN-Messenger\outputs`.
  - Smoke-test the exact packaged bits; do not infer package acceptance from a source-tree binary.
- Acceptance criteria:
  - Clean-machine launch succeeds with documented runtime requirements.
  - Exact package passes pairing, voice fallback, both video directions, hang-up/restart and legacy
    voice compatibility.
  - Version, size, SHA-256, native dependency inventory and test evidence are recorded.
- Testing tasks: package-content audit, signature/hash verification, clean-install smoke test and one
  final Windows↔Android call using the packaged executable.
- Notes: building a release or changing the shipped version needs a separate explicit user instruction.

## Dependency path

`WVR-01 → WVR-02 → WVR-03 → (WVR-04 + WVR-05) → WVR-06 → WVR-07 → WVR-08 → WVR-09 → WVR-10 → WVR-11 → WVR-12`

WVR-04 and WVR-05 may be implemented independently after WVR-03. Status updates and small local
commits should occur after each accepted unit rather than waiting for the entire chain.

## Definition of “Windows video-call ready”

The project is ready only when WVR-08, WVR-09 and WVR-10 pass with recorded evidence, the status files
accurately reflect that evidence, and the exact packaged Windows candidate passes its smoke test. A
successful build or harness alone is not a video-ready claim.

## Execution authorization

The user explicitly requested execution on 2026-10-06. Source changes, development builds,
tests, the upgrade-safe Android dev candidate and local commits are now in scope. Final release
packaging remains gated by the physical results and separate release approval; push was not requested.

## Execution checkpoint — 2026-10-06

This table supersedes the initial planning statuses in the task cards above. Detailed evidence and
the working-tree classification are in `EVIDENCE-WVR-COMPLETION-20261006.md`.

| Task | Current status | Evidence / outstanding gate |
|---|---|---|
| WVR-01 Windows | Done | Relevant dirty paths classified; prior audio and planner artifacts preserved separately. |
| WVR-02 Windows | Done (automated) | Controller send-failure, ordering, voice fallback and immutable failure-cause regression checks. Device acceptance stays in WVR-09. |
| WVR-03 Windows | Partial | ABI 39/39, managed/native media 138/138, 20 lifetimes and three real ICE/DTLS/VP8 generated sessions. No production native-audio migration or measured leak claim. |
| WVR-04 Windows | Partial | Default physical camera preview and stop/reopen 2/2. Alternate camera selection, busy/unplug physical scenarios remain open. |
| WVR-05 Windows | Partial | Real generated remote VP8 callbacks and bounded UI queue; call UI 9/9. Android image, all-DPI and 10-minute stress remain open. |
| WVR-06 Windows | Partial | Voice fallback, mid-call Accept/Decline video, deadline/negotiation effect wiring and camera-error receive continuity implemented. Two-device UX pending. |
| WVR-07 Both | Partial | Existing fresh-grant visibility/enforcement retained and focused checks pass. Live revoke/control acceptance pending. |
| WVR-08 Both | Partial | Windows build 0 errors; focused call/video 359/359; corpora 113+38 agree. Full suite reproduces known group_membership failure; remaining suites run separately. |
| WVR-09 Both | Blocked by current device configuration | SM-A075F now connected by USB and upgraded to 2.2.71/code98. Its Direct connections selection currently exposes ultra, not Windows Lap; Windows shows phone Offline. |
| WVR-10 Both | Blocked by WVR-09 | No Windows↔Android moving-image or listening acceptance is claimed. |
| WVR-11 Both | Partial | Status/evidence reconciled and explicit-path local development checkpoint; final status reconciliation depends on physical acceptance. Prior audio-only edits remain separate. |
| WVR-12 Windows | Blocked | Await physical gates and explicit final release instruction. |
