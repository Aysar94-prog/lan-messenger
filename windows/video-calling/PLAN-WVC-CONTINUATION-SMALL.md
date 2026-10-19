# Small-task plan: Continue WVC-06 then WVC-T02

Date: 2026-10-05
Status: Planning only - do not implement until approved
Source: D:\LAN-Messenger\source
Base commit: 3943a59 (bridge crash repair), tests documentation 902e92d
Windows Release build: 0 errors (post-repair). Full tests/run.ps1: exit 0.

## Context
- Bridge repair complete with preserved artifacts (old DLLs, dumps, build manifests). 
- NativeBridge 39/39, NativeAudioReadiness 24/24 (dummy ADM only), 20 cycles pass.
- call-video: 317 PASS / 0 FAIL. Windows Release: 0 errors.
- Current state: WVC-06 and WVC-T02 remain Partial. Video disabled. WVC-08 blocked.
- No physical CoreAudio/authenticated replacement-audio acceptance completed.

## Task breakdown

### Task 1: WVC-06 — Native bridge hardening for real audio (Windows, CoreAudio)
Status: Pending  
Dependencies: None  
Priority: High

**Scope**: Address gaps between current bridge and usable real audio path (as specified in prompt).
Focus areas:
1. CoreAudio integration readiness: verify ADM initialization, COM/thread ownership, start/stop capture/playback.
2. mute/route handling, startup failure and repeated teardown.
3. Ensure no microphone/camera opened during probing or ringing (probe paths). 
4. SDP/offer generation stability; audio transceiver presence before reading observer.

**Acceptance criteria**:
- CoreAudio-related edge cases identified with concrete test coverage plan.
- Bridge does not open mic/camera during probing/ringing (verified by code inspection + test).
- Repeated start/stop/teardown cycles behave correctly (no leaks).
- No change to video path (remains disabled).

**Testing tasks**:
- T1.1: Repeated teardown test (N>=20) with dummy ADM - confirm stable.
- T1.2: Startup failure simulation - verify graceful error handling.
- T1.3: Verify probe paths don't acquire devices.
- T1.4: Document COM apartment/thread requirements.

**Notes**: Dummy ADM only so far; real CoreAudio tests require device, but design for device acceptance later.

---

### Task 2: Fix set-remote behavior (Windows, Both impact limited)
Status: Pending  
Dependencies: Task 1 insights  
Priority: High

**Scope**: Current set-remote interprets SDP as offer only. Verify/apply remote answer, local descriptions, negotiation cycle correctness before claiming successful connection.

**Acceptance criteria**:
- set-remote handles offer and answer correctly per role.
- Local/remote description application validated.
- Negotiation state machine correct (no false success).
- Unit/integration tests cover both directions.

**Testing tasks**:
- T2.1: Offer→Answer cycle validated in audio-only path.
- T2.2: State transitions verified.
- T2.3: Invalid SDP rejected gracefully.

**Notes**: Critical for real audio negotiation. No protocol change.

---

### Task 3: WVC-T02 — Real audio transfer test + HELLO/READY/CALLCONNECT
Status: Pending  
Dependencies: T1, T2  
Priority: High

**Scope**: Real audio transfer test and documented session (HELLO/READY/CALLCONNECT) using the new path. Must not activate video.

**Acceptance criteria**:
- Documented HELLO/READY/CALLCONNECT session trace.
- Real audio transfer evidence captured (device acceptance) without claiming full production.
- WVC-T02 moves from Partial to documented state with evidence.
- No mic/camera opened during probing/ringing.
- Group-membership intermittent failure not assumed fixed.

**Testing tasks**:
- T3.1: Two-device LAN audio test (device acceptance).
- T3.2: Capture SDP exchange, state transitions, timing.
- T3.3: Teardown/reconnect cycle.
- T3.4: Record evidence (logs, not dumps unless needed per policy).

**Notes**: Keep WVC-06/T02 Partial until device acceptance documented.

---

### Task 4: Preserve artifacts & build discipline
Status: Pending  
Dependencies: All  
Priority: Critical

**Acceptance criteria**:
- Old DLLs, dumps, build artifacts preserved (never overwritten).
- Any new build goes to unique folder with source snapshot, manifest, PDB/map.
- No SDK shipping, no release, no Android/protocol changes.
- Local commits only, no push.

**Testing tasks**:
- T4.1: Verify artifact preservation before/after any build.
- T4.2: Manifest includes hashes, compiler flags, SDK tree state.

---

## Overall constraints
- Planning only. Do not change code/build/release until explicit approval.
- Video remains disabled. WVC-08 blocked.
- Separate code execution, automated verification, device acceptance.
- No attribution of crash to environment without evidence.
- UTF-8 handling in Windows base plan noted (do not re-encode).
- Historical intermittent group-membership failure not claimed resolved.

## Approval required
Request approval to proceed to execution phase after reviewing this plan.
