# A05 resource/interface checkpoint — 2026-10-03

Android execution remains authorized under unchanged plan-v007. User accepted
audio testing complete; do not repeat manual listening. A02b selects VP8 on a
separate secured video-only connection. No signed production video APK exists.

## Implemented source

- ICallMedia exposes optional video operations, separate recoverable callbacks,
  borrowed frame sinks and renderer context leases without Android UI imports.
- CallVideoResources lazily owns the service EGL root. Media and renderer leases
  are bounded and idempotent; shutdown rejects new leases but retains the root
  until existing media and renderers release it.
- MessengerService owns that resource owner. WebRtcCallMedia supplies the shared
  context to encoder/decoder factories and releases the native factory before
  its context. Initialization is idempotent, avoiding double factory references.
  Failed native factory disposal retains the context/ADM and rejects new factory
  creation until restart rather than freeing resources still used by native code.
- FakeCallMedia implements generation-scoped video with explicit capture gates,
  bounded identity-based sink bindings, camera controls and video-only failures.

These are A05 foundations, not completed production video media. Actual adapter
video() remains null until A06; renderer surfaces and controller effects binding
remain A07/A08. Do not advertise CALLCAPS or enable video controls prematurely.

## Verification and limits

Fresh complete production Java compilation: 48 sources passed, output
`D:\LAN-Messenger\outputs\.build\video-feasibility\android-video-foundation-50615ded671244bd96210e3c830b3d86`.
Resource fixtures: 19 passing, including 50 repeated lifetimes. Fake video
fixtures: 18 passing. Consent/command/confirmed syntax fixtures: 64/27/100 passing;
earlier draft/permission fixtures: 43/18 passing. Windows production build passed
with the existing CS1998 warning; Windows calling migration is not implemented.

Full regression `video-foundation-regression` passed its Windows microphone
checks (20/0) and earlier messaging/group/attachment checks, then stopped in
transfers.py: destination write failed because D: had only 53,248 bytes free.
This is NOT a full-suite pass. Later tests did not run. No unrelated app fix made.
Removed only that run's generated 128 MiB large-original.bin to permit checkpoint
verification; fixture is reproducible by tests, not user data. Failure logs and
peer state retained. Need adequate free space before full regression/release.

Latest verification completed: D8 with production and cached WebRTC classes,
minimum API 26, passed. Freshly compiled CallCheck against the current 48-source
production classes passed 408/0. Explicit video-contract runner passed all seven
groups (289 checks total). git diff --check passed; frozen plan hash unchanged.
No signing, installation, physical camera or UI acceptance performed.

Usage checkpoint: 89% five-hour / 22% weekly. Save this checkpoint before taking
on substantial native media work. Only about 127 MiB free remains on D: after
removing the generated fixture. Request space/approval for additional cleanup;
do not delete older builds, evidence, release packages or user files silently.

## Next task and acceptance

1. A06: production separate video-only PC using the SAME Android native factory
   as voice (do not attach a second factory to the shared audio device module).
   Add VP8 selection, source/track, remote track and bounded ICE. Camera opens
   only after fresh permission/hardware/foreground checks and bilateral consent
   plus authorized secured media readiness. Video failure disposes only video.
2. A07: authenticated CALLCAPS and version-2 controller/session/effects binding,
   generations, collision and timeout fixtures. Keep v1 voice wire camera-free.
3. A07d/A08–A11: diagnostics, real renderers/self-view/controls and foreground
   camera lifecycle. USB adb/scrcpy UI permission exists; no actual UI check yet.
4. A12/AT06/A13: full regressions, audited original-key candidate, upgrade and
   actual device acceptance. Outputs stay outside source; no push or signer change.

Acceptance not yet earned: actual EGL/device lifetimes, physical camera capture,
production encrypted video, controller integration, screen rotation/teardown and
packaged release. Keep these distinct from fake/native compilation evidence.
