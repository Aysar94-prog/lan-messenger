# SubnetDesk Windows runner CM startup repair

Date: 2026-10-08. Platform: Windows only. Status: execution explicitly authorized; in progress.

## Reproduction and evidence

After the user-approved backend handoff, original service is Stopped/Auto and candidate PID1896
owns TCP21118. An Established connection from Android 192.168.1.51 to Windows 192.168.1.12
belongs to PID1896. User reports no session indication/dock while connected. This is failed
physical dock acceptance, not just the old-backend conflict.

Candidate main log at 09:33:20 and 09:33:33 reports:
`ipc to connection manager exit: Failed to connect to connection manager`.
Only the candidate main process survives; no --cm process/window exists.
A diagnostic launch of candidate `--cm` exits with code1 while main remains connected.

Verified native runner bug at flutter/windows/runner/main.cpp:50:
`argument.erase(argument.find_last_not_of(" \\n\\r\\t"));`
std::string::erase starts at the index of the final non-whitespace character, deleting that
character as well as trailing whitespace. Thus --cm becomes --c. The native single-instance
allowlist contains --cm, not --c, so it refuses the second instance before creating its Flutter
window. Rust core_main uses original arguments and returns an empty flutter_args list for CM,
which does not restore the lost character. The same trim bug exists at the original upstream tag;
the first repair did not cover it. Empty/all-whitespace arguments also need explicit regression
coverage because find_last_not_of can return npos.

## Tasks, dependencies and acceptance

| ID | Platform | Task | Status | Dependencies | Acceptance / notes |
|---|---|---|---|---|---|
| SD-RUN-01 | Windows | Reproduce actual candidate failure and trace runner | Complete | Handoff approval | Live connection belongs to candidate; --cm exits1; source identifies last-character deletion. |
| SD-RUN-02 | Windows | Correct trailing-whitespace trimming with focused native tests | Complete | SD-RUN-01 | Actual production header old0/17 -> fixed17/17; standalone CMake/CTest1/1; Rust launch policy2/2. No dependency changes. |
| SD-RUN-03 | Windows | Distinct, consistent candidate version and rebuilt package | Complete | SD-RUN-02 | Full Rust release (6m04s/46 existing warnings) and Flutter Windows Release (25.3s) pass; Flutter53/53. Rust CLI1.3.1/exit0 and Windows metadata1.3.1+76. Binary/source ZIPs completed; source commit2aa0dc7 plus pinned submodule/WindowInjection,1180 entries, private-material gate pass. Local candidate, not official upstream release; originals preserved. |
| SD-RUN-04 | Windows | Native --cm startup alongside main and incoming session | Startup reaches Flutter CM; paired dock pending | SD-RUN-03; approved candidate runtime switch | New main PID4052 owns TCP21118, service Stopped. Manual --cm PID8696 survives5s and logs --cm started/FFI cm initialization, then closes with no clients (consistent with existing6s idle close). No visible/session dock claim from this smoke. Manual CM no longer present; user reconnect requested to test automatic launch. |
| SD-RUN-05 | Windows | Fold/chat/reconnect acceptance and status handoff | Pending | SD-RUN-04; phone/user interaction | Draft persistence, unread, bidirectional chat and reconnect verified. Voice audible acceptance separate. Do not infer service-installed/prelogin/DPI acceptance. |

Diagnosis itself changed no application code/version/build and kept the session connected.
User subsequently authorized execution: native trimming fix, consistent local candidate version
1.3.1+76 and full rebuild completed. The old candidate session was left running during build;
approved runtime switch occurred at09:47:38 Asia/Jerusalem. New main receives on .12:21118/PID4052.
Installed service retains Automatic startup; reboot may restart it. User reconnect requested;
actual incoming dock, fold/chat/voice remain pending until observed/reported.

Artifacts in outputs/SubnetDesk-helper-cm:
- SubnetDesk-1.3.1-Windows-x64-cm-dock.zip, SHA256357bd2ca6cb9cbe30a16f3208a6b8abedacbe594ddd8f198a66a3e2d74b6ef55.
- SubnetDesk-helper-v1.3.1-source.zip, SHA25666cc7e7f8f375d5f972ebacd7b092486dee4ca3365bdd929e2c522e18fce60a9.
- Build log, native regression executables and startup outputs under checks (not bundled).
Initial source merge rejected duplicate directory entry; incomplete archive preserved under
checks and merger corrected to skip only duplicate directory entries, reject duplicate files.
Recreated source gate passed. No files/settings were deleted; no push.
