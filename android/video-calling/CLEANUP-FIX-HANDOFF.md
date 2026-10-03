# Android 2.2.43 cleanup regression — approved repair

User authorized repair on 2026-10-03 after reporting `Native media cleanup
failed; restart the app`. Scope: Android media ownership/teardown and regression
tests; no wire change, Windows migration, unrelated group fix or manual listening.
Frozen plan-v007 hash remains unchanged.

| Task | Platform / status | Dependency | Work / acceptance |
|---|---|---|---|
| CF01 | Android / Complete | User approval | Pinned SDK receiver disposal and actual production-adapter failure reproduced on USB phone. |
| CF02 | Android / Complete | CF01 | Skip double disposal of an already-disposed receiver video track, including sink detachment on cleanup retry. Preserve conservative factory-blocking behavior for actual unfinished cleanup. |
| CF03 | Android testing / Targeted PASS; full runner FAILED | CF02 | Native repeated teardown, pure385/JVM50/TLS8+19/call417 pass. Full runner ended at16-member reinvite capability refusal; later tail not run. No manual listening. |
| CF04 | Android / Complete (test-candidate delivery) | CF03 | Built, audited, USB-installed and delivered 2.2.44/code71 original-signer ARM64 APK; also copied to phone Downloads. Packaged two-device acceptance remains pending. |

Initial evidence: the USB phone's call log shows peer ERROR immediately after
ACCEPT on three calls at 15:16. The screenshot's cleanup error can therefore be
reported from the other endpoint; its first failing cleanup stack is not yet
available. Candidate 2.2.43 must not be considered accepted. SDK receiver-owned
remote video track is explicitly disposed by our adapter after PC disposal;
ownership and actual failure are being investigated.

CF01 completed: baseline test APK (SHA256
df7bdb3b96137ca60b3d029e93e22b66eca91d4c1c1e9f464fd3a51629c71ec4)
on USB phone returned `videoA=false, videoB=false, factoryBlocked=true`.
Test-only diagnostic confirmed both retained remote wrappers were already
disposed. Pinned SDK bytecode confirms RtpReceiver.dispose disposes cachedTrack;
MediaStreamTrack.dispose throws on a second disposal. Newly added JVM regression
also fails against unchanged production code at terminal cleanup. This is the
demonstrated defect, not an inferred microphone/network problem.

CF02/targeted CF03 evidence: corrected native harness SHA256
08a096ed8c79ca51efb87c4a981baa731fc3e78d96d41ae8213c5b0bd2d8a3d8
completed three video lifetimes and voice-only reinitialization/offer/cleanup
after each. Final round decoded 168 physical-camera frames and 145 generated
reverse frames; switch/stop passed. This is a single-phone adapter loopback,
not packaged two-phone or Windows acceptance. No repeated manual listening.
JVM ownership/terminal regression now passes 50/0 (was 41/0 without ownership
checks); pure video suites 385/0. Normal full runner session 3981 is in progress,
log outputs/.build/cleanup71-full-tests.log; network runner session 36370.

Network runner completed successfully: actual TLS capability8/0, call channel19/0
(fake media). Voice CallCheck compiled against exact production classes.jar and
passed417/0. Initial invocation used a nonexistent non-versioned classes folder
and failed compilation; corrected invocation used the exact APK classes.jar.
No code workaround or cached test result was used.

CF04 artifact: outputs/LanMessenger-2.2.44.apk, 6349233 bytes, SHA256
e951d6aec05b5a65766fe84526288248e596b9293f7a11e93adf20baf50cc8fa.
Certificate SHA256 remains
7f4a07943d01da1266e4f2d3ce757165c9619c9a4ef74e741c58f8eee08d4161.
APK audit: production ID, code71/name2.2.44, min26/target34, ARM64 only,
one native WebRTC library; no feasibility APK/code/private key packaged.
Usage checkpoint: 63% of five-hour allowance, 32% weekly; not near limit.

USB upgrade succeeded: versionCode71/versionName2.2.44, lastUpdateTime
2026-10-03 16:07:38; firstInstallTime remains 2026-09-22 01:45:25.
Startup native probe OK (94ms); foreground service types0x90 (no camera).
Screenshot confirms Online and saved contacts/group/verification visible.
APK DEX audit confirms production adapter and isDisposed check are present,
while feasibility/test harness classes are absent. The test-only APK was stopped
after its completed test. No uninstall, data clear, or user app termination on
Windows.

FINAL verification: full normal runner3981 ENDED exit1 at
tests/group_membership.py:182, FullMember10 capability refusal while adding a
replacement to the live16-member group. Initial15-member delivery, full-size
cap and departure passed. Earlier voice clips/hardware capture, messaging,
features, transfer/resume and ordinary membership tests passed. Migration,
ownership, Offline and Windows UI tail were not reached in this invocation.
This is not a full-suite PASS. No group implementation or tests were modified.
Earlier code70 full run failed at the same reinvite gate (FullMember6).

All repair build/test sessions have ended; do not poll3981. No Windows app was
closed, no remote/branch configuration changed, and no push performed. Latest
quota checkpoint70% five-hour/33% weekly; repair notes are saved here. Next user
acceptance: install2.2.44 on both Android endpoints without uninstalling,
place/end/repeat a video call, and verify the packaged controller/UI no longer
gets stuck after the first call. Do not repeat previously accepted manual
listening. Windows/R05 and broader frozen-plan acceptance remain unfinished
separate work, not closed by this repair.
