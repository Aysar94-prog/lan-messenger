# Masters / Slave menu plan

Prepared 2026-10-03. Planning only; execution of this new feature awaits explicit
user instruction. Existing uncommitted call-control work is separate.

## Execution checkpoint

User authorized execution with "start". M01/M02 review/M04/M05 implemented;
M03 implemented with explicitly labeled session-only last-known cache (no remote
grant disk persistence). Refresh occurs on opening a list, every five seconds while
open, and manually; reconnect is reflected on the next open/refresh. M06 status and
signed build/install completed. T01 real TLS 36/0 and call regression422/0 pass.
T03 physical direction, reverse partial grant and revoke removal pass on both
phones, original grants restored. Offline cache/certificate mismatch covered by
automated checks only. T02 parser review and simulated legacy connection fallback
pass; actual Windows runtime interoperability remains pending, no Windows change.
APK 2.2.57/code84 remains a test candidate, not a final release.

## Scope and definitions

Android side menu only, based on the current Android call-control discussion.
Keep the user's exact labels and direction:

- Masters: verified devices **I granted** one or more trusted-call permissions.
- Slave: verified devices that **granted me** one or more trusted-call permissions.

A device may appear in both sections. Show device name, online/offline state,
and individual voice auto-answer, video auto-answer, camera control and speaker
control scopes. Masters opens local grant editing/revocation; Slave is read-only
about the peer's grant, with access to its conversation/call actions.

Local grants already exist. Remote grants are not currently synchronized;
the Slave list cannot be inferred from local grants or verified-contact status.
Add authenticated permission-status discovery, not remotely writable permission
settings. The recipient's local stored grant remains the only authority for
auto-answer and control commands.

## Small tasks

| ID | Platform | Status | Dependencies | Notes and acceptance criteria |
|---|---|---|---|---|
| M01 | Android | Planned | None | Define directional list model and scope labels. One device can belong to both lists; zero-scope local grants are absent from Masters. |
| M02 | Both (review); Android (implementation) | Planned | M01 | Review both peer implementations and define bounded optional authenticated permission-status query/response. Verify peer identity/certificate; expose only the requesting peer's scopes; old Windows/Android peers remain compatible and report unknown, never fabricated permissions. No Windows UI or video parity work. |
| M03 | Android | Planned | M02 | Track certificate-bound remote status, refresh on reconnect/query and grant changes. Mark cached offline results as last-known with timestamp; never use cache to authorize capture/control. Clear on revoke, forget, deletion and identity change. A remote zero-mask response removes the Slave entry. |
| M04 | Android | Planned | M01, M03 | Add Masters and Slave side-menu entries and list screens, direction explanations, empty states and per-device scopes. Masters editing reuses trusted-access consent UI. Slave cannot change the other device's grant. |
| M05 | Android | Planned | M04 | Update screen immediately after local changes; remote revocation propagates on refresh/reconnect. Do not automatically accept calls or start cameras when opening a list. |
| T01 | Android | Planned | M01-M05 | Automated tests: asymmetric and mutual grants, partial scopes, zero mask, persistence, revocation, unknown legacy peers, stale caches, certificate mismatch and malformed/unauthenticated responses. |
| T02 | Both | Planned | M02-M03 | Protocol interoperability: Android query to legacy Windows must leave messaging/voice calls working; unsupported status is unknown. Review Windows parsing before choosing extension framing. |
| T03 | Android | Planned | T01, M04-M05 | Physical ADB acceptance on both phones: grant A to B, verify A Masters/B Slave; reverse grant puts both in both lists; revoke removes correct membership after refresh; offline status is visibly stale; call controls remain caller-only. Stable LAN required. |
| M06 | Android / comparison | Planned | T01-T03 | Update Android and project status with implemented, automated and physical results separately. Build/install with original signer, preserve app data, local commit only; no final-release claim until acceptance passes. |

## Testing and handoff boundaries

No application code, protocol, build or device settings changed for this plan.
Current status documents lag the previously installed 2.2.55 test candidate;
do not interpret their historical pending-camera text as current source truth.
Existing call-control physical acceptance remains incomplete after a recipient
Wi-Fi loss. Keep that outstanding work distinct from this new menu feature.
