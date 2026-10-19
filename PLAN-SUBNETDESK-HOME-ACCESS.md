# SubnetDesk main-screen access correction

Date:2026-10-09, Asia/Jerusalem. Status: execution explicitly approved by user "نفذ".
HA-01..05 source implemented; HA-06 automated PASS/paired acceptance open; HA-07 packaged,
Android installed, Windows switch blocked by old authentication/settings dialog. Production code,
automated verification, packaging and actual device acceptance are separate checkpoints.

User requirements (latest correction): temporary code always visible on main screen; password-only setup
under Settings as Unattended access; no forced Arabic texts in Android or Windows.
Target: Windows receiver, Android sender. No automatic Android receiver parity or LAN
Messenger integration. Keep private updater/camera restrictions and signing identity.

Verified build82 defects: DesktopHomePage normal-mode build takes _buildNavigationSidebar,
but LanOptionalAccess was added only to buildLeftPane (incoming-only branch). Its isolated
widget tests missed normal home integration. New Android/Windows labels were hardcoded Arabic
instead of respecting selected language. Existing username/password controls are in the
LAN settings dialog, with no clearly separated Unattended access settings section.

2026-10-09 user removed the username requirement: Unattended access and sender connection
must ask for password only, not hide a still-required username. Current LanLoginRequest and
session/credential validation require access_username; this is a Both authentication/interop
change, not merely a Windows label edit. Design capability/version handling and receiver
configuration/credential migration before execution. Never invent a hidden fixed username,
send remembered legacy credentials to unverified peers, or silently downgrade authentication.
Preserve existing password hashes/Keystore entries unless a deliberate compatible migration
is necessary; reject unsupported password-only peers clearly, no automatic credential fallback.

Proposed behavior for approval: on a ready, unlocked interactive Windows receiver, code
appears without opening settings or manually generating it, rotating after expiry/use.
This changes build82's off-by-default/manual generation behavior and must be implemented
explicitly, not by repeatedly enabling auth from a UI polling callback. Code remains volatile,
6 digits/120 seconds/single use/5-failure revocation; no secret in discovery/logs/disk.
Preserve authentication throttling when rotating, especially after failed attempts.
On unavailable/locked/outgoing-only/service-backed states show an honest unavailable status,
not a usable/stale code and never silently broaden backend availability. No enabling OS
service/prelogin/firewall/unlock behavior under the term Unattended access.

| ID | Platform | Small task | Status | Depends on | Acceptance / testing |
|---|---|---|---|---|---|
| HA-01 | Windows | Trace normal/incoming-only home, receiver lifecycle and existing password config | Complete | None | Actual branches/listener/storage audited; native interactive availability gate retained, no service expansion. |
| HA-02 | Windows | Separate main-screen code card from access settings | Code implemented; actual UI pending | HA-01 | Normal/incoming-only source wiring and compact card tests PASS. Runtime visibility still requires new83 window inspection. |
| HA-03 | Windows | Backend-owned code initialization/rotation | Code implemented; native test in progress | HA-01,02 | Listener owns automatic code; stop/lock gate, single use, guess budget across rotation/cooldown implemented. Actual paired rotation/lock acceptance pending. |
| HA-04 | Windows | Settings > Unattended access password-only configuration and explicit password access control | Code implemented; fake-widget tests PASS | HA-01,04b | One obscured password field, no username; blank preserves configured hash. Checked encrypted config stores and explicit independent password-disable gate. Human persistence/disable/restart acceptance pending. |
| HA-04b | Both | Password-only sender/auth contract and saved-credential compatibility | Code implemented; native interop in progress | HA-01 | Additive capability/wire3 after verified encrypted probe; old/disabled peer fails without credential fallback. Empty-name encrypted Keystore payload preserves old entries. Native encrypted loopback and actual minimum-payload device verification pending. |
| HA-05 | Both | Remove forced Arabic strings in newly added access/credential UI | Implemented; automated label checks PASS | HA-02,04,04b | English keys through translate; no global language override/lang-template edits. Actual Android selected-language screen acceptance pending. |
| HA-06 | Both | Automated and interoperability verification | Flutter101/101 +36 native/wire/hash + runner2 PASS; paired open | HA-02..05,04b | Access12/loopback3/payload4/updater3/camera3/protocol6/wire4/hash1 PASS. Analyzer no errors/60 legacy warning-info findings. Actual Android minimum-payload Keystore synthetic test PASS; live Android password/code/manual English UI observed. Home source wiring is not full Windows runtime. |
| HA-07 | Both | Distinct next private candidates, packaging and actual acceptance | Both83 built/staged/packaged; Android installed, Windows switch needs human | HA-06 | Windows native4m58s/Flutter74.2s/post-build101 PASS; first child Git PATH failure repaired process-locally, retry native1.59s. Android signer/no-camera/16KB/exact-native PASS, original2075/mic grant preserved. Source snapshot2787bf3/private gates1223+1354 PASS. Old82 auth/settings dialog needs human close; no83 Windows runtime/paired acceptance claim. |

Actual acceptance checklist: main screen fresh launch displays code; expiry and successful
login replace it; old code rejected; failed guessing cannot gain unlimited attempts via rotation;
manual approve/deny/timeout still gated; Settings password-only persistence and disabling
password access; Android remembered password/restart/forget/trust/new-IP; selected English
stays English; paired session-only chat and ring remain independently open acceptance tasks.

Before execution read current PROJECT_STATUS.md and affected platform records. Preserve all
unrelated LAN Messenger work and original apps/data/service settings. Use one helper checkout,
local commits only, no push. Update implemented/automated/device records separately and correct
earlier statements that implied the code card was accessible in build82's normal home UI.
