# Remove camera calling/access without breaking screen sharing

Date:2026-10-08. User requested "video call? cancel it" during SubnetDesk helper work.
Planning-first: user confirmed SubnetDesk View camera removal and explicitly approved execution.
No new video-call feature has been implemented. Android build's video codec library is
also required for remote desktop screen transport; do not remove that dependency.
Scope confirmed: SubnetDesk View camera only. Do not modify LAN Messenger video calls.
Keep screen sharing/control and voice calls unless the user explicitly changes that scope.

| ID | Platform | Task | Status | Dependencies | Acceptance / notes |
|---|---|---|---|---|---|
| NV-01 | Both | Confirm target app/feature and trace camera entry points | Source review complete | None | SubnetDesk View camera; no LAN Messenger mutations. |
| NV-02 | Both | Remove selected camera-call/view controls | Source implemented | 01 | Mobile connect button, three peer menus and desktop session toolbar removed; shared connect handler rejects camera routes. |
| NV-03 | Both | Reject disabled camera access in native backend | Source implemented | 01 | Native client start/session APIs and encrypted login/scope handling reject camera; capability false. Screen sharing/auth/voice unchanged. |
| NV-04 | Both | Regression and interop tests | In progress | 02,03 | Policy/session-construction tests, full Flutter/native builds; physical old-peer denial/screen/voice acceptance pending. |
| NV-05 | Both | Distinct candidates/device acceptance | Pending verification | 04 | New1.3.3+78 avoids confusing updater-only1.3.2+77 intermediate. Preserve original app/data/signing. |

Permanent updater shutdown and already-approved buzzer/voice ring/access modes remain
independent. Current1.3.2 private build checkpoint does not claim camera removal.
