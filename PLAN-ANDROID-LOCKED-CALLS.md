# Android incoming calls in background and while locked

2026-10-04. Execution authorized by "fix it all"; commit/push subsequently requested.

## Execution checkpoint — 2.2.69/code96

- AC-01: affected-phone logs confirmed background trusted video acceptance failed
  on camera eligibility. Stale ringing notification after connection identified in source.
- AC-03/04: implemented call-bound PendingIntent data, bounded off-main-thread command
  dispatch/error logging, stale Accept safety, redacted lockscreen actions, CallStyle
  attached to the actual service FGS, obsolete ringing cleanup and default-importance
  incoming channel (no forced full-screen/heads-up). Physical button acceptance pending.
- AC-05: implemented trusted receive-only video acceptance with camera off when
  acquisition is ineligible; normal untrusted consent remains required. Tests cover
  voice/video/combined/no grant/camera-only grant and no spurious ringing callbacks.
- AC-06: already-prepared camera service survives Activity pause/lock. A NEW camera
  service still requires visible/unlocked preparation. Pre-arming idle camera service
  for trusted locked-camera startup was investigated, NOT implemented or accepted.
- AC-02/AC-T01: OS restrictions reviewed; complete physical locked-device matrix
  remains pending. This is not a claim that all locked camera scenarios are fixed.

Automated: CallCheck440/0; PermissionDevices40/0; RecipientControls28/0;
DirectConnections34/0; video consent74/0; all video-contract suites pass;
notification source-wiring and Offline lifecycle guards pass. No full-suite pass claimed.
Original-key ARM64 candidate built/signed (v2/v3) and installed via upgrade on both
phones. Final APK `D:\LAN-Messenger\outputs\LanMessenger-2.2.69-locked-calls.apk`;
SHA-256 `5a85372b17e8590f8eb8403eb45a34111cc18bebed99f5b36b207bea67d2759f`.

First 2.2.68 candidate FAILED physical testing: Samsung rejected standalone
CallStyle notifications and the app crashed. It is superseded, not a releasable APK.
2.2.69 fixes this by posting the call through startForeground. Retain failed evidence.
Actual USB call `7d0d0134-f3b9-41a5-9113-23482c5f64d7` automatically ACCEPTed,
exchanged media readiness/heartbeat, received remote-control commands and ended
on remote hangup while backgrounded. No locked-camera/button acceptance inferred.

USB first-install timestamp changed between observations to 2026-10-04 12:42:49;
the agent used install -r only, never uninstall/clear. Current USB contact is verified;
do not claim old identity/data preservation from this run. S22 first-install time
remained 2026-10-02 19:29:48. Saved grants were not modified by the agent.
Windows/wire unchanged; known SM-A075F Wi-Fi fault unresolved.
User requirement: Answer/Decline from notifications and trusted auto-answer must
work without opening the app, including when the phone is locked. Camera behavior
is included in feasibility review, not promised before Android eligibility testing.

## Source findings

- CallNotifier already adds Accept/Decline service PendingIntents. MessengerService
  receives these actions independently of MainActivity. Why both buttons fail on
  the reported device is NOT yet established; collect action dispatch/state evidence.
- Notification actions execute controller work on the service/main thread and
  swallow exceptions. Improve diagnosis before identifying the failure as an OS issue.
- Ringing uses IMPORTANCE_HIGH/CATEGORY_CALL: this explains a heads-up banner.
  No full-screen intent or system overlay permission exists in current source.
  Confirm whether the reported popup is this banner or the in-app call view.
- Trusted video initial acceptance requires camera eligibility. Service eligibility
  requires visible Activity, camera foreground preparation, interactive display and
  unlocked keyguard. Background/locked video auto-answer therefore fails the gate
  and falls back to ringing; voice grant is not retried after failed video acceptance.
- onPause revokes capture. Merely remaining in Recents/background is not considered
  visible. Trusted voice acceptance has no explicit Activity-visible condition;
  a voice-only failure needs device evidence, not the video explanation alone.
- Ringing snapshot is queued before trusted acceptance, so even successful automatic
  acceptance can briefly alert. Active notification does not explicitly withdraw
  the separate ringing notification. Validate lifecycle ordering in tests.

Android imposes while-in-use microphone/camera foreground-service restrictions;
notification interaction has documented exemptions. An app trust grant does not
override OS eligibility. See official documentation:
https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

## Small tasks

| ID / platform | Status | Dependencies | Work/notes | Acceptance/testing |
|---|---|---|---|---|
| AC-01 Android reproduction | Pending | None | Identify phone/OS/version, voice vs video and exact popup. Observe sanitized logs, service eligibility and actions while visible/background/locked; do not change stored grants. | Distinguish missing buttons, ignored taps, dispatch failure and controller/media failure; both devices recorded separately. |
| AC-02 Android OS feasibility | Pending | 01 | Verify ongoing microphone FGS eligibility and supported notification-interaction path; distinguish starting camera while locked from continuing already-authorized capture. No bypass of lock/security. | Supported/background/locked voice and camera matrix, explicit unsupported cases; no unconditional locked camera promise. |
| AC-03 Android notification actions | Planned | 01,02 | Design call-bound action identities, asynchronous command dispatch, observable failures and correct permission/FGS handling; retain stale-action safety. | Locked/background Answer and Decline act on exact live call; stale actions never decline or accept a newer call; UI/service remain responsive. |
| AC-04 Android presentation | Planned | 01,03 | Use proper incoming call notification presentation; suppress unnecessary ringing alert on successful auto-answer; remove obsolete ringing notification on transition. Clarify heads-up versus full-screen policy. | Exactly one current call notification; actionable lockscreen; no unwanted duplicate/persistent popup; call details remain privacy-aware. |
| AC-05 Android trusted answer | Planned | 02,03 | Separate trusted call acceptance from camera acquisition. Decide explicit audio-connected/video-pending fallback when camera unavailable; preserve independent scopes and revocation. | Valid voice grant answers while background/locked where OS eligible; failed camera gate does not silently disable authorized voice fallback; denied/changed-key peers never auto-answer. |
| AC-06 Android lifecycle | Planned | 02,05 | Define call-owned microphone/camera lifetime instead of blanket Activity ownership where supported; maintain notification and local stop/revoke. | App switching/lock does not accidentally terminate eligible voice; camera policy explicit; hangup/revoke releases resources; no silent camera restart. |
| AC-T01 Android acceptance | Pending | 03–06 | Automated action/state/consent tests plus physical visible/background/locked matrix, both call directions, voice/video, each grant, deny/revoke, app process loss and stale notifications. | Original signer/data preserved; exact candidate/hash and results; actual locked-device acceptance distinct from tests. No Wi-Fi repair claim. |

The table preserves the original task breakdown; the execution checkpoint above
is authoritative for current completion/limits. Remaining work is physical locked
notification acceptance and separately designing/verifying new locked-camera startup.
