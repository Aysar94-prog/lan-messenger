# Evidence: WVC-T03 - Real Audio Transfer Test (Two-Device LAN)

Date: 2026-10-05
Status: Pending (awaiting two-device test execution)
Build: bridge-db9eceb02a7c44bb928db7362be0ccd7 (LanMessenger.WebRtc.Native.dll)
Source base: 3943a59 (bridge crash repair)

## Test setup
- Device A (caller): ______________
- Device B (callee): ______________
- LAN segment: ______________ (same subnet)
- Build tested: Windows Release (current)
- Both devices online/trusted: [ ] Yes [ ] No
- Video disabled confirmed: [ ] Yes (no camera UI active)

## Call sequence timeline (with timestamps, UTC or local)
| Event | Time (mm:ss.ms) | Device (A/B) | State/Notes |
|---|---|---|---|
| Call initiated | ____ | A | OutgoingRinging |
| INVITE/CALL sent | ____ | A | |
| Ringing received | ____ | B | IncomingRinging |
| Answered | ____ | B | Connecting |
| Media connected | ____ | A/B | Connected (HELLO/READY/CALLCONNECT observed) |
| Audio both directions | ____ | A↔B | ~10–30s spoken |
| Hangup/End | ____ | A/B | End reason: __________ |

## HELLO/READY/CALLCONNECT sequence
Document exact sequence/order observed (from signaling/call protocol):

```
[Paste sequence here]
```

## SDP exchange (offer/answer/pranswer)
- Offer sent by: ____ (A/B)
- SDP type (detect): offer/answer/pranswer? __________
- Answer sent by: ____ 
- Key SDP lines (ICE candidates, codecs, media): 
```
[Paste relevant SDP excerpt]
```

## State & end reason
- Final states observed: A: ____, B: ____
- End reason (if ended): __________
- Call duration: ____ s

## Media/device checks (critical)
- [ ] No microphone accessed during probing/ringing (mic opened only at/after Connecting/Connected)
- [ ] No camera accessed at any time during call
- [ ] Real CoreAudio used (not dummy ADM path) – observed via bridge/init logs if available
- [ ] Voice both directions audible (A→B, B→A)
- [ ] No hang/freeze when ending call; UI responsive
- [ ] Call ended cleanly

## Logs captured
- Bridge/native logs: [ ] Yes (path: __________)
- PeerEngine/CallController/CallChannel: [ ] Yes (path: __________)
- UI state snapshots: [ ] Yes (path: __________)

## Notes / anomalies
________________
________________

## Acceptance
- WVC-06: [ ] PASS [ ] FAIL [ ] Partial (evidence complete)
- WVC-T02: [ ] PASS [ ] FAIL [ ] Partial
- Device acceptance complete: [ ] Yes [ ] No (remains Partial until Yes + evidence attached)

Evidence collected by: ______________
Verified by: ______________
Date: ______________
