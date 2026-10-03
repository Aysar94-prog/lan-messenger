# Paired native/Android feasibility — 2026-10-03

Scope: Both, test-only R02/R04 media evidence. Not a production video release.
Frozen plan-v007 SHA256 remains
`4ea7877d6643b5c89246b075d7a301d73b38bea28f644303383fa42f7ca0efbf`.

## Actual results

USB controls use R8YY80A8VLB, Samsung SM-A075F Android 16 arm64; media uses
LAN ICE/DTLS-SRTP, not adb forwarding. Windows x64 uses pinned M155 native input;
Android test APK uses M150. Generated 320x240 moving frames, no physical camera.
Audio capture/playout uses real devices; RTP counters are not audible acceptance.

| Original caller / video offer origin | Evidence directory under outputs/.build/video-feasibility/evidence | Windows decoded / motion | Android decoded / motion | Result |
|---|---|---|---|---|
| Android / Windows | paired-c77d4e0320f149419c4b9b4053677b6b | 86 / 85 | 91 / 90 | PASS |
| Windows / Android | paired-74257b8f512140d1b93f9d5e9c6a3181 | 88 / 87 | 97 / 96 | PASS |

Each result.json records two-way G722 packets before/during/after VP8 upgrade,
actual codec reports, encoded/decoded RTP frames, video-off, disposal of video
endpoint, malformed isolated Android video SDP, native isolated video rollback
and rejected malformed native SDP. Healthy audio counters continued on all of
these tested failure paths. Both results confirm production package version and
install timestamps unchanged; net.lanmsg.chat remains 2.2.42/versionCode 69.
Test APK is separate net.lanmsg.chat.videofeasibility, throwaway signed.

## Rejected mechanism and implementation findings

Single-PC Connected reoffer successfully carried generated VP8 in paired-b0f7417977b941de93e52cec3ffef822,
but native rollback subsequently removed audio media/capture in
paired-116608541c0e44188d5bf1d1d987b4e8. Do not select that mechanism.
The qualified media candidate is a separate video-only secured PeerConnection,
with its own factory and no audio track. Production authenticated call/generation
binding still needs the A02b contract and A07/W03 implementations.

Native Windows capture sometimes remained unstarted despite upstream
SetAudioRecording(true): observed Recording=0 and outbound audio samples=0.
Explicit InitRecording/StartRecording return checks fixed reverse direction;
errors now fail visibly. COM MTA ownership stays on the native network/worker
thread. Do not treat an SDP sendrecv direction as proof of captured audio.

Android explicit video addTransceiver was not reused for incoming offers and
gave one-way media. AddTrack plus selection of its actual sender transceiver
fixed both directions; paired-298c6d8e1e7e45d39d2d94bb585918bd is the earlier
failed evidence, not a pass. No failed evidence was deleted.

## Reproduction and artifacts

Explicit build: tests/video-feasibility/windows/build-probe.ps1.
Native output directory: outputs/.build/video-feasibility/windows-probe-f76db67782324c3ea8bb7d7df5063854.
lm-native-endpoint.dll SHA256:
`42381b7e85f0ed860c8ef012d5d4bd35a3cef03ccfb270130c197f087797000a`.
This is the first paired revision. Resource-bound hardening then rebuilt the DLL:
`e4f9e92fcdead1c4e6a136b8539569fc45d1ecad63677de69f71ee4a0a779c72`.
Its offline clean publish is clean-publish-0102e79d5f904808b6828a3a131f22ed
under the native output directory. Codec/ABI/endpoint tests and clean-folder
load/teardown passed. Paired exact-rebuild Windows caller/Android video offer
also passed in paired-d10513aabd9b4ecfb48531a527d43d41 (same evidence root).
APK: outputs/.build/video-feasibility/android-69cf518390ec40d295103992df238b0c/VideoFeasibility.apk,
SHA256 `a3e1a2edadb85fb0b96799ca2345a311896fdf611cd5b2cc28827fd86c4f481e`.
Build used v2/v3 throwaway signing, never production signing inputs.

Invoke pair-android.py with --serial, --dll, --driver and --adb explicit paths,
--mechanism separate --rollback-check. Run both --caller android --upgrade-origin
windows and --caller windows --upgrade-origin android. Output contains local
raw test SDP/network information: retain privately, do not paste into diagnostics.
The normal production test runner and project references remain unchanged.

## Task handoff / open gates

- R02c/d: implemented and actual local/paired endpoint tests pass; offline clean
  publish and deeper resource-stress acceptance remain to complete R02.
- R03: two-way camera-free G722 with Android AP01 passed; archived production
  baseline/CALLCONNECT/actual audible acceptance still Pending. Not Windows
  production replacement approval.
  Additional native ↔ unchanged Windows 2.2.42 production media adapter check
  passed both offer directions: native caller sent=35/received=46; legacy caller
  sent=34/received=46. Voice-only fingerprinted SDP/G722 and old mute state checked.
  Test driver loads the existing baseline assembly only for explicit historical
  compatibility; no old RTC package reference is added to the new test project.
  Initial statistics parser assumed an object; corrected to actual M155 report
  array, then rerun passed. This is media-only, not CALLCONNECT or audible proof.
- R04: selected fallback media directions, decoded motion and tested failure
  continuity passed; production consent/collision/authentication remains future.
- A02b: media evidence available, final shared wire contract/fixtures still Pending.
- A04–A13: no production video implementation or signed video release yet.
- R05: explicitly authorized by user's "Yes, migrate Windows calling too";
  dependencies and small-task execution plan in R05-MIGRATION.md. No switch yet.
- Real cameras, audible continuity, packaged UI, lifecycle and final release
  acceptance remain Pending, not replaced by generated-frame counters.

Usage checkpoint: 59% five-hour / 17% weekly, not near exhaustion. Save a new
handoff before a near-limit pause. Production signer, data and frozen plan intact.

## End verification checkpoint

Windows production Release build passed, existing CS1998 warning only. All 44
Android production Java sources freshly compiled into outputs/.build/video-feasibility/
production-end-compile-85646b38e3834cc88458b13a0646b862 (not an APK/release).
Unchanged cached production CallCheck rerun: 408 PASS/0 FAIL; draft contract
43 PASS/0 FAIL and A03 permission gate 18 PASS/0 FAIL. Native cap=4 rejected the
fifth creation; 20 video-only native teardown lifetimes passed with no audio track.

Unmodified tests/run.ps1 with TestRoot paired-end-regression passed microphone
device checks 20/20 (earlier mmresult failure did not recur), voice-message
interoperability, secure messaging and transfer checks. It failed in the full
16-member group reinvite scenario: FullMember8 capability could not be confirmed.
An isolated unchanged group_membership.py retry also failed, earlier at full-group
pairing with handshake/task cancellation diagnostics. Later runner groups and UI
tests were not reached. Full-suite PASS is not claimed. No unrelated group code
or tests were modified to suppress these failures.

Current usage checkpoint 65% five-hour / 18% weekly, not near exhaustion.
Human audible check requested separately; optional --audio-only
--audio-observation-seconds 60 keeps an explicit listening window and still
records audible acceptance Pending until the user reports actual hearing.
