# plan-v001 | version=1 | Voice calls for Android and Windows

Implement **one-to-one, full-duplex LAN voice calls**, using the existing verified peer connections for signaling and **WebRTC for live audio**. Keep call sessions separate from media tracks so video can be added later without replacing the calling foundation.

This is a plan only. No project files were modified, and no builds or tests were run. All implementation and verification tasks below are **Pending**; video implementation tasks are **Deferred**. Execution begins only after the user explicitly requests it.

There are two major platform sections. Tasks labeled **Both** define shared work once and apply to both platforms.

## 1. Android

### Phase A0 — Establish the shared architecture and prove feasibility

**Outcome:** prove that Android and Windows can exchange live audio using reproducible native dependencies before building the complete feature.

#### Current code findings

| Area | Evidence and implication |
|---|---|
| Android application | Java, minimum API 26, target API 34. `android/build.ps1` directly invokes javac, D8, aapt, zipalign and apksigner. It does not currently package a WebRTC Java/JNI dependency. |
| Networking | `PeerEngine.java` provides discovery, verified TLS connections, a five-field LM4 `HELLO`, and predominantly short transactions. Ordinary line reads are capped at 16 KiB. |
| Existing capabilities | Group `CAPS=2` already means group functionality. Calling needs an independent capability negotiation. |
| Service ownership | `MessengerService` owns the engine and its Online/Offline lifecycle. Its declared foreground-service type is currently `connectedDevice`. |
| Existing audio | Voice messages use `AudioRecord`/`AudioTrack` and fixed 16 kHz mono WAV data. `MainActivity.onPause()` stops recording and playback. Calls therefore need separate service-owned media resources. |
| Permission handling | The current recording-permission result starts a voice-message recording. Calls need a separate request identity and stale-result checks. |
| Tests | `tests/voice_interop.py` exists and is invoked by `tests/run.ps1`. Older status paragraphs saying interoperability has not started are stale relative to that wiring; test existence does not establish a current passing result. |

Primary integration points: [PeerEngine.java](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/PeerEngine.java), [MessengerService.java](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/MessengerService.java), [MainActivity.java](/D:/LAN-Messenger/source/android/src/net/lanmsg/chat/MainActivity.java), [AndroidManifest.xml](/D:/LAN-Messenger/source/android/AndroidManifest.xml), and [build.ps1](/D:/LAN-Messenger/source/android/build.ps1).

The earlier voice-call plan concerns the same request and was consulted as context. Current source governs this plan.

#### First-release scope — Both

| Included | Behavior |
|---|---|
| Participants | One verified direct contact; one pending or active call per device. |
| Supported pairings | Android ↔ Android, Windows ↔ Windows, Android ↔ Windows. |
| Network | Reachable IPv4 LAN peers; no required internet service. |
| Controls | Call, accept, decline, cancel, hang up, microphone mute, audio route/device selection. |
| Feedback | Calling, ringing, connecting, connected, busy, declined, unanswered, failed and ended. |
| Background operation | Established Android calls continue through navigation and screen lock while the service remains alive. Windows calls continue when hidden to the tray. |
| Incoming Android calls | Available while the Online service is running and reachable. A notification opens the acceptance UI. |
| Offline | Ends calling and closes media/signaling networking. Returning Online does not resume a previous call. |
| Messaging coexistence | Text and attachments remain usable. Call audio has exclusive ownership over voice-message recording/playback. |
| Privacy | No automatic answering, call recording, or stored audio. |

Deferred: group calls, call waiting/hold, durable call history, internet relays, automatic redial/reconnection, Bluetooth support as a guaranteed feature, and video UI.

#### Architecture decision — Both

Use a native WebRTC media adapter on each platform, with an initial Opus audio profile. WebRTC supports application-defined signaling and native clients; its Android implementation includes Java and JNI components. This supports the proposed architecture, but does **not** prove that a particular binary package will fit this repository. [WebRTC peer connections](https://webrtc.org/getting-started/peer-connections), [Android implementation](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/README).

The intended separation is:

```text
Android Activity / Windows call window
                 ↓
       Call controller and state
          ↙               ↘
Verified LM4/TLS       Media interface
call signaling        ↙            ↘
                Android WebRTC  Windows WebRTC
```

Reuse verified identities, contact lookup, lifecycle notifications and audio-ownership patterns. Keep the existing WAV/draft/attachment implementation intact. Its finite recording format and delivery scheduler are unsuitable as the live-call transport.

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| B00 | Both / Pending | Execution authorized | Record revision, existing changes, available devices and baseline test results. Reconcile relevant status contradictions. | Baseline distinguishes source inspection, automated results and physical acceptance. Existing failures are recorded without being silently waived. |
| B01 | Both / Pending | B00 | Write the draft call contract described below and select candidate native dependency approaches. | Both platform spikes use the same audio profile, identity model and LAN policy. |
| A00 | Android / Pending | B01 | Prove WebRTC Java/JNI loading, audio-device initialization, Opus negotiation, statistics and disposal. Inventory required ABIs; do not silently drop existing supported devices. | Runs on a physical Android device; repeated creation/disposal releases microphone and native resources. |
| W00 | Windows / Pending | B01 | Perform the Windows feasibility task specified in Phase W0. | Native adapter and signaling feasibility evidence available. |
| B02 | Both / Pending | A00, W00 | Connect the two prototypes on a LAN without external ICE servers. Select exact dependency revisions, provenance, licenses, hashes, supported architectures and reproducible build recipes. | Two-way audio, encrypted media, speaker/headset operation and repeated teardown work across Android and Windows. |
| B03 | Both / Pending | B02 | Freeze the shared protocol, limits, fixtures, native adapter contract and dependency manifest. | Java and C# can implement independently from one contract. No critical dependency/build question remains unresolved. |

**Gate:** if B02 fails, revise the native integration approach before proceeding. Do not substitute custom plaintext UDP or attachment streaming. Exact packages and revisions are an engineering decision of this gate, not an assumed completed selection.

#### Shared signaling contract to freeze in B03 — Both

**Compatibility and admission**

- Preserve discovery, the five-field `HELLO`, existing group `CAPS=2`, and existing attachment frames.
- Add a verified `CALLCAPS` transaction returning call protocol version and supported media profile.
- Perform a fresh capability check for each outgoing call. Cached capability may inform presentation only.
- Treat legacy closure/unrecognized response as unsupported; distinguish that from timeout, Offline and verification failure.
- Add `CALLOPEN` with an explicit acknowledgement before switching to call framing.
- Admit only verified direct peers. Reject self-calls, group targets and identity mismatches.
- Advertise calling only when the native implementation is installed and initialized successfully.
- One pending/active session per device; additional unrelated invitations receive Busy.
- Bound incomplete handshakes, per-peer invitation rate and total call admission resources.

**Framing and socket ownership**

- Retain the 16 KiB limit for ordinary LM4 lines.
- After successful call negotiation, use a four-byte unsigned big-endian payload length followed by UTF-8 JSON.
- Proposed limits: 64 KiB frame, 48 KiB SDP, 128 ICE candidates per negotiation, and queues bounded by both count and bytes. Verify these against actual spike output before freezing.
- Define required envelope fields: protocol version, message type, call ID, sender sequence, negotiation generation and typed body.
- Specify unknown-message handling, required/optional fields, malformed UTF-8, invalid lengths and duplicate keys.
- Reject oversized frames before allocation; apply whole-frame deadlines so slow byte-by-byte delivery cannot keep sessions alive indefinitely.
- Give each channel one reader and one serialized writer.
- Handoff must transfer ownership of both TLS and its underlying socket. Existing `using`/try-with-resources scopes must not close a transferred connection.
- Release ordinary inbound worker capacity after handoff while retaining engine tracking for Offline cancellation.
- Reserve bounded admission capacity for control handshakes separately from long attachment-serving work. This must not create an unbounded bypass of existing connection limits.

**Messages**

Define `INVITE`, `RINGING`, `ACCEPT`, `DECLINE`, `BUSY`, `CANCEL`, `OFFER`, `ANSWER`, `ICE`, `MEDIA_READY`, `HANGUP`, `ERROR`, `PING` and `PONG`.

For each message, specify allowed sender, valid states, required fields, duplicate behavior, response and terminal reason.

Bind the session to:

- Random call ID.
- Verified peer ID and pinned identity fingerprint.
- Authenticated signaling channel.
- Current engine network generation.
- Negotiation generation and media fingerprints exchanged over that channel.

Validate SDP and candidates before passing them to the native library. Accept only the negotiated audio profile in version 1; reject unexpected video/data sections and insecure media descriptions. Do not log SDP, ICE credentials, keys or raw media.

**State machine and races**

```text
Idle → OutgoingRinging / IncomingRinging
     → Connecting → Connected → Ending → Idle
```

Terminal reasons remain available in a UI snapshot after cleanup.

- Caller obtains microphone permission before inviting, without starting capture.
- Callee acceptance requires a current invitation and an eligible permission/service flow.
- Caller generates the initial offer after acceptance; callee answers.
- Neither side starts capture before explicit local user action and remote acceptance.
- Emit local `MEDIA_READY` independently when transport and local media initialization are ready. Display Connected after both readiness conditions are satisfied; this avoids waiting on each other before sending readiness.
- Queue early ICE candidates only within bounds and only for the matching negotiation.
- Use a deterministic caller-ID ordering for simultaneous outgoing calls between the same peers. Cancel the losing invitation; require explicit acceptance of the surviving incoming invitation.
- Serialize accept/cancel, timeout/accept and hangup/device-failure races.
- Late permission results, native callbacks and notification actions cannot revive a terminated session.
- Calls are ephemeral: no persistence in queued messages, no resumption after restart.

**Timers and termination**

Proposed defaults, frozen in B03:

| Timer | Default and starting point |
|---|---|
| Capability/open | 10-second total budget from operation start, including connection and TLS establishment. |
| Incoming ringing | 30 seconds from accepted invitation delivery; permission dialogs do not extend it. |
| Media setup | 15 seconds from accepted call. |
| Heartbeat | Every 5 seconds; fail after 15 seconds without valid inbound signaling activity. |
| Local teardown | Target completion within 2 seconds after a terminal event. |

Use monotonic clocks. Long-lived call I/O must use call-specific timeout handling: both implementations currently contain roughly six-second transport timeouts that cannot simply be inherited unchanged.

A lost signaling channel ends the call. ICE failure ends it; transient disconnection receives a bounded grace period within the contract, without ICE restart or automatic redial.

All terminal paths stop capture first, invalidate callbacks, close media/signaling resources, cancel timers, release audio ownership and restore platform audio settings.

Offline must include native WebRTC sockets. Do not report actual Offline or permit a new network generation while old call networking remains alive. Native teardown reliability is a release gate.

**LAN media policy**

- No configured STUN/TURN services or internet fallback.
- Use UDP host candidates on the LAN interface associated with the authenticated signaling connection.
- Initially accept remote candidate addresses matching that authenticated IPv4 endpoint. Reject public, multicast, unspecified, unrelated-interface and production loopback candidates.
- Validate candidates embedded in SDP as well as trickled candidates. Disable or explicitly resolve/filter native mDNS candidate behavior during feasibility.
- Reject native candidate gathering that escapes the selected interface policy.
- Blocked UDP, Wi-Fi client isolation or an unsupported interface arrangement gives a clear failure.
- Document the media UDP/firewall requirements selected by the native adapter; preserve existing discovery/control ports.
- Keep live media outside attachment queues and Android’s daily attachment-upload accounting. Measure coexistence before changing file-transfer policy.

### Phase A1 — Android call controller and authenticated signaling

**Outcome:** Android can negotiate and terminate calls correctly using fake media, before introducing real audio.

Proposed components under `android/src/net/lanmsg/chat/`: `CallSession`, `CallController`, `CallProtocol`, `CallSignaling` and `CallMedia`. Keep the controller and wire codec free of Android and WebRTC dependencies.

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| A01 | Android / Pending | B03 | Implement state machine, immutable snapshots, serialized executor and injectable clock/media/signaling interfaces. | Deterministic tests cover every state and terminal reason, Busy, simultaneous dialing, repeated actions and stale callbacks. |
| A02 | Android / Pending | A01 | Implement capabilities, call framing, authenticated channel handoff, queue limits and resource admission. | Production Java TLS tests cover legacy peers, unauthorized peers, malformed/partial frames, timeout and backpressure. |
| A03 | Android / Pending | A02 | Integrate generation cancellation, revocation, changed certificates, contact deletion, delete-app-data and engine shutdown. | Every path terminates the call; callbacks from the old generation cannot act after Online restarts. |
| AT01 | Android / Pending | A01–A03 | Add plain-JVM controller/codec tests and harness commands. Update explicit Java source lists in `tests/run.ps1`. | Tests run without Android/JNI and invoke production controller/codec code. Shared fixtures have identical expected outcomes in Java and C#. |

**Exit:** authenticated signaling and lifecycle behavior pass with fake media. Cross-platform signaling acceptance additionally depends on WT01 and BT01 below.

### Phase A2 — Android live audio, service ownership and audio coordination

**Outcome:** real calls survive Activity changes and use microphone, focus and routing correctly.

Proposed Android-only components: `WebRtcCallMedia`, `CallAudioRouter`, `AudioOwnership` and service-facing call command/snapshot adapters.

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| A04 | Android / Pending | A00, A03 | Implement native peer connection, Opus track, SDP/ICE exchange, mute, readiness, statistics and disposal. WebRTC owns call capture/rendering. | Two-way physical audio; mute suppresses speech; initialization failure cleans up; no voice-draft or attachment rows are created. |
| A05 | Android / Pending | A04 | Add process-wide audio ownership. Connect `VoiceUi` and `VoicePlayback` to it. Make recording finalization/release awaitable before call acquisition. | Starting/accepting a call stops playback and preserves recording as an unsent draft. Failed finalization/release prevents competing capture. |
| A06 | Android / Pending | A04, A05 | Make `MessengerService` own call lifetime. Add microphone foreground-service declaration/permission and explicit active service-type masks. Preserve existing Online/Offline semantics. | Activity navigation, recreation, Home and lock do not dispose an established call; ending it removes microphone service state while Online messaging continues. |
| A07 | Android / Pending | A06 | Implement communication audio mode/focus, earpiece/speaker selection, wired route handling and restoration. Use guarded APIs across supported OS versions. | Route changes preserve mute and release old devices. Focus loss or unrecoverable route failure ends cleanly; prior audio settings return. |
| AT02 | Android / Pending | A04–A07 | Add adapter fault tests and real-device lifecycle/routing checks. | Permission denial/revocation, initialization failure, repeated stop, unplug, service destruction and process death leave no active microphone or revived call. |

Android microphone foreground services require the microphone type, its permission and runtime `RECORD_AUDIO`. Background activation is also restricted. Use a visible Activity acceptance/start flow, then establish the required service state before allowing background continuation. [Service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

Additional implementation rules:

- Replace the current “anything except OFFLINE means Online” `onStartCommand()` dispatch with explicit actions. Unknown/stale call actions must not enable networking.
- Preserve `START_NOT_STICKY`; process death does not automatically restart a call.
- Keep microphone hardware optional so messaging works on devices without it.
- Ringing alone does not take microphone ownership or discard a recording. Use visual/vibration notification while recording to avoid injecting a ringtone into the draft.
- During an outgoing or accepted call, disable voice-message recording/playback and guard the underlying commands, including delayed permission callbacks.
- A notification Accept action opens the Activity; it does not assume background microphone activation is permitted.
- Separate voice-message and call permission request IDs; revalidate call ID and state when results arrive.
- Keep Bluetooth outside guaranteed MVP support unless separately implemented and tested.
- Request any wake lock only if device measurements establish a need; bound it to call lifetime.

Use Android’s communication routing and audio-focus APIs with API guards. The exact implementation must account for newer focus restrictions if the target SDK changes. [AudioManager](https://developer.android.com/reference/android/media/AudioManager), [audio focus](https://developer.android.com/media/optimize/audio-focus).

### Phase A3 — Android calling interface and notifications

**Outcome:** users can complete the call flow without knowing the transport details.

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| A08 | Android / Pending | A06, A07 | Add Call to direct-chat header; show actionable reasons when Offline, unverified, unsupported or already busy. | No group call action; repeated taps create one invitation. Capability checks run off the UI thread. |
| A09 | Android / Pending | A08 | Add incoming, outgoing and active-call views, elapsed time, mute, route and hangup controls; provide a return-to-call entry while navigating. | State comes from service snapshots; recreation neither duplicates nor ends the session. TalkBack labels and touch targets are verified. |
| A10 | Android / Pending | A09 | Add separate call notification channel and version-appropriate presentation. Bind actions and PendingIntent identity to the current call. | Decline/hangup work with app backgrounded; expired actions do nothing; notifications disappear on every terminal path. |
| AT03 | Android / Pending | A08–A10 | Test foreground/background incoming calls, lock screen, denied notifications, rotation/navigation and call/voice-message interactions. | No spontaneous answering/capture. When background notifications are unavailable, UI explains the limitation without claiming reliable ringing. |

No full-screen intent privilege or Telecom integration is required for the initial release. A killed/stopped service cannot receive LAN calls; make that limitation clear.

**Exit:** complete Android UX works against the Windows implementation and another Android device, subject to shared acceptance below.

### Phase A4 — Android packaging and acceptance

The current `build.ps1` hardcodes an older output filename despite the current manifest version. Status records also document its PowerShell stderr handling issue. Address the relevant build reliability issues as part of native packaging.

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| A11 | Android / Pending | B02 | Extend the build for pinned Java/JNI artifacts, native ABI libraries, required resources/manifest content and licensing. Support all generated DEX files if needed. Keep the existing pipeline if feasible; document any necessary build-system change. | A clean build contains all required classes/native libraries and loads on every declared supported ABI. Native alignment/loading requirements of tested Android versions are verified. |
| A12 | Android / Pending | A11, AT01–AT03, BT01–BT03 | Build and validate the Android release after feature acceptance. Preserve package identity, original signer and monotonically increasing versionCode. | Upgrade install retains identity, contacts, messages and drafts. APK manifest, signature and versioned output name agree. No signing secrets enter logs or archives. |
| A13 | Android / Pending | A12 | Update Android status and shared comparison with exact supported routes, OS/device evidence and remaining limitations. | Implemented code, automated tests and physical acceptance are recorded separately. Unavailable checks remain Pending-Unavailable. |

Build/test outputs belong in `D:\LAN-Messenger\outputs`, once execution has the necessary filesystem access. Keep the canonical source tree and existing branch/remotes. Commit locally; push only when requested.

### Phase A5 — Android path to video

**Outcome:** future video adds media tracks and UI, while retaining identity, session management and signaling ownership.

| ID | Platform / status | Dependencies | Future work | Acceptance / testing |
|---|---|---|---|---|
| AV01 | Android / Deferred | Voice release, BV01 | Add camera capture/rendering adapter, optional camera capability, permissions and foreground-service requirements. Existing external-camera attachment flow is not a live video source. | Voice-only calls never open/request the camera. Permission denial preserves audio. |
| AV02 | Android / Deferred | AV01 | Add local preview, remote video, camera switch, camera-off and explicit upgrade acceptance. | No automatic camera activation; surfaces attach/detach safely across navigation and orientation. |
| AV03 | Android / Deferred | AV02, WV02 | Add cross-platform video tests, quality adaptation, thermal/battery checks and audio/video synchronization. | Audio continues when video is declined, disabled or fails; all media stops on Offline. |

Build only the extension points now: media-kind capabilities, negotiation generation, separate track controls and a UI-independent media adapter. Do not implement unused camera machinery in the voice release.

## 2. Windows

### Phase W0 — Prove the native media and signaling integration

**Outcome:** select a viable Windows adapter and validate the risks specific to the current WinForms application.

Current source is .NET 9 WinForms. Voice messages use native `waveIn`/`waveOut`, with FIFO capture processing and explicit playback-buffer ownership fixes. These patterns inform cleanup tests but do not supply call transport, echo cancellation or jitter handling.

Windows TLS is a custom BouncyCastle-backed [SecureChannel.cs](/D:/LAN-Messenger/source/windows/SecureChannel.cs), not `SslStream`. Its concurrent read/write and cancellation behavior must be verified before using it for persistent bidirectional signaling.

Other integration points: [PeerEngine.cs](/D:/LAN-Messenger/source/windows/PeerEngine.cs), [Program.cs](/D:/LAN-Messenger/source/windows/Program.cs), [LanMessenger.csproj](/D:/LAN-Messenger/source/windows/LanMessenger.csproj), and [ChatWindowVoicePlayback.cs](/D:/LAN-Messenger/source/windows/ChatWindowVoicePlayback.cs).

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| W00 | Windows / Pending | B01 | Evaluate a maintained native WebRTC binding or a narrow C ABI bridge over a pinned upstream build. Prove capture/rendering, Opus, echo control, statistics, callbacks and shutdown. Test `SecureChannel` simultaneous read/write, heartbeat, timeout and cancellation. | Runs on real Windows audio hardware and interoperates in B02. Native architecture/loading and TLS concurrency are proven rather than inferred. |
| W00a | Windows / Pending | W00 | Define explicit native runtime architectures, dependency distribution, CRT requirements, DLL loading paths and ownership of native handles/callbacks. | Clean-machine loading works; missing/wrong-architecture dependencies disable calls with a useful error while messaging remains usable. |

Upstream documents native Windows support, but that does not establish a maintained .NET package or a ready-made WinForms integration. The paired feasibility gate therefore remains mandatory. [WebRTC native development](https://webrtc.github.io/webrtc-org/native-code/development/).

If the existing TLS wrapper cannot safely support this design, resolve that in B02/B03 with a tested call-specific adapter or revised signaling design. Do not silently change the protocol after Android implementation begins.

### Phase W1 — Windows call controller and authenticated signaling

**Outcome:** Windows implements the same frozen contract as Android with independent production-code verification.

Proposed components: `CallSession.cs`, `CallController.cs`, `CallProtocol.cs`, `CallSignaling.cs`, `ICallMedia.cs` and a focused `PeerEngine.Calls.cs` partial.

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| W01 | Windows / Pending | B03 | Implement the controller with serialized events, immutable snapshots and injectable clock/media. | Shared transition fixtures match Java results, including simultaneous dialing and terminal races. |
| W02 | Windows / Pending | W01 | Implement capability/open handling and persistent signaling. Refactor socket/TLS ownership explicitly around existing receive scopes and inbound semaphore. | Successful handoff survives `Receive` completion; failed handoff disposes once; semaphore capacity is neither leaked nor over-released. |
| W03 | Windows / Pending | W02 | Connect calls to Offline, disposal, trust revocation, certificate changes, forget/delete and data reset. | Native/signaling cleanup happens outside engine locks; no stale-generation work or deadlock under shutdown. |
| WT01 | Windows / Pending | W01–W03 | Add C# controller/codec tests, fake media and harness commands. Update explicit compile includes in `CsharpHarness.csproj`. | Headless tests use production logic without requiring WinForms or native WebRTC loading. Existing harness tests continue to run. |

**Exit:** Windows signaling passes locally and is ready for BT01 cross-platform checks.

### Phase W2 — Windows live audio and app-wide lifetime

**Outcome:** stable two-way audio independent of the visible chat window.

Proposed components: `WebRtcCallMedia.cs`, `CallAudioDevices.cs`, `AudioOwnership.cs`, and the native bridge only if selected in W00.

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| W04 | Windows / Pending | W00a, W03 | Implement media adapter, track mute, readiness, SDP/ICE and statistics. Root callbacks correctly and marshal events onto the call executor. | Real two-way audio; no native callback uses freed state; initialization/disposal failure returns a clear terminal reason. |
| W05 | Windows / Pending | W04 | Add app-wide audio ownership linked to voice recording and playback. Await recording finalization and release before call capture. | Drafts survive; call and voice-message capture never overlap; all terminal paths restore controls. |
| W06 | Windows / Pending | W04, W05 | Add input/output selection, default-device handling, unplug behavior and microphone privacy/device errors. Keep live switching only if validated; otherwise fail clearly. | Headset and speaker routes work; mute survives route changes; removed devices cannot leave a false Connected state. |
| W07 | Windows / Pending | W06 | Connect lifetime to the running application: hide-to-tray keeps calls; explicit Exit, suspend and unrecoverable network change end them. | Reopening the window shows the same call. Exit releases microphone/sockets; resume does not redial. |
| WT02 | Windows / Pending | W04–W07 | Add fault injection and repeated native lifecycle checks. Exercise shutdown during callbacks, device loss, double disposal and late callbacks. | No hangs, growing handle/thread counts, use-after-free or audio devices retained after termination. |

Calls must not feed audio through the WAV player, encrypted attachment store or recording-duration cap. Let the native call stack own continuous audio and its processing.

### Phase W3 — Windows call interface and tray integration

**Outcome:** a simple call flow works while the application is visible or hidden.

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| W08 | Windows / Pending | W07 | Add direct-chat Call action, incoming/outgoing views and an active-call panel/window. Show status, peer, duration, mute, devices and hangup. | One visible representation per call; state comes from controller snapshots; no network work blocks the UI. |
| W09 | Windows / Pending | W08 | Add incoming-call tray notification and return-to-call/hangup actions. Keep hiding the window separate from ending the call. | Incoming calls are reachable while hidden; closing presentation does not answer or duplicate calls; Exit ends them. |
| WT03 | Windows / Pending | W08, W09 | Extend `tests/WindowsUi` with fake-media call flows, keyboard navigation and accessibility checks. | Call/accept/decline/cancel/hangup and disabled states work; stale notifications and disposed-window callbacks are harmless. |

Existing voice-message UI tests remain regression coverage. Call UI changes must not alter existing seek/replay behavior.

### Phase W4 — Shared integration and release acceptance

**Outcome:** demonstrate interoperable calling through the actual protocol and native media stacks.

Shared work stays inside this Windows section to retain the requested two-section structure; every task below is explicitly labeled **Both**.

| ID | Platform / status | Dependencies | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| BT01 | Both / Pending | AT01, WT01 | Add `tests/calls/` contract fixtures and cross-process Java/C# signaling tests, using real TLS and fake media. | A↔A, W↔W and A↔W signaling pass in both initiation directions. Cover legacy peers, trust failures, Busy, glare, malformed frames, stale events and terminal races. |
| BT02 | Both / Pending | AT02, AT03, WT02, WT03, BT01 | Run physical audio and lifecycle matrix below using packaged-equivalent native dependencies. | Each required pairing and role has recorded evidence; synthesized harness media does not substitute for physical audio acceptance. |
| BT03 | Both / Pending | BT02 | Run network impairment, attachment coexistence, resource soak, privacy and Offline packet checks. Run relevant existing regression suites and then the full runner. | Acceptance targets met; unresolved regressions block release. Native sockets are included in Offline verification. |
| W10 | Windows / Pending | W00a, WT01–WT03, BT01–BT03 | Package native libraries and licenses; validate declared Windows architectures on a clean installation and existing-data upgrade. | Launch/call/exit works without developer tools; versioned artifacts include required dependencies and checksums. |
| B04 | Both / Pending | A12, W10 | Update Windows/Android status and `PROJECT_STATUS.md`; record artifacts, compatibility and exact evidence. Create local commits as authorized. | Separate code completion, automated verification and physical acceptance. No push or branch/remote reconfiguration. |

#### Required test matrix

| Test group | Required cases |
|---|---|
| Pairings | Android ↔ Android, Windows ↔ Windows, Android ↔ Windows; each side initiates and ends calls. |
| Normal lifecycle | Answer, decline, caller cancel, unanswered timeout, Busy, simultaneous dialing, repeated rapid call/end. |
| Security | Unverified peer, changed certificate, revoke/forget during ringing and connected states, spoofed call ID, stale notification and SDP fingerprint mismatch. |
| Android lifecycle | Home, lock/unlock, Activity recreation, notification denial, microphone denial/revocation, focus loss, service/process termination. |
| Windows lifecycle | Hide/reopen, tray actions, Exit, suspend/resume, privacy-disabled microphone, input/output unplug. |
| Networking | UDP blocked, Wi-Fi client isolation, wrong interface, Wi-Fi loss, peer crash, signaling failure, loss/jitter/reordering, Online/Offline races. |
| Audio ownership | Incoming while recording, accept during asynchronous draft finalization, delayed recording-permission result, active playback, call failure followed by voice-message use. |
| Coexistence | Text and large Normal/Fast attachments during calls; groups and existing queued sends continue correctly. |
| Compatibility | New-to-old and old-to-new messaging unchanged; unsupported calls fail within the capability deadline; app upgrades retain verification and history. |

#### Measurable acceptance targets

These are proposed release targets, not claims about the current application:

- Connected within five seconds after acceptance on an unloaded LAN, within the absolute setup deadline under adverse conditions.
- Clean-LAN one-way mouth-to-ear latency target below 250 ms, measured with a documented external audio method; RTT alone is not a substitute.
- Intelligible simultaneous speech using headsets and speakerphone, without sustained echo or feedback.
- A 30-minute call for each pairing without crash, growing delay or sustained memory/handle/thread growth.
- At least 100 automated setup/teardown cycles per implementation, plus physical device cycling.
- Under a recorded impairment profile—initially 2% random loss and 50 ms added jitter—speech remains usable without unbounded latency growth.
- Mute prevents intelligible local speech reaching the peer; packets may continue for transport control or silence handling.
- Offline and terminal cleanup release audio and native sockets; no call traffic continues once actual Offline is reported.
- Large attachments do not make hangup unresponsive or starve signaling. If coexistence fails, add the smallest measured scheduling adjustment and rerun file-transfer regressions.

Relevant regression coverage includes voice drafts, scheduler, WAV/PCM contracts, voice interoperability, Offline lifecycle, verification, transfers/resume, group compatibility and Windows UI. The Android service lifecycle test currently inspects source structure; update it deliberately when refactoring and add behavioral tests rather than treating string matching as device evidence.

Record device model, OS, native dependency revision, network, audio route and observed result. A documented historical test flake is not automatic permission to ignore a new failure.

**Release gate:** no unresolved security/lifecycle failure, no unsupported dependency-loading configuration presented as supported, and completed physical acceptance for all three pairings. Missing equipment leaves acceptance pending; compilation alone does not complete it.

### Phase W5 — Windows path to video and shared extension contract

**Outcome:** add video through negotiated tracks without redesigning calls.

| ID | Platform / status | Dependencies | Future work | Acceptance / testing |
|---|---|---|---|---|
| BV01 | Both / Deferred | Accepted voice release | Extend capabilities and negotiation for video, explicit upgrade request/accept/decline, simultaneous renegotiation and rollback. Select a common tested codec/profile. | Voice-only peers remain compatible; video rejection leaves the existing audio call intact. |
| WV01 | Windows / Deferred | BV01 | Add camera enumeration/capture and a renderer isolated from call control. Extend native bridge only as needed. | Camera resources initialize/release safely; privacy denial or removal does not terminate audio. |
| WV02 | Windows / Deferred | WV01 | Add preview, remote video, camera choice, camera-off and upgrade consent UI. | No camera capture before consent; UI resize/hide/restore does not corrupt native rendering. |
| BV02 | Both / Deferred | AV03, WV02 | Verify cross-platform video, bandwidth adaptation, audio priority, synchronization, camera loss and downgrade to audio. | All pairings preserve audio through failed/declined video upgrades and release camera/media on termination. |

The implementation order is:

1. **B00/B01 → A00 and W00/W00a → B02/B03:** prove and freeze the shared foundation.
2. **A1 and W1 → BT01:** implement and verify signaling with fake media.
3. **A2 and W2:** add native audio and lifecycle ownership.
4. **A3 and W3:** complete platform interfaces.
5. **BT02/BT03 → A12/W10 → B04:** accept, package and document voice calling.
6. **Video phases:** a later implementation request, building on the accepted voice foundation.
