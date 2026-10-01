# plan-v007 | version=7 | Voice messages first, with testable foundations for voice and video calls

## Goal

Deliver reliable voice messages on Windows and Android as the first controlled step toward future voice and video calls.

This phase adds recording, preview, explicit sending, encrypted delivery, automatic retrieval, inline playback, seeking, crash recovery, accessibility, and Windows/Android interoperability. It establishes reusable audio-device and PCM-frame boundaries without implementing call signaling or real-time media transport.

## Current project context

- Scope: **Both Windows and Android**.
- Current released version: **2.1.0** on both platforms.
- Both platforms already support encrypted ordinary attachments, offline queues, resumable transfers, direct conversations, group conversations, and cross-platform interoperability.
- Windows automatic image retrieval currently starts in `PeerEngine.QueueImageDownloads()` in `windows/Transfers.cs`, using `imageSlots`, `imageAttempts`, and `DownloadAttachmentAsync()`.
- Android automatic image retrieval currently starts in `TransferManager.queueImageDownloads()` in `android/src/net/lanmsg/chat/TransferManager.java`, using `PeerEngine.imageSlots`, `imageAttempts`, and `TransferManager.downloadAttachment()`.
- Android networking is owned by its service; microphone recording in this phase remains foreground-UI-only.
- Windows remains active through its tray process, but recording must stop when its recording UI is no longer active.
- Voice messages reuse the existing Normal encrypted attachment path instead of adding a new LM4 wire protocol.
- Later call protocols require separate shared-protocol reviews and interoperability plans.
- Every implementation and testing task remains **Planned** until the user explicitly authorizes execution.

## Phase-one product contract

### User flow

1. The user opens a direct or group conversation.
2. The user selects **Record voice message**.
3. Recording begins only after microphone permission and device initialization succeed.
4. The composer displays elapsed time and a clear Stop action.
5. Recording stops explicitly or safely when its lifecycle is interrupted.
6. The user can Preview, Delete, or Send the finalized recording.
7. Nothing leaves the device before Send.
8. Send queues the recording through the existing encrypted Normal attachment system.
9. The receiver automatically retrieves eligible voice messages while Online.
10. A validated message displays inline Play/Pause, elapsed or remaining time, seek, Retry when needed, and Save/Export.

### Initial limits

- Maximum recording duration: **5 minutes**.
- Stored format: RIFF/WAVE, **16 kHz**, signed **16-bit PCM**, **mono**.
- Maximum valid WAV size: **9,600,044 bytes**, consisting of at most 9,600,000 PCM bytes plus the canonical 44-byte header.
- Voice-message filename after message-ID allocation: `voice-<message-id>.lanvoice.wav`.
- Voice messages always use the Normal encrypted attachment store.
- Voice messages never use Fast or original-file references.
- Direct and group conversations are supported.
- Only one recorder and one active player may exist per application instance.
- Existing retention, authorization, group relay, clear-chat, and expiry rules continue to apply.

### Compatibility strategy

No new LM4 frame is required for voice messages.

A voice message remains an ordinary attachment whose filename carries an application-specific marker. A current client treats that marker only as a request to attempt voice classification. It exposes playback controls only after the complete content passes integrity checking and WAV validation.

An older client sees the object as an ordinary downloadable WAV attachment. A forged marker, arbitrary WAV file, malformed payload, unsupported encoding, oversized recording, or truncated file must never receive playable voice controls.

## Scope

### Included now

- Microphone permission and audio-device handling.
- Foreground recording in direct and group conversations.
- Stop, Preview, Delete, and explicit Send.
- Safe forced stop on conversation exit, backgrounding, Offline transition, application shutdown, or audio-device loss.
- Recoverable finalized drafts after application or process failure.
- Transactional import into encrypted attachment storage.
- Offline queueing and later delivery.
- Automatic internal retrieval with deterministic bounded scheduling.
- Resumable retrieval through existing attachment mechanisms.
- Inline playback, pause/resume, timing, and seeking.
- Separate Save/Export action.
- Keyboard and accessibility support.
- Windows-to-Android and Android-to-Windows tests.
- Shared cross-platform golden vectors for PCM framing and WAV parsing.
- Reusable PCM-frame, capture-adapter, and playback-adapter boundaries for later voice calls.
- Status and protocol documentation after implementation and verification.

### Explicitly deferred

- Voice-call invitations, ringing, accept/reject, missed calls, and call presence.
- Real-time audio transport.
- Video capture or transport.
- NAT traversal or internet relays.
- Conferencing.
- Background microphone capture.
- Echo cancellation, noise suppression, transcription, waveform generation, editing, and playback speed.
- New audio codecs or third-party codec dependencies.
- Sending partially recorded audio.
- Seeking unvalidated or incomplete content.
- Any release, commit, or push without separate authorization.

## Architecture prepared for later calls

```text
Voice-message phase

Microphone
  -> platform capture adapter
  -> shared PCM-frame contract
  -> WAV recorder
  -> recoverable draft
  -> Normal encrypted attachment store
  -> existing LM4 attachment delivery

Received encrypted attachment
  -> integrity verification
  -> WAV validation
  -> shared PCM-frame contract
  -> platform playback adapter
  -> speaker

Later call phases

Authenticated call signaling
  -> live audio session
  -> encoder / authenticated encrypted transport / jitter buffer
  -> existing capture and playback adapter boundaries
  -> synchronized audio/video session
```

Reuse is deliberately limited to audio-device ownership, PCM framing, timestamps, bounded buffering, lifecycle events, and capture/playback adapters. Voice-message WAV storage, attachment retrieval, and fairness scheduling must not become the future call transport or jitter buffer.

## Shared media contracts

### PCM frame boundary

Both platforms must implement equivalent behavior:

| Property | Contract |
|---|---|
| Audio format | 16 kHz, signed 16-bit PCM, mono |
| Target frame duration | 20 ms |
| Samples per target frame | 320 |
| Bytes per target frame | 640 |
| Sequence | Monotonically increasing within a stream |
| Timestamp | Monotonic media time relative to stream start |
| Payload | Complete samples only |
| Stream events | End, discontinuity, device failure, and restart are explicit |
| Ownership | Immutable data or bounded ownership transfer |
| Backpressure | Queues are bounded and use a deterministic overflow policy |

Device callbacks may produce different buffer sizes. Platform adapters assemble or split those callbacks into complete PCM frames without exposing native callback buffers to shared media logic.

The PCM layer must not depend on WAV containers, LM4 frames, attachment records, message UI, sockets, call signaling, or video types.

### Shared golden vectors

Create one cross-platform fixture set under `tests/voice_messages/vectors/`. It is the normative executable definition of equivalent framing, validation, and seek behavior for both implementations.

The set will contain:

- A versioned machine-readable manifest defining inputs and expected outputs.
- Canonical valid WAV fixtures, including empty, one-sample, one-frame, short-final-frame, and maximum-boundary cases.
- PCM callback sequences with fragmented, exact-frame, oversized, and discontinuous input.
- Expected normalized frame payloads, sequences, timestamps, final-frame lengths, and stream events.
- Seek inputs with expected clamped and sample-aligned byte positions.
- Adversarial WAV fixtures covering truncation, invalid sizes, overflow attempts, duplicate or reordered chunks, unknown chunks, bad alignment, unsupported formats, trailing data, and oversized content.
- Expected validation result and stable failure category for every fixture.

Windows VT01, VT02, and VT07 and Android VT01, VT02, and VT07 must load the same files directly. Platform-local copies of the fixtures are prohibited. A fixture version change must be reviewed as a Both-platform contract change.

### WAV validation

A received or recovered recording becomes playable only after validation of:

- RIFF and WAVE identifiers.
- Chunk boundaries and checked size arithmetic.
- Supported PCM format.
- One channel, 16 kHz, and 16 bits per sample.
- Correct block alignment and byte rate.
- Complete two-byte sample alignment.
- A bounded `data` chunk.
- No truncation or out-of-range reads.
- Maximum duration and stored size.
- Defined handling of recognized and unknown chunks.
- Consistency between effective duration and data length.

### Receiver states

| State | Required behavior |
|---|---|
| Candidate | Marked trusted offer; show voice-pending UI without claiming it is playable |
| Fetching | Show internal retrieval progress and existing cancel/retry semantics |
| Playable | Complete, integrity-verified, and WAV-validated; enable playback and export |
| Invalid marked content | Replace voice UI with an ordinary attachment card using the already retrieved internal copy |
| Unavailable | Explain expiry, authorization, source, membership, or storage failure without enabling playback |

Voice Retry and Resume use internal retrieval and never open a destination picker. Save/Export is a separate action after content becomes available.

### Seeking

Every seek operation must:

1. Clamp requested time to the validated duration.
2. Convert it using checked arithmetic.
3. Resolve the position within the validated WAV `data` range.
4. Align downward to a complete two-byte sample.
5. Invalidate output queued from the old position.
6. Reset playback sequence and media timestamp state.
7. Report the effective aligned position to the UI.

Seeking to the end produces the completed state.

## Draft recovery and transactional sending

### Registry rules

Maintain a durable registry in application-private storage.

- Maximum: **10 active or finalized drafts application-wide**.
- Reaching the limit blocks only creation of another recording.
- Existing drafts may still be previewed, sent, deleted, reconciled, or recovered.
- Valid finalized drafts receive a stale-review indication after **30 days**.
- Age alone never silently deletes a valid finalized draft.
- A recovered draft remains bound to its original conversation.
- A draft whose conversation no longer permits sending is Preview/Delete-only.
- Recovered drafts are never automatically retargeted.
- Arbitrary external paths are never accepted from registry data.

Each entry records an opaque draft ID, owning conversation, conversation type, timestamps, validated application-private storage identity, recording state, expected format, bounded size metadata, and temporary send-transaction association.

### Required write order

1. Create and durably register draft ownership.
2. Start accepting microphone frames.
3. Write normalized PCM to application-private storage.
4. Safely finalize the WAV when recording stops.
5. Flush and validate the completed file.
6. Mark the draft recoverable only after successful finalization.
7. Allocate the message ID and final marked filename on Send.
8. Import the complete WAV into the Normal encrypted store.
9. Durably save the queued message.
10. Remove the registry entry and best-effort delete plaintext only after the encrypted source and queued message are durable.

Crash recovery at every boundary must result in exactly one of:

- A valid recoverable draft.
- A durable queued message with a durable encrypted source.
- A diagnosed invalid entry that cannot be sent or played.

It must not produce duplicate sends, unreachable valid drafts, or queued messages dependent on deleted plaintext files.

## Deterministic retrieval scheduling

### Existing extension points

Windows VM11 will replace the image-only admission logic around `PeerEngine.QueueImageDownloads()`, `imageSlots`, and `imageAttempts` in `windows/Transfers.cs` with a bounded automatic-media scheduler. Actual retrieval continues through `PeerEngine.DownloadAttachmentAsync()`.

Android VM11 will replace the image-only admission logic around `TransferManager.queueImageDownloads()`, `PeerEngine.imageSlots`, and `imageAttempts` in `android/src/net/lanmsg/chat/TransferManager.java` with the equivalent bounded automatic-media scheduler. Actual retrieval continues through `TransferManager.downloadAttachment()` and `ResumableTransfer`.

The existing component names are recorded here so implementation and VT07 target concrete integration points rather than introducing an unrelated scheduler.

### Fixed admission policy

Both platforms use the same fixed **next-admitted, non-preemptive** policy:

1. User-initiated attachment downloads and resumes are considered first whenever capacity next becomes available.
2. Automatic voice retrieval is considered next.
3. Automatic image retrieval is considered last.
4. After three consecutive automatic voice admissions while at least one eligible image is waiting, the next automatic admission must be an image.
5. Active transfers are never paused or cancelled merely because a higher-priority request arrives.
6. A manual request arriving while all transfer capacity is occupied becomes the next eligible admission after a slot is released.
7. Fairness counters advance only when a job is successfully admitted, not when an offer is scanned, rejected, duplicated, expired, or found unreachable.
8. Offline transition, cancellation, clearing, expiry, and authorization loss retain their existing interruption semantics.
9. Restart reconstructs eligible automatic work from durable offers and partial-transfer state, with no duplicated active or completed retrieval.

There is no runtime choice between preemption and next-admitted behavior. Windows and Android both use next-admitted behavior, and VT07 verifies that exact branch.

## Implementation plan

All tasks below are **Planned**.

| ID | Platform | Status | Dependencies | Task and implementation notes | Acceptance criteria |
|---|---|---|---|---|---|
| VM01 | Both | Planned | None | Freeze format, duration, marker, size, direct/group scope, Normal-only storage, receiver states, recovery limits, lifecycle rules, compatibility, scheduler integration points, fixed next-admitted policy, golden-vector format, and non-goals. | Both platforms share one resolved behavioral contract; scheduler components and admission behavior are recorded; no product choice remains hidden inside implementation tasks. |
| VM02 | Both | Planned | VM01 | Define the transport-independent PCM frame, stream events, ownership, timestamps, sequencing, backpressure, discontinuity, restart behavior, and versioned golden-vector manifest. | The contract is independent of attachment, WAV, LM4, UI, signaling, and video code; both platform suites can consume the same manifest. |
| VM03 | Both | Planned | VM01 | Implement marker recognition and bounded streaming WAV validation. Keep candidate classification separate from content validation. Add shared valid and adversarial WAV fixtures under `tests/voice_messages/vectors/`. | Malformed, unsupported, oversized, truncated, spoofed, or unmarked content never gains playback controls; both platforms agree on every fixture result. |
| VM04 | Both | Planned | VM02, VM03 | Implement frame normalization and WAV frame sink/source boundaries. Handle fragmented callbacks, large callbacks, complete-sample alignment, final short frames, and checked counters. Add shared PCM and seek vectors. | Both platforms produce the expected ordered, bit-exact frames and metadata from the same vectors without loss, duplication, overflow, partial samples, or unbounded buffering. |
| VM05 | Both | Planned | VM01, VM03 | Add the durable draft registry, atomic transitions, startup reconciliation, ten-entry limit, 30-day review state, and invalid-entry cleanup safeguards. | Every simulated crash point resolves to a recoverable draft, durable queued message, or diagnosed invalid entry; valid finalized work remains reachable. |
| VM06 | Windows | Planned | VM02, VM04, VM05 | Implement a reusable `waveIn` capture adapter with serialized native control, rooted buffers and delegates, safe completion signaling, and idempotent stop/disposal. | Recording, forced stop, device removal, and late callbacks cause no deadlock, callback-thread UI/native-control misuse, use-after-free, or leak. |
| VM07 | Android | Planned | VM02, VM04, VM05 | Implement a reusable `AudioRecord` adapter and add `RECORD_AUDIO`. Request permission on first use and coordinate recording with Activity lifecycle and the service-owned engine. | Permission outcomes are clear; recording occurs only in the active foreground UI; interruption releases audio resources and preserves any valid finalized draft. |
| VM08 | Windows | Planned | VM05, VM06 | Add Record, elapsed time, Stop, Preview, Delete, Send, and recovery UI. Serialize recording actions with conversation switching, attachment selection, and shutdown. | Nothing queues before Send; duplicate Send is prevented; a draft cannot be sent to the wrong conversation. |
| VM09 | Android | Planned | VM05, VM07 | Add equivalent composer and recovery behavior while preserving the current conversation action layout. Handle rotation, backgrounding, process death, permission changes, and engine binding. | Only one recorder exists; backgrounding stops and safely finalizes; rotation or restart cannot rebind a draft to another conversation. |
| VM10 | Both | Planned | VM03, VM05, VM08, VM09 | Implement transactional Send: allocate message ID, assign marked filename, import into encrypted Normal storage, save the queued message, then clear registry/plaintext state. | Offline Send survives restart; no queued message uses Fast storage or depends on draft plaintext; retries cannot create duplicate sends. |
| VM11 | Both | Planned | VM03, VM10 | Add candidate, fetching, playable, invalid-marked, and unavailable cards. Extend the named Windows and Android auto-image components into equivalent automatic-media schedulers using the fixed next-admitted policy and three-voice-to-one-image fairness rule. | Retry avoids the destination picker; invalid content reuses its internal copy; manual work is next admitted; active work is not preempted; images cannot starve. |
| VM12 | Both | Planned | VM02, VM03, VM04 | Implement validated WAV-to-PCM playback sources and the checked seek converter. | Playback reads only the validated data range; both platforms match shared seek vectors; adversarial seeks cannot overflow, split samples, replay stale buffers, or report impossible positions. |
| VM13 | Windows | Planned | VM06, VM11, VM12 | Implement one active inline player using `waveOut`, with Play/Pause, timing, seek, completion reset, safe device loss, bounded decrypted memory, Save/Export, and keyboard semantics. | Opposite-platform messages play and seek correctly; device removal is safe; playback creates no plaintext temporary file. |
| VM14 | Android | Planned | VM11, VM12 | Implement one active inline player using `AudioTrack`, with audio focus, route handling, Play/Pause, timing, seek, completion, bounded streaming, and SAF Export. | Focus loss pauses safely; lifecycle exit releases resources; touch and accessibility seeking use the validated converter. |
| VM15 | Both | Planned | VM08, VM09, VM13, VM14 | Complete notifications and accessibility: descriptive labels, textual states, adequate targets, focus order, timing announcements, keyboard/accessibility actions, and non-color error communication. | Every supported action is available through keyboard or platform accessibility services; notifications disclose no audio content. |
| VM16 | Both | Planned | VM10-VM15 | Verify backward-tolerant persistence and mixed-version behavior. Avoid destructive history migration and keep new local metadata safely ignorable where required. | Older clients receive ordinary WAV attachments; existing history remains readable; spoofed or arbitrary WAV attachments do not become voice messages. |
| VM17 | Both | Planned | VM01-VM16, VT01-VT11 | Update `PROTOCOL.md`, `PROJECT_STATUS.md`, `windows/STATUS.md`, and `android/STATUS.md` after implementation and verification. | Records separately identify implemented code, automated results, Windows acceptance, Android-device acceptance, interoperability, and deferred call work. |

## Automated testing tasks

| ID | Platform | Status | Dependencies | Required coverage and acceptance |
|---|---|---|---|---|
| VT01 | Both | Planned | VM01, VM03, VM11 | Run the same `tests/voice_messages/vectors/` WAV fixtures through both validators. Test markers, exact limits, overflow, chunks, truncation, forged names, invalid formats, mismatched IDs, and ordinary-card fallback from the existing internal copy. |
| VT02 | Both | Planned | VM02, VM04 | Run the same PCM frame vectors on both platforms. Verify cadence, fragmentation, oversized callbacks, ordering, gaps, timestamps, bounded backpressure, overflow policy, short final frames, discontinuity, restart, and bit-exact output. |
| VT03 | Both | Planned | VM05 | Test every registry crash boundary, the ten-entry limit, 30-day notice, missing conversations, unsendable drafts, Restore/Delete, invalid storage identities, reconciliation, and cleanup safety. |
| VT04 | Windows | Planned | VM06, VM08 | Use fake audio input and lifecycle faults to test device loss, forced stop, repeated actions, conversation changes, shutdown, callback constraints, and native resource release. |
| VT05 | Android | Planned | VM07, VM09 | Test permission grant, denial, permanent denial, initialization failure, short reads, rotation, backgrounding, process death, duration limit, lifecycle stop, and restoration. |
| VT06 | Both | Planned | VM10, VM11 | Test direct and group delivery in both directions, Offline queueing, restart, reconnect, relay, internal fetch, interrupted resume, integrity failure, invalid fallback, and deduplication. |
| VT07 | Both | Planned | VM11-VM14 | Run common seek vectors and platform scheduling scenarios. Verify playback behavior, one-player policy, boundary/adversarial seeks, output invalidation, named scheduler integration, fixed non-preemptive next admission, manual-first behavior, three-voice-to-one-image fairness, counter rules, and deterministic restart rebuilding. |
| VT08 | Both | Planned | VM13-VM15 | Test keyboard/accessibility actions, focus order, labels, timing announcements, notifications, unavailable/invalid states, and non-color status communication. |
| VT09 | Both | Planned | VM10-VM14 | Test low storage, bounded memory, malformed input, device restart/removal, transfer concurrency, retention changes, manual-download priority, and absence of plaintext playback files. |
| VT10 | Both | Planned | VM16 | Regression-test ordinary attachments, destination downloads, images, Fast transfers, group relay/expiry, clear/delete, Offline lifecycle, pagination, Android upload budgets, and notifications. Run the complete `tests/run.ps1` suite. |
| VT11 | Both | Planned | VM02, VM04, VM12 | Enforce architecture boundaries so PCM code imports no LM4, WAV-container, attachment, UI, call-signaling, or video dependencies. Verify both platform test runners consume the shared fixture directory and reject divergent local copies. |

## Manual acceptance

### Windows

- Record, stop, preview, delete, send, play, pause, seek, resume, and export.
- Remove or disable input/output devices during use and confirm safe finalization or termination.
- Exit after finalization but before Send, restart, and restore or delete the draft.
- Remove the owning contact or leave the owning group before restart and verify Preview/Delete-only recovery.
- Fill the registry to ten drafts and verify only new recording is blocked.
- Send while Offline, restart, reconnect, and deliver successfully.
- Retrieve spoofed marked content and verify ordinary-file fallback without a second fetch.
- Saturate transfers, request a manual download, and confirm it is next admitted without interrupting active work.
- Confirm handles, delegates, buffers, and players are released after repeated cycles.

### Android physical devices

- Confirm and test the supported minimum and current target Android API during VM01.
- Verify permission grant, denial, permanent denial, and Settings recovery.
- Test rotation, process death, lock, backgrounding, foreground return, audio focus, route changes, device errors, and low storage.
- Confirm backgrounding stops or finalizes recording and introduces no background microphone service.
- Confirm the microphone privacy indicator appears only during active recording on supported Android versions.
- Force-stop after finalization and verify recovery without opening the microphone.
- Exercise touch and accessibility seeking.
- Play and export Windows-recorded messages.
- Saturate transfers, request a manual download, and confirm identical next-admitted behavior.

### Two-device LAN interoperability

- Send direct and group voice messages in both directions.
- Test sender and receiver Offline transitions, Wi-Fi interruption, restart, and process termination.
- Confirm drafts remain local until explicit Send.
- Confirm queued sends survive restart.
- Confirm resumable retrieval produces exactly one valid result.
- Relay a completely retrieved group voice message.
- Seek opposite-platform recordings at the beginning, middle, last sample, and end.
- Confirm clear, deletion, expiry, and retention cleanup stop playback and remove complete and partial internal copies.
- Confirm an older client treats the marked item as an ordinary WAV attachment.
- Confirm voice Retry never opens the destination picker.
- Confirm ordinary manual downloads receive the next available admission without cancelling active work.
- Queue voice and image backlogs and verify the three-to-one fairness rule on both platforms.

## Deferred roadmap to video calls

Each later phase requires its own detailed plan and explicit execution authorization.

| ID | Platform | Status | Dependencies | Deferred phase | Entry gate |
|---|---|---|---|---|---|
| CALL01 | Both | Deferred | VM01-VM17, VT01-VT11 | **Authenticated call control:** capability discovery, identity-bound invites, ring/accept/reject/cancel/end, session IDs, replay protection, collision handling, timeout, missed-call metadata, Offline behavior, consent, and mixed-version fallback. | A reviewed signaling state machine and threat model cover duplicates, reordering, simultaneous calls, restart, disconnect, replay, incompatible peers, and consent before media starts. |
| CALL02 | Both | Deferred | CALL01 and reusable VM02/VM04/VM06/VM07/VM12-VM14 boundaries | **Live voice calls:** codec negotiation, authenticated encrypted real-time transport, jitter buffering, loss handling, clock drift, echo control, route changes, reconnect policy, Android call-service lifecycle, bandwidth control, and call UI. | Cross-platform calls pass security, latency, loss, interruption, route-change, lifecycle, accessibility, and physical-LAN acceptance without regressing voice messages. |
| CALL03 | Both | Deferred | CALL02 | **Video calls:** camera consent and lifecycle, preview, video negotiation, encrypted transport, rendering, A/V synchronization, camera switching, adaptive bitrate, thermal/battery handling, privacy indicators, and audio-only fallback. | Windows-to-Android and Android-to-Windows calls pass privacy, consent, synchronization, adaptation, reconnect, resource-release, accessibility, and physical-device acceptance. |

### Roadmap guardrails

- CALL01 is completed and reviewed before live media is added.
- CALL02 passes cross-platform acceptance before video work begins.
- CALL03 reuses the authenticated session and audio path instead of creating a parallel calling system.
- The PCM-frame contract may be reused, but voice-message attachment buffering and scheduling are not call jitter-buffer logic.
- No call frames, codecs, services, permissions, UI, or speculative video types are added during the voice-message phase.
- Every future wire change requires both implementations to be reviewed and interoperability-tested.
- Camera permission is not requested until the video-call phase.
- Microphone permission for voice messages does not imply consent to a future live call.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Plaintext recovery data survives a crash | Use application-private storage, bounded registry entries, encrypted import before cleanup, explicit recovery actions, and documented best-effort deletion limitations. |
| A draft loses its conversation | Retain its original owner identity and expose it globally as Preview/Delete-only without retargeting. |
| A forged filename produces misleading playback UI | Treat the marker as candidate metadata only and require complete integrity and WAV validation before playback. |
| Platform media behavior diverges | Make shared golden vectors normative and require both platform suites to load the same versioned fixtures. |
| Automatic retrieval starves manual work or images | Extend the named existing schedulers with fixed non-preemptive next admission, manual-first ordering, and bounded three-to-one voice/image fairness. |
| Scheduling behavior varies at runtime | Prohibit the pause/preempt branch; both platforms use and test the same next-admitted policy. |
| Windows native callbacks deadlock or outlive managed data | Restrict callbacks to completion signaling, serialize native control calls, root in-flight objects, and make shutdown idempotent. |
| Android records unexpectedly in the background | Bind recording to active foreground UI and stop/finalize on lifecycle loss; do not introduce a microphone foreground service. |
| Crash during Send duplicates or loses a message | Use ordered durable state transitions and idempotent reconciliation around encrypted import and queued-message persistence. |
| Seeking reads outside validated audio | Use a checked converter constrained to the validated data chunk and invalidate stale output after every seek. |
| Call readiness expands this phase uncontrollably | Restrict reuse to clean media boundaries and keep signaling, live transport, codecs, and video explicitly deferred. |
| Future video diverges from voice-call security | Require video to reuse the authenticated session, consent model, encryption, and live-audio ownership established by CALL01 and CALL02. |

## Completion gates

The voice-message phase is complete only when:

1. VM01-VM17 satisfy their acceptance criteria.
2. VT01-VT11 and the complete regression suite pass.
3. Both platforms consume and pass the same versioned golden WAV, PCM-frame, and seek vectors.
4. Windows and Android implement the same stored-format, receiver-state, recovery, scheduling, and compatibility contracts.
5. Direct and group messages work in both cross-platform directions.
6. Nothing is transmitted before explicit Send.
7. Offline Send, restart, reconnect, delivery, retrieval, playback, relay, clear, and expiry pass.
8. Crash testing produces no duplicate sends and no queued message dependent on draft plaintext.
9. Valid finalized drafts remain recoverable and are never silently deleted because of age.
10. Unsendable drafts remain Preview/Delete-only and cannot be retargeted.
11. Invalid marked content becomes an ordinary attachment backed by its already retrieved copy.
12. Manual requests are next admitted when capacity becomes available, active transfers are not priority-preempted, and automatic images cannot starve.
13. Capture, playback, and automatic retrieval queues remain bounded.
14. Windows device removal and late callbacks produce no deadlocks, use-after-free, or resource leaks.
15. Android physical-device testing confirms foreground-only recording and correct privacy behavior.
16. Seeking remains inside validated aligned PCM data under adversarial input.
17. Older clients receive the message as an ordinary WAV attachment.
18. No live-call or video behavior is introduced.
19. Status records distinguish source implementation, automated verification, Windows acceptance, Android-device acceptance, and two-device interoperability.
20. Builds, commits, release packaging, pushing, CALL01, CALL02, and CALL03 occur only after their applicable explicit approvals.

## Recommended execution sequence

1. Approve and freeze VM01.
2. Define the shared frame contract and create the versioned golden-vector structure through VM02.
3. Implement WAV validation, frame normalization, shared fixtures, and draft recovery through VM03-VM05.
4. Complete VT01-VT03 before platform UI work.
5. Implement Windows and Android capture adapters and composer flows through VM06-VM09.
6. Complete VT04-VT05.
7. Implement transactional Send and the named platform scheduler extensions through VM10-VM11.
8. Complete VT06 and the scheduling portion of VT07.
9. Implement playback, seeking, accessibility, and notifications through VM12-VM15.
10. Complete the remaining VT07 coverage and VT08-VT09.
11. Finish compatibility work in VM16.
12. Run VT10 and VT11 only after VM16 is complete.
13. Perform Windows, Android-device, and two-device LAN acceptance.
14. Update protocol and status records through VM17.
15. Close the voice-message phase before separately planning and authorizing CALL01.
16. Progress in order from authenticated call control to live voice calls and finally video calls.
