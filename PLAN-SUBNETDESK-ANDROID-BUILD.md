# SubnetDesk Android candidate build gates

2026-10-08. Existing approved side-by-side build workflow, not a new feature request.
Scope Android build support only; retain original package/data and LAN Messenger signer.
Windows1.3.3+78/source12a8dac artifacts remain preserved and are not rewritten by these repairs.

| ID | Platform | Task | Status | Dependencies | Acceptance / notes |
|---|---|---|---|---|---|
| AB-01 | Android | Scoped SDK/NDK/JDK and Rust build tools | Complete | None | SDK36, NDK28.2, Temurin17, Rust1.82/arm64, cargo-ndk3.1.2; process-local environment. |
| AB-02 | Android | Native dependency build on Windows host | Complete | 01 | Main libraries, libsodium and OpenSSL3.5.2 pass; Android AOM assembler and FFmpeg delimiter fixes verified. |
| AB-03 | Android | Correct cross-compilation host/target handling | Native gate PASS | 02 | API21 flags/NDK19 includes, explicit MSVC host linker, static cross-built crypto and separate sodium archives. Root target guard and scoped pinned hwcodec build-only patch; Cargo cache/default dependency/lock unchanged. Physical vcpkg view fixes Rust static-library discovery through junction; refresh only opus derived cache (25.4MiB removed, regenerable). |
| AB-04 | Android | Build actual modified Rust library and candidate APK | PASS | 03 | Native2m02s/APK1164.2s. ARM64 ELF/load alignment0x4000; JNI/attention exports. Raw/copy0656543e…eee1ec; reproduced AGP strip/APK B0e892cd…c4cdd36.115 warnings plus override/font/plugin warnings recorded, no Android baseline comparison. |
| AB-05 | Android | Verify packaging/signing/versions and corresponding source | PASS | 04 | Helper1.3.3/code2078/arm64/no CAMERA; v2 signature/16KB ZIP alignment. APK A36585e6…82341f; source7a45b44 ZIP1330-entry/private gate PASS,02131885…c7cd7. Candidate signer preserved privately; original app never replaced. |
| AB-06 | Both | Physical new/new and old/new compatibility | Startup smoke PASS; paired pending | 05 | Side-by-side install/activity441ms/process alive/no matching startup fatal errors. Original1.3.0 unchanged. Phone4KB pages. Helper mic/notifications denied and Windows Firewall gate need user handling; screen/control/voice/camera denial/buzzer/ring/mute/rate limits not physically accepted. |

Source camera guards/updater/buzzer implementation is separate from build success. A failed
native build must not be delivered as a working APK. Keystore credentials/access modes and
reconnect chat-memory cleanup remain approved but not implemented in this checkpoint.
