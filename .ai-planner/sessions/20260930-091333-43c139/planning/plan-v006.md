# plan-v006 | version=6 | Voice calls for Android and Windows

Implement **one-to-one, full-duplex LAN voice calls** using the existing verified peer connections for signaling and **WebRTC-compatible media with Opus and DTLS-SRTP**. Keep call management separate from media tracks so video can be added later.

The first release includes the selected additions: **Allow incoming calls** on both platforms, **earpiece proximity handling** on Android, and a **connection-quality indicator** on both platforms.

This is planning only. No project files were modified, and no builds or tests were executed. All implementation and verification tasks are **Pending** until execution is explicitly requested. Video tasks are **Deferred**.

There are two major platform sections. Tasks marked **Both** define shared work once. Dependencies refer to task completion, including the stated acceptance checks.

## 1. Android

### Phase A0 — Shared architecture and paired feasibility

**Outcome:** select compatible media dependencies and prove Android ↔ Windows audio before building the complete feature.

#### Current implementation baseline

| Area | Current evidence and implication |
|---|---|
| Android application | Java, minimum API 26, target API 34; current manifest version 2.2.6, versionCode 33. |
| Android build | The javac/D8/aapt pipeline compiles the Java source directory without WebRTC dependencies and packages no WebRTC native libraries. Production build integration must precede real media implementation. |
| Android service | `MessengerService` owns the engine and Online/Offline lifecycle. Its current foreground-service type is `connectedDevice`. |
| Existing audio | Voice-message recording/playback belongs to the Activity and stops on pause. Those hooks must remain separate from service-owned calls. |
| Android permissions | `RECORD_AUDIO` exists. `MODIFY_AUDIO_SETTINGS`, microphone foreground-service permission/type, and the proximity wake-lock declaration must be added at their specified phases. |
| Android settings | `PeopleListView` builds the people menu. Incoming-call policy must be service-owned, rather than an Activity list-filter preference. |
| Networking | Both engines use verified TLS, five-field LM4 `HELLO`, short transaction handlers and 16 KiB ordinary line limits. Persistent call signaling needs explicit transport ownership and deadlines. |
| Existing capabilities | Group `CAPS=2` already has a meaning. Call capabilities must be independent. |
| Windows source | WinForms, `net9.0-windows`, BouncyCastle; no explicit RID, `PlatformTarget`, `SelfContained` or `PublishSingleFile` in the project. |
| Windows release evidence | The inspected 2.2.0 output and ZIP contain separate application files and a runtime configuration requiring .NET 9 frameworks. The extracted executable is an x64 apphost. This establishes that release’s deployment baseline, not every historical installation’s architecture. |
| Existing verification | Voice interoperability is wired into the regression runner. Existing status records distinguish implemented code, automated verification and device acceptance; a wired test is not evidence of a new passing run. |

Relevant Android files: [build.ps1](/D:/LAN-Messenger/source/android/build.ps1), [AndroidManifest.xml](/D:/LAN-Messenger/source/android/AndroidManifest.xml), [PeerEngine.java](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/PeerEngine.java), [MessengerService.java](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/MessengerService.java), [MainActivity.java](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/MainActivity.java), and [PeopleListView.java](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/PeopleListView.java).

Use current source together with `PROJECT_STATUS.md`, `android/STATUS.md` and `windows/STATUS.md`. Historical handoffs do not establish platform parity.

#### Requested review resolutions

| Reference | Binding resolution in this revision |
|---|---|
| R2-01 | A03c creates the permanent service-owned controller before A04. A04 and D01 use that same host. A06 adds microphone foreground-service behavior without moving ownership. |
| R2-03 | W00a decides architecture support, including x86, before B02 freezes dependencies. W03b explicitly replaces AnyCPU defaults with matching per-RID application/native outputs. |
| R3-02 | Record actual release deployment evidence in B00/W00a before selecting publish properties. The inspected 2.2.0 baseline is framework-dependent, separate-file deployment with an x64 apphost. |
| R4-02 | A03b unconditionally adds `android.permission.MODIFY_AUDIO_SETTINGS`; AT01b checks the packaged manifest before A04, and A11 audits it again. |
| R5-02 | A08/W08 explicitly show callers **“Declined”**, without claiming a human declined or revealing the preference. The shared per-peer invitation limit covers repeated manual redial, including policy-declined calls. |

#### Scope and milestones — Both

| Milestone | Scope |
|---|---|
| B02: feasibility demonstration | Foreground prototypes exchange encrypted Android ↔ Windows audio; proves dependencies, not release readiness. |
| D01: first integrated demonstration | Production builds support call, accept, mute, hangup, incoming policy and Offline cleanup. Android remains foreground-only until A06. |
| MVP release | Basic calls on Android ↔ Android, Android ↔ Windows and Windows ↔ Windows, including lifecycle, routing and all three selected additions. |
| BH01: extended hardening | Longer soaks, 100-cycle automation, external latency measurement and broader device/network testing. |
| Future video | Negotiated video tracks, explicit camera consent and rendering on the same foundation. |

First-release behavior:

- One verified direct contact and one pending/active call per device.
- Reachable IPv4 LAN peers, without an internet service dependency.
- Call, accept, decline, cancel, hangup, mute and basic route/device selection.
- Clear calling, ringing, connecting, connected and terminal states.
- Persisted **Allow incoming calls**, independent of Online/Offline; disabling it leaves outgoing calls available.
- Established Android calls continue through navigation and screen lock while the service remains alive.
- Windows calls continue while the application is hidden to the tray.
- Supported Android earpiece calls use proximity screen-off behavior.
- Both platforms display a stable **Connection quality reduced** indication based on validated statistics.
- Text and attachments continue working; calls and voice messages coordinate audio ownership.
- Offline ends call networking and capture. Returning Online never resumes a previous call.
- No automatic answering, recording or stored call audio.

Deferred: group calls, waiting/hold, durable call history, internet relays, automatic redial/reconnection, Bluetooth pairing/device management, comprehensive Bluetooth compatibility and video UI. The unselected local audio-check feature remains outside this plan.

#### Architecture — Both

Prefer libwebrtc-based media adapters. A managed Windows alternative qualifies only after proving the same processing, security, lifecycle, statistics and future-video requirements.

```text
Android Activity                       Windows call UI / tray
       ↓                                         ↓
MessengerService                       Application-owned controller
  CallController
       ↓                                         ↓
Admission policy → authenticated signaling → media adapter
       ↓                                         ↓
Audio routing / proximity / quality    Audio devices / quality
```

Android’s Activity supplies commands, permission flow and presentation. It never owns the call controller, peer connection, timers or media threads.

Reuse verified identities, contacts and lifecycle hooks. Preserve WAV, draft and attachment contracts. Calls must not use attachment scheduling or voice-message recording-duration limits.

Application-defined signaling is compatible with WebRTC peer connections. [WebRTC peer connections](https://webrtc.org/getting-started/peer-connections).

#### Dependency shortlist

These are initial engineering estimates, not delivery commitments. B02 selects exact revisions, hashes, licenses, toolchains and supported architectures.

**Android candidates**

| Candidate | Provides / requires proof | Initial effort |
|---|---|---|
| Preferred: `io.github.webrtc-sdk:android` | Community Java/JNI libwebrtc exposing `org.webrtc`. Evaluate a full AAR for Opus, DTLS-SRTP, AEC, statistics, ABIs, resources and dependencies. Do not assume an old Google Maven artifact remains a supported source. | Approximately 1–3 engineering days for inspection and initial raw-pipeline integration. [Repository](https://github.com/webrtc-sdk/android). |
| Fallback: pinned upstream libwebrtc self-build | Java/JNI peer connections and configurable media processing. Verify the chosen build’s codecs, AEC, statistics and ABI coverage. Own future builds and updates. | Approximately 5–10+ engineering days for infrastructure, then application integration; requires supported host/toolchain, `depot_tools`, GN/Ninja and Android AAR tooling. [Native Android documentation](https://webrtc.github.io/webrtc-org/native-code/android/). |

AEC must be enabled and physically tested; successful loading does not prove echo cancellation.

**Windows candidates**

| Candidate | Provides / requires proof | Initial effort |
|---|---|---|
| Preferred where architecture policy permits: Shiguredo `webrtc-build` plus a project-owned C ABI bridge | Native libraries and headers for Windows x64/ARM64; no assumed x86 artifact or application-specific .NET wrapper. Verify Opus, DTLS-SRTP, AEC, devices, statistics and runtime dependencies. Published builds omit H.264/H.265, so future video requires a tested common codec. | Approximately 3–7+ engineering days for the bridge and initial packaging. [Repository](https://github.com/shiguredo-webrtc-build/webrtc-build). |
| Fallback: upstream libwebrtc self-build plus C ABI bridge | Configurable native media stack. Every retained architecture, callback interface and statistics mapping requires proof. | Approximately 5–10+ engineering days for build infrastructure alone, plus bridge/application integration. [Native development](https://webrtc.github.io/webrtc-org/native-code/development/). |
| Alternative: SIPSorcery + SIPSorceryMedia.Windows + Opus/Concentus | Managed WebRTC/DTLS-SRTP with separate codec/device components. AEC, playout behavior, statistics and the complete architecture chain require validation or additional components. Future video requires codec/rendering work. | Approximately 2–4 days for basic audio; potentially substantially more for missing processing. Verify .NET 9 compatibility. [Repository](https://github.com/sipsorcery-org/sipsorcery), [DTLS-SRTP transport](https://sipsorcery-org.github.io/sipsorcery/api/SIPSorcery.Net.DtlsSrtpTransport.html). |

Archived Microsoft MixedReality-WebRTC is not the default dependency. [Repository](https://github.com/microsoft/MixedReality-WebRTC/).

#### Foundation tasks

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| B00 | Both / Pending | Execution authorized | Record revision, baseline results, device architectures and equipment. Record previous Windows artifact paths, deployment layout, runtime requirements and apphost architecture. Include Bluetooth and proximity-capable equipment. | Evidence separates source, automated checks and device acceptance. Unknown installations remain explicit. |
| B01 | Both / Pending | B00 | Draft the contracts below; evaluate the shortlist, build tools, routing helpers and statistics availability. | Candidate checklist, deployment proposal and statistics mapping ready. |
| A00 | Android / Pending | B01 | Prototype Java/JNI loading, audio, AEC, routing ownership, statistics and cleanup. Inspect API/ABI availability. | Physical audio and repeated cleanup demonstrated; missing integration work identified. |
| W00a | Windows / Pending | B01 | Inventory deployment and architectures; decide x86 support before dependency selection. Details in W0. | Evidence-backed deployment baseline and explicit support matrix. |
| W00 | Windows / Pending | B01, W00a | Media and persistent TLS feasibility against deployment, architecture and statistics requirements. | Physical audio, concurrent signaling and retained-architecture feasibility evidence. |
| B02 | Both / Pending | A00, W00, W00a | Pair prototypes without external ICE servers. Select exact dependencies, licenses, reproducible recipes, deployment modes and architectures. | Encrypted two-way audio, mute, processing, cleanup and required statistics proven. No unresolved silent architecture exclusion. |
| B03 | Both / Pending | B02 | Freeze wire/state/admission contracts, limits, normalized statistics, fixtures, routing ownership and dependency/RID manifest. | Independent implementations have precise shared inputs. |
| BQ01 | Both / Pending | B03 | Implement equivalent pure Java/C# quality evaluators using shared timestamped fixtures. | Matching degradation/recovery, counter-reset, missing-data and hysteresis behavior. |

If feasibility fails, revise the dependency choice or adapter. Do not replace requirements with plaintext media, WAV streaming or fabricated statistics.

### Shared call contracts — Both

#### Compatibility, admission and framing

- Preserve discovery, five-field `HELLO`, group `CAPS=2` and attachment frames.
- Add authenticated `CALLCAPS`; query freshly before outgoing calls. Capabilities describe implementation support, not incoming-call preferences.
- Negotiate and acknowledge `CALLOPEN` before switching to call framing.
- Admit verified direct peers only; reject self/group calls and identity mismatches.
- Advertise media capability only after successful initialization that does not capture audio.
- Preserve ordinary LM4’s 16 KiB line limit.
- Call frames use a four-byte unsigned big-endian length followed by UTF-8 JSON.
- Initial limits: 64 KiB per frame, 48 KiB SDP and 128 candidates per negotiation. Bound queues by both count and bytes.
- Envelope fields: protocol version, type, call ID, sender sequence, negotiation generation and typed body.
- Specify duplicate keys, malformed input, required/optional fields and unknown-message handling in B03.
- Reject oversized lengths before allocation; apply whole-frame deadlines.
- Use one reader and one serialized writer per call channel.
- Transfer TLS/socket ownership explicitly out of the ordinary transaction handler. Do not let an enclosing disposal scope close the handed-off channel.
- Release ordinary inbound worker capacity after handoff while retaining engine cancellation tracking.
- Reserve bounded signaling admission independently of long transfers.

**Invitation throttling:** freeze matching constants and tests in B03. Start with **five distinct, valid authenticated invitations per peer in a rolling 60-second window**, counting attempts regardless of accepted, Busy, manually declined or policy-declined outcome.

Apply the limiter before preference-specific handling, uniformly whether incoming calls are enabled or disabled. Excess invitations receive the existing generic `BUSY` outcome and close without ringing or media. Duplicate frames for an existing call follow idempotency rules and never create another invitation. Bound and expire limiter state; exhausted global admission capacity must reject rather than allocate without limit. Reconnecting a signaling channel does not reset the peer’s window.

There is no automatic retry. Repeated manual redial cannot bypass the receiver’s limit. This does not replace bounded TLS handshakes, connection admission or frame validation.

#### Incoming-call preference and caller presentation

**Label:** “Allow incoming calls.” **Default:** enabled when the setting is genuinely absent. Persist per device independently of Online preference, peer-list filters and outgoing capability.

- Load policy before admitting invitations. Messaging may start independently, but call admission remains closed until policy is ready.
- Corrupt/unreadable settings disable incoming admission for that session and show a recoverable settings error.
- For an authenticated invitation within admission limits, check current policy before `RINGING`, notifications, ringtone, audio ownership or per-call media initialization.
- Disabled policy returns the existing `DECLINE` and releases the invitation.
- Both caller UIs show **“Declined”**, exactly as for a manual decline. Do not say that the contact pressed Decline, is ignoring the caller, or has disabled incoming calls.
- Do not add a preference flag, special decline reason or changed capability advertisement.
- Outgoing calls, messaging, discovery and actual Online state remain unchanged.
- Disabling while an incoming invitation is still ringing declines it and removes its UI/notification.
- Already accepted Connecting/Connected calls continue.
- Serialize toggle, invitation and Accept commands. Whichever commits first determines eligibility.
- Check incoming policy before glare handling; simultaneous dialing cannot bypass disabled admission.
- Enabling affects future invitations only.
- Apply changes immediately in memory; serialize persistence. On save failure, retain the session choice and show “Setting was not saved.” Older writes cannot overwrite newer choices.
- Preserve the setting across the application’s chat deletion and messaging-data reset. OS-level application-data clearing or uninstall resets local preferences.
- Declined calls create no durable queue or retry.

Android uses service-owned call preferences exposed through commands/snapshots. Windows uses a separate atomic call-settings file in the existing per-user data directory. Neither reuses the network preference file.

#### Messages, security and state

Define `INVITE`, `RINGING`, `ACCEPT`, `DECLINE`, `BUSY`, `CANCEL`, `OFFER`, `ANSWER`, `ICE`, `MEDIA_READY`, `HANGUP`, `ERROR`, `PING` and `PONG`, including allowed sender, state, fields and duplicate behavior.

Bind each session to its random call ID, verified peer/fingerprint, authenticated channel, engine generation and negotiation generation. Verify media fingerprints received through authenticated signaling.

Reject insecure descriptions and unexpected video/data sections in voice version 1. Validate SDP/candidates before native processing. Logs may contain bounded aggregate diagnostics, never audio, keys or complete signaling credentials.

```text
Idle → OutgoingRinging / IncomingRinging
     → Connecting → Connected → Ending → Idle
```

- Obtain outgoing microphone permission before inviting, without starting capture.
- Accept only a current, eligible invitation through the appropriate permission/service flow.
- Caller offers after acceptance; callee answers.
- Capture requires explicit local action and remote acceptance.
- Each side sends local readiness independently; Connected requires both sides ready.
- Bound early ICE candidates by negotiation identity.
- Resolve simultaneous dialing by deterministic caller-ID ordering after admission checks.
- Serialize terminal races and ignore stale permissions, callbacks and notification actions.
- Preserve a terminal UI snapshot while returning the controller to Idle.
- Never persist or resume calls after restart.
- With admission enabled, an unrelated invitation during an occupied session receives Busy.

#### Timers and cleanup

| Timer | Proposed default |
|---|---|
| Capability/open | Ten seconds total for connection/TLS setup. |
| Ringing | Thirty seconds; permission UI does not extend it. |
| Media setup | Fifteen seconds after acceptance. |
| Heartbeat | Every five seconds; fail after fifteen seconds without valid inbound signaling. |
| Route recovery | Three seconds before clear failure termination. |
| Local teardown | Target completion within two seconds. |

Use monotonic clocks and call-specific transport deadlines instead of inheriting ordinary short-transaction timeouts. Signaling loss or terminal ICE failure ends the call; bounded transient grace is not automatic reconnection.

Cleanup stops capture, releases proximity behavior, invalidates callbacks, closes native and signaling networking, cancels quality polling/timers, releases audio ownership and restores app-owned settings. Offline must include native sockets. Do not start another engine generation while the old call still owns resources.

#### LAN media policy

- No external STUN/TURN or internet fallback.
- Use UDP host candidates on the authenticated signaling interface.
- Initially accept remote IPv4 candidates matching the authenticated signaling endpoint.
- Reject public, multicast, unspecified, unrelated-interface and production loopback candidates.
- Filter both SDP and trickled candidates; explicitly handle mDNS candidates.
- Report blocked UDP, client isolation and unsupported-network failures clearly.
- Document media firewall/port requirements without changing discovery/control ports unnecessarily.
- Keep media outside attachment queues and Android attachment-upload accounting.

#### Quality-indicator contract

The indicator describes observed network degradation. In version 1, loss/jitter describe **audio received by this device**, supplemented by transport RTT. It does not diagnose the microphone or guarantee the complete audio path; the two devices may show different results.

**Sampling and normalization**

- Sample asynchronously about once per second during Connected, with one request in flight and bounded timeout/cancellation.
- Normalize local inbound audio packet/loss counters and jitter, plus selected ICE candidate-pair RTT.
- Document adapter mappings and convert jitter/RTT explicitly to milliseconds.
- RTT is not mouth-to-ear latency.
- Use five-second rolling windows and packet-counter deltas; initially require 50 expected packets per window for loss evaluation.
- Reset baselines on source, SSRC or generation changes and counter resets.
- Handle negative loss corrections without negative percentages or artificial spikes.
- Missing, invalid, repeated-old or stale measurements are unavailable, never zero.
- Silence duration, media amplitude and mute state must not imply microphone failure.
- Do not add signaling exchanges or persistent raw-statistics storage.

The underlying metrics have distinct cumulative-counter and time-unit semantics that adapters must preserve. [WebRTC statistics specification](https://www.w3.org/TR/webrtc-stats/).

**Initial policy, calibrated before release**

| Rule | Initial value |
|---|---|
| Warm-up | Five seconds of usable observations after Connected or baseline reset. |
| Enter Reduced | Loss above 5%, rolling jitter above 30 ms, or rolling RTT above 300 ms for three consecutive valid evaluations. |
| Recover | Triggering metrics below lower thresholds—loss below 2%, jitter below 20 ms, RTT below 200 ms—for ten seconds, without another degraded metric. |
| Insufficient evidence | No healthy claim. A metric without fresh evidence for more than three seconds becomes Unknown; retain Reduced only if other fresh evidence supports it. |
| Call ends | Stop polling and clear quality state immediately. |

Use `Unknown`, `Normal` and `Reduced` internally. Normal needs no badge. Reduced displays:

> Connection quality reduced  
> Check your LAN connection. On Wi-Fi, try moving closer to the router.

Keep the hint inline or available on demand, without modal dialogs or repeated announcements. Announce a transition accessibly once. Quality does not automatically mute, reroute, reconnect or terminate a call.

BQ01 verifies deterministic behavior. BTQ01 must calibrate actual adapters and both UIs, including one-direction impairment, before release enablement.

### Phase A1 — Android signaling, policy and production integration

Proposed components: `CallSession`, `CallController`, `CallProtocol`, `CallSignaling`, `CallMedia` and `CallSettings`. Keep state, codec and quality logic independent of Android/JNI where possible.

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| A01 | Android / Pending | B03 | Serialized controller, snapshots, injectable clock/fakes, admission policy and invitation limiter. | States, glare, duplicates, rate limits and terminal races are deterministic. |
| A02 | Android / Pending | A01 | Capabilities, framing and authenticated transport handoff. | Production TLS, legacy, partial-frame and backpressure checks pass. |
| A03 | Android / Pending | A02 | Engine generation, trust, delete/reset and shutdown hooks. | No stale activity after Offline/restart. |
| A03b | Android / Pending | B03 | Integrate production javac/D8 dependencies, all DEX files, native libraries and required AAR resources/metadata. **Unconditionally declare `android.permission.MODIFY_AUDIO_SETTINGS`.** | Normal APK installs and initializes a media factory without capture or manual dependency setup. |
| A03c | Android / Pending | A03 | Create exactly one controller inside existing `MessengerService`, alongside the engine. Inject fake media; expose commands/snapshots and foreground eligibility. | Rebinding/recreation creates no second controller; no retained Activity reference. |
| A03d | Android / Pending | A03c | Service-owned preference loading, serialized updates/persistence and admission gate. | Disabled policy declines before ringing/media; startup/save failures follow the contract. |
| AT01 | Android / Pending | A01–A03 | JVM production core/codec tests and harness wiring. | Shared fixtures match C#; no Android/JNI dependency in pure tests. |
| AT01b | Android / Pending | A03b | Inspect APK dependencies, native loading and packaged manifest. | `MODIFY_AUDIO_SETTINGS` is present; no-capture initialization succeeds; native failure disables calling without crashing messaging. |
| AT01c | Android / Pending | A03c | Service binding/lifecycle tests using fake media. | One session; Activity cannot replace/dispose call media; pre-A06 foreground loss terminates cleanly. |
| AT01d | Android / Pending | A03d, AT01 | Policy startup/save/race tests and repeated invitations while disabled. | No ringing/notification/media; accepted calls continue; older saves cannot win; declines count toward throttling. |

A03b may overlap controller work, but both production packaging and permanent service/policy hosting must finish before A04.

#### Permanent ownership

- The service owns the controller, executor, signaling, media factory/session, quality polling and proximity adapter.
- Activity handles presentation and permission results, sending identity-checked commands.
- A04 replaces the service’s fake media adapter; there is no temporary Activity-owned implementation.
- UI subscriptions detach/reattach without recreating resources.
- Voice-message `onPause` cleanup remains limited to voice messages.
- Before A06, loss of valid foreground eligibility requests service-controlled termination.
- After A06, Activity pause—including proximity-related screen-off—does not end or recreate the call.
- Offline, engine failure and service destruction clean up without Activity cooperation.

#### Android packaging and permission policy

Implement production integration in A03b; audit final release artifacts in A11.

- Planned universal ABIs: `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`, verified at B02. Missing coverage requires an explicit support decision.
- Prefer one universal APK for sideload upgrades.
- Use explicit `extractNativeLibs=true` with compressed `.so` entries; inspect actual ZIP compression methods.
- Apply ordinary ZIP alignment before signing. Four-byte ZIP alignment does not prove ELF page-size compatibility.
- Verify native dependencies against supported 16 KiB environments.
- A later direct-from-APK policy would require uncompressed libraries and separately validated alignment.
- Include every DEX and required native dependency, not only `classes.dex`.
- If raw tooling cannot package the chosen dependency reliably, perform the necessary build migration inside A03b, before A04.
- Record baseline/new APK size, delta, per-ABI contribution and installed/extracted footprint.
- Verify loading on API 26 and the newest tested device; claim 16 KiB compatibility only after verification.
- Add `MODIFY_AUDIO_SETTINGS` directly to the application manifest, regardless of dependency manifest contents. The raw build must not assume automatic AAR manifest merging.

`MODIFY_AUDIO_SETTINGS` is a normal manifest permission, separate from the runtime microphone permission; it is mandatory for this implementation’s communication audio behavior. [Android permission reference](https://developer.android.com/reference/android/Manifest.permission#MODIFY_AUDIO_SETTINGS).

Native extraction and ELF compatibility require separate checks. [Extraction setting](https://developer.android.com/guide/topics/manifest/application-element#extractNativeLibs), [page-size guidance](https://developer.android.com/guide/practices/page-sizes).

### Phase A2 — Android audio, routing, proximity and quality

Proposed adapters: `WebRtcCallMedia`, `CallAudioRouter`, `AudioOwnership`, `CallProximity` and `CallQualityMonitor`.

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| A04 | Android / Pending | A00, A03b, AT01b, A03c, AT01c, A03d, AT01d | Install real media into the permanent service host: Opus, SDP/ICE, mute, readiness, statistics and disposal. Foreground-only until A06. | Production-build physical audio and cleanup; permission/build prerequisites already present; no Activity-owned peer connection. |
| A05 | Android / Pending | A04 | Audio ownership and awaited release/finalization of voice-message devices and drafts. | Drafts survive; failed release blocks competing capture. |
| A06 | Android / Pending | A04, A05 | Add microphone foreground-service permission/type masks and background-continuation behavior. **No ownership migration.** | Home/lock/recreation preserve calls after eligible promotion; failed promotion cannot permit background capture. |
| A07 | Android / Pending | A06 | Communication focus/mode and routing; use the unconditional permission supplied by A03b. Coordinate library routing helpers. | One route owner; confirmed routes, mute retention and bounded failure recovery. |
| A07p | Android / Pending | A06, A07 | Service-owned proximity behavior and wake-lock declaration; optional capability handling. | Earpiece near/far behavior works; route/terminal cleanup releases immediately; unsupported devices remain usable. |
| A07q | Android / Pending | A04, BQ01 | Normalize native statistics, run bounded asynchronous polling and update snapshots. | Correct units/baselines; no blocking or stale callbacks after termination. |
| AT02 | Android / Pending | A04–A07 | Physical lifecycle, audio routing and fault tests, including Bluetooth-connected observation. | Denial, unplug, route failure and service death retain no capture/session. |
| AT02p | Android / Pending | A07p | Fake-policy and physical proximity tests. | No stuck-dark screen, inappropriate route blanking or pause-triggered call termination. |
| AT02q | Android / Pending | A07q | Real statistics extraction plus injected timeout/reset/disposal tests. | Shared quality fixtures and native units pass. |

Microphone service promotion must follow Android’s eligible permission/foreground flow. [Foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

#### Routing and Bluetooth policy

Allow already connected, system-selected Bluetooth audio best-effort. Pairing, scanning and device management remain deferred.

- Default to System audio; display the observed route when identifiable.
- Offer System, Phone where available, and Speaker. Phone selection must request the built-in route, rather than merely clearing a preference.
- One coordinator owns app route requests. Integrate or disable competing library helpers.
- Use guarded communication-device APIs on API 31+ and a tested legacy implementation.
- For API 26–30, do not assume communication mode starts SCO. B03 must freeze either coordinated SCO start/stop under the router or a built-in-route fallback that makes no Bluetooth-call claim.
- Avoid Bluetooth-profile access. If a chosen dependency needs protected APIs, handle denied/revoked permission without breaking ordinary phone/speaker calling.
- Preserve mute during changes.
- Report route/device failures and recover within the route deadline or terminate clearly.
- A successful API call or packet counters do not prove audible two-way speech.
- Clear app requests, listeners, mode and focus on termination without overwriting unrelated later user changes.
- Route transitions and confirmed route identity drive proximity eligibility. Unknown route means proximity suppression is off.

[AudioManager](https://developer.android.com/reference/android/media/AudioManager), [VoIP routing guidance](https://developer.android.com/develop/connectivity/telecom/voip-app/api-updates), [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).

**AT02 physical observation:** use at least one paired headset/device. Check two-way speech, connection/disconnection during calls, mute retention, Phone/Speaker override, rejected route requests and post-call voice-message recovery. Record API level, actual route, permission state and legacy policy if applicable. Silent/broken audio must recover or terminate; “best-effort” is not a waiver.

#### Proximity contract

- Enable only while Connected, with confirmed built-in earpiece routing and supported proximity screen-off capability.
- Use `PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK` and the normal `WAKE_LOCK` permission; keep hardware optional.
- Let the platform handle near/far behavior.
- The service owns one idempotently managed, non-reference-counted proximity wake lock.
- Release immediately when a route change begins, routing becomes unknown/non-earpiece, Connected ends, Offline begins or service/session cleanup starts.
- Do not use delayed release waiting for the sensor to become far on those transitions.
- Reacquire only after eligibility is confirmed.
- Never enable for Speaker, Bluetooth, wired/USB, ringing or Connecting.
- Preserve eligible behavior through Activity pause/recreation; avoid lifecycle-driven release/reacquire loops.
- Cleanup removes this feature’s suppression; it does not force-unlock an independently locked device.
- Unsupported capability or acquisition failure leaves normal screen behavior and the call intact.

No additional CPU/Wi-Fi wake lock is implied. [PowerManager proximity support](https://developer.android.com/reference/android/os/PowerManager).

#### Other lifecycle rules

- Dispatch explicit service actions; stale or unknown actions cannot turn networking Online.
- Preserve `START_NOT_STICKY`; no call resurrection.
- Keep microphone hardware optional for messaging-only devices.
- Ringing never acquires microphone ownership or discards recordings.
- Guard voice-message capture/playback in command and UI layers.
- Notification Accept opens a visible permission flow.
- Use separate permission request identities and revalidate current call/policy before acceptance.

#### Early integrated demonstration

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| D01 | Both / Pending | A04, A05, W04, W05, BT01 | Minimal development controls use production signaling/media and permanent ownership. Android controls bind to `MessengerService`. Use a validated built-in/wired route. | Two-minute calls both directions, mute/hangup/Offline, disabled incoming and successful outgoing. Pre-A06 foreground loss ends cleanly. |

D01 does not wait for notification polish, proximity or quality presentation. Those selected features still gate the final MVP.

### Phase A3 — Android interface, settings and notifications

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| A08 | Android / Pending | A06, A07, A03d | Direct-chat Call action and **Allow incoming calls** switch in the people menu, bound to service policy. Explicit caller terminal wording: **“Declined”** for every ordinary `DECLINE`. | Menu state survives recreation/restart; outgoing remains available while incoming is disabled. No claim of human action, preference disclosure or automatic retry. |
| A09 | Android / In progress | A08, A07p, A07q | Call views, duration, mute, route, hangup/return-to-call and quality hint. **Call screen follows the accepted Messenger reference layout** (A09p): dark teal header carrying the peer name as an uppercase headline with the timer under it, the peer's picture in the window between, one large red disc floating above a teal control bar holding chat / speaker / microphone, and a minimised bar as the same teal. Ring screens use the same frame. | Accessible snapshot-driven UI; route, quality and connection states remain distinct. Verified on two physical phones by UI-hierarchy assertions, not by inspection. |
| A09p | Android / Done | A09 | Call screen redesigned to the reference layout: teal header (`BAR`/`BAR_DIM`), `callTitle` uppercase name, big peer picture with initial-disc fallback (`avatarView`), `controlBar` with 💬 / 🔈|🔊 / 🎤|🔇, `hangupCircle` as a rotated-📞 disc, 🔽 minimise in the header. Chat control opens the conversation and leaves the call running. Ring screens reuse the shell with worded Accept/Decline and Cancel. | `tests/CallCheck.java` unchanged for layout; two-device acceptance asserts all four regions, bar order and spacing, and that no wide text button survives. |
| A09q | Android / Done | A09p | Fixes found by that acceptance run: the floating hang-up disc no longer covers Accept/Decline while ringing (tapping Accept used to hang the call up); the collapsed bar can be expanded again (`hide` then `render`, because a matching identity test otherwise left the bar in place); Back toggles a live call between full and minimised instead of hiding it (`CallView.backWhileLive`), so a running call is always on screen. | `M1`–`M8` in the two-device acceptance: the minimised bar carries a running clock, tapping it restores the call screen, and two Backs restore it again without ending the call. |
| A09t | Android / In progress | A09s | `Group members` removed from direct chats (it opened a dialog that could not open) and menu dispatch made explicit rather than ending in `else showMembers()`. TalkBack no longer reads the clock aloud every second. The return-to-call bar updates text in place instead of rebuilding at 1 Hz. Hang-up disc keeps its specific label; dead `isCollapsed()` removed; `N147` de-flaked and `N146` rewritten around the reported symptom. | Direct and group menus verified on device (2.2.31). `N156`–`N166` pin the three decisions, which had to move into `CallUi` because the harness cannot load `CallView`/`MainActivity`. **The tap-through fix is unverified on hardware** — the second phone is off the network. |
| A09s | Android / In progress | A09q | Fixes from independent review + the user's own reports: the full-screen panel now swallows the taps it does not use (the overlay was transparent to touches, so a tap beside the avatar opened the conversation behind the call); 💬 records the dismissal instead of having it undone by `showChat`'s own `render()`; the `Return / End` bar keys on its rendered words, not on the state enum it froze at; `LOCAL_DECLINE` reads "You declined the call." and `SIGNALING_LOST` names no side. | `N152`/`N154`/`N155` pin the wording. The three control-flow fixes have **no** automated coverage — `CallCheck` cannot reach `CallView`/`MainActivity` — and the two-device acceptance run has not been valid since they landed. Still open: TalkBack announces the clock every second. |
| A09r | Android / Done | A09p | Call clock starts when the call is **answered**, not when it was created (`CallSession.elapsedMs` measures from `connectedAtMs`). `CallUi.endHint` gives every end reason a plain sentence, so "Connection lost" became "Disconnected" plus "Lost the link to the other phone…". The `Return / End` bar is rebuilt when its words change, not only when it appears. | `N143`–`N153`: no clock while ringing, clock runs from answer, ringing time is not counted, and every end reason has a label plus a distinct sentence naming whose side it was. |
| A10 | Android / Pending | A09 | Current-call notifications/actions and immediate removal after policy-driven decline. | Disabled calls never ring/notify; stale Accept cannot bypass policy. |
| AT03 | Android / Pending | A08–A10 | Menu persistence, caller wording, repeated redial, lock/background/recreation, accessibility and notification races. | Manual and policy decline look identical to the caller; throttled attempts remain generic Busy; no hidden media/ringing or implicit Online transition. |

Do not require full-screen intent privileges or Telecom integration for this release. A stopped service cannot receive LAN calls.

### Phase A4 — Android release audit and acceptance

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| A11 | Android / Pending | A03b, AT01b, AT02, AT02p, AT02q, AT03 | Audit final dependencies/licenses, ABIs, APK size, extraction/ELF policy and versioned filename. **Verify packaged `MODIFY_AUDIO_SETTINGS` unconditionally**, alongside microphone FGS and proximity declarations. | API 26/newest-device loading; optional microphone/proximity hardware causes no unintended install exclusion; recorded size delta and ABI contributions. |
| A12 | Android / Pending | A11, AT01, AT01c, AT01d, BT01–BT03, BTQ01 | Produce/sign final release after MVP gates; preserve package/signer and increment versionCode. | Upgrade retains identity/history/drafts and saved call preference; no uninstall/key replacement. |
| A13 | Android / Pending | A12 | Update Android status/comparison with code, tests, device acceptance, routes and quality calibration. | Evidence and remaining limitations are explicit. |

Outputs belong in `D:\LAN-Messenger\outputs` when execution has access. Preserve the canonical source tree, branch/remotes and signing material. Exclude `.private` from archives. Commit locally; push only when requested.

### Phase A5 — Future Android video

| ID | Platform / status | Dependencies | Future work | Acceptance / testing |
|---|---|---|---|---|
| AV01 | Android / Deferred | Accepted voice release, BV01 | Service-owned camera adapter, optional capability and eligible camera permission/service flow. | Voice-only calls never open the camera; denial preserves audio. |
| AV02 | Android / Deferred | AV01 | Preview, remote video, camera switching/off and consent. Activity owns rendering surfaces only. Define proximity behavior explicitly for video. | No accidental camera activation or unexplained screen blanking. |
| AV03 | Android / Deferred | AV02, WV02 | Video interoperability, thermal/battery, synchronization and audio-route checks. | Video failure preserves audio; Offline stops every media resource. |

Build only extension points during voice work: media kinds, negotiation generations, track controls and UI-independent adapters.

## 2. Windows

### Phase W0 — Deployment, architecture and media feasibility

**Outcome:** establish the previous deployment baseline and decide process-architecture support before freezing native dependencies.

Relevant files: [LanMessenger.csproj](/D:/LAN-Messenger/source/windows/LanMessenger.csproj), [SecureChannel.cs](/D:/LAN-Messenger/source/windows/SecureChannel.cs), [PeerEngine.cs](/D:/LAN-Messenger/source/windows/PeerEngine.cs), [Program.cs](/D:/LAN-Messenger/source/windows/Program.cs), and [ChatWindowVoicePlayback.cs](/D:/LAN-Messenger/source/windows/ChatWindowVoicePlayback.cs).

#### Observed release baseline

Read-only inspection of `D:\LAN-Messenger\outputs\LanMessenger-Windows-2.2.0` and its ZIP established:

- Separate `LanMessenger.exe`, `LanMessenger.dll`, `.deps.json`, `.runtimeconfig.json` and BouncyCastle dependency.
- Runtime configuration requests `Microsoft.NETCore.App` and `Microsoft.WindowsDesktop.App` version `9.0.0`.
- The listed package contains no bundled CLR/runtime files.
- The extracted apphost’s PE machine value is `0x8664`, meaning x64.

This is evidence of **framework-dependent, separate-file deployment with an x64 apphost** for the inspected release. It is not evidence that all users are x64 or that no one launches the managed DLL using another runtime.

B00/W00a must record this evidence and inspect other packages actually used as upgrade baselines. Capture package/version, runtime configuration, runtime-file presence, separate/single-file layout, apphost architecture and known launch method. Mark unavailable installation information Unknown.

#### Architecture and deployment decision

| Item | Required evidence or decision |
|---|---|
| Existing users | OS, process architecture, installed Desktop Runtime, previous package and executable/launch method. |
| x64 | `win-x64` with matching managed process and native chain. |
| ARM64 | Native `win-arm64` versus tested x64 emulation; label these separately. |
| x86 | Explicit retain/exclude decision; distinguish an x86 process on x64 Windows from a 32-bit-only OS. |
| Native dependencies | Actual build/artifact, bridge ABI and runtime dependencies for every retained architecture. |
| Deployment | Match the evidenced framework-dependent/separate-file baseline unless a deliberate migration is added and validated. Do not infer mode solely from the project file. |
| Upgrade data | Same user/data location, preserving DPAPI identity/history/drafts and saved call preference. |

Default: do not silently drop existing x86 users. If x86 use exists or remains unknown, B02 cannot freeze an x64/ARM64-only provider as the complete solution. Prove a compatible x86 path or obtain an explicit scope decision before that gate. A managed candidate or upstream source build does not automatically establish x86 support.

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| W00a | Windows / Pending | B01 | Record previous artifacts’ actual deployment modes and apphost architectures; inventory installations; freeze architecture policy, RID matrix and loading/toolchain requirements. | Deployment evidence precedes publish-property selection; explicit x86 disposition precedes dependency freeze. |
| W00 | Windows / Pending | B01, W00a | Prove Opus/AEC/DTLS-SRTP, statistics, devices, callbacks and disposal. Test persistent TLS concurrent reads/writes, deadlines and cancellation. | Physical paired audio and viable retained architectures; TLS shortcomings resolved before B03. |

Limited x64 experiments may proceed while inventory is incomplete, but do not satisfy B02.

### Phase W1 — Signaling, incoming policy and production RID integration

Proposed components: `CallSession.cs`, `CallController.cs`, `CallProtocol.cs`, `CallSignaling.cs`, `ICallMedia.cs`, `CallSettings.cs` and `PeerEngine.Calls.cs`.

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| W01 | Windows / Pending | B03 | Serialized controller/snapshots, clock/fakes, admission policy and limiter. | Java/C# fixtures match, including declined/redial attempts. |
| W02 | Windows / Pending | W01 | Capabilities, framing and explicit socket/TLS handoff. | No premature disposal or admission leaks. |
| W03 | Windows / Pending | W02 | Offline, trust, delete/reset and application-disposal hooks. | Cleanup outside engine locks; stale events cannot revive sessions. |
| W03b | Windows / Pending | B03, W00a | Explicit AnyCPU-default-to-per-RID transition, pinned dependencies/bridge, native-copy rules and deterministic loading. Encode the evidenced deployment policy. | Clean ordinary outputs load without manual DLL copying or PATH changes. |
| W03c | Windows / Pending | W03 | Separate per-user call settings, loaded before admission; immediate serialized state changes and atomic ordered persistence. | Disabled calls decline before ringing/media; save/read failures follow shared policy; application messaging reset preserves setting. |
| WT01 | Windows / Pending | W01–W03 | Headless production core/codec tests. | Pure harness remains independent of native/UI dependencies. |
| WT01b | Windows / Pending | W03b | Per-RID artifact/load and upgrade checks against recorded previous packages. | Correct process/native architecture and deployment prerequisites; existing data retained. |
| WT01c | Windows / Pending | W03c, WT01 | Policy loading, persistence failures, invitation/Accept/toggle races and repeated redial tests. | Same Java behavior; accepted calls continue; pending ringing ends once; delayed saves cannot win. |

#### Build and publish contract

| Package | RID | Effective platform target |
|---|---|---|
| x64 | `win-x64` | `x64` |
| Native ARM64, if retained | `win-arm64` | `ARM64` |
| x86, if retained | `win-x86` | `x86` |

- Match the entire bridge/media/runtime chain to process architecture.
- Publish each retained RID explicitly; a list of RIDs does not create a universal native package.
- For the inspected 2.2.0 upgrade path, explicitly set **`SelfContained=false`, `PublishSingleFile=false`, `UseAppHost=true`**, preserving separate-file framework-dependent deployment.
- Document the matching .NET 9 Desktop Runtime prerequisite for each supported process architecture.
- W00a must reconcile any other evidenced deployment mode before W03b freezes properties; do not silently add or remove a runtime prerequisite for those users.
- Keep intermediate/native/publish output isolated by RID.
- Provide version/RID-specific package names and deterministic developer/test targets.
- Load native DLLs only from controlled package locations.
- Keep pure protocol tests AnyCPU where useful; native/UI tests match their package.
- Preserve the existing default data directory and `LAN_MESSENGER_DATA` behavior.
- No cross-architecture DLL copying as a workaround.
- WT01b and W10 verify the published artifact, not just project properties.

Framework-dependent and self-contained publishing have different runtime requirements; explicit properties prevent accidental changes during RID adoption. [.NET publishing overview](https://learn.microsoft.com/en-us/dotnet/core/deploying/), [RID catalog](https://learn.microsoft.com/en-us/dotnet/core/rid-catalog).

### Phase W2 — Audio, application lifetime and quality

Proposed adapters: `WebRtcCallMedia.cs`, `CallAudioDevices.cs`, `AudioOwnership.cs` and `CallQualityMonitor.cs`.

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| W04 | Windows / Pending | W03, W03b, WT01b, W03c, WT01c | Real media, Opus, mute, readiness, SDP/ICE and statistics. Root native callbacks and serialize events. | Physical audio from production RID output; admission precedes per-call media; initialization failures remain safe. |
| W05 | Windows / Pending | W04 | Audio ownership and awaited voice-message draft/device release. | No competing capture; drafts survive; failed handoff prevents calling. |
| W06 | Windows / Pending | W04, W05 | Device/default selection, privacy denial and unplug recovery; live switching only when verified. | Mute preserved; failure cannot leave misleading Connected state indefinitely. |
| W07 | Windows / Pending | W06 | Application-owned session survives hiding to tray; Exit/suspend/unrecoverable network change terminates it. | Reopening shows the same call; no retained resources or automatic redial. |
| W07q | Windows / Pending | W04, BQ01 | Normalize actual statistics, bound asynchronous polling and publish quality snapshots. | Android-equivalent semantics; no fabricated values, native/UI blocking or stale callbacks. |
| WT02 | Windows / Pending | W04–W07 | Device/lifecycle faults and callback/disposal races on retained architectures. | No hangs, use-after-free or short-cycle resource growth. |
| WT02q | Windows / Pending | W07q | Statistics extraction plus evaluator/timeout/reset/disposal tests. | Shared fixtures and real metric units pass. |

D01 uses these permanent owners and the incoming policy. It does not require completed quality presentation.

### Phase W3 — Interface, tray settings and quality display

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| W08 | Windows / Pending | W07, W07q | Call views, devices, mute, hangup and quality indicator. Explicit caller terminal wording: **“Declined”** for ordinary `DECLINE`, whether manual or policy-driven. | Snapshot-driven accessible UI; no inference of human action or disabled preference; no automatic retry. |
| W09 | Windows / Pending | W08, W03c | Tray **Allow incoming calls** check item, incoming indication and return-to-call/hangup. Keep Online/Offline separate. | Correct startup/failed-save state; works while hidden; outgoing stays available. |
| WT03 | Windows / Pending | W08, W09 | Menu persistence, caller wording, repeated redial, disabled incoming, stale events and quality transitions. | Manual/policy decline display identically; generic Busy for throttling; no ring/balloon/media when disabled or duplicate accessibility announcements. |

Preserve existing voice-message seek/replay behavior and Windows UI coverage.

### Phase W4 — Shared acceptance, calibration and release

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| BT01 | Both / Pending | AT01, AT01d, WT01, WT01c | Cross-process production TLS with fake media: framing, admission, rate limits and policy. | All pairings/directions; disabled incoming preserves outgoing/messaging; no ring/media; legacy/race cases pass. |
| BT02 | Both / Pending | AT02, AT02p, AT02q, AT03, WT02, WT02q, WT03, BT01 | Physical MVP matrix and approximately five-minute calls per pairing. | Controls, routing, proximity, settings, notifications and lifecycle verified. |
| BTQ01 | Both / Pending | BQ01, AT02q, WT02q, A09, W08 | Focused real-adapter calibration: clean LAN, sustained loss/jitter/delay, short spikes, one-direction impairment and recovery. Version shared thresholds. | UIs degrade/recover without flicker; quiet/muted/missing-stat cases do not imply microphone failure. Required before default enablement. |
| BT03 | Both / Pending | BT02, BTQ01 | Transfer coexistence, Offline resource/packet checks and existing regressions. | Native sockets, capture, pollers and proximity resources stop; messaging remains correct. |
| W10 | Windows / Pending | W03b, WT01b, WT01–WT03, WT01c, WT02q, BT01–BT03, BTQ01 | Final per-RID package, notices, deployment and upgrade audit against the recorded baselines. | Correct dependencies/runtime prerequisites; retained identity/history/drafts/settings; no untested architecture or deployment claim. |
| B04 | Both / Pending | A12, W10 | Update status/comparison, selected-feature evidence and authorized local commits. | Implementation, automation and device acceptance recorded separately; no push or branch changes. |
| BH01 | Both / Pending — Hardening | BT03 | Long soaks, wider Bluetooth/devices, larger impairment and performance campaign. | Follow-up evidence; not a prerequisite for MVP packaging after focused gates pass. |

#### Required MVP matrix

| Area | Required cases |
|---|---|
| Pairings and flow | Android ↔ Android, Android ↔ Windows, Windows ↔ Windows; both initiators/hangup directions; accept/decline/cancel/timeout/Busy/glare/duplicates. |
| Incoming preference | Missing/default/corrupt setting; persistence/restart/upgrade; toggling while Offline without going Online; disable while ringing; Accept race; accepted call preserved; outgoing/text/files remain available. |
| Caller wording and redial | Manual and policy decline both display “Declined”; no preference disclosure or human-action assertion; repeated distinct invitations count toward the same limiter in enabled/disabled states; no ringtone/media allocations, queue growth or retry loop. |
| Security | Verification/key change/revoke/forget, stale IDs, fingerprint mismatch, malformed frames and insecure negotiation. |
| Android ownership | Exactly one service controller; no Activity-owned media; pre-A06 termination versus post-A06 continuation. |
| Android permission/build | Packaged `MODIFY_AUDIO_SETTINGS` present before A04 and at final audit; API 26/newest-device communication behavior; microphone permission denial does not break messaging. |
| Android proximity | Near/far on confirmed earpiece; route change or remote hangup while near; speaker/Bluetooth/wired exclusion; unknown route; unsupported capability; Offline/destruction; no delayed suppression or pause-triggered termination. |
| Android Bluetooth | Connected-headset physical observation, actual two-way route, connect/disconnect, mute, Phone/Speaker override and cleanup. Record legacy behavior if applicable. |
| Quality | Shared fixtures, real mapping, clean versus sustained impairment, one-direction impairment, hysteresis, missing/stale/reset counters, quiet/muted audio, callbacks after end and accessible transitions. |
| Windows lifecycle/architecture | Tray/Exit/suspend/privacy/unplug; every retained RID; explicit x86 policy; native/emulated ARM64 distinguished. |
| Windows deployment | Recorded previous artifact mode and apphost architecture; matching framework/runtime prerequisites; clean-machine load and upgrade from the evidenced package layout. |
| Networking | Blocked UDP, client isolation, incorrect interface, loss, crash, signaling failure and Online/Offline races. |
| Audio ownership | Recording finalization, stale permission results, playback interruption and post-call recovery. |
| Compatibility/data | Old/new messaging unchanged, bounded unsupported-call failure and retained identity/history/drafts/settings. |
| Packaging | Android ABIs, extraction/compression, ELF/API compatibility and size impact; Windows matching native dependencies and publish layout. |

#### MVP gates versus extended hardening

| Check | MVP release gate | BH01 target |
|---|---|---|
| Setup | Typically within five seconds after acceptance on the test LAN; always respects setup deadline. | Timing distributions under load. |
| Audio | Five-minute intelligible call per pairing; no sustained echo or growing delay. | Thirty-minute soaks across more devices/routes. |
| Repetition | Ten short cycles plus physical repeats; no retained resources. | At least 100 cycles and prolonged resource analysis. |
| Incoming policy | Persistence/race/UI tests, repeated-redial limit and physical no-ring/no-media check. | Larger concurrent invitation stress. |
| Proximity | Physical near/far and immediate cleanup on a supported device; unsupported fallback. | Wider OEM/sensor coverage. |
| Quality | BQ01 and focused BTQ01 calibration. | Broader threshold/performance characterization. |
| Latency | No obvious conversational lag; no unmeasured numerical claim. | Externally measured mouth-to-ear target below 250 ms. |
| Connectivity | Bounded failure and cleanup. | Extended loss/reordering/jitter combinations. |
| Offline/privacy | No unsolicited capture or retained call networking/pollers/proximity suppression. | Repeated stress. |
| Transfers | Representative Normal/Fast transfer remains correct; controls responsive. | Saturation, CPU and battery profiling. |
| Deployment | Verified native loading and upgrade for every declared package. | Wider installation/environment coverage. |

Selected enhancements are release requirements. Security failures, data loss, retained capture/networking, stuck proximity suppression and known broken routing cannot be deferred as hardening.

Run targeted tests during implementation and the full regression runner before release. Preserve voice drafts/scheduling, WAV/PCM, interoperability, Offline lifecycle, verification, transfers/resume, groups and Windows UI checks. Supplement Android source-pattern checks with service behavior tests.

Record revision, dependencies, device/OS/architecture, route, network and actual result. Missing equipment leaves the associated physical acceptance Pending.

### Phase W5 — Future video

| ID | Platform / status | Dependencies | Future work | Acceptance / testing |
|---|---|---|---|---|
| BV01 | Both / Deferred | Accepted voice release | Video capability, explicit upgrade consent, negotiation generations, glare/rollback and common codec selection. Keep incoming-call policy separate from in-call camera consent. | Voice-only compatibility; declining video preserves audio. |
| WV01 | Windows / Deferred | BV01 | Camera/renderer and retained-RID adapter support. | Permission denial or unplug releases camera without ending audio. |
| WV02 | Windows / Deferred | WV01 | Preview, remote video, camera selection/off and consent UI. | No capture before consent; safe rendering lifecycle. |
| BV02 | Both / Deferred | AV03, WV02 | Video interoperability, audio priority, synchronization/downgrade and separate audio/video quality policy. | Audio survives video failure; Offline stops all media. |

#### Implementation order

1. **B00/B01 → W00a → A00/W00 → B02/B03:** record deployment evidence, resolve architecture support and prove compatible dependencies.
2. **A01–A03 → A03c/A03d and tests; W01–W03 → W03c and tests:** implement signaling, permanent ownership, incoming policy and throttling.
3. **A03b/AT01b and W03b/WT01b:** production dependencies, Android’s mandatory audio-settings permission and explicit Windows deployment/RID properties. May overlap step 2.
4. **BT01 → A04/A05 and W04/W05 → D01:** demonstrate integrated calls using permanent ownership.
5. **BQ01; A06/A07/A07p/A07q and W06/W07/W07q:** background continuation, routing, proximity and quality.
6. **A3/W3:** complete settings, notifications, caller outcomes and quality presentation.
7. **BT02/BTQ01/BT03 and A11 → A12/W10 → B04:** validate and package after all MVP gates.
8. **BH01:** extended hardening.
9. **Video phases:** implement later using the same signaling and media foundation.
