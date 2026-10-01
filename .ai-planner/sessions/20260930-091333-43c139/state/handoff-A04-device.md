# A04 handoff — real WebRTC media, built and validated on a physical device

Date: 2026-10-01
Platform: Android
Status: A04 complete (real media working end-to-end on device). Scaffold removed. Unit tests PASS=142 FAIL=0.

## What this phase did

Took the Android voice-call work from "real media adapter written but unverified" to "WebRTC
negotiates and carries audio on a physical device", using the connected Samsung SM-S908E
(Android 16 / SDK 36, arm64-v8a) over network ADB at 192.168.1.4:5555.

## Verified on device

Native stack loads and the media path carries real audio packets. Evidence from logcat:

```
NativeLibrary: Loading native library: jingle_peerconnection_so
nativeloader: Load /data/app/.../lib/arm64/libjingle_peerconnection_so.so: ok
JavaAudioDeviceModule: HW AEC will be used / HW NS will be used
LanCallMedia: WebRTC native stack OK in 13 ms; hwAEC=true hwNS=true
```

A loopback self-test connected two real `WebRtcCallMedia` instances inside the one process
(offer -> answer -> ICE -> DTLS-SRTP -> audio) and passed:

```
offer created: 1156 bytes, audio m-lines=1, opus=true
answer created: 1114 bytes, opus=true
media ready: caller=true callee=true candidatesA=5 candidatesB=3
caller stats valid=true rx=195 lost=0 jitter=0.0 rtt=0
callee stats valid=true rx=163 lost=0
mute round-trip OK
SELFTEST PASS
```

So confirmed on real hardware: native library load, hardware AEC/NS, SDP offer and answer with
Opus, ICE host candidates (5 from the caller, 3 from the callee), DTLS-SRTP, bidirectional audio
packets, statistics, and mute.

## Production code changes

`WebRtcCallMedia` was rewritten from the reflection-based version to **direct `org.webrtc`
imports**, compiled against the AAR `classes.jar`. This was the flagged risk from the previous
handoff, and it was justified — the reflection version could not have worked. `javap` against the
real AAR showed three assumptions were false:

- `PeerConnection.RTCConfiguration` has no no-arg constructor (only `RTCConfiguration(List<IceServer>)`)
- `MediaConstraints.mandatory` / `optional` are `final` fields, so reflective assignment would throw
- `AudioSource` has no public constructor; it must come from `factory.createAudioSource(...)`

Each of those would have surfaced only as a runtime failure on a real call. Direct imports also
corrected two logic bugs: the reflection version re-applied the remote description inside
`createAnswer` (the controller already applies it, so the native state machine would have
rejected it), and stats use the newer `getStats(StatsObserver, ...)` / `StatsReport` API rather
than `RTCStatsCollectorCallback`.

`MessengerService` now probes the native stack once in the same background thread that builds the
`CallController`, then installs `WebRtcCallMedia.Factory` when the probe succeeds and
`FakeCallMedia.Factory` when it does not. A device that cannot run WebRTC degrades to fake media
instead of failing every call. The probe logs to tag `LanCallMedia`.

## Bugs found by runtime testing and fixed

1. **SDP plumbing refactor bug.** Converting the anonymous `SdpObserver` into a named class left
   the callback writing to `observer.sdp` while `createSdp` still read a never-assigned `out[0]`.
   Symptom was `WebRTC SDP negotiation timed out` after 19 ms even though the native layer had
   produced 1157 bytes of valid SDP. Fixed by reading the observer's own field.

2. **Listener callbacks ran on the WebRTC signaling thread.** `org.webrtc` delivers
   `PeerConnection.Observer` callbacks on the thread that created the `PeerConnection`. A listener
   doing blocking work — writing a signaling frame to a socket, or the self-test feeding
   candidates to the peer — stalled the media thread and deadlocked it against a caller waiting on
   `runOnSignaling`. This produced `The WebRTC signaling thread did not respond` on the second
   peer connection. Fixed with a dedicated single-thread `callbackExecutor`; all listener delivery
   goes through `post(...)`, and `onMediaReady` now fires at most once per session.

3. **`onAddTrack` / `onTrack` signature change** — updated to the `RtpReceiver` / `RtpTransceiver`
   forms present in this AAR.

## Build pipeline bugs found and fixed

The scripts had never been run end to end, and each of these was real:

1. **`prepare-webrtc.ps1` and `build-voice.ps1` had `))` syntax errors** — the scripts could not
   parse at all. Also non-ASCII box-drawing characters in comments broke parsing under the default
   encoding; comments are now ASCII.
2. **`continue` inside a `ForEach-Object` block terminated the whole script.** With `-Arm64Only`,
   the arm64-v8a directory printed and then the next ABI hit `continue` and killed the run with
   exit code 0 and no error. Replaced with an inverted `if` guard.
3. **Native libs were packaged with backslashes.** `aapt add` stores the path verbatim, producing
   the entry `lib\arm64-v8a\libjingle_peerconnection_so.so`, which Android's loader does not
   recognise. Verified in the APK after the fix: `lib/arm64-v8a/libjingle_peerconnection_so.so`.
4. **`build-voice.ps1` overwrote a release artifact.** It removed the output file and wrote
   `LanMessenger-2.2.6.apk`, destroying the existing 2.2.6 release APK — which `build.ps1`
   deliberately refuses to do. The voice build was preserved as
   `outputs/LanMessenger-2.2.6-voice-dev.apk`, and the script now refuses to overwrite, with a
   new `-ApkSuffix` parameter defaulting to `-voice-dev`.
5. **`$ErrorActionPreference = 'Stop'` aborted on harmless javac deprecation notes** that
   PowerShell surfaces as `NativeCommandError`. Now `Continue`, with explicit `$LASTEXITCODE`
   checks after every native call.
6. **Only one dex file was added**; WebRTC needs more than one on some inputs. Now every `*.dex`
   d8 produces is added, with a hard failure if none.
7. **`-VersionName` / `-VersionCode` were cosmetic** — they only named the output file, while the
   installed version came from the manifest. An APK named `2.2.7` reported `2.2.6` / 33. The
   script now patches a copy of the manifest.
8. **`aapt` rejects a `-M` path whose filename is not exactly `AndroidManifest.xml`**, reporting
   the misleading `No AndroidManifest.xml file found` even for a byte-identical copy. The patched
   manifest now goes in its own directory under that exact name.
9. **`build.ps1` no longer compiled at all**, because `WebRtcCallMedia` now imports `org.webrtc`
   and `build.ps1` had no AAR on the classpath. Since the direct imports are exactly what caught
   the API bugs, reflection was not an option. `build.ps1` is now a thin wrapper over
   `build-voice.ps1` (the single pipeline), keeping its signing-key contract and gaining
   `-VersionName` / `-VersionCode` / `-Arm64Only`. Its previous hardcoded output
   `LanMessenger-2.1.1.apk` was also stale and would have always failed the refuse-to-overwrite
   check.
10. **`-SkipWebRTC` was removed** from `build-voice.ps1`. With hard imports there is no
    "build without WebRTC" mode; the switch could only ever produce a compile failure.

## Verification performed

- 37 production source files compile with 0 errors against the AAR.
- `tests/CallCheck.java`: **PASS=142 FAIL=0**. Note the desktop test compile needs
  `CallCheck.java`, `MiniJson.java` **and** `TestProtector.java` together, with the production
  output dir and the AAR on `-classpath` (two-step compile).
- APK builds, signs and verifies (v2 + v3 signature schemes), installed over 2.2.6 / 33 with
  `adb install -r`, and reports `versionCode=34 versionName=2.2.7`.
- Cold start after `force-stop` reloads the native library and re-probes successfully; no
  `FATAL` / `AndroidRuntime` entries; process stays alive.
- APK contents verified: `AndroidManifest.xml`, `classes.dex` (825 KB, 257 references to
  `org/webrtc/PeerConnectionFactory`), `lib/arm64-v8a/libjingle_peerconnection_so.so` (11.7 MB).

## Security note — scaffold removed

The loopback self-test needed an adb-triggerable entry point, which I added as an
`android:exported="true"` receiver with no permission. That is a genuine security exposure: any
app on the device could trigger it. It has been **removed from both the source tree and the
manifest**, and I confirmed against the installed package that the receiver is gone while WebRTC
still initialises.

A copy is kept at `.ai-planner/sessions/20260930-091333-43c139/state/on-device/CallSelfTest.java.txt`
for future device validation. Re-adding it is a deliberate temporary step, and it must be removed
again before any release build. It also needs a bug fix first: it fed both sides' ICE candidates
to the callee rather than each to the other, and the two-side reference split is now documented
in the saved copy.

## Still unverified (needs a second device or a human)

- **No real two-party call.** A loopback proves the media stack but not the signalling path over
  the LAN between two devices, nor the `CALLCONNECT` handshake. The Windows peer does not exist yet.
- **Audio was not listened to.** Packets flowed with `packetsLost=0`, but nobody confirmed the
  far end produced intelligible speech.
- **A07/A07p route application** — `CallRoutePolicy` chooses a route, but applying it to
  `AudioManager` and the proximity sensor is still unwritten. `WebRtcCallMedia` currently only
  sets `MODE_IN_COMMUNICATION`, which is the minimum for correct call audio.
- **A05 arbitration under a live call** — claims are implemented and unit-tested, but a real
  recording-during-call conflict on device is untested.
- **Foreground-service microphone behaviour at runtime** — the manifest declares
  `connectedDevice|microphone` and requests `FOREGROUND_SERVICE_MICROPHONE`, but no call has
  actually started a microphone-typed foreground service on this device.
- **AT-series device checks** from the plan.

## Build commands for the next agent

```
.\android\prepare-webrtc.ps1                                  # once, caches the AAR
.\android\build.ps1 -Arm64Only -VersionName 2.2.7 -VersionCode 34   # standard build
.\android\build-voice.ps1 -Arm64Only                          # voice build, -voice-dev suffix
```

Device APK is `outputs/LanMessenger-2.2.7.apk` (6 MB, arm64 only). All-ABI is ~47 MB.
Work remains uncommitted in the working tree, per the local-commits-only preference.

## One thing I damaged and could not restore

The first successful `build-voice.ps1` run wrote `outputs/LanMessenger-2.2.6.apk`, overwriting the
existing 2.2.6 release artifact (the script removed the target before writing, and `build.ps1`
deliberately refuses to do this). The original 2.2.6 APK was 131,415 bytes and is **gone**. I moved
the voice build aside to `outputs/LanMessenger-2.2.6-voice-dev.apk` and then made the script
refuse to overwrite, but the 2.2.6 artifact itself can only be recovered by rebuilding from a
2.2.6 checkout. The overwrite was my error, not a pre-existing condition.

## Recommended next steps

1. Ask the user whether to restore `LanMessenger-2.2.6.apk` by rebuilding, and whether 2.2.7
   should be treated as the new release version.
2. A07/A07p: wire `CallRoutePolicy.reconcile` to `AudioManager` and the proximity sensor.
3. A08-A10: call views, call notifications, bind `CallUi` into `MainActivity`, and the
   "Allow incoming calls" switch — all still unwritten.
4. A11-A12: matrix validation and packaging.
5. A real two-party call, which needs a second device or the Windows peer.
