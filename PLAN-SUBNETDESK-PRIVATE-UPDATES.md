# SubnetDesk private helper: disable updater

Date: 2026-10-08. User explicitly approved permanent updater shutdown in the private build.
Scope: Both, private helper only. Preserve installed original SubnetDesk and its files.
Desired result: no automatic check/download/install, and no UI option to enable updates.
Manual replacement with a user-chosen private build remains possible; no upstream auto-install.

| ID | Platform | Task | Status | Dependencies | Notes / acceptance |
|---|---|---|---|---|---|
| SD-UP-01 | Both | Enumerate updater scheduler, direct APIs, legacy checks and UI | Source review complete | None | Startup scheduler, five FFI APIs, legacy Windows installer/CLI, Flutter About reviewed; mobile has no updater controls. |
| SD-UP-02 | Both | Add immutable private-build policy blocking all updater entry points | Implemented in source | 01 | Saved true preferences cannot enable scheduler/check/download/install; Windows legacy installer paths also blocked before file/service/UAC work. |
| SD-UP-03 | Both | Remove updater controls and build/package updater capability | UI removal/Windows packaging complete; Android pending | 02 | Private Windows1.3.3 release omits software-update; Android candidate preserves original app/data/signing boundary. |
| SD-UP-04 | Both | Tests and runtime verification | Automated native4/4 pass; paired runtime pending | 03 | Feature-enabled native tests pass at1.3.3; final no-updater release/CLI/version pass. New receiver running, manual Firewall approval/paired checks pending; installed original unchanged. |

Test tasks: policy tests; UI absence; direct API rejection; source search for bypasses;
relevant Flutter/Rust regression; distinctly versioned native builds; check runtime logs
for absence of update requests without claiming a log alone proves every network path.
Immutable native policy and entry-point guards implemented in source; updater UI removed.
Private builds omit software-update feature. Native tests deliberately compile the feature too,
to prove it cannot override private policy: native4/4 passed at1.3.3. Flutter frontend and65/65 pass;
final Windows native release and packaging pass. Visible new1.3.3 receiver running after user
approved switch; Firewall permission requires user action and physical acceptance remains pending.
Installed original files/service configuration are unchanged; reboot may restore old service.
