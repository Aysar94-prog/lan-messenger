# Voice Messages — Phase 3 Integration (plan-v003, I05-I11 / IT01-IT07) execution progress

Tracks Phase 3 of `.ai-planner/sessions/20260928-025216-b2673f/planning/plan-v003.md`
(Android ↔ Windows Integration). Phase 1 (Android, A01-A11) and Phase 2 (Windows,
W01-W11) implementation is code-complete in source; see `PLAN-VOICE-MESSAGES-ANDROID.md`
and `PLAN-VOICE-MESSAGES-WINDOWS.md`.

**Entry-gate note (honest):** plan-v003 states I05-I09 "cannot begin until **both
complete platform exit gates**, including automated tests, manual/device acceptance,
platform status updates, and their provisional project comparison updates, have
passed." That gate is NOT fully passed: AT03/AT05 were never attempted (no confirmed
Android audio device in this environment), Windows WT03/WT05 exist but Android has no
equivalent, and **manual two-device acceptance is Pending on both platforms** (needs
physical hardware, cannot be performed by an agent). The user explicitly authorized
executing the automatable subset anyway, leaving device acceptance as Not verified.
Nothing below claims the exit gate or interoperability as complete.

**No production-code change in this pass.** Voice interop reuses the existing
encrypted Normal attachment store with no new LM4 frame, by design. All work is a new
integration test plus verification runs. No release built, no push.

## Status

| ID | Task | Status | Notes |
|---|---|---|---|
| I05 | Compare both platforms against every shared fixture and stable failure category | **Done (automatable)** | `CsharpHarness --voice-check` and Java `VoiceMessagesCheck` both run against canonical `tests/voice_messages/vectors/`: **both `PASS=38 FAIL=0 SKIP=30`, identical**. Bit-exact normalized PCM / equivalent metadata, validation, receiver transitions, events, timestamps, seek positions on all testable vectors (30 SKIP are recipe-only/large/non-algorithmic categories on both). |
| I06 | Direct + group voice both directions through encrypted attachment path | **Done (automatable)** | New `tests/voice_interop.py` (wired into `tests/run.ps1` right after `voice_scheduler_android.py`): real `CREATEDRAFT/.../SENDDRAFT` drafts cs→java and java→cs auto-download byte-identical (`FILEHASH`, 3244 bytes for the 5-silent-frame clip); cross-platform group (cs owner, java+cs members) reaches every member byte-identical; queued send survives sender restart; exactly-one-row dedup checked. All PASS. |
| I07 | Identical scheduler workloads on both platforms | **Done (automatable)** | Deterministic fairness checks both pass: `VoiceSchedulerCheck` cs + java each `PASS=4 FAIL=0` (3-consecutive-voice-then-force-image, counter reset, cancel/re-admit). End-to-end `voice_scheduler.py` + `voice_scheduler_android.py` both pass (direct/group/mixed voice+image, neither starves). Exact async admission-ordering edge under saturation remains untested (same known gap as WT04/AT04). |
| I08 | Relay, retention, authorization, membership, clear/delete, expiry, interrupted resume | **Partial (automatable subset)** | Covered: group voice reaches a member set up cross-platform (uses the normal group sync path); receiver `DELETECONV` removes the voice row locally. NOT covered: relay via an offline-then-catch-up third member, expiry purge timing, authorization-loss transitions — need longer multi-process scenarios or device timing; left for device acceptance. |
| I09 | Mixed-version + hostile marked content | **Partial (automatable subset)** | Covered: forged marker (non-WAV bytes under `voice-*.lanvoice.wav`) and mismatched-id valid WAV both deliver as ordinary attachments without crash/loop (fallback, no second fetch). Older-client fallback is structural (no wire change; pre-voice builds never run marker classification and render the file as an ordinary WAV) — same argument as W10/A10, no new code. Adversarial UI-level checks (spoofed content never gains player controls) need real UI, Not verified. |
| I10 | Full regression + repeat arch/fixture checks | **Partial (full suite aborted on user request)** | `tests/voice_architecture_check.py` passes (6/6: both cores dependency-free, no platform-local fixture copies, both runners on canonical path). Voice suites all pass (WT01/AT01, WT02/AT02 incl. crash reconcile variants, scheduler both, reconcile both) plus the new cross-platform `voice_interop.py`. Full `tests/run.ps1` was started, reached `group_membership.py`'s 16-member stress, and was stopped by the user as too slow (log: `outputs/.build/phase3-fullrun.log`, `FULLRUN_EXIT=1` from the kill, not a test failure — the log shows the 16-member scenario passing its assertions up to the abort point). No voice failure was observed at any point before the abort. |
| I11 | Update `PROTOCOL.md` + finalize comparison in `PROJECT_STATUS.md` | **Not started** | Requires I10/IT07 + two-device LAN acceptance per plan. Deliberately untouched: no wire change exists to document, and claiming interop in the comparison now would contradict the Pending device acceptance. |
| IT01 | Common vectors on both platforms, compare outputs | **Done** | See I05: identical `PASS=38 FAIL=0 SKIP=30` on both runners. |
| IT02 | Direct+group both directions, Offline/restart/reconnect/interruption/resume/dedup | **Done (automatable)** | See I06. Process-termination (kill) crash boundaries already covered per-platform by WT02/AT02; cross-platform kill-during-send not added (same code path, platform-local recovery). |
| IT03 | Relay a retrieved group message; retention/expiry/auth/membership/clear/delete | **Partial** | See I08. |
| IT04 | Seek opposite-platform recordings (beginning/middle/last/end), completion, stale-output invalidation | **Not verified (harness)** | Neither harness exposes seek/player commands, so no automatable check exists. Precondition met (byte-identical canonical WAVs both directions); actual seek behavior + ▶ reset on completion remain device-acceptance items. |
| IT05 | Nine scheduler rules on both platforms | **Done (automatable, same gap as before)** | See I07. |
| IT06 | Older-client fallback, spoofed/mismatched/arbitrary/malformed/oversized/unsupported, ordinary-card fallback | **Partial** | See I09. Malformed-chunk/oversized/unsupported-format WAV classification itself is covered per-platform by WT01/AT01 vectors; cross-platform delivery of each hostile class is not exhaustively matrixed. |
| IT07 | Complete `tests/run.ps1` + both arch/fixture checks | **Aborted on user request (too slow)** | Reached `group_membership.py` 16-member stress with no voice failure; user stopped it. Voice + interop subset above is the regression evidence for this pass. |
| Two-device LAN acceptance | Real phones/PCs on a LAN | **Not verified** | Needs physical hardware: bidirectional send, Offline/Wi-Fi/restart/process-kill, drafts-local-until-Send, exactly-one-result resume, relay, opposite-platform seek, clear/delete/expiry/auth/membership stop-playback, older-client-as-ordinary-WAV, Retry/Resume-no-picker, manual-next admission, 3-to-1 fairness on both. |

## Test evidence (this session, automatable)

- `CsharpHarness --voice-check tests/voice_messages/vectors` → `VOICECHECK PASS=38 FAIL=0 SKIP=30`
- Java `VoiceMessagesCheck tests/voice_messages/vectors` → `VOICECHECK PASS=38 FAIL=0 SKIP=30`
- `CsharpHarness --voice-scheduler-check` → `VOICESCHEDULERCHECK PASS=4 FAIL=0`
- Java `VoiceSchedulerCheck` → `VOICESCHEDULERCHECK PASS=4 FAIL=0`
- `CsharpHarness --voice-draft-reconcile-check` → `VOICEDRAFTRECONCILECHECK PASS=2 FAIL=0`
- Java `VoiceDraftReconcileCheck` → `VOICEDRAFTRECONCILECHECK PASS=2 FAIL=0`
- `tests/voice_architecture_check.py` → all 6 PASS
- `tests/voice_drafts.py` / `tests/voice_drafts_android.py` → all 8 scenarios each PASS
- `tests/voice_scheduler.py` / `tests/voice_scheduler_android.py` → all PASS
- NEW `tests/voice_interop.py` → all PASS (cs→java + java→cs byte-identical direct,
  cross-platform group byte-identical, sender-restart delivery, forged + mismatched-id
  fallback, clear-removes-row). Fixed one real test bug before landing: the java→cs
  wait checked only the first row's `HASFILE`, so it passed before the second clip
  downloaded and `FILEHASH` on the pending row errored; now waits for all N rows available.

## Resume point

1. If a full `tests/run.ps1` is ever wanted for I10/IT07: it is slow (sits in
   `group_membership.py`'s 16-member stress, which has a known environmental TLS
   flake) — run it in the background or run the voice/interop subset directly
   (`--voice-check`, `VoiceMessagesCheck`, both scheduler/reconcile checks,
   `voice_drafts*.py`, `voice_scheduler*.py`, `voice_interop.py`), which is the
   evidence recorded for this pass.
2. Do NOT close I11 until two-device LAN acceptance is recorded by a human on real
   hardware; then document the wire non-change in `PROTOCOL.md` only if the project
   wants voice mentioned there at all (currently nothing voice-specific is in the
   protocol file, correctly, since there is no new frame).
3. Update `PROJECT_STATUS.md` comparison + `windows/STATUS.md` / `android/STATUS.md`
   only with evidence actually obtained (automatable PASS vs Not-verified device items).
