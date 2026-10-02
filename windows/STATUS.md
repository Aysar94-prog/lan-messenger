# Windows status

## Voice calls — implemented and verified by a real cross-platform call (2026-10-03)

Windows now has a complete, working voice-call implementation, wire-compatible with Android's:
`CallProtocol.cs`/`CallSignaling.cs` (same 4-byte-length+JSON framing, message types, timing/
limit constants, admission/state-machine rules as Android, ported line-for-line), `CallSession.cs`,
`CallChannel.cs` (the CALLCONNECT-handoff transport), `CallController.cs` (the state machine),
`CallSettings.cs`, `CallAudioIo.cs` (continuous winmm capture/playback reusing the project's
proven P/Invoke pattern), `WebRtcCallMedia.cs` (SIPSorcery-based `ICallMedia`, G722 audio codec —
SIPSorcery's bundled encoder does not actually support Opus despite its constructor signature
suggesting otherwise; G722 is a standard WebRTC fallback both sides offer, confirmed to interop),
`PeerEngine.Calls.cs` (the CALLCONNECT handoff and local-only call-log entry), `CallLogMarker.cs`,
and a minimal (correctness-first, not yet visually matching Android's reference teal-header/
hang-up-disc design) `CallView.cs`/`ChatWindowCalls.cs` UI. SIPSorcery 10.0.17 and its full
transitive dependency graph (92 packages) are vendored into `vendor/nuget` to preserve the
project's offline-only build policy.

**Verified by a real two-device call over the LAN** (Windows build machine ↔ a physical Android
phone, both directions) using a headless console driver exercising the production
`PeerEngine`/`CallController`/`WebRtcCallMedia` classes directly (there is no GUI-automation tool
for native WinForms): Windows→Android reached `Connected` with a live, ticking in-call timer on
the Android side and a clean `RemoteHangup` teardown with a real computed duration (33.5s);
Android→Windows reached `Connected` the same way (15.4s), auto-accepted on the Windows side.
Call-history entries ("X called you · duration") appeared correctly on Android for both
directions. This confirms G722 codec/SDP negotiation actually converges between SIPSorcery and
Android's libwebrtc — the single biggest flagged interop risk going in.

**One real bug found and fixed by this test**: `SecureChannel`'s constructor hardcodes a 6-second
`ReadTimeout`/`WriteTimeout` on the underlying network stream, sized for the ordinary quick
HELLO/READY/message handshake. The CALLCONNECT handoff reuses that same stream/instance for the
entire lifetime of the call-signaling channel, where frames are legitimately tens of seconds
apart (ringing wait, human accept/decline time, silence between heartbeats) — so any call died
with a spurious `SignalingLost`/socket-timeout the moment a human took longer than 6s to answer.
Fixed by adding `SecureChannel.UseLongLivedTimeouts()` (sets both timeouts to infinite) called
once, immediately after the CALLCONNECT line is written/read, on both the outgoing side
(`PeerEngine.Calls.cs`'s `OpenCallConnectionAsync`) and the incoming side (`PeerEngine.cs`'s
`Receive()` CALLCONNECT branch). This is a Windows-only bug (Android's equivalent handshake layer
never imposed this timeout) — no wire or Android-side change needed.

Remaining open items: `CallView.cs` does not yet visually match Android's reference call-screen
design (functional parity only); `tests/run.ps1` has no automated call-protocol/state-machine
test yet (W-phase test tasks from the plan below are not written); call quality under real-world
conditions (packet loss, multiple devices, degraded Wi-Fi) has not been stress-tested, only a
clean two-device LAN call.

Before picking up further call work, read the **Addendum (2026-10-02)** at the end of
`.ai-planner/sessions/20260930-091333-43c139/planning/plan-v006.md` (the shared voice-calls plan)
for the full decision record this implementation was built from, and the W0–W5 task breakdown for
what remains (test-writing tasks, UI-parity pass, stress testing are the main gaps).

## Voice Messages implemented in source (Phase 2 / W01-W10, WT01+WT06, not a release)

Windows Phase 2 of the shared Voice Messages feature (`plan-v003`, tracked at
[PLAN-VOICE-MESSAGES-WINDOWS.md](../PLAN-VOICE-MESSAGES-WINDOWS.md)): record, send, receive and
play short voice clips, reusing the existing encrypted Normal attachment store with a
`voice-<message-id>.lanvoice.wav` marker filename — no new LM4 wire frame. Fixed format:
RIFF/WAVE, 16 kHz mono 16-bit PCM, 640-byte/20 ms frames, max 300 s/9.6 MB. `waveIn`/`waveOut`
P/Invoke capture/playback (serialized native control, minimal-work native callbacks, idempotent
Stop/Dispose — avoids the classic MM-callback deadlock); a durable, encrypted, capped (10-entry)
draft registry with startup reconciliation; transactional Send (message id and marked filename
allocated together); Candidate/Fetching/Playable/Invalid/Unavailable receiver cards; one active
inline player app-wide with seven-step seeking (±10 s buttons) and no plaintext playback file;
voice folded into the existing automatic-media scheduler under the shared nine fixed rules;
full keyboard/accessibility coverage. W11 (this entry) is the only remaining code-adjacent task —
W10 verification is done; **manual two-device acceptance on physical hardware is Pending, cannot
be performed by an agent.**

Build: `dotnet build windows/LanMessenger.csproj` passes (0 errors). WT01
(`CsharpHarness --voice-check`, the real `VoicePcmAssembler`/`VoiceWav`/`VoiceSeek`/`VoiceMarker`
production classes against the shared `tests/voice_messages/vectors/manifest.json`) passes
(`PASS=38 FAIL=0 SKIP=30`). WT06 (`tests/voice_architecture_check.py`) confirms the
transport-independent PCM/WAV/marker/seek core has no WinForms/PeerEngine/attachment-store/LM4/
device-API dependency and that Windows carries no platform-local copy of the shared fixtures.
WT02-WT05 (fake-audio-input lifecycle faults, scheduler/dedup coverage, one-player-enforcement/
export) are not yet written. A full `tests/run.ps1` run surfaced one pre-existing failure in
`tests/group_membership.py`, unrelated to Voice Messages (traced to `AddMember`'s live
`QueryCapability` call returning 0 on a real network timeout under the heaviest 16-17-process
scenario — the same class of environmental contention already documented below for the
2.0.0-era group-membership work, not a regression from this feature).

This is **platform-local only; interoperability with the Android Phase 1 work (developed
concurrently by another agent in this same repository) has not yet been verified** — that is
Phase 3 of the plan, not yet started.

## Offline controls implemented in source (G1–G3, not a release)

The Windows engine has reusable `Start`/`GoOffline` and terminal `Dispose` (G1). G2 adds a default-Online persisted request read before `Shown` starts the network, one transition method shared by toolbar and tray, actual engine state and bind error in the status line, and local queued direct/group sends while Offline. Closing the window still hides to tray; Exit terminates. Offline closes LAN listeners/connections/discovery; Refresh and Add by IP are disabled while Offline, and verification, group capability checks and new remote downloads cannot reach LAN. Local identity, contacts, groups, history, cached attachments and partial transfers remain accessible; interrupted transfers resume on reconnect. Remote presence turns gray after approximately 12 seconds. A failed bind leaves actual state Offline despite an Online request; toolbar and tray show Retry online, which retries without flipping the persisted preference. The native UI suite covers offline preference/queued sends and blocked-port retry. No release or LM4 wire/compatibility change.

G3 automated check: cross-platform `tests/offline_lifecycle.py` covers accepted idle control socket shutdown, Offline Add-by-IP/refresh and remote-download gating, rejected discovery, delayed avatar sync, group capability refusal, three interrupted receiver transfer modes (Fast direct, ordinary direct, encrypted ordinary cache) plus interrupted Fast sender, partial stability, no early completion marker, integrity and duplicate-free retries. The full `tests/run.ps1` suite (including Windows native UI) and Android SDK source compilation passed with SDK 9 MSBuild and in-workspace test output; one prior full attempt failed under the 16-member capability stress scenario and passed on isolated rerun and final full rerun. This is loopback engine coverage, not physical LAN acceptance; Windows native UI blocked-port/retry is automated, but physical Windows/two-device acceptance: **Pending-Unavailable**.

Reviewed 2026-09-29. Release: **2.2.0** — the first Windows release to package the Voice
Messages Phase 2 work described in the section above (record/send/receive/play, ±10s seek
steps, one active inline player app-wide). No wire/storage change from 2.1.0; every other 2.1.0
capability (group ownership transfer, 2.0.1 carryover) is unchanged.

**Real bugs and UX changes from actual device testing of the first 2.2.0 build, fixed same day:**

1. **Choppy recorded audio — round 1.** `VoiceRecorder.cs`'s native waveIn buffer-ready callback
   dispatched each filled buffer independently to the thread pool
   (`ThreadPool.QueueUserWorkItem`), with no ordering guarantee between them — but
   `VoicePcmAssembler.Push` is a strictly sequential PCM stream, so two buffers processed out of
   order scrambles the recorded audio. Fixed by routing every buffer through one dedicated FIFO
   processing thread instead (mirroring the pattern already used on Android's
   `VoiceRecorder`/`VoicePlayer`), with careful `Stop()`/`Dispose()` draining so neither the tail
   of a recording gets silently dropped nor native buffer memory gets freed while the processing
   thread might still be using it.
2. **Sender sees their own sent voice message as a plain file.** `ChatWindowMessages.cs`'s
   `MessageCard` gate dropped the `!mine` condition, mirroring Android 2.2.2's identical fix — the
   sender now sees a proper voice-message player for a message they just sent, reversing the
   original W07 design choice. **Confirmed fixed by the user.**
3. **Choppy audio — round 2, the real root cause, in playback rather than capture.** After round
   1's recorder fix, the user reported the audio was still cutting out. `VoicePlayer.cs`'s
   `FillQueueLocked` refilled the fixed 4-slot native buffer pool by iterating the list from the
   start on every call, tracking only a *count* of in-flight buffers (`buffersInFlight`) rather
   than which specific slot was actually free — after more than one buffer had completed, this
   could pick a slot that was still genuinely queued/playing in the driver and overwrite it with
   fresh data mid-flight, corrupting live audio output. This is the bug round 1 should have looked
   for but didn't (the initial diagnosis wrongly assumed playback's buffer refill was inherently
   order-independent and therefore safe — true for *which chunk of `wav` comes next*, false for
   *which native buffer slot is safe to reuse*). Fixed by giving each buffer slot its own explicit
   `Busy` flag, checked by `FillQueueLocked` and cleared by the completion callback (and defensively
   by `Seek()`, in case a driver doesn't fire a completion callback for every buffer a
   `waveOutReset` flushes).
4. **WhatsApp-style icon buttons and a real seek bar**, matching Android 2.2.3/2.2.x: Play/Pause
   buttons now show `▶`/`⏸` glyphs (`ChatWindowVoicePlayback.cs`'s `PlayIcon`/`PauseIcon`) instead
   of text, and the fixed ±10s buttons are replaced by a draggable `TrackBar` scrubber
   (`BuildSeekBar`/`UpdateSeekBar`) wired to `VoicePlayer.Seek(long)`'s existing absolute-position
   API, in both `ChatWindowVoiceCard.cs`'s received Playable row and `ChatWindowVoice.cs`'s
   own-draft preview row. Since the button's icon always reflects `VoicePlayer.Playing`'s live
   state (already `false` the instant a clip finishes), it settles back on `▶` on its own — no
   separate "stuck on Pause" bug existed here to fix, but this was explicitly called out by the
   user as a requirement given Android's history with exactly that bug.

`dotnet build windows/LanMessenger.csproj -c Release` passed (0 errors, the one pre-existing
benign `CS1998` warning in `ChatWindowVoice.cs` noted above). Published framework-dependent via
`dotnet publish -c Release -o outputs/LanMessenger-Windows-2.2.0` (matching every prior Windows
release's shape — requires .NET Desktop Runtime 9) and zipped: `outputs/LanMessenger-Windows-2.2.0.zip`
(2,849,138 bytes, SHA-256 `40a7f09b…`; manifest: `outputs/SHA256SUMS-Windows-2.2.0.txt` —
superseded twice; see `PLAN-VOICE-MESSAGES-WINDOWS.md` for every intermediate hash). The native
`tests/WindowsUi` suite needed updating alongside round 4: it asserted the literal strings
`"Play"`/`"Pause"` and clicked a `"Forward 10 seconds"`-named button, both now gone — updated to
check the `▶`/`⏸` glyphs and to drive the new `TrackBar` via `Control.OnMouseUp` (simulating a
real drag-release, the same event `BuildSeekBar`'s handler is wired to). With that update, a full
run against this exact build passed every native Windows UI test, including every Voice Messages
one (arrival/auto-download, Play starting real playback, one-active-player enforcement,
pause/resume toggle, seek not crashing, force-stop-and-finalize on conversation
switch/tray-close/Offline), plus the voice-specific `CsharpHarness` checks (`--voice-check`,
`--voice-device-check`, `--voice-scheduler-check`, `--voice-draft-reconcile-check`) run directly —
the pre-existing `--voice-device-check` "double `Dispose()` after `Stop()`" case caught a real
non-idempotency bug in round 1's recorder fix (`BlockingCollection.Dispose()` isn't itself safe to
call twice) before it shipped. The full `tests/run.ps1` suite's own 16-member `group_membership.py`
stress scenario hit its already-documented, environmental TLS-handshake flake partway through
(reproduced in isolation, unrelated to any of this work) before ever reaching the native UI suite
in this pass — the voice-specific and native-UI checks above were run directly instead, rather
than waiting through the full suite's own long tail. As noted above, W11's manual two-device
physical acceptance is still **Pending** — three of the four items above came from the user's own
device testing of earlier 2.2.0 builds, not from any automated suite, which is exactly the class
of bug that Pending note has always been flagging as a real risk.

## Implemented

- **2.0.0** (foundation, still current): group membership is no longer fixed after creation. `Group.Members` is now the live active roster (it shrinks when someone leaves, grows when the owner adds someone) with a `MembersVersion` counter; changes propagate to every active member as a full snapshot (new `LM4\tMEMBERSUPDATE`/`MEMBERSUPDATEACK` frame), adopted whenever strictly newer — so a device offline through several changes catches up in one step. `GROUP` invites send the plain old 6 fields while a group's version is still 0 (so an unrelated, not-yet-updated device is genuinely unaffected) and 7 fields (with the version) once it's actually been mutated; the receiving dispatch accepts either. Any owner-initiated growth (`AddMember`, used by `ReinviteMember`) requires a **fresh** `LM4\tCAPS` capability query (mirroring the existing `FILECAPS` pattern) of everyone who'd be in the group afterward — never a cached/past result — refusing outright with a clear error if anyone doesn't answer. Leaving is never gated by this. Already-saved groups (the old `Group.Left`-overlapping shape) migrate once automatically on load. `ShowMembers` sources departed members from the `AllKnownMembers` API instead of `Group.Left`, its Re-invite button runs off the UI thread since `ReinviteMember` does real network I/O, and each active member's row carries a "Synced"/"Catching up" tag (comparing `MemberAckedVersion(groupId, peerId)` against `MembersVersion`) — never implying the whole group is consistent. See [PLAN-GROUP-MEMBERSHIP.md](../PLAN-GROUP-MEMBERSHIP.md).
- **2.0.1**: 2.0.0 briefly also shipped a join-request feature (any verified contact of a group's owner could ask to join by ID, with an owner accept/ignore queue in the Members dialog). **That feature has been removed** by explicit request — `RequestJoin`/`AcceptJoinRequest`/`IgnoreJoinRequest`, the `JOINREQUEST`/`JOINREQUESTACK` wire frames, the `J`/`Q` storage rows, and the Members-dialog queue/Request-to-join dialog are all gone. The mutable-membership foundation above (AddMember, capability checks, migration, Re-invite, sync status) is untouched. A saved data file that still has old `J`/`Q` rows from before the removal loads fine (those rows are now silently skipped rather than rejected). Two unrelated UI changes landed alongside the removal: `DeleteConversationConfirm`'s context-menu item and dialog now say "Leave group" (not "Delete conversation") for a group, with wording that no longer incorrectly claims verification is revoked — a group action was always just a leave, never a real delete; and a new "Leave group" toolbar button (next to Members in the chat header, enabled only for a group) makes the same action reachable from inside an open group chat, not just from the conversation list.
- **2.1.0**: group ownership can now be handed off, closing the 2.0.1 gap noted above. New `TransferOwnership(groupId, newOwnerId)` — owner-only, requires a fresh live `CAPS>=2` from *every* current active member (not just the incoming owner; refuses outright, nothing mutated, if anyone can't support it), and refuses if any member hasn't yet acked their first invite (the `GROUP` frame's `a[3]!=sender` invariant makes it impossible to deliver a first-time invite "on behalf of" a different owner mid-handoff). `Group.Owner` updates immediately, everywhere — the tricky part is that the *old* owner must stay the delivery/leave-acceptance authority for this one change (not the new owner) until every other member has actually caught up, tracked via a new durable `pendingOwnershipHandoff` set (persisted as a new `O` storage row) that survives a restart; `Deliver()`'s broadcast gate and `HandleLeave`'s accept check are both widened to check it alongside `Group.Owner==Id`. `MEMBERSUPDATE` gains a 6th (owner) field once a group has ever transferred (tracked via a new persisted `EverTransferredOwnership`/`T` row, same one-way shift as `GROUP`'s existing 6-vs-7-field split); `CAPS` bumps its reply from `1` to `2`; `AddMember` requires `CAPS>=2` (not just `>=1`) for any group that's ever transferred, since the wire shape has permanently changed for it. `DeleteConversationConfirm` shows a "Choose a new admin" picker instead of the normal confirmation when the owner tries to leave a non-empty group; the conversation-list row and chat heading both show "Leaving — waiting for members to catch up" (composer/send/leave-button disabled) until the deferred departure actually completes. See [PLAN-GROUP-OWNERSHIP-TRANSFER.md](../PLAN-GROUP-OWNERSHIP-TRANSFER.md) — including why a simpler "wait for just the new owner" design (an earlier draft, caught on external review before any code was written) doesn't work, and the accepted limitation that remains (a member who never comes back online blocks the departure indefinitely).
- 0.8.12: deleting a contact now also notifies them. `DeleteConversation`/`DeleteAllData` queue a peer id in a persisted `forgotten` set; `Deliver()` sends them a new `LM4\tFORGET` frame (retried until acked, same shape as `SEEN`/`SEENACK`) whenever that peer is next reachable, which calls `Revoke` on their side and raises a new `Forgotten` event — wired in `Program.cs` to a tray notice next to the existing `Received` wiring. Deleting a group is still a leave (unaffected for other members), but it now queues a `LM4\tLEAVE` frame to the group's owner (persisted `pendingLeaves`, keyed by the owner id captured before the local group record is dropped); the owner records the departed member in a new `Group.Left` field (a 7th, optional, backward-compatible `G` storage field) and `Deliver()`'s invite-resend loop skips anyone in `Left`. `ShowMembers` (`ChatWindowDialogs.cs`) now shows departed members distinctly with an owner-only "Re-invite" button, calling the new `ReinviteMember(groupId, memberId)`, which clears `Acknowledged`/`Left` for that id so the next delivery cycle resends the `GROUP` invite and they rejoin with the same member list — no re-verification needed. An old build that doesn't understand `FORGET`/`LEAVE` simply never acks; the sender keeps retrying rather than erroring.
- 0.8.11: right-click a conversation or group row for a "Delete conversation" option — clears its history/attachments and, for a contact, also revokes verification and removes the peer record entirely (a group is left). A rediscovered forgotten contact reappears as a brand-new, unverified device. A new "Delete app data" toolbar button wipes every conversation/contact/group/attachment on the device while keeping identity, display name and profile picture. Both ask for confirmation first. `PeerEngine.cs` also went through a purely internal file-split refactor in the same window (`ChatWindow` split across `ChatWindowRender.cs`/`ChatWindowMessages.cs`/`ChatWindowAttachments.cs`/`ChatWindowDialogs.cs`/`ChatControls.cs`; `PeerEngine` split into `Storage.cs`/`Avatars.cs`) — no behavior change, not user-visible.
- 0.8.10 hotfix: a conversation first opened with fewer than 10 messages now grows its visible window up to 10 as messages arrive. Empty-chat hints are removed before redraw/switch, preventing duplicate text. Native UI regressions and an independent five-to-six-message reproduction passed.

- Messaging, groups, device verification, blue/gray presence, attachment draft before Send, and local chat clear.
- Automatic inline ordinary photos within preview/codec limits.
- Since 0.8.7: choose destination before manual Download, stream directly to one receiver copy, open the saved file, and resume at 256 KiB boundaries.
- Fast file payload is plaintext on a separate TCP data connection. Authorization and device verification remain on TLS control; ordinary file payloads use TLS.
- Since 0.8.8: reduced photo/card repaint artifacts during scrolling and chat switching. Unchanged cards stay mounted during status updates.
- Background receipt while hidden in the Windows tray; Exit stops the process. Data is at `%LOCALAPPDATA%/LanMessenger`.
- Since 0.8.9: opening a conversation shows the newest 10 messages; scrolling to the top loads 20 older per step; switching away and back preserves visible count, loaded history, and scroll position. A 16 MiB LRU reuses decoded attachment thumbnails across chats (non-image files are skipped without a read), and live cards for up to 3 recent chats are retained and reattached. See [PLAN-CHAT-PERFORMANCE.md](PLAN-CHAT-PERFORMANCE.md).

## Not mirrored from Android

- No attachment-thumbnail LRU cache across chats — resolved in 0.8.9 (16 MiB `ThumbnailCache`, reference counted so eviction never disposes an image a live or cached card still displays).
- Android's daily upload accounting, 30/20/10 MiB/s tiers, and profile usage display are absent by the earlier Android-only scope.
- No built-in camera capture; users can attach an already saved image.
- Legacy encrypted auto-image cache still uses FETCH and 100 MiB parts rather than Android FETCHSTREAM. The new manual direct path is separate.
- Engine message scanning in memory is not an index: `PeerEngine.Messages` scans engine-held records, and rendering the newest 10 does not make the query inspect only 10 records. Measured open times stay single-digit ms up to 112 messages, so no engine recent-message index was added in 0.8.9 (plan W07).

## Verification and open checks

- Mutable group membership foundation: build (0 warnings, 0 errors) and the full `tests/run.ps1` suite passed, including `tests/group_membership.py` (cross-platform) — convergence on add/remove/re-invite, a device offline through several changes catching up in one step, a group shrinking to just the owner and regrowing past the creation-only `<3` floor, a live (never-cached) capability check that a reachable-but-uncooperative or genuinely unreachable peer both fail identically, an already-confirmed member later going uncooperative blocking further growth until they cooperate again, leaving never gated by anyone's capability, migration of an old-shape saved group on both platforms, and the 16-member cap enforced against the live roster (a real 16-real-process scenario, kept from the earlier join-request test coverage and re-pointed at direct `REINVITE` add instead of the now-removed accept-a-request path); plus `tests/group_migration_broadcast.py`, verifying the migration-triggered broadcast reaches a real, still-running second device with its own stale copy. No manual click-through of `ShowMembers`'s updated departed-member/Re-invite display was performed in this environment.
- 2.0.1 join-request removal + Leave-group relabel (list + in-chat) + Hide-groups toggle: build (0 warnings, 0 errors) and the full `tests/run.ps1` suite passed. Removing `AddMember`'s join-request-auto-clear hook and `DeleteAllData`'s join-request snapshot/clear/rollback fragments required surgical edits (not blanket deletion) to avoid disturbing the surrounding capability-check-then-mutate-then-save structure — verified byte-for-byte behaviorally unchanged by `group_membership.py`/`group_migration_broadcast.py` passing unmodified. Porting the 16-member capacity check out of the deleted `tests/join_requests.py` into `group_membership.py` initially dropped a `command_retry` wrapper the original had around the equivalent call (needed for transient contention under 17 real simultaneous processes) — found via a real failure, fixed by adding the same retry-tolerant wrapper back. This environment has a long-lived, unkillable zombie `dotnet` process from earlier in the session that intermittently causes transient TLS/timeout contention specifically under the heaviest 16-17-real-process scenario in a full-suite run (not in the code); every such failure was independently reproduced-and-confirmed-clean on an isolated rerun before being treated as environmental rather than a regression.
- 2.1.0 group ownership transfer: build (0 warnings, 0 errors) and the full `tests/run.ps1` suite passed, including the new `tests/ownership_transfer.py` — a basic transfer converges everywhere (every member's own owner field updates, not just the new owner's) and the old owner's departure completes automatically once everyone has caught up, after which the new owner can grow the group; a transfer is refused outright (owner unchanged, no pending handoff, no version bump) if any member can't answer a fresh `CAPS>=2`; transferring to yourself or a non-member is refused; the pending handoff (and its durable `O` row) survives the old owner's own restart and still completes afterward; a transfer is refused while any member is still mid-onboarding and succeeds once they finish. Not built: a dedicated test for a still-lagging member's `LEAVE` arriving specifically during the pending window (the `HandleLeave` widening) — reliably forcing that exact race needs an artificial mid-flight pause this harness's process-level start/stop granularity can't provide; the surrounding convergence scenarios already exercise the same code path indirectly. No manual click-through of the new "Choose a new admin" picker or "Leaving — waiting for…" state was performed in this environment.
- 0.8.12 (forget-notice / group re-invite): build (0 warnings, 0 errors) and the full `tests/run.ps1` suite passed, including the extended `tests/delete_conversation.py` — contact delete now also asserts the other side's own verification is auto-revoked once the `FORGET` notice reaches them (`VERIFIED` harness command); the group scenario has a non-owner member leave, the owner sees them in `Left` (`LEFT` harness command), re-invites them (`REINVITE` harness command), and confirms they rejoin and receive new group messages again. No manual click-through of `ShowMembers`' new "Re-invite" button was performed in this environment — only the engine methods and their call sites were exercised by the automated suite and a clean build.
- Windows 0.8.11 built (0 warnings, 0 errors) and passed the full test suite (`tests/run.ps1`): all prior C#/Java engine, integration, transfer and native UI coverage, plus the new `tests/delete_conversation.py` (peer forgotten and rediscovered as unverified, group left without disrupting other members, Delete app data wipes conversations/contacts/groups while keeping identity/name/avatar and resetting Android's daily upload usage). No manual click-through of the new Windows context menu / toolbar button was performed in this environment — only the engine methods and their call sites were exercised by the automated suite and a clean build.
- Windows 0.8.10 built and passed native UI tests, including the 0.8.9 coverage: 640x480 colored image; repeated wheel/programmatic scrolling, chat switching, and geometry checks; automatic images; destination cancel/open; notifications; seen receipts; plus the new paging/cache tests T01 (newest-10 open, +20 pages with preserved anchor, full-history load, arrivals, chat-switch card/scroll preservation) and T02 (12-image cache bounds, clear safety, white-box thumbnail hits/retirements/byte release). Final screenshots inspected.
- Before/after measurements via tests/MeasureWindows (loopback): large-chat paint 300→55 ms, cards built 112→10; medium paint 115→60 ms with 10 cards; first-open/revisit single-digit ms before and after (large revisit 13→3 ms). Repeat opens of the 112-message chat: 2-4 ms. Detailed record in outputs: `windows-chatperf-measure/baseline-0.8.8.txt`, `after-final.txt`, `comparison-0.8.8-vs-0.8.9.txt`.
- Windows 0.8.8 UI tests results are kept in `outputs/.build/windows-ui-tests`. The 0.8.8 repaint-fix confirmation on the affected PC/DPI setup is still the user's to confirm.
- Loopback measurements are not Wi-Fi guarantees; large-chat opening and real LAN throughput on actual hardware remain worth a check.

## Handoff pointers

- Voice Messages (Phase 2): `VoiceMessages.cs` (transport-independent PCM/WAV/marker/seek core), `VoiceDrafts.cs` (`partial class PeerEngine` draft registry + `SendVoiceDraft`), `VoiceRecorder.cs`/`VoicePlayer.cs` (`waveIn`/`waveOut` P/Invoke adapters), `ChatWindowVoice.cs` (own-draft record/send UI), `ChatWindowVoiceCard.cs` (receiver cards), `ChatWindowVoicePlayback.cs` (shared one-active-player controller), `Transfers.cs` (`QueueAutomaticMedia`, the renamed/extended scheduler). Tests: `tests/CsharpHarness/VoiceMessagesCheck.cs` (WT01), `tests/voice_architecture_check.py` (WT06). Progress tracker: [PLAN-VOICE-MESSAGES-WINDOWS.md](../PLAN-VOICE-MESSAGES-WINDOWS.md).
- UI: `ChatWindowRender.cs`/`ChatWindowMessages.cs`/`ChatWindowAttachments.cs`/`ChatWindowDialogs.cs`/`ChatControls.cs` (split out of `Program.cs`; `Program.cs` now holds only `Program.Main`, `ChatWindow`'s fields, constructor and `Send`). Delete conversation ("Leave group" for a group, now also intercepted by the ownership-transfer picker)/app data: `ChatWindowDialogs.cs` (`DeleteConversationConfirm`, `ShowTransferOwnershipPicker`, `DeleteAllDataConfirm`), engine side in `Conversations.cs` (`DeleteConversation`, `DeleteAllData`), context-menu label wiring in `Program.cs`.
- Forget-notice / group re-invite: `Conversations.cs` (`forgotten`/`pendingLeaves`, `HandleLeave`, `ReinviteMember`), `Storage.cs` (`F`/`L` rows), `PeerEngine.cs` (`Forgotten` event, `Deliver()`'s `FORGET`/`LEAVE` sending, `Receive()`'s `FORGET`/`LEAVE` dispatch), `Program.cs` (`engine.Forgotten` tray-notice wiring), `ChatWindowDialogs.cs` (`ShowMembers`'s departed-member rows and Re-invite button).
- Mutable group membership foundation: `Conversations.cs` (`Group.MembersVersion`, `departedHistory`, `memberAcked`, `AllKnownMembers`, `AcceptGroup`, `HandleMembersUpdate`, `QueryCapability`, `AddMember`, `MemberAckedVersion`, `SimulateLegacyBuild` test-only field), `Storage.cs` (`G` row's version field + migration, `D`/`V` rows; `J`/`Q` rows from the removed join-request feature are tolerated but ignored on load), `PeerEngine.cs` (`Receive()`'s `CAPS`/`MEMBERSUPDATE` dispatch, `Deliver()`'s versioned group-broadcast loop), `ChatWindowDialogs.cs` (`ShowMembers`'s sync-status rows). Plan: `PLAN-GROUP-MEMBERSHIP.md`.
- Group ownership transfer: `Conversations.cs` (`Group.EverTransferredOwnership`, `pendingOwnershipHandoff`/`PendingOwnershipHandoff`, `TransferOwnership`, `CheckOwnershipHandoffConvergence`, `MemberAckedVersionLocked`, the widened checks in `HandleLeave`/`AddMember`), `Storage.cs` (`T`/`O` rows), `PeerEngine.cs` (`CAPS` reply bump, `MEMBERSUPDATE` dispatch accepting 5-or-6 fields, `Deliver()`'s widened broadcast gate + owner field + convergence-check call), `ChatWindowRender.cs`/`ChatWindowDialogs.cs`/`Program.cs` (the "Choose a new admin" picker and "Leaving — waiting for…" state). Plan: `PLAN-GROUP-OWNERSHIP-TRANSFER.md`.
- New manual transfer: `DirectDownloads.cs`. Legacy/auto-photo transfer: `Transfers.cs`. Storage/avatars: `Storage.cs`/`Avatars.cs` (split out of `PeerEngine.cs`).
- Tests from repository root: `tests/WindowsUi` (includes paging T01 and cache T02), `tests/MeasureWindows` (chat-performance harness), `tests/direct_downloads.py`, `tests/transfers.py`, `tests/large_transfer.py`, `tests/delete_conversation.py`, `tests/group_membership.py`, `tests/group_migration_broadcast.py`, `tests/ownership_transfer.py` (all cross-platform).
- Results: `D:/LAN-Messenger/outputs/.build/windows-0810-ui`, earlier 0.8.9 results in `windows-ui-tests`, and `windows-chatperf-measure`. Package: `D:/LAN-Messenger/outputs/LanMessenger-Windows-2.1.0.zip` (requires .NET 9 Desktop Runtime).
- Windows 0.8.9 code commit: `2241347`; 0.8.10 hotfix, the 0.8.11 file-split/delete-conversation work, the 0.8.12 forget-notice/group-re-invite work, the 2.0.0 group-membership foundation + (later removed) join-request layer, the 2.0.1 join-request removal + Leave-group relabel, and the 2.1.0 group ownership-transfer feature committed locally on `master`, not pushed.

## Next planned work

- Confirm the 0.8.8/0.8.9 rendering and paging behavior on the affected PC/DPI setup.
- Optional: an engine recent-message index if real-device measurements later show scanning dominating open time (deferred by plan W07 with recorded evidence).
