# Windows sender three-method correction

2026-10-09. Execution explicitly authorized by user "نفذ الحل سريعا".
Baseline helper3af9c05/common29462e6, release1.3.7/build85. Windows receiver and Android
sender already support code/manual, but Windows sender has password/accounts only.
Scope: Windows UI/sender payload only, existing wire/native ABI unchanged. Android APK85
retained, including unresolved debug signing/unknown warning (not certified safe). New
Windows logicalbuild86 keeps public1.3.7 and verified85 native DLL; no Rust version/source
change/rebuild needed. Private MSI same-public-version upgrade must be explicitly enabled
and documented; installer preserves config. No install/auth/security/service mutation.

| ID | Platform | Task/status | Dependency | Notes / acceptance / testing |
|---|---|---|---|---|
| WS-01 | Windows | Inspect sender entry points / Complete | Approval | Add and generic discovered/recent/favorite peer Connect inspected. Explicit legacy account payloads retain their path. Terminal/RDP/tunnel advanced paths remain unchanged; receiver unchanged. |
| WS-02 | Windows | English method UI and payload / Implemented | WS-01 | Add dialog + central blank-credential Windows Connect selector. Password/saved password, six-digit code, manual request. Code/manual send existing access_method1/2 without passwords/accounts/remember. Invalid code blocks submission; switching/cancel/dispose clears fields. Explicit legacy accounts retain their existing password path. |
| WS-03 | Both review; Windows tests | Regression and compatibility / Automated complete | WS-02 | 13 rendered-widget/routing-source guards and full post-build Flutter123 PASS; new widget/test analyzer clean. Routing analyzer26 pre-existing info notices/no errors. Native85 payload parser/encrypted sender reviewed: not Android-only. Verified unchanged85 DLL, no native/JNI/paired runtime dispatch acceptance claim. |
| WS-04 | Windows | Versioned MSI/portable/source / Complete | WS-03 | Frontend-only1.3.7+86 release163.4s with exact85 DLL hash gate PASS. MSI metadata/isolation/inclusive same-version Upgrade flag/all125 extracted hashes PASS; ICE zero errors, expected ICE91 and exact equal-version ICE61. Source snapshot e3d0a63/common29462e6/private exclusion1240 entries PASS; checksums/release/acceptance notes staged. Local commits only,85/original apps preserved. MSI cannot gate logical-build rollback within1.3.7; no install. |
| WS-05 | Windows devices | Actual two-PC acceptance / Pending | WS-04 | Human install/launch on both PCs then password/code/manual Accept/Deny/timeout and old-peer failure; unavailable if second test PC not connected. Do not infer paired acceptance from mocks/builds. |

Update PROJECT_STATUS/windows STATUS/comparison with implementation, automation and actual
device acceptance separately. No push, branches/remotes changes or Android signing rebuild.
