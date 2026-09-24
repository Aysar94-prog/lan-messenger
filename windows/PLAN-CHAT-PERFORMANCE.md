# Windows plan: newest 10 messages, progressive history, and chat-switch cache

**Type:** plan only. **Prepared:** 2026-09-25. **Implementation scope:** Windows. **Overall status:** Not started. **Code baseline:** Windows 0.8.8 at commit `98198bc`. No product code was changed for this plan.

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
| W01 | Establish baseline | Not started | - | Build representative short/long/photo chats. Measure first-open and revisit latency, number of cards built, and attachment decode counts before changing code. |
| W02 | Per-chat view state | Not started | W01 | Store VisibleCount (initially 10), scroll position, and last use per conversation in ChatWindow. Switching back preserves loaded history. Clear/expiry removes affected state. |
| W03 | Render newest 10 | Not started | W02 | Build only the newest 10 cards on first open; preserve chronological/group/status behavior. New messages appear without rebuilding hidden history. |
| W04 | Load older messages | Not started | W03 | Add 20 older cards on reaching the top; preserve the first visible message/scroll anchor so the view does not jump. One top arrival must not repeatedly load pages. Show that older history is available. |
| W05 | Attachment thumbnail cache | Not started | W03 | Add a bounded LRU, initially 16 MiB, keyed by sender/message/hash/preview dimensions. Skip non-image files. Dispose Bitmaps safely on eviction/clear without disposing an image still displayed by a card. Preserve preview caps. |
| W06 | Recent-chat card/scroll cache | Not started | W04, W05 | Retain a bounded set of cards for the latest 2-3 chats, with an explicit card/memory cap. Revisit reuses valid cards and updates changed status/progress. Width/DPI changes reflow or invalidate cards; eviction disposes them. Long history may rebuild after eviction while VisibleCount/scroll state persists. |
| W07 | Review message-query cost | Not started | W03, W06 | Re-measure. If PeerEngine.Messages or LastActivity scanning dominates open time, add an appropriate recent-message API/index on Windows, retaining full-history access. Record evidence and outcome. No wire-protocol change. |
| T01 | Functional UI tests | Not started | W03-W06 | In tests/WindowsUi cover 0/1/10/11/many messages, older-page loading, arrivals without duplicates/loss, chat-switch state, clear/expiry, and image/file actions with realistic images. |
| T02 | Paint and memory tests | Not started | W05, W06 | Repeat wheel/programmatic scroll, chat switches, and resize. Check card geometry/white overlays and release of Bitmap/card resources after eviction. Measure against cache bounds. |
| T03 | After-change measurement and regression | Not started | W07, T01, T02 | Compare first-open and revisit measurements with W01. Run Windows build, UI tests, and relevant shared message/image regressions. Verify file progress, notifications, and read receipts. Report numbers only when measured. |
| R01 | Windows release and status | Not started | T03 | Update windows/STATUS.md and PROJECT_STATUS.md with actual results. Bump Windows version only and package ZIP/release notes after checks pass. Commit locally unless asked to push. |

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

When assigning work to another agent, specify item IDs from this table. Change status to In progress when starting and Complete only after the item's criterion is verified. Record measurements, test results, or blockers here.
