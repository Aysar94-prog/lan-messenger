# plan-v004 | version=4 | Voice messages with staged voice-call and video-call foundations

## Goal

Add secure voice messages to Windows and Android by extending the existing encrypted, resumable attachment system. Establish transport-independent audio boundaries, crash-recoverable drafts, and accessible playback so later voice-call and video-call phases can reuse the foundations without introducing live-call networking in this phase.

## Scope and product decisions

- Platform: **Both**.
- Support direct and group conversations.
- Composer flow: **Record -> Stop -> Preview/Delete -> Send**.
- Maximum recording duration: **5 minutes**.
- Shared stored format: **16 kHz, signed 16-bit, mono PCM WAV**.
- Maximum standard WAV size: **9,600,044 bytes**: 9,600,000 PCM bytes plus a 44-byte header.
- Voice messages remain ordinary LM4 attachment offers; this phase adds no call signaling or live-media protocol.
- After the final message ID is assigned, generate the signed filename `voice-<message-id>.lanvoice.wav`.
- Classification requires the application marker plus validated WAV content.
- Existing clients treat voice messages as downloadable WAV attachments.
- Send imports the finalized WAV into the Normal encrypted attachment store. Voice messages never use Fast/original-file references.
- A voice card's Download, Retry, and Resume actions use the internal encrypted attachment-fetch pipeline, not the destination picker.
- Saving a playable message outside the application is a separate Save/Export action.
- Eligible voice offers are automatically retrieved while Online, subject to trust, validation, retention, storage, and concurrency limits.
- Recording stops safely on conversation exit, backgrounding, Offline transition, or shutdown. A successfully finalized draft becomes recoverable.
- Microphone audio is never transmitted until the user presses Send.
- Downloaded voice messages provide Play/Pause, elapsed and remaining time, and seeking through pointer/touch, keyboard, and accessibility actions.
- Capture and playback pass through an internal PCM frame contract that can be reused by future live calls.

## Explicit non-goals for this release

- Voice calls, video calls, ringing, accept/reject, call presence, session negotiation, NAT traversal, conferencing, or real-time media transport.
- Sending the internal PCM frames over the network.
- Background microphone capture.
- Echo cancellation, noise suppression, transcription, waveform generation, editing, or playback-speed controls.
- New codecs or third-party codec dependencies.
- Seeking incomplete, corrupt, unvalidated, or still-downloading content.
- Treating arbitrary `.wav` files as voice messages.
- Altering verification, group signatures, attachment authorization, or retention rules.
- Implementing deferred R2 work during the voice-message release.
- Building, committing, releasing, or pushing until explicitly authorized.

## Shared media contracts

### Stored voice-message contract

A sendable voice message is a marked, finalized, and validated RIFF/WAVE file containing mono signed 16-bit PCM at 16 kHz. Parsers must validate chunk bounds, checked arithmetic, truncation, data alignment, format, duration, and the five-minute ceiling before exposing voice controls.

### Internal PCM frame contract

Define a transport-independent frame type with equivalent behavior on both platforms:

| Field | Rule |
|---|---|
| Sequence number | Unsigned monotonic logical sequence within one stream; discontinuities are explicit |
| Timestamp | Monotonic media timestamp relative to stream start and independent of wall-clock changes |
| Format metadata | Sample rate, channel count, sample representation, bit depth, and frame duration |
| Payload | Complete interleaved PCM samples; no partial sample crosses a frame boundary |
| Target cadence | **20 ms**: 320 mono samples and 640 bytes at 16 kHz/16-bit |
| Stream state | Explicit end-of-stream and device-restart/discontinuity indications |
| Ownership | Immutable frame data or explicitly bounded ownership transfer; device callback buffers do not escape unsafely |

Platform capture adapters normalize arbitrary callback sizes into this contract. Recording writes ordered frame payloads to WAV. Playback converts validated WAV data into the same frame shape before passing it to platform output adapters.

Buffering and backpressure must be bounded. Overflow, missing frames, timestamp regression, unsupported format changes, or device restart produce a deterministic stop or recovery outcome and never create a silently valid-but-corrupt message.

The frame boundary deliberately excludes networking, encryption, packetization, codecs, peer clock synchronization, and jitter buffering. Those are deferred to R2.

### Seek conversion contract

For validated mono 16-bit PCM:

1. Clamp the requested position to `[0, validatedDuration]`.
2. Convert with checked integer or rational arithmetic.
3. Map the time to the validated WAV `data` chunk.
4. Align downward to the complete two-byte PCM sample boundary.
5. Clamp to `[dataStart, dataStart + dataLength]`.
6. Flush or invalidate queued output, reset playback sequence and timestamp state, and resume from the aligned position.

The UI reports the effective aligned position. Seeking to the end produces the completed state. Seek operations are serialized with Play, Pause, Clear, expiry, route changes, and disposal.

## Crash-recoverable draft design

Maintain a bounded durable draft registry in application-private storage. Each entry contains:

- Opaque draft ID.
- Owning conversation ID and direct/group type.
- Creation time.
- Validated application-private storage identifier, never an arbitrary external path.
- Recording and finalization state.
- Expected format and bounded size metadata.
- Optional recovery or error status.
- Queue/import association only while completing transactional Send.

Required transition order:

1. Create and durably register draft ownership before accepting microphone frames.
2. Record normalized PCM into application-private storage.
3. On Stop or forced safe stop, finalize and validate the WAV.
4. Mark it finalized and recoverable only after the file and header are durable.
5. On Send, import the finalized WAV into the Normal encrypted store and durably save the queued message.
6. Remove the registry entry and best-effort delete plaintext only after the encrypted source and queued message are durable.

Startup reconciliation rules:

- A valid finalized, unqueued draft is offered as **Restore** or **Delete** only in its owning conversation.
- Restore revalidates the file and recreates preview state without transmitting it.
- Delete removes the registry entry and best-effort deletes its file.
- Unfinished, missing, invalid-path, oversized, corrupt, or mismatched records are never offered as playable drafts.
- Invalid entries follow a deterministic quarantine/deletion policy and may produce a non-sensitive recovery notice.
- A record associated with a durable queued message is never restored or deleted until reconciliation confirms the encrypted source is durable.
- Unregistered files and stale invalid records are cleanup candidates only after safe-reference validation.
- The registry has an explicit count and age policy. Valid recoverable work is never silently evicted; the user must resolve it when the limit is reached.

Known limitation: plaintext exists in application-private storage until encrypted import succeeds. Crash recovery can extend its lifetime, and best-effort deletion cannot guarantee erasure from snapshots or flash media.

## Architecture boundary

```text
Microphone
    |
Platform capture adapter
    |
20 ms transport-independent PCM frames
    +--> Voice-message recorder --> finalized WAV
    |                             --> Normal encrypted store
    |                             --> existing LM4 attachment fetch
    |
    +--> Future live encoder and media transport [R2-02, not implemented]

Encrypted received attachment
    |
Validated WAV data source
    |
20 ms transport-independent PCM frames
    |
Platform playback adapter
    +--> Voice-message player
    |
    +--> Future jitter buffer and call playout [R2-02, not implemented]

Future authenticated call signaling [R2-01]
    |
Future live-audio session [R2-02]
    |
Future synchronized audio/video session [R2-03]
```

Device adapters own microphone and speaker lifecycle. The frame layer owns ordering, cadence metadata, bounded buffering, discontinuities, and backpressure. Attachment storage owns asynchronous voice messages. R2 adds signaling and live-media systems outside this release.

## Voice-message task plan

All tasks are **Planned** until the user explicitly authorizes execution.

| ID | Platform | Status | Dependencies | Task and notes | Acceptance criteria |
|---|---|---:|---|---|---|
| VM01 | Both | Planned | None | Freeze the stored voice contract: WAV parameters, duration/size ceilings, marker, naming, interruption behavior, Normal-only source, auto-fetch policy, and mixed-version fallback. Use Windows `winmm.dll` `waveIn`/`waveOut` through project-owned wrappers and Android `AudioRecord`/`AudioTrack`; add no codec dependency. | Both platforms enforce identical limits and validation rules. Existing clients can still handle the payload as an ordinary WAV attachment. |
| VM02 | Both | Planned | VM01 | Define the internal PCM frame and stream-state contract: sequence, timestamp, format, 20 ms cadence, bounded payload ownership, end/discontinuity markers, queue bounds, backpressure, and device-restart behavior. Keep it independent of WAV, LM4, attachments, and signaling. | Equivalent contract tests pass on both platforms; the abstraction imports no message, socket, attachment, or call-signaling dependency. |
| VM03 | Both | Planned | VM01 | Add the marked-voice classifier and streaming WAV parser. Validate RIFF/WAVE structure, PCM format, chunks, checked size/duration arithmetic, sample alignment, trailing data, and truncation. | Valid messages report the correct data range and duration. Spoofed, malformed, oversized, truncated, unsupported, or unmarked WAVs fall back safely to ordinary attachment UI. |
| VM04 | Both | Planned | VM01, VM02 | Implement frame normalization and WAV frame source/sink boundaries. Normalize arbitrary device callback sizes, preserve complete samples, and define bounded short-tail handling. | Fragmented and oversized synthetic callbacks produce ordered PCM without loss, duplication, partial samples, or unbounded buffering. |
| VM05 | Both | Planned | VM01, VM03 | Implement the durable draft registry and atomic reconciliation rules, including bounded count, retention, invalid-entry cleanup, queued-send reconciliation, and Restore/Delete discovery. | Every simulated crash produces a valid recoverable draft, a durable queued message, or a diagnosed invalid entry—never silent deletion of valid finalized work. |
| VM06 | Windows | Planned | VM02, VM04, VM05 | Implement reusable Windows capture over `waveIn`, feeding normalized frames to the WAV sink. Register ownership before capture, finalize and validate safely, and make stop/device-loss/disposal idempotent. | Handles are released; output conforms to the contracts; duration limits stop capture; `%TEMP%` is unused; valid forced-stop drafts remain recoverable. |
| VM07 | Android | Planned | VM02, VM04, VM05 | Implement reusable `AudioRecord` capture and add `RECORD_AUDIO`. Request permission on the first recording attempt and coordinate lifecycle/device errors with registry state. | Permission and lifecycle outcomes are deterministic; resources are released; limits match Windows; valid drafts survive process death. |
| VM08 | Windows | Planned | VM05, VM06 | Add Record, elapsed time, Stop, Preview, Delete, Send, and startup Restore/Delete UI. Bind drafts to their owning conversation and serialize recorder actions, chat changes, attachment selection, and duplicate Send. | Nothing queues before Send. Recovery occurs only in the owner conversation, and conversation switching cannot misaddress a draft. |
| VM09 | Android | Planned | VM05, VM07 | Add equivalent recording and recovery UI while preserving the existing action layout. Coordinate permission callbacks, rotation, Activity lifecycle, and the service-owned engine without background capture. | Only one recorder exists. Rotation or restart cannot rebind a draft to another conversation. |
| VM10 | Both | Planned | VM03, VM05, VM08, VM09 | Implement transactional Send through the Normal encrypted store. Assign the filename after message-ID creation, import fully, save the queued row durably, and then remove registry/plaintext state. | Offline Send survives restart and later fetch. No queued message depends on a draft or Fast reference; crashes do not create duplicate sends. |
| VM11 | Both | Planned | VM03, VM10 | Extend automatic retrieval for eligible voice offers, preferring resumable `FETCHSTREAM` with legacy `FETCH` fallback. Apply marker, size, trust, membership, retention, Online, storage, and existing concurrency limits. | Interrupted retrieval resumes internally. Retry never opens a destination picker. Ineligible offers are not auto-fetched. |
| VM12 | Both | Planned | VM02, VM03, VM04 | Implement validated PCM playback sources and seek conversion. Produce frames from the WAV data range, align targets, clamp bounds, flush stale output, and restart frame state. | Boundary and adversarial seeks never read outside the data chunk, split samples, replay stale audio, overflow arithmetic, or report impossible time. |
| VM13 | Windows | Planned | VM11, VM12 | Implement inline playback over `waveOut`: Play/Pause, timing, seek slider, completion reset, one active player, bounded decrypted memory, separate Save/Export, keyboard seeking, and accessible slider semantics. | Cross-platform messages play and seek accurately. Keyboard controls work, state is announced, and no plaintext playback file is created. |
| VM14 | Android | Planned | VM11, VM12 | Implement inline playback over `AudioTrack`: Play/Pause, timing, seek slider, one active player, audio focus/routes, bounded PCM streaming, accessibility actions, and separate SAF Export. | Touch and accessibility seeking share the validated converter. Focus loss pauses, exit releases resources, and invalid content cannot play. |
| VM15 | Both | Planned | VM08, VM09, VM13, VM14 | Complete accessibility and notification behavior using textual/non-color states, adequate targets, descriptive labels, and equivalent keyboard/accessibility actions. | A keyboard or assistive-technology user can perform every supported action. Notifications say “New voice message” without exposing audio. |
| VM16 | Both | Planned | VM10-VM15 | Verify backward-tolerant persistence and mixed-version behavior. Add only local metadata older readers tolerate. | Existing histories need no destructive migration; old clients see ordinary WAV attachments; arbitrary WAV files are not misclassified. |
| VM17 | Both | Planned | VM01-VM16 | After implementation and verification, update `PROTOCOL.md`, `PROJECT_STATUS.md`, `windows/STATUS.md`, and `android/STATUS.md`. | Records distinguish implementation, automated verification, physical-device acceptance, and interoperability; they do not claim calls exist. |

## R2 deferred call roadmap

R2 items are deliberately explicit so the voice-message architecture opens the door to the final video-call goal. They remain **Deferred** and must receive their own detailed planning and explicit execution approval after the voice-message completion gates pass.

| ID | Platform | Status | Dependencies | Future task and boundary | Acceptance criteria for entering the next phase |
|---|---|---:|---|---|---|
| R2-01 | Both | Deferred | VM02, VM16, VM17 | **Authenticated call control.** Plan capability discovery, supported media/codec declarations, caller/callee identity binding, invite/ring/accept/reject/cancel/end states, collision handling, session IDs, replay protection, timeouts, missed-call metadata, group exclusion or separate group-call design, and mixed-version fallback. Reuse established verification identities but do not reuse attachment messages as live signaling without a security review. | A reviewed signaling state machine and threat model cover duplicates, reordering, simultaneous calls, disconnects, stale/replayed frames, Offline transitions, app restart, incompatible peers, and explicit user consent before media starts. |
| R2-02 | Both | Deferred | R2-01, VM02, VM04, VM06, VM07, VM12-VM14 | **Live voice call.** Connect the PCM boundary to negotiated codecs and authenticated, encrypted real-time transport. Add jitter buffering, packet-loss handling, clock drift control, echo cancellation, routing/device switching, Android foreground-service behavior, reconnect policy, bandwidth limits, and call UI. Keep attachment storage and live media as separate consumers of the frame contract. | Bidirectional calls pass security, latency, packet-loss, route-change, reconnect, lifecycle, accessibility, mixed-device, and two-device LAN acceptance without regressing voice messages. |
| R2-03 | Both | Deferred | R2-02 | **Video call.** Add camera permission/lifecycle, preview, a bounded video-frame contract, codec and resolution negotiation, encrypted video transport, rendering, A/V synchronization, camera switching, adaptive bitrate, thermal/battery controls, audio-only fallback, and privacy indicators. Reuse R2-01 sessions and R2-02 audio rather than creating a parallel call system. | Windows-to-Android and Android-to-Windows video calls pass consent, privacy, synchronization, adaptation, interruption, reconnect, resource-release, accessibility, and physical-device acceptance, with safe fallback to live voice. |

### R2 design constraints established now

- VM02’s PCM abstraction must remain independent of attachment and signaling types.
- Capture and playback adapters must have explicit lifecycle and ownership boundaries so live calls can use them later.
- Voice-message buffering rules must not be mistaken for live-call jitter-buffer policy.
- No R2 wire frames, codec packages, permissions beyond microphone recording, background call behavior, or call UI are implemented speculatively.
- R2-01 must be planned and reviewed before R2-02; R2-02 must pass before R2-03 begins.
- Any R2 shared wire change requires review of both implementations and cross-platform interoperability testing.

## Automated testing plan

| ID | Platform | Status | Dependencies | Test work and required coverage |
|---|---|---:|---|---|
| VT01 | Both | Planned | VM01, VM03 | Test construction/classification, exact limits, checked duration arithmetic, malformed chunks, overflow, truncation, unsupported formats, forged markers, filename creation, nonmatching IDs, and ordinary-WAV fallback. |
| VT02 | Both | Planned | VM02, VM04 | Add synthetic frame tests for cadence, fragmented callbacks, ordering, sequence gaps, timestamp regression, clock drift, bounded backpressure, overflow policy, short final input, format rejection, device restart, and clean resumption. Assert bit-exact PCM where no discontinuity occurs. |
| VT03 | Both | Planned | VM05 | Add crash-point tests for registry creation, recording, finalization, Restore, Delete, encrypted import, queued-row persistence, and cleanup, including invalid paths, missing/corrupt files, limits, stale entries, duplicates, and reconciliation. |
| VT04 | Windows | Planned | VM06, VM08, VM10 | Test capture/composer state machines with fake input: limit, device loss, duplicate actions, conversation switch, shutdown, recovery, explicit Send, plaintext cleanup, Offline send, restart, reconnect, and fetch. |
| VT05 | Android | Planned | VM07, VM09, VM10 | Test permission states, lifecycle shutdown, rotation/process death, initialization failure, short reads, device restart, duration limit, recovery, transactional import, and explicit Send. |
| VT06 | Both | Planned | VM10, VM11 | Extend interoperability tests for direct/group delivery in both directions, relay, offline queueing, restart recovery, auto-fetch, Retry, interrupted resume, integrity failure, and deduplication. |
| VT07 | Both | Planned | VM12-VM14 | Test Play/Pause/resume, one-player policy, timing, completion, and seeks at start/end, fractional positions, odd offsets, rapid repetition, pause-seek-resume, clear/expiry, and maximum duration. |
| VT08 | Both | Planned | VM13-VM15 | Test keyboard and accessibility actions, slider announcements, timing labels, focus order, unavailable-content behavior, notifications, and non-color state communication. |
| VT09 | Both | Planned | VM10-VM14 | Test bounded memory, low storage, termination, malformed input, fetch concurrency, retention changes, device restarts, and absence of plaintext playback files. |
| VT10 | Both | Planned | VM10-VM16 | Regression-test ordinary attachments, destination downloads, inline photos, Fast transfers, group relay/expiry, clear/delete, Offline lifecycle, Android upload budgets, pagination, and notifications. Run the complete `tests/run.ps1` suite. |
| VT11 | Both | Planned | VM02, VM04, VM12 | Add architectural boundary checks proving the reusable PCM layer has no LM4, attachment, UI, or future call-signaling dependency and that platform adapters can be driven by test frame sources/sinks. |

## Manual acceptance plan

### Windows

- Record, stop, preview, delete, send, play, pause, seek, resume, and export with available input/output devices.
- Force-close after finalization but before Send; restart and confirm Restore/Delete appears only in the owning conversation.
- Verify timer accuracy, automatic stop, device removal, handle release, keyboard operation, screen-reader announcements, and high-DPI layout.
- Send while Offline, restart, reconnect, and verify another device retrieves the message.
- Fail automatic retrieval, select Retry, and verify inline playback without a destination picker.

### Android physical devices

- Test Android 8 and a current supported Android version where available.
- Verify permission grant, denial, permanent denial, Settings recovery, rotation, process death, lock, foreground/background transitions, audio focus, route changes, and low storage.
- Force-stop after finalization and verify the draft can be restored or deleted without starting background microphone capture.
- Exercise seeking by touch and accessibility actions.
- Verify Windows-recorded playback, internal auto-fetch/Retry/Resume, and separate SAF Export.

### Two-device LAN interoperability

- Test direct and group voice messages in both directions.
- Interrupt sender and receiver with Offline transitions, restart, Wi-Fi loss, and process termination.
- Confirm recovered drafts remain local until explicit Send.
- Verify group relay from a completely retrieved internal voice message.
- Seek messages produced by the opposite platform near the start, middle, last sample, and end.
- Verify clear and seven-day expiry remove complete/partial voice storage and stop playback.
- Confirm an older client treats the marked message as an ordinary WAV attachment.
- Confirm ordinary manual downloads still use the destination picker while voice Retry does not.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Plaintext recovery data persists after a crash | Application-private storage, bounded retention, explicit Restore/Delete, encrypted import before cleanup, and documented best-effort deletion limits |
| Registry and file disagree after termination | Ordered durable transitions, startup reconciliation, revalidation, and crash-point testing |
| Platform callbacks do not match 20 ms | Bounded frame assembly; raw callback buffers never become the shared contract |
| Slow consumers create unbounded memory | Fixed queue limits with deterministic backpressure and overflow behavior |
| Seeking reads invalid data or replays stale buffers | Validated ranges, aligned checked offsets, output invalidation, and serialized player state |
| Automatic fetch changes download-consent expectations | Restrict it to marked, validated, trusted, retained, size-bounded content under existing storage and concurrency controls |
| Call readiness causes speculative scope growth | Keep R2-01 through R2-03 deferred and prohibit call signaling, live transport, video, and speculative permissions in VM work |
| A voice-message design choice blocks low-latency calls | Enforce the transport-independent frame and adapter boundaries with VT02 and VT11 |
| Future video creates a second incompatible session model | Require R2-03 to reuse R2-01 signaling and R2-02 audio/session ownership |

## Completion gates

1. VM01-VM17 meet their acceptance criteria.
2. VT01-VT11 and the unchanged full regression suite pass.
3. Valid finalized unqueued drafts offer Restore/Delete on both platforms and are never silently removed.
4. Crash tests show no duplicate Send and no queued message dependent on plaintext draft storage.
5. Synthetic PCM tests pass ordering, drift, bounded backpressure, and restart scenarios.
6. Seek conversion remains within validated data and aligns every target to a complete sample.
7. Keyboard and accessibility seeking are equivalent to pointer/touch seeking.
8. Offline Send, restart, reconnect, and retrieval pass from both sender platforms.
9. Auto-fetch failure, Retry, playback, relay, clear, and expiry pass cross-platform.
10. Physical Windows, Android, and two-device results are recorded separately; unavailable hardware remains explicitly pending.
11. No new LM4 call frame, Fast voice source, plaintext playback file, call signaling, live transport, or video behavior is introduced.
12. Status and protocol documentation describe voice-message behavior, recovery, seeking, PCM boundaries, and the deferred R2 roadmap accurately.
13. Architecture checks demonstrate that R2-01, R2-02, and R2-03 can build on the new boundaries without requiring voice-message storage or UI types in live media.
14. Implementation, builds, commits, releases, pushes, and all R2 execution occur only after applicable explicit approval.

## Delivery sequence

1. Complete VM01-VM05 and VT01-VT03 to freeze shared contracts and recovery behavior.
2. Implement platform capture and composer work in VM06-VM09 with VT04-VT05.
3. Complete transactional delivery and retrieval in VM10-VM11 with VT06.
4. Complete playback, accessibility, and UI behavior in VM12-VM15 with VT07-VT09.
5. Complete compatibility, regression, architecture checks, documentation, and physical acceptance through VM16-VM17 and VT10-VT11.
6. Close the voice-message release before separately planning and authorizing R2-01.
7. Progress sequentially from R2-01 authenticated call control to R2-02 live voice and finally R2-03 video calls.
