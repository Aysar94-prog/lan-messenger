# Windows video continuation execution evidence

Date: 2026-10-06. User execution instruction: `نفذ`. Source base: local `f4f3791`.

## Working-tree classification (WVR-01)

The tree was already dirty when execution began. Existing work was preserved and integrated where
it belongs to this Windows video task; this checkpoint does not attribute all touched code to this turn.

| Group | Paths | Treatment |
|---|---|---|
| Existing call hardening/tests | `windows/CallController.cs`, `windows/ChatWindowCalls.cs`, `tests/CsharpHarness/CallVideoCheck.cs` | Reviewed and completed, including the previous LastFailureReason resets and AcceptWireChecks. |
| Existing video enabler/transport/rendering | `windows/CallVideoNativeBridge.cs`, `windows/CallVideoNativeMedia.cs`, `windows/native/lm-webrtc-bridge.cpp`, `windows/ICallVideoMedia.cs`, `windows/CallView.cs`, `tests/video-feasibility/windows/NativeBridge/BridgeCheck.cs` | Extended and verified as one integration unit. |
| Existing permissions/recipient UI and probes | `windows/CallRecipientControls.cs`, `windows/ChatWindowDialogs.cs`, `windows/PeerEngine.cs`, `windows/Program.cs`, `tests/CsharpHarness/CsharpHarness.csproj` | Retained; HEAD would otherwise lack the recipient-control source required by its dirty harness. |
| Existing audio work | `windows/WebRtcCallMedia.cs`, `windows/CallAudioIo.cs` | Preserved; the production SIPSorcery/G722 path remains the audio owner. These earlier audio changes are not silently attributed to the video implementation. |
| Existing real-audio investigation | `tests/native-audio-readiness`, `windows/video-calling/Capture-T03-Timestamps.ps1`, `EVIDENCE-WVC-T03-REAL-AUDIO.md`, `PLAN-WVC-CONTINUATION-SMALL.md` | Preserve historical evidence. Readiness checks updated for current ABI/reason text; they still use dummy audio and do not prove native real-audio acceptance. |
| Existing status/planner/log artifacts | platform status records, `.ai-planner`, `.build-bridge.log`, `.tests-run.log` | Preserve; no broad staging or cleanup. Status receives a new dated entry. |
| New execution work | `windows/CallSession.cs`, `windows/CallVideoActions.cs`, `windows/CallVideoCoordinator.cs`, native build-script improvements, `tests/native-video-media`, `tests/WindowsCallUi` | Purpose-built completion and regression evidence. |

## Implementation

- Real default Windows camera capture through libwebrtc DirectShow, only after the managed capture
  gate; probing/ringing do not enumerate or open camera hardware. Bounded 640x480/15fps request with
  bounded driver-selected format and orientation applied by the capture module.
- One independent video m-line, VP8 only, ICE m-line index 0. The earlier uncommitted native bridge
  generated audio+video SDP, violating the existing shared validator; real native SDP is now validated
  in the integration suite rather than relying on fake SDP.
- A sendrecv video transceiver exists before offer without acquiring a camera. Camera stop/restart
  reuses its sender and does not require renegotiation. The production audio connection stays separate.
- Native local/remote frame callbacks, copied managed pixels, one pending frame per UI sink, malformed
  frame rejection, bitmap disposal, resize-clamped preview and local preview rendering.
- Native output buffer serialized against concurrent polling/commands/disposal; export/ABI binding
  failure releases the library. Callback unregistration waits for a callback already in progress.
- Native JSON errors now throw at the managed boundary. Local camera startup failure reports its
  reason and keeps the receive leg/audio alive. A no-frame watchdog delegates camera cleanup to the
  capture-owning coordinator worker; front/rear requests are refused for the default desktop camera.
- Generation disposal can recreate the video peer for a later attempt; final owner disposal also
  releases a v2 call that never initialized a video generation.
- ACCEPT send failure terminates the call; terminal snapshots own their failure reason so queued UI
  callbacks cannot read the next call's reset/changed reason. Initial unavailable-video acceptance
  falls back to an audio ACCEPT; accept work runs off the UI thread.
- Mid-call remote video requests expose Accept/Decline controls. Controller effects arm coordinator
  deadlines/start negotiation after sending once; original-caller acceptance now starts an offer.
- Native build compiles its captured source snapshot. Compiler waiting no longer remains blocked on
  the desktop console host after cl/link complete. App-local DLL lookup precedes development outputs.

## Verification actually performed

| Check | Result | Limit |
|---|---|---|
| Windows Release build | 0 errors; existing ChatWindowVoice CS1998 warning | Source/development binary, not a release package |
| Focused call/video harness | 359 passed, 0 failed | Includes 13 controller checks, 5 local-effect/lifetime checks and 3 camera-failure/retry checks beyond 338; peer lookup is a verified fixture, not a TLS/device acceptance claim |
| Native ABI | 39/39 | Hardware-free lifecycle/bounds/capacity |
| Native managed/transport integration | 138/138 | 20 production-adapter lifetimes; three actual ICE/DTLS/VP8 generated loops; validated real offer/answer and ICE index; no Android peer |
| Local physical default camera | 2/2 | Local preview frames and stop/reopen; no remote description/ICE peer, no transmitted camera content |
| Windows call UI | 9/9 | Native hidden test form: upgrade controls, 1,000-frame burst, invalid frame, resize and disposal; not all-DPI physical acceptance |
| NativeAudioReadiness | ALL PASS (24 checks) | Dummy audio; no CoreAudio or replacement-audio claim |
| Shared contract suites | 113 frame + 38 capability records agree three-way; Android focused suites pass | Syntax/policy interoperability, not moving-image acceptance |
| Full tests/run.ps1 | Stops at known group_membership failure | `FullMember8 hasn't updated to a version that supports it`; same class recorded against clean 902e92d earlier; no claim of repair |

After the known group failure, group_migration_broadcast, ownership_transfer and
offline_lifecycle passed individually in
`D:/LAN-Messenger/outputs/.build/windows-video-completion-final-20261006`.
The older WindowsUi suite initially hit port 43872 occupied by the development app;
the owned app was stopped and this suite passed on retry (exit 0). This is distinct
from the new WindowsCallUi 9/9 result above.

Native verified artifact:
`D:/LAN-Messenger/outputs/.build/video-media/bridge-d50ab22f26fc46978ab695998b7956af/LanMessenger.WebRtc.Native.dll`

SHA-256: `5405ff31f0abaf74e08033a6a8fab51cd557e52d7de9b19ce7510d09c1b4ea7a`.
The folder retains the exact compiled source snapshot, compiler arguments, library hash, notices,
PDB, map and build logs. Earlier artifacts were preserved.

## Android dev candidate and physical blocker

SM-A075F USB serial `R8YY80A8VLB` appeared during execution. Installed version was observed as
2.2.69/code96 (not assumed code97). ARM64 dev candidate:
`D:/LAN-Messenger/outputs/LanMessenger-2.2.71-wvc-completion-dev.apk`.

The original signer was verified:
`7f4a07943d01da1266e4f2d3ce757165c9619c9a4ef74e741c58f8eee08d4161`.
`adb install -r` succeeded; installed metadata confirms 2.2.71/code98 and firstInstallTime remained
`2026-10-04 12:42:49`. No uninstall/clear/key regeneration or Android source change occurred here.

The phone UI reports `Direct connections · 1 online` and exposes only the verified `ultra` contact.
Windows `Lap` reports SM-A075F `Offline · Verified`. The devices are on 192.168.1.53 and 192.168.1.12,
respectively, but a same-LAN address does not override the phone's selected-device policy. That policy
was preserved. MT1–MT6 and two-direction moving-image/listening acceptance remain pending until the
user includes the Windows contact in the intended phone Direct connections selection (or supplies
another already-permitted Windows↔Android test pair).

## Remaining completion work

### Paired-device continuation, 2026-10-06

User explicitly authorized adding Lap while retaining ultra. USB authorization was restored.
Observed and saved Direct connections enabled with both verified ultra and Lap selected;
phone then showed Lap Online and Windows showed SM-A075F Online. No other contact selected.
One first ringing attempt timed out during UI automation recovery (not an answer failure).
A second Android-originated voice-only call was accepted through the Windows UI and reached
Connected/Voice at 10:36:36 UTC. Android logged OFFER/ANSWER and MEDIA_READY in both directions.
Windows hung up after approximately 39 seconds: end=LocalHangup, cause=none; Android logged
REMOTE_HANGUP from Connected. No camera was started. This proves paired signaling/connection
and clean teardown, not audible quality or video-image acceptance. Live camera transmission
confirmation and user listening acceptance remain required.

Local source checkpoint includes the reviewed inherited video enabler and recipient-control
dependencies, not only changes authored this turn. Earlier audio-only working-tree edits
remain unstaged. Builds/tests above used this shared working tree; no isolated clean-commit
build or final release acceptance is asserted.

- Actual Windows↔Android call and video acceptance, live recipient controls/revocation, fault cases,
  all-DPI checks and the 10-minute call/stress gate.
- Alternate desktop camera selection is not implemented; production currently uses the default camera
  and honestly refuses front/rear semantics. Default camera capture has physical local evidence.
- Final packaged-bit/legacy compatibility acceptance, version selection and release packaging after
  explicit release approval. Windows version remains 2.2.42; no release or push is claimed.
