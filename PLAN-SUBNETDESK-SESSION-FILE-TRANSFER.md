# In-session Windows File Transfer / release1.3.8 build87

2026-10-09. Execution explicitly authorized: "نفذ وغير رقم الاصدار".
Baseline helper e3d0a63/common29462e6, installed Windows86 verified last diagnosis.
User narrows failure to Transfer file after a control session is already established.
Scope Windows: correct this route and password-required retry UI, no username requirement;
retain file-transfer scope/receiver authorization, never inherit a control grant as file access.
Android85 retained unless a demonstrated wire/ABI dependency requires revisiting scope.
Public Windows version changes to1.3.8 (visible native About + EXE/MSI), logicalbuild87.

| ID | Platform | Task / status | Dependency | Notes, acceptance and testing |
|---|---|---|---|---|
| FT-01 | Windows | Trace session toolbar, separate-window dispatch and auth retry / Complete | Approval | Generic Connect covered file mode; stale password-required fallback and ignored duplicate-tab payload identified. No secret logging. |
| FT-02 | Windows | Implement explicit session-file method selection + correct retry / Code complete | FT-01 | Three methods, owned file retry overlay and additive bridge on pending file session; active-channel and IO-queue guards. No control grant reuse; no wire change. Actual pairing pending. |
| FT-03 | Windows; Both review if shared | Automated regression / Complete PASS | FT-02 | Targeted26/full136 Flutter PASS, full138 with actual DLL version/new ABI PASS, isolated2 PASS after correcting test-only double-FRB-binding fixture; native13 new +50 prior regressions PASS, pure6+15/CTest3 PASS. Widget/test/smoke analyzer clean/routing60 old info, no errors/warnings. No live paired proof. |
| FT-04 | Windows | Version/build/MSI/source/checksums / Complete PASS | FT-03 | Native6m26s/UI121.1s1.3.8+87; MSI6A473880...473F70 Administrator/ProgramFiles/common Desktop+Start/old-user launch gate and125 hashes PASS, ICE0 errors/warnings. Source1250/private gate PASS, ZIP/source/checksums/releases ready. Helper productioneef9c45/snapshot4bd89d8/common29462e6. No install/uninstall/runtime switch;85/86 preserved, local commits/no push. |
| FT-05 | Windows devices | Real acceptance / Pending | FT-04 | Existing control -> toolbar FT with password/code/manual, receiver Accept/Deny/timeout, cancel keeps control, no duplicate/active-job interruption. Human closes/uninstalls old per-user Helper, installs87/UAC, checks desktop shortcut. Settings preserved; no automated install acceptance. |

No Android re-sign, OS security/service/permission changes, automatic receiver acceptance,
credential retention expansion or LAN Messenger integration. Update platform statuses and
comparison with implemented/automated/device acceptance separately after work.
