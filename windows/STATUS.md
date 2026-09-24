# Windows status

Reviewed 2026-09-25. Release: **0.8.8**. See the [platform comparison](../PROJECT_STATUS.md).

## Implemented

- Messaging, groups, device verification, blue/gray presence, attachment draft before Send, and local chat clear.
- Automatic inline ordinary photos within preview/codec limits.
- Since 0.8.7: choose destination before manual Download, stream directly to one receiver copy, open the saved file, and resume at 256 KiB boundaries.
- Fast file payload is plaintext on a separate TCP data connection. Authorization and device verification remain on TLS control; ordinary file payloads use TLS.
- Since 0.8.8: reduced photo/card repaint artifacts during scrolling and chat switching. Unchanged cards stay mounted during status updates.
- Background receipt while hidden in the Windows tray; Exit stops the process. Data is at `%LOCALAPPDATA%/LanMessenger`.

## Not mirrored from Android

- Newest-10-message initial UI and progressive older-message loading are not implemented. `RenderCore` constructs cards for the full selected conversation.
- No attachment-thumbnail LRU cache across chats. `avatarCache` stores avatars only; `cards` represents the current chat and is cleared on switch.
- `TryImageThumbnail(Message, ...)` still attempts content read/decode for a small non-image file and ignores decode failure.
- Android's daily upload accounting, 30/20/10 MiB/s tiers, and profile usage display are absent by the earlier Android-only scope.
- No built-in camera capture; users can attach an already saved image.
- Legacy encrypted auto-image cache still uses FETCH and 100 MiB parts rather than Android FETCHSTREAM. The new manual direct path is separate.
- Complete UI trees for multiple conversations are not cached. Engine message records in memory do not mean the UI is cached.

## Verification and open checks

- Windows 0.8.8 built and passed native UI tests with a 640x480 colored image, repeated wheel/programmatic scrolling, chat switching, geometry checks, automatic images, destination cancel/open, notifications, and seen receipts. The result screenshot was inspected.
- The user still needs to confirm the repaint fix on the affected PC and monitor/DPI setup.
- Large-chat opening latency and real LAN throughput need measurement; loopback results are not Wi-Fi guarantees.

## Handoff pointers

- UI: `Program.cs`, especially `BufferedFeed`, `MessageBubble`, `RenderCore`, and `FileAction`.
- New manual transfer: `DirectDownloads.cs`. Legacy/auto-photo transfer: `Transfers.cs`.
- Tests from repository root: `tests/WindowsUi`, `tests/direct_downloads.py`, `tests/transfers.py`, `tests/large_transfer.py`.
- Results: `D:/LAN-Messenger/outputs/.build/windows088-tests`. Package: `D:/LAN-Messenger/outputs/LanMessenger-Windows-0.8.8.zip` (requires .NET 9 Desktop Runtime).
- Last Windows code commit: `07f2e54`; preceding shared-transfer commit: `7d9d99c`. Both are local.

## Next planned work

[Windows chat pagination and cache plan](PLAN-CHAT-PERFORMANCE.md). Every implementation and test task is **Not started**. The plan does not mean the feature has been implemented.
