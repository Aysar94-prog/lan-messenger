# Android Wi-Fi stability repair

Final user decision: stop investigation and package2.2.65/code92, commit and push.
W01/W02 incomplete causal isolation; W03 not demonstrated as a repair; W04
partial automated verification passed; W05 physical acceptance FAILED. W06
release authorized by user despite the known fault, not by stability acceptance.
No further physical tests, reboot or Wi-Fi setting changes performed.

2026-10-03. New implementation task: planning only until explicit start of execution.
Android scope; Windows unchanged. Release remains on hold.

Execution update: explicit user start received. W01 partially complete (Offline
185s stable; stopped baseline105s, not full planned180s). W02 exercised multiple
signed isolation builds, but root cause not established. W03 candidate implemented
but failed physical stability; W04 pacing28/0 and CallCheck422/0 pass. W05 failed:
full-function2.2.64/code91 dropped23:29:16,23:30:16,23:31:22 on SM-A075F.
W06 evidence recorded, no repair commit or release acceptance. User confirms only
this device is affected; next external isolation needs approval for phone reboot
or another network. Temporary traffic suppression removed from source/installed APK.

## Evidence and limits

SM-A075F on coffe-5G, installed Android 2.2.57/code84. Force-stopped app:
22:55:13-22:56:58 (~105 seconds), no new Wi-Fi disconnect. Reopened app at
22:57:02, no call: Wi-Fi disconnects at 22:57:35.264 and 22:58:32.297.
App reopened afterward, original permissions/data preserved. This supports an
app-related trigger, not a proven specific root cause. Repeated randomized/vendor
reason values must not be interpreted as a standard router diagnosis.

Inspected source: no Wi-Fi disable/disconnect API or CHANGE_WIFI_STATE permission.
Service holds a multicast lock while online. Discovery uses UDP broadcast/unicast
announcements every three seconds. Delivery runs every two seconds and opens a
TLS connection per peer even before checking for queued work. These are isolation
candidates, not established causes. Permission queries run only while list is open.

## Small-task execution plan

| ID | Platform | Status | Depends on | Notes / acceptance criteria |
|---|---|---|---|---|
| W01 | Android | Planned | None | Repeat stopped/running and app-open Offline comparisons for at least three minutes each. Capture Wi-Fi link events, app state, traffic counters and exact timestamps without recording messages, keys or media. Restore original Online setting after tests. |
| W02 | Android | Planned | W01 | Isolate multicast-lock acquisition, discovery and idle delivery connection churn separately using temporary diagnostic switches. Keep one source tree and original signer; never ship switches that weaken TLS, grants or capture consent. Obtain reproducible causal evidence before selecting fix. |
| W03 | Android | Planned | W02 | Implement smallest demonstrated fix. Candidate changes: correct unnecessary multicast-lock use/lifetime, bounded discovery, or skip/back off idle/offline-peer connection work. No router/security changes, identity reset or automatic call reacceptance. Remove temporary experiments from final build. |
| W04 | Android | Planned | W03 | Automated tests for changed lifecycle/scheduler path; call422, permission36 and video-contract checks; retain discovery, delivery retry and explicit Offline behavior. No shared wire change intended; review both implementations if one becomes necessary. |
| W05 | Android | Planned | W04 | Original-key signed candidate upgrade to both phones, data retained. Ten-minute idle Online observation, then repeated video calls with recipient rear/front/off/on, speaker on/off and local mute, plus hangup/background cleanup. No app-triggered Wi-Fi drops, crashes or stuck capture. Do not imply remote microphone control exists. |
| W06 | Android / comparison | Planned | W05 | Record actual evidence, remaining limits and APK hash in Android/project status; local commit only. Resume Android release preparation only after stability acceptance. Windows unchanged; no feature-parity claim. |

If isolation does not identify an app fix, report the evidence and the next
required external check rather than claiming repair or silently changing Wi-Fi
settings. A short successful test alone is not sufficient for final release.
