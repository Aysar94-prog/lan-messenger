# Voice Messages shared contract (I01)

contract-version: 1

This file freezes the product and media contract for Voice Messages on Android
and Windows. It is normative: platform phases (A01-A11, W01-W11) and the
integration phase (I05-I11) must conform to it and cannot expand Voice Message
scope independently. The transport-independent PCM frame and stream-event
contract is frozen separately by I02 (`pcm-contract.md`); shared fixtures and
expected outputs are frozen by I03/I04 under `vectors/`. This document defines
no runtime or wire changes: Voice Messages reuse the existing encrypted Normal
attachment path and introduce no new LM4 frame.

## Decisions

1. A Voice Message is an ordinary encrypted attachment whose filename carries
   the marker `voice-<message-id>.lanvoice.wav`. No new LM4 frame type exists.
2. Nothing leaves the device before explicit Send. Send imports the finalized
   WAV into the encrypted Normal attachment store; Fast storage and
   original-file references are never used for Voice Messages.
3. Recording is foreground-UI-only on both platforms. There is no microphone
   foreground service and no background capture.
4. Recording stops and finalizes safely on explicit Stop, conversation exit or
   switch, Android backgrounding or lifecycle loss, Windows recording-UI
   closure, Offline transition, application shutdown, audio input device loss,
   or the five-minute duration limit.
5. Playback and Save/Export controls appear only after complete retrieval,
   integrity verification, and bounded streaming WAV validation.
6. Exactly one recorder and one active inline player may exist per
   application instance.
7. Automatic retrieval uses the fixed next-admitted, non-preemptive scheduler
   defined below. There is no runtime choice between preemption and
   next-admitted behavior.
8. Drafts live only in application-private storage, stay bound to their
   original conversation, and are never retargeted. A draft whose conversation
   can no longer send is Preview/Delete-only.
9. Older clients receive an ordinary WAV attachment. The filename marker alone
   never grants playback; forged, mismatched, malformed, unsupported, or
   oversized content falls back to an ordinary attachment card.
10. Direct and group conversations are supported. Existing retention,
    authorization, relay, clear-chat, deletion, and expiry rules apply
    unchanged.
11. Any capability listed under Non-goals requires a new plan and explicit
    authorization.

## Constants

| Constant | Value |
|---|---|
| Sample rate | 16000 Hz |
| Sample format | signed 16-bit PCM, little-endian |
| Channels | 1 (mono) |
| Block alignment | 2 bytes |
| Byte rate | 32000 bytes/s |
| Target PCM frame | 20 ms = 320 samples = 640 bytes |
| Maximum recording duration | 300 s (5 minutes) |
| Maximum PCM data | 9600000 bytes |
| Maximum canonical WAV size | 9600044 bytes (9600000 data bytes + 44-byte canonical header) |
| Marked filename | `voice-<message-id>.lanvoice.wav` |
| Draft registry cap | 10 active or finalized drafts application-wide |
| Stale-review age | 30 days |
| Automatic fairness | 3 consecutive automatic voice admissions, then 1 image |
| Contract version | 1 |

## Receiver states

| State | Required behavior |
|---|---|
| Candidate | A marked trusted offer; show pending voice UI without claiming the item is playable |
| Fetching | Show internal retrieval progress and retain existing cancel/retry semantics |
| Playable | Complete, integrity-verified, and WAV-validated; enable playback and Save/Export |
| Invalid marked content | Replace voice UI with an ordinary attachment card backed by the already retrieved internal copy; do not fetch again |
| Unavailable | Explain expiry, authorization, source, membership, or storage failure without enabling playback |

Voice Retry and Resume are internal retrieval operations and never open a
destination picker. Save/Export is a separate action, available only after
content becomes available.

## Scheduler priority

Admission is considered in this fixed order whenever capacity next becomes
available:

1. User-initiated attachment downloads and resumes.
2. Automatic Voice Message retrieval.
3. Automatic image retrieval.

Active transfers are never paused or cancelled because a higher-priority
request arrives (non-preemptive, next-admitted). A manual request arriving
while all capacity is occupied becomes the next eligible admission after a
slot is released.

## Non-goals

Voice Messages do not include:

1. Echo cancellation, noise suppression, automatic gain control, or other live-call audio processing.
2. Waveform generation or waveform editing.
3. Audio trimming, editing, effects, or replacement of recorded sections.
4. Playback-speed controls.
5. Speech transcription, captions generated from audio, or translation.
6. New audio codecs, compression, transcoding, or codec negotiation.
7. Streaming or sending a recording before it has stopped, finalized, and passed validation.
8. Live-call signaling, real-time media transport, jitter buffering, or call reconnection.
9. NAT traversal, internet relays, TURN/STUN/ICE, or non-LAN transport expansion.
10. Video capture, transport, rendering, or video-call UI.

## Scheduler rules

Both platforms extend their existing automatic-image admission component into
a bounded automatic-media scheduler with these fixed rules:

1. User-initiated attachment downloads and resumes are considered first whenever capacity next becomes available.
2. Automatic Voice Message retrieval is considered next.
3. Automatic image retrieval is considered last.
4. After three consecutive automatic voice admissions while an eligible image waits, the next automatic admission is an image.
5. Active transfers are never paused or cancelled merely because a higher-priority request arrives.
6. A manual request arriving while all capacity is occupied becomes the next eligible admission after a slot is released.
7. Fairness counters advance only on successful admission, not when an offer is scanned, rejected, duplicated, expired, or unreachable.
8. Offline transition, cancellation, clearing, expiry, and authorization loss retain their existing interruption semantics.
9. Restart reconstructs eligible automatic work from durable offers and partial-transfer state without duplicating active or completed retrieval.

## Draft recovery

### Registry rules

1. The durable registry resides only in application-private storage.
2. Maximum 10 active or finalized drafts application-wide.
3. Reaching the limit blocks only creation of another recording; existing
   drafts remain available for Preview, Send, Delete, reconciliation, or
   recovery.
4. A valid finalized draft receives a stale-review indication after 30 days.
5. Age alone never silently deletes a valid finalized draft.
6. Every draft remains bound to its original conversation.
7. A draft whose conversation no longer permits sending is Preview/Delete-only.
8. Unsendable drafts are never retargeted.
9. Registry data may not introduce arbitrary external filesystem paths.

### Registry fields

Every entry records:

1. Opaque draft ID.
2. Owning conversation identity.
3. Conversation type.
4. Created and updated timestamps.
5. Validated application-private storage identity.
6. Recording/finalization state.
7. Expected audio format.
8. Bounded byte-size and duration metadata.
9. Temporary Send-transaction association.
10. Stale-review state where applicable.

## Transactional Send

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
10. Remove the registry entry and best-effort delete plaintext only after the
    encrypted source and queued message are durable.

### Crash-boundary reconciliation

At every simulated crash boundary, reconciliation must produce exactly one
allowed outcome:

1. A valid recoverable draft.
2. A durable queued message with a durable encrypted source.
3. A diagnosed invalid entry that cannot be sent or played.

It must never produce a duplicate Send, an unreachable valid draft, or a
queued message dependent on deleted plaintext.

## Seek procedure

Every seek operation must follow this seven-step procedure:

1. Clamp the requested time to the validated duration.
2. Convert time using checked arithmetic.
3. Resolve the byte position within the validated WAV data range.
4. Align downward to a complete two-byte sample.
5. Invalidate output queued from the old position.
6. Reset playback sequence and media-timestamp state.
7. Report the effective aligned position to the UI.

Seeking to the end produces the completed state.

## WAV validation

A recovered or received candidate becomes playable only after bounded
streaming validation confirms:

1. RIFF and WAVE identifiers.
2. Checked chunk-offset, padding, boundary, and size arithmetic without
   overflow.
3. Supported PCM format.
4. Exactly one channel, 16 kHz, and 16 bits per sample.
5. Correct block alignment and byte rate.
6. Complete two-byte sample alignment.
7. A bounded data chunk.
8. No truncation, overlap, or out-of-range read.
9. Maximum duration and stored-size limits.
10. Duration consistency with the validated data length, byte rate, and sample
    alignment.
11. Unknown chunks are skipped only through checked sizes and RIFF padding.
12. Duplicate required fmt or data chunks are rejected.
13. Reordered required chunks are handled according to the I04 fixture contract
    without unbounded buffering; both platforms must return the same result.
14. Trailing bytes and recognized optional chunks receive the stable behavior
    defined by I04 and the manifest.

Candidate classification remains separate from content validation.

## Version

contract-version: 1

- This is version 1 of the Voice Messages shared contract.
- I02 (`pcm-contract.md`) and the I03/I04 fixture manifest must declare
  compatibility with this version.
- Any change to this file is a Both-platform contract change and requires
  review before platform phases may consume it.
