# plan-v003 | version=3 | Phased voice calls for Android and Windows

Implement **one-to-one, full-duplex LAN voice calls**, using existing verified peer connections for signaling and **WebRTC-compatible media with Opus and DTLS-SRTP**. Keep call management separate from media tracks so video can be added later.

This is a plan only. No project files were modified, and no builds or tests were executed. Implementation and verification tasks are **Pending** until execution is explicitly requested. Video tasks are **Deferred**.

The two major sections cover Android and Windows. Tasks labeled **Both** define shared work once. Production dependency integration precedes live-media implementation. Android calls belong to `MessengerService` from their first production integration. Windows architecture support is decided before dependencies are frozen.

## 1. Android

### Phase A0 — Shared architecture and paired feasibility

**Outcome:** choose a viable media stack and demonstrate Android ↔ Windows audio before implementing the complete feature.

#### Current implementation baseline

| Area | Current code and implication |
|---|---|
| Android application | Java, minimum API 26, target API 34. The raw javac/D8/aapt pipeline has no WebRTC dependency integration. |
| Compilation | `build.ps1` compiles every Java file in `src/net/lanmsg/chat`. Adding `org.webrtc` imports before their compile/runtime dependencies breaks the build. |
| Packaging | The script adds only `classes.dex`; it currently packages no WebRTC native libraries. |
| Networking | Both engines provide verified TLS connections, five-field LM4 `HELLO`, predominantly short transactions and 16 KiB line limits. |
| Capabilities | Group `CAPS=2` has an existing meaning. Calling needs an independent capability. |
| Android service | `MessengerService` already owns the engine and its Online/Offline lifecycle. Its foreground-service type is currently `connectedDevice`. |
| Existing Android audio | Voice-message recording/playback belongs to the Activity and stops on pause. Those existing cleanup hooks must remain specific to voice messages. |
| Permission handling | The current recording-permission callback starts a voice-message recording. Calls need separate request identification and stale-result checks. |
| Windows build | `LanMessenger.csproj` targets .NET 9 WinForms without an explicit `RuntimeIdentifier` or `PlatformTarget`. Managed AnyCPU defaults do not establish which architectures previous executable packages actually supported. |
| Windows media/TLS | Voice messages use `waveIn`/`waveOut`; TLS uses a BouncyCastle-backed `SecureChannel`. No production WebRTC references or native-copy rules exist. |
| Verification records | `tests/voice_interop.py` is invoked by `tests/run.ps1`, despite older status paragraphs saying interoperability had not started. Test wiring is not proof of a current passing run. |

Relevant Android files: [build.ps1](/D:/LAN-Messenger/source/android/build.ps1), [AndroidManifest.xml](/D:/LAN-Messenger/source/android/AndroidManifest.xml), [PeerEngine.java](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/PeerEngine.java), [MessengerService.java](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/MessengerService.java), and [MainActivity.java](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/MainActivity.java).

Current source and platform status records govern implementation. Previous plans are context, not evidence of implemented behavior.

#### Scope and delivery milestones — Both

| Milestone | Scope |
|---|---|
| **Feasibility demonstration: B02** | Foreground Android ↔ Windows prototypes exchange encrypted audio. Proves dependency viability; not a releasable app. |
| **First integrated demonstration: D01** | Production builds and signaling support call, accept, mute, hangup and Offline cleanup. Android uses the existing service as owner, with foreground-only operation until A06. |
| **MVP release** | Complete basic controls and lifecycle behavior on Android ↔ Android, Windows ↔ Windows and Android ↔ Windows. |
| **Hardening: BH01** | Longer soaks, 100-cycle automation, external latency measurement and detailed impairment/performance tests. |
| **Future video** | Negotiated video tracks, explicit camera consent and platform rendering using the same call foundation. |

MVP behavior:

- One verified direct contact and one pending/active call per device.
- Reachable IPv4 LAN peers; no required internet service.
- Call, accept, decline, cancel, hangup, mute and basic route/device selection.
- Clear calling, ringing, connecting, connected and terminal states.
- Established Android calls continue through navigation and screen lock while the service remains alive.
- Windows calls continue while the application is hidden to the tray.
- Incoming Android calls are available while its Online service is running and reachable.
- Offline ends all call networking; returning Online does not resume a call.
- Text and attachments remain available; calls have exclusive ownership of call audio resources.
- No automatic answering, call recording or stored call audio.

Deferred: group calls, call waiting/hold, durable call history, internet relays, automatic redial/reconnection, guaranteed Bluetooth support and video UI.

#### Architecture — Both

Prefer a **libwebrtc-based media adapter on both platforms**. Retain a managed Windows alternative only if it satisfies the same media, echo-control, lifecycle and future-video gates.

```text
Android Activity                 Windows call UI
       ↓                               ↓
MessengerService                Application-owned
  CallController                  CallController
       ↓                               ↓
Authenticated signaling + platform CallMedia adapter
```

Android’s Activity supplies commands, permissions and rendering. It never owns the production call controller, native peer connection, call timers or media threads—even during the early demonstration.

Reuse verified identities, contact lookup and lifecycle hooks. Keep the existing WAV, draft and attachment contracts intact. Calls do not use attachment scheduling or the voice-message duration limit.

WebRTC supports application-defined signaling and native clients, allowing existing authenticated connections to carry negotiation. [Peer connections](https://webrtc.org/getting-started/peer-connections), [native Android development](https://webrtc.github.io/webrtc-org/native-code/android/).

#### B01 dependency shortlist

The following are **planning estimates for initial bring-up and packaging**, not the whole feature. They assume experienced implementation and suitable build hardware. B02 must verify exact revisions, licenses, toolchains, architecture coverage and configured capabilities.

**Android candidates**

| Candidate | Capabilities and gaps | Expected effort / packaging |
|---|---|---|
| **Preferred: `io.github.webrtc-sdk:android`**, from `webrtc-sdk/android` | Community Java/JNI libwebrtc distribution using `org.webrtc`. Evaluate the full variant for Opus, DTLS-SRTP and configured audio processing/AEC. Test speakerphone performance. Avoid choosing a stripped video-codec variant solely for initial size savings. | **Approximately 1–3 engineering days** for inspection and initial raw-build integration. Unpack AAR classes, native libraries and required metadata/resources; supply javac/D8 inputs. This is not a current Google-published Maven dependency. [Repository](https://github.com/webrtc-sdk/android). |
| **Fallback: pinned upstream libwebrtc self-build** | Java/JNI, native peer connections and configurable audio processing. Build and verify Opus, DTLS-SRTP and AEC. App signaling and service behavior remain project work. | **Approximately 5–10+ engineering days** for initial infrastructure/reproducibility, followed by app packaging. Requires a supported native build host, `depot_tools`, GN/Ninja, source/toolchain downloads and `tools_webrtc/android/build_aar.py`. [Build documentation](https://webrtc.github.io/webrtc-org/native-code/android/). |

Libwebrtc’s audio-processing module must be configured and tested; successful library loading does not establish effective echo cancellation. [Audio-processing interface](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_processing/include/audio_processing.h).

**Windows candidates**

| Candidate | Capabilities and gaps | Expected effort / packaging |
|---|---|---|
| **Preferred where architecture inventory permits: Shiguredo `webrtc-build` plus a project-owned C ABI bridge** | Native libraries, headers and revision information; published Windows targets include x64/ARM64. No project-specific .NET wrapper. Evaluate Opus, DTLS-SRTP, audio devices and AEC. Do not assume an x86 binary exists. Published binaries omit H.264/H.265 codecs; future video needs a tested common codec. | **Approximately 3–7+ engineering days** for bridge bring-up and packaging. Match native headers/library/toolchain and distribute bridge/runtime dependencies. Cannot be frozen as the sole provider if required x86 support is unresolved. [Repository](https://github.com/shiguredo-webrtc-build/webrtc-build). |
| **Fallback: upstream libwebrtc self-build plus C ABI bridge** | Native stack with configurable Opus, DTLS-SRTP and AEC. Own build flags, updates, callbacks and device integration. Each required architecture—including x86 if retained—must be proven buildable, not assumed. | **Approximately 5–10+ engineering days for infrastructure alone**, plus bridge/app integration. This is a multi-day native toolchain task. [Native development](https://webrtc.github.io/webrtc-org/native-code/development/). |
| **Alternative: `SIPSorcery` + `SIPSorceryMedia.Windows` + Opus/Concentus** | Managed WebRTC/DTLS-SRTP, documented Opus support and separate Windows device endpoints. AEC and production-quality playout/jitter behavior remain unproven. Identify and test additional processing if needed. Managed transport alone does not prove x86 compatibility of every dependency. | **Approximately 2–4 days** for basic audio, potentially substantially more for AEC, playout and future video. Pin .NET 9-compatible dependencies and inspect transitive conflicts. Reject if speakerphone/lifecycle requirements cannot be met. [Capabilities](https://github.com/sipsorcery-org/sipsorcery), [DTLS-SRTP API](https://sipsorcery-org.github.io/sipsorcery/api/SIPSorcery.Net.DtlsSrtpTransport.html). |

Do not use archived Microsoft MixedReality-WebRTC as the default shortcut. [Repository status](https://github.com/microsoft/MixedReality-WebRTC/).

#### Foundation tasks

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| B00 | Both / Pending | Execution authorized | Record revision, existing changes, baseline tests, existing-device architectures and test equipment. Reconcile relevant stale status entries. | Source inspection, automated results and physical acceptance are separate; existing failures are explicit. |
| B01 | Both / Pending | B00 | Draft the shared contract and evaluate the named candidates. Record build-host/toolchain requirements and effort. | Concrete dependency checklist and reproducible-input proposal for each spike. |
| A00 | Android / Pending | B01 | Prototype loading, Opus, audio processing, statistics and teardown; inspect ABI/API availability. | Physical foreground audio works and repeated disposal releases the microphone. Prototype packaging does not satisfy production integration. |
| **W00a** | **Windows / Pending** | **B01** | **Inventory Windows architecture requirements and decide the x86 support policy before selecting/finalizing the dependency.** Details are in Phase W0. | Explicit support matrix and candidate constraints; no inference that no x86 users exist. |
| W00 | Windows / Pending | B01, W00a | Perform the Windows media/TLS spike against the architecture policy. | Media and persistent-signaling evidence; required architectures have viable build paths. |
| B02 | Both / Pending | A00, W00, W00a | Pair prototypes without external ICE servers. Select exact dependencies, revisions, licenses, architectures and reproducible build recipes. | Encrypted two-way audio, mute, basic speaker/headset operation and teardown pass. Windows x86 support has an explicit disposition before dependency selection is frozen. |
| B03 | Both / Pending | B02 | Freeze contract, limits, fixtures, media interface and dependency manifest, including Windows RID/architecture matrix. | Both platforms can implement independently; production build tasks have exact inputs. |

If feasibility fails, revise the dependency/adapter approach before implementation. Do not substitute plaintext media, WAV streaming or an untested audio-processing path.

### Shared call contract to freeze in B03 — Both

#### Compatibility and admission

- Preserve discovery, five-field `HELLO`, group `CAPS=2` and attachment frames.
- Add verified `CALLCAPS` describing protocol version and media profile.
- Query capability freshly before outgoing calls; cached capability may inform presentation only.
- Distinguish unsupported/legacy response, transport failure, Offline and unverified contact.
- Negotiate `CALLOPEN` and acknowledge before switching framing.
- Accept verified direct peers only; reject groups, self-calls and identity mismatch.
- Advertise support after dependency loading and basic initialization succeed, without opening a microphone.
- Allow one pending/active call. Additional unrelated invitations receive Busy.
- Bound incomplete handshakes, invitation rates, queues and call-admission resources.

#### Framing and socket ownership

- Retain 16 KiB limits for ordinary LM4 lines.
- After negotiation, use a four-byte unsigned big-endian payload length followed by UTF-8 JSON.
- Proposed limits: 64 KiB frame, 48 KiB SDP and 128 ICE candidates per negotiation. Bound queues by count and bytes; verify limits against spike output.
- Envelope fields: version, type, call ID, sender sequence, negotiation generation and typed body.
- Specify required/optional fields, duplicate-key rules, unknown-message handling and malformed UTF-8 behavior.
- Reject oversized lengths before allocation; use whole-frame deadlines.
- One reader and one serialized writer per channel.
- Transfer TLS and underlying socket ownership explicitly; receive-scope disposal must not close a handed-off connection.
- Release ordinary inbound capacity after handoff while retaining engine ownership for cancellation.
- Reserve bounded control admission separately from long-running attachment serving without bypassing overall limits.

#### Messages and identity

Define `INVITE`, `RINGING`, `ACCEPT`, `DECLINE`, `BUSY`, `CANCEL`, `OFFER`, `ANSWER`, `ICE`, `MEDIA_READY`, `HANGUP`, `ERROR`, `PING` and `PONG`.

Specify each message’s allowed sender, valid states, fields, duplicate handling and terminal effects.

Bind sessions to random call ID, verified peer ID/fingerprint, authenticated channel, engine network generation and negotiation generation. Exchange media fingerprints through authenticated signaling and enforce them in the media stack.

Validate SDP/candidates before native processing. Version 1 accepts its negotiated audio profile only; reject unexpected video/data sections and insecure negotiation. Log state, timing and aggregate metrics—not media, keys or full SDP/ICE credentials.

#### State machine

```text
Idle → OutgoingRinging / IncomingRinging
     → Connecting → Connected → Ending → Idle
```

Keep the terminal reason in the UI snapshot after cleanup.

- Caller obtains microphone permission before inviting, without capture.
- Callee accepts a current invitation through an eligible permission/service flow.
- Caller creates the offer after acceptance; callee answers.
- No capture before explicit local action and remote acceptance.
- Send local `MEDIA_READY` independently when local media/transport are ready; display Connected after both sides are ready.
- Bound early candidates and associate them with the correct negotiation.
- Resolve simultaneous dialing using deterministic caller-ID ordering. Cancel the losing invitation and require acceptance of the surviving incoming invitation.
- Serialize accept/cancel, timeout/accept and hangup/device-failure races.
- Ignore stale permissions, callbacks and notification actions.
- Never persist calls in message queues or resume them after process restart.

#### Timing and cleanup

| Timer | Proposed default |
|---|---|
| Capability/open | 10-second total budget, including connection and TLS. |
| Ringing | 30 seconds from accepted invitation delivery; permission UI does not reset it. |
| Media setup | 15 seconds after acceptance. |
| Heartbeat | Every five seconds; fail after 15 seconds without valid inbound signaling. |
| Local teardown | Target completion within two seconds of termination. |

Use monotonic clocks and call-specific I/O deadlines. Existing approximately six-second transport timeouts cannot be inherited blindly.

Lost signaling or terminal ICE failure ends the call. Define a bounded transient-disconnection grace period without automatic ICE restart/redial.

Termination stops capture, invalidates callbacks, closes media/signaling, cancels timers, releases audio ownership and restores settings. Offline includes native media sockets; do not report actual Offline or permit a new generation while old call networking remains active.

#### LAN media policy

- No external STUN/TURN or internet fallback.
- UDP host candidates on the interface associated with authenticated signaling.
- Initially accept remote candidate addresses matching the authenticated IPv4 endpoint; reject public, multicast, unspecified, unrelated-interface and production loopback candidates.
- Filter SDP-embedded and trickled candidates.
- Disable or explicitly resolve/filter mDNS candidates; prove gathering remains within the selected interface policy.
- Blocked UDP, client isolation and unsupported network arrangements produce clear failures.
- Document native media port/firewall requirements; preserve existing discovery/control ports.
- Keep live media outside attachment queues and Android attachment-upload accounting.

### Phase A1 — Android signaling, service host and production dependency integration

**Outcome:** validated signaling, a single service-owned controller and a production APK capable of loading the selected dependency before live-media implementation.

Proposed components: `CallSession`, `CallController`, `CallProtocol`, `CallSignaling` and `CallMedia`. Keep controller/codec classes independent of Android and WebRTC.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| A01 | Android / Pending | B03 | Serialized state machine, immutable snapshots, injectable clock and fake media/signaling. | Deterministic states, Busy, glare, duplicate actions, races and stale callbacks. |
| A02 | Android / Pending | A01 | Capabilities/open, authenticated framing, ownership handoff and limits. | Java TLS tests cover legacy/untrusted peers, partial/malformed frames, deadlines and backpressure. |
| A03 | Android / Pending | A02 | Generation cancellation, trust changes, deletion, data reset and engine shutdown hooks. | Every path terminates sessions; old callbacks cannot act after Online restarts. |
| **A03b** | Android / Pending | B03 | Integrate dependency into the production build before adding media implementation sources: javac classpath, D8 dependency/transitive inputs, all DEX files, native libraries and required AAR resources/manifest content. Implement packaging policy below. | Clean production APK builds, installs and initializes/disposes the media factory without capture. Inspect DEX/native entries; no manual classpath/library installation. |
| **A03c** | **Android / Pending** | **A03** | **Instantiate exactly one production `CallController` inside the existing `MessengerService`, alongside its engine.** Inject fake media initially. Expose binder commands and immutable snapshots; connect service/engine shutdown. Add a service-owned foreground-eligibility policy for the pre-A06 stage. | Binding/rebinding and Activity recreation never create a second controller. Commands before service readiness fail safely. Service destruction/Offline terminates the controller; no retained Activity reference. |
| AT01 | Android / Pending | A01–A03 | JVM controller/codec tests and harness commands; update explicit source lists. | Production logic runs without Android/JNI; shared fixture outcomes match C#. |
| AT01b | Android / Pending | A03b | Artifact checks and native-load smoke procedure. | Incomplete packaging fails clearly; native loading failure cannot crash ordinary messaging at startup. |
| **AT01c** | **Android / Pending** | **A03c** | Test the service host with fake media: bind/unbind/rebind, delayed initialization, foreground loss, service destruction and stale commands. | One service-owned session; Activity callbacks cannot directly dispose or replace controller/media. Pre-A06 foreground loss produces one controlled termination. |

A03b can proceed alongside A01–A03. A03c uses fake media and therefore need not wait for native packaging. **A03b/AT01b and A03c/AT01c must all complete before A04.**

#### Android ownership contract from A03c onward

- `MessengerService` owns `CallController`, its executor, signaling session and media factory/session.
- Construct the controller after the service’s engine is ready. Register/unregister engine hooks exactly once.
- A04 replaces the injected fake adapter with `WebRtcCallMedia` in that same host. There is no temporary Activity-owned implementation.
- Use application/service context for media resources; do not retain `MainActivity`.
- Activity binds, renders snapshots, requests permissions and submits commands containing current call ID and lifecycle/session tokens.
- UI subscriptions detach on pause/destroy and reattach to the existing controller on resume/recreation.
- Existing `VoiceUi.stopVoiceRecording()` and `VoicePlayback.stopActivePlayer()` hooks remain limited to voice messages.
- Before A06, the service permits capture only while a valid visible/resumed call UI and permission grant are present. UI loss signals the controller to terminate through its normal cleanup path; it does not directly dispose native resources.
- A late permission result cannot start capture after foreground eligibility or call identity has changed.
- Service destruction, Offline and engine failure clean up without relying on an Activity callback.

#### Native packaging policy — implement in A03b, audit in A11

- Planned universal APK ABIs: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, verified at B02. Missing support requires another artifact/build or an explicit revised support decision; never silently omit an existing user architecture.
- Prefer one universal APK for current sideload/upgrade behavior. Any future per-ABI distribution must preserve package/signer/version compatibility.
- For raw packaging, explicitly use **`android:extractNativeLibs="true"` with compressed native `.so` entries**. Inspect actual ZIP methods rather than assuming `aapt add` selected them.
- Retain ordinary ZIP alignment before signing under this extraction policy. `zipalign -f 4` does not prove native ELF compatibility.
- Validate ELF segment alignment of every native dependency for tested 16 KiB environments. Compression/extraction cannot repair incompatible ELF files.
- A later direct-from-APK policy requires uncompressed libraries, `extractNativeLibs=false` and verified 16 KiB ZIP alignment; do not mix policies accidentally.
- If build-system migration is necessary, complete it inside A03b with equivalent supported packaging settings.
- Record baseline APK size, new size/delta, compressed/uncompressed size per ABI and installed/extracted footprint.
- Test installation/loading on API 26 and the newest tested device, including a 16 KiB environment where support is claimed. Repeat with final upgrade artifacts.

These decisions follow Android’s distinction between extraction, ZIP alignment and ELF compatibility. [Manifest extraction setting](https://developer.android.com/guide/topics/manifest/application-element#extractNativeLibs), [16 KiB compatibility](https://developer.android.com/guide/practices/page-sizes).

**Phase exit:** fake-media signaling, service ownership and production dependency loading pass. Cross-platform signaling additionally requires BT01.

### Phase A2 — Android live audio and background continuation

**Outcome:** add real media to the existing service-owned controller, then enable supported background continuation without changing ownership.

Proposed components: `WebRtcCallMedia`, `CallAudioRouter`, `AudioOwnership` and service command/snapshot adapters.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| **A04** | Android / Pending | **A00, A03b, AT01b, A03c, AT01c** | Install the real adapter into `MessengerService`’s existing controller. Implement Opus, SDP/ICE, mute, readiness, statistics and disposal. Service owns all native resources. Operate foreground-only until A06. | Two-way physical audio using production APK and service host; mute and failure cleanup work; no drafts/attachments or Activity-owned peer connections. |
| A05 | Android / Pending | A04 | Process-wide audio ownership; await voice-recording finalization/release before call capture. Connect existing voice UI/playback. | Playback stops; recording is preserved as an unsent draft. Failed release/finalization blocks competing capture. |
| **A06** | **Android / Pending** | **A04, A05** | **Add microphone foreground-service type/permission, active type-mask management and background-continuation eligibility to the existing service-owned call.** Replace pre-A06 foreground-only termination policy only after service prerequisites succeed. No controller/media ownership migration. | Established call survives navigation, Activity recreation, Home and lock. Hangup removes microphone foreground state while Online messaging continues. Failure to promote service does not allow background capture. |
| A07 | Android / Pending | A06 | Communication mode/focus, earpiece/speaker, wired routes and restoration with API guards. | Mute survives supported route changes; lost focus/unrecoverable device failure ends cleanly. |
| AT02 | Android / Pending | A04–A07 | Adapter faults and physical ownership/lifecycle/routing tests. Include pre-A06 foreground termination and post-A06 continuation as distinct policies. | Denial/revocation, repeated stop, unplug, service/process destruction leave no capture or revived call. Activity reattachment observes the same service session. |

Microphone foreground services require the appropriate declarations and runtime permission, and background activation is restricted. Start/accept through a visible Activity, establish eligible service state, then permit background continuation. [Service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

Additional rules:

- Use explicit service actions. Unknown/stale call actions must not change Online preference or start networking.
- Preserve `START_NOT_STICKY`; process death does not restart a call.
- Keep microphone hardware optional for messaging-only devices.
- Ringing does not seize the microphone or discard a recording; use visual notification while recording.
- Guard voice-message recording/playback in both UI and command layers while a call owns audio.
- Notification Accept opens the Activity; it does not assume background microphone activation is permitted.
- Separate call and voice-message permission request IDs; revalidate call identity and eligibility after permission results.
- Guaranteed Bluetooth support remains deferred.
- Add a scoped wake lock only if measurements demonstrate a need.

Use supported, API-guarded communication routing/focus APIs. [AudioManager](https://developer.android.com/reference/android/media/AudioManager), [audio focus](https://developer.android.com/media/optimize/audio-focus).

#### Early integrated demonstration

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| **D01** | **Both / Pending** | **A04, A05, W04, W05, BT01** | Minimal development-only call/accept/mute/hangup controls. Android controls bind to the existing `MessengerService` controller and provide visible permission/foreground eligibility only. Use production builds and real signaling/media. | Android ↔ Windows calls in either direction for approximately two minutes, with mute, hangup and Offline cleanup. Before A06, Home/lock/foreground loss causes service-controlled termination, with no background capture. |

D01 needs neither notification polish nor extended hardening. It introduces no throwaway ownership model. A06 later changes background policy on the same service/session architecture.

### Phase A3 — Android calling UI and notifications

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| A08 | Android / Pending | A06, A07 | Direct-chat Call action with Offline/unverified/unsupported/busy explanations. | No group button; repeated taps create one invitation; network work stays off UI thread. |
| A09 | Android / Pending | A08 | Incoming/outgoing/active views, duration, mute, route, hangup and return-to-call entry. | Service snapshots drive all views; recreation does not duplicate/end calls; accessible labels/touch targets. |
| A10 | Android / Pending | A09 | Call notification channel and API-appropriate presentation; PendingIntent identity tied to current call. | Background decline/hangup work; expired actions do nothing; all terminal paths remove notifications. |
| AT03 | Android / Pending | A08–A10 | Foreground/background incoming, lock screen, notification denial, navigation and voice-message interaction. | No spontaneous answer/capture; presentation does not create controllers; notification limitations are explained. |

No full-screen intent privilege or Telecom integration is required for MVP. A killed/stopped LAN service cannot receive incoming calls.

### Phase A4 — Android release audit and acceptance

Production build integration has already completed in A03b.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| A11 | Android / Pending | A03b, AT01b, AT02, AT03 | Audit final dependencies/notices, ABI set, APK size/delta and installed footprint. Verify extraction/compression/alignment, all declared ABIs, API 26/newest-tested-device loading and version-derived output name. | Artifact report identifies each library/ABI and loading result; 16 KiB claims have evidence; unsupported architectures resolved explicitly. |
| A12 | Android / Pending | A11, AT01, AT01c, BT01–BT03 | Build/sign release after MVP gates. Preserve package ID, original signer and increasing versionCode. | Upgrade without uninstall retains identity, contacts, messages and drafts; signature/manifest/output agree. |
| A13 | Android / Pending | A12 | Update Android status and shared comparison. | Code, automated verification and physical acceptance remain separate; unavailable checks are Pending-Unavailable. |

Check fresh and upgrade installation, including storage for extracted libraries. Build outputs belong in `D:\LAN-Messenger\outputs` when execution has the necessary access. Keep one source tree and existing branch/remotes; preserve signing material and exclude `.private` from archives. Commit locally; push only when requested.

### Phase A5 — Future Android video

| ID | Platform / status | Dependencies | Future work | Acceptance / testing |
|---|---|---|---|---|
| AV01 | Android / Deferred | Accepted voice release, BV01 | Service-owned camera/media adapter, optional capability and camera permission/service requirements. External camera attachments are not a live source. | Voice-only calls never open/request camera; denial preserves audio. |
| AV02 | Android / Deferred | AV01 | Preview, remote video, camera switch/off and explicit upgrade consent. Activity owns rendering surfaces, not the call session. | No capture before consent; surfaces detach/reattach safely across navigation/orientation. |
| AV03 | Android / Deferred | AV02, WV02 | Video interoperability, adaptation, thermal/battery and synchronization checks. | Disabled/failed/declined video preserves audio; Offline stops all media. |

During voice work, build only the extension points: media-kind capability, negotiation generation, separate track controls and UI-independent adapters.

## 2. Windows

### Phase W0 — Architecture decision and media feasibility

**Outcome:** explicitly decide supported Windows process architectures before freezing the media dependency.

Relevant source: [LanMessenger.csproj](/D:/LAN-Messenger/source/windows/LanMessenger.csproj), [SecureChannel.cs](/D:/LAN-Messenger/source/windows/SecureChannel.cs), [PeerEngine.cs](/D:/LAN-Messenger/source/windows/PeerEngine.cs), [Program.cs](/D:/LAN-Messenger/source/windows/Program.cs), and [ChatWindowVoicePlayback.cs](/D:/LAN-Messenger/source/windows/ChatWindowVoicePlayback.cs).

The current project has no explicit RID or platform target. A native bridge changes deployment from an architecture-neutral managed build configuration to **explicit architecture-specific application packages**. Previous apphost architecture and actual user-device support must be inspected rather than inferred from AnyCPU.

#### W00a architecture decision

Produce a support matrix covering:

| Item | Required decision/evidence |
|---|---|
| Existing installations | Inventory Windows OS architecture, current application process architecture, installed Desktop Runtime architecture and previous package/apphost architecture. Record unknowns explicitly. |
| x64 | Candidate target: `win-x64` with matching x64 managed process, bridge and transitive native libraries. |
| ARM64 | Decide native `win-arm64` support versus a specifically tested x64-under-emulation option. Do not label emulated execution as native ARM64 support. |
| x86 | Decide explicitly whether `win-x86` must remain supported. Distinguish a 32-bit process on x64 Windows from a 32-bit-only OS. |
| Native dependency | Match each supported process architecture to an actual available/buildable artifact, toolchain and runtime. Shiguredo x64/ARM64 availability does not establish x86 support. |
| Upgrade | Preserve existing user-data path, DPAPI identity access, contacts and history when changing executable/runtime architecture. |

**Default compatibility rule:** do not drop existing x86 users. If x86 users exist—or the inventory is insufficient to establish that x86 support is unnecessary—B02 cannot freeze an x64/ARM64-only dependency as the complete solution. Either:

1. Prove a compatible x86 build/dependency path that meets the same call gates; or
2. Obtain an explicit support-scope decision before narrowing support.

A source build or managed candidate is not automatically an x86 solution; test its full dependency chain. An unresolved x86 decision blocks dependency freeze, not the ability to conduct a limited x64 feasibility experiment. Record that experiment as limited evidence.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| **W00a** | **Windows / Pending** | **B01** | Complete architecture inventory and support policy above before candidate selection. Define initial RID/platform matrix, bridge ABI, toolchain/CRT, callbacks and loading requirements; refine with W00 evidence. | Explicit x86 retain/exclude decision with evidence or authorized scope change. Unknown architecture support cannot silently become “not supported.” B02 receives a complete support matrix. |
| W00 | Windows / Pending | B01, W00a | Evaluate named candidates constrained by the support matrix. Prove Opus, DTLS-SRTP, AEC, capture/rendering, callbacks, statistics and teardown. Test `SecureChannel` concurrent read/write, heartbeat, timeout and cancellation. | Physical audio/B02 interoperability and viable builds for every retained architecture; managed alternative proves equivalent audio behavior. |

The self-build fallback remains a multi-day infrastructure task. Resolve persistent TLS limitations with a tested call-specific adapter or revised signaling design before B03.

### Phase W1 — Windows signaling and explicit RID-based build integration

**Outcome:** implement the shared contract and make normal builds/publishes load architecture-matched media dependencies before W04.

Proposed components: `CallSession.cs`, `CallController.cs`, `CallProtocol.cs`, `CallSignaling.cs`, `ICallMedia.cs` and `PeerEngine.Calls.cs`.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| W01 | Windows / Pending | B03 | Serialized controller, immutable snapshots, injectable clock and fake media. | Shared fixtures match Java, including glare and termination races. |
| W02 | Windows / Pending | W01 | Capabilities/open/framing and explicit TLS/socket handoff around receive disposal and inbound semaphore. | Successful handoff survives `Receive`; failure disposes once; no leaked/over-released admission slots. |
| W03 | Windows / Pending | W02 | Offline/disposal, trust changes, forget/delete and data-reset hooks. | Cleanup outside engine locks; no deadlocks or stale generation activity. |
| **W03b** | **Windows / Pending** | **B03, W00a** | **Implement the AnyCPU-default-to-explicit-RID deployment transition.** Configure compatible RID/platform targets, pinned references, bridge build/reference, transitive runtime files, deterministic loading and copy-to-build/publish rules. Apply the frozen architecture matrix below. | Every supported RID builds/publishes to isolated output, initializes/disposes media without capture and runs without developer PATH/manual DLL copies. Wrong/missing architecture produces a clear error; messaging remains available when optional media loading fails. |
| WT01 | Windows / Pending | W01–W03 | Controller/codec tests and harness commands; update explicit compile includes. | Production logic tests run without UI/native loading. Pure managed harness may remain AnyCPU. |
| **WT01b** | Windows / Pending | W03b | Per-RID artifact/load checks, effective process architecture, dependency architecture and upgrade smoke tests. | Each supported package contains only its matching native chain; clean-machine launch and existing-data access pass. No x86 support claim from x64-only testing. |

#### W03b build/publish contract

The exact supported rows are frozen at B02/B03:

| Supported package | RID | Effective platform target | Required native chain |
|---|---|---|---|
| Windows x64 | `win-x64` | `x64` | x64 bridge, media library and transitive runtimes. |
| Windows ARM64, if retained | `win-arm64` | `ARM64` | ARM64 bridge, media library and transitive runtimes. |
| Windows x86, if retained | `win-x86` | `x86` | x86 bridge, media library and transitive runtimes. |

Implementation requirements:

- Make selected `RuntimeIdentifier` and effective `PlatformTarget` agree; do not leave production process bitness to accidental host/runtime selection.
- A `RuntimeIdentifiers` list alone is not a multi-architecture package. Build/publish each supported RID explicitly.
- Retain framework-dependent deployment unless a separate decision changes it: specify `SelfContained=false` and document the matching .NET 9 Desktop Runtime requirement.
- Isolate native build, intermediate and publish outputs by architecture/RID to prevent stale cross-architecture binaries.
- Produce clearly named version/RID packages, such as `LanMessenger-Windows-<version>-win-x64`.
- Define a deterministic developer build target and test-runner target. Do not silently use the developer machine’s architecture for release support claims.
- Keep pure protocol harnesses independent of the native dependency; align native/UI test processes with the package under test.
- Validate PE/process architecture and load all dependencies from controlled application paths.
- Upgrade using the same user-data location and Windows user; verify identity and history access without resetting data.
- A wrong package cannot be repaired by copying DLLs from another architecture. Provide clear package/runtime selection instructions.

Microsoft documents RIDs as target-platform identifiers and requires platform-specific publishing for native dependencies. [RID catalog](https://learn.microsoft.com/en-us/dotnet/core/rid-catalog), [publishing overview](https://learn.microsoft.com/en-us/dotnet/core/deploying/).

W03b can proceed alongside controller work after B03. W00a defines support; **W03b implements the actual build/deployment transition**. W10 is final release validation.

### Phase W2 — Windows audio and application lifetime

**Outcome:** stable two-way audio independent of visible chat controls.

Proposed components: `WebRtcCallMedia.cs`, `CallAudioDevices.cs`, `AudioOwnership.cs` and the selected bridge/processing implementation.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| W04 | Windows / Pending | W03, W03b, WT01b | Media adapter, Opus, mute, readiness, SDP/ICE and statistics. Root native callbacks/handles; dispatch events to the call executor. | Real audio from normal RID-specific application output; no callbacks access freed state; failures terminate clearly. |
| W05 | Windows / Pending | W04 | App-wide audio ownership; await voice-draft finalization/device release. | No competing capture; drafts survive; controls recover after every terminal path. |
| W06 | Windows / Pending | W04, W05 | Input/output selection, defaults, privacy errors and unplug handling. Live switching only if validated. | Headset/speaker work; mute survives supported changes; no false Connected state after failure. |
| W07 | Windows / Pending | W06 | Hide-to-tray preserves calls; Exit, suspend and unrecoverable network changes end them. | Reopen shows same session; Exit releases sockets/microphone; resume never redials. |
| WT02 | Windows / Pending | W04–W07 | Fault injection, double disposal, callback/shutdown races and device loss on supported architectures. | No hangs, use-after-free, retained devices or resource growth in short repeat tests. |

D01 can run once A04/A05, W04/W05 and BT01 complete. Android is already service-owned at this milestone; background continuation remains disabled until A06.

### Phase W3 — Windows call interface and tray

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| W08 | Windows / Pending | W07 | Direct-chat Call action; incoming/outgoing/active views, duration, mute, device controls and hangup. | One representation per call; snapshots drive UI; network work does not block UI thread. |
| W09 | Windows / Pending | W08 | Incoming tray notification, return-to-call and hangup; preserve hide versus Exit. | Hidden application has accessible call indication; closing presentation does not answer/duplicate calls. |
| WT03 | Windows / Pending | W08, W09 | Extend Windows UI tests with fake-media call flows, keyboard/accessibility and stale events. | Controls and disabled states work; disposed-window callbacks are harmless. |

Keep existing voice-message seek/replay regression tests.

### Phase W4 — Shared MVP acceptance and release

**Outcome:** verify basic calling for release while keeping extended performance characterization separate.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| BT01 | Both / Pending | AT01, WT01 | Shared `tests/calls/` fixtures and Java/C# processes using real TLS/fake media. | All pairings and initiation directions; trust/legacy failure, Busy, glare, malformed input, races and cleanup. |
| BT02 | Both / Pending | AT02, AT03, WT02, WT03, BT01 | Physical MVP matrix using production UI/dependencies and declared architecture packages. | Basic calls, routes, mute, lifecycle and teardown pass on all pairings; approximately five-minute normal calls. |
| BT03 | Both / Pending | BT02 | Attachment coexistence, Offline packet/resource checks and existing regressions. | No security/lifecycle regression; text/files work; native sockets obey Offline. |
| **W10** | Windows / Pending | W03b, WT01b, WT01–WT03, BT01–BT03 | Audit versioned per-RID packages, notices, clean-machine loading and existing-data upgrade. Verify B02’s x86 disposition and all retained architectures. | Correct process/native/runtime architecture, no developer tools needed, exact dependencies/checksums and preserved data. Untested architectures are not advertised as accepted. |
| B04 | Both / Pending | A12, W10 | Update platform status/comparison, compatibility and artifact evidence; create local commits as authorized. | Code, automation and physical acceptance separated; no push or branch/remote changes. |
| BH01 | Both / Pending — Hardening | BT03 | Extended quality/soak tests below. May run before or after MVP packaging; not a dependency of A12/W10. | Results and fixes tracked separately. Newly discovered security/lifecycle defects still block release until fixed. |

#### MVP test matrix

| Area | Required cases |
|---|---|
| Pairings | Android ↔ Android, Windows ↔ Windows, Android ↔ Windows; both initiation/hangup directions. |
| Call flow | Accept, decline, cancel, timeout, Busy, simultaneous dialing and repeated rapid actions. |
| Security | Unverified/changed identity, revoke/forget during call, spoofed/stale ID, fingerprint mismatch and insecure negotiation rejection. |
| Android ownership | One service controller across bindings; no Activity-owned media; pre-A06 foreground-only termination; post-A06 continuation through recreation/Home/lock. |
| Android lifecycle | Permission denial/revocation, denied notifications, focus loss and service/process termination. |
| Windows lifecycle | Hide/reopen, tray, Exit, suspend/resume, microphone privacy denial and unplug. |
| Windows architecture | Each retained RID’s clean-machine load and upgrade; process/native match; x86 disposition verified; native ARM64 versus emulation clearly distinguished. |
| Network | Blocked UDP, client isolation, unsupported interface, Wi-Fi loss, peer crash, signaling loss and Online/Offline races. |
| Audio ownership | Incoming while recording, accept during finalization, stale permission result, playback interruption and return to voice messages. |
| Coexistence | Text and representative large Normal/Fast transfers during calls; responsive call controls and correct files. |
| Compatibility | Unsupported calls fail within deadline; old/new messaging remains compatible; upgrades retain identity/history/drafts. |
| Android packaging | Correct ABIs, extraction/compression/ELF policy, API 26 and newest-tested-device loading. |

#### MVP gates versus hardening

| Check | MVP gate | BH01 hardening target |
|---|---|---|
| Setup | Typically connected within five seconds after acceptance; always respects setup deadline/failure reporting. | Timing distributions under load/adverse conditions. |
| Audio quality | Approximately five-minute call per pairing; intelligible simultaneous speech, usable headset/speaker audio, no sustained echo or growing delay. | Thirty-minute soak per pairing and wider device/route matrix. |
| Repetitions | Ten short setup/teardown cycles per implementation, plus physical repeats; no retained mic/socket or clear resource growth. | At least 100 cycles and longer memory/handle/thread analysis. |
| Latency | No obvious conversational lag; no unsupported numeric claim. | External mouth-to-ear measurement, target below 250 ms on clean LAN; RTT is not equivalent. |
| Network failure | Connectivity failures end/fail within deadlines and clean up. | Controlled 2% loss, 50 ms added jitter and reordering characterization. |
| Privacy/mute | No intelligible speech reaches peer while muted; no unsolicited capture. | Extended route/codec/device combinations. |
| Offline | No call sockets/capture once actual Offline is reported; no revival on Online. | Repeated generation/toggle stress. |
| File coexistence | Representative transfers correct; hangup/signaling responsive. | Sustained saturation, CPU/battery/throughput profiling and evidence-driven scheduling changes. |

Security defects, unsolicited capture, broken cleanup, data loss and dependency-loading failures cannot be deferred as hardening.

Run targeted regressions during implementation and the full runner before release: voice drafts/scheduler, WAV/PCM contracts, voice interoperability, Offline lifecycle, verification, transfers/resume, groups and Windows UI. Update Android’s source-pattern lifecycle test deliberately and supplement it with behavior tests for service ownership.

Record dependency revision, OS/device, architecture, route, network and result. Historical flakes require investigation; unavailable equipment leaves acceptance Pending-Unavailable.

### Phase W5 — Future video and shared negotiation

| ID | Platform / status | Dependencies | Future work | Acceptance / testing |
|---|---|---|---|---|
| BV01 | Both / Deferred | Accepted voice release | Video capability, explicit upgrade request/accept/decline, negotiation generation, glare/rollback and common codec/profile. | Voice-only peers remain compatible; declined upgrade preserves audio. |
| WV01 | Windows / Deferred | BV01 | Camera capture/enumeration and isolated renderer; extend bridge/adapter for every supported RID. | Privacy denial/unplug releases camera without ending audio. |
| WV02 | Windows / Deferred | WV01 | Preview, remote video, camera selection/off and consent UI. | No capture before consent; resize/hide/restore safe. |
| BV02 | Both / Deferred | AV03, WV02 | Video interoperability, bandwidth adaptation, audio priority, synchronization and downgrade. | Failed/declined video preserves audio; all media ends on Offline. |

#### Implementation order

1. **B00/B01 → W00a architecture policy → A00/W00 → B02/B03:** prove dependencies and freeze explicit platform support.
2. **A01–A03 → A03c/AT01c; W01–W03:** implement signaling and establish Android service ownership using fake media.
3. **A03b/AT01b and W03b/WT01b:** integrate production dependencies and explicit Windows RID packages; may overlap step 2.
4. **AT01/WT01 → BT01; then A04/A05 and W04/W05 → D01:** demonstrate foreground Android ↔ Windows calling with permanent ownership architecture.
5. **A06/A07 and W06/W07 → A3/W3:** add background continuation, routes and complete presentation.
6. **BT02/BT03 and A11 → A12/W10 → B04:** release after MVP and architecture acceptance.
7. **BH01:** extended hardening, tracked independently.
8. **Video phases:** later implementation using the accepted call foundation.
