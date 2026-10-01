# Handoff — Phase A1 Complete (A01, A02, A03, A03c, A03d done)

**Date:** 2026-10-01
**From:** lead → next agent
**Phase:** Android Voice Calls — Phase A1 Complete

## Status — All automatable A1 tasks done

| ID | Status | Evidence |
|---|---|---|
| A01 | **Done** | Injectable Clock, invitation limiter, deterministic glare |
| A02 | **Done** | CALLCONNECT protocol in PeerEngine; callHandler + openCallConnection() |
| A03 | **Done** | onPeerRevoked ends active call; onRevoke callback in PeerEngine; engine-gen hooks |
| A03c | **Done** | One CallController in MessengerService, fake media, no Activity ref |
| A03d | **Done** | CallSettings loaded in onCreate, admission gate wired |

## Remaining before Phase A2

| ID | Status | Blocker |
|---|---|---|
| A03b | Pending | **Needs A00** — WebRTC AAR integration into build.ps1 |
| AT01-AT01d | Pending | Can start AT01 (pure Java tests), others need A03b |
| A00 | Pending | WebRTC library evaluation & prototype |

## Summary of all code created/modified

### New files (8 + 1 test)
- `CallProtocol.java` — 14 msg types, 6 states, quality thresholds, limiter, glare
- `CallSignaling.java` — 4-byte-BE + JSON serialization/parsing + builders
- `CallSession.java` — Immutable snapshots
- `CallController.java` — Orchestrator with Clock injection, onPeerRevoked
- `ICallMedia.java` — Media interface + Factory
- `FakeCallMedia.java` — Fake for testing
- `CallSettings.java` — Incoming-call preference persistence
- `CallQualityMonitor.java` — Cumulative-stat quality evaluator
- `tests/CallCheck.java` — 75 unit tests

### Modified files
- `PeerEngine.java` — +callHandler, +onRevoke, +openCallConnection(), +CALLCONNECT dispatch
- `MessengerService.java` — +callController, +callSettings, +call wiring, +Offline/revoke hooks

## Test evidence
```
CALLCHECK PASS=75 FAIL=0
```
All production classes compile with javac -source 8 -target8 (0 errors).

## Resume point
The critical blocker is **A00** — evaluating how to integrate a WebRTC library (io.github.webrtc-sdk:android or self-build) into the existing javac/d8/aapt pipeline. The build system has no Gradle/Maven — everything is raw tool invocations in build.ps1.