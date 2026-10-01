# plan-v003 | version=3 | Crash-resilient, accessible, call-ready voice messages

## Goal

Add secure voice messages to Windows and Android by extending the existing encrypted, resumable attachment system. Establish a transport-independent PCM frame contract, crash-recoverable recording drafts, and accessible playback seeking so later voice-call and video-call phases can reuse the audio boundary without introducing live-call signaling or networking now.

## Scope and product decisions

- Platform: **Both**.
- Support direct and group conversations.
- Composer flow: **Record -> Stop -> Preview/Delete -> Send**.
- Maximum recording duration: **5 minutes**.
- Shared stored format: **16 kHz, signed 16-bit, mono PCM WAV**.
- Maximum standard WAV size: **9,600,044 bytes**: 9,600,000 PCM bytes plus a 44-byte header.
- Voice notes remain ordinary LM4 attachment offers; this phase adds no message frame, call signaling, or media-network protocol.
- At queue time, after the final message ID is assigned, generate the signed filename `voice-<message-id>.lanvoice.wav`.
- Classification requires the application marker plus validated WAV content. The embedded filename ID need not equal the message ID unless both platforms later adopt that rule together.
- Existing clients treat voice notes as downloadable WAV attachments.
- **Send always imports the finalized WAV into the Normal encrypted attachment store. Voice notes never use Fast/original-file references.**
- A voice card's **Download, Retry, and Resume** actions use the internal automatic-fetch pipeline and encrypted `LMATCS1` / `.sec.resume` storage, never the destination-picker flow.
- Saving a playable note outside the application is a distinct **Save/Export** action after internal retrieval and validation.
- Eligible voice offers are automatically retrieved while Online, subject to trust, validation, retention, storage, and concurrency limits.
- Recording stops safely on chat exit, backgrounding, Offline transition, or shutdown. A successfully finalized draft becomes recoverable.
- Microphone audio is not transmitted until the user presses Send.
- Downloaded voice messages provide an optional seek slider, elapsed time, and remaining time. Seeking is available through pointer/touch, keyboard, and accessibility actions.
- Voice-message capture and playback pass through a shared internal PCM frame contract designed for reuse by future live calls.

## Explicit non-goals

- Voice or video calls, ringing, accept/reject, call presence, session negotiation, NAT traversal, conferencing, or real-time media transport.
- Network transmission of PCM frames introduced by the internal frame contract.
- Background microphone capture.
- Echo cancellation, noise suppression, transcription, waveform generation, editing, or playback-speed controls.
- New codecs or third-party codec dependencies.
- Seeking incomplete, corrupt, unvalidated, or still-downloading content.
- Treating arbitrary `.wav` files as voice messages.
- Altering verification, group signatures, attachment authorization, or retention rules.
- Shipping releases, changing versions, implementing tasks, building artifacts, committing, or pushing without explicit approval.

## Shared media contracts

### Stored voice-note contract

A sendable note is a marked, fully finalized and validated RIFF/WAVE file containing exactly supported mono 16-bit PCM at 16 kHz. Parsers must validate chunk bounds, arithmetic, truncation, data alignment, duration, and the five-minute ceiling before exposing voice controls.

### Internal PCM frame contract

Define a transport-independent frame type shared in meaning across both implementations:

| Field | Rule |
|---|---|
| Sequence number | Unsigned monotonic logical sequence within one stream; discontinuities are reported rather than silently reordered |
| Timestamp | Monotonic media timestamp relative to stream start, independent of wall-clock changes |
| Format metadata | Sample rate, channel count, sample representation/bit depth, and frame duration |
| Payload | Complete interleaved PCM samples only; no partial sample may cross a frame boundary |
| Target cadence | **20 ms**, equivalent to 320 mono samples and 640 payload bytes at 16 kHz/16-bit |
| End/discontinuity state | Explicit end-of-stream and device-restart/discontinuity indications; neither is inferred from an arbitrary short callback |
| Ownership | Immutable frame data or an explicitly bounded ownership transfer; device callback buffers must not escape unsafely |

Capture adapters normalize platform callback sizes into this contract. Voice-note recording consumes ordered frames and writes their PCM payload to WAV. Playback parses WAV into the same frame shape before submitting it to platform output adapters.

The contract must define bounded buffering and backpressure behavior. Voice-note file production may briefly absorb scheduling jitter within a fixed queue, but it must never grow an unbounded queue. Overflow, missing frames, timestamp regression, format changes, or device restart must produce a deterministic stop/recovery result and must not create a silently valid-but-corrupt note.

This boundary intentionally excludes packetization, codecs, encryption, jitter buffers, clocks shared between peers, and networking. Those belong to later live-call phases.

### Seek conversion contract

For validated mono 16-bit PCM:

1. Clamp the requested position to `[0, validatedDuration]`.
2. Convert using checked integer/rational arithmetic rather than unchecked floating-point multiplication.
3. Map to the corresponding offset within the validated WAV `data` chunk.
4. Align downward to the complete PCM sample boundary of **2 bytes**.
5. Clamp the result to `[dataStart, dataStart + dataLength]`.
6. Flush or invalidate already queued output frames, reset playback sequence/timestamp state at the selected sample, and resume only from the validated aligned offset.

The UI displays elapsed and remaining time from the effective aligned position, not merely the unvalidated requested value. End seeking resets to the completed state. Seek operations are serialized with Play, Pause, Clear, expiry, route changes, and disposal.

## Crash-recoverable draft design

Maintain a small durable draft registry in application-private storage. Each entry contains:

- Opaque draft ID.
- Owning conversation ID and whether it is direct or group.
- Creation time.
- Safe app-private storage reference represented as a controlled relative name or validated internal identifier, never an arbitrary external path.
- Recording/finalization state.
- Expected format and bounded size metadata.
- Optional recovery/error status.
- Queue/import association only while completing transactional Send.

Registry and draft transitions must be crash-safe:

1. Create and durably register the draft ownership before accepting microphone frames.
2. Record PCM through the frame boundary into app-private storage.
3. On Stop or forced safe stop, finalize and validate the WAV.
4. Durably mark the entry **finalized and recoverable** only after the file and header are complete.
5. On Send, import the finalized WAV into the Normal encrypted store and durably save the queued message.
6. Remove the registry entry and best-effort delete the plaintext only after both encrypted source and queued message row are durable.

At startup:

- A valid finalized, unqueued registry entry is **not an orphan**. Offer **Restore** or **Delete** in its owning conversation.
- Restore revalidates the file and recreates the preview state without transmitting it.
- Delete removes the registry entry and best-effort deletes the draft.
- An unfinished, missing, path-invalid, oversized, corrupt, or mismatched entry is not offered as playable content. Quarantine or delete it according to a deterministic cleanup policy and show a non-sensitive recovery notice when appropriate.
- Entries already tied to a durable queued message must never be restored as drafts or deleted before queue reconciliation proves the encrypted source is durable.
- Unregistered files and stale invalid records are cleanup candidates only after safe-reference validation.
- Define a small bounded registry and an age policy for recoverable drafts; when the limit is reached, require the user to resolve an existing draft rather than silently evicting valid work.

Known limitation: the recording draft remains plaintext in app-private storage until encrypted import succeeds. Crash recovery intentionally can extend this lifetime. Best-effort deletion cannot guarantee erasure from filesystem snapshots or flash media.

## Future-call architecture boundary

```text
Microphone
    |
Platform capture adapter
    |
20 ms transport-independent PCM frames
    +--> Voice-note recorder --> finalized WAV --> Normal encrypted store --> LM4 fetch
    +--> Future encoder / live-media transport (not implemented)

Encrypted received attachment
    |
Validated WAV/data-chunk source
    |
20 ms transport-independent PCM frames
    |
Platform playback adapter
    +--> Voice-message player with aligned seeking
    +--> Future live-call playout and jitter buffer (not implemented)
```

Device adapters own microphone/speaker lifecycle. The frame layer owns ordering, cadence metadata, bounded buffering, discontinuities, and backpressure. Attachment storage owns asynchronous voice messages. Future calls add signaling, session security, codecs, real-time transport, jitter management, and echo control outside this phase.

## Task plan

All tasks are **Planned** until the user explicitly authorizes execution.

| ID | Platform | Status | Dependencies | Task and notes | Acceptance criteria |
|---|---|---:|---|---|---|
| VM01 | Both | Planned | None | Freeze the stored voice contract: PCM WAV parameters, duration/size ceilings, marker, queue-time naming, interruption behavior, Normal-only source, auto-fetch policy, and mixed-version fallback. Select Windows `winmm.dll` `waveIn`/`waveOut` through project-owned P/Invoke wrappers and Android `AudioRecord`/`AudioTrack`; add no third-party codec package. | Both implementations use identical limits and validation rules. Existing clients can still handle the payload as an ordinary WAV attachment. |
| VM02 | Both | Planned | VM01 | Define the internal PCM frame type and stream-state contract: sequence, monotonic timestamp, format metadata, 20 ms target cadence, immutable/bounded payload, end/discontinuity markers, queue bounds, backpressure, and device-restart rules. Keep it independent of WAV, LM4, and platform APIs. | Equivalent contract tests pass on both platforms; the type imports no message, socket, attachment, or call-signaling dependency. |
| VM03 | Both | Planned | VM01 | Add the marked-voice classifier and streaming WAV parser. Validate RIFF/WAVE structure, PCM format, chunks, checked size/duration arithmetic, sample alignment, trailing data, and truncation before exposing voice controls. | Valid notes report the correct data range and duration. Spoofed, malformed, oversized, truncated, unsupported, or unmarked WAVs safely fall back to ordinary attachment UI. |
| VM04 | Both | Planned | VM01, VM02 | Implement shared frame normalization helpers and WAV frame source/sink boundaries. Normalize arbitrary device callback sizes into 20 ms frames, preserve final complete samples, and define bounded short-tail handling for stored notes. | Synthetic input with fragmented or oversized callbacks produces ordered PCM with no loss, duplication, partial samples, or unbounded buffering. |
| VM05 | Both | Planned | VM01, VM03 | Implement the durable draft registry schema and atomic update/reconciliation rules. Restrict references to validated app-private identifiers and define bounded count, retention, invalid-entry cleanup, queued-send reconciliation, and Restore/Delete discovery. | A crash at every registry/file transition produces either a valid recoverable finalized draft, a durable queued note, or a safely diagnosed invalid entry—never silent deletion of a valid finalized unqueued draft. |
| VM06 | Windows | Planned | VM02, VM04, VM05 | Implement reusable Windows capture over `waveIn`, feeding normalized frames to the WAV sink. Register ownership before capture, finalize and validate safely, and make start/stop/device-loss/disposal idempotent. | Handles are always released; output matches the frame and WAV contracts; the cap stops capture; `%TEMP%` is unused; valid forced-stop drafts are recoverable. |
| VM07 | Android | Planned | VM02, VM04, VM05 | Implement reusable `AudioRecord` capture and add `RECORD_AUDIO`. Request permission only on the first recording attempt, feed normalized frames to the WAV sink, and coordinate lifecycle/device errors with registry state. | Permission and lifecycle outcomes are deterministic; resources are released; limits match Windows; valid finalized drafts survive process death. |
| VM08 | Windows | Planned | VM05, VM06 | Add Record, elapsed time, Stop, Preview, Delete, Send, and startup Restore/Delete UI. Bind every draft to its owning conversation and serialize recorder actions, chat changes, attachment selection, and duplicate Send. | Nothing queues before Send. Restart restores a valid finalized draft only in its owner conversation. Restore revalidates it; Delete removes it; chat switching cannot misaddress it. |
| VM09 | Android | Planned | VM05, VM07 | Add equivalent recording and recovery UI while preserving existing action layout. Coordinate permission callbacks, rotation, Activity lifecycle, and the service-owned engine without background capture. | Only one recorder exists. Rotation/process restart cannot rebind a draft to another chat. Restore/Delete and error recovery behave like Windows. |
| VM10 | Both | Planned | VM03, VM05, VM08, VM09 | Implement transactional Send through the **Normal encrypted attachment store only**. Assign the final filename after message-ID creation, import fully, save the queued row durably, then remove registry/plaintext state. Reconcile crashes between every step. | Offline-send survives restart and later fetch. No queued note depends on the draft or Fast reference. A crash cannot create duplicate sends or expose one draft in both recoverable and queued states. |
| VM11 | Both | Planned | VM03, VM10 | Extend internal automatic retrieval for eligible voice offers, preferring resumable `FETCHSTREAM` with legacy `FETCH` fallback. Apply marker, size, trust, membership, retention, Online, storage, and existing two-task concurrency limits. | Failed/interrupted retrieval resumes internally. Retry never opens a destination picker. Untrusted, expired, oversized, Offline, or storage-failing offers do not auto-fetch. |
| VM12 | Both | Planned | VM02, VM03, VM04 | Implement the validated PCM playback source and seek converter. Produce frames from the WAV data chunk, convert seek targets with checked arithmetic, align to 2-byte samples, clamp bounds, flush stale output, and restart sequence/timestamp state. | Boundary, repeated, and adversarial seeks never read outside the validated data chunk, split a sample, play stale queued audio, overflow arithmetic, or report an impossible time. |
| VM13 | Windows | Planned | VM11, VM12 | Implement inline playback over `waveOut`: Play/Pause, elapsed/remaining time, optional seek slider, completion reset, one active player, bounded decrypted memory, and separate Save/Export. Add keyboard seek actions and accessible slider semantics. | Cross-platform notes play and seek accurately. Arrow/Page/Home/End operations and announced elapsed/remaining state work without a mouse. No plaintext playback file is created. |
| VM14 | Android | Planned | VM11, VM12 | Implement inline playback over `AudioTrack`: Play/Pause, elapsed/remaining time, optional seek slider, one active player, audio focus/routes, bounded PCM streaming, accessibility actions, and separate SAF Export. | Touch and accessibility seeking use the same validated converter. Focus loss pauses, screen exit releases resources, and incomplete/corrupt notes cannot seek or play. |
| VM15 | Both | Planned | VM08, VM09, VM13, VM14 | Complete accessibility and notification behavior. Use textual/non-color states, adequate targets, descriptive labels, and equivalent keyboard/accessibility actions for record, recovery, playback, seek, download, retry, delete, and export. | A keyboard or assistive-technology user can perform every supported voice-message action. Incoming notifications say “New voice message” without exposing audio. |
| VM16 | Both | Planned | VM10-VM15 | Verify backward-tolerant persistence and mixed-version behavior. Add only local metadata older readers tolerate. Confirm old peers see an ordinary WAV attachment and arbitrary WAV files remain ordinary files. | Existing histories need no destructive migration; mixed-version direct/group messaging remains valid; upgraded clients do not misclassify ordinary WAV attachments. |
| VM17 | Both | Planned | VM01-VM16 | After implementation, update `PROTOCOL.md`, `PROJECT_STATUS.md`, `windows/STATUS.md`, and `android/STATUS.md`. Document the voice auto-fetch exception, registry recovery, seek rules, PCM frame boundary, and remaining call roadmap. | Records distinguish implemented code, automated verification, hardware acceptance, and cross-platform LAN acceptance; they do not claim voice/video calls exist. |

## Automated testing plan

| ID | Platform | Status | Dependencies | Test work and required coverage |
|---|---|---:|---|---|
| VT01 | Both | Planned | VM01, VM03 | Test construction/classification, exact limits, checked duration arithmetic, malformed chunks, overflow, truncation, unsupported formats, forged markers, filename creation, nonmatching embedded IDs, and ordinary-WAV fallback. |
| VT02 | Both | Planned | VM02, VM04 | Add synthetic frame-contract loopback tests covering exact 20 ms cadence, fragmented callbacks, ordering, sequence gaps, timestamp regression, long-run clock drift, bounded backpressure, slow consumers, overflow policy, short final input, format rejection, device restart/discontinuity, and clean resumption. Assert bit-exact PCM where no declared discontinuity occurs. |
| VT03 | Both | Planned | VM05 | Add crash-point tests for registry creation, recording, header finalization, finalized-state persistence, Restore, Delete, encrypted import, queued-row persistence, and cleanup. Include invalid paths, missing files, corrupt/oversized files, registry limits, stale entries, duplicate records, and queued-send reconciliation. |
| VT04 | Windows | Planned | VM06, VM08, VM10 | Test capture/composer state machines with fake frame input: cap, device loss, duplicate actions, chat switch, shutdown, recovery prompt, Restore/Delete, explicit Send, and plaintext cleanup. Send Offline, restart, reconnect, and fetch after the draft is gone. |
| VT05 | Android | Planned | VM07, VM09, VM10 | Test permission states, lifecycle shutdown, rotation/process death, initialization failure, short reads, device restart, duration cap, registry recovery, transactional import, and explicit Send. |
| VT06 | Both | Planned | VM10, VM11 | Extend interoperability tests for Windows-to-Android and Android-to-Windows direct/group delivery, relay, offline queueing, restart recovery, auto-fetch, Retry, interrupted stream/legacy resume, integrity failure, and deduplication. Assert Retry never opens a destination picker. |
| VT07 | Both | Planned | VM12-VM14 | Test Play/Pause/resume, one-player policy, elapsed/remaining time, completion, and seek conversion at start/end, fractional positions, odd/untrusted offsets, rapid repeated seeks, pause-seek-resume, clear/expiry during seek, and maximum duration. Verify output starts at the expected complete sample. |
| VT08 | Both | Planned | VM13-VM15 | Test keyboard and accessibility actions, slider range/value announcements, elapsed/remaining labels, focus order, disabled seeking for unavailable content, notifications, and non-color state communication. |
| VT09 | Both | Planned | VM10-VM14 | Test bounded memory, low storage, forced termination, malformed input, automatic-fetch concurrency, retention changes during fetch/playback, device restarts, and absence of plaintext playback files. |
| VT10 | Both | Planned | VM10-VM16 | Regression-test ordinary attachments, destination-picker downloads, inline photos, Fast transfers, group signatures/relay/expiry, clear/delete, Offline lifecycle, Android upload budgets, pagination, and notifications. Run the complete `tests/run.ps1` suite. |

## Manual acceptance plan

### Windows

- Record, stop, preview, delete, send, play, pause, seek, resume, and export using available microphones and output devices.
- Force-close after finalization but before Send; restart and verify Restore/Delete appears only in the owning conversation.
- Verify timer accuracy, automatic stop, input/output device removal, handle release, seek accuracy, keyboard operation, screen-reader announcements, and high-DPI layout.
- Send while Offline, restart, reconnect, and verify another device fetches the note.
- Fail automatic retrieval, press Retry, and verify inline playback without a destination picker.

### Android physical devices

- Test Android 8 and a current supported Android version where available.
- Verify permission grant, denial, permanent denial, Settings recovery, rotation, process death, lock, background/foreground, audio focus, route changes, and low storage.
- Force-stop after finalization and verify the valid draft can be restored or deleted without starting background microphone capture.
- Exercise seek through touch and accessibility actions; confirm elapsed and remaining time follow the effective aligned position.
- Verify Windows-recorded playback, internal auto-fetch/Retry/Resume, and separate SAF Export.

### Two-device LAN interoperability

- Test direct and group notes in both directions.
- Interrupt sender and receiver with Offline transitions, restart, Wi-Fi loss, and process termination.
- Verify crash-restored drafts remain local until explicit Send.
- Verify group relay from an internally downloaded complete note.
- Seek notes produced by the opposite platform near start, middle, final sample, and end.
- Verify clear and seven-day expiry remove voice-note complete/partial storage and stop active playback.
- Confirm an older client treats the marked note as an ordinary WAV attachment.
- Confirm ordinary manual downloads still use the destination picker and voice-card Retry does not.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Plaintext recovery data persists longer after a crash | App-private storage, bounded registry/retention, explicit Restore/Delete, Normal-store import before cleanup, and documented best-effort deletion limitation |
| Registry and file disagree after termination | Ordered durable transitions, startup reconciliation, revalidation before Restore, and crash-point tests |
| Platform callbacks do not match 20 ms | Normalize through bounded frame assembly; never expose raw callback buffers as the shared contract |
| Slow consumer creates unbounded audio memory | Fixed queue limits and explicit backpressure/overflow behavior tested under synthetic load |
| Seeking reads invalid data or replays stale buffers | Validated data range, checked aligned offsets, output flush/generation invalidation, and serialized player state |
| Accessibility differs between platforms | Shared semantic requirements with platform-specific keyboard/accessibility acceptance |
| Call-ready abstractions expand into speculative call work | Keep PCM frames transport-independent and exclude signaling, packetization, codec, network, and jitter-buffer implementation |
| Auto-fetch changes prior download consent expectations | Restrict it to marked, validated, size-bounded notes under existing trust, retention, storage, Online, and concurrency controls |

## Completion gates

1. VM01-VM17 meet their acceptance criteria.
2. VT01-VT10 and the unchanged full regression suite pass.
3. Crash recovery offers Restore/Delete for valid finalized unqueued drafts on both platforms and never silently sweeps them as orphans.
4. Crash-point tests show no duplicate send and no queued message dependent on plaintext draft storage.
5. The synthetic PCM loopback passes ordering, drift, bounded backpressure, and device-restart scenarios.
6. Seek conversion remains within validated data bounds and aligns every target to a complete mono 16-bit sample.
7. Keyboard and accessibility seeking are equivalent to pointer/touch seeking.
8. Offline-send/restart/fetch passes on both sender platforms.
9. Failed-auto-fetch/Retry/play/relay/clear-or-expiry passes cross-platform.
10. Physical Windows, Android, and two-device results are recorded separately; unavailable hardware remains explicitly pending.
11. No new LM4 frame, Fast voice source, plaintext playback file, call signaling, or destination-picker dependency is introduced.
12. Status and protocol documentation accurately describe the auto-fetch exception, recovery behavior, seek semantics, PCM frame boundary, and remaining call roadmap.
13. Implementation, builds, commits, releases, and pushes occur only after the applicable explicit approval.

## Later roadmap

1. **Voice messages:** asynchronous encrypted PCM WAV attachments, crash-recoverable drafts, accessible seeking, and the internal PCM frame boundary described by this plan.
2. **Voice-call control:** capability discovery, authenticated invite/ring/accept/reject/end, session IDs, timeouts, and call UI.
3. **Live voice:** connect the PCM frame boundary to codec negotiation, secure real-time transport, jitter buffering, packet-loss handling, clock synchronization, echo cancellation, routing, and Android foreground-service behavior.
4. **Video foundation:** camera permissions/lifecycle, preview, video-frame contract, codec negotiation, rendering, and bandwidth adaptation while reusing the live-audio session.
5. **Video calls:** synchronized audio/video sessions, reconnection, device switching, thermal/battery controls, and full security/interoperability acceptance.
