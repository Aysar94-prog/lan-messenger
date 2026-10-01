# Handoff — A00 + A03b (WebRTC Dependency Evaluation & Build Integration)

**Date:** 2026-10-01
**From:** lead → next agent
**Phase:** Voice Calls — A00 WebRTC Evaluation + A03b Build Integration

## Status

| ID | Status | Evidence |
|---|---|---|
| A00 | **Done** | Library evaluated: io.github.webrtc-sdk:android:150.7871.01 from Maven Central. 433 org.webrtc classes, 4 ABI native libs (arm64-v8a 11.7MB, armeabi-v7a 6.5MB, x86 12.2MB, x86_64 15.4MB), total AAR 46.9MB. MIT licensed, standard org.webrtc API. See `.ai-planner/sessions/.../state/A00-evaluation.md` |
| A03b | **Done** | AndroidManifest updated (+MODIFY_AUDIO_SETTINGS, +extractNativeLibs=true, +microphone feature), WebRtcCallMedia skeleton created, prepare-webrtc.ps1 + build-voice.ps1 created for AAR → APK pipeline |

## Changes

### AndroidManifest.xml
- Added `MODIFY_AUDIO_SETTINGS` permission (unconditional, per plan R4-02)
- Added `android:extractNativeLibs="true"` to application element
- Added `android.hardware.microphone` feature with `required="false"` (messaging-only devices remain supported)

### New: WebRtcCallMedia.java
Skeleton implementation of ICallMedia. Currently returns UnsupportedOperationException for
media methods — full org.webrtc API wiring requires the AAR on the classpath and is
estimated at 1-2 days of work. The skeleton allows the project to compile and test
without the WebRTC dependency present.

### New: prepare-webrtc.ps1
Downloads webrtc-sdk:android:150.7871.01 AAR from Maven Central, extracts
classes.jar and jni/*. so files. Run once before building with -SkipWebRTC=$false

### New: build-voice.ps1
Complete APK build integrating WebRTC:
- Adds classes. jar to javac classpath (enables WebRtcCallMedia org.webrtc imports)
- Passes both project classes. jar and AAR classes. jar to d8
- Packages . so files into lib/<abi>/ inside APK via aapt
- Supports -SkipWebRTC (small APK, FakeCallMedia only) and -Arm64Only (12MB vs 47MB)

## Remaining before Phase A2 (real media)

1. **Full WebRtcCallmedia implementation** — wire org.webrtc APIs:
   - PeerConnectionFactory creation with audio constraints
   - AudioSource/AudioTrack for capture
   - SDP offer/answer via SdpObserver
   - IceCandidate handling
   - RTCStatsReport → ICallMedia.Stats mapping
   - Proper dispose() cleanup
2. **Compile with AAR on classpath** — test that WebRtcCallMedia compiles against real org.webrtc
3. **Build minimal APK** — verify native loading works on device
4. **A04** — Install real media in permanent service host

## Test evidence
```
CALLCHECK PASS=75 FAIL=0 (no AAR dependenсy — pure Java)
```