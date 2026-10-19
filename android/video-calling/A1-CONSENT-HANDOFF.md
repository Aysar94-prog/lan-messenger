# A04 / AT02 source handoff — 2026-10-03

Platform Android. User authorized execution and accepts audio tests complete;
no further listening test. A02b confirmed VP8 on separate video-only secured PC.
Unchanged frozen plan hash 4ea7877d6643b5c89246b075d7a301d73b38bea28f644303383fa42f7ca0efbf.

| Small task | Status | Dependencies / acceptance |
|---|---|---|
| A02b shared selection/spec | Complete | Both paired generated video directions, G722/failure continuity, user audio disposition; A02b-CONTRACT.md. Windows production migration remains separate. |
| AT01 confirmed syntax fixtures | PASS: 100 | Actual production CallVideoProtocol validator, v1 exact bytes, v2 bodies, UTF-8/SDP/ICE/counter limits, selected VP8, no mixed audio/video PC. Controller enforcement awaits A07. |
| A04 consent policy | Implemented | CallVideoConsent handles initial video/voice answer, upgrade accept/decline, collision, generation, camera intent and recovery without modifying voice. |
| A04 service/UI commands | Implemented boundary; integration Pending | CallVideoActions with fresh eligibility and injected effects; CallUi explicit identity-checked actions. A07 must bind these to actual authenticated signaling/media. No visible enabled video control yet. |
| AT02 fake permission/media effects | PASS: 64 policy + 27 effects | Denial, revocation, missing hardware, foreground/offline, stale call/request/permission callback, repeated/concurrent taps, failed sends, rollback, decline and audio retention. Actual OS/UI acceptance remains Pending. |

CallVideoConsent owns no Activity/media/socket; controller must still authenticate
peer/version/cid/sequence and call native operations off locks. A peer request or
camera-state frame never grants local consent. Both parties authorize a request;
the losing collision request does not transfer permission to the winner. Every
camera start needs a fresh permission/hardware/foreground check. Camera-off keeps
video receive authorization and voice. Revocation requires a new local camera-on
action after returning; late generation callbacks cannot resurrect failed video.
Remember at most 128 request UUIDs/call without eviction/reuse; refuse further
video proposals without ending voice. A failed initial answer allows explicit
retry or voice fallback; stale UI providers cannot act on a replacement call.

Protocol parser hardening within video signaling scope: strict UTF-8, bounded
16-level JSON nesting, duplicate/unknown v2 envelope fields, invalid escapes/control
characters, trailing data and integral v2 counters rejected. Separate Long/Double
returns fix Java conditional numeric promotion that lost >2^53 counters.
Frame.copy now preserves protocolVersion. Valid v1 invitation bytes unchanged.
These are source changes, not a signed APK or production controller v2 support.

## Next exact work

1. A05 interface/fake video operations and service-owned EGL/renderer leases.
2. A06 separate secured video PC, VP8, capturer/sources/tracks/sinks; acquire camera
   only after the A04 eligibility/consent and authorized setup checks.
3. A07 CALLCAPS + v2 session/signaling and effects binding; AT03 failure fixtures.
4. A07d diagnostics, A08–A09d rendering/self-view/controls/clipboard, A10–A11
   foreground camera eligibility/lifecycle, then release gates A12/AT06/A13.

scrcpy UI checks authorized. The computer-use skill was read for that later
Windows viewer task; no UI action yet. Preserve no-audio/actual-audio distinction.
Never promote the AP01 test APK or include it in production compilation/signing.

Usage checkpoint: 81% five-hour / 20% weekly, near-limit handoff saved before
large media changes. Continue from these source files, not old listening blockers.
No source release/version/signing change; original production remains 2.2.42/code69.
