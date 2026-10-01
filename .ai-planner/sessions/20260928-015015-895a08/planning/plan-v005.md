# plan-v005 | version=5 | Voice messages with durable recovery and call-ready media boundaries

## Goal

Add secure voice messages to Windows and Android by extending the existing encrypted, resumable attachment system. Establish transport-independent audio boundaries, crash-recoverable drafts, fair retrieval scheduling, and accessible playback so later voice-call and video-call phases can reuse the foundations without introducing live-call networking in this phase.

## Scope and product decisions

- Platform: **Both**.
- Support direct and group conversations.
- Composer flow: **Record -> Stop -> Preview/Delete -> Send**.
- Maximum recording duration: **5 minutes**.
- Shared stored format: **16 kHz, signed 16-bit, mono PCM WAV**.
- Maximum standard WAV size: **9,600,044 bytes**: 9,600,000 PCM bytes plus a 44-byte header.
- Voice messages remain ordinary LM4 attachment offers; this phase adds no call signaling or live-media protocol.
- After the final message ID is assigned, generate the signed filename `voice-<message-id>.lanvoice.wav`.
- Classification requires the application filename marker plus validated WAV content.
- Existing clients treat voice messages as downloadable WAV attachments.
- Send imports the finalized WAV into the Normal encrypted attachment store. Voice messages never use Fast/original-file references.
- A marked offer initially uses a pre-fetch voice-pending card based only on trusted offer metadata. It becomes a playable voice card only after download and full WAV validation.
- Download, Retry, and Resume on a marked offer use the internal encrypted attachment-fetch pipeline, never the destination picker.
- If an internally fetched marked offer fails voice validation, retain it as an ordinary internally stored attachment and offer Save/Export from that copy without fetching it again.
- Saving any received content outside the application is a separate Save/Export action.
- Eligible voice offers are automatically retrieved while Online, subject to trust, retention, storage, validation, scheduling, and concurrency limits.
- User-initiated attachment downloads have priority over all automatic retrieval.
- Among automatic retrieval jobs, voice offers have priority over automatic image retrieval, with bounded round-robin fairness so images cannot starve.
- Recording stops safely on conversation exit, backgrounding, Offline transition, or shutdown. A successfully finalized draft becomes recoverable.
- Microphone audio is never transmitted until the user presses Send.
- Downloaded and validated voice messages provide Play/Pause, elapsed and remaining time, and seeking through pointer/touch, keyboard, and accessibility actions.
- Capture and playback pass through an internal PCM frame contract reusable by future live calls.
- No application code, build, commit, release, or push begins until the user explicitly authorizes execution.

## Explicit non-goals for this release

- Voice calls, video calls, ringing, accept/reject, call presence, session negotiation, NAT traversal, conferencing, or real-time media transport.
- Sending internal PCM frames over the network.
- Background microphone capture.
- Echo cancellation, noise suppression, transcription, waveform generation, editing, or playback-speed controls.
- New codecs or third-party codec dependencies.
- Seeking incomplete, corrupt, unvalidated, or still-downloading content.
- Treating arbitrary `.wav` files as voice messages.
- Altering verification, group signatures, attachment authorization, or retention rules.
- Implementing deferred call-roadmap work during the voice-message release.
- Automatically retargeting a recovered draft to a different conversation.
- Building, committing, releasing, or pushing until explicitly authorized.

## Shared media contracts

### Stored voice-message contract

A sendable voice message is a marked, finalized, and validated RIFF/WAVE file containing mono signed 16-bit PCM at 16 kHz. Parsers must validate chunk bounds, checked arithmetic, truncation, data alignment, format, duration, and the five-minute ceiling before exposing playback controls.

The filename marker is sufficient only to recognize a candidate offer before retrieval. It is never proof that the content is playable voice audio.

### Receiver card state model

| State | Evidence available | UI and behavior |
|---|---|---|
| Candidate voice offer | Trusted attachment offer, valid marker, acceptable advertised size | Show voice-pending card with Download/Retry/Resume; use internal retrieval only |
| Fetching candidate | Internal encrypted partial and resume metadata | Show progress/cancel/retry according to existing transfer behavior |
| Valid playable voice | Complete internal copy, integrity check passed, WAV validation passed | Show Play/Pause, timing, seek, and separate Save/Export |
| Invalid marked content | Complete internal copy and integrity check passed, but WAV validation failed | Replace with ordinary-file card; disable voice controls; Save/Export directly from the internal copy without refetch |
| Unavailable or expired | Authorization, retention, membership, source, or storage conditions prevent retrieval | Show a non-playable explanatory state using existing attachment semantics |

Clear, conversation deletion, group expiry, and retention cleanup apply to complete and partial internal copies in every state, including invalid marked content.

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

The PCM contract depends only on the shared sample-format decision—16 kHz, signed 16-bit, mono—not on the WAV container, filename marker, LM4, attachment storage, or messaging types.

Platform capture adapters normalize arbitrary callback sizes into this contract. Recording writes ordered frame payloads to a WAV sink. Playback converts validated WAV data into the same frame shape before passing it to platform output adapters.

Buffering and backpressure must be bounded. Overflow, missing frames, timestamp regression, unsupported format changes, or device restart produce a deterministic stop or recovery outcome and never create silently valid-but-corrupt content.

The frame boundary excludes networking, encryption, packetization, codecs, peer-clock synchronization, and jitter buffering. Those belong to later call phases.

### Windows audio-adapter safety

Windows uses project-owned `winmm.dll` wrappers around `waveIn` and `waveOut`.

- Prefer `CALLBACK_EVENT` or `CALLBACK_WINDOW` buffer completion.
- If a function callback is necessary, it may only publish a completion signal into a bounded queue.
- All `waveIn*` and `waveOut*` control calls, buffer recycling, reset, close, stop, and disposal execute on an adapter-owned serialized thread or UI/message thread, never inside the system callback.
- Any native structures, buffers, handles, and delegates that can remain in flight are pinned or otherwise rooted until native shutdown completes.
- Stop, device removal, and disposal are idempotent and tolerate late completion notifications.
- No callback may update WinForms state directly.

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

Maintain a durable draft registry in application-private storage.

### Registry policy

- Maximum registry entries: **10 finalized or active drafts across the application**.
- A full registry blocks only creation of a new recording. Restore, Preview, Delete, Send, reconciliation, and cleanup remain available.
- Finalized valid drafts have a **30-day review age**, not a silent deletion deadline.
- At 30 days, show them as stale in the recovery notice and require explicit Send or Delete.
- A valid finalized draft is never silently deleted solely because of age.
- Invalid, unfinished, or unregistered artifacts use a separate deterministic cleanup policy after safe-reference checks.
- Reconciliation and cleanup must never delete a source still referenced by a durable queued message.

Each registry entry contains:

- Opaque draft ID.
- Owning conversation ID and direct/group type.
- Creation and last-state-change times.
- Validated application-private storage identifier, never an arbitrary external path.
- Recording and finalization state.
- Expected format and bounded size metadata.
- Optional recovery or error status.
- Queue/import association only while completing transactional Send.

### Required transition order

1. Create and durably register draft ownership before accepting microphone frames.
2. Record normalized PCM into application-private storage.
3. On Stop or forced safe stop, finalize and validate the WAV.
4. Mark it finalized and recoverable only after the file and header are durable.
5. On Send, import the finalized WAV into the Normal encrypted store and durably save the queued message.
6. Remove the registry entry and best-effort delete plaintext only after the encrypted source and queued message are durable.

### Startup reconciliation

- A valid finalized, unqueued draft whose conversation still exists and permits sending is offered as **Restore** or **Delete** in that conversation and in a global recovery notice.
- Restore revalidates the file and recreates preview state without transmitting it.
- Delete removes the registry entry and best-effort deletes its file.
- If the owning conversation is missing or cannot accept a send—for example, a removed contact, deleted conversation, departed group, or changed membership—the draft appears in the global recovery notice as **unsendable** with Preview and Delete only.
- An unsendable draft is never retargeted or attached to a different conversation.
- Preview of an unsendable draft does not restore composer ownership or enable Send.
- Unfinished, missing, invalid-path, oversized, corrupt, or mismatched records are never offered as playable drafts.
- Invalid entries follow a deterministic quarantine/deletion policy and may produce a non-sensitive recovery notice.
- A record associated with a durable queued message is not restored or deleted until reconciliation confirms the encrypted source is durable.
- Unregistered files and stale invalid records become cleanup candidates only after safe-reference validation.

Known limitation: plaintext exists in application-private storage until encrypted import succeeds. Crash recovery can extend its lifetime, and best-effort deletion cannot guarantee erasure from snapshots or flash media.

## Retrieval scheduling policy

Use the existing bounded transfer machinery, but make scheduling explicit:

1. User-initiated downloads and resumes are admitted before automatic work.
2. Automatic voice candidates are selected before automatic image candidates because voice messages are bounded and interaction-sensitive.
3. After three consecutive automatic voice admissions while an image is waiting, admit one image job before continuing voice work.
4. New automatic work never preempts an already active transfer by destroying its progress; priority applies when a slot becomes available.
5. If all slots are occupied by automatic work and the current transfer layer supports safe pause/resume, a user-initiated request may pause one automatic job at a resumable boundary. Otherwise it becomes the next admitted job.
6. Restart rebuilds queues deterministically from durable offer/partial state without duplicating active work.
7. Invalid marked content remains associated with its completed internal encrypted copy and leaves the automatic queue permanently unless that copy is cleared.

This policy preserves the current global concurrency limit while preventing voice/image starvation and protecting manual operations.

## Architecture boundary

```text
Microphone
    |
Platform capture adapter
    |
Transport-independent PCM frames
    +--> Voice-message recorder --> finalized WAV
    |                             --> Normal encrypted store
    |                             --> existing LM4 attachment fetch
    |
    +--> Future live encoder and media transport [R2-02, not implemented]

Encrypted received attachment
    |
Validated WAV data source
    |
Transport-independent PCM frames
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

Device adapters own microphone and speaker lifecycle. The frame layer owns ordering, cadence metadata, bounded buffering, discontinuities, and backpressure. Attachment storage owns asynchronous voice messages. Later call phases add signaling and live-media systems outside this release.

## Voice-message task plan

All tasks are **Planned** until the user explicitly authorizes execution.

| ID | Platform | Status | Dependencies | Task and notes | Acceptance criteria |
|---|---|---:|---|---|---|
| VM01 | Both | Planned | None | Freeze the sample format, stored WAV contract, five-minute and 9,600,044-byte ceilings, marker, naming, interruption behavior, Normal-only source, receiver states, retrieval policy, ten-entry registry, 30-day review age, and mixed-version fallback. | Both platforms enforce identical stored-content, registry, and receiver-state rules. Existing clients can handle the payload as an ordinary WAV attachment. |
| VM02 | Both | Planned | VM01 sample-format decision only | Define the PCM frame and stream-state contract using only the 16 kHz/16-bit/mono parameters from VM01. Define sequence, timestamp, format, 20 ms cadence, bounded ownership, end/discontinuity markers, queue bounds, backpressure, and device restart. | Equivalent contract tests pass on both platforms; the abstraction imports no WAV, marker, message, socket, attachment, UI, or call-signaling dependency. |
| VM03 | Both | Planned | VM01 | Add candidate-offer classification and a streaming WAV parser. Validate RIFF/WAVE structure, PCM format, chunks, checked size/duration arithmetic, sample alignment, trailing data, and truncation. | Marker-only candidates are distinguished from validated voice. Spoofed, malformed, oversized, truncated, unsupported, or unmarked content cannot receive playback controls. |
| VM04 | Both | Planned | VM01, VM02 | Implement frame normalization and WAV frame source/sink boundaries. Normalize arbitrary device callback sizes, preserve complete samples, and define bounded short-tail handling. | Fragmented and oversized synthetic callbacks produce ordered PCM without loss, duplication, partial samples, or unbounded buffering. |
| VM05 | Both | Planned | VM01, VM03 | Implement the durable ten-entry registry, 30-day review state, global recovery notice, ownership validation, unsendable-draft handling, and atomic reconciliation. | Every simulated crash produces a valid recoverable draft, durable queued message, or diagnosed invalid entry. Valid finalized work is never silently deleted or made unreachable. |
| VM06 | Windows | Planned | VM02, VM04, VM05 | Implement reusable Windows capture using the defined `winmm` callback safety rules. Register ownership before capture, finalize safely, and make stop/device-loss/disposal idempotent. | Handles, delegates, and buffers remain valid until shutdown and are released afterward. No prohibited native operation occurs in a callback. Forced-stop drafts remain recoverable when valid. |
| VM07 | Android | Planned | VM02, VM04, VM05 | Implement reusable `AudioRecord` capture and add `RECORD_AUDIO`. Request permission on the first attempt and coordinate lifecycle/device errors with registry state. Record only while foreground UI is actively recording. | Permission and lifecycle outcomes are deterministic; resources are released; no background recording or microphone foreground service is introduced; valid drafts survive process death. |
| VM08 | Windows | Planned | VM05, VM06 | Add Record, elapsed time, Stop, Preview, Delete, Send, and local/global recovery UI. Serialize recorder actions, conversation changes, attachment selection, and duplicate Send. | Nothing queues before Send. A draft cannot be misaddressed, and an unsendable draft remains Preview/Delete-only. |
| VM09 | Android | Planned | VM05, VM07 | Add equivalent recording and recovery UI while preserving the existing action layout. Coordinate permissions, rotation, Activity lifecycle, and the service-owned engine. | Only one recorder exists. Rotation or restart cannot rebind a draft, and leaving the foreground stops/finalizes recording without background capture. |
| VM10 | Both | Planned | VM03, VM05, VM08, VM09 | Implement transactional Send through the Normal encrypted store. Assign the filename after message-ID creation, import fully, save the queued row durably, then remove registry/plaintext state. | Offline Send survives restart and later fetch. No queued message depends on a draft or Fast reference; crashes do not create duplicate sends. |
| VM11 | Both | Planned | VM03, VM10 | Implement candidate, fetching, playable, invalid-marked, and unavailable receiver states. Extend internal automatic retrieval with the stated manual/voice/image priority and fairness rules. Invalid marked content becomes an ordinary-file card backed by the completed internal copy. | Retry never opens a destination picker. Invalid marked content can be exported without refetch. Clear/expiry cleans every complete or partial internal state. Manual downloads are not starved by automatic work. |
| VM12 | Both | Planned | VM02, VM03, VM04 | Implement validated PCM playback sources and seek conversion. Produce frames only from a validated WAV data range, align targets, clamp bounds, flush stale output, and restart frame state. | Adversarial seeks never read outside the data chunk, split samples, replay stale audio, overflow arithmetic, or report impossible time. |
| VM13 | Windows | Planned | VM06, VM11, VM12 | Implement inline playback over `waveOut` using the Windows callback safety rules: Play/Pause, timing, seek, completion reset, one active player, bounded decrypted memory, Save/Export, keyboard controls, and accessible semantics. | Cross-platform messages play and seek accurately; device removal is safe; no plaintext playback file is created. |
| VM14 | Android | Planned | VM11, VM12 | Implement inline playback over `AudioTrack`: Play/Pause, timing, seek, one active player, audio focus/routes, bounded PCM streaming, accessibility actions, and separate SAF Export. | Touch and accessibility seeking share the validated converter. Focus loss pauses, exit releases resources, and invalid content cannot play. |
| VM15 | Both | Planned | VM08, VM09, VM13, VM14 | Complete accessibility and notification behavior using textual/non-color states, adequate targets, descriptive labels, and equivalent keyboard/accessibility actions. | A keyboard or assistive-technology user can perform every supported action. Notifications say “New voice message” without exposing audio. |
| VM16 | Both | Planned | VM10-VM15 | Verify backward-tolerant persistence and mixed-version behavior. Add only local metadata older readers tolerate. | Existing histories need no destructive migration; old clients see ordinary WAV attachments; arbitrary or spoofed WAV files are not treated as playable voice. |
| VM17 | Both | Planned | VM01-VM16 | After implementation and verification, update `PROTOCOL.md`, `PROJECT_STATUS.md`, `windows/STATUS.md`, and `android/STATUS.md`. | Records distinguish implementation, automated verification, device acceptance, and interoperability; they do not claim calls exist. |

## Deferred call roadmap

These tasks keep the final video-call goal visible but remain **Deferred**. Each needs a new detailed plan and explicit execution approval after the preceding phase passes.

| ID | Platform | Status | Dependencies | Future task and boundary | Entry acceptance criteria |
|---|---|---:|---|---|---|
| R2-01 | Both | Deferred | VM02, VM16, VM17 | **Authenticated call control.** Design capability discovery, media/codec declarations, verified identity binding, invite/ring/accept/reject/cancel/end states, collisions, session IDs, replay protection, timeouts, missed-call metadata, Offline behavior, and mixed-version fallback. | A reviewed signaling state machine and threat model cover duplicates, reordering, simultaneous calls, disconnects, replay, restart, incompatible peers, and consent before media starts. |
| R2-02 | Both | Deferred | R2-01, VM02, VM04, VM06, VM07, VM12-VM14 | **Live voice calls.** Connect the PCM boundary to negotiated codecs and authenticated encrypted real-time transport. Add jitter buffering, loss handling, clock-drift control, echo cancellation, route changes, Android foreground-service behavior, reconnect policy, bandwidth limits, and call UI. | Bidirectional calls pass security, latency, packet-loss, route-change, reconnect, lifecycle, accessibility, mixed-device, and physical LAN acceptance without regressing voice messages. |
| R2-03 | Both | Deferred | R2-02 | **Video calls.** Add camera permission/lifecycle, preview, bounded video frames, codec/resolution negotiation, encrypted transport, rendering, A/V synchronization, camera switching, adaptive bitrate, thermal/battery controls, audio-only fallback, and privacy indicators. | Windows-to-Android and Android-to-Windows video calls pass consent, privacy, synchronization, adaptation, interruption, reconnect, resource-release, accessibility, and physical-device acceptance. |

### Call-roadmap constraints established now

- VM02 remains independent of attachment, WAV, UI, signaling, and network types.
- Capture and playback adapters have explicit lifecycle and ownership boundaries.
- Voice-message buffering is not reused as a live-call jitter buffer.
- No call wire frames, codec packages, speculative permissions, call background behavior, or call UI are implemented in the voice-message phase.
- R2-01 must be planned and reviewed before R2-02; R2-02 must pass before R2-03 begins.
- R2-03 reuses the R2-01 session and R2-02 audio path rather than creating a parallel call system.
- Every shared wire change requires review of both implementations and cross-platform interoperability testing.

## Automated testing plan

| ID | Platform | Status | Dependencies | Test work and required coverage |
|---|---|---:|---|---|
| VT01 | Both | Planned | VM01, VM03, VM11 | Test candidate classification, post-fetch validation, valid/invalid state transitions, exact limits, overflow, truncation, forged markers, nonmatching IDs, and ordinary-file fallback backed by the existing internal copy. |
| VT02 | Both | Planned | VM02, VM04 | Test cadence, fragmented callbacks, ordering, gaps, timestamp regression, clock drift, bounded backpressure, overflow policy, short final input, format rejection, device restart, and bit-exact PCM where no discontinuity occurs. |
| VT03 | Both | Planned | VM05 | Test every registry crash point, the ten-entry limit, new-recording-only blocking, Restore while full, 30-day stale notice, missing/unsendable conversations, Preview/Delete-only recovery, invalid paths, duplicates, and reconciliation. |
| VT04 | Windows | Planned | VM06, VM08, VM10, VM11 | Test capture/composer/receiver states with fake input, including spoofed marked offers, internal-copy export without refetch, conversation removal, device loss, duplicate actions, Offline Send, restart, reconnect, and callback-thread invariants. |
| VT05 | Android | Planned | VM07, VM09, VM10 | Test permission states, foreground-only capture, lifecycle shutdown, rotation/process death, initialization failure, short reads, duration limit, recovery, and transactional import. |
| VT06 | Both | Planned | VM10, VM11 | Test direct/group delivery in both directions, relay, offline queueing, restart recovery, auto-fetch, Retry, interrupted resume, integrity failure, invalid marked content, and deduplication. |
| VT07 | Both | Planned | VM11-VM14 | Test Play/Pause/resume, one-player policy, timing, completion, adversarial seeking, and automatic queue fairness: voice priority, the three-voice/one-image rule, restart ordering, and absence of image starvation. |
| VT08 | Both | Planned | VM13-VM15 | Test keyboard and accessibility actions, slider announcements, timing labels, focus order, unavailable/invalid states, notifications, and non-color communication. |
| VT09 | Both | Planned | VM10-VM14 | Test bounded memory, low storage, malformed input, transfer concurrency, manual-download priority, safe pause/resume where supported, retention changes, device restart/removal, and absence of plaintext playback files. |
| VT10 | Both | Planned | VM10-VM16 | Regression-test ordinary attachments, destination downloads, inline photos, Fast transfers, group relay/expiry, clear/delete, Offline lifecycle, Android upload budgets, pagination, and notifications. Run the complete `tests/run.ps1` suite. |
| VT11 | Both | Planned | VM02, VM04, VM12 | Enforce architectural boundaries: the PCM layer has no LM4, WAV-container, attachment, UI, or call-signaling dependency, and adapters can be driven by test frame sources/sinks. |

## Manual acceptance plan

### Windows

- Record, stop, preview, delete, send, play, pause, seek, resume, and export with available devices.
- Remove or disable capture and playback devices during active use; confirm safe finalization or stop, no deadlock, no callback-thread UI access, and complete handle release.
- Force-close after finalization but before Send; restart and confirm Restore/Delete appears.
- Remove the owning contact or leave/delete the group before restart; confirm the global notice offers Preview/Delete only and never retargets the draft.
- Fill the registry to ten drafts; confirm only new recording is blocked and existing drafts remain resolvable.
- Send while Offline, restart, reconnect, and verify retrieval on another device.
- Fetch a spoofed marked offer; confirm it becomes an ordinary-file card that exports without refetch.

### Android physical devices

- Test API 26 (Android 8.0 minimum) and API 34 (the current target-SDK level recorded for this plan).
- Verify permission grant, denial, permanent denial, Settings recovery, rotation, process death, lock, foreground/background transitions, audio focus, route changes, and low storage.
- On API 34, verify the microphone privacy indicator appears only during active foreground recording.
- Confirm backgrounding stops/finalizes recording and that no microphone foreground-service type or background microphone capture is used.
- Force-stop after finalization and verify Restore/Delete without starting microphone capture.
- Remove the owning conversation and confirm global Preview/Delete-only recovery.
- Exercise seeking by touch and accessibility actions.
- Verify Windows-recorded playback, internal Retry/Resume, spoof fallback, and separate SAF Export.

### Two-device LAN interoperability

- Test direct and group voice messages in both directions.
- Interrupt sender and receiver with Offline transitions, restart, Wi-Fi loss, and process termination.
- Confirm recovered drafts remain local until explicit Send.
- Verify group relay from a completely retrieved internal voice message.
- Seek opposite-platform messages near the start, middle, last sample, and end.
- Verify clear and seven-day expiry remove complete and partial voice storage, including invalid marked copies, and stop playback.
- Confirm an older client treats the marked message as an ordinary WAV attachment.
- Confirm ordinary manual downloads keep priority and still use the destination picker.
- Confirm voice Retry never opens the destination picker.
- Queue voice and image backlogs together and verify voice priority plus bounded image fairness.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Plaintext recovery data persists after a crash | Application-private storage, bounded registry, explicit Restore/Delete, encrypted import before cleanup, and documented best-effort deletion limits |
| A missing conversation makes a draft unreachable | Global recovery notice with Preview/Delete-only handling and no retargeting |
| Registry limits prevent users resolving drafts | Limit blocks only new recording; recovery and cleanup remain available |
| Age cleanup silently loses valid work | Thirty days triggers a stale notice, never automatic deletion of valid finalized drafts |
| Marker spoofing creates misleading voice UI | Candidate state before fetch; playable state only after validation; ordinary-file fallback reuses the internal copy |
| Automatic voice retrieval starves other work | Manual priority, voice-before-image ordering, and bounded three-to-one fairness |
| Windows callbacks deadlock or outlive managed state | Event/window completion, serialized native control calls, rooted delegates/buffers, and late-notification-safe disposal |
| Platform callbacks do not match 20 ms | Bounded frame assembly; raw callback buffers never become the shared contract |
| Seeking reads invalid data or replays stale buffers | Validated ranges, aligned checked offsets, output invalidation, and serialized player state |
| Call readiness causes speculative scope growth | Keep all call tasks deferred and prohibit call signaling, transport, video, and speculative permissions |
| Future video creates an incompatible session model | Require video to reuse the authenticated call session and live-audio ownership model |

## Completion gates

1. VM01-VM17 meet their acceptance criteria.
2. VT01-VT11 and the unchanged full regression suite pass.
3. Candidate, playable, invalid-marked, and unavailable receiver states behave deterministically.
4. Invalid marked content exports from its internal copy without refetch and is removed by clear/expiry.
5. Valid finalized drafts are always reachable through conversation or global recovery UI and are never silently deleted.
6. Missing or unsendable owner conversations produce Preview/Delete-only recovery with no retargeting.
7. The ten-entry limit blocks only new recordings; the 30-day threshold produces a notice rather than deletion.
8. Crash tests show no duplicate Send and no queued message dependent on plaintext draft storage.
9. Windows capture/playback survives device removal without callback-thread native-control calls, deadlocks, use-after-free, or leaked handles.
10. Manual downloads have priority; automatic voice work has priority over image work; bounded fairness prevents image starvation.
11. Synthetic PCM tests pass ordering, drift, bounded backpressure, and restart scenarios without introducing WAV or attachment dependencies.
12. Seek conversion remains within validated data and aligns every target to a complete sample.
13. Offline Send, restart, reconnect, retrieval, playback, relay, clear, and expiry pass cross-platform.
14. API 34 acceptance confirms foreground-only recording, correct privacy indication, and no microphone foreground-service requirement for this design.
15. No new call frame, Fast voice source, plaintext playback file, signaling, live transport, or video behavior is introduced.
16. Status and protocol documentation accurately distinguish implemented behavior, automated verification, and physical-device acceptance.
17. Implementation, builds, commits, releases, pushes, and deferred call execution occur only after applicable explicit approval.

## Delivery sequence

1. Freeze VM01 and the sample-format-only dependency used by VM02.
2. Complete VM02-VM05 with VT01-VT03, including receiver states, recovery limits, and unsendable-draft handling.
3. Implement platform capture and composer work in VM06-VM09 with VT04-VT05.
4. Complete transactional delivery, retrieval states, and fair scheduling in VM10-VM11 with VT06.
5. Complete playback, seeking, accessibility, and UI behavior in VM12-VM15 with VT07-VT09.
6. Complete compatibility, regression, architectural checks, documentation, and physical acceptance through VM16-VM17 and VT10-VT11.
7. Close the voice-message phase before separately planning and authorizing R2-01.
8. Progress sequentially from authenticated call control to live voice calls and finally video calls.
