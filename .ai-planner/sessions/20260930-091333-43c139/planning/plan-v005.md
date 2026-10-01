# plan-v005 | version=5 | Voice calls for Android and Windows

Implement **one-to-one, full-duplex LAN voice calls**, using existing verified peer connections for signaling and **WebRTC-compatible media with Opus and DTLS-SRTP**. Separate call management from media tracks so video can be added later.

The selected additions are included in the first release: **Allow incoming calls** on both platforms, **earpiece proximity handling** on Android, and a **connection-quality indicator** on both platforms.

This is planning only. No project files were modified, and no builds or tests were executed. Implementation and verification tasks are **Pending** until execution is explicitly requested. Video tasks are **Deferred**.

There are two major platform sections. Tasks labeled **Both** define shared work once. Android calls remain service-owned from their first production integration. Windows architecture support is decided before dependencies are frozen.

## 1. Android

### Phase A0 — Shared architecture and paired feasibility

**Outcome:** choose a viable media stack and prove Android ↔ Windows audio before implementing the full feature.

#### Current implementation baseline

| Area | Current code and implication |
|---|---|
| Android application | Java, minimum API 26, target API 34. The javac/D8/aapt pipeline has no WebRTC integration. |
| Compilation/packaging | `build.ps1` compiles every Java file in the source folder, adds only `classes.dex`, and currently packages no WebRTC native libraries. Production dependency integration must precede media implementation. |
| Networking | Both engines provide verified TLS, five-field LM4 `HELLO`, predominantly short transactions and 16 KiB line limits. |
| Existing capabilities | Group `CAPS=2` has an existing meaning; calls need an independent capability. |
| Android ownership | `MessengerService` owns the engine and Online/Offline lifecycle; its current foreground-service type is `connectedDevice`. |
| Existing audio | Voice-message recording/playback belongs to the Activity and stops on pause. Those hooks must not dispose call resources. |
| Android settings | `PeopleListView` builds the people menu. Existing visibility preferences are Activity-oriented; incoming-call admission must instead be service/controller-owned. |
| Android permissions | Existing microphone permission results start voice-message recording. The manifest lacks the new microphone foreground-service, audio-settings and proximity wake-lock declarations required by this plan. |
| Windows settings/build | `Program.cs` owns the tray and a local network preference. `LanMessenger.csproj` has no explicit RID/platform target; managed AnyCPU defaults do not prove previous package architecture coverage. |
| Windows media/TLS | Voice messages use `waveIn`/`waveOut`; TLS uses BouncyCastle-backed `SecureChannel`. No production WebRTC/native-copy integration exists. |
| Existing verification | `tests/voice_interop.py` is wired into `tests/run.ps1`; older status paragraphs are partially stale. Wiring is not evidence of a current passing run. |

Relevant Android files: [build.ps1](/D:/LAN-Messenger/source/android/build.ps1), [AndroidManifest.xml](/D:/LAN-Messenger/source/android/AndroidManifest.xml), [PeerEngine.java](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/PeerEngine.java), [MessengerService.java](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/MessengerService.java), [MainActivity.java](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/MainActivity.java), and [PeopleListView.java](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/PeopleListView.java).

Current source and platform status records govern implementation.

#### Scope and milestones — Both

| Milestone | Scope |
|---|---|
| **B02: feasibility demonstration** | Foreground prototypes exchange encrypted Android ↔ Windows audio. Proves dependencies; not a release. |
| **D01: first integrated demonstration** | Production builds support call, accept, mute, hangup, incoming-call admission policy and Offline cleanup. Android remains foreground-only until A06. |
| **MVP release** | Complete basic calls on all three pairings, platform lifecycle/routing, incoming-call toggle, Android proximity behavior and validated quality indicator. |
| **BH01: extended hardening** | Longer soaks, 100-cycle automation, external latency measurement and broader impairment/device coverage. |
| **Future video** | Negotiated video tracks, explicit camera consent and rendering using the same call foundation. |

First-release behavior:

- One verified direct contact and one pending/active call per device.
- Reachable IPv4 LAN peers; no required internet service.
- Call, accept, decline, cancel, hangup, mute and basic route/device selection.
- Clear calling, ringing, connecting, connected and terminal states.
- Persisted **Allow incoming calls**, independent of Online/Offline; outgoing calls remain available when disabled.
- Established Android calls continue through navigation/screen lock while the service remains alive.
- Windows calls continue while hidden to the tray.
- Android earpiece calls use supported proximity behavior to prevent cheek taps.
- Both platforms display a stable **Connection quality reduced** indication when validated network statistics justify it.
- Android permits system-selected Bluetooth audio best-effort, with explicit observation and recovery.
- Offline ends call networking; returning Online does not resume calls.
- Text and attachments remain available; voice messages and calls coordinate audio ownership.
- No automatic answering, recording or stored call audio.

Deferred: group calls, waiting/hold, durable call history, internet relays, automatic redial/reconnection, Bluetooth pairing/device management, comprehensive Bluetooth compatibility and video UI. The unselected local audio-check feature is outside this plan.

#### Architecture — Both

Prefer libwebrtc-based adapters. A managed Windows alternative qualifies only if it meets the same audio-processing, lifecycle, statistics and future-video requirements.

```text
Android Activity                  Windows call UI / tray
       ↓                                    ↓
MessengerService                   Application-owned
  CallController                     CallController
       ↓                                    ↓
Admission policy + authenticated signaling + media adapter
       ↓                                    ↓
Audio routing / quality state       Devices / quality state
Android proximity lifecycle
```

Android’s Activity supplies commands, permissions and presentation. It never owns the controller, native peer connection, call timers or media threads.

Reuse verified identities, contact lookup and lifecycle hooks. Preserve existing WAV, draft and attachment contracts. Calls do not use attachment scheduling or recording-duration limits.

WebRTC supports application-defined signaling and native clients. [Peer connections](https://webrtc.org/getting-started/peer-connections), [native Android development](https://webrtc.github.io/webrtc-org/native-code/android/).

#### Dependency shortlist

Estimates below cover initial bring-up/packaging, not the complete feature. They assume experienced implementation and suitable hardware. B02 verifies exact revisions, licenses, toolchains, architectures and configured capabilities.

**Android**

| Candidate | Capabilities and gaps | Estimated effort |
|---|---|---|
| **Preferred: `io.github.webrtc-sdk:android`** | Community Java/JNI libwebrtc using `org.webrtc`. Evaluate full variant for Opus, DTLS-SRTP, AEC and statistics. Inspect AAR contents, routing behavior and required dependencies. Do not select stripped video codecs solely for initial size. | **1–3 engineering days** for inspection and initial build integration. Not a current Google-published Maven dependency. [Repository](https://github.com/webrtc-sdk/android). |
| **Fallback: pinned upstream libwebrtc self-build** | Java/JNI, peer connections and configurable processing. Verify Opus, DTLS-SRTP, AEC and required statistics. Own toolchains and updates. | **5–10+ engineering days** for infrastructure, then app packaging. Requires supported build host, `depot_tools`, GN/Ninja and Android AAR build tooling. [Documentation](https://webrtc.github.io/webrtc-org/native-code/android/). |

AEC must be configured and tested, not inferred from successful loading. [Audio-processing interface](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_processing/include/audio_processing.h).

**Windows**

| Candidate | Capabilities and gaps | Estimated effort |
|---|---|---|
| **Preferred where architecture policy permits: Shiguredo `webrtc-build` plus project-owned C ABI bridge** | Native libraries/headers/revision information; Windows x64/ARM64 targets. No application-specific .NET wrapper; no assumed x86 artifact. Verify Opus, DTLS-SRTP, AEC, devices and statistics. Published binaries omit H.264/H.265; future video needs a tested common codec. | **3–7+ engineering days** for bridge and packaging. Match headers/library/toolchain and runtime dependencies. [Repository](https://github.com/shiguredo-webrtc-build/webrtc-build). |
| **Fallback: upstream self-build plus C ABI bridge** | Native stack with configurable processing and build flags. Prove every retained architecture and expose required statistics through the bridge. | **5–10+ engineering days for infrastructure alone**, plus integration. [Native development](https://webrtc.github.io/webrtc-org/native-code/development/). |
| **Alternative: SIPSorcery + SIPSorceryMedia.Windows + Opus/Concentus** | Managed WebRTC/DTLS-SRTP, Opus and separate device endpoints. AEC, playout/jitter behavior, statistics access and complete architecture coverage require proof. Future video adds codec/rendering work. | **2–4 days** for basic audio, potentially substantially more for missing processing/statistics. Pin .NET 9-compatible dependencies. [Capabilities](https://github.com/sipsorcery-org/sipsorcery), [DTLS-SRTP API](https://sipsorcery-org.github.io/sipsorcery/api/SIPSorcery.Net.DtlsSrtpTransport.html). |

Archived Microsoft MixedReality-WebRTC is not the default shortcut. [Repository status](https://github.com/microsoft/MixedReality-WebRTC/).

#### Foundation tasks

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| B00 | Both / Pending | Execution authorized | Record revision, changes, baseline results, device architectures and equipment. Include paired Bluetooth headset and proximity-capable Android device. | Existing failures and unavailable equipment explicit; source/automation/device evidence separate. |
| B01 | Both / Pending | B00 | Draft contracts below; evaluate candidates and tools. Inspect routing helpers and loss/jitter/RTT availability. | Concrete candidate checklist, build proposal and statistics mapping. |
| A00 | Android / Pending | B01 | Prototype loading, audio, AEC, statistics, routing ownership and cleanup; inspect ABI/API availability. | Physical audio and repeated cleanup; real statistics available or identified integration work. |
| W00a | Windows / Pending | B01 | Architecture inventory and explicit x86 policy before dependency freeze. | Support matrix, with unknown x86 use not assumed absent. |
| W00 | Windows / Pending | B01, W00a | Media/TLS spike against architecture and statistics requirements. | Audio, persistent signaling and retained-architecture feasibility evidence. |
| B02 | Both / Pending | A00, W00, W00a | Pair prototypes without external ICE servers. Select revisions, licenses, tools, architectures and reproducible recipes. | Encrypted two-way audio, mute, processing, cleanup and required statistics access proven; x86 disposition explicit. |
| B03 | Both / Pending | B02 | Freeze wire/state/admission contracts, normalized statistics, fixtures, routing ownership and dependency/RID manifest. | Exact inputs for independent implementation. |
| **BQ01** | **Both / Pending** | **B03** | Define and implement matching pure Java/C# quality evaluators with shared timestamped fixtures; initial thresholds below. | Deterministic degradation/recovery, stale/missing data, counter resets and hysteresis match across platforms. |

If feasibility fails, revise dependencies/adapters. Do not replace requirements with plaintext media, WAV streaming or fabricated statistics.

### Shared call contracts — Both

#### Compatibility, admission and framing

- Preserve discovery, five-field `HELLO`, group `CAPS=2` and attachment frames.
- Add authenticated `CALLCAPS` with protocol/media version; query freshly before outgoing calls.
- Capabilities describe implementation support, not the user’s incoming-call preference.
- Negotiate/acknowledge `CALLOPEN` before call framing.
- Admit verified direct peers only; reject self/group/identity mismatch.
- Advertise media capability after successful no-capture initialization.
- Bound handshakes, invitation rates, queues and pending sessions.
- Preserve 16 KiB ordinary LM4 lines.
- Call framing: four-byte unsigned big-endian length followed by UTF-8 JSON.
- Initial limits: 64 KiB frame, 48 KiB SDP, 128 candidates per negotiation; queues bounded by count and bytes.
- Envelope: version, type, call ID, sender sequence, negotiation generation and typed body.
- Define malformed input, duplicate keys, required/optional fields and unknown-message behavior.
- Reject oversized lengths before allocation; enforce whole-frame deadlines.
- One reader/serialized writer; explicit TLS/socket ownership handoff.
- Release ordinary inbound capacity after handoff but retain engine cancellation tracking.
- Reserve bounded signaling admission separately from long transfers.

#### Incoming-call preference contract

**Label:** “Allow incoming calls.” **Default:** enabled for a genuinely missing setting. Persist per device independently of Online preference, peer-list filters and outgoing-call capability.

- Load settings before admitting invitations; network messaging need not wait for media readiness, but call admission stays closed until policy is loaded.
- On corrupt/unreadable settings, disable incoming admission for the session and show a recoverable settings error rather than unexpectedly ringing.
- On every authenticated `INVITE`, consult the current policy **before `RINGING`, notifications, ringtone, audio ownership or per-call media initialization**.
- Disabled: return existing `DECLINE`, close/release that invitation and show the caller the normal declined outcome. Do not disclose a new preference flag or change protocol capability.
- Outgoing calls, text, attachments, discovery and actual Online state remain unchanged.
- Turning off while an incoming call is still ringing declines it and removes its UI/notification.
- An already accepted call in Connecting/Connected continues. Turning off does not hang it up.
- Serialize toggle, invitation and Accept on the controller executor. Whichever command commits first determines whether acceptance is allowed.
- Apply this policy before glare handling; simultaneous dialing does not bypass a disabled incoming policy.
- Turning on admits only subsequent invitations; it never resurrects an expired/declined one.
- Apply changes immediately in memory and serialize persistence. If saving fails, retain the current-session choice and show “Setting was not saved”; prevent older writes overwriting newer choices.
- Preserve this device setting across chat deletion and messaging-data reset, consistently on both platforms. Uninstall/new installation uses the default.
- No durable queue or retry is created for declined calls.

Android stores the setting in service-owned call preferences and exposes it through binder/snapshots. Windows uses a separate atomic per-user call-settings file under the existing data directory. Do not overload either platform’s network preference.

#### Messages, security and state

Define `INVITE`, `RINGING`, `ACCEPT`, `DECLINE`, `BUSY`, `CANCEL`, `OFFER`, `ANSWER`, `ICE`, `MEDIA_READY`, `HANGUP`, `ERROR`, `PING`, `PONG`, with valid sender/state/fields and duplicate rules.

Bind sessions to random call ID, verified peer/fingerprint, authenticated channel, engine generation and negotiation generation. Enforce media fingerprints delivered through authenticated signaling.

Reject insecure descriptions and unexpected video/data sections in voice version 1. Validate SDP/candidates before native processing. Logs contain aggregate diagnostics, not audio, keys or full credentials.

```text
Idle → OutgoingRinging / IncomingRinging
     → Connecting → Connected → Ending → Idle
```

- Permission before outgoing invitation, without capture.
- Acceptance requires current invitation, enabled admission and eligible permission/service flow.
- Caller offers after acceptance; callee answers.
- Capture requires explicit local action and remote acceptance.
- Send local readiness independently; Connected requires both sides ready.
- Bound early candidates by negotiation identity.
- Deterministic caller-ID ordering resolves simultaneous dialing after admission checks.
- Serialize terminal races; ignore stale permissions/callbacks/actions.
- Never persist/resume calls after restart.
- Additional unrelated invitations receive Busy when incoming admission is enabled and a session is occupied.

#### Timers and cleanup

| Timer | Proposed default |
|---|---|
| Capability/open | Ten-second total connection/TLS budget. |
| Ringing | Thirty seconds; permission UI does not extend it. |
| Media setup | Fifteen seconds after acceptance. |
| Heartbeat | Every five seconds; fail after fifteen seconds without valid inbound signaling. |
| Route recovery | Three seconds before clear failure termination. |
| Local teardown | Target completion within two seconds. |

Use monotonic clocks and call-specific transport deadlines. Lost signaling/terminal ICE failure ends calls; bounded transient grace does not imply auto-redial.

Cleanup stops capture, releases proximity behavior, invalidates callbacks, closes networking, cancels quality polling/timers, releases audio ownership and restores app-owned settings. Offline includes native sockets; no new generation until old call resources stop.

#### LAN policy

- No external STUN/TURN or internet fallback.
- UDP host candidates on the authenticated signaling interface.
- Initially accept remote addresses matching the authenticated IPv4 endpoint.
- Reject public, multicast, unspecified, unrelated-interface and production loopback candidates.
- Filter SDP and trickled candidates; explicitly handle mDNS.
- Clear failures for blocked UDP/client isolation/unsupported networks.
- Document media firewall/port requirements while preserving discovery/control ports.
- Keep media outside attachment queues and Android attachment-upload accounting.

#### Shared quality-indicator contract

The indicator reports **observed network degradation**, not a microphone diagnosis or a guarantee about the complete audio path. It does not mute, reroute, reconnect or terminate calls.

**Statistics and sampling**

- Sample asynchronously about once per second during Connected, with one request in flight and bounded cancellation/timeout.
- Normalize local inbound audio packet counters/loss and jitter, plus selected ICE candidate-pair RTT. Adapters document equivalent mappings if names differ.
- Jitter/RTT units are converted explicitly to milliseconds. RTT is not mouth-to-ear latency.
- Use rolling five-second windows. Compute loss from packet-counter deltas, not lifetime percentages; require sufficient traffic, initially 50 expected packets per window.
- Treat source/SSRC/generation changes and counter resets as new baselines. Handle negative loss corrections without producing negative percentages or artificial spikes.
- Missing, repeated-old, invalid or stale measurements are unavailable, never substituted with zero.
- No media amplitude, silence duration or mute state is used to infer a microphone fault.
- No new signaling exchange or persistent raw-statistics storage is required.

WebRTC statistics distinguish cumulative packet counts, packet jitter in seconds and transport RTT; native adapters must preserve those meanings. [Statistics specification](https://www.w3.org/TR/webrtc-stats/).

**Initial policy, calibrated before release**

| Rule | Initial value |
|---|---|
| Warm-up | Five seconds of usable observations after Connected or baseline reset. |
| Enter Reduced | Loss above 5%, rolling jitter above 30 ms, or rolling RTT above 300 ms for three consecutive valid evaluations. |
| Recover | Triggering metrics below lower thresholds—loss below 2%, jitter below 20 ms, RTT below 200 ms—for ten seconds, with no new degraded metric. |
| Insufficient evidence | Show no healthy claim. If supporting evidence becomes stale for more than three seconds, move that metric to Unknown; retain Reduced only if other fresh evidence supports it. |
| Call ends | Stop polling and clear all quality state immediately. |

Use `Unknown`, `Normal` and `Reduced` internally. Normal need not display a badge. Reduced shows:

> Connection quality reduced  
> Check your LAN connection. On Wi-Fi, try moving closer to the router.

Show the hint inline or on demand, without modal dialogs or repeated announcements. Announce state changes accessibly once, not every sample. Quality status remains separate from connection state and audio-route errors.

**Validation requirement:** BQ01 covers deterministic behavior; BTQ01 uses actual adapters and controlled impairment/recovery before default release enablement. Broader performance testing remains BH01.

### Phase A1 — Android signaling, policy and production integration

Proposed classes: `CallSession`, `CallController`, `CallProtocol`, `CallSignaling`, `CallMedia`, `CallSettings`. Keep core state/codec/quality logic free of Android and native dependencies.

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| A01 | Android / Pending | B03 | Serialized controller, snapshots, injectable clock/fakes and incoming policy input. | States, glare, duplicate/race/terminal behavior deterministic. |
| A02 | Android / Pending | A01 | Capability/open/framing and authenticated handoff. | Production TLS negative/legacy/partial-frame/backpressure checks. |
| A03 | Android / Pending | A02 | Generation/trust/delete/reset/shutdown hooks. | No stale activity after Offline/restart. |
| A03b | Android / Pending | B03 | Production javac/D8 dependencies, all DEX files, native libraries and required AAR metadata/resources; packaging policy below. | Clean APK installs and no-capture factory initialization works without manual setup. |
| **A03c** | **Android / Pending** | **A03** | **Create one controller inside existing `MessengerService`, next to its engine.** Inject fake media; expose commands/snapshots and pre-A06 foreground eligibility. | Rebinding/recreation never creates another controller; no retained Activity. |
| **A03d** | **Android / Pending** | **A03c** | Service-owned call-preference loading, serialized updates/persistence and admission gate. | Disabled policy declines before ringing/media; outgoing/messaging unchanged; startup and save failures handled as specified. |
| AT01 | Android / Pending | A01–A03 | JVM core/codec tests and harness wiring. | Production code executes without Android/JNI; fixtures match C#. |
| AT01b | Android / Pending | A03b | Artifact/native-loading checks. | Packaging failure clear; optional native failure does not crash messaging. |
| AT01c | Android / Pending | A03c | Service binding/lifecycle tests with fake media. | One session; Activity cannot directly replace/dispose media; controlled foreground-only termination. |
| **AT01d** | **Android / Pending** | **A03d** | Toggle persistence/startup/failure tests and invitation/Accept/toggle ordering. | No ringtone/notification/media calls when disabled; ringing cancelled once; accepted call continues; delayed saves cannot revert new value. |

A03b can overlap controller work. All production-build and service/policy prerequisites complete before A04.

#### Android ownership

- Service owns controller, executor, signaling, media factory/session, quality polling and proximity adapter.
- Activity handles presentation/permission and sends identity-checked commands.
- A04 replaces the service’s fake adapter; no temporary Activity-owned media.
- UI subscriptions detach/reattach without recreating call resources.
- Existing voice-message lifecycle cleanup remains specific to voice messages.
- Before A06, loss of valid foreground eligibility requests service-controlled termination.
- After A06, Activity pause—including pause associated with proximity screen-off—does not end or recreate the call.
- Service destruction, Offline and engine failure clean up without Activity cooperation.

#### Android packaging policy

Implement in A03b; audit final artifacts in A11:

- Planned universal ABIs: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, verified at B02. No silent loss of existing-device support.
- Prefer one universal APK for sideload upgrades.
- Raw packaging: explicit `extractNativeLibs=true` and compressed `.so` entries; inspect ZIP methods.
- Apply ordinary ZIP alignment before signing. Four-byte ZIP alignment is not ELF page-size evidence.
- Verify every native dependency’s ELF compatibility for tested 16 KiB environments.
- Any direct-from-APK policy later requires uncompressed libraries and appropriate verified alignment.
- Necessary build migration occurs inside A03b.
- Record baseline/new APK size, delta, per-ABI contribution and installed/extracted footprint.
- Test API 26/newest-device load and final upgrade artifacts; 16 KiB claims require evidence.

[Extraction setting](https://developer.android.com/guide/topics/manifest/application-element#extractNativeLibs), [16 KiB compatibility](https://developer.android.com/guide/practices/page-sizes).

### Phase A2 — Android audio, routing, proximity and quality

Proposed adapters: `WebRtcCallMedia`, `CallAudioRouter`, `AudioOwnership`, `CallProximity`, `CallQualityMonitor`.

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| A04 | Android / Pending | A00, A03b, AT01b, A03c, AT01c, A03d, AT01d | Real media in service host: Opus, SDP/ICE, mute, readiness, statistics and disposal. Foreground-only until A06. | Production audio, correct admission and cleanup; no Activity-owned peer connection or call files. |
| A05 | Android / Pending | A04 | Audio ownership and awaited voice-draft/device release. | Draft preserved; failed release blocks competing capture. |
| **A06** | **Android / Pending** | **A04, A05** | **Microphone foreground-service declarations/type masks and background-continuation policy. Ownership is unchanged.** | Home/lock/recreation preserve calls after successful promotion; failure cannot permit background capture. |
| A07 | Android / Pending | A06 | Communication focus/mode, supported routes, Bluetooth policy and required audio-settings declaration. | One route coordinator, mute retention, bounded fallback, correct observed route. |
| **A07p** | **Android / Pending** | **A06, A07** | Service-owned proximity behavior under the contract below; optional sensor/capability handling and wake-lock declaration. | Near earpiece blanks display; far restores normal behavior; route/terminal cleanup releases immediately; unsupported devices unaffected. |
| **A07q** | **Android / Pending** | **A04, BQ01** | Map native statistics into shared evaluator; asynchronous bounded polling and snapshot updates. | Correct units/baselines, no UI/native-thread blocking, no stale callbacks after end; missing data never fabricates Normal. |
| AT02 | Android / Pending | A04–A07 | Physical lifecycle/routing and fault tests, including mandatory Bluetooth observation. | Denial/unplug/route failure/service death leave no retained capture/session. |
| **AT02p** | **Android / Pending** | **A07p** | Fake-policy tests plus physical near/far, route switch while near, remote hangup while near, Offline/destruction and unsupported-sensor checks. | No stuck-dark screen, no speaker/Bluetooth/wired blanking, no call interruption from proximity-triggered Activity lifecycle. |
| **AT02q** | **Android / Pending** | **A07q** | Real stats extraction plus injected evaluator/timeout/teardown tests. | Rolling thresholds, missing data and state changes match shared fixtures. |

Microphone service activation must follow Android’s eligible permission/foreground flow. [Service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [background restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

#### Routing and automatic Bluetooth policy

Permit already connected, system-selected Bluetooth audio best-effort. Pairing/scanning/device management remain deferred.

- Default to System audio; Android may select Bluetooth automatically.
- Observe and display the route when identifiable; otherwise say System audio rather than inventing a device.
- Offer System, Phone where available, and Speaker. Explicitly selecting Phone must not be implemented merely as clearing preference.
- One coordinator owns application route requests; integrate or disable competing media-library helpers.
- Use API-guarded communication-device APIs on API 31+ and a tested legacy path.
- Add `MODIFY_AUDIO_SETTINGS` where required.
- Avoid Bluetooth-profile access. If a dependency needs protected APIs, guard permission denial/revocation; normal phone/speaker calling must remain possible.
- Preserve mute during changes. Report device/route failure and recover within the bounded timeout or end clearly.
- Do not infer audibility from successful selection or packet counters.
- Clear app requests/listeners/mode/focus on termination.
- Feed confirmed route identity and route-transition events into proximity eligibility. Unknown route never enables proximity blanking.

[AudioManager](https://developer.android.com/reference/android/media/AudioManager), [VoIP routing](https://developer.android.com/develop/connectivity/telecom/voip-app/api-updates), [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).

**AT02 physical Bluetooth observation:** on at least one paired headset/device, verify two-way speech, connect/disconnect during call, mute retention, Phone/Speaker override, rejected-route handling and post-call voice-message recovery. Record actual route and permission state. A silent/broken path must recover or end clearly; best-effort is not a test waiver. Missing equipment leaves acceptance pending.

#### Android proximity contract

This is a required first-release feature where supported, not a change to call ownership.

- Enable only when the controller is **Connected**, routing confirms the **built-in earpiece**, and the device supports proximity screen-off behavior.
- Use `PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK` with the normal `WAKE_LOCK` permission; check support before acquiring. Treat proximity hardware as optional.
- Prefer platform-managed near/far behavior rather than custom screen-lock or brightness tricks.
- Service owns one idempotently managed, non-reference-counted proximity wake lock.
- Release immediately when route change begins, route becomes unknown/non-earpiece, call leaves Connected, Offline starts, or service/session cleanup begins. Reacquire only after confirmed eligible state.
- Release without `RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY` on those terminal/ineligible transitions; do not keep the display suppressed until the user moves the phone.
- Do not enable for Speaker, Bluetooth, wired/USB output, ringing or Connecting.
- Maintain eligibility through Activity pause/recreation caused by screen-off; avoid a release/reacquire lifecycle loop.
- Normal user screen lock/power-button behavior remains under Android control. Cleanup removes this feature’s suppression; it does not forcibly unlock or wake an independently locked device.
- Unsupported sensor/wake-lock capability or acquisition failure leaves ordinary screen behavior and the call intact.
- No additional CPU/Wi-Fi wake lock is implied. Such locks remain evidence-driven.

Android provides a dedicated proximity wake-lock level and a separate delayed-release flag; immediate call cleanup deliberately does not use that flag. [PowerManager](https://developer.android.com/reference/android/os/PowerManager).

#### Other lifecycle rules

- Explicit service actions; unknown/stale actions cannot enable networking.
- Preserve `START_NOT_STICKY`; no call resurrection.
- Optional microphone hardware for messaging-only devices.
- Ringing never takes microphone ownership or discards recordings.
- Guard voice-message capture/playback in UI and command layers.
- Notification Accept opens visible permission flow.
- Separate permission IDs and revalidate current call/policy before committing acceptance.

#### Early integrated demonstration

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| D01 | Both / Pending | A04, A05, W04, W05, BT01 | Minimal development controls, production signaling/media and permanent ownership. Use a validated built-in/wired route. Exercise incoming policy through test controls. | Calls both directions for approximately two minutes; mute/hangup/Offline work; disabled incoming is declined while outgoing works. Pre-A06 foreground loss ends cleanly. |

D01 does not depend on notification polish, proximity, quality presentation or extended hardening. Those selected additions still gate the final MVP release.

### Phase A3 — Android interface, settings and notifications

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| A08 | Android / Pending | A06, A07, A03d | Direct-chat Call action and **Allow incoming calls** switch in `PeopleListView` people menu. Bind to service policy, not Activity filter preferences. | Checked state survives menu rebuild/restart; disabled-while-Online explanation; outgoing stays enabled; no implicit Online transition. |
| A09 | Android / Pending | A08, A07p, A07q | Call views, duration, mute, route, hangup/return-to-call and inline reduced-quality indicator/hint. | Snapshot-driven, accessible; route/connection/quality meanings distinct; no repeated announcement/flicker. |
| A10 | Android / Pending | A09 | Notifications/actions tied to current call; immediate removal after policy-driven decline. | Disabled calls never notify/ring; stale Accept cannot bypass current policy. |
| AT03 | Android / Pending | A08–A10 | Menu persistence, background/lock/recreation, accessibility, route controls, quality display and notification races. | Policy consistent while Activity absent; existing filters/Online unaffected; proximity screen-off does not end calls. |

No full-screen intent privilege or Telecom integration is required. A stopped service cannot receive LAN calls.

### Phase A4 — Android release audit and acceptance

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| A11 | Android / Pending | A03b, AT01b, AT02, AT02p, AT02q, AT03 | Final dependencies/notices, ABIs, size, extraction/ELF policy and versioned filename; audit new permissions and optional proximity capability. | API 26/newest-device load, no install exclusion for sensorless devices, accurate artifact report. |
| A12 | Android / Pending | A11, AT01, AT01c, AT01d, BT01–BT03, BTQ01 | Build/sign after MVP gates. Original package/signer, increasing versionCode. | Upgrade retains identity/history/drafts and saved call preference; no uninstall/key replacement. |
| A13 | Android / Pending | A12 | Status/comparison with settings, proximity, observed routes and quality calibration evidence. | Code/automation/device acceptance separate; unavailable checks explicit. |

Outputs belong in `D:\LAN-Messenger\outputs` when execution has access. Preserve canonical source, branch/remotes and signing material; exclude `.private` from archives. Commit locally, push only when requested.

### Phase A5 — Future Android video

| ID | Platform / status | Dependencies | Future work | Acceptance / testing |
|---|---|---|---|---|
| AV01 | Android / Deferred | Accepted voice release, BV01 | Service-owned camera adapter, optional capability and eligible camera permissions/service state. | Voice-only never opens camera; denial preserves audio. |
| AV02 | Android / Deferred | AV01 | Preview, remote video, camera switching/off and explicit consent; Activity owns surfaces only. Revisit proximity eligibility explicitly for video UI. | No accidental camera activation or unexplained video-screen blanking. |
| AV03 | Android / Deferred | AV02, WV02 | Video interoperability, thermal/battery, synchronization and audio-route/quality checks. | Video failure preserves audio; Offline stops all media. |

Build only extension points now: media kinds, negotiation generation, track controls and UI-independent adapters.

## 2. Windows

### Phase W0 — Architecture and media feasibility

**Outcome:** decide process-architecture support before freezing dependencies.

Relevant files: [LanMessenger.csproj](/D:/LAN-Messenger/source/windows/LanMessenger.csproj), [SecureChannel.cs](/D:/LAN-Messenger/source/windows/SecureChannel.cs), [PeerEngine.cs](/D:/LAN-Messenger/source/windows/PeerEngine.cs), [Program.cs](/D:/LAN-Messenger/source/windows/Program.cs), and [ChatWindowVoicePlayback.cs](/D:/LAN-Messenger/source/windows/ChatWindowVoicePlayback.cs).

A native bridge requires architecture-specific deployment. Inspect previous apphost and actual installations rather than assuming AnyCPU establishes universal support.

#### Architecture decision

| Item | Required evidence |
|---|---|
| Existing users | OS, process, Desktop Runtime and prior executable architecture; unknowns recorded. |
| x64 | Candidate `win-x64` with matching process/native chain. |
| ARM64 | Decide native `win-arm64` versus tested x64 emulation; label accurately. |
| x86 | Explicit retain/exclude decision; distinguish x86 process on x64 OS from 32-bit-only OS. |
| Native dependencies | Actual artifact/build/toolchain for every retained architecture. |
| Upgrade | Same data path and Windows user; preserve DPAPI identity/history and new call preference. |

Default: do not drop existing x86 users. If x86 use is present or unknown, B02 cannot freeze an x64/ARM64-only provider as complete. Prove a compatible x86 path or obtain an explicit scope decision. A managed candidate or source build is not automatically an x86 solution.

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| W00a | Windows / Pending | B01 | Inventory/support policy, RID matrix, bridge ABI, CRT/toolchain and loading requirements. | Explicit x86 disposition before freeze. |
| W00 | Windows / Pending | B01, W00a | Prove audio/AEC/Opus/DTLS-SRTP/statistics, callbacks and cleanup; persistent TLS concurrent I/O/timeouts/cancellation. | Physical paired audio and statistics mapping; viable retained architectures. |

Limited x64 experiments may proceed while inventory is incomplete, but do not satisfy the freeze gate. Resolve TLS shortcomings before B03.

### Phase W1 — Signaling, incoming policy and RID build

Proposed components: `CallSession.cs`, `CallController.cs`, `CallProtocol.cs`, `CallSignaling.cs`, `ICallMedia.cs`, `CallSettings.cs`, `PeerEngine.Calls.cs`.

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| W01 | Windows / Pending | B03 | Serialized controller/snapshots, clock/fakes and incoming admission policy. | Java/C# fixtures match. |
| W02 | Windows / Pending | W01 | Capabilities/framing and explicit socket/TLS handoff. | No disposal/admission leaks. |
| W03 | Windows / Pending | W02 | Offline/trust/delete/reset/disposal hooks. | Cleanup outside locks; no stale generation events. |
| W03b | Windows / Pending | B03, W00a | AnyCPU-default-to-explicit-RID transition, pinned references/bridge, native copy rules and deterministic loading. | Clean normal outputs load without manual DLL/PATH setup. |
| **W03c** | **Windows / Pending** | **W03** | Load/save separate per-user call settings before call admission; serialized memory updates and atomic ordered writes. Preserve setting across messaging reset. | Disabled invitations decline before ring/media; outgoing/messaging unchanged; clear read/write error handling. |
| WT01 | Windows / Pending | W01–W03 | Headless production core/codec tests. | Pure managed harness independent of native/UI. |
| WT01b | Windows / Pending | W03b | Per-RID artifact/load/upgrade checks. | Matching process/native chain and retained data. |
| **WT01c** | **Windows / Pending** | **W03c** | Toggle/startup/persistence-failure and invite/Accept/toggle race tests. | Same policy as Java; accepted calls continue, pending ringing declines once, old saves cannot win. |

#### Build/publish contract

| Package | RID | Effective platform target |
|---|---|---|
| x64 | `win-x64` | `x64` |
| Native ARM64, if retained | `win-arm64` | `ARM64` |
| x86, if retained | `win-x86` | `x86` |

- Match full bridge/media/runtime chain to process architecture.
- Publish every retained RID explicitly; a RID list does not create a universal package.
- Keep framework-dependent deployment unless separately changed: `SelfContained=false`, matching .NET 9 Desktop Runtime.
- Isolate native/intermediate/publish output by RID.
- Version/RID names, deterministic developer/test targets and controlled DLL paths.
- Pure protocol harness may remain AnyCPU; native/UI tests match their package.
- Upgrade without changing data location or resetting identity.
- No cross-architecture DLL copying as a workaround.

[RID catalog](https://learn.microsoft.com/en-us/dotnet/core/rid-catalog), [publishing overview](https://learn.microsoft.com/en-us/dotnet/core/deploying/).

### Phase W2 — Audio, lifetime and quality monitoring

Proposed adapters: `WebRtcCallMedia.cs`, `CallAudioDevices.cs`, `AudioOwnership.cs`, `CallQualityMonitor.cs`.

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| W04 | Windows / Pending | W03, W03b, WT01b, W03c, WT01c | Media/Opus/mute/readiness/SDP/ICE/statistics; rooted callbacks and serialized events. | Real audio from production RID output; admission before media; safe failures. |
| W05 | Windows / Pending | W04 | Audio ownership and awaited draft/device release. | No competing capture; drafts survive. |
| W06 | Windows / Pending | W04, W05 | Devices/defaults/privacy/unplug; live switching only where verified. | Mute retained; device failures cannot leave false Connected state. |
| W07 | Windows / Pending | W06 | Tray hiding preserves calls; Exit/suspend/unrecoverable network change ends them. | Reopen same session; no redial or retained resources. |
| **W07q** | **Windows / Pending** | **W04, BQ01** | Normalize actual media statistics, bounded asynchronous polling and evaluator snapshots. | Equivalent semantics to Android; no native/UI blocking, stale callbacks or fabricated values. |
| WT02 | Windows / Pending | W04–W07 | Device/lifecycle faults and callback/disposal races across retained architectures. | No hangs, use-after-free or short-cycle resource growth. |
| **WT02q** | **Windows / Pending** | **W07q** | Stats extraction and evaluator/timeout/reset/disposal tests. | Shared quality fixtures and real-stat units pass. |

D01 uses permanent ownership and the admission policy, without waiting for complete quality presentation.

### Phase W3 — Interface, tray settings and quality display

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| W08 | Windows / Pending | W07, W07q | Call views/devices/mute/hangup plus reduced-quality indicator and concise hint. | Snapshot-driven, stable, accessible, distinct from route/terminal errors. |
| **W09** | Windows / Pending | W08, W03c | Tray **Allow incoming calls** check item, incoming indication, return-to-call/hangup. Bind setting to controller; Online/Offline remains separate. | Correct checked state at startup and after failures; toggle works while hidden; outgoing remains available. |
| WT03 | Windows / Pending | W08, W09 | UI/menu persistence, disabled incoming, stale events, quality transitions and accessibility. | No ring/balloon when disabled; current ringing removed on toggle; no UI flicker or duplicate announcement. |

Preserve existing voice-message seek/replay tests.

### Phase W4 — Shared acceptance, calibration and release

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| BT01 | Both / Pending | AT01, AT01d, WT01, WT01c | Real TLS/fake-media cross-process tests, including admission preference. | Every pairing/direction; policy off preserves outgoing/messaging, no ring/media; race and legacy cases pass. |
| BT02 | Both / Pending | AT02, AT02p, AT02q, AT03, WT02, WT02q, WT03, BT01 | Physical MVP matrix and approximately five-minute calls per pairing. | Controls, routes, proximity, settings, notifications and lifecycle verified. |
| **BTQ01** | **Both / Pending** | **BQ01, AT02q, WT02q, A09, W08** | Focused real-adapter quality calibration under clean LAN, sustained loss/jitter/delay, short spikes and recovery. Tune/version shared thresholds. | Both UIs show/recover appropriately without flicker; clean/muted/quiet/missing-stats cases do not falsely diagnose failure. Required before release enablement. |
| BT03 | Both / Pending | BT02, BTQ01 | Transfer coexistence, Offline resources/packets and existing regressions. | Native sockets/pollers/proximity resources stop; text/files remain correct. |
| W10 | Windows / Pending | W03b, WT01b, WT01–WT03, WT01c, WT02q, BT01–BT03, BTQ01 | Final RID package/notices/upgrade and settings-retention audit. | Correct dependencies/checksums; no untested architecture claim. |
| B04 | Both / Pending | A12, W10 | Status/comparison, selected-feature evidence and authorized local commits. | Implementation/automation/device evidence separate; no push/branch changes. |
| BH01 | Both / Pending — Hardening | BT03 | Long soaks, wider devices/Bluetooth, larger impairment/performance campaign. | Follow-up results; not prerequisite to MVP packaging after focused gates pass. |

#### Required MVP matrix

| Area | Cases |
|---|---|
| Pairings/flow | All three pairings, both initiators/hangup directions; accept/decline/cancel/timeout/Busy/glare/duplicates. |
| Incoming preference | Default/missing/corrupt settings; persist/restart/upgrade; toggle while Offline without going Online; disable while ringing; Accept race; connected call preserved; outgoing/text/files still work; no background ring/media when disabled. |
| Security | Verification/key change/revoke/forget, stale IDs, fingerprint mismatch and insecure negotiation. |
| Android ownership | One service controller; no Activity-owned media; pre-A06 termination versus post-A06 continuation. |
| Android proximity | Near/far on confirmed earpiece; route change/remote hangup while near; Bluetooth/speaker/wired exclusion; unknown route; unsupported sensor; Offline/destruction; no pause-triggered call end or delayed suppression. |
| Android Bluetooth | Physical connected-headset two-way observation, connect/disconnect, mute, Phone/Speaker override and cleanup; no best-effort waiver for broken routing. |
| Quality | Shared fixtures, real metric mapping, clean LAN, brief versus sustained impairment, hysteresis recovery, stale/missing/reset stats, quiet/muted audio, callbacks after end and accessible UI transitions. |
| Windows lifecycle/architecture | Tray/Exit/suspend/privacy/unplug; retained RID clean-machine load/upgrade; x86 decision; correct native/emulated ARM64 claims. |
| Networking | Blocked UDP/client isolation/wrong interface/loss/crash/signaling failure/Online-Offline races. |
| Audio ownership | Recording finalization, stale permission results, playback interruption and recovery. |
| Compatibility/data | Old/new messaging unchanged, bounded unsupported failure, upgrade identity/history/drafts/settings retention. |
| Packaging | Android ABIs/extraction/ELF/API 26/newest-device; optional proximity capability; Windows matching dependencies. |

#### MVP gates versus extended hardening

| Check | MVP gate | BH01 |
|---|---|---|
| Setup | Typically within five seconds after acceptance; respects deadline. | Timing distributions under heavy load. |
| Audio | Five-minute intelligible call per pairing; no sustained echo/growing delay. | Thirty-minute soaks, wider routes/devices. |
| Repetition | Ten short cycles plus physical repeats; no retained resources. | At least 100 cycles and prolonged resource analysis. |
| Incoming policy | Deterministic admission/persistence/UI/race tests and physical no-ring check. | Larger concurrent invitation stress. |
| Proximity | Physical near/far and cleanup on a supported device; unsupported fallback. | Wider OEM/sensor behavior. |
| Quality indicator | BQ01 plus focused BTQ01 calibration before default enablement. | Broader thresholds/performance characterization. |
| Latency | No obvious conversational lag; no unmeasured numeric claim. | External mouth-to-ear target below 250 ms. |
| Connectivity | Bounded failure/cleanup. | Extended loss/reordering/jitter combinations. |
| Offline/privacy | No capture/socket/poller/proximity retention, no unsolicited capture. | Repeated stress. |
| Transfers | Representative Normal/Fast transfer correct and controls responsive. | Saturation/CPU/battery profiling. |

Selected enhancements are release requirements, not optional flags left unimplemented. Security, cleanup, data loss, stuck proximity suppression and known routing failures cannot be deferred as hardening.

Run targeted tests during implementation and the full regression runner before release. Preserve voice drafts/scheduler, WAV/PCM, interoperability, Offline lifecycle, verification, transfers/resume, groups and Windows UI coverage. Supplement source-pattern Android lifecycle checks with service behavior tests.

Record revision, dependency, device/OS/architecture, route, network and result. Missing equipment leaves the associated physical acceptance pending.

### Phase W5 — Future video

| ID | Platform / status | Dependencies | Future work | Acceptance / testing |
|---|---|---|---|---|
| BV01 | Both / Deferred | Accepted voice release | Video capability, upgrade consent, negotiation generations/glare/rollback and common codec. Keep incoming admission separate from in-call video consent. | Voice-only compatibility; declined video preserves audio. |
| WV01 | Windows / Deferred | BV01 | Camera/renderer and retained-RID adapter support. | Denial/unplug releases camera without ending audio. |
| WV02 | Windows / Deferred | WV01 | Preview, remote video, camera selection/off and consent. | No capture before consent; safe presentation lifecycle. |
| BV02 | Both / Deferred | AV03, WV02 | Interoperability, audio priority, synchronization/downgrade and separate audio/video quality policy. | Audio survives video failure; Offline stops all media. |

#### Implementation order

1. **B00/B01 → W00a → A00/W00 → B02/B03:** prove dependencies, architectures and statistics mapping.
2. **A01–A03 → A03c/A03d and tests; W01–W03 → W03c and tests:** signaling, service ownership and incoming policy.
3. **A03b/AT01b and W03b/WT01b:** production dependency integration; may overlap step 2.
4. **BT01 → A04/A05 and W04/W05 → D01:** early integrated call.
5. **BQ01; A06/A07/A07p/A07q and W06/W07/W07q:** background, routing, proximity and quality.
6. **A3/W3:** complete menu/tray settings, notifications and indicators.
7. **BT02/BTQ01/BT03 and A11 → A12/W10 → B04:** release after all selected-feature gates.
8. **BH01:** extended hardening.
9. **Video phases:** later implementation using this foundation.
