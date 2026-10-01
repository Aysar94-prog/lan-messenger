# plan-v002 | version=2 | Cross-platform voice messages with secure, call-ready media foundations

## Goal

Add secure voice messages to Windows and Android by extending the existing encrypted, resumable attachment system. Establish reusable PCM capture and playback adapters for future voice and video calls without adding live-call signaling, real-time transport, or call permissions in this phase.

## Scope and product decisions

- Platform: **Both**.
- Support direct and group conversations.
- Composer flow: **Record → Stop → Preview/Delete → Send**.
- Maximum duration: **5 minutes**.
- Shared format: **16 kHz, 16-bit, mono PCM WAV**.
- Maximum standard WAV size: **9,600,044 bytes**: 9,600,000 bytes of PCM plus a 44-byte header.
- Voice notes remain ordinary LM4 attachment offers; no new message frame or capability negotiation is introduced.
- At queue time, after the final message ID is assigned, generate the signed filename `voice-<message-id>.lanvoice.wav`.
- Classification requires the application marker plus validated WAV content. It does not require the filename’s embedded ID to equal the message ID unless both platforms later introduce and enforce that rule identically.
- Existing clients treat voice notes as downloadable WAV attachments.
- **Send always imports the finalized WAV into the Normal encrypted attachment store. Voice notes never use Fast/original-file references.**
- A received voice note’s **Download, Retry, and Resume** actions use the internal automatic-fetch pipeline and its encrypted `LMATCS1` / `.sec.resume` storage. They never open the existing destination-picker manual-download flow.
- Saving a playable note outside the application is a distinct **Save/Export** action performed only after internal retrieval and validation.
- Eligible voice offers are automatically retrieved while Online, subject to validation, trust, retention, storage, and concurrency limits.
- Recording stops safely on chat exit, backgrounding, Offline transition, or shutdown and retains a preview draft when possible.
- Microphone audio is not transmitted until the user presses Send.

## Explicit non-goals

- Voice or video calls, ringing, accept/reject, call presence, NAT traversal, conferencing, or real-time media transport.
- Background microphone capture.
- Echo cancellation, noise suppression, transcription, waveform generation, editing, or playback-speed controls.
- New codecs or third-party codec dependencies.
- Treating arbitrary `.wav` files as voice messages.
- Altering verification, group signatures, attachment authorization, or retention rules.
- Shipping releases, changing versions, pushing commits, or executing this plan without explicit approval.

## Security and storage decisions

- Windows recording drafts reside only in an application-private data directory, never `%TEMP%`.
- Android recording drafts reside in app-private storage.
- Startup cleanup sweeps orphaned drafts, but must not remove a draft referenced by an in-progress or queued message.
- Sending follows this durability order:
  1. Finalize and validate the WAV draft.
  2. Import it completely into the Normal encrypted `LMATCS1` attachment store.
  3. Durably save the queued message row referencing that encrypted attachment.
  4. Only then securely delete the plaintext draft on a best-effort basis.
- A failure before both encrypted attachment and message row are durable retains a recoverable draft or rolls back the incomplete queue operation.
- Playback prefers bounded in-memory decryption and PCM streaming, capped by the 9,600,044-byte voice-note ceiling. It must not create a plaintext playback file.
- Known limitation: a recording draft is plaintext in app-private storage between capture and successful encrypted import. A crash can extend that window until the startup orphan sweep; best-effort deletion cannot guarantee erasure from filesystem snapshots or flash media.

## Future-call architecture boundary

```text
Microphone
    ↓
Platform PCM capture adapter
    ├── Voice-note recorder → WAV → Normal encrypted attachment store → LM4 fetch
    └── Future live encoder/transport (not implemented)

Encrypted received attachment
    ↓
Validated bounded PCM source
    ↓
Platform PCM playback adapter
    ├── Voice-message player
    └── Future live-call playout
```

Capture and playback adapters own device lifecycle and PCM frame movement. Attachment storage owns asynchronous voice messages. Future calls will add separate signaling, session, codec, jitter-buffer, and real-time transport layers.

## Task plan

| ID | Platform | Status | Dependencies | Task and notes | Acceptance criteria |
|---|---|---:|---|---|---|
| VM01 | Both | Planned | None | Freeze the shared contract: PCM WAV parameters, five-minute and 9,600,044-byte ceilings, filename marker, queue-time naming, classifier rules, interruption behavior, storage source type, auto-fetch policy, and mixed-version fallback. For Windows, select the Windows Multimedia `winmm.dll` `waveIn`/`waveOut` APIs through project-owned P/Invoke wrappers. No third-party audio or codec package is permitted. | Both implementations use identical limits and validation rules. The Windows API supports streaming PCM input/output, Play/Pause, and playback-position reporting. Ordinary WAV files remain ordinary attachments. |
| VM02 | Both | Planned | VM01 | Add a narrowly scoped marker classifier and streaming WAV parser. Validate RIFF/WAVE structure, PCM format, channels, sample rate, bit depth, data size, duration arithmetic, truncation, and bounded trailing data before exposing voice controls. Do not require the marker’s embedded ID to match the message ID. | Valid generated notes are recognized with correct duration. Spoofed, malformed, oversized, truncated, unsupported, or unmarked WAVs fall back safely to ordinary attachment UI without excessive allocation. |
| VM03 | Windows | Planned | VM01 | Implement a reusable Windows PCM capture adapter over `winmm.dll` `waveInOpen`/buffer callbacks. Stream frames into an app-private WAV draft, finalize its header safely, and make start, stop, failure, and disposal idempotent. Add startup sweeping for unreferenced orphan drafts. | Repeated recording releases all handles; output matches the contract; the cap stops capture automatically; `%TEMP%` contains no draft; failures create no sendable corrupt recording; referenced drafts survive cleanup. |
| VM04 | Android | Planned | VM01 | Implement a reusable `AudioRecord` PCM capture adapter and add `RECORD_AUDIO`. Request permission only on the first recording attempt, stream into an app-private WAV draft, and finalize safely. | Permission grant, denial, permanent denial, device failure, backgrounding, and destruction behave predictably; microphone resources are released; cap and format match Windows. |
| VM05 | Windows | Planned | VM03 | Add microphone, elapsed-time, Stop, Preview, Delete, and Send states. Prevent concurrent recorder actions, attachment selection, duplicate Send, or cross-chat draft association. Preview through bounded in-memory WAV parsing and the playback adapter. | Nothing is queued before Send. Delete removes an unqueued draft. Switching chats cannot misaddress it. Controls remain keyboard-accessible and expose textual states. |
| VM06 | Android | Planned | VM04 | Add equivalent recording-draft UI while preserving the existing action layout. Coordinate permission callbacks, activity lifecycle, rotation, and the service-owned engine. Preview with streaming/bounded PCM playback without plaintext playback files. | Only one recorder exists; lifecycle changes release it; the draft stays bound to its original conversation; cancel/delete removes unqueued data. |
| VM07 | Both | Planned | VM02, VM05, VM06 | Implement transactional voice-note queueing through the **Normal encrypted attachment store only**. Never create a Fast/original-reference source. Bind the filename after final message-ID assignment. Delete the draft only after the encrypted copy and message row are durably saved. Ensure cleanup preserves referenced drafts and encrypted queued sources. Reuse existing direct/group delivery, signatures, deduplication, relay, expiry, clear, and delete semantics. | A voice note sent Offline remains fetchable after sender restart and later reconnection. Group recipients can fetch it through relay after the original send. The signed queue-time filename is stable. No queued note depends on the plaintext draft or original-file reference. Clear/expiry removes internal complete and partial content under existing rules. |
| VM08 | Both | Planned | VM02, VM07 | Extend internal automatic retrieval to eligible voice offers. Voice-card Download/Retry/Resume invokes the same internal encrypted fetch pipeline used by automatic photos, preferring `FETCHSTREAM` and resuming `.sec.resume`, with legacy `FETCH` fallback. Apply marker, size, trust, membership, retention, Online, storage, and two-task concurrency checks. Do not invoke a destination picker. | An interrupted or failed voice fetch resumes internally and becomes playable inline. Oversized, untrusted, expired, Offline, or storage-failing offers do not auto-fetch. Completed content can serve authorized group relay and is removed by clear/expiry. |
| VM09 | Windows | Planned | VM02, VM07, VM08 | Implement the inline card and reusable `winmm.dll` `waveOut` playback adapter with Play/Pause, elapsed/total position, completion reset, and single-active-player behavior. Decrypt at most the voice ceiling into bounded memory, validate before output, and stream PCM buffers to `waveOut`; create no plaintext playback file. Add separate Save/Export for validated complete notes. | Notes from both platforms play, pause, resume, and report position correctly. Missing content shows Download; failed content shows Retry. Clear/expiry invalidates playback. Export is separate from retrieval and inline playback. |
| VM10 | Android | Planned | VM02, VM07, VM08 | Implement the inline card using `AudioTrack` with validated PCM streaming, position tracking, audio focus, route changes, pause/background handling, and resource release. Add a separate SAF Save/Export action for complete validated notes; SAF is not used as playback storage. | Cross-platform notes play inline; focus loss pauses; screen exit releases resources; incomplete/corrupt notes cannot play; Retry creates an internally stored playable note without requesting a destination URI. |
| VM11 | Both | Planned | VM05–VM10 | Add accessible controls and notifications. Identify incoming content as “New voice message” without exposing audio. Use textual/non-color recording and playback states and adequate touch targets. | Notifications open the correct conversation; all recording, download, retry, playback, delete, and export actions are understandable without color or waveform animation. |
| VM12 | Both | Planned | VM07–VM10 | Verify backward-tolerant persistence and mixed-version behavior. Add local metadata only if older readers can tolerate it. Confirm old peers see a normal WAV attachment and arbitrary audio files retain ordinary-file behavior. | Existing histories need no destructive migration; mixed-version direct/group messaging remains valid within current protocol constraints; upgraded clients do not misclassify normal WAV attachments. |
| VM13 | Both | Planned | VM01–VM12 | Update `PROTOCOL.md`, `PROJECT_STATUS.md`, `windows/STATUS.md`, and `android/STATUS.md` after implementation. Document the deliberate exception to the prior non-image consent policy: marked and validated voice offers up to 9,600,044 bytes may auto-fetch, share the existing global cap of two automatic tasks, and are rescheduled from pending offers after restart. Document internal Retry/Resume versus separate Export. | Documentation distinguishes implemented code, automated verification, Windows hardware acceptance, Android device acceptance, and cross-platform LAN acceptance. It does not claim voice/video calls are implemented. |

## Automated testing plan

| ID | Platform | Status | Dependencies | Test work and required coverage |
|---|---|---:|---|---|
| VT01 | Both | Planned | VM01, VM02 | Test WAV construction/classification, exact limits, duration arithmetic, malformed chunks, overflow, truncation, unsupported formats, forged markers, queue-time filename creation, nonmatching embedded IDs, and ordinary-WAV fallback. |
| VT02 | Windows | Planned | VM03, VM05, VM07 | Test capture and composer state machines with fake PCM input: start/stop/cancel, cap, device loss, duplicate actions, chat switch, shutdown, app-private orphan sweeping, referenced-draft preservation, and explicit Send. Include: send while Offline, verify Normal encrypted import and durable queue, restart the sender, reconnect, and let a receiver fetch successfully after the plaintext draft is gone. |
| VT03 | Android | Planned | VM04, VM06, VM07 | Test permission states, lifecycle shutdown, rotation, initialization failure, short reads, duration cap, app-private cleanup, referenced-draft preservation, transactional encrypted import, and explicit Send. |
| VT04 | Both | Planned | VM07, VM08 | Extend interoperability tests for Windows→Android and Android→Windows, direct/group delivery, relay, offline queueing, restart recovery, auto-fetch, internal Retry, interrupted `FETCHSTREAM`/legacy `FETCH` resume, integrity failure, and deduplication. Required cases: (1) send while Offline, restart sender, reconnect, and fetch successfully; (2) force auto-download failure, press Retry, play inline, verify the complete copy can serve group relay, then verify clear and expiry delete complete/partial internal storage. Assert that Retry never opens a destination picker. |
| VT05 | Both | Planned | VM09, VM10 | Test player adapters and UI states: Play/Pause/resume, position, completion, errors, missing content, one-player-at-a-time, background/chat switch, clear during playback, bounded memory, and disposal. Verify no plaintext playback file is created. |
| VT06 | Both | Planned | VM07–VM10 | Regression-test ordinary files, manual destination-picker downloads, inline photos, Fast transfers, group signatures/relay/expiry, clear/delete, Offline lifecycle, Android upload budgets, pagination, and notifications. Run the complete `tests/run.ps1` suite. |
| VT07 | Both | Planned | VT01–VT06 | Add resource and adversarial tests for maximum recordings, memory bounds, rapid actions, unsafe paths, malformed WAV input, auto-fetch concurrency, low storage, forced termination, orphan cleanup, and retention changes during fetch/playback. |

## Manual acceptance plan

### Windows

- Record, preview, delete, send, play, pause, resume, and export using built-in and external microphones where available.
- Verify timer accuracy, automatic stop, device removal, handle release, playback position, keyboard navigation, screen-reader labels, and high-DPI layout.
- Confirm drafts use app-private storage and startup removes only unreferenced orphans.
- Send while Offline, restart, reconnect, and verify another device fetches the note.
- Fail an automatic fetch, press Retry, and verify inline playback without a destination picker.

### Android physical devices

- Test Android 8 and a current supported Android version where available.
- Verify permission grant, denial, permanent denial, Settings recovery, rotation, lock, background/foreground, focus loss, routes, and low storage.
- Confirm no recorder or recording foreground service remains after leaving the flow.
- Verify Windows-recorded playback, internal auto-fetch/Retry/Resume, and separate SAF Export.

### Two-device LAN interoperability

- Test direct and group notes in both directions.
- Interrupt sender and receiver through Offline transitions, restart, Wi-Fi loss, and process termination.
- Verify group relay from an internally downloaded complete note.
- Verify clear and seven-day expiry remove voice-note complete/partial storage and prevent further serving.
- Confirm an older client treats the marked note as an ordinary WAV attachment.
- Confirm ordinary manual downloads still use the destination picker and voice-card Retry does not.

## Completion gates

1. VM01–VM13 meet their acceptance criteria.
2. VT01–VT07 and the unchanged full regression suite pass.
3. Offline-send/restart/fetch passes on both sender platforms.
4. Failed-auto-fetch/Retry/play/relay/clear-or-expiry passes cross-platform.
5. Physical Windows and Android results are recorded separately; unavailable hardware remains pending.
6. Two-device LAN tests pass in both directions for direct and group messages.
7. No new LM4 frame, Fast voice source, plaintext playback file, or destination-picker dependency is introduced.
8. Status and protocol documentation accurately reflect the auto-fetch consent exception and remaining call roadmap.
9. Implementation, builds, commits, releases, and pushes occur only after the applicable explicit approval.

## Later roadmap

1. **Voice messages:** asynchronous encrypted PCM WAV attachments, implemented by this plan.
2. **Voice-call control:** capability discovery, authenticated invite/ring/accept/reject/end, session IDs, timeouts, and call UI.
3. **Live voice:** low-latency codec selection, real-time transport, jitter buffering, packet-loss handling, echo cancellation, routing, and Android foreground-service behavior.
4. **Video foundation:** camera permissions/lifecycle, preview, codec negotiation, rendering, and bandwidth adaptation.
5. **Video calls:** synchronized audio/video sessions, recovery, device switching, thermal/battery controls, and full security/interoperability acceptance.
