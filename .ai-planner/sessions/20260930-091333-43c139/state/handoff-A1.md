# Handoff — Phase A1 (A01, A02, A03c, A03d done)

**Date:** 2026-10-01
**From:** lead → next agent
**Phase:** Android Voice Calls — Phase A1 (Signaling, Policy, Service Integration)

## Status

| ID | Status | Evidence |
|---|---|---|
| A01 | **Done** | Injectable Clock on CallController; invitation limiter active; state/glare/duplicates/rate-limits all deterministic; 75 tests pass |
| A02 | **Done** | CALLCONNECT protocol added to PeerEngine; `callHandler` callback for incoming call sockets; `openCallConnection()` for outgoing; transport wired in MessengerService |
| A03c | **Done** | Exactly one CallController created in MessengerService.onCreate(), alongside engine, with FakeCallMedia factory; no retained Activity reference |
| A03d | **Done** | CallSettings loaded in service onCreate from peer-data directory; admission gate (`settings.allowIncoming()`) checked in controller.onInvite; wired into Offline transition |
| A03 | Pending | Engine generation tracking, delete/reset hooks, trust verification integration |
| A03b | Pending | Production javac/D8 WebRTC dependency integration (needs A00) |
| AT01-AT01d | Pending | Production core/codec tests, APK manifest inspection, service binding tests, policy tests |

## Changes to existing files

### PeerEngine.java
- Added `callHandler` field: `BiConsumer<String,Socket>` — receives (peerId, authenticatedTlsSocket) when a peer sends CALLCONNECT after READY
- Added `openCallConnection(peerId, callId)` — opens authenticated TLS, sends HELLO/READY, sends CALLCONNECT, returns live socket
- Added CALLCONNECT detection in `receive()`: after READY, if next frame is `LM4\tCALLCONNECT\t<uuid>`, hands socket to callHandler and returns without closing

### MessengerService.java
- Added `callController` and `callSettings` volatile fields
- `onCreate()`: loads CallSettings before engine; creates CallController after engine with FakeCallMedia; wires `peer.callHandler` to read call frames and dispatch to controller; a Transport is built on the same socket for responses
- `transition(false)`: notifies controller.onOffline() before tearing down networking
- `onDestroy()`: calls controller.shutdown() before closing engine
- `notification()`: appends call state to notification text when call is active

### CallController.java
- Added `Clock` interface with `nowMs()` and `sleep()` for deterministic testing
- All `System.currentTimeMillis()` replaced with `clock.nowMs()`
- Public constructor defaults to system clock; package-private constructor accepts injectable Clock

### CallSession.java
- `Builder` constructor now takes explicit `long nowMs` instead of calling `System.currentTimeMillis()`

## Protocol additions

```
LM4\tCALLCONNECT\t<call-id>
```
Sent by caller after mutual HELLO/READY on a verified TLS connection. Server hands socket to callHandler; both sides switch to 4-byte-BE-length + UTF-8 JSON framing.

## Files created (Phase A0 carryover)
All 8 Java files from Phase A0 + CallCheck.java (75 tests)

## Compile verification
```
javac -source 8 -target 8: 0 errors (deprecation note on pre-existing code only)
java CallCheck: CALLCHECK PASS=75 FAIL=0
```

## Resume point

Next steps by priority:

1. **A00** (WebRTC evaluation): Research io.github.webrtc-sdk:android, evaluate build integration with javac/d8/aapt pipeline, prototype audio pipeline
2. **A03** (engine hooks): Generation tracking in controller, delete/reset trust integration
3. **A03b** (production deps): Integrate WebRTC AAR into build.ps1, add MODIFY_AUDIO_SETTINGS to manifest
4. **AT01-AT01d** (tests): Production core/codec tests, APK manifest inspection, service binding tests, policy tests