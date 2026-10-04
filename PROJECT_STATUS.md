# LAN Messenger project status and platform comparison

2026-10-04 Windows video-call implementation pass (source only, **not a release**): the
managed v2 coordination layer is now implemented in Windows source and is
wire-compatible with Android's existing production video implementation — strict v2
parser and builders, `CALLCAPS`/`CALLGRANTS` engine responders, capability probe,
consent/actions/coordinator state machine, two-stage frame admission, v1-preserving
`CallController` integration, a `CallView` video stage with a clamped draggable preview,
all four trusted-call grant bits, and `PlatformTarget=x64`. Automated: Release build
0 errors, full `tests/run.ps1` exit 0, `--call-video-check` 307/0, and the shared corpora
agree across both platforms (107 frame records, 38 capability records, each also
matching a hand-authored expectation). **Parity is still not claimed.** There is no
production native video adapter, so a v2 call negotiates as v2 *audio-only*; Section B
(the native voice replacement) was deliberately not started and voice stays on the
proven SIPSorcery + G722 path; and there was no packaging, no webcam, no DPI or renderer
measurement and no two-device call. Windows `<Version>` is still 2.2.42 and the shipped
Windows release still contains none of this. Android code and wire are unchanged by this
pass. See [Windows video calling plan](windows/video-calling/PLAN-WINDOWS-VIDEO-CALLING.md).

2026-10-04 Android locked/background calls: 2.2.69/code96 original-key ARM64 test
candidate installed on both phones. Trusted video acceptance no longer depends on
camera startup; notification action identity/threading/FGS presentation and stale
ring cleanup corrected; already-prepared camera continues across Activity pause.
Call440/0, permission40/0, recipient28/0, direct34/0, video-contract and lifecycle
guards pass. Actual USB automatic acceptance/background call observed. Full locked
notification acceptance and NEW locked-camera startup remain unverified/pending;
2.2.68 failed candidate superseded. Windows and wire unchanged; Wi-Fi fault unresolved.
See [Android locked calls](PLAN-ANDROID-LOCKED-CALLS.md).

2026-10-04 Windows planning review: rebuilt the
[Windows video-call continuation plan](windows/video-calling/PLAN-WINDOWS-VIDEO-CALLING.md)
against current source and retained evidence. Native separate-PC VP8/G722
feasibility passed; Windows production is still voice-only 2.2.42. Production
integration, real webcam/UI/lifecycle, grant-gated recipient controls and packaged
interoperability are pending. Android production video exists in current candidates;
no parity, Wi-Fi repair or new physical acceptance is claimed. Documentation only.

2026-10-04 Android recipient-control visibility checkpoint: recipient camera and
speaker controls appear only with the respective fresh grant from that device
to the caller (Slave direction). Unknown, failed, revoked and expired confirmation
hides controls; recipient-side enforcement unchanged. Signed ARM64 Android
2.2.67/code94 test candidate upgraded on both phones, including USB SM-A075F.
Permission40/0, visibility28/0, call422/0 and direct34/0 checks pass. Physical
call-screen acceptance pending; Wi-Fi fault unresolved. Windows/wire unchanged.
See [recipient-control plan](PLAN-ANDROID-RECIPIENT-CONTROL-VISIBILITY.md).

2026-10-04 Android feature checkpoint: optional Direct connections now restricts
application networking to selected verified identities and explicit IPv4 addresses,
with discovery disabled, encrypted saved selection, socket/call-candidate guards
and other contacts offline/queued. Android2.2.66/code93 ARM64 original-key test
candidate installed over existing data on both phones (SM-A075F over USB).
Direct34/0, call422/0, pacing28/0, permission36/0 and video-contract/lifecycle
checks pass. Device UI/native media and Wi-Fi stability acceptance remain pending;
the known SM-A075F disconnect fault is not claimed repaired. Windows has no new
direct-mode UI; LM4 unchanged and physical Windows pairing in this mode pending.
See [Android direct-connections plan](PLAN-ANDROID-DIRECT-CONNECTIONS.md).

Release decision 2026-10-03: user stopped investigation and requested Android
2.2.65/code92 final packaging, commit and push. Existing signer; all supported
ABIs. Known issue: SM-A075F Wi-Fi disconnections remain unresolved and physical
stability acceptance failed. This is a user-authorized release, not a verified
Wi-Fi repair. Windows remains unchanged; no new wire or parity change.


Wi-Fi investigation checkpoint 2026-10-03: Android2.2.64/code91 full-function
diagnostic candidate installed on both phones with original signer/data retained.
SM-A075F still loses Wi-Fi association; SM-S908E does not show this issue.
Multicast/delivery isolation and pacing have NOT confirmed a repair. CallCheck
422/0 and pacing28/0 pass, but physical stability acceptance failed. Release held;
Windows unchanged and no wire/parity change. See Android status and Wi-Fi plan.

Current checkpoint 2026-10-03: Android 2.2.57/code84 ARM64 signed test candidate
is installed on SM-A075F and SM-S908E with original signer and unchanged first-install
times. Android has Masters (devices I granted access to) and Slave (devices granting
me access) menu lists, backed by optional verified TLS CALLGRANTS/1 queries.
Two-phone list direction, partial reverse grant and automatic revoke removal passed;
test grant was removed afterward. Permission TLS checks 36/0, CallCheck 422/0 and
all video-contract suites pass. Remote status is informational, session-only and
timestamped; offline/legacy status is last-known/unknown, never authorization.
Android source also includes caller-only recipient camera on/off/front/rear and
speaker controls, trusted initial video acceptance and video detach cleanup fixes.
Earlier physical video streaming succeeded, but latest complete call-control
acceptance remains pending following recipient Wi-Fi/signaling loss. Windows
unchanged: voice-only, no menu/query implementation or production video parity.
Actual new-query Windows interoperability not physically repeated; parser review
and simulated-legacy TLS fallback passed. This is not a final release.
Older checkpoints below are historical and superseded where they conflict.

Trusted-call/icon source checkpoint 2026-10-03: the user-supplied artwork is now
the Android adaptive/legacy launcher icon and Windows executable/window/tray icon.
Both platforms persist certificate-bound per-contact trusted-call scopes and expose
an explicit **Trusted call access** control; the implemented scope is voice
auto-answer only. Revocation, remote forget, contact deletion and delete-all clear
the grant. Android 2.2.45/code72 ARM64 signed test candidate built with the original
certificate (`LanMessenger-2.2.45-trusted-dev.apk`, SHA-256
`e801f62792e3916900cf27c61da880b8842596541a0bb82a5595d12cdd8bb07a`).
CallCheck 422/0 and Windows Release build/publish passed. Android 2.2.46/code73
adds certificate-bound trusted video auto-accept and remote speaker routing; its
APK build/signature checks pass and a physical camera source initialized on an
SM-S908E, but the complete two-phone call has not yet been repeated and remote
front/rear camera switching remains pending. This is not a release or
physical-device acceptance. Remote speaker and camera control, video auto-answer,
and Windows production video remain pending; there is no new wire command yet.
See [trusted-call/icon plan](PLAN-TRUSTED-CALL-ACCESS-AND-APP-ICON.md).

Latest Android cleanup-fix test candidate is 2.2.44/code71 (ARM64), installed over
2.2.43 with original signer and unchanged first-install time. Reproduced/fixed
receiver video-track double disposal; three native adapter lifetimes and next
voice-only setup/cleanup pass. Packaged two-phone video remains pending; Windows
2.2.42 remains voice-only. This changes no wire contract or achieved parity.
See android/video-calling/CLEANUP-FIX-HANDOFF.md. Older snapshots below are historical.

Latest installed Android TEST CANDIDATE is2.2.43/code70, arm64, original signer;
the earlier stable pair in the release table below remains2.2.42. Candidate
startup/data-visible retention and separate physical camera adapter checks pass.
Connected packaged UI/video and Windows pairing remain unaccepted; no final
cross-platform video parity claim. See Android A3-A4 candidate handoff.

In-progress Android candidate 2.2.43/code70: video coordination, controls,
renderers and lifecycle are in source, not yet packaged-device accepted.
Windows production video/R05 remain pending; Android-to-old-Windows calls remain
voice-only. No new release/parity claim. See Android A3-A4 candidate handoff.

Android A07 source checkpoint: authenticated capability probe and call/channel/
replay boundaries added; video advertising remains disabled pending full v2
orchestration. Real TLS/fake-media checks passed; no physical production video or
release compatibility/parity change. Latest full regression reached Windows UI
and failed occupied-port reservation; current-source peer/group interoperability
checks passed separately. [A07 handoff](android/video-calling/A07-SIGNALING-HANDOFF.md).

Latest Android A06 source adds the separate secured VP8 adapter and explicitly
gated camera operations. It is not yet wired to production signaling/UI and has
no physical-camera acceptance or signed release. JVM adapter boundaries 41/0;
compatibility/parity claims remain unchanged. See
[A06 media handoff](android/video-calling/A06-MEDIA-HANDOFF.md).

Android video source checkpoint 2026-10-03: consent policy/commands and service
EGL/media interface foundations implemented. Java/DEX build, call checks 408/0
and explicit video foundations 289/0 passed; full regression stopped on disk
exhaustion. Actual production video media/controller/UI and signed candidate are
not complete. No release compatibility/parity change. See
[Android phase handoff](android/video-calling/A2-RESOURCE-HANDOFF.md).

Video feasibility update 2026-10-03: test-only Windows native/physical Android
generated VP8 pairing passed both directions with G722 and isolated video failure
continuity. Neither production release has video; compatibility/parity and
current 2.2.42 release claims are unchanged. Details:
[paired feasibility](windows/video-calling/PAIRED-FEASIBILITY.md).

Reviewed 2026-10-03. Current releases are **Windows 2.2.42** and **Android 2.2.42** (versionCode 69) — numbered to match by explicit user request, not because the platforms share a version scheme; releases remain independent and this is the first time the two numbers have ever lined up. See the feature comparison below for the actual per-feature state.

## Working arrangement

G1–G3 offline product controls are implemented in source and included in the 2.1.1 Android APK, but not in the Windows 2.1.0 or prior Android 2.1.0 release packages. Windows toolbar/tray and Android people-menu/notification controls share their respective engine transitions. A separate persisted default-Online request is distinct from actual state (bind failures remain Offline and can be retried). Go Offline enforces a strict no-LAN boundary while retaining local history, contacts, groups, cached attachments, partial transfers and queued direct/group sends; in-flight transfers interrupt and resume on reconnection. Remote presence ages out after approximately 12 seconds. Android uses one service-owned engine: actual Offline is bound-only/non-foreground while the Activity is bound, and engine-load/network-bind failures clear the started-service lifetime so it may stop after unbind; foreground and multicast lock are held only while networking. Its connection preference does not change people-list filters. LM4 protocol and compatibility are unchanged. Historical source-based Android physical-device/two-device LAN checks passed for both transfer directions and ADB service cleanup; these were not performed on the packaged 2.1.1 APK. Device acceptance of that APK remains pending.

- One repository at `D:/LAN-Messenger/source` and one shared development branch, currently `master`.
- [Windows status](windows/STATUS.md), [Android status](android/STATUS.md), and this comparison are the current platform records. `HANDOFF.md` is historical context.
- Releases and build/test output live in `D:/LAN-Messenger/outputs`. Do not duplicate the source tree.
- Label new work Windows, Android, or Both. Update the affected status and this comparison when behavior or compatibility changes.
- A platform UI change does not force a release on the other platform. A shared protocol change needs review and interoperability tests on both.
- Separate implemented behavior, automated verification, and acceptance on a real device.
- For new requests, plan first. Do not implement or build a release until the user explicitly asks to start.

## Current releases

| Platform | Release | Latest change | Verification |
|---|---|---|---|
| Windows | 2.2.42 (current; numbered to match Android by explicit request) | Real-device bugfix pass, same day, from the user's first actual manual use of the packaged build: (1) clicking "Call" froze the whole app ("Not Responding") because the Call button's click handler ran `CallController.StartCall` directly on the UI thread, which blocks on a synchronous TCP+TLS connect inside its transport factory — fixed by moving the call onto a background thread; (2) no ringtone at all for an incoming or outgoing call — first fix attempt (a repeating `SystemSounds.Exclamation` cue, the same mechanism used for message notifications) **did not work in practice**: the user confirmed it was still silent on real hardware despite a sound file being assigned to that event in the registry. Replaced with a new `CallRingtone.cs` that synthesizes a real two-tone ring and plays it directly through `CallAudioPlayback`, the same winmm output path real call audio already uses, instead of depending on any OS sound-event mechanism. Neither automated tests nor the `windriver` console-driver call test could have caught either original bug (the driver calls `CallController` directly, bypassing the WinForms UI entirely), and the ringtone fix itself has no automated coverage either (no WinForms UI test exists for calls) — a clean build is the only automated signal for it. See `windows/STATUS.md`'s 2026-10-03 bugfix entry for the full account. Plus everything below: first Windows release to package real voice calls (`CallController`/`CallProtocol`/`CallSignaling`/`WebRtcCallMedia`, SIPSorcery+G722), **verified by a real two-device LAN call against a physical Android phone, both directions** (Windows→Android 33.5s, Android→Windows 15.4s) confirming G722/SDP negotiation converges with Android's libwebrtc; one real bug found and fixed by that test (`SecureChannel`'s 6-second handshake timeout bleeding into the long-lived call-signaling stream, fixed with `UseLongLivedTimeouts()`); plus everything from 2.2.0 (Voice Messages Phase 2) | `dotnet build -c Release` (0 errors, one pre-existing benign warning) and `dotnet publish` succeeded; full `tests/run.ps1` suite passed clean (exit 0) against the intermediate (bug-1-fixed) build; the final ringtone-replacement build was only build-verified, not re-run through the full suite (no test covers this code). Republished (same version, no bump — this corrects a just-shipped release) as `outputs/LanMessenger-Windows-2.2.42.zip` (7,210,458 bytes, SHA-256 `a3975ac0acff78a3c59dbd64a6c6985f25a70f2307155e2fd7a1cdffff6f0d87`; manifest: `outputs/SHA256SUMS-Windows-2.2.42.txt`). **All fixes confirmed by the user on real hardware** ("yes both fixed, ringing works now"); `CallView.cs`'s UI still does not visually match Android's reference call-screen design |
| Android | 2.2.42, versionCode 69 (current; see `android/STATUS.md` for the full 2.2.7-2.2.42 history: voice calls, call-screen redesign, media-preview thumbnails, compose-row icons with camera/gallery video support, a 2026-10-02 crash fix for `RECORD_AUDIO` not being granted when the foreground service starts, and Messenger-style call-history entries in chat) | Same-day fifth pass on voice-message replay, after three straight attempts at reusing the same drained `AudioTrack` all failed on the user's device: `VoicePlayer` now exposes `isFinished()`, and `togglePlayback` routes a finished player through the same "build a brand-new player" path used for a different message, instead of trying to resume the old `AudioTrack` in place — this is exactly what leaving and re-entering the conversation already did reliably. Otherwise identical to 2.2.5/2.2.4/2.2.3: WhatsApp-style icon buttons (▶/⏸/⏹/🎤), draggable seek bar, sender's own sent voice message renders as a proper player. Plus everything from 2.2.2/2.2.1/2.2.0/2.1.1: Voice Messages Phase 1 (A01-A11, in source — see the Feature comparison below; platform-local only, interoperability not yet verified), Offline controls G1-G3, 2.1.0 ownership transfer, 2.0.1 carryover | Locally built and signed APK (`outputs/LanMessenger-2.2.6.apk`, 131,415 bytes, SHA-256 `72c29da9…`; manifest: `outputs/SHA256SUMS-Android-2.2.6.txt`). Original-key signer continuity confirmed directly via `keytool -printcert` (byte-identical certificate SHA-256 to 2.2.5/2.2.4/2.2.3/2.2.2/2.2.1/2.2.0/2.1.1) and `apksigner verify` (v2/v3, one signer). Built by running `android/build.ps1`'s exact pipeline manually (its own pre-existing, unrelated stderr-abort issue is noted in `android/STATUS.md`); every tool, path and signing input was still the project's own. Real-toolchain `javac`/`d8` compile clean; still awaiting device re-testing on the same phone that surfaced this. |

Windows 2.2.42 interoperates with Android 2.2.42, and both remain wire-compatible with the 2.0.x pair for any group that has never had its membership changed and never had its ownership transferred — see below. New direct-to-destination manual downloads require at least 0.8.7 on both peers; platform releases are independent and do not imply feature parity. Voice calls now have a packaged release on both platforms, **and interoperability between them is verified** by a real two-device LAN call in both directions (see the release table above) — unlike Voice Messages below, which still has no actual cross-platform send/receive test run. The sender-sees-own-voice-as-player, drag-seek-bar, and icon-button UX changes in Android 2.2.2 through 2.2.6 are Android-only; the Windows 2.2.0+ source still deliberately does the opposite on the first two points (see `android/STATUS.md`'s 2.2.2 entry) and has not been asked for parity yet. Voice Messages (the separate, older text/audio-clip feature, not voice calls) has a packaged release on both platforms (Windows 2.2.0+, Android 2.2.0+), but **interoperability between them is not yet verified** — that's Phase 3 of the shared Voice Messages plan, not yet started; both sides use the same marker filename and WAV format by design, but no actual cross-platform voice-message send/receive test has been run.

2.0.0 shipped group membership no longer fixed after creation: `Members` is now a live roster that shrinks when someone leaves and grows when the owner adds someone, propagated to every active member as a versioned snapshot (new `MEMBERSUPDATE` frame) so a device that's been offline through several changes catches up in one step rather than replaying each one. Any growth (a direct add or Re-invite) requires a **fresh, live capability check** of everyone who'd be in the group afterward — a past success is never trusted as durable, since the same device could have been downgraded or restored from a backup since. A group that's never been mutated stays on the exact old wire shape, so an unrelated, not-yet-updated device is genuinely unaffected; only a group that has actually changed requires every device in it to be current. Already-saved groups migrate once, automatically, from the old shape where a departed member sat in the member list and a separate flag at the same time. See [PLAN-GROUP-MEMBERSHIP.md](PLAN-GROUP-MEMBERSHIP.md) for the full design.

2.0.0 also briefly shipped a join-request feature (any verified contact of a group's owner could ask to join, with an owner accept/ignore queue). **That feature was removed** by explicit request in 2.0.1 — the wire frames, storage rows, engine methods and UI it added are all gone from both platforms; the mutable-membership foundation above is unaffected and remains fully in place. A device still running the exact 2.0.0 build that shipped join-requests will simply never get a response to a `JOINREQUEST` frame it sends (same tolerance as any other frame an older/newer build doesn't understand) — group messaging, add, and re-invite are unaffected. Two further, unrelated UI changes landed alongside the removal: "Delete conversation" is labeled "Leave group" when the target is a group (it was always actually a leave, never a real delete, and the old wording incorrectly implied verification was revoked), now reachable both from the conversation list and from inside an open group chat; and Android gained a "Hide groups" side-menu toggle, mirroring the existing "Show offline users" toggle.

**2.1.0**: group ownership can now be handed off. Previously the owner was fixed forever and the UI didn't actually stop them from leaving their own group — doing so silently orphaned it for everyone else (nobody was ever told, and since only the owner could change membership, the roster was permanently frozen). Now, "Leave group" for the owner of a non-empty group shows a "Choose a new admin" picker instead of the normal confirmation; picking someone calls the new `TransferOwnership`, which requires a fresh, live capability check (`CAPS>=2`) of *every* current active member — refusing outright, nothing changed, if anyone can't support it or hasn't finished onboarding yet. The owner's own departure is deferred (shown as "Leaving — waiting for members to catch up", composer disabled) until every other member has actually caught up to the handoff, not just the incoming owner — the old owner stays the sole delivery/leave-acceptance authority for that one change in the meantime, via a durable `pendingOwnershipHandoff` record that survives an app restart. This mirrors the existing versioned-snapshot convergence guarantee rather than inventing new trust machinery; see [PLAN-GROUP-OWNERSHIP-TRANSFER.md](PLAN-GROUP-OWNERSHIP-TRANSFER.md) for why a simpler "wait for just the new owner" design (an earlier draft) doesn't work, and for the accepted limitation that remains (a member who never comes back online blocks the departure indefinitely — a signed, relayable handoff proof would remove this, but is a separate future enhancement, not built speculatively). A group that's never had an ownership transfer is completely unaffected for old builds; only a group that's actually used this feature requires every device in it to be current, same shape as the existing membership-mutation compatibility split. An owner-only group still leaves with a plain, immediate local delete — nothing to hand off.

## Feature comparison

| Feature | Windows | Android |
|---|---|---|
| Discovery, verification, queued messages, delivery/read state | Implemented | Implemented |
| Groups, avatars, unread count | Implemented | Implemented |
| Clear chat locally while retaining contact/group and user-saved file | Implemented | Implemented |
| Delete conversation: forgets a contact (revokes verification, removes peer record); labeled "Leave group" for a group (always just a leave, never a real delete) | Implemented; right-click a conversation row | Implemented; long-press a conversation row |
| Delete app data: wipes every conversation/contact/group/attachment, keeps identity/name/avatar | Implemented; toolbar button | Implemented; side-menu item, also resets daily upload usage |
| Forgetting a contact also notifies them: their verification of you is auto-revoked, with an on-device notice | Implemented | Implemented |
| Leaving a group lets the owner see who departed and re-invite them back into the same group | Implemented; "Re-invite" in Members dialog | Implemented; "Re-invite" in Members dialog |
| Group membership can change after creation (not just re-invite): live roster, versioned propagation, migration of old-shape saved groups, a live capability check before any growth | Implemented | Implemented |
| Group ownership can be handed off; the owner must do so before leaving a non-empty group (owner-only groups still leave with a plain local delete) | Implemented | Implemented (not yet device-verified) |
| Blue online / gray offline presence | Implemented | Implemented |
| Go Online/Go Offline control (strict no-LAN Offline; local history, queued direct/group sends and resumable transfer partials retained; persisted default-Online request separate from actual state/bind failure) | Implemented in source, not in Windows 2.1.0 package; toolbar and tray share transition and show Retry online after bind failure; Refresh/Add by IP disabled Offline; Close hides to tray, Exit terminates | Included in Android 2.1.1 APK, device acceptance pending; people menu and notification share one service-owned engine; Refresh/Add by IP disabled Offline, verification explains Online requirement; Offline bound-only/non-foreground while Activity bound; foreground/multicast only during networking; `START_NOT_STICKY` avoids OS-driven restart |
| People-screen side menu | Not implemented | Implemented on Android; Profile and About live in it |
| Hide offline direct peers in the people list | Not implemented | Implemented on Android as an Android-local persisted `Show offline users` flag, default off; a people-list display filter for direct-peer rows only; it does not change engine state, routing or the wire, and deep links and open chats bypass the list rather than being filtered |
| Hide all group rows in the people list | Not implemented | Implemented on Android as a separate Android-local persisted `Hide groups` flag, default off, mirroring the offline-peer filter exactly (display-only, engine/wire unaffected, deep links and open chats bypass it) |
| Attachment draft followed by explicit Send | Implemented | Implemented |
| Automatic inline ordinary photos | Implemented within preview/codec limits | Implemented within preview/codec limits |
| Ordinary attachments up to 1 GiB; Fast offers up to 1 TiB | Implemented | Implemented |
| Manual Download asks destination, writes one receiver copy, Open launches it | Implemented | Implemented; destination provider must support seeking |
| Fast payload plaintext with authenticated TLS control | Implemented | Implemented |
| Manual download resume at 256 KiB boundaries | Implemented | Implemented |
| Background operation | Tray process until Exit | Foreground service subject to OS/battery limits |
| Built-in camera capture | Not implemented | Implemented |
| Initial show newest 10 messages, load 20 older on upward scroll | Implemented (paged, per-conversation view state) | Implemented at UI level |
| Attachment-thumbnail cache across chat changes | Implemented; 16 MiB reference-counted LRU, skips non-image files | 16 MiB Activity LRU cache |
| Retain unchanged cards on status updates | Implemented | Visible cards are rebuilt when the view signature changes |
| Keep complete UI trees for multiple active chats | Not implemented | Not implemented |
| Daily aggregate upload tiers: unlimited below 2 GiB, then 30/20/10 MiB/s at 2/5/10 GiB | Not implemented by earlier Android-only scope | Implemented |
| Comprehensive anti-flood policy | Not implemented beyond basic connection/transfer limits | Not implemented beyond basic limits and daily upload policy |
| Voice messages: record, send, receive and play short voice clips (reuses the existing encrypted Normal attachment store; no new wire frame) | Implemented in source (Phase 2, W01-W11, WT01-WT06 all written; WT04's exact admission-ordering edge case untested) | Implemented in source (Phase 1, A01-A11, AT01/AT02/AT06; AT04 partial, same untested edge case as Windows; AT03/AT05 not attempted, no confirmed audio device in this environment) |
| Photo/video attachments: Messenger-style single-thumbnail rendering (no filename/size line or buttons once a preview exists), fullscreen view with a single download control, video gets a first-frame thumbnail + play-disc with single-tap inline playback and double-tap fullscreen | Not implemented (Windows still shows the ordinary generic file card plus thumbnail, no video preview) | Implemented and device-verified two-device, both directions (see `android/STATUS.md`'s 2026-10-02 entry); Android-only, UI-only, no wire/storage change |
| Compose row: Messenger-style icons (+/camera/gallery/mic/send), camera icon offers Photo or Video capture, gallery icon picker accepts photo or video | Not implemented (Windows compose row is unchanged text-labeled controls, photo capture/picker only) | Implemented and device-verified (see `android/STATUS.md`'s 2026-10-02 "Compose row" entry); Android-only, no wire/storage change |
| Voice calls (real-time audio, WebRTC-based) | Implemented in source (`CallController`/`CallProtocol`/`CallSignaling`/`WebRtcCallMedia` etc., SIPSorcery+G722); **verified working cross-platform by a real two-device LAN call, both directions** (see `windows/STATUS.md`'s 2026-10-03 entry); not yet in a packaged release; `CallView.cs` UI does not yet match Android's reference design | Implemented and shipped (release 2.2.42); same cross-platform call test confirms wire/codec interop with Windows |
| Call history entries inline in chat, Messenger-style ("You called X", "X called you · duration", "Missed call") | Implemented (`CallLogMarker.cs`/`PeerEngine.AppendCallLog`), device-verified in the same cross-platform call test | Implemented and device-verified, both sides, real multi-call test (see `android/STATUS.md`'s 2026-10-02 "Call history entries" entry); purely local per-device annotation, no wire/protocol change |

Automatic ordinary images are the exception to destination selection and use an app-private cache. Fast image offers remain manual.

Offline automation (not device acceptance): Android SDK source compilation and full `tests/run.ps1` (including Windows native UI and cross-platform `tests/offline_lifecycle.py`) passed with SDK 9 MSBuild and in-workspace test output. The loopback runner checks accepted idle socket closure, Offline gating, delayed avatar/group work, interrupted Fast/ordinary/cache transfers, partial stability, integrity and duplicate-free retry. One earlier full-suite 16-member capability stress failure passed on isolated rerun and final full rerun. A 2.1.1 APK has since been built, signed and independently verified (see Current releases above). Windows source-only Offline controls have no Windows 2.1.0 package acceptance; Android 2.1.1 APK device/emulator acceptance is **Pending-Unavailable**. Historical Android source-based two-device Offline checks passed but do not constitute 2.1.1 APK acceptance; remote peers age out after approximately 12 seconds, not instantaneously.

Voice messages (in source on both platforms, not released): record/send/receive/play short voice
clips per `plan-v003` — see [windows/STATUS.md](windows/STATUS.md) and
[android/STATUS.md](android/STATUS.md) for the full write-ups. Both platforms' implementation
tasks (W01-W11 / A01-A11) are code-complete and pass their full local regression suites with
zero regressions; both used real compile/build verification throughout, not blind ports. A real
duplicate-Send bug in the draft registry's crash-boundary reconciliation was found during the
Android verification pass and fixed on **both** platforms in the same session (see either
status file's A05/W02 entry). Remaining on both: the scheduler's exact 3-consecutive-voice-then-
1-image admission-ordering (WT04/AT04's untested edge case, deliberately not forced black-box
against real async timing), device-lifecycle interaction tests needing real/simulated audio
hardware (Windows got WT03/WT05 coverage because its sandboxed environment happened to expose a
real `waveIn`/`waveOut` device; no Android equivalent has been confirmed here), and manual
two-device acceptance on physical hardware. **Platform-local only; interoperability between the
Windows and Android implementations has not yet been verified** (Phase 3 of the plan, not yet
started) — this row will be revised once both platform exit gates and the integration phase
complete.

## Performance and compatibility limits

Message records are resident in each engine's memory. Windows 0.8.9 renders only the newest 10 messages and pages backward, but `PeerEngine.Messages` still scans engine-held records; it is not a disk query for only 10 records. Measured before/after (tests/MeasureWindows, loopback): the 112-message chat opened in 8 ms on 0.8.8 and 2 ms on 0.8.9, so scanning was never the dominant cost and no engine recent-message index was added (plan W07). There is no fully implemented "ask only for new records" path across all UI/storage flows.

The automatic-photo legacy paths differ: Android has encrypted FETCHSTREAM with 256 KiB resume, while Windows uses FETCH with 100 MiB cached segments. The new manual FETCHDIRECT path works on both.

## Proposed work, not implemented

1. Confirm the Windows 0.8.10 rendering, paging, and repaint behavior on the affected PC/DPI setup; check large-chat opening and throughput on real LAN hardware.
2. Add a Windows engine recent-message index only if real-device measurements later show message scanning dominating open time (deferred by the finished pagination plan with recorded evidence).
3. Measure and consider retention/update of active chat UI on both platforms (Windows now caches up to 3 recent chats' cards; Android still rebuilds visible cards on signature changes).
4. Add Windows upload tiers only if the user expands the previously Android-only scope.
5. Validate Android destination choice, pause/resume, Open, background operation, and throughput on physical phones.

The repository has a GitHub origin. Recent release commits are local; no push was made for those releases. Remote refs were not refreshed for this status audit.
