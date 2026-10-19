# Android call history entries in chat, Messenger-style

Platform: **Android only** (calls aren't implemented on Windows yet). No wire/storage-format
change in the LM4 protocol sense: each device logs its own call locally, from its own
`CallController`'s perspective — a call's caller/callee role, connect time and duration are
already known identically on both ends without either side needing to tell the other, so this
is purely a local annotation, never sent over the wire.

## Design

- `CallController.endCall()` (CallController.java:664-712) is the single point where a call's
  final `isCaller`, `durationMs` (0 if it never connected) and outcome are all available together,
  delivered once via the listener MessengerService already has wired
  (`cc.addListener(...)` → `onCallSnapshot`, MessengerService.java:171, `case Ending:`).
- At that hook, append a **local-only** `PeerEngine.Message`: never queued, never dispatched to
  the peer (status set directly to `"Delivered"`, bypassing the normal Queued→deliver() pipeline
  entirely — `deliver()`'s retry loop only ever looks at `status.equals("Queued")`, so this is
  never picked up for network send). `from`/`to` set so the existing `mine` bubble-alignment gate
  falls out naturally: `isCaller` → `from=my id` (renders as "mine", matching an outgoing call),
  else `from=peerId` (renders as the peer's, matching an incoming call) — no new alignment logic
  needed.
- A new marker convention in `fileName` (mirroring `VoiceMarker`'s approach exactly), parsed by a
  new `CallLogMarker` class: encodes `isCaller`, `connected` and `durationMs`. `text`/`fileSize`/
  `fileHash` stay empty, `groupId` stays empty (calls are direct-only, never group).
- Four label variants, matching the user's examples plus the natural symmetric case:
  - caller, connected: "You called {peer} · {duration}"
  - caller, not connected: "You called {peer}"
  - callee, connected: "{peer} called you · {duration}"
  - callee, not connected: "Missed call" (declined/no-answer/busy/lost-connection all fold into
    this from the callee's side, matching how Messenger itself doesn't distinguish them inline)
- Rendering (`MainActivity.render()`'s per-message loop, MainActivity.java:385): a call-log
  message is detected and rendered as a small centered pill — no sender name/avatar row, no
  delivery ticks — directly on `feed`, bypassing the normal left/right bubble wrapper entirely,
  matching Messenger's actual visual treatment (a call entry is a system-style center row, not a
  chat bubble). A 📞 icon; missed calls tinted to stand out (red), same spirit as Messenger's red
  missed-call entry.

## Tasks

| # | Task | Status |
|---|---|---|
| L01 | `PeerEngine.appendCallLog(peerId, isCaller, connected, durationMs)` — builds and persists the local-only Message directly (no dispatch). | Done |
| L02 | `CallLogMarker.java` (new) — encode/parse the fileName marker, mirroring `VoiceMarker`. | Done |
| L03 | Hook into `MessengerService.onCallSnapshot`'s `case Ending:` to call `appendCallLog` with the terminal `CallSession`'s fields. | Done |
| L04 | `MainActivity.render()`: detect a call-log message before the normal bubble path and render it as a centered system-style pill. | Done |
| L05 | Add a timestamp under the pill (user follow-up: "add the log time"). | Done |

## Verification (2026-10-02)

Real javac compile (0 errors) and a full `build-voice.ps1` build/sign/verify pass after each
change. **On-device, both phones, a real multi-call session** (place/answer/hang up, place/let
ring out, place/connect briefly): all four label variants confirmed correct and symmetric —
each device shows its own correctly-worded entry ("You called X [· duration]" vs "X called you
· duration" vs "Missed call") with a matching timestamp, for the exact same calls. Missed calls
render in red; connected calls in neutral gray; no sender name/avatar row or delivery ticks on
any call-log pill, matching Messenger's actual centered-pill treatment. One real device name
(the contact's own self-set profile name, unrelated to this feature) had reverted to its Android
model default after an earlier reinstall wiped it — renamed back to "ultra" via Profile, after
which both sides correctly showed "ultra" / device-1's-own-name for each other, confirming the
log always uses whatever the real current contact name is, not a stale or hardcoded one.

## Acceptance criteria

- Ending a call writes exactly one local entry on each device, from each device's own
  perspective — no wire traffic, no duplication, nothing sent to the peer.
- Entry text matches the four variants above; missed calls visually distinct (red).
- No regression to normal text/voice/photo/video message rendering.
- No change to LM4 wire frames, storage format version, or Windows.

## Testing

- Manual on-device: place and answer a real call between the two test phones, hang up, confirm
  both devices show their own correctly-worded entry with a plausible duration. Place a call and
  let it ring out/decline it, confirm the caller sees "You called X" and the callee sees "Missed
  call".
