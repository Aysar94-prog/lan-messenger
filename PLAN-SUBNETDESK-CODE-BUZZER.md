# SubnetDesk longer temporary code and audible Windows buzzer

2026-10-09, Asia/Jerusalem. Status: EXECUTION APPROVED by user "start".
Reconciliation: Both84 builds finished; selected native43/runner3/Flutter106 PASS.
Android2084 installed and English UI/AOT checks PASS; actual pending-request wording
and physical paired acceptance remain open. Windows84 staged; user Esc stopped computer
use before automated switching; later read-only sees user/external84 mainPID4420/exact
path/version/native hash/listener21118.84 ZIP/source archives and final output records
unfinished. New manual-default request is a separate planning-only task in
PLAN-SUBNETDESK-MANUAL-DEFAULT.md; current84 default manual OFF still unchanged.
User added Android no-Arabic requirement; CB-07 added within authorized work. Build both
private1.3.6/build84 candidates because actual Android message source needs correction.
User asks for two-hour temporary code or regeneration on app restart, reports buzzer
works visually but assumes "now sound" means NO audible laptop sound. Confirm if not.

Baseline: production85b9941/807f718/commonb56ada4, build83. Read-only runtime verifies
Windows1.3.5+83 mainPID23120/exact portable path/listener21118; no established session
at check. Native DLL hash matches staged83. Previous old82-close blocker is resolved
externally/user-launched; no automated security/permission changes. User report is evidence
of visual buzzer operation, not our independent UI or audible verification. Android2083
remains installed from previous work; no receiver feature parity inferred.

Chosen expiry:7200 seconds maximum, receiver main-app restart generates a new random
volatile code. User explicitly chose reusable code for multiple connections until expiry
or restart; successful connections DO NOT rotate/consume it or reset remaining attempts.
CB-01 code choice resolved; explicit execution start received. New code button and
five-attempt revocation remain deliberate security overrides. No persistence of codes.
Planned availability handling: locked/stopped/noninteractive receiver hides code and
denies all optional grants, cancels pending manual requests, but preserves still-valid code
only in receiver-main process memory so unlock/listener resume does not unexpectedly rotate
it. Wall lifetime continues while unavailable; if expired, regenerate only once available.
This changes old suspend/revocation semantics, not the authoritative access-denial gate.
Closing/restarting only the CM/chat process does not reset the receiver main-app code.

Confirmed code dependency: CODE_TTL currently120 also drives failed-attempt window and
cooldown. Do NOT merely change its constant to7200: separate expiry from120-second failure
window/cooldown. Preserve five attempts, guess budgets across manual/automatic regeneration,
per-connection grant gating/concurrency and no downgrade; multiple valid connections are
now intended. Independent60-second manual approval timeout remains unchanged.

Sound evidence: ChatAttentionState defaults to SystemSound.play(SystemSoundType.alert).
Pinned Flutter engine Windows platform_handler.cc366..371 calls MessageBeep(MB_OK) then
unconditionally reports Success without checking return. This is the Windows Default Beep,
not the app-owned voice WAV. Current default beep assignment/file exists, default sound
scheme selected, Audiosrv/AudioEndpointBuilder running. Exact endpoint/mixer/app-mute cause
is not established. No sound playback/volume/scheme changes performed in this planning turn.
Primary API reference: https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-messagebeep

Proposed sound repair: dedicated bounded app-owned buzzer PCM via existing Windows native
audio channel, distinct from voice ring. Preserve mute,5-second per-session cooldown,
aggregate cap, authorized/connected gates and nonactivating chat reveal. Surface playback
failure honestly. Arbitrate shared PlaySound output: buzzer must not interrupt/stop a pending
voice ring; explicitly suppress while voice owns output (keep visual indication, no stale
queued beep), tests cover ownership/stop/mute/disposal. No changing OS mixer/security,
unmuting user sound, forcing output-device/volume or spawning a workaround audio helper.

| ID | Platform | Small task | Status | Dependencies | Notes / acceptance / testing |
|---|---|---|---|---|---|
| CB-01 | Windows | Decide code reuse semantics and confirm no-sound report | Code choice resolved; no-sound interpretation stated | None | User chose same code/multiple connections until two hours or app restart. No execution authorization inferred from choosing semantics. |
| CB-02 | Windows | Separate code lifetime from guessing protection; implement selected semantics | Implemented9a9337b; native policy/CM14 PASS, paired pending | CB-01, explicit start |7200-second exact boundary; repeat/concurrent valid connections preserve same code/deadline/attempt count. Restart/New code invalidate old code; locked/stopped hide and deny but preserve unexpired volatile code, no stale grant. Five guesses and120-second limits preserved across generation/suspension/success; manual timeout60s unchanged. |
| CB-03 | Windows | Update home code timing text/countdown | Implemented; compact widget PASS, runtime pending | CB-02 | English translation keys, two-hour h:mm:ss display without overflow, reusable-code explanation matches actual policy. Render compact home and refresh tests; never edit lang/template.rs. |
| CB-04 | Windows | Dedicated native buzzer tone and playback ownership/errors | Implemented; Dart/CTest PASS, physical pending | Explicit start | App-owned0.6s PCM, no dependency on Default Beep; return errors instead of silent success. Preserve visual dock, mute/rate/session gates; voice ringtone priority/suppression and stop/disposal tests, PCM CTest. Physical laptop audibility requires real buzz. |
| CB-05 | Both review/build | Regression and unchanged-wire compatibility | Flutter106/runner3/native43 PASS, Both builds PASS | CB-02..04, CB-07 | Native policy/CM gates, Flutter buzzer/mute/voice/audio channel/widget tests, private updater/camera/password/code/manual/chat regressions. Android wire unchanged; Android rebuilt specifically for CB-07 message correction, no receiver parity. |
| CB-06 | Both build/actual acceptance | New distinct candidate/package and live test | Both84 built/staged; Android installed; Windows user/external launch; archives/paired pending | CB-05 | Windows1.3.6/build84 and Android2084, source/native/version/hash/license/private-material gates, local commit only. Preserve83 outputs/original apps/data/service/Android key. Session-safe replacement only with no active session/unsaved dialog; human handles security/auth/Firewall. User audibly confirms buzzer and tests code expiry/restart, both-sides paired sessions. No total-completion claim from builds. |
| CB-07 | Both; Android message | Remove remaining forced Arabic UI statuses | Implemented; source/AOT/device selection-screen PASS, real pending screen open | Explicit start, user-added constraint | User confirmed exact waiting-for-approval text in shared model.dart; also Windows server_page.dart. Both replaced with English translation keys, UI-wide Arabic guard PASS. Preserve user-entered Arabic chat/names and existing selected English preference; no OS language/credential/security setting mutation. New Android2084 preserved helper signer/original2075. |

Update PROJECT_STATUS/windows STATUS and comparison after execution. Android record must
state whether sender remains2083, not pretend parity or a new APK. Preserve other LAN
Messenger work; canonical shared LAN repo and existing helper checkout, no duplicate repos.
