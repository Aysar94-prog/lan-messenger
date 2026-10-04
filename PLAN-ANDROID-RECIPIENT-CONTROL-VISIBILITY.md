# Android recipient controls follow Slave permissions

2026-10-04. User requested recipient controls be hidden when the recipient is not
a Slave, then explicitly authorized execution with "start".

| Task | Platform | Status | Depends on | Acceptance / tests |
|---|---|---|---|---|
| S01 | Android | Implemented; automated passed | None | Call-scoped verified TLS grant query, initially hidden, refresh every5s only during caller-side connected video-capable calls. Late responses from another/ended call ignored. |
| S02 | Android | Implemented; automated passed | S01 | Recipient speaker appears only for remote-speaker scope8; camera on/off/front/rear only for camera scope4. Voice/video auto-answer grants alone expose neither. Receiver-side authorization remains authoritative. |
| S03 | Android | Implemented; automated passed | S02 | Hide on zero grant, failed query, local revocation, certificate mismatch, unavailable network or confirmation older than10s. Overlay key includes visible scopes; controls rebuild when scopes change. Action rechecks current call and scopes before dispatch. |
| S04 | Android | Automated passed; device call UI pending | S03 | PermissionDevicesCheck40/0 real TLS; RecipientControlsCheck28/0 for all16 masks, unknown/expired/failure/revocation and late-response boundaries. CallCheck422/0 and DirectConnections34/0. |
| S05 | Android | Candidate installed; physical acceptance pending | S04 | Original-key ARM64 Android2.2.67/code94 on USB SM-A075F R8YY80A8VLB and SM-S908E. Verify real call controls for no grants, camera-only, speaker-only and revoke; existing Wi-Fi fault may interfere. Record UI acceptance separately. |

Informational last-known Slave list entries remain available as before. A fresh
call-scoped confirmation is additionally required for controls; a stale list entry
cannot enable controls. Grant direction is recipient -> caller, never the caller's
local trusted-call grant. Polling targets only the active recipient and respects
Direct connections restrictions. No wire command, Windows code or grant authority
changed. Remote revocation is reflected by the next successful5s refresh; failure
hides controls and expired confirmation is never used.

Test candidate: `D:/LAN-Messenger/outputs/LanMessenger-2.2.67-slave-controls.apk`.
SHA-256: `dec9705555ef96ee7ce741e6e322a1aa5ec550d64a2b47d171be74d214e07816`.
Production Java/DEX/APK build and v2/v3 signer verification pass. Installation is
an upgrade retaining existing app data, not physical call-screen acceptance.
