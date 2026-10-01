# A00 Evaluation Report — WebRTC for Android

**Date:** 2026-10-01
**Status:** Evaluated — ready for A03b integration

## Library: io.github.webrtc-sdk:android:150.7871.01

- **Source:** Maven Central (`repo1.maven.org`)
- **License:** MIT (BSD-style for WebRTC itself)
- **API:** Standard `org.webrtc` package (same as Google's official bindings)

## AAR Contents

| Component | Size | Notes |
|---|---|---|
| classes.jar | 1.0 MB | 433 classes in `org.webrtc` package |
| arm64-v8a/libjingle_peerconnection_so.so | 11.7 MB | Required for modern phones |
| armeabi-v7a/libjingle_peerconnection_so.so | 6.5 MB | Legacy 32-bit ARM |
| x86/libjingle_peerconnection_so.so | 12.2 MB | Emulator support |
| x86_64/libjingle_peerconnection_so.so | 15.4 MB | Emulator support |
| **Total AAR** | **46.9 MB** | All 4 ABIs |

## Build Integration Plan

Current build.ps1 uses raw javac + d8 + aapt. Integration steps:

1. **Download AAR** from Maven Central (one-time, cached)
2. **Extract classes.jar** → add to javac `-classpath` for compilation
3. **D8 step** → merge classes.jar dex output with project dex
4. **Extract jni/*.so** → add to APK via aapt under `lib/<abi>/`
5. **Manifest:** add `android:extractNativeLibs="true"` to `<application>`
6. **Manifest:** add `android.permission.MODIFY_AUDIO_SETTINGS`

## APK Size Impact

| Component | Size |
|---|---|
| Current APK (2.2.6) | ~131 KB |
| New app classes (call subsystem) | ~50 KB (est.) |
| WebRTC classes.jar (dexed) | ~100 KB (est.) |
| Native .so (all 4 ABIs) | ~46 MB |
| **Estimated APK** | **~46.3 MB** |

Single-ABI (arm64-v8a only): ~12 MB — option for smaller builds.

## Key org.webrtc APIs needed

- `PeerConnectionFactory` — factory for connections and media
- `PeerConnectionFactory.InitializationOptions` — native init
- `PeerConnectionFactory.Options` — codec/network config
- `PeerConnection` — connection state, observers
- `PeerConnection.RTCConfiguration` — ICE servers (none for LAN)
- `MediaConstraints` — audio constraints
- `AudioSource` / `AudioTrack` — local audio capture
- `SdpObserver` / `SessionDescription` — SDP offer/answer
- `IceCandidate` — ICE candidates
- `PeerConnection.Observer` — callbacks
- `RTCStatsReport` / `RTCStats` — statistics for quality

## Decision

**Proceed with this library.** The API is standard org.webrtc, the AAR is usable with raw
javac/d8/aapt, and all required features (Opus, DTLS-SRTP, AEC, statistics) are present.
The 46 MB universal APK is large but acceptable for sideload deployments per the plan.