# SubnetDesk session-only chat memory

2026-10-08. Both: Windows receiver CM and Android sender first; same shared Flutter lifecycle.
User previously requested no retained chat and explicitly approved execution of all requests.
Current reminder does not mean credentials/access methods were completed. No LAN changes.

| ID | Platform | Task | Status | Dependencies | Notes / acceptance / testing |
|---|---|---|---|---|---|
| CMEM-01 | Both | Trace message identity, reconnect, remote close and snapshots | Complete | None | MessageKey currently equates different incoming connIds for one peer; resetClientMode incorrectly indexes map with integer-1. close hides overlay without erasing history. |
| CMEM-02 | Both | Session-specific message identity and logical memory cleanup | Implemented; automated checks pass | 01 | Exact peer+connId keys; ended lists cleared before dropping references, including held UI lists. Selected draft/selection/latest reference cleared. No disk persistence. |
| CMEM-03 | Both | Wire disconnect/error/reconnect/snapshot/dispose hooks | Implemented; physical acceptance pending | 02 | Incoming remove/disconnected-card and authoritative snapshot cleanup; outgoing connection error/restart/manual or automatic reconnect; close/dispose. Async receive checks live connection/generation after yields. Fold/tab alone do not clear. |
| CMEM-04 | Both | Tests, builds and actual device acceptance | In progress | 03 | Memory8/8 + ChatModel lifecycle8/8; full Flutter89/89 PASS. Final Windows frontend1.3.3+80 PASS149.0s; verified native DLL reused, no wire changes. Android APK80 build in progress. Neither runtime updated; physical reconnect/remote-close/network-loss acceptance pending. |

Scope is removal from application-owned state and dropping references for garbage collection,
not a forensic guarantee of overwriting immutable Dart strings/OS memory. No chat content in
logs, no persistence/export added, no removal of app files or other application chat data.
Keep credential store independent: clearing chat must not erase deliberately saved passwords.
Fresh versions/packages remain separate from currently running build79 until verified.

Verification logs in outputs/SubnetDesk-helper-cm/checks:
chat-memory-windows-build80-final.log and chat-memory-android-build80.log.
This task does not implement password storage, temporary codes or manual approval.
Those approved tasks remain open in their separate plans. No all-requests completion claim.
