# Voice Messages Validation Contract (I04)

validation-contract-version: 1

This file freezes the normative Voice Messages validation rules for Android
and Windows. It is a machine-checkable companion to `contract.md`
(contract-version 1) and `pcm-contract.md` (pcm-contract-version 1), and
declares compatibility with both. Platform validators (A01-A11, W01-W11)
and integration validators (I05-I11) must conform. Every numbered item
below is a required clause; deleting or altering a clause must cause the
`--validation` checker to fail.

## Marker recognition

1. A candidate voice-message filename must match `voice-<message-id>.lanvoice.wav` where `<message-id>` is a non-empty opaque identifier and the prefix, suffix, and separator are fixed (no variable interpolation other than the identifier).
2. Marker recognition is a pure string operation; it must not open, stat, or read the file or attachment content.
3. The extracted message-id must be compared exactly (case-sensitive) against the attachment's own message identity.
4. Only attachments in the Normal encrypted store are eligible; a voice marker on a Fast-store attachment is permanently invalid and classified as an ordinary attachment.
5. A filename that does not match the marker pattern is an unmarked attachment and classified as an ordinary attachment.
6. A filename that matches the marker pattern but whose extracted message-id does not match the attachment identity is a mismatched marker and classified as an ordinary attachment.
7. Candidate classification is separate from content validation; a candidate becomes playable only after WAV validation passes.
8. The marker pattern alone never grants playback capability; forged, mismatched, or structurally invalid content always falls back to an ordinary attachment card.

## WAV format bounds

1. Every candidate must begin with the four-byte ASCII identifiers RIFF (offset 0) and WAVE (offset 8).
2. All chunk-offset and size arithmetic must use checked operations that reject overflow; a chunk whose computed end exceeds the verified file length is a bounds failure.
3. The fmt chunk must declare audio format 1 (PCM), exactly one channel, exactly 16000 Hz sample rate, exactly 16 bits per sample, exactly 2-byte block alignment, and exactly 32000 bytes/s byte rate.
4. The data chunk must be present, non-empty, and its declared size must not exceed the bytes remaining after its header.
5. Unknown chunks between the RIFF header and data chunk are tolerated only when their declared sizes are checked and RIFF word-alignment padding is correctly skipped; an unchecked or overflowed chunk size is a bounds failure.
6. Required chunks (fmt, data) must appear exactly once; a duplicate fmt or data chunk is rejected immediately regardless of any other validation state.
7. The PCM data must be an even number of bytes; a data chunk with an odd declared size is a sample-alignment failure.
8. Total file size must not exceed 9600044 bytes (44000024 for a 44-byte header plus 9600000 PCM bytes); an oversized file is rejected before any content inspection.
9. Candidate WAV validation is streaming-bounded: the validator must not buffer the entire file in memory and must reject the file at the earliest detectable violation.

## Error precedence

1. File existence and readability is checked first; an unreadable or absent file fails at this stage without deeper inspection.
2. File too short (< 44 bytes) is checked second; this is a truncation error.
3. Missing RIFF or WAVE identifiers are checked third; this is an identifier error.
4. Overflowing chunk-offset or chunk-size arithmetic is checked fourth; this is a bounds error.
5. Duplicate required chunks (fmt, data) are checked fifth; this is a structural error.
6. Missing required chunks (fmt, data) are checked sixth; this is a structural error.
7. Unsupported audio format (not PCM) is checked seventh; this is a format error.
8. Incorrect sample rate, channels, or bit depth is checked eighth; this is a format error.
9. Incorrect block alignment or byte rate is checked ninth; this is a format error.
10. Data chunk truncation or oversize is checked tenth; this is a data-integrity error.
11. Odd-byte data chunk is checked eleventh; this is a sample-alignment error.
12. Duration exceeds maximum (300000 ms) is checked twelfth; this is a duration error.
13. Duration inconsistency with data length is checked thirteen; this is a consistency error.
14. Trailing bytes after the data chunk are checked last; this is a trailing-bytes error.

A WAV that fails multiple checks must report only the earliest applicable error in precedence order; lower-precedence errors are suppressed when a higher-precedence error is detected. The precedence order is normative for both platforms.

## Seek procedure

1. Clamp the requested time to the validated duration before any arithmetic.
2. Convert the clamped time to a sample count using checked integer arithmetic: samples = <clamped_time> * 16000 / 1000, truncated toward zero, with overflow rejection.
3. Resolve the byte position relative to the validated WAV data start offset using checked arithmetic; a byte position that exceeds the data-end boundary is a seek-end condition.
4. Align the byte position downward to the nearest even boundary; an odd position loses one byte.
5. Invalidate any output buffers queued from the previous playback position.
6. Reset playback sequence, media-timestamp state, and any incomplete-frame assembler.
7. Report the effective aligned byte position and the corresponding nanosecond timestamp (effective_ns = ((aligned_byte - data_start) / 2 * 62500) to the UI.

Seek to the end produces the completed state instead of ready; a completed seek reports the data-end byte and the full-duration timestamp.

### Seek-to-end vs. completed-state resolution

When a seek resolves exactly to the data-end boundary, the seek is to-end and the state is completed; the effective position is data_start + data_bytes, the effective timestamp is duration_ms * 1000000 ns, and no PCM data is available for playback. This result is consistent with Seek procedure step 1 (clamp) and the requirement that seeking to the end produces the completed state.

### Alignment precedence

Downward sample-alignment (step 4) is performed before position reporting (step 7) and after byte-position resolution (step 3). A byte position that is already even-aligned is not modified. The one-byte loss rule is absolute: there is no "round up" or "nearest" fallback; a computation that would place the byte at an odd offset must subtract 1 from the position.

## Receiver state and fallback

1. A Candidate transitions to Playable only after complete retrieval, SHA-256 integrity verification against the declared attachment hash, and bounded streaming WAV validation that passes all format, bounds, and duration checks.
2. A Candidate transitions to Invalid marked content when any WAV validation check fails; the UI must replace the voice card with an ordinary attachment card backed by the already-retrieved internal copy and must not re-fetch.
3. A Candidate transitions to Unavailable when retrieval fails due to expiry, authorization loss, source membership change, or storage failure; no playback controls are enabled.
4. Fetching is an internal retrieval state; cancel and retry semantics follow the existing attachment download path without special voice-message handling.
5. Save/Export is a separate user action available only in the Playable state; it opens a platform destination picker and copies the validated WAV content.
6. Voice Retry and Resume operate on the internal retrieval state only and must not open a destination picker or file-save dialog.

## Draft state recovery

1. At every crash boundary, the durable registry and plaintext storage must reconcile to exactly one of three outcomes: a valid recoverable draft, a durable queued message with a durable encrypted source, or a diagnosed invalid entry that cannot be sent or played.
2. A valid recoverable draft has a completed WAV file, registry metadata that matches the file identity, and an owning conversation that can still send.
3. A durable queued message has both the encrypted Normal-store blob and the queued message record on durable storage before plaintext cleanup runs.
4. A diagnosed invalid entry is one whose registry metadata references a missing, truncated, or unreadable WAV file; such entries are logged and cleared from the registry without attempting Send.
5. Reconciliation must never produce a duplicate Send of the same draft.
6. Reconciliation must never produce a queued message whose encrypted source has been deleted.
7. An unreachable valid draft (registry references a file but the file cannot be read) must transition to diagnosed invalid within a single reconciliation pass; it must not linger in the registry.

## Ten-step transaction and crash outcomes

### Ten-step durable write order

1. Step 1: create and durably register draft ownership is idempotent; if a prior registration for the same identity exists, it is surfaced without creating a duplicate.
2. Step 2: start accepting microphone frames only after step 1 is durable.
3. Step 3: write normalized PCM to application-private storage with exclusive write-lock.
4. Step 4: safely finalize the WAV when recording stops; a crash during finalization may leave a partial WAV that, on recovery, is diagnosed invalid.
5. Step 5: flush and validate the complete file before marking it recoverable; an unflushed file is treated as if step5 never completed.
6. Step 6: mark the draft recoverable only after successful validation; a draft marked recoverable must have a valid WAV on disk.
7. Step 7: allocate the message ID and final marker filename only on explicit Send; a draft that has not been sent has no message ID.
8. Step 8: import the complete, validated WAV into the encrypted Normal store; this is the only point where the plaintext WAV leaves application-private storage.
9. Step 9: durably save the queued message; the message record includes the attachment reference and the marked filename.
10. Step10: remove the registry entry and best-effort delete the plaintext WAV only after step 9 is durable; a crash after step10 may leave a stale plaintext file that is identified as orphaned on next reconciliation.

### Crash-boundary reconciliation outcomes

1. Crash after step 1 before step 2: registered draft with no PCM; recovery deletes the empty draft.
2. Crash after step 3 before step 4: partial PCM in application-private storage; recovery treats the file as unvalidated, deletes it, and clears the registry entry.
3. Crash after step 4 before step5: finalized but unvalidated WAV; recovery deletes the file and clears the registry entry.
4. Crash after step5 before step 6: validated but unmarked WAV; the file exists but is not marked recoverable; recovery clears the registry entry and deletes the orphan plaintext.
5. Crash after step 6 before step7: recoverable draft with valid WAV; recovery surfaces the draft for Send or Deletion.
6. Crash after step7 before step8: message ID allocated but WAV not imported; on recovery, the message ID is guaranteed to not appear in the queued-message table; the plaintext WAV is orphaned and deleted.
7. Crash after step8 before step9: WAV imported to Normal store but message not queued; the encrypted blob is an orphan attachment with no referencing message and is identified on next cleanup.
8. Crash after step9 before step10: message queued with durable encrypted source but registry entry still present; next reconciliation finds the queued message, verifies the encrypted source exists, and completes step10 (remove registry, delete plaintext).

## Scheduler admission rules

1. User-initiated attachment downloads and resumes are considered first whenever capacity next becomes available.
2. Automatic Voice Message retrieval is considered next.
3. Automatic image retrieval is considered last.
4. After three consecutive automatic voice admissions while an eligible image waits, the next automatic admission is an image (3:1 fairness).
5. Active transfers are never paused or cancelled merely because a higher-priority request arrives (non-preemptive, next-admitted).
6. A manual request arriving while all capacity is occupied becomes the next eligible admission after a slot is released.
7. Fairness counters advance only on successful admission, not when an offer is scanned, rejected, duplicated, expired, or unreachable.
8. Offline transition, cancellation, clearing, expiry, and authorization loss retain their existing interruption semantics; the scheduler must not retry or re-queue work that was interrupted for these reasons.
9. Restart reconstructs eligible automatic work from durable offers and partial-transfer state without duplicating active or completed retrieval; the scheduler must diff the durable offer set against the transfer-history log and admit only offers that are not already active or complete.

### Fairness counter semantics

The 3:1 fairness counter is per-scheduler instance and resets on restart to 0 consecutive voice admissions. The counter increments only when a voice admission succeeds while at least one eligible image was waiting at the moment of admission. The image admission resets the counter to 0. Consecutive voice admissions with no eligible image waiting do not advance the counter (the counter stays at its current value).

### Restart reconstruction semantics

On restart, the scheduler must load the durable offer set and compare each offer against the transfer-history log. An offer whose message-id appears in the history log with a completed or terminal status is skipped. An offer whose message-id appears with an in-progress status and whose partial-transfer state is reachable is resumed from its last checkpoint. An offer whose message-id does not appear in the history log is admitted as new work. A partial-transfer whose stored state is unreachable (missing, corrupted, or mismatched) is treated as terminal and skipped.

## Contradiction resolution

1. WAV validation item 13 (reordered required chunks) and the chunk-parsing order: when chunks are reordered, the fmt chunk may appear after the data chunk; the validator must reject a candidate with reordered required chunks at the chunk-parsing stage (error precedence 5 or 6) rather than attempting to parse the fmt chunk after the data chunk. Both platforms must return the same rejection reason.
2. Seek to end vs. duration clamping: when the requested time equals the validated duration, the seek produces the completed state; clamping to duration in step 1 and seek-to-end detection are the same operation. There is no "clamp to just-before-end" case for exact-duration requests.
3. Trailing bytes and optional chunks: trailing bytes after the data chunk (WAV validation item 14) are rejected at error precedence 14; this applies when the RIFF chunk size correctly matches the physical file length minus 8 and extra raw bytes appear after the data chunk within the RIFF. A separate RIFF-size-mismatch condition, where the RIFF declared size does not equal the physical file length minus 8, is a higher-priority bounds error detected at error precedence 4. An optional chunk whose declared size fits entirely before the data chunk and whose bytes are correctly padded is tolerated.
4. Short final frame vs. data validation: a WAV whose PCM data is a nonempty even number of bytes is valid at the WAV validation stage regardless of whether it is a multiple of 640 bytes (the target frame size). A 2-byte data chunk (one sample) and a 642-byte data chunk (640 + 2) are both valid WAV-level PCM and valid PCM final frames under the nonempty-even-frame rule; there is no 640-byte WAV minimum. The short-final-frame check is a PCM-level convention that accepts any nonempty even final frame up to 640 bytes and does not reject shorter data at the WAV layer.
5. Maximum duration and maximum file size: an oversized file (> 9600044 bytes) is rejected at error precedence 8 before any format or duration checks. A file within the size bound but whose computed duration exceeds 300000 ms is rejected at error precedence 12. The size check (precedence 8) and duration check (precedence 12) are independent and report distinct errors.

## Version

validation-contract-version: 1

- This is version 1 of the Voice Messages validation contract.
- It must remain compatible with `contract.md` (contract-version 1) and
  `pcm-contract.md` (pcm-contract-version 1).
- Any change to this file is a Both-platform contract change and requires
  review before platform validators may consume it.