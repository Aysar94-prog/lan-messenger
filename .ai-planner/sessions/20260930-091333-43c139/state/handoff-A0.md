# Handoff — Phase A0 Foundation (B00, B01 done)

**Date:** 2026-10-01
**From:** lead → next agent
**Phase:** Android Voice Calls — Phase A0 (Shared Architecture & Contracts)

## Status

| ID | Status | Evidence |
|---|---|---|
| B00 | **Done** | Baseline recorded: Android 2.2.6 (versionCode 33), javac -source 8 -target 8, API 26/34, MessengerService owns engine, manifest has RECORD_AUDIO + FOREGROUND_SERVICE_CONNECTED_DEVICE, existing voice-message system (AudioRecord/AudioTrack adapters) is separate from planned call controller |
| B01 | **Done** | Pure-Java contracts drafted: CallProtocol, CallSignaling, CallSession, CallController, ICallMedia, FakeCallMedia, CallSettings, CallQualityMonitor — 75/75 unit tests pass |
| A00 | Pending | WebRTC dependency evaluation needs Java/JNI library research (io.github.webrtc-sdk:android or self-build) |
| B02 | Pending | Depends on A00 + Windows W00 |
| B03 | Pending | Depends on B02 |

## New files (all in android/src/net/lanmsg/chat/)

| File | Purpose | Lines |
|---|---|---|
| `CallProtocol.java` | 14 message types, 6 states, endpoints, timing constants, quality thresholds, invitation limiter, glare resolution | ~200 |
| `CallSignaling.java` | 4-byte BE length + UTF-8 JSON frame serialization, parsing, builders for all message types | ~270 |
| `CallSession.java` | Immutable call snapshots, state tracking, elapsed duration | ~75 |
| `CallController.java` | Main orchestrator: state machine, signaling dispatch, media lifecycle, timers, heartbeat, quality polling, offline cleanup | ~430 |
| `ICallMedia.java` | Media adapter interface + Factory (for dependency injection) | ~75 |
| `FakeCallMedia.java` | Deterministic fake media for testing (synthetic SDP/ICE/stats) | ~100 |
| `CallSettings.java` | Incoming-call preference with atomic file persistence and generation counter | ~90 |
| `CallQualityMonitor.java` | Quality evaluator using WebRTC cumulative stats with first/last deltas, 3-consecutive-degraded entry | ~170 |
| `tests/CallCheck.java` | 75 unit tests: protocol, signaling, settings, quality, limiter, controller lifecycle, incoming, mute, frame parse | ~290 |

## Key design decisions implemented

1. **Frame format:** 4-byte unsigned big-endian length prefix + UTF-8 JSON body, max 64 KiB — verified round-trip
2. **State machine:** Idle → OutgoingRinging/IncomingRinging → Connecting → Connected → Ending → Idle — all transitions validated per plan-v006
3. **Invitation throttling:** 5 per peer per 60s rolling window, counting attempts regardless of outcome
4. **Quality indicator:** Cumulative-counter deltas (matching WebRTC stats spec), 3 consecutive degraded evaluations to enter Reduced, recovery flips to Normal immediately
5. **Settings:** Separate atomic file (call-settings.txt) with generation counter to prevent stale writes
6. **Controller ownership:** Designed for MessengerService — no Activity references, injectable media factory, snapshot-based UI pattern
7. **Glare resolution:** Lexicographic (localId+remoteId) comparison — deterministic, same on both platforms

## Compile verification

```
javac -source 8 -target 8: 0 errors (deprecation note on pre-existing code only)
java CallCheck: CALLCHECK PASS=75 FAIL=0
```

## Resume point

Next step is **A00** — evaluate WebRTC dependencies. This requires:
1. Research `io.github.webrtc-sdk:android` library availability, ABI coverage, Java API
2. Determine how to integrate native .so files into the javac/d8/aapt pipeline (currently no Gradle/Maven)
3. If the community SDK is usable, create a prototype `WebRtcCallMedia` implementing `ICallMedia`
4. If not, evaluate self-build or alternative approach

The controller and contracts are ready to accept a real media adapter via the `ICallMedia.Factory` interface — no controller changes needed.