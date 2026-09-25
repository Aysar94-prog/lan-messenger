# Windows status

Reviewed 2026-09-25. Release: **0.8.10**. See the [platform comparison](../PROJECT_STATUS.md).

## Implemented

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

- Windows 0.8.10 built and passed native UI tests, including the 0.8.9 coverage: 640x480 colored image; repeated wheel/programmatic scrolling, chat switching, and geometry checks; automatic images; destination cancel/open; notifications; seen receipts; plus the new paging/cache tests T01 (newest-10 open, +20 pages with preserved anchor, full-history load, arrivals, chat-switch card/scroll preservation) and T02 (12-image cache bounds, clear safety, white-box thumbnail hits/retirements/byte release). Final screenshots inspected.
- Before/after measurements via tests/MeasureWindows (loopback): large-chat paint 300→55 ms, cards built 112→10; medium paint 115→60 ms with 10 cards; first-open/revisit single-digit ms before and after (large revisit 13→3 ms). Repeat opens of the 112-message chat: 2-4 ms. Detailed record in outputs: `windows-chatperf-measure/baseline-0.8.8.txt`, `after-final.txt`, `comparison-0.8.8-vs-0.8.9.txt`.
- Windows 0.8.8 UI tests results are kept in `outputs/.build/windows-ui-tests`. The 0.8.8 repaint-fix confirmation on the affected PC/DPI setup is still the user's to confirm.
- Loopback measurements are not Wi-Fi guarantees; large-chat opening and real LAN throughput on actual hardware remain worth a check.

## Handoff pointers

- UI: `Program.cs`, especially `BufferedFeed`, `MessageBubble`, `RenderCore`, `ChatViewState`, `ThumbnailCache`, `LoadOlderPage`, and `FileAction`.
- New manual transfer: `DirectDownloads.cs`. Legacy/auto-photo transfer: `Transfers.cs`.
- Tests from repository root: `tests/WindowsUi` (includes paging T01 and cache T02), `tests/MeasureWindows` (chat-performance harness), `tests/direct_downloads.py`, `tests/transfers.py`, `tests/large_transfer.py`.
- Results: `D:/LAN-Messenger/outputs/.build/windows-0810-ui`, earlier 0.8.9 results in `windows-ui-tests`, and `windows-chatperf-measure`. Package: `D:/LAN-Messenger/outputs/LanMessenger-Windows-0.8.10.zip` (requires .NET 9 Desktop Runtime).
- Windows 0.8.9 code commit: `2241347`; 0.8.10 hotfix committed locally on `master`, not pushed.

## Next planned work

- Confirm the 0.8.8/0.8.9 rendering and paging behavior on the affected PC/DPI setup.
- Optional: an engine recent-message index if real-device measurements later show scanning dominating open time (deferred by plan W07 with recorded evidence).
