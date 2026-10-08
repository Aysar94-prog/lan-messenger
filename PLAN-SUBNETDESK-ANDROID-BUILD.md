# SubnetDesk Android candidate build gates

2026-10-08. Existing approved side-by-side build workflow, not a new feature request.
Scope Android build support only; retain original package/data and LAN Messenger signer.
Windows1.3.3+78/source12a8dac artifacts remain preserved and are not rewritten by these repairs.

| ID | Platform | Task | Status | Dependencies | Acceptance / notes |
|---|---|---|---|---|---|
| AB-01 | Android | Scoped SDK/NDK/JDK and Rust build tools | Complete | None | SDK36, NDK28.2, Temurin17, Rust1.82/arm64, cargo-ndk3.1.2; process-local environment. |
| AB-02 | Android | Native dependency build on Windows host | Complete | 01 | Main libraries, libsodium and OpenSSL3.5.2 pass; Android AOM assembler and FFmpeg delimiter fixes verified. |
| AB-03 | Android | Correct cross-compilation host/target handling | Native gate PASS | 02 | API21 flags/NDK19 includes, explicit MSVC host linker, static cross-built crypto and separate sodium archives. Root target guard and scoped pinned hwcodec build-only patch; Cargo cache/default dependency/lock unchanged. Physical vcpkg view fixes Rust static-library discovery through junction; refresh only opus derived cache (25.4MiB removed, regenerable). |
| AB-04 | Android | Build actual modified Rust library and candidate APK | Rust release PASS; APK building | 03 | ARM64 ELF/load alignment0x4000; JNI_OnLoad/Java_ffi exports present. Rebuilt/copy SHA2560656543e…eee1ec match. Native build2m02s,115 compiler warnings plus override warning recorded (not classified against an Android baseline). Manifest/signature gate still pending. |
| AB-05 | Android | Verify packaging/signing/versions and corresponding source | Pending | 04 | Preserve candidate signer for later upgrades; original app never replaced. No private signing material in source ZIP. |
| AB-06 | Both | Physical new/new and old/new compatibility | Pending | 05 | Camera denied/absent, screen/control/voice preserved, buzzer/ring/mute/rate limits; Windows permission prompt handled by user. |

Source camera guards/updater/buzzer implementation is separate from build success. A failed
native build must not be delivered as a working APK. Keystore credentials/access modes and
reconnect chat-memory cleanup remain approved but not implemented in this checkpoint.
