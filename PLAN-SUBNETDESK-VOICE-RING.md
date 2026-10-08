# SubnetDesk Windows incoming voice ringtone

Date: 2026-10-08. User approved execution with "execute everything"; work in progress.
User reports voice call now works after granting Android microphone permission, but
Windows receives the request without a ringtone. Phone log at10:29:29 confirms recorder
creation and onVoiceCallStarted success. Audible quality/stress acceptance remains separate.

Verified cause: server_model.dart:updateVoiceCallState sets incomingVoiceCall, restores
the Windows CM and expands/selects the session, but does not invoke a sound player.
This is distinct from a chat buzzer; accepting a call must remain a local user decision.

| ID | Platform | Task | Status | Dependencies | Notes / acceptance |
|---|---|---|---|---|---|
| SD-VR-01 | Windows | Review incoming-call transition and cancellation lifecycle | Initial source review complete | None | Distinguish false->true request transition from repeated state sync/polling. |
| SD-VR-02 | Windows | Short repeating incoming-call alert with separate mute setting | Source implemented | 01 | Repeat every3s for at most30s; no mic permission/auto-answer. Independent mute control. |
| SD-VR-03 | Windows | Stop on accept, dismiss, remote cancellation, timeout/disconnect and dispose | Source implemented | 02 | Pending transitions/disconnect/dispose stop timer; aggregate playback is bounded. |
| SD-VR-04 | Windows | Automated lifecycle/audio callback tests and Windows-only build | Focused tests pass; frontend build pending | 03 | Four ring tests plus eight buzzer and five panel tests:17/17; native backend release passes. |
| SD-VR-05 | Both | Physical phone-originated call acceptance | Pending candidate installation | 04 | User must hear Windows ring and verify stop paths. Existing voice success is not ringtone acceptance. |

Voice ringing is implemented in source but not yet running on the receiver. Buzzer and three-mode
authentication work have separate approval, test and acceptance records. Sound implementation
must use normal Windows output and respect user mute/system audio, never override it.
