# Windows plan: newest 10 messages, progressive history, and chat-switch cache

**Type:** plan only. **Prepared:** 2026-09-25. **Implementation scope:** Windows. **Overall status:** Complete. **Code baseline:** Windows 0.8.8 at commit `98198bc`. Implemented as Windows 0.8.9.

## Desired result

Opening a Windows conversation shows the newest 10 messages promptly. Scrolling to the top adds 20 older messages per step. Switching away and back preserves the visible count and scroll position. A bounded cache reuses decoded attachment thumbnails and recently displayed cards so switching does less disk reading, image decoding, and control construction.

## Current code observations

- `ChatWindow.RenderCore` in `windows/Program.cs` calls `engine.Messages(selected)`, builds a signature across every message, and constructs cards for the full selected conversation. On chat switch, `ClearFeed` disposes the cards.
- `cards` only retains the current chat. `avatarCache` is for profile avatars, not attachment thumbnails.
- `TryImageThumbnail(Message, ...)` reads and decodes available attachments again when their cards are rebuilt; it also tries small non-image files.
- `PeerEngine.Messages` in `windows/PeerEngine.cs` scans engine-held messages. Rendering 10 cards alone does not make the engine query inspect only 10 records.
- `BufferedFeed` and `MessageBubble` contain the 0.8.8 repaint fix. Scrolling and cache changes must preserve that behavior.

## Work items

| ID | Small task | Status | Depends on | Notes and completion criterion |
|---|---|---|---|---|
| W01 | Establish baseline | Complete | - | Built representative short/long/photo chats via the tests/MeasureWindows harness. Baseline on 0.8.8: small `3|0|56|5|0`, medium `5|4|115|46|6`, large `8|13|300|112|12` (first|revisit|paint|cards|images). Saved in outputs as baseline-0.8.8.txt. |
| W02 | Per-chat view state | Complete | W01 | `ChatViewState` (VisibleCount=10, ScrollY, AtBottom, FeedWidth, LastUse, detachable Cards/StatusLabels) in ChatWindow. Switching back preserves loaded history and scroll; ClearChat removes the affected state. Verified by T01/T02. |
| W03 | Render newest 10 | Complete | W02 | `RenderCore` renders only the newest `VisibleCount` slice (10 on first open). New messages appear without rebuilding hidden history; chronological order enforced. Verified by T01. |
| W04 | Load older messages | Complete | W03 | `BufferedFeed.TopReached` → `LoadOlderPage` adds 20 older messages per top arrival with a `loadingOlder` guard; the first visible message stays pinned (signed-offset anchor restores the pre-scroll relationship, including when the anchor card is clipped above the viewport). "Older messages" hint shown while more history exists. |
| W05 | Attachment thumbnail cache | Complete | W03 | `ThumbnailCache`: 16 MiB LRU keyed by conversation/sender/message/hash/preview size, reference counted so eviction never disposes an image a live or cached card still displays; non-image files skipped without a read attempt via `PeerEngine.IsImageAttachment`. Revisit and eviction byte accounting verified by T02 white-box checks. |
| W06 | Recent-chat card/scroll cache | Complete | W04, W05 | `StashFeed`/`DiscardFeed`/`EvictChatCache` keep live cards for up to `MaxCachedChats=3` chats / `MaxCachedCards=600` (LRU). Reattach validates width and `cachedAlive` (no disposed controls). Width/DPI invalidates cached cards. VisibleCount/scroll persist after eviction. |
| W07 | Review message-query cost | Complete | W03, W06 | Re-measured. Open of the 112-message chat was 8 ms on 0.8.8 and 2 ms after; engine scanning never dominated open time, so no recent-message index was added. Evidence recorded in outputs (comparison-0.8.8-vs-0.8.9.txt), consistent with the plan's decision rule. No wire-protocol change. |
| T01 | Functional UI tests | Complete | W03-W06 | tests/WindowsUi covers newest-10 open with hint, +20 page load with preserved anchor, full-history load without duplicates/loss, arrivals keep mid-history cards stable, chat switch preserves card count/instances and scroll position, clear safety and fresh-view re-pagination at 10. |
| T02 | Paint and memory tests | Complete | W05, W06 | 12-image chat switches stay within cache bounds and paint cleanly; thumbnail bytes under budget with 12 live images; white-box checks: repeat-key hits, 40 × 420×320 pressure retirements over the 16 MiB budget, releasing retired thumbnails frees bytes. |
| T03 | After-change measurement and regression | Complete | W07, T01, T02 | After (0.8.9): small `6|1|60|5|0`, medium `2|4|60|10|6`, large `2|3|55|10|10`; large repeats `2|4|3|4` ms at 10 cards. Paint is the payoff (large 300→55 ms); opens were already single-digit and stay single-digit. Windows build 0 warnings/0 errors; full UI suite (regression + T01 + T02) passes. |
| R01 | Windows release and status | Complete | T03 | windows/STATUS.md and PROJECT_STATUS.md updated with actuals; version bumped to 0.8.9 and ZIP/release notes packaged after checks passed. Committed locally; not pushed. |

## Initial implementation decisions

- Start with 10 visible messages and add 20 at a time, matching the requested behavior.
- Per-chat view state lasts for the app session. Thumbnail/card caches are bounded and evictable; no disk-storage migration is expected.
- Adapt the approach to WinForms rather than copying Android UI code. Keep the 0.8.8 repaint fix and unchanged-card updates.
- Design T01/T02 alongside W03/W05. A successful compile alone does not complete a task.
- Caching live WinForms controls risks stale parents, paint artifacts, or resource leaks. W06/T02 must demonstrate safe attach, detach, and eviction. UI pagination alone may leave engine scanning cost; W07 decides from measurements.

## Status log

| Date | Change | Status |
|---|---|---|
| 2026-09-25 | Planned after reading current Windows rendering and test paths. | All implementation and test tasks Not started. No product code changed and no implementation tests run. |
| 2026-09-25 | Implemented W02-W06, added T01/T02, re-measured. Baseline vs after recorded in the table above and in outputs (baseline-0.8.8.txt, after-final.txt, comparison-0.8.8-vs-0.8.9.txt). Full WindowsUi suite (regression + T01 + T02) passes on the 0.8.9 build; measurements show paint 300→55 ms on the large chat and single-digit open times throughout. W07 decided from measurements: no engine index needed. | Complete. Version bumped to 0.8.9, release packaged, status docs updated, committed locally (not pushed). |

0.8.10 follow-up: the newest-10 implementation needed two fixes after review. A chat first opened at five messages now grows to six on arrival rather than hiding message one; repeated empty-chat redraws no longer duplicate the hint. Both were covered by new native UI regressions and an independent six-message reproduction. The 0.8.9 work items remain complete; this paragraph records the subsequent hotfix.

When assigning work to another agent, specify item IDs from this table. Change status to In progress when starting and Complete only after the item's criterion is verified. Record measurements, test results, or blockers here.
