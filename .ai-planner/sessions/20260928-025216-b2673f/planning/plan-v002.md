# plan-v002 | version=2 | Voice Messages split into Android, Windows, and Integration phases

## Goal

Deliver reliable Voice Messages in three execution phases:

1. Android
2. PC (Windows)
3. Android ↔ Windows Integration

The work includes recording, preview, explicit Send, encrypted attachment delivery, automatic retrieval, inline playback, seeking, crash recovery, accessibility, and direct/group conversations. It establishes reusable audio-device and PCM-frame boundaries without implementing call signaling, real-time call media, or video.

All implementation, testing, documentation, build, release, commit, and push work is **Planned**. Execution of each phase requires explicit user authorization.

## Current project context

- Scope: Android and Windows, followed by cross-platform interoperability.
- Both platforms currently support encrypted ordinary attachments, offline queues, resumable transfers, direct conversations, group conversations, and interoperability.
- Voice Messages reuse the existing Normal encrypted attachment path; no new LM4 frame is introduced.
- Android networking remains service-owned, while recording remains foreground-UI-only.
- Windows may remain active in the tray, but recording stops when its recording UI is no longer active.
- Windows automatic image retrieval currently begins in `PeerEngine.QueueImageDownloads()` in `windows/Transfers.cs`, using `imageSlots`, `imageAttempts`, and `DownloadAttachmentAsync()`.
- Android automatic image retrieval currently begins in `TransferManager.queueImageDownloads()` in `android/src/net/lanmsg/chat/TransferManager.java`, using `PeerEngine.imageSlots`, `imageAttempts`, `TransferManager.downloadAttachment()`, and `ResumableTransfer`.
- No platform phase may claim interoperability before Phase 3 passes.
- Status records must distinguish implemented code, automated verification, manual/device acceptance, and interoperability.

## Fixed product contract

### User flow

1. The user opens a direct or group conversation.
2. The user selects **Record voice message**.
3. Recording begins only after microphone permission and device initialization succeed.
4. The composer shows elapsed time and a clear Stop action.
5. Recording stops explicitly or safely on a required lifecycle interruption.
6. The finalized recording can be Previewed, Deleted, or Sent.
7. Nothing leaves the device before explicit Send.
8. Send queues the recording through the existing encrypted Normal attachment system.
9. An Online receiver automatically retrieves eligible Voice Messages.
10. A validated message provides inline Play/Pause, time, seeking, Retry or Resume when appropriate, and a separate Save/Export action.

### Format and limits

- Maximum recording duration: **5 minutes**.
- Stored format: RIFF/WAVE, **16 kHz**, signed **16-bit PCM**, mono.
- Maximum PCM data: **9,600,000 bytes**.
- Maximum canonical WAV size: **9,600,044 bytes**.
- Target PCM frame: **20 ms**, 320 samples, 640 bytes.
- Marked filename: `voice-<message-id>.lanvoice.wav`.
- Voice Messages always use the encrypted Normal attachment store.
- They never use Fast storage or original-file references.
- Direct and group conversations are supported.
- Only one recorder and one active player may exist per application instance.
- Existing retention, authorization, relay, clear-chat, deletion, and expiry rules continue to apply.

### Compatibility

A Voice Message remains an ordinary attachment whose filename is a voice candidate marker. Playback controls appear only after complete retrieval, integrity verification, and WAV validation.

Older clients receive an ordinary WAV attachment. A forged marker, arbitrary WAV file, mismatched message ID, malformed payload, unsupported encoding, oversized recording, or truncated file never gains playable voice controls.

### Lifecycle requirements

Recording must be safely stopped and finalized, when possible, on:

- Explicit Stop.
- Conversation exit or switch.
- Android backgrounding or lifecycle loss.
- Windows recording-UI closure.
- Offline transition.
- Application shutdown.
- Audio input device loss.
- Five-minute duration limit.

Recording remains foreground-UI-only. No microphone foreground service or background capture is introduced.

## Shared contract gates

### Phase 3a — Pre-phase shared contract

Although these tasks are integration-owned, **I01-I04 run before Phase 1 or Phase 2 implementation**. They freeze the common contract and shared fixtures. After I01-I04 pass, Android and Windows may be separately authorized and implemented in either order or in parallel.

| ID | Platform | Status | Dependencies | Task and notes | Acceptance criteria |
|---|---|---|---|---|---|
| I01 | Both | Planned | None | Freeze the product, format, storage, lifecycle, compatibility, receiver-state, draft-recovery, transactional-send, and scheduler contracts recorded in this plan. | No unresolved behavioral choice is delegated to platform implementation. |
| I02 | Both | Planned | I01 | Define the transport-independent PCM frame and stream-event contract, including sequence, timestamps, ownership, bounded backpressure, deterministic overflow, end, discontinuity, device failure, and restart. | Both capture and playback adapters can use the contract without importing WAV, LM4, attachment, UI, call-signaling, or video dependencies. |
| I03 | Both | Planned | I02 | Establish the versioned shared fixture directory and manifest under `tests/voice_messages/vectors/`. Platform-local fixture copies are prohibited. | Both platform runners can load the same manifest and source files directly. |
| I04 | Both | Planned | I01-I03 | Freeze marker recognition, WAV validation, receiver transitions, checked seeking, registry states, durable Send ordering, crash outcomes, and stable failure categories. | Identical inputs have identical classification, recovery, seek, persistence, and scheduling expectations on both platforms. |

## Normative shared media contract

### PCM-frame properties

| Property | Required contract |
|---|---|
| Audio format | 16 kHz, signed 16-bit PCM, mono |
| Target duration | 20 ms |
| Target samples | 320 |
| Target bytes | 640 |
| Sequence | Monotonically increasing within one stream |
| Timestamp | Monotonic media time relative to stream start |
| Payload | Complete two-byte samples only |
| Final frame | May be shorter than 640 bytes but must contain complete samples |
| Stream events | End, discontinuity, device failure, and restart are explicit |
| Ownership | Immutable data or bounded ownership transfer; native callback buffers are not exposed to shared logic |
| Backpressure | Queues are bounded and use one deterministic overflow policy frozen by I02 |
| Restart | Begins a clearly identified stream epoch and resets sequence/timestamp state as defined by I02 |

Device callbacks may be fragmented, exact-sized, or oversized. Adapters assemble or split them without sample loss, duplication, partial samples, unbounded buffering, or reuse of mutable callback storage.

### Golden-vector manifest

The versioned machine-readable manifest must identify:

- Fixture contract version and compatible test-runner version.
- Fixture ID, category, and source file.
- Input byte length, checksum, and declared callback fragmentation where applicable.
- Expected WAV classification and stable validation failure category.
- Expected normalized PCM payloads or payload checksums.
- Expected frame sequence numbers, timestamps, lengths, and stream events.
- Expected seek request, clamped time, aligned byte position, and effective UI position.
- Expected receiver-state transition sequence where applicable.
- Exact-limit and maximum-boundary expectations.

The shared fixture set must include:

- Empty, one-sample, one-frame, short-final-frame, and maximum-boundary valid WAVs.
- Fragmented, exact-frame, oversized, and discontinuous PCM callback sequences.
- Expected normalized frames, timestamps, sequences, final lengths, and events.
- Beginning, middle, last-sample, end, negative, oversized, and arithmetic-overflow seek inputs.
- Truncated, invalid-size, overflow, reordered, duplicate-chunk, unknown-chunk, bad-alignment, unsupported-format, trailing-data, and oversized WAVs.
- Forged markers, mismatched IDs, unmarked WAVs, and arbitrary ordinary attachments.

A fixture change is a Both-platform contract change and requires review.

### WAV validation checklist

A recovered or received candidate becomes playable only after bounded streaming validation confirms:

- RIFF and WAVE identifiers.
- Checked chunk-offset, padding, boundary, and size arithmetic without overflow.
- Supported PCM format.
- Exactly one channel, 16 kHz, and 16 bits per sample.
- Correct block alignment and byte rate.
- Complete two-byte sample alignment.
- A bounded `data` chunk.
- No truncation, overlap, or out-of-range read.
- Maximum duration and stored-size limits.
- Duration consistency with the validated data length, byte rate, and sample alignment.
- Unknown chunks are skipped only through checked sizes and RIFF padding.
- Duplicate required `fmt ` or `data` chunks are rejected.
- Reordered required chunks are handled according to the I04 fixture contract without unbounded buffering; both platforms must return the same result.
- Trailing bytes and recognized optional chunks receive the stable behavior defined by I04 and the manifest.

Candidate classification remains separate from content validation.

### Receiver states

| State | Required behavior |
|---|---|
| Candidate | A marked trusted offer; show pending voice UI without claiming the item is playable |
| Fetching | Show internal retrieval progress and retain existing cancel/retry semantics |
| Playable | Complete, integrity-verified, and WAV-validated; enable playback and Save/Export |
| Invalid marked content | Replace voice UI with an ordinary attachment card backed by the already retrieved internal copy; do not fetch again |
| Unavailable | Explain expiry, authorization, source, membership, or storage failure without enabling playback |

Voice Retry and Resume are internal retrieval operations and never open a destination picker. Save/Export is a separate action available after content becomes available.

### Seven-step seek procedure

Every seek operation must:

1. Clamp the requested time to the validated duration.
2. Convert time using checked arithmetic.
3. Resolve the byte position within the validated WAV `data` range.
4. Align downward to a complete two-byte sample.
5. Invalidate output queued from the old position.
6. Reset playback sequence and media-timestamp state.
7. Report the effective aligned position to the UI.

Seeking to the end produces the completed state.

## Draft recovery and transactional Send contract

### Registry rules and fields

The durable registry resides only in application-private storage.

- Maximum: **10 active or finalized drafts application-wide**.
- Reaching the limit blocks only creation of another recording.
- Existing drafts remain available for Preview, Send, Delete, reconciliation, or recovery.
- A valid finalized draft receives a stale-review indication after **30 days**.
- Age alone never silently deletes a valid finalized draft.
- Every draft remains bound to its original conversation.
- A draft whose conversation no longer permits sending is Preview/Delete-only.
- Unsendable drafts are never retargeted.
- Registry data may not introduce arbitrary external filesystem paths.

Every entry records:

- Opaque draft ID.
- Owning conversation identity.
- Conversation type.
- Created and updated timestamps.
- Validated application-private storage identity.
- Recording/finalization state.
- Expected audio format.
- Bounded byte-size and duration metadata.
- Temporary Send-transaction association.
- Stale-review state where applicable.

### Ten-step durable write order

1. Create and durably register draft ownership.
2. Start accepting microphone frames.
3. Write normalized PCM to application-private storage.
4. Safely finalize the WAV when recording stops.
5. Flush and validate the completed file.
6. Mark the draft recoverable only after successful finalization.
7. Allocate the message ID and final marked filename on Send.
8. Import the complete WAV into the encrypted Normal store.
9. Durably save the queued message.
10. Remove the registry entry and best-effort delete plaintext only after the encrypted source and queued message are durable.

At every simulated crash boundary, reconciliation must produce exactly one allowed outcome:

1. A valid recoverable draft.
2. A durable queued message with a durable encrypted source.
3. A diagnosed invalid entry that cannot be sent or played.

It must never produce a duplicate Send, an unreachable valid draft, or a queued message dependent on deleted plaintext.

## Fixed retrieval scheduler contract

Both platforms extend their existing automatic-image admission component into a bounded automatic-media scheduler. They use the same fixed **next-admitted, non-preemptive** rules:

1. User-initiated attachment downloads and resumes are considered first whenever capacity next becomes available.
2. Automatic Voice Message retrieval is considered next.
3. Automatic image retrieval is considered last.
4. After three consecutive automatic voice admissions while an eligible image waits, the next automatic admission is an image.
5. Active transfers are never paused or cancelled merely because a higher-priority request arrives.
6. A manual request arriving while all capacity is occupied becomes the next eligible admission after a slot is released.
7. Fairness counters advance only on successful admission, not when an offer is scanned, rejected, duplicated, expired, or unreachable.
8. Offline transition, cancellation, clearing, expiry, and authorization loss retain their existing interruption semantics.
9. Restart reconstructs eligible automatic work from durable offers and partial-transfer state without duplicating active or completed retrieval.

There is no runtime choice between preemption and next-admitted behavior.

# Phase 1 — Android

## Objective

Implement and locally verify the complete Android Voice Message experience without adding background microphone capture or changing the LM4 wire protocol.

## Android implementation tasks

| ID | Status | Dependencies | Implementation notes | Acceptance criteria |
|---|---|---|---|---|
| A01 | Planned | I01-I04 | Add marker recognition, bounded streaming WAV validation, PCM normalization, WAV sink/source boundaries, and checked seek conversion. Load shared fixtures directly. | Android matches all shared validation, framing, event, timestamp, receiver-state, and seek expectations. |
| A02 | Planned | A01 | Add the durable application-private registry, atomic state transitions, ten-entry enforcement, stale-review state, validated storage identities, and startup reconciliation. | Every crash boundary ends in one of the three allowed outcomes; valid finalized drafts remain reachable. |
| A03 | Planned | A01, A02 | Implement a reusable `AudioRecord` adapter and add `RECORD_AUDIO`. Normalize native reads into bounded frames and expose explicit stream events. | Permission and initialization failures are clear; short reads and errors are safe; one-recorder enforcement and resource release pass. |
| A04 | Planned | A03 | Add Record, elapsed time, Stop, Preview, Delete, Send, and recovery UI while preserving the conversation action layout. Coordinate Activity lifecycle and the service-owned engine. Force safe stop/finalization on conversation exit, backgrounding, Offline transition, application shutdown, device failure, and duration limit. | Rotation, rebinding, process death, lifecycle loss, Offline transition, or conversation switching cannot lose, continue, or retarget a valid draft. |
| A05 | Planned | A02, A04 | Implement transactional Send using the ten-step durable write order. | Nothing queues before Send; Offline Send survives restart; duplicate Send is prevented; queued messages use Normal storage and never depend on draft plaintext. |
| A06 | Planned | A01, A05 | Extend `TransferManager.queueImageDownloads()`, `PeerEngine.imageSlots`, and `imageAttempts` into the fixed automatic-media scheduler. Continue retrieval through `TransferManager.downloadAttachment()` and `ResumableTransfer`. | All nine admission rules pass, including manual-next admission, voice/image fairness, existing interruption semantics, and deterministic restart. |
| A07 | Planned | A01, A06 | Add Candidate, Fetching, Playable, Invalid marked content, and Unavailable cards. Keep Retry/Resume internal. | Each receiver state follows the shared table; invalid content falls back without a second fetch. |
| A08 | Planned | A01, A07 | Implement one active inline player using `AudioTrack`, including focus, routes, Play/Pause, timing, seven-step seeking, completion, bounded streaming, and SAF Export. | Focus loss and lifecycle exit are safe; no plaintext playback file is created; seeking stays in validated aligned PCM. |
| A09 | Planned | A04, A07, A08 | Complete labels, textual states, touch targets, focus order, announcements, accessible seeking, notification privacy, and non-color errors. | Every supported action is accessible and state remains understandable without color alone. |
| A10 | Planned | A01-A09 | Verify persistence compatibility and regressions covering attachments, images, Fast transfers, groups, expiry, clear/delete, Offline lifecycle, upload budgets, pagination, and notifications. | Existing Android behavior remains operational and older clients can treat Voice Messages as ordinary WAV attachments. |
| A11 | Planned | A01-A10, AT01-AT06, Android manual acceptance | Update `android/STATUS.md` at the end of the Android phase. Record implemented code, automated verification, and physical-device/manual acceptance as separate facts. Mark Android ↔ Windows interoperability **not yet verified**. | The Android status accurately supports handoff even if Phase 2 or Phase 3 is not authorized. |

## Android automated testing

| ID | Status | Dependencies | Required coverage |
|---|---|---|---|
| AT01 | Planned | A01 | Run shared WAV, PCM, stream-event, receiver-state, and seek vectors, including exact limits, fragmented/oversized reads, discontinuities, overflow attempts, malformed chunks, duplicates, unknown chunks, truncation, bad alignment, and unsupported formats. |
| AT02 | Planned | A02 | Test every registry and durable-Send crash boundary, ten-entry behavior, stale review, invalid storage identities, missing conversations, Preview/Delete-only drafts, Restore/Delete, and reconciliation. |
| AT03 | Planned | A03, A04 | Test permission grant, denial, permanent denial, Settings recovery, initialization failure, short reads, rotation, backgrounding, Offline-transition forced stop, conversation exit, shutdown, process death, duration limit, and repeated stop/dispose. |
| AT04 | Planned | A05-A07 | Test direct/group queueing, Offline restart, reconnect, interrupted internal retrieval, resume, integrity failure, fallback, all nine scheduler rules, fairness counters, and deduplication. |
| AT05 | Planned | A08, A09 | Test one-player enforcement, focus loss, route changes, device errors, lifecycle exit, completion, adversarial seeks, export, accessibility, and notification privacy. |
| AT06 | Planned | A10 | Run Android tests and the relevant complete regression suite before physical-device acceptance. |

## Android manual acceptance

- Confirm the supported minimum and current target Android API.
- Verify permission grant, denial, permanent denial, and Settings recovery.
- Record, stop, preview, delete, send, play, pause, seek, resume, and export.
- Test rotation, lock, backgrounding, foreground return, process death, force-stop, low storage, audio focus, routes, and device errors.
- Enter Offline while recording and confirm safe forced stop/finalization.
- Send while Offline, restart, reconnect, and confirm delivery.
- Confirm backgrounding stops/finalizes recording and no microphone foreground service exists.
- Confirm the microphone privacy indicator appears only during active recording.
- Force-stop after finalization and recover without reopening the microphone.
- Delete the owning contact or leave the group and verify Preview/Delete-only recovery.
- Fill the registry to ten drafts and verify only new recording is blocked.
- Exercise touch and accessibility seeking.
- Retrieve spoofed marked content and verify fallback without a second fetch.
- Saturate transfers and verify all fixed next-admission and fairness rules.
- Record any item not physically exercised as **not verified**, not passed.

## Android phase exit gate

Phase 1 is complete only when:

- I01-I04 are frozen.
- A01-A10 and AT01-AT06 pass.
- Android physical-device/manual acceptance results are recorded.
- A11 updates `android/STATUS.md`.
- Interoperability is explicitly marked **not yet verified**.

# Phase 2 — PC (Windows)

## Objective

Implement and locally verify the complete Windows Voice Message experience, including safe native callback handling and tray-process lifecycle behavior.

## Windows implementation tasks

| ID | Status | Dependencies | Implementation notes | Acceptance criteria |
|---|---|---|---|---|
| W01 | Planned | I01-I04 | Add marker recognition, bounded streaming WAV validation, PCM normalization, WAV sink/source boundaries, and checked seek conversion. Load shared fixtures directly. | Windows matches all shared validation, framing, event, timestamp, receiver-state, and seek expectations. |
| W02 | Planned | W01 | Add the durable application-private registry, atomic state transitions, ten-entry enforcement, stale-review state, validated storage identities, and startup reconciliation. | Every crash boundary ends in one of the three allowed outcomes; valid finalized drafts remain reachable. |
| W03 | Planned | W01, W02 | Implement a reusable `waveIn` adapter with serialized native control, rooted delegates/buffers, bounded frame delivery, safe completion signaling, and idempotent stop/disposal. | Device loss and late callbacks cause no deadlock, callback-thread misuse, use-after-free, or leak. |
| W04 | Planned | W03 | Add Record, elapsed time, Stop, Preview, Delete, Send, and recovery UI. Serialize actions with conversation switching, attachment selection, Offline transition, UI closure, shutdown, device failure, and duration limit. | Leaving the recording UI or entering Offline safely stops/finalizes; drafts cannot move between conversations. |
| W05 | Planned | W02, W04 | Implement transactional Send through encrypted Normal storage using the ten-step durable write order. | Offline Send survives restart; no queued message uses Fast storage or depends on plaintext; reconciliation cannot duplicate Send. |
| W06 | Planned | W01, W05 | Replace image-only admission around `PeerEngine.QueueImageDownloads()`, `imageSlots`, and `imageAttempts` in `windows/Transfers.cs` with the fixed automatic-media scheduler. Continue retrieval through `PeerEngine.DownloadAttachmentAsync()`. | All nine admission rules pass, including manual-next admission, voice/image fairness, existing interruption semantics, and deterministic restart. |
| W07 | Planned | W01, W06 | Add Candidate, Fetching, Playable, Invalid marked content, and Unavailable cards with internal Retry/Resume and separate Save/Export. | Each receiver state follows the shared table; invalid content reuses its retrieved copy. |
| W08 | Planned | W01, W07 | Implement one active inline player using `waveOut`, including Play/Pause, timing, seven-step seeking, completion reset, safe device loss, bounded decrypted memory, and Save/Export. | No plaintext playback temporary file is created; stale buffers cannot play after seeking. |
| W09 | Planned | W04, W07, W08 | Add keyboard and accessibility semantics, labels, textual states, focus order, timing announcements, notification privacy, and non-color errors. | All supported actions are keyboard-accessible and screen-reader meaningful. |
| W10 | Planned | W01-W09 | Verify persistence compatibility and regressions covering attachments, images, Fast transfers, groups, relay, expiry, clear/delete, Offline lifecycle, pagination, and notifications. | Existing Windows behavior remains operational and older clients can treat Voice Messages as ordinary WAV attachments. |
| W11 | Planned | W01-W10, WT01-WT06, Windows manual acceptance | Update `windows/STATUS.md` at the end of the Windows phase. Record implemented code, automated verification, and manual acceptance separately. Mark Android ↔ Windows interoperability **not yet verified**. | The Windows status accurately supports handoff even if Phase 1 or Phase 3 is not authorized. |

## Windows automated testing

| ID | Status | Dependencies | Required coverage |
|---|---|---|---|
| WT01 | Planned | W01 | Run shared WAV, PCM, stream-event, receiver-state, and seek vectors, including exact limits, fragmented/oversized callbacks, discontinuities, overflow attempts, malformed chunks, duplicates, unknown chunks, truncation, bad alignment, and unsupported formats. |
| WT02 | Planned | W02 | Test every registry and durable-Send crash boundary, ten-entry behavior, stale review, invalid storage identities, missing conversations, Preview/Delete-only drafts, Restore/Delete, and reconciliation. |
| WT03 | Planned | W03, W04 | Use fake audio input and lifecycle faults to test repeated actions, conversation changes, Offline-transition forced stop, UI closure, shutdown, device loss, callback restrictions, late callbacks, and native resource release. |
| WT04 | Planned | W05-W07 | Test direct/group queueing, Offline restart, reconnect, interrupted internal retrieval, resume, integrity failure, fallback, all nine scheduler rules, fairness counters, and deduplication. |
| WT05 | Planned | W08, W09 | Test one-player enforcement, completion, output-device loss, adversarial seeks, keyboard/accessibility actions, export, bounded memory, and notification privacy. |
| WT06 | Planned | W10 | Run Windows tests and the relevant complete regression suite before manual acceptance. |

## Windows manual acceptance

- Record, stop, preview, delete, send, play, pause, seek, resume, and export.
- Disable or remove input/output devices during use.
- Enter Offline while recording and confirm safe forced stop/finalization.
- Exit after finalization but before Send, restart, and restore or delete the draft.
- Delete the owning contact or leave the group and verify Preview/Delete-only recovery.
- Fill the registry to ten drafts and verify only new recording is blocked.
- Send while Offline, restart, reconnect, and deliver.
- Retrieve spoofed marked content and verify fallback without a second fetch.
- Saturate transfers and verify all fixed next-admission and fairness rules.
- Repeat capture/playback cycles and confirm handles, delegates, buffers, and players are released.
- Record any item not manually exercised as **not verified**, not passed.

## Windows phase exit gate

Phase 2 is complete only when:

- I01-I04 are frozen.
- W01-W10 and WT01-WT06 pass.
- Windows manual acceptance results are recorded.
- W11 updates `windows/STATUS.md`.
- Interoperability is explicitly marked **not yet verified**.

# Phase 3 — Android ↔ Windows Integration

## Objective

Prove bidirectional Android/Windows delivery and playback using the frozen shared contracts, then document compatibility and interoperability results.

I01-I04 are the pre-phase gates defined above. Runtime interoperability tasks I05-I09 cannot begin until **both complete platform exit gates**, including automated tests, manual/device acceptance, and platform status updates, have passed.

## Integration tasks

| ID | Status | Dependencies | Task and notes | Acceptance criteria |
|---|---|---|---|---|
| I05 | Planned | Android phase exit gate, Windows phase exit gate | Compare both platforms against every shared fixture and stable failure category. | Outputs have bit-exact normalized PCM and equivalent metadata, validation, receiver transitions, events, timestamps, and seek positions. |
| I06 | Planned | Android phase exit gate, Windows phase exit gate, I05 | Verify direct and group Voice Messages in both directions through the encrypted attachment path. | Explicit Send, Offline queueing, restart, retrieval, playback, seeking, export, and exactly-one completion pass in both directions. |
| I07 | Planned | Android phase exit gate, Windows phase exit gate, I05 | Run identical scheduler workloads on both platforms. | All nine admission rules produce equivalent results and deterministic restart reconstruction. |
| I08 | Planned | Android phase exit gate, Windows phase exit gate, I06 | Verify relay, retention, authorization, membership changes, clear/delete, expiry, and interrupted resumable retrieval. | Playback stops when content becomes invalid or removed; complete and partial internal copies follow existing cleanup rules; relay yields one valid result. |
| I09 | Planned | Android phase exit gate, Windows phase exit gate, I06-I08 | Verify mixed-version behavior and hostile marked content. | Older clients receive ordinary WAV attachments; forged, arbitrary, malformed, oversized, unsupported, or truncated content never gains playable controls. |
| I10 | Planned | I05-I09, IT01-IT07 | Run full regression and architecture-boundary checks on both platforms. | PCM code has no forbidden dependency; ordinary messaging and transfer behavior remain operational. |
| I11 | Planned | I10, two-device LAN acceptance | Update `PROTOCOL.md` and the comparison/interoperability portions of `PROJECT_STATUS.md`. Record interoperability results and unresolved items. Do not replace the platform-local implementation and acceptance records maintained by A11 and W11. | Protocol and comparison records distinguish shared contract, platform-local results, two-device acceptance, and deferred work. |

## Integration testing

| ID | Status | Dependencies | Required coverage |
|---|---|---|---|
| IT01 | Planned | I05 | Run common WAV, PCM, stream-event, receiver-state, and seek vectors on both platforms and compare all outputs. |
| IT02 | Planned | I06 | Send direct and group messages in both directions, including Offline Send, restart, reconnect, process termination, interruption, resume, and deduplication. |
| IT03 | Planned | I06, I08 | Relay a completely retrieved group message and test retention, expiry, authorization loss, membership changes, clear, and deletion. |
| IT04 | Planned | I06 | Seek opposite-platform recordings at beginning, middle, last sample, and end; verify completion and stale-output invalidation. |
| IT05 | Planned | I07 | Queue manual downloads, Voice Messages, and image backlogs on both platforms and verify all nine scheduler rules. |
| IT06 | Planned | I09 | Test older-client fallback, spoofed markers, mismatched IDs, arbitrary WAVs, malformed chunks, oversized data, unsupported formats, and ordinary-card fallback without another fetch. |
| IT07 | Planned | I10 | Run the complete `tests/run.ps1` regression suite and both platform architecture checks. |

## Two-device LAN acceptance

- Send direct and group Voice Messages in both directions.
- Test sender and receiver Offline transitions, Wi-Fi interruption, restart, and process termination.
- Confirm drafts remain local until explicit Send.
- Confirm queued sends survive restart.
- Confirm resumable retrieval creates exactly one valid result.
- Relay a completely retrieved group Voice Message.
- Seek opposite-platform recordings at beginning, middle, last sample, and end.
- Verify clear, deletion, expiry, retention, authorization loss, and membership changes stop playback and clean complete/partial internal copies under existing rules.
- Verify an older client treats the marked item as an ordinary WAV attachment.
- Confirm Voice Retry and Resume never open a destination picker.
- Confirm a manual request arriving at full capacity is the next admission without cancelling active work.
- Queue voice and image backlogs and verify three-to-one automatic fairness on both platforms.
- Record unperformed cases as **not verified**.

## Integration phase exit gate

Phase 3 is complete only when:

- Both platform exit gates have passed.
- I05-I10 and IT01-IT07 pass.
- Two-device LAN acceptance is recorded.
- I11 updates `PROTOCOL.md` and `PROJECT_STATUS.md`.
- Any unverified or failed item is explicitly recorded rather than implied complete.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Plaintext recovery data survives a crash | Use application-private storage, bounded entries, encrypted import before cleanup, explicit recovery actions, and documented best-effort deletion limits. |
| A draft loses its conversation | Retain its original owner and expose an unsendable draft as Preview/Delete-only without retargeting. |
| A forged filename produces misleading playback UI | Treat the marker only as a candidate and require integrity plus full WAV validation. |
| Platform media behavior diverges | Make shared golden vectors normative and require both runners to consume the same files. |
| Automatic retrieval starves manual work or images | Enforce non-preemptive manual-next admission and bounded three-voice-to-one-image fairness. |
| Scheduler behavior varies at runtime | Prohibit a preemption mode and test the same fixed rules on both platforms. |
| Windows callbacks deadlock or outlive managed data | Restrict callbacks to safe signaling, serialize native control, root in-flight objects, and make shutdown idempotent. |
| Android records in the background | Bind recording to active foreground UI and stop/finalize on lifecycle loss without a microphone foreground service. |
| Crash during Send duplicates or loses a message | Follow the ten-step durable order and reconcile idempotently at every boundary. |
| Seeking escapes validated audio | Use the seven-step checked converter and invalidate stale output after every seek. |
| Call readiness expands Voice Message scope | Reuse only clean adapter/frame boundaries; defer signaling, real-time transport, codecs, and video. |
| Future video diverges from call security | Require video to reuse authenticated sessions, consent, encryption, and audio ownership established by earlier call phases. |

## Deferred roadmap to voice and video calls

Every roadmap phase requires its own detailed plan and explicit execution authorization.

| ID | Platform | Status | Dependencies | Deferred phase | Entry gate |
|---|---|---|---|---|---|
| CALL01 | Both | Deferred | Completed Voice Message phases | Authenticated call control: capability discovery, identity-bound invites, ring/accept/reject/cancel/end, session IDs, replay protection, collision handling, timeout, missed-call metadata, Offline behavior, consent, and fallback. | A reviewed signaling state machine and threat model cover duplicates, reordering, simultaneous calls, restart, disconnect, replay, incompatible peers, and consent before media starts. |
| CALL02 | Both | Deferred | CALL01 and reusable capture/frame/playback boundaries | Live voice calls: codec negotiation, authenticated encrypted transport, jitter buffering, loss handling, clock drift, echo control, routes, reconnect policy, Android call-service lifecycle, bandwidth control, and UI. | Cross-platform calls pass security, latency, loss, interruption, route, lifecycle, accessibility, and physical-LAN acceptance without regressing Voice Messages. |
| CALL03 | Both | Deferred | CALL02 | Video calls: camera consent/lifecycle, preview, negotiation, encrypted transport, rendering, A/V synchronization, camera switching, adaptive bitrate, thermal/battery handling, privacy indicators, and audio-only fallback. | Both directions pass privacy, consent, synchronization, adaptation, reconnect, resource-release, accessibility, and physical-device acceptance. |

### Roadmap guardrails

- CALL01 is completed and reviewed before live media is added.
- CALL02 passes cross-platform acceptance before video work begins.
- CALL03 reuses the authenticated session and audio path rather than creating a parallel calling system.
- The PCM-frame contract may be reused, but attachment buffering and retrieval scheduling are not call jitter-buffer logic.
- No call frames, codecs, services, permissions, UI, or speculative video types are added during Voice Message work.
- Every future wire change requires review of both implementations and interoperability testing.
- Camera permission is not requested before the video-call phase.
- Microphone permission for Voice Messages does not imply consent to a live call.

## Completion criteria

Voice Messages are complete only when:

1. I01-I04 freeze the normative shared contracts.
2. A01-A11, AT01-AT06, and Android manual acceptance pass or accurately record exceptions.
3. W01-W11, WT01-WT06, and Windows manual acceptance pass or accurately record exceptions.
4. I05-I11, IT01-IT07, and two-device LAN acceptance pass.
5. Both platforms consume the same versioned golden WAV, PCM, receiver-state, and seek fixtures.
6. Direct and group delivery works in both directions.
7. Nothing is transmitted before explicit Send.
8. Offline Send, restart, reconnect, retrieval, playback, relay, clear, and expiry pass.
9. Crash tests produce no duplicate Send and no queued message dependent on plaintext.
10. Valid finalized drafts remain recoverable and are not silently deleted because of age.
11. Unsendable drafts remain Preview/Delete-only and cannot be retargeted.
12. Invalid marked content becomes an ordinary attachment backed by its existing internal copy.
13. All nine scheduler rules pass on both platforms.
14. Capture, playback, and retrieval queues remain bounded.
15. Windows device removal and late callbacks cause no deadlock, use-after-free, or leak.
16. Android physical-device testing confirms foreground-only recording and correct privacy behavior.
17. Seeking remains inside validated aligned PCM under adversarial inputs.
18. Older clients receive an ordinary WAV attachment.
19. No live-call or video behavior is introduced.
20. Platform and project status records separately state implementation, automated verification, manual/device acceptance, and interoperability.

## Recommended execution order

1. Authorize and complete I01-I04 as the Phase 3a pre-phase shared-contract gate.
2. Separately authorize Phase 1, Phase 2, or both.
3. For Android, complete A01-A10 and AT01-AT06.
4. Perform Android manual/device acceptance, including Offline forced stop and Offline Send/restart.
5. Complete A11 and close the Android exit gate with interoperability marked not yet verified.
6. For Windows, complete W01-W10 and WT01-WT06.
7. Perform Windows manual acceptance.
8. Complete W11 and close the Windows exit gate with interoperability marked not yet verified.
9. Authorize runtime integration only after both complete platform exit gates pass.
10. Complete I05-I09 and IT01-IT06.
11. Complete I10 and IT07.
12. Perform two-device LAN acceptance.
13. Complete I11 and close the integration exit gate.
14. Plan and authorize CALL01 separately; progress to CALL02 and CALL03 only through their entry gates.
