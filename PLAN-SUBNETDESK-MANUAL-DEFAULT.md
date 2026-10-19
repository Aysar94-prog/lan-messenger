# SubnetDesk manual approval ON by default

2026-10-09, Asia/Jerusalem. EXECUTION APPROVED by user "start".
User additionally requests final Windows MSI and Android APK with the same release
number. Scope updated to Both1.3.7/build85 (Android private versionCode2085 retains
upgrade compatibility); MA-07 added. English-only new UI/messages; preserve signer.
User answered yes to ON-by-default and remembered ON/OFF choice across restarts.
Current explicit start authorizes implementation, builds and final artifacts. Do not
infer authorization to install the MSI/change OS service/firewall/security settings.

Baseline: helper production9a9337b/docsd29c532, commonb56ada4, private1.3.6/build84.
Windows and Android builds completed; native43/Flutter106/runner CTest3 PASS. Android
Helper2084 installed, original2075/mic grant preserved; actual release Keystore synthetic
PASS. Windows84 staged/exact-native gate PASS; user stopped computer use with Esc before
automated switching. New read-only check now sees exact Windows84 mainPID4420, user/
external launch. No UI, session, code-reuse, pending-prompt or audible acceptance inferred.
Build84 source archives/binary ZIP and final documentation remain separate open follow-up;
do not silently replace frozen artifacts or claim all prior requests accepted.

Baseline implementation: AccessPolicy::automatic_codes enables only code, with manual
false from Default. POLICY is volatile; configure changes only memory. UI explicitly says
manual disabled on restart. This was an implementation choice, not a user requirement.

Final execution checkpoint: source snapshot3af9c05/productionc88c34f/common29462e6,
MSI f4d27e4. MA-01..04 implemented.50 native/config/wire/hash, Flutter110 pre/post-build,
standalone15 and CTest3 PASS. Both1.3.7/build85 full builds, final MSI readonly/isolation/
all125 payload hashes/explicit ICE, APK preserved signer/no-camera/16KB/exact-native/AOT
English prompt gates PASS. Both source archives/private gates1237/1363 and checksums ready.
MSI unsigned; inherited WindowInjection VC++ x64 prerequisite present on this laptop, other
machines need separate checks. No85 installs or physical paired/installer acceptance yet.

Current per-task status (overrides original planning table statuses below):

| Task | Code / package implementation | Automated verification | Device acceptance |
|---|---|---|---|
| MA-01 | Implemented/common29462e6 | Native preference3 PASS | Saved ON/OFF actual restart pending |
| MA-02 | Implemented/c88c34f | Native access18/default/gating PASS | Cold launch and service/lock pending |
| MA-03 | Implemented/c88c34f | Native cancellation/save failures PASS | Paired pending cancel/restart pending |
| MA-04 | Implemented/c88c34f | Full Flutter110 PASS | Final85 UI pending |
| MA-05 | Unchanged wire reviewed | Native/config/wire/hash50/Flutter110/policy15/CTest3 PASS | Actual sender/receiver pending |
| MA-06 | Matching85 native/UI builds and archives COMPLETE | Version/signer/native/source private gates PASS | No85 installs/paired acceptance inferred |
| MA-07 | Final per-user English MSI COMPLETE/f4d27e4 | Final125 exact payload/isolation/explicit ICE PASS; unsigned | Human install/uninstall and other-machine runtime pending |

Chosen behavior:
- Windows receiver, missing new preference => manual approval ON, including existing
  profiles that never saved a manual choice. Previous volatile OFF is not a saved choice.
- Persist explicit ON/OFF; saved OFF must remain OFF after restart. Persist only the
  boolean preference, never codes, pending requests, nonces or prior acceptance.
- ON permits sending a request, NOT remote control: local Accept/Deny still mandatory.
  Existing60s timeout, nonce/connection binding, queue/source bounds and no replay remain.
- Locked/stopped/service-only/noninteractive receiver still denies/hides capabilities,
  cancels pending requests; saved ON is not a bypass or service/prelogin enablement.
- Password, reusable7200s code, guessing protections, saved credentials, English UI,
  buzzer/ring priority, updater shutdown and camera removal remain unchanged.

| ID | Platform | Task | Status | Dependencies | Notes / acceptance / tests |
|---|---|---|---|---|---|
| MA-01 | Windows | Persist local manual preference with checked writes | Planned | Explicit start | Add isolated nonsecret option/API using existing checked Config2 storage pattern, preserve encryption/other options. Absent => ON, explicit ON/OFF recognized, invalid value fails closed with English error. Save failure must not claim persistence or enable against unsaved OFF. Test parsing, unrelated-config preservation, failure handling, fresh/existing migration and saved OFF/ON round trips. |
| MA-02 | Windows | Initialize receiver policy from saved preference | Planned | MA-01 | Load correct app profile before advertising manual capability; do not rely on Flutter polling/toggle to enable. Default ON at startup, persisted OFF honored. No OS service/firewall/privacy/auth change. Test cold/restart, main vs CM/service process and stopped/locked availability gates. |
| MA-03 | Windows | Toggle persistence and cancellation | Planned | MA-01..02 | Native setter persists choice and updates authoritative policy coherently; OFF clears/rejects pending requests and is respected after restart. Permit safe disabling while unavailable without enabling code/manual or bypassing availability. No stale nonce grants, no locks across await, no reset of code/guess budget. Tests disable/cancel/race/restart and write failures. |
| MA-04 | Windows UI | Correct English setting explanation | Planned | MA-02..03 | Replace misleading session-only/restart-off description with saved-setting explanation. Checkbox reflects backend, failed save shows English error. Compact settings and no-Arabic/source-wiring regressions. Never edit language template or force locale; no Android receiver UI addition. |
| MA-05 | Both review; Windows verification | Regressions and sender compatibility | Planned | MA-01..04 | Review existing Android2084 sender/probe/pending path; wire/FRB signatures unchanged. Test missing-pref ON, saved OFF blocks sender, saved ON pending only, Accept/Deny/timeout/replay/no pre-accept control. Native policy/CM/config/wire/password/code/updater/camera/buzzer and full Flutter regression. If protocol or Android code must change, revise scope/build plan first. |
| MA-06 | Both build; Both paired acceptance | Matching release candidates and delivery | Executing | MA-05 | Both1.3.7/build85, Android internal2085. Full native/UI builds, version/hash/exact-native/signature/source/license/private-material gates, local commits only. Preserve84/original apps/signing/data/service. Deliver final MSI/APK; user handles install/security/auth/approval. Do not infer live acceptance from build gates. |
| MA-07 | Windows installer; Both version check | Private English MSI and paired final release | Executing design | MA-05..06 | Separate per-user Helper product/upgrade identity and folder, English installer, app/start-menu files only. No upstream service/firewall/process-kill/driver/auto-launch/updater custom actions; no removal of original app or user config. Preserve upgrade identity across private releases, validate MSI properties/tables and extract/hash payload against staged candidate. Deliver matching1.3.7/build85 APK with original helper signer and source archives/checksums; disclose lack of Windows signing certificate if none available. MSI creation does not mean install/uninstall device acceptance. |

Test completion is not device acceptance. Mark implemented code, automated checks and
physical paired acceptance separately in PROJECT_STATUS/windows STATUS/android STATUS
and helper status. No push, branch/remote change, duplicate source tree or key exposure.
