# Windows status

Reviewed 2026-09-26. Release: **0.8.11**. See the [platform comparison](../PROJECT_STATUS.md).

## Implemented

- Unreleased, in source on top of 0.8.11: deleting a contact now also notifies them. `DeleteConversation`/`DeleteAllData` queue a peer id in a persisted `forgotten` set; `Deliver()` sends them a new `LM4\tFORGET` frame (retried until acked, same shape as `SEEN`/`SEENACK`) whenever that peer is next reachable, which calls `Revoke` on their side and raises a new `Forgotten` event — wired in `Program.cs` to a tray notice next to the existing `Received` wiring. Deleting a group is still a leave (unaffected for other members), but it now queues a `LM4\tLEAVE` frame to the group's owner (persisted `pendingLeaves`, keyed by the owner id captured before the local group record is dropped); the owner records the departed member in a new `Group.Left` field (a 7th, optional, backward-compatible `G` storage field) and `Deliver()`'s invite-resend loop skips anyone in `Left`. `ShowMembers` (`ChatWindowDialogs.cs`) now shows departed members distinctly with an owner-only "Re-invite" button, calling the new `ReinviteMember(groupId, memberId)`, which clears `Acknowledged`/`Left` for that id so the next delivery cycle resends the `GROUP` invite and they rejoin with the same member list — no re-verification needed. An old build that doesn't understand `FORGET`/`LEAVE` simply never acks; the sender keeps retrying rather than erroring.
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

- Forget-notice / group re-invite (unreleased): build (0 warnings, 0 errors) and the full `tests/run.ps1` suite passed, including the extended `tests/delete_conversation.py` — contact delete now also asserts the other side's own verification is auto-revoked once the `FORGET` notice reaches them (`VERIFIED` harness command); the group scenario has a non-owner member leave, the owner sees them in `Left` (`LEFT` harness command), re-invites them (`REINVITE` harness command), and confirms they rejoin and receive new group messages again. No manual click-through of `ShowMembers`' new "Re-invite" button was performed in this environment — only the engine methods and their call sites were exercised by the automated suite and a clean build.
- Windows 0.8.11 built (0 warnings, 0 errors) and passed the full test suite (`tests/run.ps1`): all prior C#/Java engine, integration, transfer and native UI coverage, plus the new `tests/delete_conversation.py` (peer forgotten and rediscovered as unverified, group left without disrupting other members, Delete app data wipes conversations/contacts/groups while keeping identity/name/avatar and resetting Android's daily upload usage). No manual click-through of the new Windows context menu / toolbar button was performed in this environment — only the engine methods and their call sites were exercised by the automated suite and a clean build.
- Windows 0.8.10 built and passed native UI tests, including the 0.8.9 coverage: 640x480 colored image; repeated wheel/programmatic scrolling, chat switching, and geometry checks; automatic images; destination cancel/open; notifications; seen receipts; plus the new paging/cache tests T01 (newest-10 open, +20 pages with preserved anchor, full-history load, arrivals, chat-switch card/scroll preservation) and T02 (12-image cache bounds, clear safety, white-box thumbnail hits/retirements/byte release). Final screenshots inspected.
- Before/after measurements via tests/MeasureWindows (loopback): large-chat paint 300→55 ms, cards built 112→10; medium paint 115→60 ms with 10 cards; first-open/revisit single-digit ms before and after (large revisit 13→3 ms). Repeat opens of the 112-message chat: 2-4 ms. Detailed record in outputs: `windows-chatperf-measure/baseline-0.8.8.txt`, `after-final.txt`, `comparison-0.8.8-vs-0.8.9.txt`.
- Windows 0.8.8 UI tests results are kept in `outputs/.build/windows-ui-tests`. The 0.8.8 repaint-fix confirmation on the affected PC/DPI setup is still the user's to confirm.
- Loopback measurements are not Wi-Fi guarantees; large-chat opening and real LAN throughput on actual hardware remain worth a check.

## Handoff pointers

- UI: `ChatWindowRender.cs`/`ChatWindowMessages.cs`/`ChatWindowAttachments.cs`/`ChatWindowDialogs.cs`/`ChatControls.cs` (split out of `Program.cs`; `Program.cs` now holds only `Program.Main`, `ChatWindow`'s fields, constructor and `Send`). Delete conversation/app data: `ChatWindowDialogs.cs` (`DeleteConversationConfirm`, `DeleteAllDataConfirm`), engine side in `Conversations.cs` (`DeleteConversation`, `DeleteAllData`).
- Forget-notice / group re-invite: `Conversations.cs` (`Group.Left`, `forgotten`/`pendingLeaves`, `HandleLeave`, `ReinviteMember`), `Storage.cs` (`F`/`L` rows, `G` row's optional 7th field), `PeerEngine.cs` (`Forgotten` event, `Deliver()`'s `FORGET`/`LEAVE` sending and the `Left`-skip guard, `Receive()`'s `FORGET`/`LEAVE` dispatch), `Program.cs` (`engine.Forgotten` tray-notice wiring), `ChatWindowDialogs.cs` (`ShowMembers`'s departed-member rows and Re-invite button).
- New manual transfer: `DirectDownloads.cs`. Legacy/auto-photo transfer: `Transfers.cs`. Storage/avatars: `Storage.cs`/`Avatars.cs` (split out of `PeerEngine.cs`).
- Tests from repository root: `tests/WindowsUi` (includes paging T01 and cache T02), `tests/MeasureWindows` (chat-performance harness), `tests/direct_downloads.py`, `tests/transfers.py`, `tests/large_transfer.py`, `tests/delete_conversation.py` (new, cross-platform).
- Results: `D:/LAN-Messenger/outputs/.build/windows-0810-ui`, earlier 0.8.9 results in `windows-ui-tests`, and `windows-chatperf-measure`. Package: `D:/LAN-Messenger/outputs/LanMessenger-Windows-0.8.11.zip` (requires .NET 9 Desktop Runtime).
- Windows 0.8.9 code commit: `2241347`; 0.8.10 hotfix and the 0.8.11 file-split/delete-conversation work committed locally on `master`, not pushed.

## Next planned work

- Confirm the 0.8.8/0.8.9 rendering and paging behavior on the affected PC/DPI setup.
- Optional: an engine recent-message index if real-device measurements later show scanning dominating open time (deferred by plan W07 with recorded evidence).
