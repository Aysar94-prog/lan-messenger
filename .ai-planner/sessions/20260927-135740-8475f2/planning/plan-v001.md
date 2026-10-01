# plan-v001 | version=1 | Add true online/offline status toggle

## Scope and interpretation

- **Platform:** Both Windows and Android.
- “Offline” means the app remains usable for local history and composing queued messages, but performs no LAN communication:
  - Close the fixed TCP message listener.
  - Close the UDP discovery socket and stop announcements.
  - Stop outbound delivery, probes, capability checks, avatar synchronization, and automatic downloads.
  - Cancel/close active control, download, upload, and temporary direct-transfer sockets.
  - Release Android’s multicast lock while offline.
- Other devices will age this device out through the existing presence timeout; no new wire message or protocol version is required.
- Going online reopens networking, announces immediately, and resumes queued work.
- Default existing-user behavior remains **Online**. The selected state persists across normal restarts.
- This is different from Android’s existing display-only “Show offline users” preference and must be labeled clearly.
- Android’s current notification “Go offline” action should enter the same persistent offline state instead of destroying access to the locally loaded engine.

## Important design constraint

`PeerEngine.Dispose()` on Windows and `PeerEngine.close()` on Android are terminal: they cancel tokens or shut down executors and cannot safely restart. Implement a reusable **network session lifecycle** that can be stopped and started while the identity, conversations, groups, queues, and storage-backed engine remain loaded. Final application shutdown must still use the existing terminal disposal path.

## Task plan

| ID | Platform | Task | Status | Dependencies | Notes | Acceptance criteria |
|---|---|---|---|---|---|---|
| P01 | Both | Confirm the shared behavior contract in code-facing documentation/tests | Planned | None | Define Online, transition states, Offline, and final shutdown. Explicitly distinguish “offline” from hiding offline contacts and from exiting the app. | Both implementations and tests use the same behavioral definition; no protocol change is introduced. |
| E01 | Windows | Separate reusable network-session cancellation from terminal engine disposal | Planned | P01 | Replace the one-shot networking cancellation model with per-session cancellation/resources. Preserve loaded state and long-lived engine callbacks across transitions. Make start/stop idempotent and synchronized. | `GoOffline` closes network resources without destroying local state; `GoOnline` can restart the same engine repeatedly; final `Dispose` remains terminal. |
| E02 | Android | Separate reusable network-session shutdown from terminal engine close | Planned | P01 | Keep the `PeerEngine` object loaded while offline. Recreate session executors/timers or otherwise make only network workers restartable. Keep storage and callbacks available to the activity. | The same engine can transition online → offline → online repeatedly without executor rejection, duplicate timers, or loss of local data. |
| E03 | Both | Enforce a strict offline network boundary | Planned | E01, E02 | Gate all inbound and outbound network entry points on session state. Track and close accepted sockets, outbound sockets, active download/upload sockets, and temporary direct-transfer listeners. Reject new Add-by-IP, capability-query, verification-network, and transfer work while offline with clear errors. | After transition completion, the process has no LAN Messenger listening socket, sends no discovery packets, opens no peer connection, and leaves no transfer socket active. |
| E04 | Both | Preserve local offline use and queued messaging | Planned | E01, E02 | Keep contacts, groups, messages, drafts, attachments, unread state, and queue APIs available. Text and attachment preparation should still be stored locally where current storage semantics permit; delivery waits for Online. | A message created while offline is visible as queued and is delivered after reconnecting; existing history remains readable throughout. |
| E05 | Both | Implement safe transition behavior | Planned | E03, E04 | Expose a single state API such as `Online`, `GoingOffline`, `Offline`, `GoingOnline`, with change notification. Serialize rapid toggles. Ensure stale workers from an older session cannot update or reopen a newer session. | Repeated or rapid toggles do not throw, bind duplicate ports, leak workers, duplicate delivery, or leave UI state inconsistent. |
| U01 | Windows | Add the Online/Offline toggle and state feedback | Planned | E01, E05 | Add a clearly labeled status control in the main toolbar/status area and equivalent tray access. Disable network-only actions while offline but retain local chat/composer actions. Show transition or bind failure text without falsely reporting Online. | The toggle reflects actual engine state; Offline remains usable locally; retrying Online after a bind failure works; tray and window controls stay synchronized. |
| U02 | Android | Add the Online/Offline toggle and state feedback | Planned | E02, E05 | Add “Online” to the People-screen menu near the existing display filters. Keep the foreground service/engine available in Offline mode so chats remain accessible. Update the persistent notification text and action to use the same toggle path. | Menu, screen status, and notification agree; tapping either control changes the same state; offline mode does not make local content disappear. |
| S01 | Windows | Persist the preferred state | Planned | U01 | Store only the user preference, not transient transition/failure state. Read it before initial networking starts. Existing installations default Online. | Offline survives an ordinary app restart; first run and upgraded installations start Online unless the user previously selected Offline. |
| S02 | Android | Persist the preferred state and adjust service startup | Planned | U02 | Store the preference separately from `show_offline_users` and `hide_groups`. The service should load the engine in either state, acquire the multicast lock only when Online, and follow Android foreground-service rules. | Restarting the activity/service preserves the choice; Offline startup opens no LAN sockets and holds no multicast lock. |
| D01 | Both | Define behavior for in-progress transfers | Planned | E03 | On Going Offline, close active transfer connections/listeners and leave resumable downloads paused with their valid partial state. Ordinary queued sends remain queued. Provide a concise UI indication when work is paused. | No transfer continues after Offline completes; resumable work continues correctly after Online; partial files are neither falsely marked complete nor discarded. |
| T01 | Both | Add engine lifecycle unit/integration tests | Planned | E01–E05 | Extend the C# and Java harnesses for initial online, offline port closure, restart, idempotence, rapid toggles, and terminal shutdown. Use isolated test ports. | Tests prove the TCP port cannot be connected to and discovery is not emitted while offline, then prove both resume after reconnect. |
| T02 | Both | Add cross-platform discovery and delivery tests | Planned | E03–E05 | Exercise Windows↔Android combinations: one side offline, both offline, reconnect, queued direct message, queued group message, and presence expiry/rediscovery. | An offline peer is not newly discoverable or reachable; the other peer eventually shows it offline; queued work delivers once after reconnect with no protocol regression. |
| T03 | Both | Add transfer interruption tests | Planned | D01 | Cover ordinary attachment, resumable download, and Fast/direct transfer while toggling Offline mid-operation. | Ports and sockets close promptly, state remains valid, and supported transfers resume or retry cleanly after Online. |
| T04 | Windows | Add UI automation coverage | Planned | U01, S01 | Verify toolbar/tray synchronization, disabled network-only actions, locally queued message creation, persistence, and recovery from a simulated bind failure. | Automated Windows UI suite passes and reports actual state accurately. |
| T05 | Android | Add UI/service tests and physical-device acceptance checklist | Planned | U02, S02 | Test menu/notification actions, process/service restart, multicast-lock handling, offline background behavior, and queued messaging. Physical acceptance should include Wi‑Fi discovery from another device. | Automated tests pass; physical-device results are recorded separately from automated verification. |
| R01 | Both | Update status and compatibility records after implementation | Planned | T01–T05 | Update `windows/STATUS.md`, `android/STATUS.md`, and `PROJECT_STATUS.md`. Separate implemented code, automated results, and real-device acceptance. Record that the wire protocol is unchanged. | Status documents accurately describe both platforms, verification performed, remaining manual checks, and compatibility impact. |

## Testing matrix

| Scenario | Expected result |
|---|---|
| Online → Offline | Fixed TCP and UDP ports close; announcements, probes, delivery, and transfers stop. |
| Offline local use | History, contacts, groups, drafts, and queued message creation remain available. |
| Offline peer observation | Existing remote peers change this device to offline through the normal timeout; new peers cannot discover or connect to it. |
| Offline → Online | Ports bind, Android reacquires its multicast lock, discovery announces immediately, and queued work resumes. |
| Restart while Offline | App loads local data but opens no LAN sockets until explicitly switched Online. |
| Rapid repeated toggles | Final requested state wins; there are no duplicate workers, duplicate sends, crashes, or leaked sockets. |
| Port bind failure | UI remains Offline and shows a useful error; later retry can succeed. |
| Active transfer interrupted | Network activity stops; resumable state remains valid; no incomplete file is marked complete. |
| Final app exit | All resources are terminally disposed regardless of the selected preference. |
| Cross-version peer | No new frames are required; older peers simply observe normal disappearance and rediscovery. |

## Risks and implementation notes

- Presence cannot disappear instantly on remote devices without a new signed/protocol-level departure signal. Closing ports and ceasing discovery satisfies the requested network invisibility; remote UI follows its existing timeout.
- Merely suppressing UDP announcements is insufficient because Add-by-IP and remembered endpoints could still connect. The TCP listener and every outbound path must also be stopped.
- Merely stopping the Android service would remove the loaded engine and currently prevents useful offline queueing. The service should own a loaded-but-network-disabled engine instead.
- Active Fast/direct transfers create temporary listeners, so the offline transition must wait for their closure before reporting Offline.
- Network state changes must not rewrite identity, verification, conversation, group, or attachment records.
- Release building, version changes, and commits are outside this planning round and require explicit execution authorization.

## Completion definition

The feature is complete when both apps expose one persistent Online/Offline control, Offline closes all LAN Messenger network activity while preserving local use and queueing, Online reliably restores discovery and delivery, automated lifecycle/interoperability/transfer tests pass, physical-device acceptance is recorded where available, and all three status records are updated.
