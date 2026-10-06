# LAN Messenger 3.0.0 release

User explicitly requested saving all project work and issuing Windows/Android version 3.
No push authorized. Outputs stay in D:/LAN-Messenger/outputs; private keys excluded.

| Task | Platform | Status | Dependencies | Acceptance/testing |
|---|---|---|---|---|
| R3-01 Preserve source | Both | In progress | None | Explicit source/docs commit; exclude planner logs/private keys |
| R3-02 Version | Both | In progress | R3-01 | Windows 3.0.0, Android 3.0.0/code99 > installed98 |
| R3-03 Package | Windows | Pending | R3-02 | Framework-dependent x64 publish + verified native DLL/NOTICE; no development-path dependency |
| R3-04 Package | Android | Pending | R3-02 | Multi-ABI APK, original signer, manifest version verified |
| R3-05 Verify/archive | Both | Pending | R3-03/R3-04 | UI regression, native packaged DLL integration, SHA256, release notes/status and local commit |

User reports Windows physical camera cover was closed and is now open. Previous paired
phone image reached Windows; Windows-originated useful-image retest, listening acceptance,
10-minute stress and complete MT1-MT6 remain pending. These are not claimed PASS by packaging.
Known unrelated group_membership regression remains open. Windows requires .NET Desktop Runtime9.
