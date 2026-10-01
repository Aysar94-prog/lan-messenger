# plan-v001 | version=1 | Voice Messages split into Android, Windows, and Integration phases

## Goal

Deliver reliable voice messages on Android and Windows, followed by verified Android ↔ Windows interoperability, while preserving clean media boundaries for future voice and video calls.

Voice messages will support recording, preview, explicit Send, encrypted attachment delivery, automatic retrieval, inline playback, seeking, crash recovery, accessibility, and direct/group conversations. Call signaling, live media transport, and video remain deferred.

## Status

All implementation, testing, documentation, build, release, commit, and push work is **Planned**. Execution requires separate user authorization.

## Fixed product contract

- Maximum recording duration: **5 minutes**.
- Format: RIFF/WAVE, **16 kHz**, signed **16-bit PCM**, mono.
- Maximum valid size: **9,600,044 bytes**.
- Target PCM frame: **20 ms**, 320 samples, 640 bytes.
- Filename: `voice-<message-id>.lanvoice.wav`.
- Storage: existing encrypted **Normal** attachment path only.
- Direct and group conversations are supported.
- Only one recorder and one active player may exist per application instance.
- Nothing is transmitted before explicit Send.
- A filename marker creates only a voice candidate; integrity and WAV validation are required before playback.
- Invalid marked content falls back to an ordinary attachment using the already retrieved copy.
- Older clients receive an ordinary WAV attachment.
- Drafts remain bound to their original conversation and cannot be retargeted.
- Maximum recoverable drafts: **10 application-wide**.
- Finalized drafts older than **30 days** receive a review indication but are not silently deleted.
- Recording remains foreground-UI-only.
- Voice Retry and Resume use internal retrieval; Save/Export is a separate action.
- Automatic retrieval is non-preemptive: manual downloads are next admitted, then voice, then images, with one image admitted after three consecutive voice admissions when an image is waiting.

## Shared dependency gates

These integration-owned gates must be completed before dependent Android or Windows implementation tasks:

- **I01** freezes the common product, storage, lifecycle, validation, scheduling, and compatibility contract.
- **I02** defines the transport-independent PCM frame contract.
- **I03** establishes the shared golden-vector fixtures.
- **I04** defines common WAV validation, seeking, draft-state, and transactional-send behavior.

After I01-I04, Android and Windows implementation may proceed in parallel. Final interoperability work begins after both platform phases pass their local automated gates.

# Phase 1 — Android

## Objective

Implement and locally verify the complete Android voice-message experience without introducing background microphone capture or changing the LM4 wire protocol.

## Tasks

| ID | Status | Dependencies | Implementation notes | Acceptance criteria |
|---|---|---|---|---|
| A01 | Planned | I01-I04 | Add marker recognition, bounded streaming WAV validation, PCM normalization, WAV sink/source boundaries, and checked seek conversion. Consume fixtures directly from `tests/voice_messages/vectors/`. | Android matches every shared validation, framing, event, timestamp, and seek result; malformed or unsupported content never becomes playable. |
| A02 | Planned | A01 | Add an application-private durable draft registry with atomic transitions, ten-entry enforcement, 30-day review state, validated storage identities, and startup reconciliation. | Every simulated crash boundary resolves to a recoverable draft, durable queued message, or diagnosed invalid entry; valid finalized drafts remain reachable. |
| A03 | Planned | A01, A02 | Implement a reusable `AudioRecord` capture adapter and add `RECORD_AUDIO`. Normalize native reads into bounded PCM frames and expose explicit end, discontinuity, failure, and restart events. | Permission or initialization failure is clear; short reads and device errors are safe; only one recorder exists; all audio resources are released. |
| A04 | Planned | A03 | Add Record, elapsed time, Stop, Preview, Delete, Send, and recovery UI while preserving the existing conversation action layout. Coordinate with Activity lifecycle and the service-owned engine. | Backgrounding stops and safely finalizes recording; rotation, rebinding, process death, or conversation switching cannot lose or retarget a valid draft. |
| A05 | Planned | A02, A04 | Implement transactional Send: allocate the message ID, apply the marked filename, import the complete WAV into encrypted Normal storage, persist the queued message, then remove registry/plaintext state. | Nothing queues before Send; Offline Send survives restart; duplicate Send is prevented; no queued message uses Fast storage or depends on draft plaintext. |
| A06 | Planned | A01, A05 | Extend `TransferManager.queueImageDownloads()`, `PeerEngine.imageSlots`, and `imageAttempts` into a bounded automatic-media scheduler. Continue retrieval through `TransferManager.downloadAttachment()` and `ResumableTransfer`. | Manual work is next admitted, active work is not preempted, voice precedes images, the three-voice-to-one-image rule prevents starvation, and restart creates no duplicate retrieval. |
| A07 | Planned | A01, A06 | Add candidate, fetching, playable, invalid-marked, and unavailable message cards. Retry and Resume must remain internal operations. | Invalid content falls back without a second fetch; unavailable content cannot play; expiry, authorization, storage, and membership failures are explained. |
| A08 | Planned | A01, A07 | Implement one active inline player using `AudioTrack`, including audio focus, route changes, Play/Pause, timing, checked seeking, completion, and bounded streaming. Add SAF Export. | Focus loss pauses safely; lifecycle exit releases resources; no plaintext playback file is created; seeking stays inside validated aligned PCM data. |
| A09 | Planned | A04, A07, A08 | Complete accessibility and notification behavior: labels, textual states, adequate touch targets, focus order, announcements, accessible seeking, and non-color errors. | Every supported action is accessible; notifications reveal no audio content; state is understandable without color alone. |
| A10 | Planned | A01-A09 | Verify backward-tolerant persistence and regressions affecting attachments, images, Fast transfers, groups, expiry, clear/delete, Offline lifecycle, upload budgets, pagination, and notifications. | Existing Android behavior remains operational and older clients can treat voice messages as ordinary WAV attachments. |

## Android testing tasks

| ID | Status | Dependencies | Required testing and acceptance |
|---|---|---|---|
| AT01 | Planned | A01 | Run shared WAV, PCM, event, and seek vectors; include exact limits, fragmented and oversized reads, discontinuities, overflow attempts, malformed chunks, truncation, bad alignment, and unsupported formats. |
| AT02 | Planned | A02 | Test all registry crash boundaries, ten-entry behavior, stale-review state, invalid paths, missing conversations, unsendable drafts, reconciliation, Restore, and Delete. |
| AT03 | Planned | A03, A04 | Test permission grant, denial, permanent denial, Settings recovery, initialization failure, short reads, rotation, backgrounding, process death, duration limit, and repeated stop/dispose operations. |
| AT04 | Planned | A05-A07 | Test direct and group queueing, Offline restart, reconnect, interrupted internal retrieval, resume, integrity failure, invalid fallback, scheduler ordering, fairness counters, and deduplication. |
| AT05 | Planned | A08, A09 | Test one-player enforcement, focus loss, route changes, device errors, lifecycle exit, playback completion, adversarial seeks, export, accessibility actions, and notification privacy. |
| AT06 | Planned | A10 | Run Android tests and the relevant complete regression suite before physical-device acceptance. |

## Android manual acceptance

- Confirm the supported minimum and current target Android API.
- Verify permission grant, denial, permanent denial, and Settings recovery.
- Test recording, preview, deletion, Send, playback, seeking, and export.
- Test rotation, lock, backgrounding, process death, force-stop, low storage, audio focus, and route changes.
- Confirm backgrounding stops or finalizes recording and no microphone foreground service exists.
- Confirm the microphone privacy indicator appears only during active recording.
- Confirm finalized drafts recover without reopening the microphone.
- Confirm touch and accessibility seeking.
- Saturate transfers and verify the fixed next-admitted scheduling policy.

## Android phase exit gate

Phase 1 is complete when A01-A10 and AT01-AT06 pass, Android physical-device acceptance is recorded separately, and no Windows interoperability claim has yet been made.

# Phase 2 — PC (Windows)

## Objective

Implement and locally verify the complete Windows voice-message experience, including safe native audio callback handling and tray-process lifecycle behavior.

## Tasks

| ID | Status | Dependencies | Implementation notes | Acceptance criteria |
|---|---|---|---|---|
| W01 | Planned | I01-I04 | Add marker recognition, bounded streaming WAV validation, PCM normalization, WAV sink/source boundaries, and checked seek conversion. Consume fixtures directly from `tests/voice_messages/vectors/`. | Windows matches every shared validation, framing, event, timestamp, and seek result; malformed or unsupported content never becomes playable. |
| W02 | Planned | W01 | Add an application-private durable draft registry with atomic transitions, ten-entry enforcement, 30-day review state, validated storage identities, and startup reconciliation. | Every simulated crash boundary resolves to a recoverable draft, durable queued message, or diagnosed invalid entry; valid finalized drafts remain reachable. |
| W03 | Planned | W01, W02 | Implement a reusable `waveIn` capture adapter with serialized native control, rooted delegates and buffers, bounded frame delivery, safe completion signaling, and idempotent stop/disposal. | Device loss and late callbacks cause no deadlock, callback-thread native/UI misuse, use-after-free, or resource leak. |
| W04 | Planned | W03 | Add Record, elapsed time, Stop, Preview, Delete, Send, and recovery UI. Serialize recording actions with conversation switching, attachment selection, Offline transition, UI closure, and shutdown. | Nothing queues before Send; duplicate Send is prevented; leaving the recording UI safely stops and finalizes; drafts cannot move between conversations. |
| W05 | Planned | W02, W04 | Implement transactional Send through encrypted Normal storage using the required durable write order. | Offline Send survives restart; no queued message uses Fast storage or depends on draft plaintext; reconciliation cannot duplicate a send. |
| W06 | Planned | W01, W05 | Replace image-only admission around `PeerEngine.QueueImageDownloads()`, `imageSlots`, and `imageAttempts` in `windows/Transfers.cs` with the bounded automatic-media scheduler. Continue retrieval through `PeerEngine.DownloadAttachmentAsync()`. | Manual work is next admitted, active work is not preempted, voice precedes images, fairness prevents image starvation, and restart creates no duplicate retrieval. |
| W07 | Planned | W01, W06 | Add candidate, fetching, playable, invalid-marked, and unavailable cards with internal Retry/Resume and separate Save/Export. | Invalid content reuses its retrieved copy; unavailable content cannot play; receiver states and errors are clear. |
| W08 | Planned | W01, W07 | Implement one active inline player using `waveOut`, including Play/Pause, timing, checked seeking, completion reset, safe device loss, bounded decrypted memory, and Save/Export. | Playback creates no plaintext temporary file; output-device loss is safe; stale buffers cannot play after seeking. |
| W09 | Planned | W04, W07, W08 | Add keyboard and accessibility semantics, descriptive labels, textual states, focus order, timing announcements, and non-color errors. | All supported actions are keyboard-accessible and screen-reader meaningful; notifications reveal no audio content. |
| W10 | Planned | W01-W09 | Verify backward-tolerant persistence and regressions affecting attachments, images, Fast transfers, groups, relay, expiry, clear/delete, Offline lifecycle, pagination, and notifications. | Existing Windows behavior remains operational and older clients can treat voice messages as ordinary WAV attachments. |

## Windows testing tasks

| ID | Status | Dependencies | Required testing and acceptance |
|---|---|---|---|
| WT01 | Planned | W01 | Run shared WAV, PCM, event, and seek vectors; include exact limits, fragmented and oversized callbacks, discontinuities, overflow attempts, malformed chunks, truncation, bad alignment, and unsupported formats. |
| WT02 | Planned | W02 | Test all registry crash boundaries, ten-entry behavior, stale-review state, invalid paths, missing conversations, unsendable drafts, reconciliation, Restore, and Delete. |
| WT03 | Planned | W03, W04 | Use fake audio input and lifecycle faults to test repeated actions, conversation changes, forced stop, device loss, shutdown, callback restrictions, late callbacks, and native resource release. |
| WT04 | Planned | W05-W07 | Test direct and group queueing, Offline restart, reconnect, interrupted internal retrieval, resume, integrity failure, invalid fallback, scheduler ordering, fairness counters, and deduplication. |
| WT05 | Planned | W08, W09 | Test one-player enforcement, playback completion, output-device loss, adversarial seeks, keyboard actions, accessibility state, export, bounded memory, and notification privacy. |
| WT06 | Planned | W10 | Run the Windows tests and relevant complete regression suite before Windows manual acceptance. |

## Windows manual acceptance

- Record, stop, preview, delete, send, play, pause, seek, resume, and export.
- Disable or remove input and output devices during use.
- Exit after finalization but before Send, restart, and restore or delete the draft.
- Delete the owning contact or leave the owning group, then verify Preview/Delete-only recovery.
- Fill the registry to ten drafts and verify only new recording is blocked.
- Send while Offline, restart, reconnect, and deliver.
- Retrieve spoofed marked content and verify fallback without a second fetch.
- Saturate transfers and verify the fixed next-admitted scheduling policy.
- Repeat capture and playback cycles and confirm handles, delegates, buffers, and players are released.

## Windows phase exit gate

Phase 2 is complete when W01-W10 and WT01-WT06 pass, Windows manual acceptance is recorded separately, and no cross-platform interoperability claim is made until Phase 3 passes.

# Phase 3 — Android ↔ Windows Integration

## Objective

Freeze and verify the shared media contract, prove bidirectional Android/Windows delivery and playback, and document compatibility without adding a new LM4 frame.

## Tasks

| ID | Status | Dependencies | Implementation notes | Acceptance criteria |
|---|---|---|---|---|
| I01 | Planned | None | Freeze format, limits, filename marker, direct/group scope, Normal-only storage, receiver states, lifecycle rules, recovery limits, compatibility, fixed scheduler policy, and non-goals. | Both platforms have one resolved behavioral contract with no hidden implementation-time product decisions. |
| I02 | Planned | I01 | Define PCM frames, timestamps, sequencing, ownership, bounded backpressure, deterministic overflow, end, discontinuity, failure, and restart behavior independently of WAV, LM4, attachments, UI, calls, and video. | The contract can support both platform adapters and later live-media work without importing attachment or call transport concerns. |
| I03 | Planned | I02 | Create the versioned fixture set under `tests/voice_messages/vectors/`, including valid/adversarial WAVs, fragmented PCM callbacks, expected frames/events, and seek vectors. Platform-local copies are prohibited. | Both test runners consume the same files directly and reject divergent fixture copies. |
| I04 | Planned | I01-I03 | Freeze common marker recognition, streaming validation, receiver-state transitions, seek arithmetic, draft registry states, transactional-send ordering, and crash outcomes. | Both implementations classify, recover, seek, and persist identically for the same inputs. |
| I05 | Planned | A01-A10, W01-W10 | Compare platform results for every shared fixture and stable validation failure category. | Android and Windows produce bit-exact normalized PCM payloads and equivalent metadata, validation, state transitions, and seek positions. |
| I06 | Planned | A05-A08, W05-W08 | Verify direct and group voice messages from Android to Windows and Windows to Android through the existing encrypted attachment path. | Each direction supports explicit Send, Offline queueing, restart, retrieval, validation, playback, seeking, export, and exactly one completed result. |
| I07 | Planned | A06, W06 | Verify equivalent scheduler behavior under common transfer workloads. | Manual work is next admitted, active work is never priority-preempted, fairness counters advance only on admission, images cannot starve, and restart reconstructs work deterministically. |
| I08 | Planned | I06 | Verify group relay, retention, authorization, membership changes, clear/delete, expiry, and interrupted resumable retrieval across platforms. | Playback stops when content becomes invalid or removed; complete and partial internal copies follow existing cleanup rules; relay produces one valid result. |
| I09 | Planned | I06-I08 | Verify mixed-version behavior and hostile marked content. | Older clients receive ordinary WAV attachments; forged, arbitrary, malformed, oversized, unsupported, or truncated content never receives playable voice controls. |
| I10 | Planned | I05-I09 | Run full regression and architecture-boundary checks on both platforms. | PCM code imports no LM4, WAV-container, attachment, UI, call-signaling, or video dependency; ordinary messaging and transfers remain operational. |
| I11 | Planned | I01-I10 | Update `PROTOCOL.md`, `PROJECT_STATUS.md`, `android/STATUS.md`, and `windows/STATUS.md` after implementation and verification. | Records separately identify implemented code, automated verification, Android-device acceptance, Windows acceptance, interoperability acceptance, and deferred work. |

## Integration testing tasks

| ID | Status | Dependencies | Required testing and acceptance |
|---|---|---|---|
| IT01 | Planned | I03-I05 | Run the common WAV, PCM, stream-event, and seek vectors on both platforms and compare all outputs. |
| IT02 | Planned | I06 | Send direct and group messages in both directions, including Offline Send, restart, reconnect, process termination, interruption, resume, and deduplication. |
| IT03 | Planned | I06, I08 | Relay a completely retrieved group voice message and test retention, expiry, authorization loss, membership changes, clear, and deletion. |
| IT04 | Planned | I06 | Seek opposite-platform recordings at the beginning, middle, last sample, and end; verify completion and stale-output invalidation. |
| IT05 | Planned | I07 | Queue manual downloads, voice messages, and image backlogs on both platforms and verify identical non-preemptive admission and three-to-one fairness. |
| IT06 | Planned | I09 | Test older-client fallback, spoofed markers, mismatched IDs, arbitrary WAV attachments, invalid formats, malformed chunks, oversized data, and ordinary-card fallback without another fetch. |
| IT07 | Planned | I10 | Run the complete `tests/run.ps1` regression suite and both platform architecture checks. |

## Two-device LAN acceptance

- Send direct and group voice messages in both directions.
- Test sender and receiver Offline transitions, Wi-Fi interruption, restart, and process termination.
- Confirm drafts remain local until explicit Send.
- Confirm queued sends survive restart.
- Confirm interrupted retrieval resumes to exactly one valid result.
- Relay a completely retrieved group voice message.
- Play, seek, and export recordings produced by the opposite platform.
- Confirm clear, deletion, expiry, and retention cleanup stop playback and remove eligible complete and partial copies.
- Confirm an older client treats the marked object as an ordinary WAV attachment.
- Confirm voice Retry never opens a destination picker.
- Confirm manual downloads receive the next available admission without cancelling active work.
- Confirm the three-voice-to-one-image fairness rule on both platforms.

## Integration phase exit gate

Voice Messages are complete only when:

1. I01-I11 and IT01-IT07 pass.
2. Android and Windows pass the same versioned fixtures.
3. Direct and group messages work in both directions.
4. Offline Send, restart, reconnect, retrieval, playback, relay, clear, and expiry pass.
5. Nothing is transmitted before explicit Send.
6. Crash recovery produces no duplicate sends or queued messages dependent on plaintext drafts.
7. Invalid marked content safely becomes an ordinary attachment.
8. Scheduling is bounded, deterministic, non-preemptive, manual-first, and image-fair.
9. Capture, playback, and retrieval queues remain bounded.
10. Android foreground-only recording and privacy behavior pass physical-device acceptance.
11. Windows device removal and late callbacks cause no deadlock, use-after-free, or leak.
12. Older clients receive ordinary WAV attachments.
13. Status records distinguish implementation, automated verification, platform acceptance, and interoperability.
14. No live-call or video behavior is introduced.

## Deferred roadmap

| ID | Status | Dependencies | Deferred work |
|---|---|---|---|
| CALL01 | Deferred | All Voice Messages gates | Authenticated call signaling: capabilities, identity-bound invitations, consent, accept/reject/cancel/end, session IDs, replay protection, collisions, timeouts, missed-call metadata, and mixed-version fallback. |
| CALL02 | Deferred | CALL01 | Live voice calls: codec negotiation, authenticated encrypted real-time transport, jitter buffering, loss and drift handling, echo control, route changes, lifecycle, reconnect, bandwidth control, and call UI. |
| CALL03 | Deferred | CALL02 | Video calls: camera consent and lifecycle, preview, negotiation, encrypted transport, rendering, A/V synchronization, camera switching, adaptive bitrate, thermal/battery handling, and audio-only fallback. |

The PCM and platform capture/playback boundaries may be reused later. Voice-message WAV storage, attachment retrieval, and fairness scheduling must not become call transport or jitter-buffer logic. Each deferred phase requires a separate plan and explicit execution authorization.

## Recommended execution order

1. Approve and freeze I01-I04.
2. Implement A01-A10 and W01-W10 in parallel or as separately authorized platform work.
3. Complete AT01-AT06 and WT01-WT06.
4. Complete Android physical-device and Windows manual acceptance.
5. Execute I05-I10 and IT01-IT07.
6. Perform two-device LAN acceptance.
7. Update documentation and status records through I11.
8. Close Voice Messages before separately planning CALL01.
9. Do not build a release, commit, push, or begin call work without its applicable explicit authorization.
