# SubnetDesk continuation and LAN Messenger integration plan

Date: 2026-10-07

## Authorized temporary backend handoff (2026-10-08)

User explicitly approved stopping the old backend on this computer (Wi-Fi 192.168.1.12),
then starting the candidate. This is not authorization to replace installed files, reconfigure
the service, deploy to 192.168.1.61, change Android, or publish a release.

| ID | Platform | Task | Status | Dependencies | Acceptance / testing notes |
|---|---|---|---|---|---|
| SD-HANDOFF-01 | Windows | Snapshot old service, processes and candidate paths | Complete | User approval | Original service Running/Auto, PID7604; old server PID3808 owns TCP21118; old CM PID9592 uses --cm-no-ui. Candidate main PID9704. |
| SD-HANDOFF-02 | Windows | Temporarily stop old service and remaining validated old processes | Complete | SD-HANDOFF-01; administrative service rights | Normal stop lacked rights; elevated, path-validated helper succeeded. Service Stopped/Auto; old server, headless CM and tray gone. No deletion, binary replacement, startup-mode or config change. Rollback: stop candidate and Start-Service SubnetDesk. |
| SD-HANDOFF-03 | Windows | Restart candidate and verify listener ownership | Complete | SD-HANDOFF-02 | Candidate PID1896 owns TCP21118 and has a native main-window handle. No installed-path SubnetDesk process remains. This is not a dock/session test. |
| SD-HANDOFF-04 | Windows | Incoming Android session and dock acceptance | Pending | SD-HANDOFF-03; user reconnects phone | Candidate --cm, visible native panel lasting >3 seconds, fold/unfold and chat separately accepted. If no window appears, retain failure evidence. |

Earlier user attempts did not show the requested dock. Local inspection found old service/backend
still active alongside the new UI, with a new connection in old cm-no-ui logs. This is failed
user-visible acceptance, not a successful test of the candidate's incoming-session backend.

Handoff completed approximately 09:32 Asia/Jerusalem. User requested to reconnect the phone to
192.168.1.12 for actual incoming-session acceptance. The service retains Automatic startup and
the original installed path, so Windows restart may bring the old backend back. No installed
release/version change was made; candidate still reports 1.3.0+75. Diagnostic transcript:
`D:\LAN-Messenger\outputs\SubnetDesk-helper-cm\checks\stop-old-backend.log`.

Scope status: the user explicitly authorized execution on 2026-10-07 and then specified a
TeamViewer-style foldable dock at the bottom of the Windows desktop, containing chat. SubnetDesk
source changes and full Windows candidate builds are complete (2026-10-08); graphical/paired
acceptance is pending. LAN Messenger application code is unchanged.

## Inputs and boundaries

- User clarification: SubnetDesk will remain a separate helper application. Repair that application
  first, then consider launching/connecting it from LAN Messenger. Copying its engine into LAN
  Messenger is not the current requested scope.
- SubnetDesk upstream: `zibo-chen/SubnetDesk`, release/tag `v1.3.0`; annotated tag object
  `1d3ac5ae36736e4f19f6a4bfc5fd2fe22a6f376d`, peeled source commit
  `a412726163265a0e18deb0b8244ac1e87fb6ffd5`.
- Reference copy: `D:\LAN-Messenger\reference\SubnetDesk-v1.3.0`.
- LAN Messenger canonical source remains `D:\LAN-Messenger\source`.
- SubnetDesk is AGPL-3.0. Any reuse, linking, distribution, or network deployment must be reviewed
  for source-offer, notice, and corresponding-source obligations before code is copied or combined.
- Do not assume Android/Windows parity. Classify each requested fix as Windows, Android, or Both.
- Do not change the LAN Messenger wire protocol unless both implementations are reviewed and the
  interoperability test plan is updated.

## Tasks

| ID | Platform | Task | Status | Dependencies | Notes |
|---|---|---|---|---|---|
| SD-01 | Windows | Run the unmodified portable v1.3.0 package and record the reported small bugs | Ready; waiting for user-described bugs | User observations | Preserve screenshots, exact steps, expected result, and actual result. |
| SD-02 | Both | Establish a writable Git working copy at the exact v1.3.0 tag and record upstream state | Complete | SD-01 | Separate helper checkout and pinned hbb_common submodule; LAN Messenger branch/remotes unchanged. |
| SD-03 | Windows | Trace the missing CM and requested foldable dock | Source tracing complete; physical reproduction pending | SD-02 | See SD-CM findings below. |
| SD-04 | Windows | Implement the agreed CM/dock fixes | Source implemented | SD-03; execution authorized | Followed SubnetDesk `AGENTS.md`; Android and wire protocol unchanged. |
| SD-05 | Windows | Build and run focused automated/smoke checks for the fixes | Builds/checks complete; physical acceptance pending | SD-04 | Rust policy 2/2; Flutter 53/53; native CLI startup 0/1.3.0. |
| INT-01 | Both | Compare SubnetDesk remote-control capabilities with LAN Messenger identity, trust, direct-IP, and permissions models | Planned | SD-01 | Produce a compatibility matrix; do not infer parity. |
| INT-02 | Both | Select an integration boundary: launch/deep-link, side-by-side IPC, shared identity, or code reuse | Planned | INT-01, licensing review, user decision | Prefer a narrow boundary that avoids coupling the two wire protocols. |
| INT-03 | Both | Write the shared protocol/security contract if integration requires new messages | Not started | INT-02 | Review both apps, authentication, certificate binding, authorization, replay protection, and failure behavior. |
| INT-04 | Both | Implement integration in small platform-scoped tasks | Blocked pending a second explicit execution approval | INT-02/INT-03 | Update affected platform status and the project comparison after changes. |

## Acceptance criteria

- Every reported SubnetDesk bug has deterministic reproduction steps and an expected result.
- Each fix passes its focused automated test or has an explicit reason why only manual acceptance is
  possible.
- Windows fixes are visually accepted on Windows; Android fixes are accepted on an Android device.
- No LAN Messenger behavior, protocol, signing material, or release output changes during the
  SubnetDesk-only repair stage.
- Any integration preserves LAN Messenger's certificate-bound trust and explicit permission model.
- Shared protocol work passes Windows/Android corpus and interoperability checks before parity is
  claimed.
- AGPL notices and corresponding-source obligations are documented before any combined release.

## Test tasks

1. Baseline: launch unmodified SubnetDesk v1.3.0, record version, architecture, settings, firewall
   prompt, discovery behavior, direct `IP:port` behavior, and logs.
2. Bug regression: add the smallest focused test available for each confirmed defect, then rerun the
   original reproduction steps.
3. Network/security: verify incorrect credentials, changed fingerprints, CIDR denial, offline peer,
   stale discovery, and port-conflict behavior.
4. Integration contract: verify identity mismatch, revoked permission, unavailable SubnetDesk,
   unsupported peer version, and clean fallback with no remote-control privilege escalation.
5. Physical acceptance: test the exact built artifacts on the affected Windows/Android devices;
   record acceptance separately from compilation and automated checks.

## Next decision

The first reported issue is the missing Windows Connection Manager during an Android-originated
session. Source review is recorded below. Application changes/builds were subsequently authorized
by the user's explicit execution request.

## SD-CM: visible Windows Connection Manager (2026-10-07)

Reported behavior: Android connects to Windows, the Windows tray reports `1 sessions`, but the user
cannot find the session's Chat/Voice/Disconnect interface. This is a user-reported reproduction;
no installed process, window, screenshot, or live session was inspected in this review.

Verified source findings at v1.3.0:

- `src/ipc.rs` answers `ControlledSessionCount` with `Connection::alive_conns().len()`. The tray
  consumes that value. A live backend connection does not prove a visible CM window exists.
- `src/server/connection.rs:4972` returns `--cm-no-ui` from `connection_manager_launch_args()`.
  Its existing `lan_connection_manager_is_always_headless` test explicitly pins this behavior.
- `src/core_main.rs:510` handles `--cm-no-ui` by starting the CM backend and returning before
  Flutter UI startup. This is the primary source-level explanation for an absent window in the
  ordinary incoming-session bootstrap path.
- `flutter/lib/main.dart` still supports `--cm` and the `DesktopServerPage` window. The existence
  of this path does not mean incoming sessions use it.
- `flutter/lib/models/server_model.dart:409` schedules a three-second minimize in `_addTab`,
  including when rebuilding existing client tabs. It affects CM UI when that UI actually runs.
  Pointer interaction in `desktop/pages/server_page.dart` can cancel the stored timer.
- `showCmWindow` also contains a minimize while restoring opacity, and the server model polls
  client state every 500 ms. Restoring a window must be checked together with these paths to
  preserve a user's manual minimize choice.

The proposed removal of the three-second timer is insufficient by itself. The concrete repair
target is Windows CM launch/presentation plus removal of involuntary minimize behavior.

| ID | Platform | Task | Status | Dependencies | Acceptance / notes |
|---|---|---|---|---|---|
| SD-CM-01 | Windows | Trace live count, launch arguments, UI timers and restoration | Source review complete; device reproduction pending | User report | Confirm actual installed version/process arguments when execution starts. |
| SD-CM-02 | Windows | Restore visible CM for an interactive Windows desktop | Source implemented | SD-CM-01 | Windows Flutter launches --cm; IPC reuse and prelogin/user-startup paths retained. Runtime/service acceptance pending. |
| SD-CM-03 | Windows | Remove three-second auto-minimize and review restored-window path | Source implemented | SD-CM-02 | Windows timer removed; periodic polls do not restore/focus a manually minimized window. |
| SD-CM-04 | Windows | Update launch-policy regression coverage and build the candidate | Policy 2/2; full builds pass | SD-CM-02, SD-CM-03 | Windows Flutter visible; legacy/other modes retain headless behavior. |
| SD-CM-05 | Windows | Accept against an unmodified Android peer | Pending user-approved backend handoff | SD-CM-04 | Old installed service still Running/Auto; do not interrupt sessions without direction. Check Chat, audible Voice, Disconnect, reconnect and service behavior. |

No new Chat/Voice implementation is assumed necessary: CM already has UI and handler paths for
these operations, but successful presentation alone does not prove their end-to-end operation.
No Android build or LAN Messenger protocol change is planned for this Windows repair.

## Execution checkpoint: bottom-right foldable Windows dock

The user specified a TeamViewer-style bottom-side foldable panel with chat after authorizing
execution. This replaces the earlier standalone CM-window presentation target.

| ID | Platform | Task | Status | Dependencies | Acceptance / notes |
|---|---|---|---|---|---|
| SD-DOCK-01 | Windows | Launch visible CM, expose its taskbar entry, remove automatic minimize | Source implemented; launch-policy tests 2/2 pass | SD-CM source review | Other platforms retain their launch behavior. Existing IPC and logged-in-user startup path are retained. |
| SD-DOCK-02 | Windows | Bottom-right dock with folded header, chat, permissions and session selector | Source implemented; widget tests 5/5 pass | SD-DOCK-01 | Draft survives folding and tab switching; panel stays expanded beyond the historical timer; keyboard entry works after unfolding. |
| SD-DOCK-03 | Windows | Unread indicator while folded and expansion for incoming Voice | Source implemented; full integration verification pending | SD-DOCK-02 | Reuses existing chat and voice handlers; no wire change. |
| SD-DOCK-04 | Windows | Full dependency/toolchain setup and build | Complete | SD-DOCK-01 through SD-DOCK-03 | All 16 native packages, full-feature Rust, Flutter Release and auxiliary DLL builds pass. |
| SD-DOCK-05 | Windows | Candidate startup and Windows/Android physical acceptance | CLI startup pass; paired/graphical pending | SD-DOCK-04; approved backend handoff | Verify chat, audible Voice, Disconnect, reconnect, multi-session selection, fold/unfold, DPI/work area and service startup. |

Focused evidence so far: Windows Flutter launch-policy test 1/1; legacy/headless launch-policy
test 1/1; foldable-panel Flutter widget tests 5/5 and existing CM API test 1/1;
modified Dart files analyze with exit 0 (existing informational lints only); new widget/test analysis clean;
LAN-only source gate passes. These do not constitute a full application build or device acceptance.

Additional verification: complete Flutter suite 53/53 pass; full Flutter analysis reports no
errors with 315 upstream style/deprecation/unused warnings/information. Generated bridge completed.
2026-10-08: full Rust (flutter,software-update,hwcodec,vram), Flutter Windows Release and auxiliary
DLL builds all exit 0. Candidate CLI --version exits 0 and reports 1.3.0 using isolated data.
Candidate folder: `D:\LAN-Messenger\outputs\SubnetDesk-helper-cm\Windows-x64-v1.3.0-cm-dock`.
The original installed SubnetDesk service is Running/Auto at
`C:\Program Files\SubnetDesk\SubnetDesk.exe --service`; inspected only, not stopped/replaced.
It can still launch the old/headless CM, so merely opening the candidate does not test the patched
incoming-session path. User direction for a session-safe backend handoff is required before pairing.
Android remember-password addition is a separate planning-first task in
`PLAN-SUBNETDESK-ANDROID-CREDENTIALS.md`, awaiting explicit execution start.
