# Task Contract — Android Voice Calls (plan-v006)

**Task ID:** VC-ANDROID
**Goal:** Implement one-to-one full-duplex LAN voice calls for Android
**Level:** 2
**Source plan:** plan-v006.md
**Status:** A0/A1/A2 Done, A04 done and **device-validated**, **A08–A10 Done** (229 unit tests, built, installed, UI verified on device). **Released as `outputs/LanMessenger-2.2.9.apk`** (versionCode 36). A07 apply/proximity pending; **no live two-device call ever placed (only one device exists)**

## Phase A0 — Shared Architecture & Feasibility

| ID | Task | Status |
|---|---|---|
| B00 | Record revision, baseline, device architectures | **Done** |
| B01 | Draft call contracts; evaluate shortlist | **Done** |
| A00 | Prototype Java/JNI loading, audio, AEC | **Done** (AAR eval + real device: native lib loads, hwAEC/hwNS active) |
| B02 | Pair prototypes without external ICE servers | **Done** (ICE host candidates only, no STUN/TURN; device loopback connected) |
| B03 | Freeze wire/state/admission contracts | Pending |

## Phase A1 — Signaling, Policy, Service Integration

| ID | Task | Status |
|---|---|---|
| A01 | Serialized controller, snapshots, injectable clock, invitation limiter | **Done** |
| A02 | CALLCAPS/CALLCONNECT framing, authenticated transport handoff | **Done** |
| A03 | Engine generation, trust, delete/reset, shutdown hooks | **Done** |
| A03b | Production javac/D8 dependency integration (WebRTC) | **Done** (AAR cached; pipeline runs; signed APK installed and verified on device) |
| A03c | One controller inside MessengerService, fake media, no Activity ref | **Done** |
| A03d | Service-owned CallSettings, serialized updates, admission gate | **Done** |
| AT01-AT01d | Production core/codec tests, APK manifest, service binding, policy tests | **Done** (142 tests; APK manifest check pending) |

## Phase A2 — Audio Ownership, Routing, Service Type

| ID | Task | Status |
|---|---|---|
| A04 | Install real media into permanent service host | **Done** — direct `org.webrtc` imports (compiler-verified against AAR), probe-then-install in `MessengerService`, loopback SDP/ICE/DTLS/audio PASS on SM-S908E |
| A05 | Audio ownership, awaited release/finalization of voice devices | **Done** (desktop tests; device conflict untested) |
| A06 | Microphone foreground-service type/permission | **Done** (manifest; runtime mic FGS untested) |
| A07 | Communication mode + route selection policy | **Partly** — policy + `MODE_IN_COMMUNICATION` done; speaker applied via `CallRoute` on the applied route; `CallRoutePolicy.reconcile` → `AudioManager` recovery still unwritten |
| A07p | Proximity behavior | Pending (decision rule done, sensor unwired) |
| A07q | Normalize statistics, bounded polling | **Done** (`CallQualityMonitor`; real `StatsReport` normalization verified on device) |
| AT02-AT02q | Audio ownership, routing, quality tests | **Done** (desktop 229/229) / device pending |

## Phase A3 — UI, notifications, entry point

| ID | Task | Status |
|---|---|---|
| A08 | Call entry point (menu action), admission gates, transport factory, `onInvite` replies, policy withdrawal of a ringing call | **Done** (229 unit tests; menu action + offline refusal + pref persistence verified on device) |
| A09 | In-call overlay, terminal banner, return-to-call bar, Back behaviour, snapshot labels | **Done** (unit-tested; **overlay never rendered on device — no second peer**) |
| A10 | Ringing/active notification channels, Accept opens MainActivity, stale-action revalidation, "Allow incoming calls" switch | **Done** (channels + switch + persistence verified on device; **notification actions never fired on device**) |

## Files

| File | Phase | Purpose |
|---|---|---|
| CallProtocol.java | A0 | Message types, state machine, constants, invitation limiter |
| CallSignaling.java | A0 | 4-byte-BE + JSON frame serialization/parsing |
| CallSession.java | A0/A1 | Immutable snapshots with injectable clock |
| CallController.java | A0/A1 | Main orchestrator with Clock injection |
| ICallMedia.java | A0 | Media adapter interface + Factory |
| FakeCallMedia.java | A0 | Deterministic fake for testing; also the fallback when the WebRTC probe fails on-device |
| WebRtcCallMedia.java | A04 | Real media: dedicated signaling thread, refcounted global factory, `probe()` for device check |
| CallSettings.java | A0 | Incoming-call preference persistence |
| CallQualityMonitor.java | A0/A2 | WebRTC cumulative-stat quality evaluator; bounded polling |
| AudioOwnership.java | A2 | Arbitration between voice calls and voice messages |
| CallRoutePolicy.java | A2 | Route selection (earpiece/speaker/wired/BT), recovery window |
| CallUi.java | A3 prep / A08–A10 | Service-owned view-model: labels, actions, terminal-snapshot retention, observer list |
| CallView.java | A09 | In-Activity call overlay on `stage`: state/detail/route/quality lines, Accept/Decline/Mute/Speaker/Hang up, terminal banner |
| CallNotifier.java | A10 | `calls_ringing`/`calls_active` channels, ringtone, Accept/Decline service intents, `EXTRA_CALL_ID` |
| CallChannel.java | A08 | Socket + one reader thread + serialized writer for both call directions; implements `Transport` and `Closeable` |
| CallRoute.java | A07 | Carries a route decision to `AudioManager` (`MODE_IN_COMMUNICATION`, speaker/earpiece) |
| MessengerService.java | A1/A4 | Service integration, call socket wiring, WebRTC probe + real-media install (modified) |
| PeerEngine.java | A1 | CALLCONNECT protocol + call handler (modified) |
| android/build-voice.ps1 | A03b/A04 | Single AAR-aware build pipeline (javac/d8/aapt/apksigner + native `.so`) |
| android/build.ps1 | A03b/A04 | Thin wrapper over build-voice.ps1, keeps signing-key contract |
| android/prepare-webrtc.ps1 | A00 | Downloads and caches the WebRTC AAR |
| tests/CallCheck.java | A0–A10 | 229 unit tests |