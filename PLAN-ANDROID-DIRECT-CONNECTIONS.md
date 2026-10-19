# Android direct connections

2026-10-04. User approved execution with "yes do it" after the planning proposal.
Meaning: LAN Messenger stays unavailable to everyone except selected verified
devices at explicit LAN IPv4 addresses. This is application networking policy.

| ID | Platform | Status | Dependencies | Notes / acceptance and tests |
|---|---|---|---|---|
| D01 | Android | Implemented | None | Optional direct-only flag, independent of actual Online/Offline; strict Offline continues to stop sockets. |
| D02 | Android | Implemented; device UI pending | D01 | Menu > Direct connections lists verified contacts with editable IP:port, prefilled saved/current addresses. Encrypted persisted targets bind identity and certificate. Invalid/nonlocal IP and invalid port rejected. |
| D03 | Android | Implemented; automated passed | D02 | No discovery socket, receive thread or announcements in direct mode. Incoming IP prefilter; authenticated identity/pin gate before HELLO response. Outgoing TLS and fast-file sockets restricted. |
| D04 | Android | Implemented; automated passed | D03 | Selected messages, ordinary attachment downloads, call channels and raw fast transfers in both directions pass real socket tests. Other contacts remain queued/offline. Remote SDP/ICE candidates restricted to selected addresses. Native media acceptance pending. |
| D05 | Android | Implemented; device UI pending | D04 | Status and notification label Direct connections. Save stops active calls/network before applying policy and resumes only if Online requested. Reopening persists preference; malformed settings block all targets and offer explicit recovery. Deletion clears target metadata and retains restriction. |
| D06 | Android | Passed | D04 | DirectConnectionsCheck 34/0, including wrong certificate, revocation, reload, corruption recovery, delete-all, selected TLS delivery/files/call handoff, raw transfers, bounded presence and blocked contacts. CallCheck 422/0, pacing 28/0, permission TLS 36/0, all video-contract suites and service lifecycle guard pass. |
| D07 | Android | Pending device acceptance | D06 | Original-key ARM64 2.2.66/code93 test candidate installed on SM-A075F USB R8YY80A8VLB and SM-S908E. First-install times retained. Enable selected peer on device, test menu/save/background/Offline and call/video, then observe SM-A075F Wi-Fi for at least 10–15 minutes. No Wi-Fi repair claim. |
| D08 | Both review | Wire unchanged; Windows direct-mode pairing pending | D04 | Existing LM4 frames/TLS remain unchanged. Android policy is local; Windows feature/UI unchanged. No new cross-platform physical compatibility acceptance. |

Idle selected peers are checked about every eight seconds over authenticated TLS
to refresh existing peers before their normal 12-second presence expires;
direct presence lasts 45 seconds. Queued work on a reachable peer uses existing
2-second retry pacing. There is no automatic discovery fallback when an IP changes;
edit the selected address. Normal mode remains the default. Enabling direct mode
while Offline saves the policy without starting networking.

Candidate: `D:/LAN-Messenger/outputs/LanMessenger-2.2.66-direct.apk`.
SHA-256: `b816dcea145b4a1fb9c54d0cbccad2eb18bf8b630a6242c0069c4bc2285b3ac5`.
v2/v3 verification passes, certificate matches Android 2.2.65.
Earlier `-direct-dev.apk` and `-direct-test.apk` are superseded by `-direct.apk`.
This is a test candidate, not a final release or Wi-Fi acceptance.
