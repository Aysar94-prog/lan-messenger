# Windows video calling — audited continuation plan

Revision: 2026-10-04 (second pass, implementation). Source baseline: local commit `f27f05b`.
Platform: **Windows**, with explicitly marked **Both** interoperability tasks.

This revision records an implementation pass, not a documentation review. Sections A
(contract/fixtures/architecture), C (managed v2 coordination) and E (managed grant
handling) are implemented in source and pass automated checks. Section B (the native
voice replacement) was deliberately **not** started. **No release was packaged and no
physical-device acceptance was performed or is claimed.** Every row below states its
own status; see "Evidence discipline for this pass" for what was and was not run.

## Scope and authority

Deliver production Windows one-to-one voice/video calls using the approved native
replacement, compatible with current Android video and legacy voice peers.
Include the previously requested trusted recipient controls as a dependent phase;
do not enable those controls before their grants and media paths are implemented.
Prior R05 migration and permissive transitive-license approvals remain valid.

This is the current Windows continuation plan. Preserve frozen
`.ai-planner/sessions/20261002-223910-bd586a/planning/plan-v007.md` and its hash.
R05, R01/R02 and feasibility handoffs remain historical evidence, not current
completion checklists. Do not restart completed Android phases from their older wording.

Non-goals: group video, screen sharing, recording, internet relay, call hold,
automatic reconnection, Android background-camera changes, Android Wi-Fi repair,
Windows Direct-connections UI, or unrelated attachment/compose changes.

## Deviations from the plan's fixed constraints, and why

Three deviations are recorded here rather than buried, because each one changes what a
later row is allowed to assume.

1. **Section B was reordered, not skipped — and is now starting.** Rows 05–08 and
   gate T02 assumed the native voice replacement lands first and that video activation
   follows it. An earlier pass implemented the managed v2 coordination (09–10) *ahead* of it,
   behind an `ICallVideoMedia` seam with `FakeCallVideoMedia` as the only implementation.
   Nothing regressed in voice: Windows voice stays on the proven SIPSorcery 10.0.17 +
   G722 + winmm adapter, which was untouched. **Why it was reordered:** WVC-03 was blocked at
   the time, on an advisory that has since been shown to be wrong — the first pass concluded
   CVE-2026-103631 was unresolved against the pin, when in fact the pinned commit *is* the
   fix (WVC-03 row). Sequencing video behind that false block would have produced no progress
   at all on the rows carrying the actual wire risk. **Now that WVC-03 passes, Section B
   proceeds** — WVC-08 (default factory switch and SIPSorcery removal) still gates real
   video, and production video stays disabled until the WVC-T03/T04/T05 and WVC-08 gates pass.
2. **`RuntimeIdentifier` is deliberately not set.** WVC-02 pinned `PlatformTarget=x64`,
   which is what fixes the compiled architecture and P/Invoke resolution. Setting a RID
   additionally drags in the win-x64 runtime and apphost packs, which the vendored
   offline feed does not carry, and it failed the build with NU1101. It belongs with the
   native adapter, where the apphost genuinely needs to locate libwebrtc's DLLs beside
   the exe. Recorded in `windows/LanMessenger.csproj` so it is not rediscovered as a bug.
3. **The video UI ships as a stage, not as rendered frames.** WVC-15's remote view,
   preview surface, draggable/clamped preview placement and button flows exist, but
   nothing paints into them: the renderer (WVC-14) and camera (WVC-12) are both absent,
   so the surfaces say so plainly rather than showing a blank "connected" picture.
4. **Video is gated off in this build rather than advertised and left unserved.** The
   user's explicit direction was that VP8 support and camera controls must not be
   advertised without a real backend. This is a behavioural change from the first pass,
   where both flags defaulted to true; see "This build advertises voice only" below.

## Evidence discipline for this pass

Implemented, and verified by the commands recorded per row: the strict v2 parser and
all v2 builders, the `CALLCAPS`/`CALLGRANTS` engine responders, the capability probe,
the consent/actions/coordinator layer, two-stage frame admission, v2 send-time
stamping and validation, the v1-preserving `CallController` integration, `CallSession`
plumbing, the `CallView` stage and controls, and all four grant bits in the trusted
call-access dialog.

**Not** performed: no `dotnet publish`, no zip, no manifest, no version bump, no
physical call, no webcam, no DPI measurement, no renderer benchmark, no Android
handset. `windows/LanMessenger.csproj` still reads `<Version>2.2.42</Version>`, and
the current shipped Windows release remains 2.2.42 with none of this in it.

**This build advertises voice only, and that is deliberate.** `CallVideoSupport`
defaults to false and `CallController.VideoEnabled` *cannot report true unless a
`VideoMediaFactory` is installed*, because the getter is `videoEnabled &&
VideoMediaFactory != null`. There is no assignment that produces a build claiming VP8
with nothing to serve it. So a peer probing this build gets no CALLCAPS reply, offers
voice from the start, and gets a clean v1 call — instead of being invited into
`media=video` and then waiting forever for a picture. The camera and video controls
never appear, because `VideoCapable` is false for every call.

This corrects a defect in the first pass, which is worth recording because it was
exactly the failure the gating was supposed to prevent: both flags defaulted to `true`,
and the app shell seeded the controller from the engine's default and then made the
engine read the result back — a self-referential pin to `true` with no path to `false`.
Installing the adapter means setting `VideoEnabled = true` **and** assigning the
factory; neither alone is enough, and the comment at the wiring site says so. Ten
`capability-honesty` checks now pin this, and they were confirmed to fail (3 of them)
against the old behaviour before being kept.

Automated verification actually run for this pass, from local commit `f27f05b`:

- `dotnet build windows\LanMessenger.csproj -c Release` — succeeded, 0 errors, and only
  the one pre-existing `ChatWindowVoice.cs(19,16) CS1998` warning.
- `tests/run.ps1` — full suite, exit code **0**.
- `CsharpHarness --call-video-check` — **317 passed, 0 failed**. The harness now also
  links `CallController`/`CallSession`/`CallChannel`/`CallSettings`/`ICallMedia`/
  `PeerEngine.Calls`/`CallLogMarker`, so controller-level behaviour is testable at all
  — it was not before this pass.
- `tests/video-contract/run.ps1` — `107 records agree between Android and Windows`
  (shared frame corpus) and `38 records agree between Android and Windows` (shared
  capability corpus), with 0 failures against the hand-authored expectation on both
  platforms; plus 432 Android-side video checks passing.

The shared corpora are three-way: Android's verdict, Windows's verdict and a
hand-authored expectation must all match. That is the only automated evidence here
that speaks to interoperability with Android, and it is evidence about *parsing and
serialization* — not about moving pictures.

## What is actually done

| Area / platform | Implemented or established | Verification and limits | Status |
|---|---|---|---|
| Windows current application | Version 2.2.42; SIPSorcery 10.0.17 voice-only `WebRtcCallMedia`; authenticated CALLCONNECT/v1; trusted voice auto-answer | No production native replacement, CALLCAPS/video coordinator, webcam capture or video renderer | Existing baseline, video NOT implemented |
| Windows dependency research | Native WebRTC M155 `m155.8059.2.0` selected for feasibility; pinned upstream core and notices reviewed; user accepted permissive transitive licenses | Dated audit is not a current security clearance. Older M150 wrapper remains on hold | Research/prototype input complete; production clearance pending |
| Windows native prototype | C ABI audio/video endpoints, G722, generated VP8, bounded handles, explicit CoreAudio startup, static CRT | Offline prototype publish; codec motion; cap/fifth rejection; 20 repeated teardown lifetimes | Feasibility implemented, not a production library |
| Both native paired media | Separate video-only secured PeerConnection passed VP8 moving frames in both caller directions; tested video failure/disposal preserved G722 packets | Physical Android endpoint but generated pictures, not physical Windows webcam. Test-only drivers, not authenticated production calls | Media feasibility PASS |
| Both legacy media | Native ↔ unchanged Windows 2.2.42 G722 media passed both offer directions and legacy mute checks | Media adapter exercise only; actual HELLO/READY/CALLCONNECT application path still needs testing | Partial compatibility proof |
| Both architecture | One-PC video rollback interrupted audio; separate-PC approach passed tested isolation | Single-PC production approach rejected | Decision settled |
| Both wire | Android A02b contract defines VP8-only independent video PC, consent, identity/generation binding and deadlines | Windows v1 implementation has not adopted it; additive trusted controls need reconciled fixtures | Core contract confirmed; Windows implementation pending |
| Android current application | Production video coordinator/capture/rendering exists; latest test candidate 2.2.67/code94 includes fresh-grant recipient visibility | Physical call/control acceptance remains distinct. SM-A075F Wi-Fi fault unresolved | Do not redo Android from zero |
| Audio listening | User accepts audio testing complete and requests no repeated listening | Continue automated continuity/mute/route checks; historic no-sound entries are not a renewed listening gate | Accepted by user |
| Windows regression baseline | Prior build/publish and targeted tests exist | Historical full-suite group-cap/reinvite failure is not a full-suite pass; reproduce and classify during execution | Known baseline issue |

Evidence references: [paired feasibility](PAIRED-FEASIBILITY.md),
[R01 audit](R01-AUDIT-HANDOFF.md), [R02 handoff](R02-HANDOFF.md),
[hardware inventory](W0-FEASIBILITY.md),
[Android contract](../../android/video-calling/A02b-CONTRACT.md),
[trusted access plan](../../PLAN-TRUSTED-CALL-ACCESS-AND-APP-ICON.md).

Read-only verification during this review confirmed:

- Frozen plan SHA-256: `4ea7877d6643b5c89246b075d7a301d73b38bea28f644303383fa42f7ca0efbf`.
- Test-only native package SHA-256: `0131cad1d573250a1b9423b4e36bdfdc5946a46e43efc2c8d48e6fc03efafb6a`.
- Hardened prototype endpoint DLL SHA-256: `e4f9e92fcdead1c4e6a136b8539569fc45d1ecad63677de69f71ee4a0a779c72`.
- Paired separate-PC runs `paired-c77d4e0320f149419c4b9b4053677b6b`,
  `paired-74257b8f512140d1b93f9d5e9c6a3181` and hardened
  `paired-d10513aabd9b4ecfb48531a527d43d41` retain passing results.

## Fixed design constraints

1. Keep original audio PC/G722 alive independently of video. Video errors dispose
   video only; audio failure remains fatal. No single-PC upgrade/rollback retry.
2. Use fresh verified TLS CALLCAPS per call, no cross-call cache. Unsupported,
   unknown and simulated-legacy peers use unchanged voice v1. Capability is not a grant.
3. Match A02b strictly: 64 KiB frame bound, 48 KiB SDP, 4 KiB ICE, 128 ICE per
   generation; integral signed-64 counters; peer/call/request/generation binding;
   original caller alone offers; audio generation 1; monotonically new video generations.
   Reject bad frames before heartbeat/deadline/accepted-sequence advancement.
4. Probe deadline 10 seconds, video consent 30 seconds, video negotiation 15 seconds.
   One outstanding request; lower UUID wins collision; no consent transfer;
   bounded retired-request tracking and no identifier/counter reuse.
5. Camera acquisition requires local consent/live OS permission or an explicit,
   certificate-bound trusted grant within the agreed policy. Never acquire during
   capability probing/ringing. Remote VIDEO_STATE is information, not authority.
6. Separate protocol parsing, call policy, native transport, capture and rendering.
   Marshal UI updates; never block the UI or call-controller locks on native work.
7. Preserve legacy signaling, voice messages, ringtone and TLS dependencies.
   Removing SIPSorcery does not authorize deleting BouncyCastle or all winmm code.
8. Use production-owned sources/package/integration. Do not ship the test-only
   NativeProbe project, generated source, feasibility DLL or throwaway Android signer.

## Small-task execution checklist

Status key: **Done** = supported by evidence above; **Partial** = reusable proof but
acceptance remains; **Pending** = not implemented; **Decision** = resolve before dependents.
Every row includes its own acceptance/testing gate. Execute in dependency order.
The status column below reflects the **2026-10-04 implementation pass**, not the
original documentation review. Rows left at Pending were genuinely not started — this
pass did not partially build them and then describe them as pending work in progress.

### A. Lock the baseline, contract and production inputs

| ID / platform | Status | Depends on | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| WVC-01 Windows baseline | Done | None | Recorded: clean tree apart from this task's own files; `dotnet build -c Release` succeeds with exactly one pre-existing `CS1998` warning in `ChatWindowVoice.cs`; `tests/run.ps1` exits 0. User's in-flight Android work was left untouched and is committed separately in `f27f05b`. | Reproducible baseline report — satisfied by the build + full-suite exit 0 above. No group-suite failure occurred on the baseline run, so there was nothing to classify at this point; see WVC-T03 for the failure that did occur later. |
| WVC-02 Windows architecture | Done (decision) | 01 | **x64 only**, approved by the user as a support reduction, because the native video adapter is libwebrtc, which ships x64 binaries only. Applied `PlatformTarget=x64`. `RuntimeIdentifier` deliberately deferred — see deviation 2 above. | Architecture support documented with explicit user approval. Physical camera enumeration and RGB/IR inventory remain under WVC-12 and are **not** done; no webcam was attached to this pass. |
| WVC-03 Windows production dependency | Done (gate PASS) | 02 | Reviewed 2026-10-04, **corrected and passed** — see [WVC-03-ADVISORY-REVIEW.md](WVC-03-ADVISORY-REVIEW.md). **This reverses the earlier STOP in this same row.** The blocker was reported as CVE-2026-103631 (High, buffer overflow in WebRTC) being absent from the pin; that was wrong. Fetching the pinned commit `f89edcb7…` from official upstream shows it is titled `[M155] Harden payload capacity and reduction checks in RTP packetizers`, carries `Bug: chromium:567088927` — the advisory's own bug id — and sits at `refs/branch-heads/8059@{34859}` (our branch), cherry-picked from `fc666626…`. **The pin IS the fix**; the earlier pass reached the opposite conclusion from an inferred branch-point date rather than from upstream evidence. No re-pin needed. Also corrected: the NOTICE inventory is **25** components, not 24 — the earlier count dropped `libc++` because its extraction regex excluded `+`. | Automated/read-only only: advisory window parsed from the Chrome Releases feed (10 media/RTC-relevant CVEs); patch inclusion resolved against official upstream; license screen against the delivered NOTICE; `audit-upstream.ps1` rewritten to audit **both** the upstream archive and the vendored package, derive its verdict from `$fail`/`$unresolved` instead of hardcoding it, and prove the package's `webrtc.lib` is byte-identical to the audited archive's (`c5ae79fe…`). Runs **PASS, exit 0**, and four negative tests (missing package, wrong artifact, tampered identity commit, unknown relation) all correctly return HOLD exit 2. Honest limit: 1 of 7 advisory rows is machine-verified; 3 are `ancestor` and 3 `scope`, carried from R01's reviewed upstream evidence and labelled as not machine-verified. **License delta is zero** — all 25 components are inside the set already approved, so nothing new to approve. Residual: Chromium core revision is not pinned by the 55-byte `DEPS` stub, disclosed rather than treated as blocking. Production video remains disabled regardless. |
| WVC-04 Both contract reconciliation | Done | 01 | Reconciled against the actual Android source rather than the provisional plan names: `CALLCAPS` → `LM4\tCALLCAPS\t2\tVP8`; grants `LM4\tCALLGRANTS\t1` → `1\t<0..15>` with `TRUSTED_AUTO_ANSWER_VOICE=1`, `TRUSTED_AUTO_ANSWER_VIDEO=2`, `TRUSTED_REMOTE_CAMERA=4`, `TRUSTED_REMOTE_SPEAKER=8`; v2 envelope `v/t/cid/seq/gen[/b]`. Windows parses and emits all of it. | Field/state table is the shared corpora plus `CallVideoProtocol`; every rejection case is pinned by a fixture, not by prose. No protocol downgrade: a peer that cannot answer CALLCAPS stays on unchanged v1 voice. |
| WVC-T01 Both wire fixtures | Done | 04 | `tests/video-contract/fixtures/call-frames.txt` (107 records) and `capabilities.txt` (38 records), read independently by `SharedFrameFixtureCheck.java`/`CallCapabilityFixtureCheck.java` and by `CallFrameFixtureCheck.cs`/`CallCapabilitiesFixtureCheck.cs`. Format is `name\|expectParse\|expectValid\|payload`, with `#SAMPLE`/`@name@` expansion and `b64:` for raw wire bytes. | **Both parsers accept and reject identically, and both match a hand-authored expectation, on all 145 records** — `tests/video-contract/run.ps1` diffs the two verdict files. Legacy v1 voice fixtures are included and unchanged. Serialization comparison respects JSON key-order semantics (payloads are verbatim JSON text). |

### B. Replace Windows voice safely before activating video

| ID / platform | Status | Depends on | Work and notes | Acceptance / testing |
|---|---|---|---|---|
Rows 05–08 and gate T02 were **not started** in the passes up to and including the WVC-03
gate rewrite; see deviation 1 above. They remain the correct path to production, and WVC-08
still gates real video.

One consequence must be stated plainly, because it is easy to misread: with no
`ICallVideoMedia` factory installed, `VideoEnabled` is false, so this build never
negotiates v2 at all. It sends v1 INVITEs, refuses inbound v2 INVITEs by closing the
transport, and advertises no VP8. The v2 machinery is therefore exercised by the
fixture corpora and the coordinator tests but is **not** on any path a real call
currently takes. A reviewer should read "v2 implemented" as "the envelope, grammar and
state machine are correct", never as "a peer has ever carried video with this build".

Note that the earlier alternative — advertise VP8, then answer `media=audio` — was
rejected on purpose. It keeps the call alive, but it makes the caller commit to a video
call that can never produce a picture, which is a worse experience than offering voice
from the start. The v2 *audio-only* fallback remains implemented and tested for the
adapter-integration stage, where a backend exists but a particular camera may not.

| WVC-05 Windows production native ABI | Done | 03 | Production C ABI bridge (`windows/native/lm-webrtc-bridge.cpp`) links pinned libwebrtc M155 (no device modules); exposes `lm_wr_abi_version`, `lm_wr_create`, `lm_wr_command`, `lm_wr_destroy`. Structural invariants: EnableMedia only, no ADM; `start-video` accepts only `synthetic` (rejects named devices); video-only disposal. Socket server not driven for ICE polling (by design). | Automated ABI-contract suite: 21/21 checks pass (`NativeBridge` harness), no camera/network. Bound handles, teardown-once, buffer-bounded, invalid-arg rejection, monotonic handles, 8-bridge cap. |
| WVC-06 Windows native audio | Partial | 05 | Untouched, as intended: voice remains the proven SIPSorcery 10.0.17 + G722 + winmm adapter. | No new voice failures in the full suite. |
| WVC-07 Windows managed adapter | Done | 05,06 | Native P/Invoke bridge (`windows/CallVideoNativeBridge.cs`) loads latest built DLL, validates ABI; `windows/CallVideoNativeMedia.cs` implements `ICallVideoMedia` against native bridge (synthetic-only start, video-only disposal). | Build succeeded; full suite 141 PASS / 0 FAIL; `--call-video-check` 317/0. |
| WVC-T02 Both authenticated voice gate | Partial | 07,01 | Automated fixture/signaling checks pass (voice-only, v1 unchanged); no real authenticated HELLO/READY/CALLCONNECT exercised against a replacement native audio adapter yet. | Automated verification only. Production authenticated voice path with replacement adapter remains pending. |
| WVC-08 Windows offline integration/switch | Pending | T02,03 | Not started. SIPSorcery remains the production voice factory. | Not performed. |
| WVC-T03 Windows voice regression | Partial | 08 | Not applicable to the replacement (there is none), but the equivalent regression question was answered: the full suite passes with exit 0 and no new voice failures. | **Unresolved intermittent failure, carried forward.** The historically flaky 16-member `group_membership.py` case **did occur** in a later run of this pass (abort at `run.ps1:85`, exit 1, only PASS lines printed, empty stderr) and the immediate re-run exited 0. That is **not** a fix: a passing re-run does not prove a non-reproducing failure is resolved, and no diagnosis was obtained. Not attributed to the known zombie-`dotnet` contention either, because no evidence for that was gathered here. No application code changed between the two runs. |

### C. Add authenticated v2 and isolated video coordination

| ID / platform | Status | Depends on | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| WVC-09 Windows capabilities/parser | Partial | 04,T01,08 | `CallCapabilities.cs` (strict `LM4` CALLCAPS parser), `CallVideoProtocol.cs` (closed v2 validator: `MaxDepth=16`, no duplicate keys, exact key sets per type, integral signed-64 counters, 64 KiB frame / 48 KiB SDP / 4 KiB ICE bounds, 128 ICE per generation), rewritten `CallSignaling.cs` (strict UTF-8, all v2 builders), and `PeerEngine.cs` responders for `LM4\tCALLCAPS` and `LM4\tCALLGRANTS`. One switch, read by both the responder and the negotiator, so the engine cannot claim v2 the controller would refuse. The switch **defaults to false** and is gated on an installed media backend, so this build advertises voice only — see deviation 4. | Automated only: 107 frame + 38 capability fixtures agree with Android and with hand-authored expectations; `--call-video-check` covers probe timeout, malformed input, fragmented frames and wrong-peer input, and asserts invalid input cannot prolong a call. Ten `capability-honesty` checks pin the no-false-advertisement rule and were verified to fail against the old default. **Not** done: legacy fallback against a real archived 2.2.42 peer, and no physical-connection run. |
| WVC-10 Windows consent/controller | Partial | 09 | `CallVideoConsent.cs` (consent state machine: original-caller-alone offers, one outstanding request, lower-UUID collision, no consent transfer, bounded retired-request tracking), `CallVideoActions.cs` (user commands), `CallVideoCoordinator.cs` (~19 KB, state machine bound to a single worker), `CallFrameAdmission.cs`, `CallVideoDiagnostics.cs`, `CallVideoPlacement.cs`, `CallVideoResources.cs`, `CallCameraPermission.cs`. `CallController` gained v2 INVITE `media`, audio-only answer, mid-call bilateral upgrade, decline, per-role trusted auto-answer, and Android's **two-stage admission** (video frames intercepted before the state/role table; heartbeat refresh only after admission succeeds). `StartMediaLocked()` is the single place any media adapter is created — which is what makes "no capture during probe or ringing" checkable rather than aspirational. | Automated only, and it is substantial: `--call-video-check` **307 passed / 0 failed**, including both upgrade initiators, simultaneous requests, late acceptance, stale-generation callbacks, permission denial, collision policy, and the requirement that no camera is acquired before consent. **Not** done: no two-device call, so consent behaviour against a real peer's real timing is unverified. |
| WVC-11 Windows video transport | Partial | 05,10 | `ICallVideoMedia.cs` seam + `FakeCallVideoMedia.cs`. **No production PeerConnection exists.** Video failure disposal and audio-survival semantics are implemented against the fake, so the isolation policy is in place for when a real adapter lands. | Fake-media tests only. Production adapter loopback and stale-generation revival tests remain blocked on WVC-03/05/06/07. |
| WVC-T04 Both generated production interop | Pending | 11,T01 | Not run. | Blocked: needs WVC-11. |

### D. Capture, rendering and user-facing call controls

| ID / platform | Status | Depends on | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| WVC-12 Windows webcam capture | Pending | 02,11 | Not started — blocked on WVC-11. No camera was touched by this pass. | Physical Lenovo RGB capture, format/rotation validation, unplug/busy/switch: all **not performed**. |
| WVC-13 Windows frame ownership | Pending | 11 | Not started — blocked on WVC-11. The seam defines the bounded-copy contract, but no producer exists. | Not performed. |
| WVC-14 Windows renderer decision | Pending (was Decision) | 13 | Not started. The `CallView` stage deliberately uses plain `Panel`s and states "No video" rather than rendering, so nothing here can be mistaken for a chosen renderer. | No measurement, no threshold. This row stays open and unmeasured. |
| WVC-15 Windows video UI | Partial | 10,12,14 | `CallView.cs` gained a hidden video stage, a remote view with an honest placeholder, a draggable local preview clamped into the stage on every layout via `CallVideoPlacement`, accept-with-video / decline-video / camera / add-video buttons, and voice-only callers keep the exact pre-existing fixed window. `ChatWindowCalls.AttachCallViewHandlers` routes every video button through the controller's command boundary, with refusal results surfaced as plain language ("The call continues with audio only") rather than raw enum text. `ShowTrustedCallAccess` now offers all four grant bits with per-grant consent prompts. | Automated: none of this is covered — there is no WinForms UI test for calls, which is a pre-existing gap this pass did not close. Untested on screen: keyboard/focus/accessibility, 100/125/150/200% DPI, and that the preview actually stays inside the window after a resize. `CallVideoPlacement`'s clamping *is* unit-tested (9 checks); the WinForms wiring around it is not. |
| WVC-16 Windows lifecycle | Pending | 12,15 | Not started. | Not performed. |
| WVC-17 Windows quality/diagnostics | Partial | 11,14,16 | `CallVideoDiagnostics.cs` — allowlisted copied stats, no SDP, ICE/IP details, certificates, keys or private content. Not yet fed by a real media adapter. | Unit-tested (13 checks) at the type level. No real CPU/memory/frame-rate measurement, no degraded-LAN audio-continuity run. |
| WVC-T05 Windows capture/UI stress | Pending | 15,16,17 | Not run. | Not performed. |

### E. Previously requested trusted recipient controls

| ID / platform | Status | Depends on | Work and notes | Acceptance / testing |
|---|---|---|---|---|
| WVC-18 Both control contract | Done | 04,10 | Matches Android exactly: `CALLGRANTS` optional v1 query returning mask `0..15`; `REMOTE_SPEAKER` at generation 0 with a boolean; `REMOTE_CAMERA` at the active video generation with request/camera/facing. Windows desktop honesty is preserved explicitly — there is no arbitrary webcam advertised as "front"/"rear", and no earpiece/speaker route is claimed; `CallController.ApplyRemoteSpeakerRoute` returns `false` in this build rather than pretending. Capability alone never grants control: grants are a separate mask with a 10 s freshness window. | Fixtures cover all four bits and combinations on both platforms and agree. Unsupported behaviour is declared rather than silently ignored. |
| WVC-19 Windows grants/enforcement | Partial | 18,16 | All four bits are stored, parsed, persisted and editable in `ShowTrustedCallAccess`, each with its own consent prompt on first grant (remote camera gets a separate warning, because it is the one that moves a physical device and exposes a picture). `PeerEngine` answers `CALLGRANTS`, and `CallController` enforces sender-role and connected-state on inbound `REMOTE_*` frames. | Automated: fixtures + 307 checks cover bit independence and wrong-sender rejection. **Not** done: revocation/revoke-delete/global-Offline/busy scenarios as live tests, and the recipient-visibility freshness policy (WVC-20). |
| WVC-20 Windows recipient visibility | Pending | 19 | Not started. | Not performed. |
| WVC-21 Windows control UI/override | Pending | 20,15 | Not started. | Not performed. |
| WVC-T06 Both trusted controls | Pending | 21 | Not run. | Blocked on 21. |

### F. Candidate, physical interoperability and release gates

| ID / platform | Status | Depends on | Work and notes | Acceptance / testing |
|---|---|---|---|---|
Rows 22–24 and gates T07–T09 are **not** started and must not be read as imminent.
WVC-22 is explicitly gated on T03/T04/T05/T06, of which only T03 is even partially
answered, so no candidate packaging is authorized yet.

| WVC-22 Windows candidate packaging | Pending | T03,T04,T05,T06 | Not started, and correctly blocked. | Nothing produced. |
| WVC-T07 Both physical calls | Pending | 22 | Not run. No physical call was made. | **Not performed** — no webcam, no Android handset, no package. |
| WVC-T08 Both compatibility/degraded LAN | Pending | 22,T07 | Not run. | **Not performed.** The archived-2.2.42 v1 compatibility claim rests on fixture parity and on a plain v1 INVITE still being exactly `caller`/`callee` at generation 0 (asserted in `--call-video-check`), *not* on a two-device call. |
| WVC-23 Windows final acceptance | Pending | T07,T08 | Not started. | Not performed. |
| WVC-24 Windows final package | Pending | 23 | Not started. No version bump, no publish, no zip, no manifest. | Nothing produced. `outputs/` untouched by this pass. |
| WVC-T09 Both exact-final smoke | Pending | 24 | Not run. | Not performed. |

## Critical path and practical checkpoints

`01–04 → T01 → 05–07 → T02 → 08/T03 → 09–11 → T04 → 12–17/T05`

Trusted controls branch: `04/10 → 18–21 → T06`.
Both branches join at `22 → T07/T08 → 23 → 24 → T09`.
Independent input/fixture/UI-design preparation can overlap, but no gate is skipped.
Cross-language fixtures occur early; candidate packaging precedes physical tests;
final packaging follows acceptance. This removes the older package/test dependency cycle.

Checkpoint 1: replacement voice works through authenticated production calls.
Checkpoint 2: production video signaling works with generated test input.
Checkpoint 3: real webcam, rendering/lifecycle and fresh-grant controls pass.
Checkpoint 4: packaged physical/legacy interoperability and exact-final smoke pass.
Do not estimate a reliable completion date before checkpoint 1 and webcam measurements.

## Acceptance evidence discipline

For every executed row record status, source revision, test command/result, exact
binary hashes, backend/version, architecture, device/OS, duration and limitations.
Separate simulated/fake, generated-media, physical-camera and human UI evidence.
Keep failed runs and sanitized summaries; do not copy raw SDP/candidates into public docs.
No new listening approval is required, but packet continuity alone is not webcam/UI acceptance.

Minimum physical matrix: both original caller directions; initial video and voice
then upgrade from either participant; simultaneous upgrade; audio-only answer;
camera denial/busy/unplug/switch; hangup/Offline; video timeout/malformed video SDP;
minimize/tray/lock/sleep; no grant/individual scopes/revoke; legacy voice fallback.
Video failures must leave audio connected where audio transport itself remains healthy.

Decisions still needed during execution: supported architectures, measured video
profile/render thresholds, Windows route/front-rear semantics and availability of
an archived Android test endpoint. These are not excuses to redo settled media research.

## Mapping from older plans

| Older item | Current disposition |
|---|---|
| W00 / W01 / WT01 | Hardware inventory and native media feasibility Partial/Done; production webcam and authenticated voice still pending. New 01–03, T02,12. |
| W02 / R01 / R02 | Prototype audit/package done; production provenance/security/offline integration still 03,05,08. |
| R05.1–R05.3 | Prototype audio evidence reusable; production bridge/adapter and actual call-path gate 05–07/T02. |
| R05.4 / B01 | A02b core confirmed; additive controls/fixtures 04/T01/18. No final-package dependency. |
| W03/W04/WT02 | Production capability/strict parser/consent coordinator 09–10. |
| W05/W06/WT03 | Native separate-PC path 11 and physical capture12; obsolete SIPSorcery video implementation wording removed. |
| W07/W08/W08m/WT04 | Frame ownership, measured renderer and UI 13–15. |
| W09/W10/W10d/WT05 | Lifecycle, performance, safe diagnostics16–17/T05. |
| Trusted T08–T10 | Dependent controls18–21/T06; do not assume every historical Android trust/history proposal is implemented. |
| W11/W12/WT06, B02–B06/BT01–BT03 | Regression/candidate/physical/legacy/final sequence T03,22–24/T07–T09. |

After execution update this checklist, `windows/STATUS.md` and `PROJECT_STATUS.md`;
update Android status only for actual Android changes or new Android acceptance evidence.
