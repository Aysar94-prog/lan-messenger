# plan-v001 | version=1 | Phased voice calls for Android and Windows

Build **one-to-one, full-duplex LAN voice calls**, using the existing verified peer identity for signaling and **WebRTC for live audio**. Keep call management independent of audio/video tracks so video can be added later.

This is a planning deliverable only. No project files were modified, and no builds or tests were executed. Every implementation task below is **Pending** until execution is explicitly requested; future video tasks are **Deferred**.

The working tree contains Android Java sources targeting API 34/minimum API 26, and a .NET 9 WinForms Windows application. Voice messages already provide recording, playback, encrypted storage, and useful lifecycle patterns. Live calls need additional signaling, continuous media transport, echo cancellation, device routing, and app-wide session ownership.

Two important findings inform this plan:

- Both `PeerEngine` implementations use verified TLS connections, LM4 discovery, bounded 16 KiB line reads, and predominantly short request/response transactions. Group `CAPS=2` already has a specific meaning; calling must introduce a separate capability.
- Some status entries are stale. For example, `tests/voice_interop.py` exists and is invoked by `tests/run.ps1`, although older status paragraphs say interoperability has not started. This inspection confirms the test exists, not that it passed against the current tree.

## 1. Android

### Phase A0 — Shared foundation and Android feasibility

**Outcome:** establish a viable media stack on both platforms before implementing the complete Android calling feature.

The shared tasks in this phase are labeled **Both** and apply equally to Windows.

#### Initial product scope

| Area | First voice-call release |
|---|---|
| Participants | One verified direct contact; one outgoing, incoming, or active call per device |
| Network | Reachable LAN peers, using the existing IPv4 networking scope; no internet service required |
| Controls | Call, accept, decline, cancel, hang up, microphone mute, audio route |
| Feedback | Calling, ringing, connecting, connected, busy, declined, unanswered, failed, ended |
| Android background behavior | An established call continues through navigation and screen lock while its service remains alive |
| Incoming Android calls | Supported while the app’s online service is running and reachable; notification opens the incoming-call UI |
| Windows background behavior | Established calls continue when the main window is hidden to the tray |
| Offline | Ends the call and closes all call networking; returning Online does not resume it |
| Existing messaging | Text and attachments remain available during a call |
| Voice-message interaction | A shared audio-ownership policy prevents simultaneous call and voice-message recording/playback |
| Outside first release | Group calls, call waiting, recording, durable call history, internet relay, automatic reconnection, video UI |

**Architecture choice:** WebRTC peer connections with Opus audio and encrypted media; existing TLS identity verification authenticates the signaling that exchanges session descriptions and media fingerprints. WebRTC supports a separate application-defined signaling transport and provides native platform implementations. The exact dependency distribution still needs the feasibility gate below. [WebRTC peer connections](https://webrtc.org/getting-started/peer-connections), [native development](https://webrtc.github.io/webrtc-org/native-code/development/).

Do not convert the existing 16 kHz WAV attachment path into a streaming call protocol. Keep `VoiceMessages`, draft storage, attachment scheduling, and voice-message wire behavior intact.

| Task | Platform / status | Dependencies | Implementation notes | Acceptance and testing |
|---|---|---|---|---|
| B01 — Establish implementation baseline | Both / Pending | Execution requested | Record source revision, existing changes, current voice-message tests, platform versions, and available test devices. Reconcile relevant status contradictions using evidence. | Existing voice-message and Offline checks have recorded results; unavailable device checks remain explicitly pending. |
| A01 — Android native-media spike | Android / Pending | B01 | Prove loading a pinned WebRTC Java/JNI dependency, constructing and disposing a peer connection, capturing and rendering audio, and exposing statistics. Determine supported phone/emulator ABIs. | Real Android execution succeeds; repeated open/close releases the microphone; required native libraries load on each proposed ABI. |
| B02 — Paired feasibility gate | Both / Pending | A01, W01 | Connect the Android and Windows prototypes on an isolated LAN without STUN/TURN servers. Exchange Opus audio both ways; inspect encryption, routing, echo control, and teardown. Select exact dependency revisions, provenance, licenses, update process, and build recipes. | Two-way audio works without internet access; no plaintext media; a reproducible dependency manifest exists for both platforms. |
| B03 — Freeze shared calling contract | Both / Pending | B02 | Define capability negotiation, framing, states, errors, timers, identity binding, resource limits, and canonical test fixtures. Specify reserved extension behavior for future video. | Both implementations can be developed from one contract; valid and invalid fixtures cover each message and transition. |

**Dependency decision:** prefer a compatible, pinned upstream WebRTC lineage on both platforms. Android can use its Java/JNI API; Windows may require a maintained binding or a small C ABI bridge. Neither package availability nor compatibility with the current builds is assumed proven. The upstream Android implementation explicitly includes both Java and JNI components. [Android WebRTC source](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/README).

If B02 fails, revise the dependency/build approach before proceeding. Do not silently replace WebRTC with custom plaintext UDP or WAV streaming.

#### Shared contract to freeze in B03

**Discovery and compatibility**

- Preserve the existing five-field `HELLO` and group `CAPS=2`.
- Add an authenticated `CALLCAPS` query describing calling protocol version, audio support, and supported media profile.
- Query capabilities freshly for each outgoing call. A cached result may assist display but cannot authorize setup.
- Distinguish unsupported protocol, unavailable peer, unverified contact, and transport failure.
- An older peer may close the connection or give no recognizable response. Stop within a bounded timeout and leave messaging unaffected.
- Accept only verified direct peers. No group identifiers or self-calls.

**Signaling transport**

- Reuse the existing TCP endpoint and mutual TLS handshake.
- Introduce a negotiated `CALLOPEN` transaction that transfers socket ownership to a dedicated call-signaling session.
- Preserve existing 16 KiB limits for ordinary LM4 lines. Following successful call negotiation, use separate length-prefixed call framing.
- Proposed limits: 64 KiB maximum frame, 48 KiB session description, 128 candidates per negotiation, and bounded receive/send queues. Reject oversized lengths before allocating.
- Specify the exact encoding, byte order, schema, required fields, and malformed-input handling in the shared contract.
- Use one serialized writer and one reader per call channel; validate concurrent read/write behavior of Windows `SecureChannel` during the spike.
- Release ordinary inbound worker capacity after handoff. Preserve engine ownership for cancellation and Offline cleanup.
- Reserve bounded call admission capacity so attachment downloads cannot indefinitely starve call setup or hangup.

**Messages and session binding**

- Define `INVITE`, `RINGING`, `ACCEPT`, `DECLINE`, `BUSY`, `CANCEL`, `OFFER`, `ANSWER`, `ICE`, `MEDIA_READY`, `HANGUP`, `ERROR`, and heartbeat messages.
- Bind every call to a random call ID, verified peer identity, current network generation, negotiated protocol version, and the authenticated channel.
- Match SDP media fingerprints through this trusted signaling path; reject fingerprint mismatch and insecure media negotiation.
- The caller creates the initial offer after acceptance. Queue early candidates only within strict bounds until the corresponding remote description is installed.
- Duplicate control messages must not create duplicate sessions. Completed sessions cannot be revived by late messages or callbacks.
- Clear all live sessions on process restart. Calls are never persisted in the outgoing-message queue.

**State and timing**

- Model `Idle → Outgoing/IncomingRinging → Connecting → Connected → Ending → Idle`, with explicit terminal reasons.
- Proposed timers: capability/open attempt 5 seconds; ringing 30 seconds; media setup 15 seconds; heartbeat every 5 seconds with failure after 15 seconds without valid signaling activity.
- Use monotonic clocks; define when each deadline begins.
- Begin the duration counter only when local media is ready, transport is established, and remote readiness is confirmed.
- No microphone capture before explicit user action and remote acceptance. Permission prompts must not leave the peer ringing indefinitely.
- A second unrelated call receives Busy.
- For simultaneous calls between the same peers, use a deterministic ordering of caller IDs: keep one invitation, cancel the other, and require acceptance of the surviving incoming call.
- Hangup, decline, cancellation, expiry, and failure converge on one idempotent cleanup path.
- A disconnected signaling channel ends the call in this first release. No automatic redial or session resurrection.

**LAN and media policy**

- Use host candidates on the selected LAN interface; configure no external STUN/TURN servers.
- Filter local and remote candidates to the allowed LAN scope; reject public, multicast, unspecified, and inappropriate loopback destinations. Define multi-interface handling explicitly.
- Use WebRTC’s media encryption, jitter handling, congestion control, loss concealment, and echo processing.
- Blocked UDP or Wi-Fi client isolation produces an actionable connection failure. There is no unencrypted fallback.
- Go Offline must stop native media sockets as well as existing engine sockets. Report actual Offline only after teardown completes.
- Treat live media separately from file-transfer scheduling and Android’s daily attachment-upload policy. Measure coexistence before changing any file policy.
- Logs contain state changes, timings, and aggregate metrics; no captured audio, keys, or full SDP/ICE credentials.

### Phase A1 — Call state and authenticated signaling

**Outcome:** Android can establish and terminate correctly authenticated call sessions using fake media.

Existing integration points:

- [PeerEngine.java](D:/LAN-Messenger/source/android/src/net/lanmsg/chat/PeerEngine.java)
- [MessengerService.java](D:/LAN-Messenger/source/android/src/net/lanmsg/chat/MessengerService.java)
- `SecureIdentity.java`
- `tests/PeerHarness.java` and `tests/run.ps1`

Proposed new components: `CallSession`, `CallController`, `CallProtocol`, `CallSignaling`, and a platform-neutral `CallMedia` interface.

| Task | Platform / status | Dependencies | Implementation notes | Acceptance and testing |
|---|---|---|---|---|
| A02 — Pure Java call controller | Android / Pending | B03 | Implement the state machine on one serialized executor, immutable UI snapshots, injectable clock, and fake media/signaling interfaces. Keep Android framework types outside this core. | Deterministic tests cover all states, double accept/hangup, cancel versus accept, expiry, simultaneous dialing, Busy, and stale callbacks. |
| A03 — TLS call integration | Android / Pending | A02 | Add capability handling and socket handoff after existing trust checks. Track every call resource under the network generation; bound admission and queues. | Java-to-Java and Java-to-C# fake-media tests cover authentication, legacy peers, partial frames, oversize input, channel closure, and duplicate messages. |
| A04 — Security and engine lifecycle hooks | Android / Pending | A03 | End calls on local/remote revocation, certificate changes, contact deletion, delete-app-data, service shutdown, and Offline. Avoid blocking native cleanup while holding engine locks. | Race tests prove no post-Offline packets, no call revival after Online, no deadlock, and no effect on unrelated conversation data. |

**Phase exit:** Android signaling works through production TLS code, with deterministic state tests and complete resource cleanup, before adding device audio.

### Phase A2 — Real audio and service ownership

**Outcome:** a call survives Activity changes and uses Android audio correctly.

The current `MainActivity` owns voice-message recorder/player fields and stops them in `onPause()`/`onDestroy()`. Calls must instead belong to `MessengerService`; the Activity subscribes to snapshots and sends commands.

| Task | Platform / status | Dependencies | Implementation notes | Acceptance and testing |
|---|---|---|---|---|
| A05 — WebRTC media adapter | Android / Pending | A01, A03, A04 | Implement peer-connection creation, SDP/ICE exchange, Opus track, mute, readiness, metrics, and deterministic disposal. Let the selected WebRTC audio device module own call capture/rendering. | Physical two-way audio works; mute suppresses outgoing speech; failed device initialization ends cleanly; no WAV files or draft rows are created. |
| A06 — Foreground call lifecycle | Android / Pending | A05 | Extend the existing service with call ownership and microphone foreground-service support. Dispatch notification actions explicitly rather than treating every non-Offline action as Online. Preserve `START_NOT_STICKY` and existing Offline behavior. | Call survives Activity recreation, navigation, Home, and screen lock; hangup removes call notification and microphone service type; ordinary online messaging remains running. |
| A07 — Audio routing and ownership | Android / Pending | A06 | Add communication audio mode/focus, earpiece/speaker selection, wired-headset behavior, route restoration, and a process-wide audio arbiter. End the first-release call on focus loss rather than implementing hold. | Speaker/earpiece and wired route tests pass; focus interruption stops capture; voice-message controls recover after every termination path. |

Android requires the microphone service type, its foreground-service permission, and runtime microphone permission. Microphone access is also subject to while-in-use restrictions. The first implementation should open a visible Activity from the incoming-call notification and start capture only through an eligible user acceptance flow. Do not assume an existing background messaging service can freely activate the microphone. [Foreground service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types).

**Detailed lifecycle decisions**

- Declare microphone hardware optional so devices without it can still use messaging.
- Add `FOREGROUND_SERVICE_MICROPHONE`; activate the microphone type only during an accepted call.
- Validate the existing connected-device type against actual messaging behavior during A06; make any required type adjustment explicitly.
- Keep incoming ringing free of microphone capture.
- Use notification actions tied to the current call ID; reject stale Accept/Decline/Hangup actions.
- On API 26–30 use supported notification presentation; newer call-style presentation must be API guarded.
- Notification denial leaves foreground calling available but must not imply reliable background ringing.
- Do not request full-screen intent privileges or add Telecom integration merely to complete the first release.
- Acquire a narrowly scoped wake lock only if device tests establish it is necessary; every exit path must release it.
- Bluetooth support is a separate compatibility gate: use communication-device APIs where available and test supported older routes. Do not claim support based only on successful device enumeration.
- Android focus handling must respect current platform restrictions, including foreground/top-app requirements when targeting API 35 or later. [Audio focus documentation](https://developer.android.com/media/optimize/audio-focus).

**Voice-message interaction policy**

- Starting or accepting a call stops playback and finalizes any active voice-message recording as an unsent draft before transferring audio ownership.
- If finalization or microphone release fails, do not open a competing capture session.
- Disable new voice-message recording and playback during a call; preserve saved drafts.
- Incoming ringing alone does not discard a recording.
- After call cleanup, restore voice-message controls without automatically resuming playback.

### Phase A3 — Android calling interface

**Outcome:** complete basic calling from a direct conversation.

| Task | Platform / status | Dependencies | Implementation notes | Acceptance and testing |
|---|---|---|---|---|
| A08 — Call entry and in-call screen | Android / Pending | A06, A07 | Add a labeled Call action to direct-chat headers; incoming accept/decline UI; outgoing cancel; connected duration, mute, route, and hangup. Keep a return-to-call affordance when navigating elsewhere. | No call action for groups; unverified/offline/unsupported states explain why calling is unavailable; TalkBack names and touch targets are correct. |
| A09 — Automated Android verification | Android / Pending | A08, W03 | Add shared-fixture core tests, service/notification action tests, and device instrumentation where available. Update explicit Java source lists in the harness and runner without pulling Android/JNI classes into headless tests. | Core, signaling, lifecycle, and UI checks pass; device-only checks are reported separately from JVM tests. |

**UI acceptance details**

- Back returns to messaging without ending an established call.
- Incoming call UI is independent of the currently selected conversation and people-list filters.
- Permission denial returns to a usable state and informs the caller.
- Cancel before acceptance never flashes a connected screen.
- A late Accept cannot reopen an ended call.
- Ending a call restores the previous conversation and composer draft.
- Offline remains an explicit immediate termination action.

### Phase A4 — Android device acceptance and packaging

**Outcome:** verified Android behavior on real phones and a reproducible distributable.

The current [build.ps1](D:/LAN-Messenger/source/android/build.ps1) compiles plain Java and packages `classes.dex`; it does not currently integrate a WebRTC AAR/JNI dependency. It also contains an output filename older than the manifest version. These are concrete build tasks.

| Task | Platform / status | Dependencies | Implementation notes | Acceptance and testing |
|---|---|---|---|---|
| A10 — Physical Android acceptance | Android / Pending | A09, B04 | Exercise Android↔Android and Android↔Windows on real LAN hardware, including locked screen, background service, permissions, headset removal, network loss, and another app taking audio focus. | Shared acceptance matrix passes; device models, OS versions, routes, and remaining limitations are recorded. |
| A11 — Package and upgrade verification | Android / Pending | A10, B05 | Integrate pinned Java/native libraries into the build. Include required AAR resources/manifest entries if present, all DEX outputs, ABI libraries, and native alignment/page-size requirements. Fix native-tool exit handling and derive artifact naming from the chosen version. | Clean build succeeds; APK contents and signatures verify; installation over the current app preserves identity, verification, history, and drafts; packaged calls pass smoke tests. |

Preserve the existing signing key. Do not generate a replacement for an upgrade. Keep signing material private and exclude `.private` from archives.

Write build artifacts under `D:\LAN-Messenger\outputs`. Update `android/STATUS.md` with separate implementation, automated verification, and physical acceptance results.

### Phase A5 — Future Android video extension

**Deferred; not part of first voice-call delivery.**

| Task | Platform / status | Dependencies | Implementation notes | Acceptance and testing |
|---|---|---|---|---|
| AV01 — Camera tracks and rendering | Android / Deferred | Accepted voice release; video execution requested | Add camera permission, capture adapter, preview/remote rendering, camera switching, rotation, and appropriate service lifecycle. Reuse call identity and signaling transport. | Camera stays off until consent; audio continues when video is disabled or camera initialization fails. |
| AV02 — Video upgrade UX and lifecycle | Android / Deferred | AV01, WV01; shared video contract | Add mutually negotiated upgrade/downgrade, background camera policy, bandwidth adaptation, and audio-only fallback. | Android↔Android and Android↔Windows video work; an audio-only peer declines the upgrade without losing its call. |

The voice implementation should expose media tracks and negotiation revisions rather than hard-coding “one microphone stream” into call identity. It should advertise **audio only** until video is implemented and verified.

## 2. Windows

### Phase W0 — Native-media feasibility

**Outcome:** prove that the chosen media engine can be deployed and controlled safely from the current WinForms application.

Relevant starting points:

- [LanMessenger.csproj](D:/LAN-Messenger/source/windows/LanMessenger.csproj): .NET 9 WinForms, currently with BouncyCastle as its package dependency.
- [SecureChannel.cs](D:/LAN-Messenger/source/windows/SecureChannel.cs): custom TLS stream whose concurrency and teardown behavior require verification.
- `VoiceRecorder.cs` / `VoicePlayer.cs`: existing `waveIn`/`waveOut` voice-message adapters.
- [Program.cs](D:/LAN-Messenger/source/windows/Program.cs): app lifetime, tray behavior, engine ownership, and voice-message interaction.

| Task | Platform / status | Dependencies | Implementation notes | Acceptance and testing |
|---|---|---|---|---|
| W01 — Windows native-media spike | Windows / Pending | B01 | Evaluate a maintained compatible binding; otherwise prove a minimal C ABI bridge to pinned native WebRTC. Cover capture/render devices, SDP/ICE, mute, statistics, callbacks, and destruction. | Standalone prototype exchanges audio with A01; a clean machine loads all native dependencies; supported process architectures are explicit. |
| W02 — Freeze bridge and distribution | Windows / Pending | B02, B03 | Specify ownership of native handles, callback threading, string/buffer lifetime, error mapping, build tools, runtime dependencies, and license notices. | Repeated create/dispose survives late callbacks; missing/wrong-architecture DLLs produce a clear error without crashing messaging. |

Prefer x64 for the initial Windows native target, subject to B01 confirming the deployed machines. Do not accidentally drop an existing required architecture.

Existing voice-message buffer-ordering and slot-reuse fixes are useful regression cases. The first call implementation should let the selected media engine own its audio path instead of adapting those file-oriented buffers into a second live transport.

### Phase W1 — Call core and authenticated signaling

**Outcome:** Windows implements exactly the shared contract defined in B03.

Proposed new components: `CallSession.cs`, `CallController.cs`, `CallProtocol.cs`, `CallSignaling.cs`, `ICallMedia`, and `WebRtcCallMedia`.

| Task | Platform / status | Dependencies | Implementation notes | Acceptance and testing |
|---|---|---|---|---|
| W03 — C# call controller | Windows / Pending | B03 | Implement the same states, timers, collision rule, errors, and cleanup semantics as Android. Use a serialized executor and fake clock/media. | Run the same canonical transition fixtures as Java; outcomes match for every race and rejection case. |
| W04 — TLS channel integration | Windows / Pending | W03, W02 | Add `CALLCAPS`/`CALLOPEN` to `PeerEngine`; transfer socket ownership out of the current request-scoped disposal path. Keep call traffic out of attachment delivery queues. | Cross-language signaling passes; idle calls are not killed by existing short LM4 read deadlines; slow or malformed call sessions remain bounded. |
| W05 — Engine lifecycle integration | Windows / Pending | W04 | Hook Offline, shutdown, revoke, key change, forget, contact deletion, and delete-app-data into call termination. Dispose native resources outside engine locks. | Offline closes media and signaling; rapid Online/Offline cannot restore stale calls; messaging, group capability checks, and transfers retain their behavior. |

Preserve the existing twelve-connection inbound limit for ordinary traffic while designing bounded call handoff/admission. Long calls must not permanently consume general request workers.

### Phase W2 — Real audio, devices, and app ownership

**Outcome:** stable duplex audio owned by the running application.

| Task | Platform / status | Dependencies | Implementation notes | Acceptance and testing |
|---|---|---|---|---|
| W06 — WebRTC audio adapter | Windows / Pending | W02, W05 | Connect the native engine to call signaling; implement Opus, readiness, mute, statistics, and deterministic release. Marshal callbacks onto the controller executor. | Windows↔Windows and Windows↔Android calls carry simultaneous speech; no capture before acceptance; teardown releases all handles and callbacks. |
| W07 — Device selection and audio ownership | Windows / Pending | W06 | Add input/output selection with system communication defaults, clear missing-device/privacy errors, and hotplug handling. Apply the same voice-message arbitration policy as Android. | Headset plug/unplug and default-device changes behave predictably; no competing recorder/player; failure never causes silent indefinite capture. |

**Device behavior**

- Reuse the last selected device if available; otherwise identify the fallback visibly.
- On headset removal, stop or mute capture while reevaluating routes. Avoid unexpectedly moving a private call onto speakers.
- If a usable replacement route cannot be established within a bounded interval, end with a clear reason.
- Restore previous audio ownership after the call.
- Measure echo cancellation with actual laptop speakers and microphones; successful loopback alone is insufficient.

### Phase W3 — WinForms and tray calling interface

**Outcome:** calling works independently of selected chat and main-window visibility.

| Task | Platform / status | Dependencies | Implementation notes | Acceptance and testing |
|---|---|---|---|---|
| W08 — Call UI and tray integration | Windows / Pending | W07 | Add a Call action to direct-chat headers, an incoming prompt, and a persistent call panel/window with peer, state, duration, mute, devices, and hangup. Add tray return-to-call and hangup actions. | Switching chats and hiding the main window preserve the call; Exit terminates it; keyboard navigation, accessibility labels, and high-DPI layout pass. |
| W09 — Automated Windows verification | Windows / Pending | W08, A03 | Extend C# harness source includes, shared call fixtures, native adapter tests, and `tests/WindowsUi`. Inject fake media for deterministic UI tests; keep actual-device tests separately labeled. | Core, signaling, UI, callback-lifetime, and disposal checks pass; existing voice-message and Offline tests remain green. |

Current `FormClosing` hides the application to the tray, whereas `FormClosed` disposes the engine. Preserve this distinction:

- Closing the main window keeps an active call running and discoverable through the tray.
- Closing a dedicated call window must have a documented behavior; use minimize/hide unless the user presses Hang up.
- Choosing Exit ends the call and shuts down the application.
- Incoming calls while hidden show a usable prompt without repeatedly stealing focus.
- Windows lock leaves an established call running; suspend ends it, and resume does not redial.

### Phase W4 — Shared interoperability and quality gate

**Outcome:** Android and Windows pass one compatibility matrix before either platform is described as call-ready.

| Task | Platform / status | Dependencies | Implementation notes | Acceptance and testing |
|---|---|---|---|---|
| B04 — Cross-platform call verification | Both / Pending | A09, W09 | Add a shared call harness and fixtures, plus a real native-media integration runner. Run Android↔Windows with both caller roles, Android↔Android, and Windows↔Windows. | All required combinations pass; fake-media signaling results are distinguished from real media and physical-device results. |
| W10 — Physical Windows acceptance | Windows / Pending | B04 | Test real PC microphones, speakers, wired/USB headsets, tray operation, lock/suspend, firewall behavior, and Android interoperability. | Shared matrix passes on documented devices; acoustic echo and simultaneous speech are acceptable. |
| B05 — Regression and release-readiness gate | Both / Pending | A10, W10 | Run the complete regression suite and targeted call checks. Review dependency notices, legacy compatibility, packet captures, resource metrics, and remaining defects. | No unresolved release-blocking call issue; no unexplained regression; device acceptance evidence exists for both platforms. |

#### Required shared test matrix

| Area | Required cases |
|---|---|
| Basic calls | Both caller directions; accept, decline, cancel, unanswered, Busy, hangup from either end |
| Races | Simultaneous dialing; accept versus cancel; hangup during setup; expiry versus acceptance; repeated UI actions; delayed native callbacks |
| Trust | Unverified peer; changed key; forged peer identity; revoke/forget during ringing and connected state; media fingerprint mismatch |
| Compatibility | Old peer without calling; incompatible call version; no common codec; fresh capability check following downgrade |
| Protocol robustness | Partial/truncated frames; oversized lengths; invalid fields; duplicate messages; candidate flood; stale call ID; bounded queue exhaustion |
| Network | Internet disconnected; UDP blocked; AP client isolation; LAN loss; interface change; peer crash; Offline and rapid Online retry |
| Media | Simultaneous speech, mute/unmute, silence, speaker echo, device disappearance, denied microphone access |
| Coexistence | Text/group messages, ordinary/Fast file transfer, voice-message draft finalization, playback exclusion, unchanged saved drafts |
| Lifecycle | Android screen lock and Activity recreation; notification actions; Windows tray and Exit; process kill; suspend; no automatic resurrection |
| Privacy | Encrypted media capture; no plaintext recording artifacts; no external ICE-server traffic; no sensitive signaling in logs |

#### Proposed measurable acceptance targets

These are release targets to validate, not claims about current performance:

- On a healthy local Wi-Fi network, incoming alert within **3 seconds**, excluding user permission interaction.
- Audio ready within **5 seconds after acceptance** under the same conditions.
- Measured end-to-end audio latency **p95 ≤250 ms** on the documented clean-LAN reference setup. Use an actual latency measurement method, not network RTT alone.
- With **3% random loss and up to 50 ms injected jitter**, speech remains usable, buffers remain bounded, and latency does not continually accumulate.
- A **60-minute call** per platform pairing has no growing thread/socket/native-handle count and no unexplained memory growth after warm-up.
- **100 automated setup/teardown cycles** and **20 physical call cycles** leave no microphone capture, ringing, or call notification behind.
- Local hangup stops capture promptly, targeted within **1 second**; peer loss is detected within the specified heartbeat deadline.
- Under large-file transfer load, calling remains intelligible and controls remain responsive. If existing transfers prevent that, add a narrowly scoped, tested coexistence adjustment before release.

Record apparatus, OS versions, network conditions, metrics, and subjective listening results. A headless Java engine test cannot establish Android background microphone behavior or acoustic quality.

Known historical group stress-test failures must be investigated if they recur; do not automatically classify a new failure as the same environmental issue.

### Phase W5 — Windows packaging and final handoff

**Outcome:** both packaged applications are verified, with accurate platform status.

| Task | Platform / status | Dependencies | Implementation notes | Acceptance and testing |
|---|---|---|---|---|
| W11 — Package and upgrade verification | Windows / Pending | B05 | Package the application with the correct native DLLs, runtime requirements, dependency notices, and selected architecture. Test firewall prompts on the intended network profile without disabling the firewall. | Clean-machine launch and call smoke tests pass; upgrade preserves identity/history/drafts; no machine-specific dependency path is required. |
| B06 — Final status and handoff | Both / Pending | A11, W11 | Update both platform status files and `PROJECT_STATUS.md`, including protocol compatibility, build recipes, versions, checksums, device results, and limitations. | Code-complete, automated-pass, and physical-acceptance states are separate; no stale “not implemented/not tested” claims remain for accepted work. |

Use the existing shared branch and source tree. Keep outputs under `D:\LAN-Messenger\outputs`; create local commits when executing the work, and push only when requested.

**Recommended execution sequence**

1. B01, then Android A01 and Windows W01 feasibility work.
2. B02 paired media demonstration, then B03 shared contract.
3. Android A02–A04 and Windows W02–W05 core/signaling work.
4. Android A05–A09 and Windows W06–W09 media, lifecycle, and UI work.
5. B04 interoperability, then A10/W10 physical acceptance.
6. B05 readiness review, A11/W11 packaging, and B06 final handoff.

This sequence exposes cross-platform media and packaging risks early rather than discovering them after completing one platform.

### Phase W6 — Future Windows video extension

**Deferred; not part of first voice-call delivery.**

| Task | Platform / status | Dependencies | Implementation notes | Acceptance and testing |
|---|---|---|---|---|
| WV01 — Camera and video rendering | Windows / Deferred | Accepted voice release; video execution requested | Add camera enumeration/capture and a WinForms-compatible renderer behind the existing media interface. Define frame-buffer ownership and GPU/CPU fallback. | Camera release, resizing, DPI changes, device loss, and renderer disposal pass without disturbing audio. |
| WV02 — Shared video negotiation | Both / Deferred | WV01, AV01 | Extend capabilities and versioned negotiation for video tracks, codec intersection, explicit upgrade consent, simultaneous upgrades, downgrade, and bandwidth adaptation. | Voice continues if video is rejected or fails; old audio-only peers remain usable; all platform pairings pass. |

Video readiness is established in the voice release by the track-based media interface, versioned negotiation, and app-owned call lifecycle. Camera permissions, renderers, video codecs, and upgrade UI remain deferred until that work is requested.
