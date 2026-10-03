# Verification / resume commands

Run from `D:\LAN-Messenger\source`. Only the frozen plan-v007 is authoritative.

## Android test-only endpoint

```powershell
adb devices -l
tests/video-feasibility/android/build.ps1
adb -d install --no-incremental -r <TEST_APK returned by build>
adb -d shell pm grant net.lanmsg.chat.videofeasibility android.permission.RECORD_AUDIO
tests/video-feasibility/android/command.ps1 -Serial R8YY80A8VLB -Command loopback -Codec VP8 -Mode audio -Summary
tests/video-feasibility/android/command.ps1 -Serial R8YY80A8VLB -Command loopback -Codec VP8 -Mode inactive -Summary
tests/video-feasibility/android/command.ps1 -Serial R8YY80A8VLB -Command loopback -Codec VP9 -Profile 0 -Mode audio -Summary
tests/video-feasibility/android/command.ps1 -Serial R8YY80A8VLB -Command loopback -Codec H264 -Mode audio -Summary
tests/video-feasibility/android/command.ps1 -Serial R8YY80A8VLB -Command stop
```

These were run via USB on SM-A075F; recheck serial/model before resuming.
Native loopback checks real sent/received audio packets, encoded/decoded video,
decoded changing luminance, and audio after stopping video. It does not measure
audible continuity, lip synchronization, Windows packetization, or physical cameras.
Endpoint source `camera` is for explicit init/video commands, not generated loopback.

See `tests/video-feasibility/android/README.md` for external Windows offer/answer
and upgrade commands. WT01 must be explicitly run separately from regressions.

## Deterministic contract / permission checks

```powershell
tests/video-contract/run.ps1
```

Results: draft boundary 43/43; call-camera permission identity boundary 18/18.
These are provisional and must be rerun/revised after A02b. A03 tests do not close
AT02's consent/media/Activity race acceptance.

## Production compilation and voice regression

Current build outputs are under
`D:\LAN-Messenger\outputs\.build\video-feasibility\production-compile`.
All 44 production Java sources were compiled with JDK 17 `javac -source 8 -target 8`,
Android build-tools 35.0.0 core-lambda-stubs, android-34/android.jar and cached
webrtc-150.7871.01/classes.jar. A production jar and D8 min-api 26 DEX were built
from those sources plus the WebRTC jar; no prototype or draft classes are included.
Production signing/release is deliberately deferred until the plan's A13 gate.

`tests/CallCheck.java` and `tests/TestProtector.java` were compiled against those
production classes plus the Java harness output from the production regression
run and android.jar. It ran with:

```powershell
$java = 'C:\Program Files (x86)\Android\openjdk\jdk-17.0.14\bin\java.exe'
$root = 'D:\LAN-Messenger\outputs\.build\video-feasibility'
& $java -cp "$root\call-tests;$root\production-compile;$root\production-regression\java" net.lanmsg.chat.CallCheck
```

Final source voice-call regression: 408 passed, 0 failed.

## Full regression / failures

```powershell
tests/run.ps1 -TestRoot D:\LAN-Messenger\outputs\.build\video-feasibility\production-regression
dotnet build windows/LanMessenger.csproj -c Release --configfile NuGet.Config
```

Windows build passed with existing CS1998 warning. The unmodified full regression
failed at the recording-device checks: 11 pass / 5 fail, `mmresult 1` opening the
Windows microphone. A supplementary invocation ran the original runner text in
memory with only that failed hardware command skipped. It stopped at
`group_membership.py`'s Migrate-Java startup timeout (empty stderr), which also
repeated in a targeted retry. No test source or runner was changed to suppress
these failures. Do not claim a full-suite pass.

After the repeated group failure, an additional in-memory invocation starts at
`group_migration_broadcast.py`, using the same compiled harness output, to finish
unaffected tests. Group migration passed; ownership-transfer later had a separate
Java startup timeout. The isolated Offline tail passed. The Windows UI tail passed
through playback/seek, then stalled at the next recording action; the identified
test process was terminated and that runner ended with exit 1. Its remaining
recording/lifecycle checks are incomplete. Final results are recorded in HANDOFF.md.
Original failure logs:

- `production-regression/peers-c7e80cac57364431b5968c8878ea8f7a/Migrate-Java/stderr-806a245cfafe4261a8868e5505326579.log`
- `production-regression/peers-4669676837a74a85ad9ef76e131196fb/Migrate-Java/stderr-e73000d50b2549dd9ee819ffca5e2105.log`
- `production-regression/peers-c4e4411647164e1db87af4af89276ff0/Fifth/stderr-66f03c29c333417cba179344e09a7dd9.log` (ownership-transfer tail startup timeout)
