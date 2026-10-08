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
| NV-04 | Both | Regression and interop tests | Windows builds/automated checks pass; physical pending | 02,03 | Camera native3/3, updater native4/4 (feature compiled), protocol6/6, Flutter65/65 and native/Flutter releases pass. Physical old-peer denial/screen/voice acceptance pending. |
| NV-05 | Both | Distinct candidates/device acceptance | Windows staged/running; Android pending build | 04 | New1.3.3+78/private-gated source ZIP complete, visible new backend owns21118. Firewall permission requires user action; paired acceptance pending. Preserve original app/data/signing. |

Permanent updater shutdown and already-approved buzzer/voice ring/access modes remain
independent. Current1.3.2 private build checkpoint does not claim camera removal.

Execution checkpoint: source committed locally in helper565ea3e, no push. Windows frontend
1.3.3+78 and Flutter65/65 passed; native camera3/3 and updater4/4 passed. Final native release
passes; Windows/source ZIPs complete at helper12a8dac. Visible new backend PID25332; Firewall
permission prompt requires manual user handling. No paired/device acceptance claim.
User approved switching the old test receiver to the new candidate after build completion;
recheck active sessions before switch, preserve installed original files/service configuration.
Android side-by-side APK remains gated by actual native dependency/Rust/APK build completion.

Device test order:

1. Verify new Windows executable/version and owning receiver process; no old backend confusion.
2. Original Android -> new Windows: screen/control and voice still work, incoming voice rings.
3. An old client's View camera request is refused; no camera session or permission prompt.
4. New Android candidate has no View camera action; deep-link/native camera attempts denied.
5. New Android -> new Windows: screen/voice, buzzer indication/sound and mute/rate limits.
6. Record physical acceptance separately from build/unit success. Access modes/Keystore/chat
   cleanup are not in this checkpoint; do not describe pending features as delivered.
