# Android video candidate preparation — 2026-10-03

Final delivery-file source includes preview-job coalescing and camera-off
placeholder, preventing rapid hide/show queue growth and stale local images.
4018ac… is the final hash. e01cc… belongs to the superseded
LanMessenger-2.2.43-before-preview-fix.apk. Final signed file was USB-installed
again successfully with the same versionCode70/signer. Windows Release project
build succeeded (existing CS1998 warning only); no Windows publish/migration.
Final controller call regression417/0 passed against b8878c… production classes.
No full/background runner remains active. Detailed UI/FGS/privacy/thermal and
two-device video acceptance are still OPEN: deliver as a TEST CANDIDATE only.

## Exact APK built and USB-upgraded

`D:\LAN-Messenger\outputs\LanMessenger-2.2.43.apk`, version2.2.43/code70,
arm64-v8a, SHA256
`4018ac369352602ba87005945b3166a59de971d0edbc858218b02d475f1f5855`.
Signed v2/v3 with original certificate SHA256
`7f4a07943d01da1266e4f2d3ce757165c9619c9a4ef74e741c58f8eee08d4161`.
USB install -r succeeded on R8YY80A8VLB; firstInstallTime stayed
2026-09-22 01:45:25. Before/after screenshots show the same saved contacts,
verification states, group and local device name. This is not a byte-by-byte
history/draft/settings audit. Going Online succeeds with CAMERA still denied.
APK has production DEX/native library only; no AP01/Harness/ProductionVideoAdapterCheck
strings, no test activity, no debuggable flag or private key. Intermediate
`-video-candidate.apk` is superseded and must not be delivered.

AT03 production adapter on the physical phone passed in the separate, explicitly
invoked AP01 APK: physical camera151 decoded frames, reverse generated88 decoded
frames, local155 frames, real front/back switch and stop passed. Measured VP8,
send30.1fps/309.8kbps, receive19.1fps/120.2kbps, receive loss0.0%. Actual production
WebRtcCallVideo/WebRtcCallMedia sources executed, shared factory/EGL lifecycle;
this is Android-local adapter evidence, NOT Windows pairing or exact-candidate
controller/UI video acceptance. No listening test repeated. Test APK
`android-7ca41a89412a4042ade04eb57146af0a/VideoFeasibility.apk`, SHA256
`ab7e79732a35210a060824ed534680ee840e123f803d52aaabd781a435bb8b64`.

Pure suites now385/0 (39 coordinator incl request/negotiation/initial-offer
timeouts, late ready, admission sequence and ICE cap;13 stats;9 placement).
Final source authenticated TLS capabilities8/0 and channels19/0 pass; root
`a07-network-93c61442ab02451a9f0badef08c97aed`.
Full normal regression ended exit1 (session22510), with log
`D:\LAN-Messenger\outputs\.build\android-video70-regression.log`.
Failure: group_membership.py182, full16-member reinvite refused because the
FullMember6 capability probe failed. Earlier messaging/attachment/large-transfer
and group cases passed. Migration/ownership/Windows UI tail did not run in this
invocation. No unrelated group code changed; do not infer the cause or repair it
outside scope. Windows app still holds43872 without close consent, but this was
NOT this run's failure. No full-suite PASS or final release acceptance claimed.
Final stale VIDEO_STATE/duplicate readiness admission guards were rebuilt into
the final APK and reinstalled; prior hash e8ba… belongs to
LanMessenger-2.2.43-before-admission-fix.apk and is superseded. Final APK is
6349233 bytes. Final native JVM boundaries41/0 and TLS8/19 pass; final network
root a07-network-b8878c36362f4893904f509c7a9e6b07. Call regression417/0 passed.
Windows v1 signaling/engine were reviewed: no CALLCAPS responder, so fresh
probing closes/falls back without sending v2 SDP; group CAPS2 unchanged.
Usage47% five-hour/30%weekly; no near-limit pause needed.

Platform: Android. Frozen plan-v007 unchanged. User explicitly requests execution
through delivery of an installable APK for their testing. This is not a claim
that final Both/package acceptance is complete.

| Task | Source status | Acceptance / tests |
|---|---|---|
| A07 | v2 coordinator/controller/session/actions implemented | Pure 29 coordinator cases and actual TLS/fake media 19 channel cases pass; native and timeout stress remain. Audio listening already accepted; never repeat. |
| A07d | Bounded native stats snapshot and strict report allowlist implemented | 13 pure checks pass; actual phone counters remain to check. Missing/stale/reset counters show Unavailable. |
| A08/A08m | Activity-owned shared-EGL surfaces, detach/release, mirrored preview and stage-only placement/hide/reset implemented | 9 pure placement cases pass. Physical small/rotation/reopen testing remains. |
| A09/A09d | Initial/upgrade consent, camera/switch, preview controls and explicit diagnostics copy implemented | UI compile passed. Physical touch/clipboard preview acceptance remains. No application proximity blanking exists in baseline; none is enabled for video. |
| A10/A11 | Camera FGS type promotion/demotion, explicit foreground eligibility and background/lock/thermal capture stop implemented | Native boundaries 41/0 and Offline structural check pass; clean-permission/privacy/thermal/recreation phone acceptance remains. |
| A12/AT06/A13 | Candidate build begun, NOT accepted/released | Version 2.2.43 code70, arm64 test candidate, original signing pipeline. Full regression and USB install verification remain. |

Service now binds v2 capability to usable native media/controller. Fresh outgoing
CALLCAPS falls back to unchanged v1 on old Windows; Windows 2.2.42 has NO video.
R05/Windows production video and final Both interoperability still remain.
Initial outgoing video checks capability BEFORE camera permission and uses one
reserved UUID for permission and the eventual invitation. Incoming notification
acceptance remains voice; video consent requires the foreground UI. Microphone
and camera requests remain separate.

CAMERA declaration requires runtime permission even when opening the system
attachment camera. A separate attachment-only permission request now preserves
that existing action; no attachment codec/storage/send behavior changed.

Renderer sinks borrow frames; no UI/controller/socket work on frame callbacks.
View replacement detaches through a bounded serialized binding worker, releases
surfaces on the main looper, then closes EGL renderer leases. Activity Handler
teardown cannot cancel this cleanup. Preview placement and diagnostics remain
local; report formatting accepts only measurements and a constrained version.
Copy uses sensitive clipboard metadata to suppress text preview and explicitly
states OS retention; it never auto-copies or promises to clear the clipboard.

Latest prior compile/network root:
`a07-network-ee2063e2e5124ea3aa8f13caf9b8e867`.
Pure suites: 375/0 (324 foundations +29 coordinator +13 stats +9 placement).
Call regression 417/0 was run before the latest UI/FGS additions; rerun pending.
USB phone remains 2.2.42/code69 until candidate verification/install.
Usage 38% five-hour /29% weekly; no near-limit pause. User's Windows app still
holds port43872; asked permission to close it for the full UI regression. Do not
terminate that process without permission. No push or unrelated cleanup.
