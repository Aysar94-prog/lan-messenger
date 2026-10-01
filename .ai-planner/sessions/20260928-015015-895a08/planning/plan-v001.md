# plan-v001 | version=1 | Cross-platform voice messages with call-ready foundations

## Goal

Add secure voice messages to both Windows and Android while reusing the existing encrypted, resumable attachment transport. Structure audio capture and playback so later voice-call and video-call work can reuse media components without prematurely adding live-call signaling or streaming.

## Scope and product decisions

- Platform: **Both**.
- Support direct and group conversations.
- Use a voice-note composer flow: **Record → Stop → Preview/Delete → Send**.
- Record a maximum of **5 minutes** per message.
- Use **16 kHz, 16-bit, mono PCM WAV** as the first shared format:
  - natively recordable and playable on both platforms;
  - no third-party codec dependency;
  - suitable as a reusable raw-audio basis for later call work.
- Voice messages use the existing attachment offer, encrypted storage, transfer, retry, resume, deduplication, group relay, expiry, clear-chat, and deletion behavior.
- Use an application-owned filename marker such as `voice-<message-id>.lanvoice.wav`.
- Confirm the marker and validate the WAV structure before treating an attachment as a voice message. An arbitrary filename alone must not activate the voice UI.
- Existing clients receive voice notes as ordinary downloadable WAV attachments. No LM4 frame or capability change is required for this phase.
- Upgraded clients automatically download eligible voice notes when Online, but retain a manual Download/Resume fallback.
- Recording is available only while the conversation UI is active. Leaving the chat, app backgrounding, going Offline, or beginning shutdown stops recording safely and retains a preview draft when possible.
- Microphone audio is never transmitted until the user explicitly presses Send.

## Explicit non-goals

- Live voice calls, video calls, ringing, accept/reject, call presence, NAT traversal, conferencing, echo cancellation, or real-time media transport.
- Background microphone capture.
- Voice transcription, waveform generation, playback speed controls, editing, or noise suppression.
- Changing existing verification, trust, encryption, attachment limits, group signatures, or retention policy.
- Treating all `.wav` files as voice messages.
- Shipping releases or changing version numbers until implementation and acceptance are complete.

## Future-call architecture boundary

```text
Microphone
    ↓
Platform audio capture adapter
    ↓ PCM frames
Voice-note recorder → WAV attachment → Existing LM4 transfer
    │
    └── future live-media encoder/transport (not implemented now)

Stored/received audio
    ↓
Platform audio playback adapter
    ├── voice-message player now
    └── future live-call playout later
```

The reusable boundary is PCM capture/playback plus lifecycle and permission handling. Future call signaling and real-time transport remain separate modules so voice-message storage semantics do not become a call protocol by accident.

## Task plan

| ID | Platform | Status | Dependencies | Task and notes | Acceptance criteria |
|---|---|---:|---|---|---|
| VM01 | Both | Planned | None | Freeze the voice-note contract: marker filename, WAV parameters, five-minute limit, maximum calculated byte size, validation rules, auto-download eligibility, interruption behavior, and mixed-version fallback. Document that these are attachment semantics, not a new LM4 message type. | Windows and Android constants and validation rules are identical; maximum duration and byte-size calculations are tested; ordinary WAV attachments remain ordinary files. |
| VM02 | Both | Planned | VM01 | Add a narrowly scoped voice-note classifier and WAV metadata parser in each engine/UI boundary. Validate RIFF/WAVE, PCM format, channel count, sample rate, bit depth, data length, safe duration arithmetic, truncation, and trailing-data limits before exposing playback controls. | Valid generated notes are recognized with the correct duration; malformed, oversized, spoofed, or unsupported files fall back to ordinary attachment behavior without crashes or excessive allocation. |
| VM03 | Windows | Planned | VM01 | Introduce a Windows audio-capture adapter using supported local Windows APIs. Emit bounded PCM chunks into a temporary WAV draft without holding the complete recording in memory. Make start, stop, cancel, device failure, and disposal idempotent. | Recording starts and stops repeatedly without leaking handles; the result is a valid 16 kHz/16-bit/mono WAV; the five-minute cap stops recording automatically; failure leaves no sendable corrupt draft. |
| VM04 | Android | Planned | VM01 | Add `RECORD_AUDIO` permission and an Android capture adapter based on `AudioRecord`. Request permission only when recording is first attempted. Stream PCM into an app-private temporary WAV draft and finalize its header safely. | Grant starts recording; denial and permanent denial show actionable UI without changing network state; pause/background/destruction releases the microphone; the cap and format match Windows. |
| VM05 | Windows | Planned | VM03 | Add a microphone action to the composer. Implement Record, visible elapsed timer, Stop, Preview, Delete, and Send states. Prevent simultaneous recording, attachment picking, sending, conversation switching, and duplicate button actions. Integrate the finalized recording with the existing attachment draft and explicit Send path. | No audio is queued before Send; Delete removes the temporary draft; switching chats cannot attach a recording to the wrong conversation; recording controls remain keyboard-accessible and expose clear text/status. |
| VM06 | Android | Planned | VM04 | Add the equivalent microphone action and recording-draft UI. Preserve the existing horizontally scrollable action layout and explicit Send behavior. Coordinate runtime permission callbacks, activity lifecycle, rotation handling, and the service-owned engine. | The UI cannot start two recorders; lifecycle changes release the microphone; a finalized draft remains associated only with its originating conversation; cancel/delete removes temporary data. |
| VM07 | Both | Planned | VM02, VM05, VM06 | Queue finalized voice drafts through the ordinary encrypted attachment path. Ensure source retention, queued-offline behavior, retry, resume, group fan-out, group signature canonicalization, seven-day group expiry, clear-chat, delete-conversation, and delete-all-data work without special wire logic. | Direct and group voice notes survive sender restart and Offline/Online interruption; deduplication prevents duplicate cards; cleanup removes all related source/cache/partial files under existing rules. |
| VM08 | Both | Planned | VM02, VM07 | Add conservative automatic retrieval for recognized voice offers. Apply a dedicated voice-note size ceiling, existing trust and membership checks, existing transfer concurrency limits, resumable storage, and Online gating. Manual Download/Resume remains available after failure or when auto-download is ineligible. | Untrusted, malformed, oversized, Offline, or storage-failing cases do not auto-download; interrupted retrieval resumes correctly; automatic retrieval cannot bypass daily Android upload policy or transfer authorization. |
| VM09 | Windows | Planned | VM02, VM07 | Add an inline voice card and Windows playback adapter with Play/Pause, elapsed/total duration, completion reset, and single-active-player behavior. Read only validated local content through the encrypted attachment access path or a controlled temporary playback file with guaranteed cleanup. | Playback works for notes recorded on both platforms; selecting another note stops the previous one; missing/incomplete/corrupt media shows Download/Retry or a clear error; UI rendering never blocks on full-file reads. |
| VM10 | Android | Planned | VM02, VM07 | Add the equivalent inline voice card using Android playback APIs. Integrate audio focus, route changes, pause/background behavior, completion, and resource release. Do not request speaker/Bluetooth call permissions in this phase. | Cross-platform notes play correctly; audio-focus loss pauses playback; leaving the screen releases the player; incomplete or corrupt content cannot be played as a valid note. |
| VM11 | Both | Planned | VM05–VM10 | Add notifications and accessibility behavior. Notifications identify a “New voice message” without exposing recording content. Provide content descriptions, non-color state indicators, timer text, and minimum touch targets. | Incoming voice notifications open the correct conversation; recording/playback states remain understandable without relying on color or waveform animation. |
| VM12 | Both | Planned | VM07–VM10 | Define compatibility and migration behavior. Keep persistence readable by current code where possible; add only backward-tolerant fields if local metadata becomes necessary. Verify that older peers handle the note as an ordinary WAV attachment and that upgraded peers still render arbitrary audio files normally. | No existing messages or attachments require destructive migration; mixed-version direct messaging and groups continue operating; an old client can download/open the WAV as a file. |
| VM13 | Both | Planned | VM01–VM12 | Update `protocol.md`, `PROJECT_STATUS.md`, `windows/STATUS.md`, and `android/STATUS.md` after implementation. Clearly separate implemented source, automated verification, Windows hardware acceptance, Android device acceptance, and cross-platform LAN acceptance. Record voice/video calls as future work, not implemented functionality. | Status records accurately describe scope, compatibility, test evidence, and remaining device acceptance; no parity claim is made without evidence. |

## Automated testing plan

| ID | Platform | Status | Dependencies | Test work and required coverage |
|---|---|---:|---|---|
| VT01 | Both | Planned | VM01, VM02 | Unit-test WAV creation/parsing, duration calculations, zero/truncated/overflow data, unsupported codecs, wrong sample formats, forged marker names, and ordinary WAV fallback. |
| VT02 | Windows | Planned | VM03, VM05 | Test the recorder state machine using an injectable fake capture source: start/stop/cancel, repeated commands, device loss, cap reached, chat switch, shutdown, temporary-file cleanup, and explicit-send enforcement. |
| VT03 | Android | Planned | VM04, VM06 | Test permission granted/denied/permanently denied paths, lifecycle shutdown, rotation behavior, capture initialization failure, short reads, cap reached, and temporary-file cleanup with an injectable capture source. |
| VT04 | Both | Planned | VM07, VM08 | Extend the cross-platform harness for Windows→Android and Android→Windows voice offers, direct and group delivery, offline queueing, restart recovery, auto-download, manual fallback, interrupted transfer resume, integrity failure, and deduplication. |
| VT05 | Both | Planned | VM09, VM10 | Test player state transitions with fake playback adapters: play, pause, resume, completion, error, missing content, one-player-at-a-time behavior, conversation switch, app background, and disposal. |
| VT06 | Both | Planned | VM07–VM10 | Regression-test ordinary files, inline photos, Fast transfers, group relay/expiry, clear/delete operations, offline lifecycle, upload limits, message pagination, and existing notifications. Run the complete `tests/run.ps1` suite. |
| VT07 | Both | Planned | VT01–VT06 | Add malformed-input and resource tests: bounded memory during maximum-length capture/transfer/playback, path safety, decompression-free parsing, auto-download concurrency, rapid UI actions, and cleanup following forced termination. |

## Manual acceptance plan

### Windows

- Record, preview, delete, and send using at least one built-in and one external microphone when available.
- Verify timer accuracy, automatic stop at five minutes, device-removal behavior, and microphone release after stop/exit.
- Play locally recorded and Android-recorded notes; verify pause/resume, completion, and switching between messages.
- Validate high-DPI layout, keyboard navigation, screen-reader labels, tray minimize/restore, and Offline queueing.

### Android physical devices

- Test Android 8 and a current supported Android version when available.
- Verify first-use permission grant, denial, permanent denial, and Settings recovery.
- Exercise screen lock, app background/foreground, rotation, audio focus loss, wired/Bluetooth route changes, and low-storage failure.
- Confirm no microphone capture or recording foreground service remains after leaving the recording flow.
- Verify playback of Windows-recorded notes and automatic/manual download behavior.

### Two-device LAN interoperability

- Test Windows↔Android in both directions for direct and group chats.
- Interrupt sender and receiver with Go Offline, app restart, Wi-Fi loss, and process termination; confirm resume, integrity, and no duplicate message.
- Confirm an older build receives the voice note as an ordinary WAV attachment and continues text/file interoperability.
- Confirm group expiry and conversation/data deletion remove voice-note data according to existing policy.

## Completion gates

1. All implementation tasks VM01–VM13 meet their acceptance criteria.
2. All automated tasks VT01–VT07 pass, including the unchanged full regression suite.
3. Windows and Android physical-device results are recorded separately; unavailable hardware is reported as pending rather than passed.
4. Cross-platform LAN acceptance passes in both directions.
5. No LM4 compatibility change is introduced accidentally.
6. Source/status changes may be committed locally only after explicit execution approval; releases and pushes require separate user direction.

## Later roadmap

1. **Voice messages — this plan:** stored, resumable, asynchronous PCM audio attachments.
2. **Voice-call foundation:** call capability discovery, authenticated invite/ring/accept/reject/end state machine, session IDs, timeouts, and call UI.
3. **Live voice:** low-latency encoded audio, jitter buffer, packet loss handling, echo cancellation, audio routing, and foreground-service behavior.
4. **Video-call foundation:** camera lifecycle, permission handling, preview, codec negotiation, bandwidth adaptation, and video rendering.
5. **Video calls:** synchronized audio/video transport, call recovery, device switching, thermal/battery controls, and full interoperability/security testing.
