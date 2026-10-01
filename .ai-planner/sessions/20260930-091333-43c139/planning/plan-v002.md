# plan-v002 | version=2 | Phased voice calls for Android and Windows

Implement **one-to-one, full-duplex LAN voice calls**, using existing verified peer connections for signaling and **WebRTC-compatible media with Opus and DTLS-SRTP**. Keep call management separate from audio/video tracks so video can be added later.

This is a planning deliverable only. No project files were modified, and no builds or tests were executed. Implementation and verification tasks are **Pending** until execution is explicitly requested. Video tasks are **Deferred**.

The plan has two major platform sections. Tasks labeled **Both** define shared work once. Production dependency integration must precede production media code on both platforms.

## 1. Android

### Phase A0 — Shared architecture and paired feasibility

**Outcome:** select a workable media stack and demonstrate Android ↔ Windows audio before implementing the complete calling feature.

#### Current implementation baseline

| Area | Current code and planning implication |
|---|---|
| Android application | Java, minimum API 26, target API 34. The raw javac/D8/aapt build currently has no WebRTC dependency integration. |
| Android compilation | `build.ps1` compiles every Java file in `src/net/lanmsg/chat`. Adding `org.webrtc` imports before supplying their compile/runtime dependencies breaks the application build. |
| Android packaging | The script adds only `classes.dex`; it currently packages no WebRTC `lib/<abi>/*.so` files. |
| Networking | Both `PeerEngine` implementations provide verified TLS connections, five-field LM4 `HELLO`, short transactions and 16 KiB line limits. |
| Capabilities | Group `CAPS=2` has an existing meaning. Calling requires a separate capability. |
| Service | `MessengerService` owns the Android engine and Online/Offline lifecycle; its current foreground-service type is `connectedDevice`. |
| Existing audio | Voice messages use fixed 16 kHz mono WAV data. Android recording/playback belongs to the Activity and stops on pause; live calls need service ownership. |
| Permission handling | Android’s existing recording-permission callback starts a voice-message recording. Calls need separate request identification and stale-result checks. |
| Windows | .NET 9 WinForms, BouncyCastle-backed `SecureChannel`, and `waveIn`/`waveOut` voice-message adapters. No production WebRTC reference/native-copy configuration exists. |
| Verification records | `tests/voice_interop.py` exists and is invoked by `tests/run.ps1`, despite older status paragraphs saying interoperability had not started. This confirms test wiring, not a current passing result. |

Relevant source: [Android build](/D:/LAN-Messenger/source/android/build.ps1), [manifest](/D:/LAN-Messenger/source/android/AndroidManifest.xml), [PeerEngine](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/PeerEngine.java), [MessengerService](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/MessengerService.java), and [MainActivity](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/MainActivity.java).

The current source and platform status records govern implementation. Earlier plans provide context only.

#### Product scope and delivery milestones — Both

| Milestone | Scope and gate |
|---|---|
| **Feasibility demonstration: B02** | A foreground Android ↔ Windows prototype exchanges encrypted two-way audio on a LAN. Proves dependency viability; not a releasable application. |
| **First integrated demonstration: D01** | Production build pipelines and signaling support a foreground call, acceptance, mute, hangup and Offline cleanup using minimal test controls. Does not wait for notification polish or extended performance tests. |
| **MVP release** | Complete basic call controls and platform lifecycle behavior, tested on all three pairings: Android ↔ Android, Windows ↔ Windows and Android ↔ Windows. |
| **Hardening milestone: BH01** | Longer soaks, 100-cycle automation, externally measured latency and detailed impairment/performance checks. These are tracked separately from MVP release. |
| **Future video** | Negotiated video tracks, explicit camera consent and platform rendering, built on the accepted voice foundation. |

MVP behavior:

- One verified direct contact and one pending/active call per device.
- Reachable IPv4 LAN peers; no internet service required.
- Call, accept, decline, cancel, hang up, mute and basic route/device selection.
- Clear calling, ringing, connecting, connected and terminal states.
- Android established calls continue through navigation and screen lock while the service remains alive.
- Windows established calls continue while hidden to the tray.
- Android incoming calls are available while its Online service is running and reachable.
- Offline ends all call networking; returning Online does not resume the call.
- Text and attachments remain available; call audio owns the microphone/playback resources exclusively.
- No automatic answering or call recording.

Deferred: group calls, call waiting/hold, durable call history, internet relays, automatic redial/reconnection, guaranteed Bluetooth support and video UI.

#### Architecture — Both

Prefer a **libwebrtc-based adapter on both platforms**, retaining a managed Windows alternative only if it satisfies the same media, echo-control, lifecycle and future-video gates.

```text
Android Activity / Windows call UI
                 ↓
      Call controller and state
          ↙               ↘
Verified LM4/TLS       CallMedia interface
call signaling        ↙                ↘
                Android adapter   Windows adapter
```

Reuse verified identities, contact lookup, lifecycle hooks and audio-ownership patterns. Keep the WAV, draft and attachment contracts intact. Live calls do not use WAV files, attachment scheduling or the voice-message duration cap.

WebRTC leaves signaling to the application and supplies native integration paths. This supports using existing authenticated connections for negotiation. [WebRTC peer connections](https://webrtc.org/getting-started/peer-connections), [native Android development](https://webrtc.github.io/webrtc-org/native-code/android/).

#### B01 dependency shortlist

The estimates below are **planning estimates for initial bring-up and packaging**, assuming an experienced developer and suitable build hardware. They exclude the complete calling feature. Exact revisions, licenses, toolchains, supported architectures and capabilities must be verified at B02; a package name alone is not evidence that the required configuration works.

**Android candidates**

| Candidate | Capabilities and gaps | Expected effort / packaging |
|---|---|---|
| **Preferred first spike: `io.github.webrtc-sdk:android`**, from `webrtc-sdk/android` | Community prebuilt libwebrtc Java/JNI distribution using `org.webrtc`. Evaluate the full variant for Opus, DTLS-SRTP and configured audio processing/AEC. Actual speakerphone performance remains a device test. Avoid selecting a stripped video-codec variant merely to reduce the initial download. | **Approximately 1–3 engineering days** for dependency inspection and initial raw-build integration. Unpack AAR classes, native libraries and any required metadata/resources; supply javac and D8 inputs; package each supported ABI. It is not a current Google-published Maven dependency. [Repository](https://github.com/webrtc-sdk/android). |
| **Fallback: pinned upstream libwebrtc self-build**, using `depot_tools`, GN/Ninja and `tools_webrtc/android/build_aar.py` | Supplies the native peer-connection stack, Java/JNI and configurable audio processing. Build and verify Opus, DTLS-SRTP and AEC; own updates and build flags. No app signaling or service lifecycle is supplied. | **Approximately 5–10+ engineering days** for initial infrastructure/reproducibility, then application packaging. Requires a supported Android native build host, source/toolchain downloads and build capacity; do not assume the existing PowerShell SDK environment suffices. [Android build documentation](https://webrtc.github.io/webrtc-org/native-code/android/). |

Libwebrtc includes an audio-processing module; the selected adapter must configure and test it rather than assume echo cancellation works merely because the dependency loads. [Audio-processing interface](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_processing/include/audio_processing.h).

**Windows candidates**

| Candidate | Capabilities and gaps | Expected effort / packaging |
|---|---|---|
| **Preferred first spike: Shiguredo `webrtc-build` prebuilt Windows libwebrtc plus a project-owned narrow C ABI bridge** | Distribution supplies native libraries, headers and revision information, including Windows x64/ARM64 builds. Evaluate Opus, DTLS-SRTP and libwebrtc AEC/audio-device configuration. It does **not** supply the project’s .NET wrapper or WinForms integration. Published binaries omit H.264/H.265 codecs; future video must select a tested common codec. | **Approximately 3–7+ engineering days** for bridge bring-up and packaging. Build the bridge against the exact matching headers/library/toolchain; distribute its DLL and runtime dependencies. [Repository](https://github.com/shiguredo-webrtc-build/webrtc-build). |
| **Fallback: upstream libwebrtc self-build plus the same C ABI bridge** | Full native stack with configurable Opus, DTLS-SRTP and AEC/audio processing. Gives control over revisions/build flags; still needs bridge ownership, callbacks and device integration. | **Approximately 5–10+ engineering days for build infrastructure alone**, plus bridge/application integration. Explicitly a multi-day infrastructure task involving `depot_tools`, compatible Windows toolchains and reproducible native builds. [Native development](https://webrtc.github.io/webrtc-org/native-code/development/). |
| **Alternative: `SIPSorcery` + `SIPSorceryMedia.Windows` + its Opus/Concentus path** | Managed WebRTC transport provides DTLS-SRTP; documented Opus support and separate Windows device endpoints exist. Treat AEC and production-quality playout/jitter behavior as **unproven**, not bundled libwebrtc-equivalent capabilities. If necessary, an additional processing component must be identified, packaged and demonstrated before selection. Future video adds codec/rendering dependencies. | **Approximately 2–4 days** for basic audio bring-up, potentially substantially more for AEC, playout and video readiness. Pin a .NET 9-compatible dependency set and check transitive dependency conflicts. Reject at B02 if it cannot meet speakerphone and lifecycle requirements. [Project capabilities](https://github.com/sipsorcery-org/sipsorcery), [DTLS-SRTP API](https://sipsorcery-org.github.io/sipsorcery/api/SIPSorcery.Net.DtlsSrtpTransport.html). |

Do not adopt archived Microsoft MixedReality-WebRTC as the default shortcut; its upstream repository is archived. [Repository status](https://github.com/microsoft/MixedReality-WebRTC/).

#### Foundation tasks

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| B00 | Both / Pending | Execution authorized | Record revision, existing changes, baseline tests, existing user-device architectures and available test equipment. Reconcile relevant stale status entries. | Source inspection, automation and physical acceptance are recorded separately; existing failures are explicit. |
| B01 | Both / Pending | B00 | Draft the shared contract below. Evaluate the named shortlist, starting with preferred candidates. Record build-host/toolchain needs and estimated effort. | Each spike has a concrete dependency, feature checklist and reproducible-input proposal. |
| A00 | Android / Pending | B01 | Prototype Java/JNI loading, peer connection, Opus, audio processing, statistics and teardown. Inspect ABI/API availability. | Physical foreground audio and repeated initialization/disposal work; microphone releases. Prototype packaging is not mistaken for production integration. |
| W00 | Windows / Pending | B01 | Perform Windows native/managed and TLS feasibility work in Phase W0. | Media and persistent signaling risks have evidence. |
| W00a | Windows / Pending | W00 | Define the proposed architecture/runtime/bridge distribution, as specified in W0. | Exact integration requirements available to B02. |
| B02 | Both / Pending | A00, W00, W00a | Pair the prototypes without external ICE servers. Select dependencies, revisions, provenance, licenses, architectures, tools and reproducible build recipes. | Encrypted two-way audio, mute, basic speaker/headset behavior and repeated teardown work across Android and Windows. |
| B03 | Both / Pending | B02 | Freeze contract, limits, shared fixtures, media adapter API and dependency manifest. | Both implementations can proceed independently; production build tasks have exact inputs. |

**Gate:** if feasibility fails, revise the dependency or adapter approach before continuing. Do not silently substitute plaintext media, WAV streaming or an untested audio-processing path.

### Shared call contract to freeze in B03 — Both

#### Compatibility and admission

- Preserve discovery, five-field `HELLO`, group `CAPS=2` and attachment frames.
- Add verified `CALLCAPS` with protocol version and supported media profile.
- Check capability freshly before each outgoing call; cached results may inform UI only.
- Distinguish unsupported/legacy response, transport failure, Offline and unverified contact.
- Add `CALLOPEN` and acknowledgement before switching to call framing.
- Accept verified direct peers only; reject group targets, self-calls and identity mismatch.
- Advertise support only after the media dependency loads and basic initialization succeeds, without opening a microphone.
- One pending/active session per device; unrelated invitations receive Busy.
- Bound handshake resources, invitation rate, queues and concurrent call admission.

#### Framing and connection ownership

- Ordinary LM4 lines remain limited to 16 KiB.
- Negotiated call frames use a four-byte unsigned big-endian length and UTF-8 JSON.
- Initial limits: 64 KiB frame, 48 KiB SDP, 128 ICE candidates per negotiation; bound queues by count and bytes. Confirm limits against spike output.
- Envelope: version, type, call ID, sender sequence, negotiation generation and typed body.
- Specify required/optional fields, duplicate keys, unknown-message handling and malformed UTF-8.
- Reject oversized lengths before allocation and apply whole-frame deadlines.
- Use one reader and one serialized writer per call channel.
- Transfer TLS and underlying socket ownership explicitly. Existing receive-scope disposal must not close a handed-off channel.
- Release ordinary inbound capacity after handoff while retaining engine ownership for cancellation.
- Reserve bounded control admission separately from long-running attachment serving; preserve overall connection limits.

#### Messages and identity

Define `INVITE`, `RINGING`, `ACCEPT`, `DECLINE`, `BUSY`, `CANCEL`, `OFFER`, `ANSWER`, `ICE`, `MEDIA_READY`, `HANGUP`, `ERROR`, `PING` and `PONG`.

For each, specify allowed sender, valid states, fields, duplicate handling and terminal effects.

Bind every session to a random call ID, verified peer ID/fingerprint, authenticated channel, engine network generation and negotiation generation. Media fingerprints travel through authenticated signaling and are enforced by the media stack.

Validate SDP/candidates before native processing. Version 1 accepts only its negotiated audio profile; reject unexpected video/data sections and insecure media negotiation. Logs contain state/timing/aggregate metrics, not media, keys or full SDP/ICE credentials.

#### State machine

```text
Idle → OutgoingRinging / IncomingRinging
     → Connecting → Connected → Ending → Idle
```

Keep the terminal reason in the UI snapshot after cleanup.

- Caller obtains permission before inviting, without capture.
- Callee accepts only a current invitation through an eligible permission/service flow.
- Caller creates the offer after acceptance; callee answers.
- No capture before explicit local action and remote acceptance.
- Send local `MEDIA_READY` when local media and transport are ready, without waiting for the peer’s readiness. Show Connected after both sides are ready.
- Bound early ICE candidates and associate them with the matching negotiation.
- Resolve simultaneous dialing by a deterministic caller-ID ordering; cancel the losing invitation and require acceptance of the surviving incoming call.
- Serialize accept/cancel, timeout/accept and hangup/device-failure races.
- Ignore late permissions, native callbacks and notification actions for terminated sessions.
- Never persist calls in message queues or resume them after process restart.

#### Timing and termination

| Timer | Proposed default |
|---|---|
| Capability/open | 10-second total budget including connection and TLS. |
| Ringing | 30 seconds from accepted invitation delivery; permission UI does not reset it. |
| Media setup | 15 seconds after acceptance. |
| Heartbeat | Every 5 seconds; fail after 15 seconds without valid inbound signaling. |
| Local teardown | Target completion within 2 seconds of a terminal event. |

Use monotonic clocks and call-specific I/O deadlines. Existing approximately six-second transport timeouts cannot be inherited blindly.

Lost signaling or terminal ICE failure ends the call. Specify a bounded transient-disconnection grace period without automatic ICE restart/redial.

Cleanup stops capture, invalidates callbacks, closes media/signaling, cancels timers, releases audio ownership and restores settings. Offline includes native media sockets; do not report actual Offline or permit another generation while old networking remains alive.

#### LAN media policy

- No external STUN/TURN or internet fallback.
- UDP host candidates on the LAN interface associated with authenticated signaling.
- Initially accept remote candidate addresses matching the authenticated IPv4 endpoint. Reject public, multicast, unspecified, unrelated-interface and production loopback candidates.
- Apply filtering to SDP-embedded and trickled candidates.
- Disable or explicitly resolve/filter mDNS candidates; prove that gathering stays on allowed interfaces.
- Blocked UDP, client isolation and unsupported network arrangements produce clear failures.
- Record native media port/firewall requirements; preserve discovery/control ports.
- Keep live media outside attachment queues and Android attachment-upload accounting.

### Phase A1 — Android signaling and production dependency integration

**Outcome:** validated signaling plus a production APK that can load the selected media dependency before `WebRtcCallMedia` implementation starts.

Proposed components: `CallSession`, `CallController`, `CallProtocol`, `CallSignaling` and `CallMedia`. Keep controller/codec classes free of Android and WebRTC dependencies.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| A01 | Android / Pending | B03 | Implement serialized state machine, immutable snapshots, injectable clock and fake media/signaling. | Deterministic coverage of states, Busy, glare, races, duplicate actions and stale callbacks. |
| A02 | Android / Pending | A01 | Implement capability/open, authenticated framing, ownership handoff and limits. | Production Java TLS tests cover legacy/untrusted peers, malformed/partial frames, deadlines and backpressure. |
| A03 | Android / Pending | A02 | Hook generation cancellation, revocation, changed certificates, deletion, data reset and shutdown. | Every path ends the session; old callbacks cannot act after Online restarts. |
| **A03b** | **Android / Pending** | **B03** | **Integrate the selected dependency into the production build before adding media implementation sources.** Add javac classpath inputs, dependency/transitive classes to D8, all generated DEX files, `lib/<abi>/*.so`, and required AAR resources/manifest content. Implement the packaging policy below. Fix relevant script failure handling/output parameterization needed for repeatable development builds. | Clean production-pipeline APK builds, installs and performs a no-capture dependency/factory initialization/disposal smoke test. Inspect DEX classes and native entries. No external/manual classpath or DLL installation is needed. |
| AT01 | Android / Pending | A01–A03 | Add JVM controller/codec tests and harness commands; update explicit test source lists. | Tests execute production logic without Android/JNI; fixture outcomes match C#. |
| AT01b | Android / Pending | A03b | Add artifact checks and native-load smoke procedure; verify missing dependency detection. | Build fails clearly for incomplete packaging; a missing native dependency cannot crash ordinary messaging at startup. |

A03b may proceed alongside A01–A03 after B03, but **must finish before A04**. Keep each integration step buildable. A00’s prototype cannot satisfy this prerequisite.

#### Android native packaging policy — implement in A03b, audit in A11

- **Planned universal APK ABI set:** `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, subject to artifact availability verified at B02. This protects compatibility with the previously Java-only app. Do not silently omit an existing user architecture; a missing ABI requires another build/artifact or an explicit revised support decision before release.
- Prefer one universal APK for existing sideload/upgrade behavior. Any later per-ABI distribution must clearly identify packages and preserve signer/package/version compatibility.
- For the current raw packaging pipeline, explicitly set **`android:extractNativeLibs="true"` and package native `.so` entries compressed**. Inspect actual ZIP compression methods instead of assuming `aapt add` selected them.
- Under that extraction policy, retain ordinary ZIP alignment before signing; the current `zipalign -f 4` is not evidence of ELF page-size compatibility.
- Require compatible ELF segment alignment in every shipped native dependency, including runtime libraries, for tested 16 KiB devices. Compression/extraction does not repair an incompatible ELF.
- If switching to direct-from-APK loading later, deliberately change to uncompressed native entries, `extractNativeLibs=false` and verified 16 KiB ZIP alignment using appropriate tooling. Do not mix policies accidentally.
- If the chosen dependency requires build-system migration, preserve the policy through its supported packaging settings and complete migration inside A03b.
- Record baseline APK size, total new size/delta, compressed and uncompressed native size per ABI, and installed/extracted footprint. Expect a material increase; measure rather than invent an exact number.
- Perform installation/loading on API 26 and the newest tested device, including a 16 KiB environment where support is claimed. Release audit repeats this with final artifacts and upgrade installs.

Android documents the extraction attribute and the separate ZIP/ELF considerations for 16 KiB page support; extracted libraries also increase installed storage. [Application manifest](https://developer.android.com/guide/topics/manifest/application-element#extractNativeLibs), [16 KiB compatibility](https://developer.android.com/guide/practices/page-sizes).

**Phase exit:** fake-media signaling passes and the production dependency smoke test passes. Cross-platform signaling acceptance additionally requires BT01.

### Phase A2 — Android live audio and service ownership

**Outcome:** real audio with deterministic ownership and lifecycle.

Proposed components: `WebRtcCallMedia`, `CallAudioRouter`, `AudioOwnership` and service call-command/snapshot adapters.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| **A04** | Android / Pending | **A00, A03, A03b, AT01b** | Implement peer connection, Opus, SDP/ICE, mute, readiness, statistics and disposal. Media stack owns continuous capture/rendering. | Two-way physical foreground audio using the production APK; mute works; initialization failure cleans up; no draft/attachment rows. |
| A05 | Android / Pending | A04 | Add process-wide audio ownership; make existing voice-recording finalization/release awaitable. Connect `VoiceUi` and `VoicePlayback`. | Starting/accepting a call stops playback and preserves recording as an unsent draft. Failed finalization/release blocks competing capture. |
| A06 | Android / Pending | A04, A05 | Make `MessengerService` own calls. Add microphone foreground-service permission/type and explicit active type masks. | Navigation, recreation, Home and lock preserve established calls; hangup removes microphone service state while Online messaging continues. |
| A07 | Android / Pending | A06 | Implement communication mode/focus, earpiece/speaker, wired routes and restoration with API guards. | Mute survives route changes; lost focus/unrecoverable route failure ends cleanly and restores settings. |
| AT02 | Android / Pending | A04–A07 | Test injected media failures and physical lifecycle/routing. | Permission denial/revocation, repeated stop, unplug, service destruction and process death leave no active capture or revived call. |

Android requires microphone foreground-service declarations and runtime permission, with restrictions on background activation. Start/accept through a visible Activity, establish the eligible service state, then allow background continuation. [Foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

Implementation rules:

- Replace `onStartCommand()`’s “anything except OFFLINE means Online” behavior with explicit actions.
- Unknown/stale call actions must not enable networking.
- Preserve `START_NOT_STICKY`; process death does not restart calls.
- Keep microphone hardware optional for messaging-only devices.
- Ringing does not seize the microphone or discard recordings; use visual notification while recording to avoid capturing a ringtone.
- During outgoing/accepted calls, block voice-message capture/playback at both UI and command layers.
- Notification Accept opens the Activity; do not assume background microphone activation is allowed.
- Use separate permission request IDs and revalidate call ID/state when results arrive.
- Defer guaranteed Bluetooth support.
- Add a scoped wake lock only if device evidence shows it is necessary.

Audio routing/focus must use supported, API-guarded communication APIs. [AudioManager](https://developer.android.com/reference/android/media/AudioManager), [audio focus](https://developer.android.com/media/optimize/audio-focus).

#### Early integrated demonstration

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| **D01** | **Both / Pending** | **A04, A05, W04, W05, BT01** | Add minimal development-only foreground call/accept/mute/hangup controls on both apps. Use production builds, verified signaling and the real adapters. Use a visible Android permission flow; do not promise background support yet. | Android ↔ Windows can initiate either way, talk for approximately two minutes, mute and hang up; Offline stops microphone and sockets. Leaving unsupported foreground demo conditions ends cleanly. |

D01 is a reviewable early milestone, not an MVP release. It does not depend on A3/W3 notification polish or BH01 extended hardening.

### Phase A3 — Android call UI and notifications

**Outcome:** complete the basic user-facing call flow.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| A08 | Android / Pending | A06, A07 | Direct-chat Call action with actionable Offline/unverified/unsupported/busy states. | No group call button; repeated taps create one invitation; network work stays off UI thread. |
| A09 | Android / Pending | A08 | Incoming/outgoing/active views, duration, mute, route, hangup and return-to-call entry. | Service snapshots drive presentation; recreation does not duplicate/end calls; accessible labels and touch targets. |
| A10 | Android / Pending | A09 | Call notification channel and API-appropriate presentation; unique current-call PendingIntent identity. | Background decline/hangup work; expired actions do nothing; every terminal path removes notifications. |
| AT03 | Android / Pending | A08–A10 | Foreground/background incoming, lock screen, notification denial, navigation and voice-message interactions. | No spontaneous answering/capture; notification limitations are explained without claiming guaranteed background ringing. |

No full-screen intent privilege or Telecom integration is required for MVP. A stopped/killed LAN service cannot receive calls.

### Phase A4 — Android release audit, packaging and acceptance

**Outcome:** validate final installable artifacts. Production classpath/native integration has already completed in A03b.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| **A11** | Android / Pending | **A03b, AT01b, AT02, AT03** | Audit final dependency licenses/notices, exact ABI set, APK size delta and installed footprint. Verify extraction/compression/alignment policy; validate every declared ABI and API 26/newest-tested-device loading. Finalize version-derived output naming. | Artifact report lists each native file/ABI, size and loading result. API 26 and newest tested device install/load checks pass; 16 KiB claims have evidence. Any unsupported architecture is resolved explicitly. |
| A12 | Android / Pending | A11, AT01, BT01–BT03 | Build/sign the MVP release after its gates pass. Preserve package ID, original signer and increasing versionCode. | Upgrade over current app retains identity, contacts, messages and drafts; manifest/signature/output version agree. No uninstall or key replacement. |
| A13 | Android / Pending | A12 | Update Android status and shared comparison with supported devices/routes and evidence. | Code, automation and physical acceptance are separate; unavailable checks remain Pending-Unavailable. |

Check both fresh and upgrade installations, including storage needs for extracted libraries. Record final size rather than treating the existing small APK footprint as preserved.

Build outputs belong in `D:\LAN-Messenger\outputs` when execution has the necessary filesystem access. Keep one source tree and the existing branch/remotes. Preserve signing material; exclude `.private` from archives. Commit locally and push only when requested.

### Phase A5 — Future Android video

| ID | Platform / status | Dependencies | Future work | Acceptance / testing |
|---|---|---|---|---|
| AV01 | Android / Deferred | Accepted voice release, BV01 | Add live camera capture/rendering, optional capability and camera permission/service requirements. External camera attachments are not a live video source. | Voice-only calls never open/request camera; permission denial preserves audio. |
| AV02 | Android / Deferred | AV01 | Preview, remote video, camera switch/off and explicit upgrade acceptance. | No capture before consent; surfaces survive navigation/orientation safely. |
| AV03 | Android / Deferred | AV02, WV02 | Cross-platform video, adaptation, thermal/battery and synchronization tests. | Declined/disabled/failed video preserves audio; Offline stops all media. |

Build only extension points during voice work: media-kind capability, negotiation generation, separate track control and UI-independent adapters.

## 2. Windows

### Phase W0 — Windows media and signaling feasibility

**Outcome:** prove the chosen dependency and persistent signaling model before production implementation.

The current application uses .NET 9 WinForms and a custom BouncyCastle TLS stream. Existing native voice-message buffer fixes inform lifecycle tests, but do not implement continuous call media.

Relevant source: [LanMessenger.csproj](/D:/LAN-Messenger/source/windows/LanMessenger.csproj), [SecureChannel.cs](/D:/LAN-Messenger/source/windows/SecureChannel.cs), [PeerEngine.cs](/D:/LAN-Messenger/source/windows/PeerEngine.cs), [Program.cs](/D:/LAN-Messenger/source/windows/Program.cs), and [ChatWindowVoicePlayback.cs](/D:/LAN-Messenger/source/windows/ChatWindowVoicePlayback.cs).

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| W00 | Windows / Pending | B01 | Evaluate the concrete Windows shortlist. Start with prebuilt libwebrtc plus a small C ABI bridge. Prove capture/rendering, Opus, DTLS-SRTP, AEC, callbacks, statistics and shutdown. Test `SecureChannel` concurrent read/write, heartbeat, timeout and cancellation. | Physical audio and B02 interoperability; no assumption that `SecureChannel` behaves like `SslStream`. Managed alternative must prove equivalent media behavior before selection. |
| W00a | Windows / Pending | W00 | Specify runtime architectures, bridge ABI, native/transitive dependencies, toolchain/CRT, callbacks and DLL search paths. Inventory existing Windows user architecture before limiting support. | B02 receives an exact integration recipe. Missing/wrong-architecture dependencies have a defined non-crashing error path. |

The source-build fallback is a separate multi-day infrastructure activity, not a small NuGet substitution. If persistent TLS is unsuitable, resolve a tested call-specific adapter or revised signaling design before B03 freezes the contract.

### Phase W1 — Windows signaling and production dependency integration

**Outcome:** shared-contract signaling plus a production application that can load its selected media implementation before W04.

Proposed components: `CallSession.cs`, `CallController.cs`, `CallProtocol.cs`, `CallSignaling.cs`, `ICallMedia.cs` and `PeerEngine.Calls.cs`.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| W01 | Windows / Pending | B03 | Serialized controller, snapshots, injectable clock and fake media. | Shared transition fixtures match Java, including glare and terminal races. |
| W02 | Windows / Pending | W01 | Capabilities/open/framing and explicit TLS/socket handoff around receive disposal and inbound semaphore. | Transferred channel survives `Receive`; failed handoff disposes once; no leaked/over-released admission slots. |
| W03 | Windows / Pending | W02 | Offline/disposal, trust changes, forget/delete and data reset hooks. | Cleanup occurs outside engine locks; no deadlocks or stale generation activity. |
| **W03b** | **Windows / Pending** | **B03, W00a** | **Implement production references and runtime distribution before W04.** Add pinned packages/project references to `LanMessenger.csproj`, native bridge build/reference if selected, architecture/RID mapping, transitive runtime files, and copy-to-build/publish rules. Establish deterministic DLL loading and lazy initialization. For a managed selection, integrate all required audio/codec/processing packages here. | Clean normal build and publish include required dependencies. The application performs a no-capture media initialization/disposal smoke test from its output directory, without developer PATH or manual DLL copies. Missing dependencies disable calling while messaging works. |
| WT01 | Windows / Pending | W01–W03 | Controller/codec tests, fake media and harness commands; update explicit C# test compile includes. | Production logic runs headlessly without WinForms or native media loading. |
| WT01b | Windows / Pending | W03b | Build/publish artifact checks and dependency-load smoke tests for intended architectures. | Correct binaries are present; wrong/missing architectures fail clearly; existing application still launches. |

W03b can proceed alongside controller work after B03. W00a defines distribution; **W03b actually implements it**. W10 is final release validation, not first native packaging.

### Phase W2 — Windows audio and application lifetime

**Outcome:** stable two-way audio independent of visible chat controls.

Proposed components: `WebRtcCallMedia.cs`, `CallAudioDevices.cs`, `AudioOwnership.cs` and a native bridge if selected. If B02 selects the managed option, keep the same interface and supply its required audio-processing implementation.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| **W04** | Windows / Pending | **W03, W03b, WT01b** | Media adapter, Opus, mute, readiness, SDP/ICE and statistics. Root callbacks/handles correctly; marshal events to controller executor. | Real audio using normal application output; no callback accesses freed state; failures terminate clearly. |
| W05 | Windows / Pending | W04 | App-wide audio ownership linked to recording/playback; await draft finalization and microphone release. | No overlapping capture; drafts survive; controls recover after every terminal path. |
| W06 | Windows / Pending | W04, W05 | Input/output selection, defaults, privacy errors and unplug behavior. Live switching only where validated; otherwise terminate clearly. | Headset/speaker work; mute survives supported switches; no false Connected state after device failure. |
| W07 | Windows / Pending | W06 | Hide-to-tray preserves call; Exit, suspend and unrecoverable network changes end it. | Reopen shows same session; Exit releases sockets/microphone; resume never redials. |
| WT02 | Windows / Pending | W04–W07 | Inject failure and exercise native lifecycle, double disposal, shutdown during callbacks and device loss. | No hangs, use-after-free, retained devices or resource growth in short repeat tests. |

D01 can run once A04/A05, W04/W05 and BT01 complete. Full route/background behavior still requires A06/A07 and W06/W07.

### Phase W3 — Windows call interface and tray

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| W08 | Windows / Pending | W07 | Direct-chat Call action, incoming/outgoing/active presentation, duration, mute, device controls and hangup. | One representation per call; controller snapshots drive UI; no blocking network work. |
| W09 | Windows / Pending | W08 | Incoming tray notification, return-to-call and hangup; preserve hide versus Exit distinction. | Hidden application receives accessible call indication; closing presentation never answers/duplicates calls. |
| WT03 | Windows / Pending | W08, W09 | Extend `tests/WindowsUi` with fake-media call flows, keyboard and accessibility tests. | Call controls/disabled states work; stale notification and disposed-window callbacks are harmless. |

Existing voice-message seek/replay tests remain regression coverage.

### Phase W4 — Shared MVP acceptance and release

**Outcome:** verify simple calling thoroughly enough to release, without making extended performance characterization a prerequisite.

| ID | Platform / status | Dependencies | Implementation notes | Acceptance / testing |
|---|---|---|---|---|
| BT01 | Both / Pending | AT01, WT01 | Shared `tests/calls/` fixtures and cross-process Java/C# signaling through real TLS with fake media. | All three pairings, both initiation directions; legacy/trust failure, Busy, glare, malformed input, races and cleanup. |
| BT02 | Both / Pending | AT02, AT03, WT02, WT03, BT01 | Physical MVP matrix below with production dependencies and UI. | Basic calls, routes, mute, lifecycle and teardown pass on all three pairings; approximately five-minute normal calls. |
| BT03 | Both / Pending | BT02 | Basic attachment coexistence, Offline packet/resource checks and existing regressions. | No security/lifecycle regression; text/files still work; call native sockets obey Offline. |
| W10 | Windows / Pending | W03b, WT01b, WT01–WT03, BT01–BT03 | Validate final versioned publish/package, licenses, runtime architectures and clean-machine/current-data upgrade. | Calls work without developer tools; artifacts contain exact dependencies and checksums; existing data remains usable. |
| B04 | Both / Pending | A12, W10 | Update status/comparison, compatibility and artifact evidence; make local commits as authorized. | Implemented, automated and physical results separated; no push/branch/remote changes. |
| **BH01** | **Both / Pending — Hardening** | **BT03** | Execute extended quality/soak targets below. May precede or follow MVP packaging; not a dependency of A12 or W10. | Results and follow-up fixes recorded separately. Any newly discovered security/lifecycle defect remains a release blocker until fixed. |

#### MVP required test matrix

| Area | Required MVP cases |
|---|---|
| Pairings | Android ↔ Android, Windows ↔ Windows and Android ↔ Windows; both initiation and hangup directions. |
| Call flow | Answer, decline, cancel, unanswered timeout, Busy, simultaneous dialing and repeated rapid actions. |
| Security | Unverified/changed identity, revoke/forget during call, spoofed/stale call ID, fingerprint mismatch and insecure negotiation rejection. |
| Android | Home, lock/unlock, recreation, denied notifications, permission denial/revocation, focus loss and service/process termination. |
| Windows | Hide/reopen, tray, Exit, suspend/resume, microphone privacy denial and device unplug. |
| Network | UDP blocked, client isolation, unsupported interface, Wi-Fi loss, peer crash, signaling loss and Online/Offline races. |
| Audio ownership | Incoming while recording, accept during draft finalization, delayed recording permission, playback interruption and return to voice messages after call failure. |
| Coexistence | Text and a representative large Normal/Fast attachment during a call; control responsiveness and transfer correctness. |
| Compatibility | Unsupported peer fails within deadline; old/new messaging still works; upgrade retains identity, history and drafts. |
| Packaging | Correct Android ABI/compression/extraction/ELF policy; API 26 and newest-tested-device loading; Windows clean-machine dependency loading. |

#### MVP release gates versus hardening targets

| Check | MVP gate | Separate BH01 hardening target |
|---|---|---|
| Clean-LAN setup | Typically connects within five seconds after acceptance; never exceeds contract deadline without reporting failure. | Distribution of setup times under load and adverse networks. |
| Conversation quality | Approximately five-minute call per pairing; intelligible simultaneous speech, usable headset/speaker audio, no sustained echo/feedback or growing delay. | Thirty-minute soak per pairing; wider device/route matrix. |
| Lifecycle repetitions | Ten consecutive short setup/teardown cycles per implementation, plus physical repeat calls; no retained microphone/socket or clear resource growth. | At least 100 automated cycles and longer memory/handle/thread analysis. |
| Latency | No obvious conversational lag in device acceptance; do not claim a numeric latency result without measurement. | External mouth-to-ear measurement, target below 250 ms on clean LAN; RTT is not a substitute. |
| Network failure | Blocking/loss of connectivity fails or ends within contract deadlines and cleans up. | Controlled 2% random loss, 50 ms added jitter and reordering; characterize intelligibility and delay growth. |
| Mute/privacy | No intelligible local speech reaches peer while muted; no unsolicited capture. | Longer route/codec/device verification. |
| Offline | No call sockets/capture remain once actual Offline is reported; returning Online does not revive calls. | Stress rapid toggles and network-generation races over many cycles. |
| File coexistence | Representative transfer remains correct; hangup/signaling stay responsive. | Sustained saturation, throughput/CPU/battery profiling and minimal scheduling changes if evidence requires them. |

Security, unsolicited capture, broken teardown, data loss and dependency-loading failures **cannot be deferred as hardening**. The split removes long performance campaigns from the critical path; it does not permit known critical defects.

Relevant regressions include voice drafts, scheduler, WAV/PCM contracts, voice interoperability, Offline lifecycle, verification, transfers/resume, groups and Windows UI. Run targeted suites during development and the full runner before release. The source-pattern Android service test must be updated deliberately and supplemented with behavioral checks.

Record dependency revision, platform/OS/device, architecture, route, network and result. Historical flakes require investigation rather than automatic waiver. Missing equipment leaves the associated acceptance Pending-Unavailable.

### Phase W5 — Future Windows video and shared negotiation

| ID | Platform / status | Dependencies | Future work | Acceptance / testing |
|---|---|---|---|---|
| BV01 | Both / Deferred | Accepted voice release | Video capability, upgrade request/accept/decline, negotiation generation, glare/rollback and common tested codec/profile. | Voice-only peers remain compatible; declined upgrade preserves audio. |
| WV01 | Windows / Deferred | BV01 | Camera enumeration/capture and isolated renderer; extend bridge or selected media implementation. | Privacy denial/unplug releases camera without ending audio. |
| WV02 | Windows / Deferred | WV01 | Preview, remote video, camera selection/off and consent UI. | No capture before consent; resize/hide/restore safe. |
| BV02 | Both / Deferred | AV03, WV02 | Cross-platform video, bandwidth adaptation, audio priority, synchronization and downgrade. | Audio survives failed/declined video and all media ends on Offline. |

#### Implementation order

1. **B00/B01 → A00 and W00/W00a → B02/B03:** choose and prove dependencies; freeze the contract.
2. **A01–A03 and W01–W03:** implement signaling and lifecycle with fake media.
3. **A03b/AT01b and W03b/WT01b:** integrate and validate production dependencies. These may overlap step 2, but must finish before their media tasks.
4. **AT01/WT01 → BT01; then A04/A05 and W04/W05 → D01:** demonstrate a simple foreground Android ↔ Windows call early.
5. **A06/A07 and W06/W07 → platform UI/notifications:** complete MVP lifecycle and presentation.
6. **BT02/BT03, Android A11 → A12 and W10 → B04:** release after MVP acceptance.
7. **BH01:** extended hardening, tracked independently.
8. **Video phases:** later implementation work using the accepted call foundation.
