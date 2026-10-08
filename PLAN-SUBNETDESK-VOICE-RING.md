# SubnetDesk Windows incoming voice ringtone

## Physical failure follow-up (2026-10-08, Windows only)

Supersedes the older build/pending-install entries below. User reports no audible ring on
the running Windows1.3.3+78 receiver. Actual child CM7416 belongs to main25332 and shows
Accept/Dismiss and "Mute call ringtone" (not muted). Thus signaling/UI reach the new app;
audible acceptance FAILED. This does not prove playback was invoked. System sound file
exists; output routing/volume remain unverified and unchanged.

Source findings: default ring uses Flutter SystemSound.alert; the installed Windows engine
calls MessageBeep(MB_OK), discards its return value and reports success. Native client
snapshot/add paths do not synchronize ring state, and an event before client registration
is ignored. These are confirmed gaps, not a proven single cause of this live failure.
Existing execution approval for ringtone repair applies; no new protocol/Android work.

| ID | Platform | Task | Status | Dependencies | Notes / acceptance and testing |
|---|---|---|---|---|---|
| VR-F1 | Windows | Trace running receiver, call UI and audio route | Complete | None | Correct new CM, pending request, app mute off; no OS audio changes. |
| VR-F2 | Windows | Dedicated in-memory PCM ringtone, checked native start/stop | Implemented; native WAV test PASS | F1 | Own application audio session, no system-scheme dependency or volume override; playback errors surfaced. Native WAV format/bounds tests. |
| VR-F3 | Windows | Synchronize snapshots/add/events and cancel actual playback | Implemented; Flutter tests PASS | F2 | Authorization/connection gate; duplicate sync cannot reset30s limit; stop on mute/accept/reject/cancel/disconnect/dispose. Local answer tombstone prevents stale pending sync restarting audio. |
| VR-F4 | Windows | Windows-only frontend build79 using unchanged native backend1.3.3 | Build/staging/archives PASS | F2,F3 | Distinct candidate, FileVersion1.3.3+79; frozen-source release PASS132.1s, Flutter73/73, CTest2/2, DLL hash preserved. Helper4e4ba6a,1199-entry corresponding-source/private gate PASS. Android APK and frozen78 artifacts unchanged. |
| VR-F5 | Both (test only) | Phone-originated audible acceptance | Switched to79; awaiting human Firewall prompt handling | F4 | User authorized switch and closed78; main5476/79 owns21118. Security overlay blocks phone test; no permission input automated. User must hear ring and verify stop paths; API success is not audible acceptance. |

Code/build verification complete; audible success still requires user listening on79.
User repeated a call while main25332/78 remained active and reported no sound again. This
does not test79. User then authorized switch and closed78. Build79 main5476 now owns21118;
Windows Firewall permission overlay requires human handling, so Computer Use paused before
phone-call test. Initial raw-path launcher opened installed1.3.0; detected/closed, explicit
process:path then launched correct79. No audible acceptance yet. LAN Messenger
implementation/parity, Android package/signing and updater policy remain unchanged.

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
