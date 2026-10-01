# plan-v004 | version=4 | Phased voice calls for Android and Windows

Implement **one-to-one, full-duplex LAN voice calls**, using existing verified peer connections for signaling and **WebRTC-compatible media with Opus and DTLS-SRTP**. Separate call management from media tracks so video can be added later.

This is a plan only. No project files were modified, and no builds or tests were executed. Implementation and verification tasks are **Pending** until execution is explicitly requested. Video tasks are **Deferred**.

The two major sections cover Android and Windows. Tasks labeled **Both** define shared work once. Android calls belong to `MessengerService` from their first production integration. Android’s automatic Bluetooth routing has an explicit policy and acceptance check. Windows architecture support is decided before dependencies are frozen.

## 1. Android

### Phase A0 — Shared architecture and paired feasibility

**Outcome:** choose a viable media stack and demonstrate Android ↔ Windows audio before implementing the complete feature.

#### Current implementation baseline

| Area | Current code and implication |
|---|---|
| Android application | Java, minimum API 26, target API 34. The javac/D8/aapt pipeline has no WebRTC integration. |
| Compilation | `build.ps1` compiles every Java file in `src/net/lanmsg/chat`. Media imports cannot be added before their dependencies enter the production build. |
| Packaging | The script adds only `classes.dex`; it does not package WebRTC native libraries. |
| Networking | Both engines provide verified TLS, five-field LM4 `HELLO`, predominantly short transactions and 16 KiB line limits. |
| Capabilities | Group `CAPS=2` has an existing meaning. Calling requires a separate capability. |
| Android ownership | `MessengerService` owns the engine and Online/Offline lifecycle. Its current foreground-service type is `connectedDevice`. |
| Existing audio | Android voice-message recording/playback belongs to the Activity and stops on pause. Those cleanup hooks must remain specific to voice messages. |
| Permissions/routing | Existing microphone permission results start voice-message recording. The manifest has no Bluetooth or `MODIFY_AUDIO_SETTINGS` permission, and current voice playback does not establish a call-routing policy. |
| Windows build | .NET 9 WinForms without explicit `RuntimeIdentifier` or `PlatformTarget`. Managed AnyCPU defaults do not establish which architectures previous executable packages supported. |
| Windows media/TLS | Voice messages use `waveIn`/`waveOut`; TLS uses BouncyCastle-backed `SecureChannel`. No production WebRTC references/native-copy rules exist. |
| Verification records | `tests/voice_interop.py` is wired into `tests/run.ps1`, despite older status paragraphs saying interoperability had not started. Wiring does not prove a current passing run. |

Relevant Android files: [build.ps1](/D:/LAN-Messenger/source/android/build.ps1), [AndroidManifest.xml](/D:/LAN-Messenger/source/android/AndroidManifest.xml), [PeerEngine.java](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/PeerEngine.java), [MessengerService.java](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/MessengerService.java), and [MainActivity.java](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/MainActivity.java).

Current source and platform status records govern implementation. Earlier plans provide context only.

#### Scope and delivery milestones — Both

| Milestone | Scope |
|---|---|
| **Feasibility demonstration: B02** | Foreground Android ↔ Windows prototypes exchange encrypted audio. Proves dependency viability; not a releasable application. |
| **First integrated demonstration: D01** | Production builds and signaling support call, accept, mute, hangup and Offline cleanup. Android is service-owned and foreground-only until A06. |
| **MVP release** | Basic controls and lifecycle behavior on Android ↔ Android, Windows ↔ Windows and Android ↔ Windows. |
| **Hardening: BH01** | Longer soaks, 100-cycle automation, external latency measurement and detailed impairment/performance checks. |
| **Future video** | Negotiated video tracks, explicit camera consent and platform rendering using the same call foundation. |

MVP behavior:

- One verified direct contact and one pending/active call per device.
- Reachable IPv4 LAN peers; no required internet service.
- Call, accept, decline, cancel, hangup, mute and basic route/device selection.
- Clear calling, ringing, connecting, connected and terminal states.
- Established Android calls continue through navigation and screen lock while the service remains alive.
- Windows calls continue while hidden to the tray.
- Android incoming calls are available while its Online service is running and reachable.
- Offline ends all call networking; returning Online does not resume a call.
- Text and attachments remain available; calls coordinate exclusive audio ownership with voice messages.
- No automatic answering, call recording or stored call audio.
- Android permits system-selected Bluetooth audio **best-effort**, with route visibility and recovery as specified in A07. This does not promise support for every Bluetooth headset/profile.

Deferred: group calls, call waiting/hold, durable call history, internet relays, automatic redial/reconnection, app-managed Bluetooth pairing/device selection, comprehensive Bluetooth compatibility and video UI.

#### Architecture — Both

Prefer a **libwebrtc-based adapter on both platforms**. A managed Windows alternative is eligible only if it meets the same media, echo-control, lifecycle and future-video gates.

```text
Android Activity                 Windows call UI
       ↓                               ↓
MessengerService                Application-owned
  CallController                  CallController
       ↓                               ↓
Authenticated signaling + platform CallMedia adapter
```

The Android Activity supplies commands, permissions and rendering. It never owns the production controller, peer connection, call timers or media threads—even during the early demonstration.

Reuse verified identities, contact lookup and lifecycle hooks. Preserve existing WAV, draft and attachment contracts. Calls do not use attachment scheduling or the voice-message duration limit.

WebRTC supports application-defined signaling and native integration. [Peer connections](https://webrtc.org/getting-started/peer-connections), [native Android development](https://webrtc.github.io/webrtc-org/native-code/android/).

#### B01 dependency shortlist

These are **planning estimates for initial bring-up and packaging**, not the whole feature. They assume experienced implementation and suitable hardware. B02 verifies exact revisions, licenses, toolchains, architectures and configured capabilities.

**Android candidates**

| Candidate | Capabilities and gaps | Expected effort / packaging |
|---|---|---|
| **Preferred: `io.github.webrtc-sdk:android`** | Community Java/JNI libwebrtc distribution using `org.webrtc`. Evaluate the full variant for Opus, DTLS-SRTP and audio processing/AEC. Test actual speakerphone behavior. Avoid selecting a stripped video-codec variant solely for initial size. | **Approximately 1–3 engineering days** for inspection and initial raw-build integration. Unpack AAR classes/native libraries/resources and supply javac/D8 inputs. This is not a current Google-published Maven dependency. [Repository](https://github.com/webrtc-sdk/android). |
| **Fallback: pinned upstream libwebrtc self-build** | Java/JNI, native peer connections and configurable audio processing. Build and verify Opus, DTLS-SRTP and AEC; application signaling/service behavior remain project work. | **Approximately 5–10+ engineering days** for infrastructure/reproducibility, followed by packaging. Requires a supported native build host, `depot_tools`, GN/Ninja and `tools_webrtc/android/build_aar.py`. [Build documentation](https://webrtc.github.io/webrtc-org/native-code/android/). |

Libwebrtc audio processing must be configured and tested; library loading alone does not establish effective echo cancellation. [Audio-processing interface](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_processing/include/audio_processing.h).

**Windows candidates**

| Candidate | Capabilities and gaps | Expected effort / packaging |
|---|---|---|
| **Preferred where architecture inventory permits: Shiguredo `webrtc-build` plus project-owned C ABI bridge** | Native libraries, headers and revision information; Windows targets include x64/ARM64. No project-specific .NET wrapper. Evaluate Opus, DTLS-SRTP, devices and AEC. Do not assume an x86 artifact exists. Published binaries omit H.264/H.265; future video needs a tested common codec. | **Approximately 3–7+ engineering days** for bridge and packaging. Match library/headers/toolchain and distribute runtime dependencies. Cannot be the complete solution while required x86 support is unresolved. [Repository](https://github.com/shiguredo-webrtc-build/webrtc-build). |
| **Fallback: upstream libwebrtc self-build plus C ABI bridge** | Native stack with configurable Opus, DTLS-SRTP and AEC. Own updates, callbacks and devices. Prove every retained architecture, including x86 where required. | **Approximately 5–10+ engineering days for infrastructure alone**, plus bridge/application integration. [Native development](https://webrtc.github.io/webrtc-org/native-code/development/). |
| **Alternative: `SIPSorcery` + `SIPSorceryMedia.Windows` + Opus/Concentus** | Managed WebRTC/DTLS-SRTP, documented Opus and separate Windows device endpoints. AEC and production playout/jitter behavior remain unproven; additional processing may be necessary. Managed transport does not prove architecture compatibility of all dependencies. | **Approximately 2–4 days** for basic audio, potentially substantially more for processing and video readiness. Pin .NET 9-compatible dependencies and inspect conflicts. Reject if speakerphone/lifecycle gates cannot be met. [Capabilities](https://github.com/sipsorcery-org/sipsorcery), [DTLS-SRTP API](https://sipsorcery-org.github.io/sipsorcery/api/SIPSorcery.Net.DtlsSrtpTransport.html). |

Do not use archived Microsoft MixedReality-WebRTC as the default shortcut. [Repository status](https://github.com/microsoft/MixedReality-WebRTC/).

#### Foundation tasks

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| B00 | Both / Pending | Execution authorized | Record revision, existing changes, baseline tests, device architectures and equipment. Include availability of a paired Bluetooth headset for AT02. Reconcile stale status entries. | Source, automation and physical evidence separated; unavailable equipment explicit. |
| B01 | Both / Pending | B00 | Draft shared contract; evaluate named candidates, tools and effort. Inspect Android adapter routing behavior and any implicit Bluetooth helpers/permissions. | Concrete dependency checklist and reproducible-input proposal. |
| A00 | Android / Pending | B01 | Prototype loading, Opus, processing, statistics and teardown; inspect ABI/API availability and audio-device-module routing ownership. | Physical foreground audio and repeated cleanup work. Document who changes audio routes; prototype packaging does not replace production integration. |
| W00a | Windows / Pending | B01 | Inventory architectures and decide x86 support policy before freezing dependencies. | Explicit support matrix and candidate constraints; unknown x86 usage is not treated as absent. |
| W00 | Windows / Pending | B01, W00a | Windows media/TLS spike against architecture policy. | Physical media/persistent-signaling evidence and viable retained architecture paths. |
| B02 | Both / Pending | A00, W00, W00a | Pair prototypes without external ICE servers. Select exact dependencies, licenses, architectures and reproducible recipes. | Encrypted two-way audio, mute, basic speaker/headset operation and cleanup. Windows x86 disposition explicit before freeze; Android routing integration can implement A07. |
| B03 | Both / Pending | B02 | Freeze protocol, limits, fixtures, media interface and dependency manifest, including Windows RID matrix and Android routing ownership. | Exact production-build inputs; both implementations can proceed independently. |

If feasibility fails, revise the dependency/adapter approach. Do not substitute plaintext media, WAV streaming or an untested audio-processing path.

### Shared call contract to freeze in B03 — Both

#### Compatibility and admission

- Preserve discovery, five-field `HELLO`, group `CAPS=2` and attachment frames.
- Add verified `CALLCAPS` describing protocol version and media profile.
- Query capability freshly before calls; cached values may inform presentation only.
- Distinguish unsupported response, transport failure, Offline and unverified contact.
- Negotiate and acknowledge `CALLOPEN` before changing framing.
- Accept verified direct peers only; reject groups, self-calls and identity mismatch.
- Advertise support after dependency loading/basic initialization succeeds, without microphone capture.
- One pending/active call; unrelated invitations receive Busy.
- Bound handshake resources, invitation rates, queues and call admission.

#### Framing and connection ownership

- Preserve ordinary LM4’s 16 KiB line limit.
- Negotiated frames: four-byte unsigned big-endian payload length followed by UTF-8 JSON.
- Proposed limits: 64 KiB frame, 48 KiB SDP, 128 ICE candidates per negotiation. Bound queues by count and bytes; verify against spike output.
- Envelope: version, type, call ID, sender sequence, negotiation generation and typed body.
- Specify required/optional fields, duplicate-key rules, unknown messages and malformed UTF-8.
- Reject oversized lengths before allocation and enforce whole-frame deadlines.
- One reader and one serialized writer per channel.
- Transfer TLS/socket ownership explicitly so receive-scope disposal cannot close a handed-off connection.
- Release ordinary inbound capacity after handoff while retaining engine cancellation ownership.
- Reserve bounded control admission separately from long attachment work without bypassing overall limits.

#### Messages and identity

Define `INVITE`, `RINGING`, `ACCEPT`, `DECLINE`, `BUSY`, `CANCEL`, `OFFER`, `ANSWER`, `ICE`, `MEDIA_READY`, `HANGUP`, `ERROR`, `PING` and `PONG`.

For each, specify sender, valid states, fields, duplicate handling and terminal effects.

Bind sessions to random call ID, verified peer ID/fingerprint, authenticated channel, engine network generation and negotiation generation. Exchange media fingerprints through authenticated signaling and enforce them in the media stack.

Validate SDP/candidates before native processing. Version 1 permits only its negotiated audio profile; reject unexpected video/data sections and insecure negotiation. Logs contain state/timing/aggregate metrics, not media, keys or full SDP/ICE credentials.

#### State machine

```text
Idle → OutgoingRinging / IncomingRinging
     → Connecting → Connected → Ending → Idle
```

Retain the terminal reason in the UI snapshot.

- Caller obtains microphone permission before inviting, without capture.
- Callee accepts a current invitation through an eligible permission/service flow.
- Caller creates the offer after acceptance; callee answers.
- No capture before explicit local action and remote acceptance.
- Send local `MEDIA_READY` independently when local media/transport are ready. Connected requires readiness from both sides.
- Bound early candidates and associate them with the correct negotiation.
- Resolve simultaneous dialing by deterministic caller-ID ordering; cancel the losing invitation and require acceptance of the surviving incoming call.
- Serialize accept/cancel, timeout/accept and hangup/device-failure races.
- Ignore stale permissions, native callbacks and notification actions.
- Never persist calls in message queues or resume them after restart.

#### Timing and cleanup

| Timer | Proposed default |
|---|---|
| Capability/open | Ten-second total budget including connection/TLS. |
| Ringing | Thirty seconds from accepted invitation delivery; permission UI does not reset it. |
| Media setup | Fifteen seconds after acceptance. |
| Heartbeat | Every five seconds; fail after fifteen seconds without valid inbound signaling. |
| Local teardown | Target completion within two seconds of termination. |

Use monotonic clocks and call-specific I/O deadlines; do not blindly inherit existing approximately six-second transport timeouts.

Lost signaling or terminal ICE failure ends the call. Define a bounded transient-disconnection grace period without automatic ICE restart/redial.

Termination stops capture, invalidates callbacks, closes media/signaling, cancels timers, releases audio ownership and restores app-owned audio settings. Offline includes native sockets; do not report actual Offline or permit a new generation while old call networking remains active.

#### LAN policy

- No external STUN/TURN or internet fallback.
- UDP host candidates on the interface associated with authenticated signaling.
- Initially accept remote addresses matching the authenticated IPv4 endpoint; reject public, multicast, unspecified, unrelated-interface and production loopback candidates.
- Filter SDP-embedded and trickled candidates.
- Disable or explicitly resolve/filter mDNS candidates; prove gathering respects the interface policy.
- Blocked UDP, client isolation and unsupported arrangements produce clear failures.
- Document media port/firewall requirements; preserve discovery/control ports.
- Keep live media outside attachment queues and Android attachment-upload accounting.

### Phase A1 — Android signaling, service ownership and production build

**Outcome:** validated signaling, one service-owned controller and an APK that loads the selected dependency before live-media implementation.

Proposed components: `CallSession`, `CallController`, `CallProtocol`, `CallSignaling`, `CallMedia`. Controller/codec classes remain independent of Android and WebRTC.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| A01 | Android / Pending | B03 | Serialized state machine, immutable snapshots, injectable clock/fakes. | States, Busy, glare, duplicates, races and stale callbacks covered deterministically. |
| A02 | Android / Pending | A01 | Capability/open, framing, authenticated handoff and limits. | Java TLS tests cover legacy/untrusted peers, malformed/partial frames, deadlines and backpressure. |
| A03 | Android / Pending | A02 | Generation, trust, deletion, data-reset and shutdown hooks. | Every path terminates; old callbacks cannot act after Online restarts. |
| A03b | Android / Pending | B03 | Production javac/D8 dependencies, all DEX files, native libraries and required AAR resources/manifest content. Implement packaging policy below and relevant script reliability fixes. | Clean production APK installs and initializes/disposes media factory without capture; no manual library/classpath setup. |
| **A03c** | **Android / Pending** | **A03** | **Create exactly one `CallController` in existing `MessengerService`, alongside its engine.** Inject fake media initially. Expose binder commands/snapshots and implement service-owned foreground eligibility before A06. | Rebinding/recreation never creates a second controller. Early commands fail safely; Offline/destruction terminate resources; no retained Activity. |
| AT01 | Android / Pending | A01–A03 | JVM production-controller/codec tests and harness commands; update source lists. | No Android/JNI needed; fixtures match C#. |
| AT01b | Android / Pending | A03b | Artifact and native-load checks. | Incomplete packaging fails clearly; optional media loading failure does not crash messaging. |
| **AT01c** | Android / Pending | A03c | Fake-media service tests: bindings, delayed initialization, foreground loss, destruction and stale commands. | One service session; Activity cannot directly dispose/replace it. Pre-A06 foreground loss causes one controlled termination. |

A03b can overlap controller work. A03c uses fake media. **A03b/AT01b and A03c/AT01c must complete before A04.**

#### Android ownership contract

- `MessengerService` owns controller, executor, signaling, media factory and live media session.
- Construct after engine readiness; register/unregister hooks once.
- A04 installs real media in this same host. No temporary Activity-owned implementation.
- Use service/application context for media; never retain `MainActivity`.
- Activity binds, renders snapshots, obtains permission and submits commands with call/lifecycle identity.
- UI subscriptions detach/reconnect to the existing controller across lifecycle changes.
- Existing voice-message pause/destroy cleanup must not dispose call resources.
- Before A06, capture requires valid visible/resumed call UI and permission. UI loss informs the controller, which terminates normally.
- Stale permission results cannot start capture.
- Service destruction, Offline and engine failure clean up without relying on Activity callbacks.

#### Native packaging policy

Implement in A03b and audit in A11:

- Planned universal APK ABIs: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, verified at B02. Missing support requires another artifact/build or explicit support decision; do not silently omit existing devices.
- Prefer one universal APK for current sideload upgrades.
- Raw packaging uses **`android:extractNativeLibs="true"` with compressed `.so` entries**. Inspect ZIP compression methods.
- Apply ordinary ZIP alignment before signing. `zipalign -f 4` does not establish native ELF compatibility.
- Verify ELF segment alignment for every dependency in tested 16 KiB environments; extraction cannot repair incompatible ELF.
- Direct-from-APK loading, if chosen later, needs uncompressed libraries, `extractNativeLibs=false` and verified 16 KiB ZIP alignment.
- Any necessary build-system migration completes inside A03b.
- Record baseline/new APK size, delta, per-ABI compressed/uncompressed size and extracted installed footprint.
- Test API 26 and newest-tested-device installation/loading, including 16 KiB where claimed; repeat with final upgrade artifacts.

[Android extraction setting](https://developer.android.com/guide/topics/manifest/application-element#extractNativeLibs), [16 KiB compatibility](https://developer.android.com/guide/practices/page-sizes).

**Exit:** signaling, service ownership and production dependency loading pass. Cross-platform signaling additionally requires BT01.

### Phase A2 — Android audio, background continuation and routing

**Outcome:** install real audio into the existing service host, then complete background and route behavior.

Proposed components: `WebRtcCallMedia`, `CallAudioRouter`, `AudioOwnership` and service command/snapshot adapters.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| **A04** | Android / Pending | **A00, A03b, AT01b, A03c, AT01c** | Install real media into the existing service controller. Implement Opus, SDP/ICE, mute, readiness, statistics and disposal. Foreground-only until A06. | Production APK exchanges physical audio; mute/failures clean up; no Activity-owned peer connections or call-created drafts/files. |
| A05 | Android / Pending | A04 | Process-wide audio ownership; await voice-recording finalization/device release. | Playback stops, draft survives, failed finalization blocks competing capture. |
| **A06** | **Android / Pending** | **A04, A05** | **Add microphone foreground-service declarations, active type masks and background-continuation eligibility.** Change pre-A06 foreground-only policy only after service prerequisites succeed. Ownership is unchanged. | Navigation/recreation/Home/lock preserve accepted calls; hangup removes microphone foreground state while Online messaging remains; failed promotion prevents background capture. |
| **A07** | **Android / Pending** | **A06** | Communication mode/focus, supported earpiece/speaker/wired routes, explicit routing policy below and app/native route coordination. Add required audio-settings declaration and guard APIs/permissions. | System-selected Bluetooth has defined behavior; route display reflects available evidence; mute survives changes; fallback and teardown work without competing route managers. |
| **AT02** | **Android / Pending** | **A04–A07** | Fault/lifecycle/routing tests, including the mandatory Bluetooth-connected observation below. Distinguish pre-A06 foreground termination from post-A06 continuation. | Denial, unplug, route failure, repeated stop and service/process loss leave no retained capture/session. A connected headset cannot silently bypass route acceptance. |

Microphone foreground-service declarations and runtime permission are required, with restrictions on background activation. Start/accept through a visible Activity, establish eligible service state, then permit continuation. [Service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

#### A07 routing and Bluetooth policy

**Decision:** permit an already connected, system-selected Bluetooth communication route on a **best-effort basis**. Do not implement Bluetooth scanning, pairing or a Bluetooth-device picker in MVP. Comprehensive headset/profile compatibility remains deferred; handling automatic route changes is required now.

| Situation | Required behavior |
|---|---|
| Call starts | Default to **System audio**. Let Android select a communication route, including an already connected Bluetooth headset. Do not assume the built-in microphone/output remains active. |
| Bluetooth becomes active automatically | Allow it; preserve mute and session identity. Update route status from platform/media routing evidence. Where identified, show “Bluetooth — system selected”; otherwise show “System audio,” without inventing a device name or claiming verified headset audio. |
| User selects phone or speaker | Request the available built-in earpiece or speaker explicitly; do not treat “clear route preference” as a guaranteed switch to earpiece. Show actual/confirmed selection, not merely the requested route. |
| Headset disconnects | Observe the resulting system route. Keep an available working route, update UI and preserve mute. Do not add an app-forced speaker switch; offer phone/speaker selection. |
| Route/device fails | Surface an audio-route problem. Allow a bounded recovery to a supported selected route; if recovery fails, end with a clear reason and release resources. No infinite retry or knowingly silent Connected state after a reported device failure. |
| Call ends | Clear this app’s communication-device request, unregister listeners and release its audio mode/focus. Do not restore a stale headset choice over a later user/system route change. |

Implementation requirements:

- `CallAudioRouter` is the single coordinator for app route requests. Inspect and disable conflicting automatic helper behavior in the selected adapter, or integrate its callbacks under this coordinator.
- On API 31+, use communication-device selection/query/listeners with API guards. Use a tested legacy path on API 26–30. Do not call newer APIs unguarded or assume legacy speaker-off means earpiece.
- Add the normal `MODIFY_AUDIO_SETTINGS` declaration where needed for the chosen audio-control APIs.
- Avoid Bluetooth profile enumeration or connection management in MVP. If a selected dependency invokes permission-sensitive Bluetooth APIs, disable that optional helper where possible; otherwise explicitly guard permission handling and denial. Ordinary phone/speaker calling must not depend on a Bluetooth grant.
- Catch unavailable-device, permission and routing errors. A routing request’s success return is not proof of audible two-way audio.
- Expose a simple **System / Phone, when available / Speaker** choice. Provide a way to switch to phone/speaker when the user reports no sound.
- Do not infer route health from speech silence or RTP counters alone. Physical acceptance verifies audibility in both directions.
- B03 defines a bounded route-recovery timeout, initially three seconds. Device-error recovery cannot indefinitely retain audio resources.

Android’s communication-device APIs allow selection distinct from the platform default and require clearing app requests after use. API 31+ routing differs from legacy Bluetooth/SCO handling. [AudioManager](https://developer.android.com/reference/android/media/AudioManager), [VoIP routing updates](https://developer.android.com/develop/connectivity/telecom/voip-app/api-updates). Permission-sensitive Bluetooth access must follow platform requirements. [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).

#### AT02 mandatory Bluetooth-connected observation

At least one physical Android device with an already paired headset is required for MVP route acceptance:

1. Connect the headset before starting an Android ↔ Windows call; record OS/device, headset, observed route and relevant permission state.
2. Verify actual speech capture and playback in both directions. Record whether the platform selected Bluetooth or a built-in route.
3. Connect/disconnect the paired headset during a call; verify route updates, resource handling and preservation of mute.
4. Select Phone, if present, and Speaker while Bluetooth is connected; verify actual audible routing and clear handling of rejected requests.
5. End the call and verify audio focus/mode, listeners and app route requests are released; voice-message playback/recording works again.
6. Inject route/permission failures in adapter tests. If the dependency needs Bluetooth permission, also test denial/revocation without losing ordinary phone/speaker calling.

A Bluetooth route need not work on every headset to meet MVP scope. The tested configuration must either provide intelligible audio or recover clearly to a supported route/end safely. **A silent or broken route is not a passing result merely because Bluetooth is best-effort.** Fix its handling and repeat the check. Missing equipment leaves this acceptance pending.

A broad Classic Bluetooth/LE Audio/hearing-aid/device matrix belongs to BH01 or a later compatibility effort.

#### Other lifecycle rules

- Dispatch explicit service actions; unknown/stale actions cannot enable networking.
- Preserve `START_NOT_STICKY`; no call resurrection.
- Keep microphone hardware optional for messaging-only devices.
- Ringing does not seize the microphone/discard recordings; use visual notification while recording.
- Guard voice-message capture/playback in UI and command layers during call ownership.
- Notification Accept opens the Activity.
- Separate call/voice-message permission request IDs and revalidate identity/eligibility.
- Add a scoped wake lock only if measurements demonstrate a need.

#### Early integrated demonstration

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| **D01** | **Both / Pending** | **A04, A05, W04, W05, BT01** | Development-only minimal controls, production builds and real signaling/media. Android binds to existing service controller and supplies visible permission/eligibility. Use the validated built-in/wired route for this early demo. | Both initiation directions, approximately two minutes of speech, mute/hangup/Offline cleanup. Before A06, Home/lock/foreground loss causes service-controlled termination. |

D01 does not wait for A07’s complete routing acceptance, notification polish or BH01. It makes no Bluetooth/background-support claim and introduces no temporary ownership model.

### Phase A3 — Android calling interface and notifications

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| A08 | Android / Pending | A06, A07 | Direct-chat Call action; actionable Offline/unverified/unsupported/busy states. | No group button; repeated taps create one invitation; network work off UI thread. |
| A09 | Android / Pending | A08 | Incoming/outgoing/active views, duration, mute, route and hangup. Show System/Phone/Speaker and observed route/failure state from A07; return-to-call entry. | Snapshots drive UI; recreation does not duplicate/end calls; requested and actual route are not confused; accessible controls. |
| A10 | Android / Pending | A09 | Call notification channel, API-appropriate presentation and call-specific PendingIntent identity. | Background decline/hangup work; expired actions do nothing; all terminal paths remove notifications. |
| AT03 | Android / Pending | A08–A10 | Foreground/background, lock, notification denial, navigation, route controls and voice-message interaction. | No spontaneous capture/answer; presentation never creates controllers; route errors/fallback actions visible. |

No full-screen intent privilege or Telecom integration is required. A stopped/killed LAN service cannot receive calls.

### Phase A4 — Android release audit and acceptance

Production dependency integration is already complete in A03b.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| A11 | Android / Pending | A03b, AT01b, AT02, AT03 | Audit dependencies/notices, ABI set, APK size/footprint, extraction/compression/ELF alignment and version-derived filename. | Each library/ABI and loading result recorded; API 26/newest-device checks pass; 16 KiB claims evidenced. |
| A12 | Android / Pending | A11, AT01, AT01c, BT01–BT03 | Build/sign after MVP gates. Preserve package ID, original signer and increasing versionCode. | Upgrade without uninstall retains identity, contacts, messages and drafts; artifact/signature/version agree. |
| A13 | Android / Pending | A12 | Update Android status/comparison, including actual Bluetooth observation and best-effort limitation. | Code, automation and physical acceptance separated; no claim of universal headset support. |

Check fresh/upgrade installation and extracted-library storage. Outputs belong in `D:\LAN-Messenger\outputs` when execution has access. Keep one source tree, branch and remotes; preserve signing material and exclude `.private` from archives. Commit locally; push only when requested.

### Phase A5 — Future Android video

| ID | Platform / status | Dependencies | Future work | Acceptance / testing |
|---|---|---|---|---|
| AV01 | Android / Deferred | Accepted voice release, BV01 | Service-owned camera adapter, optional capability and camera permission/service requirements. | Voice-only calls never open/request camera; denial preserves audio. |
| AV02 | Android / Deferred | AV01 | Preview, remote video, camera switch/off and explicit consent. Activity owns rendering surfaces, not sessions. | No capture before consent; surfaces reattach safely. |
| AV03 | Android / Deferred | AV02, WV02 | Interoperability, adaptation, thermal/battery and synchronization. Recheck audio routes with camera active. | Failed/disabled/declined video preserves audio; Offline stops all media. |

Voice implementation creates only extension points: media-kind capability, negotiation generation, track controls and UI-independent adapters.

## 2. Windows

### Phase W0 — Architecture decision and media feasibility

**Outcome:** decide supported process architectures before freezing dependencies.

Relevant files: [LanMessenger.csproj](/D:/LAN-Messenger/source/windows/LanMessenger.csproj), [SecureChannel.cs](/D:/LAN-Messenger/source/windows/SecureChannel.cs), [PeerEngine.cs](/D:/LAN-Messenger/source/windows/PeerEngine.cs), [Program.cs](/D:/LAN-Messenger/source/windows/Program.cs), and [ChatWindowVoicePlayback.cs](/D:/LAN-Messenger/source/windows/ChatWindowVoicePlayback.cs).

The current project has no explicit RID/platform target. A native bridge requires explicit architecture-specific deployment. Inspect previous apphost architecture and actual installations rather than assuming AnyCPU means every architecture was shipped.

#### W00a architecture decision

| Item | Required evidence/decision |
|---|---|
| Existing installations | OS architecture, current process architecture, installed Desktop Runtime and previous package/apphost architecture; unknowns recorded. |
| x64 | Candidate `win-x64`, matching managed process/native chain. |
| ARM64 | Decide native `win-arm64` versus specifically tested x64 emulation; label accurately. |
| x86 | Explicitly retain/exclude `win-x86`. Distinguish x86 process on x64 OS from 32-bit-only OS. |
| Dependency | Actual available/buildable native chain, toolchain and runtime for each retained architecture. |
| Upgrade | Preserve data path, DPAPI identity access, contacts and history across architecture changes. |

**Default compatibility rule:** do not drop existing x86 users. If x86 use exists or is unknown, B02 cannot freeze an x64/ARM64-only provider as the full solution. Prove an x86 path meeting the same call gates, or obtain an explicit support-scope decision before narrowing support.

Neither a source build nor managed candidate is automatically an x86 solution. Limited x64 experimentation can continue while inventory is incomplete, but it does not satisfy dependency freeze.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| **W00a** | **Windows / Pending** | **B01** | Complete inventory/support policy before candidate selection. Define RID/platform matrix, bridge ABI, toolchain/CRT, callbacks and loading requirements; refine with spike evidence. | Explicit x86 disposition based on evidence or authorized change; complete matrix reaches B02. |
| W00 | Windows / Pending | B01, W00a | Evaluate candidates constrained by matrix. Prove audio, Opus, DTLS-SRTP, AEC, callbacks/statistics/cleanup and persistent `SecureChannel` read/write/timeout/cancellation. | Physical audio, paired interoperability and viable retained architectures; no assumed `SslStream` behavior. |

Source-building remains multi-day infrastructure. Resolve TLS limitations before B03.

### Phase W1 — Signaling and explicit RID-based build integration

**Outcome:** implement the shared contract and architecture-matched normal build/publish before media implementation.

Proposed components: `CallSession.cs`, `CallController.cs`, `CallProtocol.cs`, `CallSignaling.cs`, `ICallMedia.cs`, `PeerEngine.Calls.cs`.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| W01 | Windows / Pending | B03 | Serialized controller, snapshots, clock and fakes. | Fixtures match Java, including glare/races. |
| W02 | Windows / Pending | W01 | Framing, capability/open and explicit TLS/socket handoff around disposal/semaphore. | Successful handoff survives receive completion; no double disposal or admission leak. |
| W03 | Windows / Pending | W02 | Offline/disposal, trust, forget/delete and data-reset hooks. | Cleanup outside locks; no deadlock/stale generation activity. |
| **W03b** | **Windows / Pending** | **B03, W00a** | **Implement transition from AnyCPU defaults to explicit RID/platform deployment.** Pin references, bridge/native dependencies, deterministic loading and build/publish copy rules. | Every supported RID produces isolated output and initializes/disposes media without manual PATH/DLL setup. Optional loading failures disable calling clearly while messaging remains available. |
| WT01 | Windows / Pending | W01–W03 | Controller/codec harness tests and source includes. | No UI/native dependency; pure managed harness may remain AnyCPU. |
| WT01b | Windows / Pending | W03b | Per-RID artifact/loading/process-architecture and upgrade smoke tests. | Matching native chain, clean-machine launch and existing data access; no x86 claim from x64-only tests. |

#### W03b build/publish contract

Freeze retained rows at B02/B03:

| Package | RID | Effective platform target | Native chain |
|---|---|---|---|
| x64 | `win-x64` | `x64` | Matching x64 bridge/media/runtimes. |
| Native ARM64, if retained | `win-arm64` | `ARM64` | Matching ARM64 chain. |
| x86, if retained | `win-x86` | `x86` | Matching x86 chain. |

- RID and effective platform target must agree; production bitness cannot depend on accidental host selection.
- A `RuntimeIdentifiers` list does not create a multi-architecture package. Publish each RID explicitly.
- Preserve framework-dependent deployment unless separately changed: `SelfContained=false`, matching .NET 9 Desktop Runtime documented.
- Isolate native, intermediate and publish outputs by architecture/RID.
- Name version/RID artifacts clearly.
- Define deterministic development/test targets; developer-host architecture is not release evidence.
- Keep pure protocol tests independent of native loading; match native/UI test process architecture to tested package.
- Verify PE/process architecture and controlled library paths.
- Upgrade with unchanged user-data location and Windows user; verify identity/history without reset.
- Do not repair mismatches by copying libraries from another architecture.

[RID catalog](https://learn.microsoft.com/en-us/dotnet/core/rid-catalog), [publishing overview](https://learn.microsoft.com/en-us/dotnet/core/deploying/).

W00a defines support; W03b implements deployment. W10 audits the final release.

### Phase W2 — Audio and application lifetime

Proposed components: `WebRtcCallMedia.cs`, `CallAudioDevices.cs`, `AudioOwnership.cs` and the selected native bridge/processing implementation.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| W04 | Windows / Pending | W03, W03b, WT01b | Media adapter, Opus, mute, readiness, SDP/ICE/statistics; rooted native callbacks/handles and serialized events. | Audio from normal RID output; no freed-state access; clear failure termination. |
| W05 | Windows / Pending | W04 | App audio ownership and awaited draft finalization/device release. | No competing capture; drafts survive; controls recover. |
| W06 | Windows / Pending | W04, W05 | Input/output choice, defaults, privacy errors and unplug. Live switching only where validated. | Headset/speaker work; mute preserved; failed devices cannot leave false Connected state. |
| W07 | Windows / Pending | W06 | Tray hiding preserves calls; Exit, suspend and unrecoverable network changes end them. | Same session on reopen; released resources on Exit; no redial on resume. |
| WT02 | Windows / Pending | W04–W07 | Failures, repeated disposal, callback/shutdown races and device loss across supported architectures. | No hangs, retained devices, use-after-free or short-cycle resource growth. |

D01 uses service-owned Android calls and production Windows packages. Full Android background/routing behavior follows A06/A07.

### Phase W3 — Call interface and tray

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| W08 | Windows / Pending | W07 | Direct-chat Call, incoming/outgoing/active views, duration, mute, devices and hangup. | One representation per call, snapshot-driven, no blocking network UI work. |
| W09 | Windows / Pending | W08 | Incoming tray notification, return-to-call and hangup; hide versus Exit preserved. | Accessible hidden-app indication; closing presentation never answers/duplicates calls. |
| WT03 | Windows / Pending | W08, W09 | Native UI tests with fake media, keyboard/accessibility and stale events. | Correct controls/disabled states; harmless disposed-window callbacks. |

Preserve existing voice-message seek/replay regression coverage.

### Phase W4 — Shared MVP acceptance and release

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| BT01 | Both / Pending | AT01, WT01 | Shared fixtures and Java/C# processes with real TLS/fake media. | All pairings/directions; trust/legacy failure, Busy/glare, malformed input and cleanup races. |
| BT02 | Both / Pending | AT02, AT03, WT02, WT03, BT01 | Physical MVP matrix with production UI/dependencies and declared architecture packages. Include AT02’s Bluetooth observation. | Controls, audio, routes and lifecycle pass on all pairings; approximately five-minute normal calls. |
| BT03 | Both / Pending | BT02 | Attachment coexistence, Offline packet/resource checks and regressions. | Correct text/files, responsive control and strict native-socket Offline behavior. |
| W10 | Windows / Pending | W03b, WT01b, WT01–WT03, BT01–BT03 | Audit versioned RID packages, notices, clean-machine loading, upgrades and x86 disposition. | Correct runtime/native/process chain, exact dependencies/checksums and retained data; no untested architecture claims. |
| B04 | Both / Pending | A12, W10 | Status/comparison, compatibility/artifact evidence and authorized local commits. | Separate implementation/automation/device acceptance; no push or branch/remote changes. |
| BH01 | Both / Pending — Hardening | BT03 | Extended tests below; not a dependency of A12/W10. Include broader Bluetooth compatibility if pursued. | Evidence/fixes tracked separately; known security/lifecycle defects still block release. |

#### MVP test matrix

| Area | Required cases |
|---|---|
| Pairings | Android ↔ Android, Windows ↔ Windows, Android ↔ Windows; both initiation/hangup directions. |
| Call flow | Accept, decline, cancel, timeout, Busy, glare and rapid duplicate actions. |
| Security | Unverified/changed identity, revoke/forget, spoofed/stale call ID, fingerprint mismatch, insecure media rejection. |
| Android ownership | One service controller; no Activity-owned media; foreground-only termination before A06, continuation afterward. |
| Android lifecycle | Home/lock/recreation, permission/notification denial, focus loss, service/process death. |
| **Android Bluetooth/routing** | **At least one physical connected-headset observation, actual two-way audibility, mid-call connect/disconnect, mute retention, phone/speaker override and cleanup. Broken routing must recover clearly or terminate; best-effort is not a waiver.** |
| Windows lifecycle | Tray/hide/reopen, Exit, suspend/resume, privacy denial and unplug. |
| Windows architecture | Retained RID clean-machine load/upgrade, process/native match, x86 disposition and accurate ARM64/emulation claims. |
| Network | UDP blocking, client isolation, wrong interface, Wi-Fi loss, peer crash, signaling loss and Online/Offline races. |
| Audio ownership | Incoming during recording, acceptance during draft finalization, stale permissions, playback interruption and recovery. |
| Coexistence | Text and representative large Normal/Fast transfer during calls. |
| Compatibility | Bounded unsupported-call failure; old/new messaging; upgrade retention of identity/history/drafts. |
| Android packaging | ABI/extraction/compression/ELF checks; API 26/newest-device loading. |

#### MVP gates versus hardening

| Check | MVP gate | BH01 target |
|---|---|---|
| Setup | Typically within five seconds after acceptance; always respects setup deadline/failure reporting. | Timing distributions under load. |
| Quality | Approximately five-minute call per pairing; intelligible simultaneous speech, no sustained echo or growing delay. | Thirty-minute soaks and wider devices/routes. |
| Repetition | Ten short cycles per implementation plus physical repeats; no retained mic/socket or clear growth. | At least 100 cycles and longer resource analysis. |
| Latency | No obvious conversational lag; no unmeasured numeric claim. | External mouth-to-ear measurement, target below 250 ms on clean LAN. |
| Network failure | Bounded failure and cleanup. | Controlled 2% loss, 50 ms added jitter and reordering. |
| Privacy/mute | No intelligible speech while muted; no unsolicited capture. | Extended route/codec/device combinations. |
| Bluetooth | Mandatory observation and usable fallback/clear termination for tested automatic-route behavior. | Classic/LE Audio/hearing-aid and wider device compatibility. |
| Offline | No call sockets/capture after actual Offline; no resurrection. | Repeated generation/toggle stress. |
| File coexistence | Correct transfers and responsive call control. | Saturation, CPU/battery/throughput characterization. |

Security defects, unsolicited capture, broken cleanup, data loss and dependency-loading failures cannot be deferred. Neither can known automatic-routing failures be dismissed solely because comprehensive Bluetooth support is deferred.

Run targeted regressions during implementation and the full runner before release: voice drafts/scheduler, WAV/PCM, voice interoperability, Offline lifecycle, verification, transfers/resume, groups and Windows UI. Update Android source-pattern lifecycle checks deliberately and add behavioral service-ownership tests.

Record dependency, OS/device, architecture, route, network and result. For Bluetooth, record the headset and observed route without collecting unnecessary identifiers. Missing equipment leaves acceptance Pending-Unavailable.

### Phase W5 — Future video and shared negotiation

| ID | Platform / status | Dependencies | Future work | Acceptance / testing |
|---|---|---|---|---|
| BV01 | Both / Deferred | Accepted voice release | Video capability, explicit upgrade consent, generations, glare/rollback and common codec/profile. | Voice-only compatibility; declined upgrade preserves audio. |
| WV01 | Windows / Deferred | BV01 | Camera capture/enumeration and renderer; bridge/adapter support for retained RIDs. | Privacy denial/unplug releases camera without ending audio. |
| WV02 | Windows / Deferred | WV01 | Preview, remote video, camera choice/off and consent. | No capture before consent; safe resize/hide/restore. |
| BV02 | Both / Deferred | AV03, WV02 | Interoperability, bandwidth adaptation, audio priority, synchronization/downgrade. | Failed/declined video preserves audio; Offline stops all media. |

#### Implementation order

1. **B00/B01 → W00a → A00/W00 → B02/B03:** establish support, prove dependencies and freeze contracts.
2. **A01–A03 → A03c/AT01c; W01–W03:** implement signaling and permanent Android service ownership using fake media.
3. **A03b/AT01b and W03b/WT01b:** production dependency integration and explicit RID packages; may overlap step 2.
4. **AT01/WT01 → BT01; A04/A05 and W04/W05 → D01:** early foreground Android ↔ Windows demonstration.
5. **A06/A07/AT02 and W06/W07 → A3/W3:** background continuation, route policy—including automatic Bluetooth—and complete presentation.
6. **BT02/BT03 and A11 → A12/W10 → B04:** release after MVP acceptance.
7. **BH01:** independent extended hardening.
8. **Video phases:** later implementation using the accepted calling foundation.
