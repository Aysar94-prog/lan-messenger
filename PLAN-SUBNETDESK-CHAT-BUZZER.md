# SubnetDesk chat attention / buzzer plan

Date: 2026-10-08. Execution approved by user on this date; implementation in progress.
User also approved a separately identified side-by-side Android candidate; preserve original.

## Scope and execution gate

This request concerns the separate SubnetDesk helper, not LAN Messenger. First-stage
assumption follows the current usage: Android sends a buzzer to the Windows receiver
inside an authenticated, active session. Reverse-direction/Android receiver support is
not automatically included; revise this scope if the user wants both directions.

The user's "yes" to the buzzer/microphone checkpoint authorizes this feature's execution.
The subsequent three-access-mode request is a separate plan, not yet authorized for execution.
Supplied login credentials must not enter plans, logs or source.

Source: D:/LAN-Messenger/reference/SubnetDesk-v1.3.0/source/zibo-chen-SubnetDesk-1d3ac5a.
Outputs: D:/LAN-Messenger/outputs/SubnetDesk-helper-cm.

## Proposed behavior

- An Android chat/session action sends an attention event, not a voice-call request.
- Windows plays one short alert and visibly highlights the matching session/chat.
  Expand a folded dock and select its conversation without stealing keyboard focus.
  Review manually minimized/hidden windows separately; do not turn polling into focus theft.
- The receiver can mute buzzer sounds. Respect Windows audio settings; a visible
  indication remains when sound is muted or playback fails.
- Proposed cooldown: one accepted buzz per connection per 5 seconds, plus a receiver-wide
  maximum of 3 audible alerts per 10 seconds. Enforce at the receiver, not just the button.
- Only authenticated, connected sessions may buzz. Clear attention/cooldown state on
  session removal; never deliver an old event to a different/reused connection identity.
- No persistent chat or buzzer history. This does not add disk storage or silently change
  the existing in-memory chat lifecycle. Immediate clearing on disconnect is a separate choice.
- An older receiver remains usable for ordinary chat; the buzzer action is unavailable
  when support has not been advertised. Do not encode commands as magic chat text.

## Verified starting points / contract caution

Shared Flutter chat: flutter/lib/common/widgets/chat_page.dart. Android session actions:
flutter/lib/mobile/pages/remote_page.dart. Windows dock: cm_session_panel.dart and server_model.dart.
Current ChatMessage carries only text; connection.rs forwards chat to the separate CM over IPC.
The reviewed chat/control paths do not expose a dedicated remote buzzer action.

libs/hbb_common/protos/message.proto contains LanClientHello.client_capabilities, currently
sent as zero; LanServerHello has no server-capabilities field. The signed handshake transcript
does not authenticate a capability bitmap. Do not invent a server field, treat an unauthenticated
bitmap as permission, or bump the strict LAN handshake version for this optional UI feature.
Prefer support advertisement and a bounded typed control event on the existing encrypted,
authenticated session. Final protobuf/extension encoding requires compatibility review first.
Any hbb_common source change must be explicit, reproducible and included in the corresponding
source artifact; do not keep claiming the original pinned submodule is unchanged afterwards.

## Small tasks

| ID | Platform | Task | Status | Dependencies | Notes / acceptance criteria |
|---|---|---|---|---|---|
| SD-BZ-01 | Both | Trace Android sender, authenticated transport, Windows IPC and CM lifecycle | Initial read-only review complete; deeper trace planned | None | Identify authentication gate, capability delivery and all disconnect/reuse paths. |
| SD-BZ-02 | Both | Define optional typed attention event and support advertisement | Planned; awaiting start | 01 | Explicit contract, bounded payload, old peers keep chat working; no magic text, trust bypass or handshake downgrade. |
| SD-BZ-03 | Both | Wire event through sender/native bindings and receiver/CM IPC | Planned; awaiting start | 02 | Correct session identity; ignore pre-auth, unsupported, malformed and stale events. Review view-camera allowlists explicitly. |
| SD-BZ-04 | Android | Add chat/session buzzer action and feedback/cooldown | Planned; awaiting start | 03 | Disabled offline/unsupported; correct peer; clear sent/throttled state; no microphone permission needed. |
| SD-BZ-05 | Windows | Handle event in dock: matching chat, expansion and visible badge | Planned; awaiting start | 03 | Folded dock becomes visible; correct session selected; drafts retained; no unsolicited keyboard focus. |
| SD-BZ-06 | Windows | Short sound, mute control and receiver-side rate limits | Planned; awaiting start | 03, 05 | One bounded alert; respects sound settings; visible fallback; per-session and aggregate limits cannot be bypassed by sender. |
| SD-BZ-07 | Both | Automated event/security/lifecycle and UI regression tests | Planned; awaiting start | 04, 05, 06 | Fake-clock throttle tests, mixed versions, multiple sessions, disconnect/reuse and no persistent history. |
| SD-BZ-08 | Android | Resolve candidate package identity and signer before packaging | Planned; user choice required before APK | 04 | Official SubnetDesk signer unavailable; never overwrite/uninstall official app or reuse LAN Messenger's private key. |
| SD-BZ-09 | Both | Build distinctly versioned Windows and Android candidates, with matching source | Planned; awaiting start | 07, 08 | Separate source verification/build/signature evidence from actual acceptance; preserve previous artifacts and installed app data. |
| SD-BZ-10 | Both | Physical Android -> Windows acceptance and mixed-version checks | Planned; awaiting start | 09 | User actually hears/sees the alert, including folded/muted/throttled cases; record failures without inferring acceptance from widget tests. |

## Testing tasks

1. Contract corpus: supported, absent support, malformed, unknown event, over-limit payload,
   pre-auth and replay/stale session. Compare both app implementations.
2. Fake-clock limits: repeated sends, 5-second boundary, aggregate cap, multiple clients,
   disconnect/reconnect and connection-ID reuse. Receiver remains responsive under spam.
3. Android UI: button states, correct selected session, reconnect and feedback; microphone
   denied must not prevent sending a buzzer.
4. Windows UI: expanded/folded/minimized states, correct conversation, unread indication,
   preserved draft, no focus theft, sound muted/failure and multiple sessions.
5. Run existing Rust/native and Flutter regression tests relevant to changed code; full
   builds for both candidates. Record test counts and compiler failures separately.
6. Device acceptance: modified Android -> modified Windows; old Android -> new Windows
   ordinary chat; new Android -> old Windows disables buzzer but retains ordinary chat.
   Compare binaries actually running, not just archive names or tray session count.
7. Privacy/export check: no credentials, chat contents, buzz history or private signing
   material in source archives/logs. No automatic installation or backend replacement.

## Independent unfinished work

Android password saving remains its own plan, not silently bundled into this feature.
Voice diagnosis now has direct device evidence of missing Android RECORD_AUDIO permission;
see DIAGNOSIS-SUBNETDESK-VOICE-20261008.md. Permission enablement and an audible paired
retest remain pending. Buzzer acceptance will not count as voice-call acceptance.
