# LAN Messenger 3.0.0 release

User explicitly requested saving all project work and issuing Windows/Android version 3.
No push authorized. Outputs stay in D:/LAN-Messenger/outputs; private keys excluded.

| Task | Platform | Status | Dependencies | Acceptance/testing |
|---|---|---|---|---|
| R3-01 Preserve source | Both | Done | None | Local8506520 saves remaining audio work/docs; planner logs/private keys excluded |
| R3-02 Version | Both | Done | R3-01 | Windows3.0.0, Android3.0.0/code99 verified |
| R3-03 Package | Windows | Done | R3-02 | Self-contained portable x64 ZIP, .NET9.0.9 + native DLL/NOTICE; isolated-data startup alive |
| R3-04 Package | Android | Done | R3-02 | Four ABIs, original signer7f4a0794..., manifest3.0.0/code99 |
| R3-05 Verify/archive | Both | Done | R3-03/R3-04 | UI11/11, packaged native138/138, SHA256, notes/status/local archive; physical acceptance remains separate |

User reports Windows physical camera cover was closed and is now open. Previous paired
phone image reached Windows; Windows-originated useful-image retest, listening acceptance,
10-minute stress and complete MT1-MT6 remain pending. These are not claimed PASS by packaging.
Known unrelated group_membership regression remains open. Portable Windows bundles .NET Desktop Runtime9.
