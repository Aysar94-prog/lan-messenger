# Voice Messages PCM interface contract (I02)

pcm-contract-version: 1

This file freezes the device-independent PCM frame and stream-event interface
for Voice Message capture and playback on Android and Windows. It is
normative for platform phases (A01-A11, W01-W11) and the integration phase
(I05-I11), extends the shared contract `contract.md` (contract-version 1),
and defines no platform runtime, wire, call, or video behavior. Platform
adapters translate device callbacks into the fragments and events defined
here; the interface itself stays pure and device-independent.

## Ownership and frames

1. Frames are transport-independent, immutable, and owned by their consumer once delivered; a producer must never mutate, reuse, or retract delivered frame bytes, and native callback buffers are never exposed to shared logic.
2. A frame carries at most 640 even bytes of PCM payload.
3. The final frame of a stream is nonempty and even.
4. Frame payloads are signed 16-bit mono 16000 Hz little-endian samples as frozen by `contract.md`.

## Fragments and odd bytes

1. Arbitrary callback fragment boundaries are accepted; the assembler keeps at most one pending odd byte between fragments.
2. A pending odd byte is assembled with the next available byte and never becomes a frame by itself.
3. Ending the stream while an odd byte is pending is fatal: the epoch fails terminally and no truncated frame is emitted.
4. An oversized callback is split incrementally into complete frames with bounded allocation; assembly never requires an unbounded buffer.

## Epoch, sequence, and time

1. Each epoch numbers its frames with sequence 0 upward, incrementing by exactly one per frame.
2. A frame's timestampNs equals completed samples * 62500 within its epoch (16000 Hz); wall-clock time is never used for frame timing.
3. Restart closes the current epoch and begins a new one with sequence and timestamp state reset.
4. Discontinuity marks a gap in the sample stream without closing the epoch; sequence and timestamp keep following the epoch rules above.

## Capacity and fail-stop

1. Pending capacity is eight frames / 5120 bytes; the producer callback never blocks.
2. Overflow discards the incomplete epoch and emits exactly one terminal failure through an independent bounded control-event path.
3. After a terminal failure the draft is invalid; bytes are never silently dropped, truncated, or stitched.
4. A new recording requires an explicit restart into a new epoch.
5. The hard maximum of accepted PCM is 9600000 bytes; input beyond the maximum is rejected, never truncated.

## Events

1. The explicit stream events are End, Discontinuity, DeviceFailure, and Restart.
2. End is the only normal termination and requires a complete even-byte stream.
3. DeviceFailure is terminal for its epoch and invalidates the draft.
4. Restart is the only way to begin new recording samples after a terminal failure or a completed epoch.

## Forbidden dependencies

1. Transport-independent PCM frame, normalization, and stream-event code must not import or depend on platform, WAV-parsing, LM4, attachment-store, UI, call-signaling, or video modules.
2. Only platform adapters may touch device APIs; they translate device callbacks into fragments and events without leaking device types inward.

## Version

pcm-contract-version: 1

- This is version 1 of the PCM interface contract.
- It must remain compatible with `contract.md` (contract-version 1).
- Any change to this file is a Both-platform contract change and requires
  review before platform phases may consume it.
