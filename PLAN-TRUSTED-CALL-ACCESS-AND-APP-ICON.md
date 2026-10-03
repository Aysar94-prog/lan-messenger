# Trusted call access and app icon plan

Prepared 2026-10-03. Planning only: no application code, generated icon asset,
build, package, install, release, or protocol change has been made.

## Requested outcomes and scope

| Workstream | Platform | Requested outcome | Current reality |
|---|---|---|---|
| L01 app icon | Both | Use the supplied 1254x1254 PNG as LAN Messenger's app icon. | Source image is RGB with a white outer background and no alpha. Neither platform currently declares a custom app icon in the inspected project files. |
| T01 trusted call access | Both | The owner can grant one verified device permission to call without a manual answer and, within that call, control the receiving device's front/rear camera and speaker route. | Voice calling is released on both platforms. Android video is an unaccepted 2.2.44/code71 test candidate; Windows 2.2.42 is voice-only and its production video migration remains pending. |

The user-facing name should be **Trusted call access**, not “super permission.” It
is a local, per-device authorization layered on top of certificate verification;
it is not an Android/Windows OS permission and it grants no general remote-control
access outside an active LAN Messenger call.

## Non-negotiable safety and behavior boundaries

- Default is off. Only the local owner can grant, change, or revoke it from the
  verified contact's device details. The remote device cannot grant itself access.
- Store the grant against both peer ID and the currently verified certificate
  fingerprint. Verification revocation, key change, forgetting the contact, or
  deleting app data immediately invalidates the grant and ends an active call.
- Separate scopes are persisted: `autoAnswerVoice`, `autoAnswerVideo`,
  `remoteCameraControl`, and `remoteSpeakerControl`. A convenience “Allow all”
  switch may set them together, but the confirmation page must enumerate them.
- Auto-answer and remote controls apply only to a direct, authenticated,
  verified-peer call on the existing call channel. They never apply to groups,
  discovery packets, ordinary messages, stale calls, or a replacement identity.
- Microphone/camera OS permission is never bypassed. Camera starts only during an
  accepted active call, with an ongoing call notification and the existing visible
  call UI/privacy indicators. No hidden capture, recording, snapshots, background
  storage, or camera access after hangup is included.
- The receiving user always retains local mute, camera-off, route, and hang-up
  controls. Hang-up takes precedence over queued remote commands. A prominent
  “Revoke trusted access” action must be available from the active-call UI.
- If the app/OS cannot legally or technically start microphone/camera capture from
  its current background state, the call must fall back to a visible incoming-call
  notification requiring a tap; it must not loop, weaken checks, or falsely report
  that video is active.
- Rate limits, busy handling, one-active-call limits, Offline behavior, and legacy
  peer behavior remain enforced. Trusted access does not override them.

## Proposed protocol and state design

The permission itself is deliberately **local-only** and is not synchronized to
the permitted peer. For each incoming invitation, the callee evaluates its current
local grant after the existing authenticated TLS handoff and verified fingerprint
check.

For version-2-capable calls, add bounded authenticated control frames tied to the
current `cid`, accepted video request UUID, active generation, and increasing
sequence/revision values:

| Command | Required local grant | Meaning |
|---|---|---|
| `REMOTE_CAMERA_SET camera=on/off` | auto-answer video plus remote camera control | Start/stop local transmission; `on` rechecks OS permission, foreground eligibility, hardware, and active bilateral video authorization. |
| `REMOTE_CAMERA_SWITCH facing=front/rear` | remote camera control | Switch only while authorized video is active. Failure reports state/error and leaves voice alive. |
| `REMOTE_SPEAKER_SET speaker=on/off` | remote speaker control | Request speaker or normal local route. Wired/Bluetooth/system policy may override; report actual route. |
| `REMOTE_CONTROL_STATE` | none beyond active call | Informational acknowledgement containing actual camera/facing/route state; it never grants authority or starts capture by itself. |

Exact names/fields are provisional until T05. Unknown frames remain safely ignored
by legacy peers. Voice-only auto-answer can remain on the v1 call path and needs no
new frame. Video auto-answer/control must require a fresh successful `CALLCAPS`
exchange and the confirmed v2 video contract. Commands received before connection,
after hangup, for the wrong peer/call/request/generation, out of sequence, or after
revocation are rejected without touching hardware.

Incoming decision order:

1. Validate channel, peer identity, call ID, rate limit, Online state, busy state,
   and global incoming-call setting exactly as today.
2. Load the grant for the authenticated peer ID + verified fingerprint.
3. Voice invitation: auto-accept only if `autoAnswerVoice`; otherwise ring normally.
4. Video invitation: require `autoAnswerVideo`, current video capability, OS
   permissions, and an eligible foreground/call-service state. Otherwise answer
   voice-only when voice auto-answer is allowed, or ring/fall back visibly.
5. After connection, accept each remote command only when its scope is still
   granted; re-evaluate the grant and OS/media state on every command.
6. Show the trusted peer name and active microphone/camera state in the call UI and
   ongoing notification. Log that the incoming call was auto-answered, but do not
   log camera frames or audio.

## Small-task implementation plan

| ID | Platform | Status | Depends on | Task / notes | Acceptance criteria |
|---|---|---|---|---|---|
| L01 | Both | Planned | Execution approval | Copy the supplied PNG into a durable source-art location; record provenance as user-supplied. Do not depend on the temporary clipboard path. | Canonical source asset is committed; pixel dimensions/hash recorded; temporary file is not referenced by builds. |
| L02 | Android | Planned | L01 | Produce adaptive launcher foreground/background assets plus density fallbacks. Preserve the artwork, choose a dark navy background from it, crop the white outer margin, and keep speech bubbles/monitors inside the adaptive safe zone. Add `android:icon` and `android:roundIcon`. | Launcher and settings icons render without white square corners, clipping, blur, or missing-resource fallback on representative API 26/34 launchers. |
| L03 | Windows | Planned | L01 | Generate a multi-resolution `.ico` (16, 20, 24, 32, 40, 48, 64, 128, 256) and declare it in the project/executable and main/tray window surfaces. | Explorer, taskbar, Alt-Tab, title bar, tray, and published executable show the new icon sharply at normal and high DPI. |
| T01 | Both | Planned | Execution approval | Freeze product semantics above, including separate scopes, local override, visible indicators, revocation behavior, and Android fallback. | Written behavior review has no path for unverified, key-changed, group, stale, or post-hangup control. |
| T02 | Both | Planned | T01 | Extend encrypted local peer storage with a version-tolerant trusted-call grant keyed to peer ID + verified fingerprint. Add atomic grant/update/revoke APIs. | Old stores load with all scopes off; new stores round-trip; failed saves roll back; revoke/key change/forget/delete-data clear the grant. |
| T03 | Android | Planned | T02 | Add a verified-contact details section with scope toggles, a high-friction confirmation summarizing microphone/camera/speaker effects, and revoke action. | Cannot enable for unverified/key-changed peer; grant survives restart; UI accurately reflects each scope and active fingerprint. |
| T04 | Windows | Planned | T02 | Add the equivalent verified-contact dialog controls and confirmation/revoke flow. | Same policy and persistence acceptance as T03, without implying Windows video is available. Video-related scopes are disabled with an explanatory status until Windows production video exists. |
| T05 | Both | Planned | T01, Android v2 contract, Windows R05.4 | Specify exact versioned remote-control frames, bounds, roles, acknowledgements, errors, ordering, and legacy behavior. Update cross-language fixtures before production code. | Java/C# byte fixtures agree; malformed/extra/oversized/stale/wrong-role frames are rejected; v1 bytes and old voice interoperability are unchanged. |
| T06 | Both | Planned | T02 | Refactor incoming-call admission so a valid per-peer voice grant can enter the existing accept flow without a UI click. Preserve limiter/busy/Offline/global-disable checks and cancellation races. | Authorized voice calls auto-answer once; unauthorized calls ring; simultaneous/replayed invites do not double-start media; revocation/hangup wins races. |
| T07 | Android | Planned | T03, T05, T06, accepted Android production video candidate | Integrate video auto-answer and remote camera/speaker commands with `CallVideoConsent`, `CallVideoCoordinator`, `CallRoutePolicy`, and service-owned lifecycle. Treat the stored grant as advance local consent only for its named scopes. | Front/rear/on/off and speaker requests affect only the active authorized call; camera permission/foreground/hardware failure falls back safely; voice survives recoverable video/control failure. |
| T08 | Windows | Blocked by existing video work | T04, T05, T06, Windows R05.5 | Add speaker-route control to released voice calls. Add camera control only after the planned native Windows production video adapter/UI exists; do not extend the current voice-only SIPSorcery release speculatively. | Speaker control reports actual route. Camera tests pass on the completed production video stack; until then Windows rejects video controls as unsupported while voice continues. |
| T09 | Both | Planned | T03/T04, T06-T08 | Add active-call disclosure: “Auto-answered for <device>”, camera/mic state, remote-control event indication, immediate revoke, local override, and notification actions. | User can always see, stop, override, revoke, or hang up. No notification/UI claims a requested route/camera state until the adapter confirms it. |
| T10 | Both | Planned | T02-T09 | Add structured call-history outcome markers for auto-answer/fallback/control failure without storing media content. Keep existing message retention semantics. | History distinguishes normal answer, trusted auto-answer, voice-only fallback, and failed/rejected control; no secrets or raw frames are persisted. |
| T11 | Both | Planned | All implementation tasks | Update `windows/STATUS.md`, `android/STATUS.md`, `PROJECT_STATUS.md`, protocol docs, and relevant video handoffs. Keep implemented, automated, and physical acceptance claims separate. | Records state exact platform differences and do not claim Windows video or Android device acceptance prematurely. |

## Testing tasks and release gates

| ID | Platform | Status | Depends on | Test task / acceptance |
|---|---|---|---|---|
| V01 | Both | Planned | T02 | Storage migration, atomic-save failure, restart, fingerprint rotation, revoke, forget, remote-forget, and delete-data tests. Every invalidation turns all scopes off. |
| V02 | Both | Planned | T05 | Cross-language parser/admission fixtures: unknown legacy behavior, duplicate/replay, reordered sequence, wrong peer/cid/request/gen/role, retired request, post-hangup, oversized frame, and command flood. No hardware callback on rejection. |
| V03 | Both | Planned | T06 | Voice regression: normal ringing unchanged; authorized auto-answer in each direction; Offline/busy/global-disable/rate-limit/cancel races; mute, route, hangup, call log, and UI responsiveness. Pair current and legacy builds. |
| V04 | Android | Planned | T07 | Instrumented/fake-media lifecycle tests for foreground/background/locked-screen states, permission missing/revoked mid-call, process/activity recreation, camera unavailable, front/rear absence, Bluetooth/wired-route override, repeated calls, and teardown leak checks. |
| V05 | Android | Planned | V04 | Physical two-device acceptance on target API 34+: voice and video auto-answer, visible ongoing notification/privacy indicators, front/rear/on/off, speaker on/off, local override, revoke during call, screen locked/background fallback, app force-stop, and key-change denial. Record OEM/device/API limitations explicitly. |
| V06 | Windows | Blocked by T08 | T08 | Physical Windows/Android acceptance in both call directions; speaker route first, camera controls only on the completed production Windows video stack. Include webcam privacy denial/device loss and high-DPI icon checks. |
| V07 | Both | Planned | L02/L03 | Clean builds inspect packaged icon resources and published executable; visual acceptance on actual Android launcher/settings and Windows Explorer/taskbar/tray. |
| V08 | Both | Planned | V01-V07 | Full platform regression and interoperability suite. A release is blocked by any authentication, revocation, hidden-capture, teardown, or false-state-reporting failure. Preserve Android signing certificate continuity; produce outputs only under `D:\LAN-Messenger\outputs`; local commit only unless push is requested. |

## Android feasibility note

The app targets API 34 and already declares camera/microphone foreground-service
types and permissions. Android 14 applies while-in-use restrictions to camera and
microphone foreground services: background creation/access is not universally
available merely because runtime permission was previously granted. Therefore the
exact “auto-answer while locked/backgrounded” result is a physical acceptance item,
not a promised implementation result. The supported fallback is a visible incoming
call notification that the user taps to bring the app into an eligible state.

## Recommended execution order

1. L01-L03 (icon work is independent and can be completed first).
2. T01-T06 plus V01-V03 (safe, useful voice auto-answer on both platforms).
3. T07, T09-T10 and Android V04-V05 after the current Android video candidate is
   accepted as a production baseline.
4. T08 and Windows V06 only along the existing R05 Windows video migration path.
5. V07-V08, status/comparison updates, signed/local packages only when separately
   requested, then a local commit.

