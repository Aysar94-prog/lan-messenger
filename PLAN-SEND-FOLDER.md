## Current plan — direct folder streaming with adaptive compression (2026-10-17 22:36 Asia/Jerusalem)

User request: “its faster ! use it on lan”. New implementation scope, PLANNING ONLY under AGENTS.md; explicit start still required after this plan. Preserve latest Windows-only preference: implement Windows sender/receiver first, review Android compatibility without implementing/releasing Android. Current package3.0.11 remains ZIP-based. Direct-folder speed gains are a hypothesis until measured against the same dataset/network/transfer mode.

### Intended behavior / decisions

- Same existing File/Attach and Fast file entry points and combined file/folder picker. Ordinary files unchanged; no separate Folder action or compression toggle.
- Supported receivers get a folder job: relative-path manifest, directories/empty dirs, streamed individual files and automatic recreation beneath the user-chosen destination root. No temporary ZIP or manual ZIP extraction.
- Adopt SubnetDesk's approach, not an assumption that its RustDesk wire frames work with LAN's LM4 protocol. SubnetDesk inspected files: `D:\Projects AI\SubnetDesk-Helper\libs\hbb_common\src\fs.rs` (recursive jobs, compress only when resulting chunk smaller, compressed flag, decompression on receipt), `compress.rs` (Zstandard). Do not modify or move SubnetDesk files or merge projects.
- Adaptive Zstandard compression per bounded chunk, raw chunks when not beneficial. Audit/pin/vendoring and decoder output limits before adding dependency; no zstd package currently found in LAN vendor/nuget. Package/runtime assets and licenses must be reproducible. No benchmark claim based on compression alone.
- Preserve selected File normal TLS/encrypted-store semantics and Fast explicit RAW payload/authenticated token semantics; compression is independent of encryption. No silent mode switch, auto-accept or verification bypass. File can prepare/encrypt/cache per file for reliable queue retries; Fast retains stable user sources, without building a plain folder archive. Check that existing byte policy remains unchanged; DF-01 explicitly documents per-file/whole-job rules before implementation, no arbitrary entry-count cap or truncation.
- Add a separate authenticated folder capability/version. Never repurpose existing CAPS group version or FILECAPS STREAM1 in a way that old builds misparse. Existing Windows3.0.11 and Android3.0.9 lack folder metadata/receiver support. Show an explicit receiver-update requirement; any legacy ZIP compatibility choice must be explicit and preserve chosen transfer mode, not an automatic fallback.
- Job/per-file progress, cancellation, durable queue and per-file/block restart/resume with uncompressed offsets/hashes. Source changes fail visibly; validate every manifest path, decoded output size and case/name collisions. Default destination is a fresh root, preventing silent overwrites; pending/partial files stay job-owned and cleanup cannot delete user sources or unrelated destinations. Preserve empty directories and Unicode.

| Task | Platform | Status | Dependencies | Notes | Acceptance / testing |
|---|---|---|---|---|---|
| DF-01 Contract/mode/dependency design | Both (Windows implementation, Android review) | Pending | Explicit user start | Review both LM4 receivers, dedicated folder capability, mode invariants, byte policies, zstd library/asset/license choice; keep project boundaries | Written bounded frames/manifest/offset/lifetime spec; old peer behavior defined; pinned dependency and build inputs reproducible |
| DF-02 Folder manifest and job persistence | Windows | Pending | DF-01 | Relative paths, dirs/empty dirs, file identity/size/hash/source references, stable job IDs and snapshot semantics; no ZIP | >65,535 entries without arbitrary count cap; paths/Unicode/collisions/cancel/source changes and restart persistence verified |
| DF-03 Adaptive block codec | Windows | Pending | DF-01 | Bounded input/output, compressed flag, zstd only if smaller; independent of transport; avoid full-file buffering | Round trips text/random/media/zero bytes, invalid/truncated/oversized output rejection, stream hash consistency |
| DF-04 Sender dispatch | Windows | Pending | DF-02/03 | Same selected File or Fast path; pipeline per-file prep/send; auth/lifetime/concurrency guards | Verified recipient only; TLS vs RAW preserved, first-file dispatch without ZIP creation; ordinary-file regressions |
| DF-05 Receiver folder reconstruction | Windows | Pending | DF-02/03 | Select destination root once; validate path containment/reparse/case rules, create dirs, bounded streamed writes, partial-to-complete commit | Exact tree/hashes incl empty dirs; no traversal/absolute-path escape, silent overwrites or premature completion |
| DF-06 Retry/resume/cancel lifecycle | Windows | Pending | DF-04/05 | Persist per-file/block progress; reconnect auth, source identity and hashes; retain only needed state/data | Interrupt/restart sender and receiver mid-folder; resume remaining offsets/files, no duplicated/wrong files, correct cancellation/cleanup |
| DF-07 Capability/compatibility integration | Both (review/testing; Android code deferred) | Pending | DF-04/05/06 | Probe dedicated capability without confusing old commands; unchanged single-file protocol | New Windows<->Windows folders; old Windows/Android explicit unsupported result; ordinary-file interoperability retained; no Android APK rebuild implied |
| DF-08 Focused automated + fault tests | Windows | Pending | DF-02..07 | Extend existing harness meaningfully; shared wire tests appropriate to actual changes, verify selected modes and job lifetimes | Large-tree, codec/path/collision, auth, cancellation, resume, partial/missing-source and queue tests pass; full logs/results tied to source |
| DF-09 Controlled performance/device acceptance | Windows | Pending | DF-08 | Compare3.0.11 ZIP baseline and new streaming on same text/media/many-small datasets and mode; separate synthetic and physical evidence | First-byte delay, wall time, bytes, CPU/RAM/disk measured; report gains/regressions honestly; actual physical acceptance not inferred |
| DF-10 Release/checkpoint | Windows | Pending | DF-08/09 | Next unused patch version chosen at execution, self-contained x64/native/notices/source/checksums; Android retained | Exact published checks and archive gates, limitations and compatibility documented; local only/no push |

### Inspection and resume state

Only read-only inspection and plan/status edits this turn. No application edits, dependency install, code build, test, release, benchmark, firewall change or app restart. No owned process. Existing app3.0.11/source ZIP workflow remains authoritative; prior44+2 checks do NOT cover proposed direct-folder protocol. Firewall investigation remains separate and does not authorize security-setting changes.

Reviewed `windows/DirectDownloads.cs`, `Transfers.cs`, `PeerEngine.cs` and Android `DirectFileTransfer.java`/`PeerEngine.java`: FETCHDIRECT/DIRECT/TOKEN/RAWREADY are single-file flows, Android FILECAPS STREAM1 is not folder support. SubnetDesk source recursion/chunk compression code read, no live speed benchmark. Android implementation deferred; old receiver cannot automatically reconstruct a folder from unchanged current file messages.

After user explicitly starts, begin DF-01 from canonical `D:\Projects AI\LAN-Messenger\source`, PowerShell7.6.5, .NETSDK9.0.305. Do not create duplicate repositories/worktrees or change branch/remote. Exact initial reads:

```powershell
Get-Content AGENTS.md
Get-Content PLAN-SEND-FOLDER.md -TotalCount 80
Get-Content windows/DirectDownloads.cs
Get-Content windows/Transfers.cs
Get-Content android/src/net/lanmsg/chat/DirectFileTransfer.java
Get-Content windows/NuGet.Release.Config
```

Shared caches `D:\BuildCache` (Gradle `gradle-user-home`, AndroidSDK `android-sdk` if needed for compatibility tests); actual binaries/reports `D:\Projects AI\LAN-Messenger\outputs\.build\<phase>`, release artifacts outputs root, complete logs `D:\BuildCache\logs\LAN-Messenger\<phase>`. Root NuGet.Config offline/vendor cache outputs/.build/nuget; release-only config can acquire pinned runtime/dependency assets. Verify actual JDK/test commands before Android JVM compatibility tests; connected phone does not substitute for required emulators. Preserve .private signer; exclude from snapshots. Keep original3.0.11 ZIP as benchmark baseline.

## Firewall investigation result — 2026-10-17 22:29 Asia/Jerusalem

User explicitly asked investigation after forgetting which prompt/feature. Completed read-only diagnosis; no requested fix or OS changes inferred.

- Windows Firewall event2097 recorded exact3.0.11 app inbound TCP/UDP Public BLOCK rules at22:19:43. Event message explicitly says rule added; it is not a packet-drop event. “Query User” rule IDs likely relate to the remembered permission prompt; cannot establish which button was clicked or which individual feature triggered it.
- Current app switched externally to3.0.11 PID17740, production TCP43872/UDP43871 listeners active. Prior3.0.10/offline observation is superseded. Source normal messaging/discovery use those ports; Fast payload adds ephemeral TCP, but no evidence singles it out.
- Wi-Fi currently Private, Private firewall disabled, Public enabled. Existing Public-only blocks do not establish cause on this Wi-Fi.
- Three actual app outbound TCP43872 destinations in SynSent; no-payload3s probes all timed out. Two current-subnet neighbor entries Unreachable; one destination uses gateway route to another subnet. Evidence supports unreachable/offline/stale peer addresses, not confirmed remote firewall/device state. Remote IPs not copied into checkpoint/source; counts/routes summarized.
- Packet-drop logging disabled, pfirewall.log absent. Readable Security5152/5155/5157 and FirewallDiagnostics queries for last2h returned no matches. Rule/profile history exists (Wi-Fi Public->Private21:58:51), but cannot reconstruct a specific blocked transfer/call.

FW-01 complete. FW-02 investigation complete with above evidence limit; no further user recollection required. FW-03 corrective rule proposal not justified by current evidence, so no rule/profile/app/security change made. No user messages sent (diagnostic TCP handshake only); no app restart, build/release/commit/push. No owned processes remain; read-only session58882 completed. Next useful external check: peer device actually Online on same Wi-Fi and reachable at its current address. Keep the current app/rules intact until a reproducible affected flow exists.

Evidence: `D:\BuildCache\logs\LAN-Messenger\firewall-20261017\diagnosis.json`. Windows version3.0.11 artifacts unchanged. Exact read commands below remain valid; currentPID supersedes older examples. No new implementation plan or tests needed for this diagnosis-only request.

## Firewall latest finding — 2026-10-17 22:27 Asia/Jerusalem

Get-CimInstance confirms live PID12736 runs `D:\Projects AI\LAN-Messenger\outputs\LanMessenger-3.0.10-Windows\LanMessenger.exe`, not3.0.11. No production TCP listener/UDP endpoint. Source can be Offline or listener startup can have failed; exact UI state not observed. Public-only block rules do not prove a current Private-Wi-Fi block because Private firewall is disabled. Do not change rules before correlating user symptom. User should tray-Exit old app, run3.0.11 and check Online. Async symptom question pending; no automatic app restart, firewall change or source edit. Read-only exec46937 finished; no owned processes remain.

Exact read-only resume commands:

```powershell
Get-NetConnectionProfile
Get-NetFirewallProfile -PolicyStore ActiveStore
Get-CimInstance Win32_Process -Filter "Name = 'LanMessenger.exe'"
Get-NetTCPConnection -State Listen -LocalPort 43872 -ErrorAction SilentlyContinue
Get-NetUDPEndpoint -LocalPort 43871 -ErrorAction SilentlyContinue
```

## Firewall diagnostic checkpoint — 2026-10-17 22:26 Asia/Jerusalem

Read-only diagnosis of user's “firewall blocks something” report. No configuration/app changes authorized or made. Get-NetConnectionProfile shows Wi-Fi Private and Tailscale Private. Get-NetFirewallProfile -PolicyStore ActiveStore: Private Enabled0, Public/Domain Enabled1. ActiveStore application filters for exact outputs/LanMessenger-3.0.11-Windows/LanMessenger.exe associate enabled inbound TCP/UDP BLOCK rules, Public only;3.0.10 has same. These rules alone do NOT prove a block on current Private Wi-Fi. Source production ports TCP43872/UDP43871, Fast temporarily opens a random inbound TCP port via DirectDownloads. No production-port listeners observed. Earlier lookup of44971/44972 was test-port-only and not evidence about production app. App process/online state being rechecked; async question asks affected feature and prompt path.

| Task | Platform | Status | Dependencies | Notes | Acceptance / testing |
|---|---|---|---|---|---|
| FW-01 Read active rules/profile/ports | Windows | Complete | User report | Public block rules identified, Private profile disabled | Read-only PowerShell evidence; no drop event proof |
| FW-02 Identify actual blocked flow | Windows | Pending user detail | FW-01 | Which feature/prompt, app path/online state, network involved | Correlate actual profile/listener/app, avoid attributing failure to irrelevant profile |
| FW-03 Narrow corrective proposal | Windows | Pending | FW-02 | If requested/needed, exact executable/local subnet/needed profile scope; retain security settings otherwise | User-authorized change with before/after rule evidence and affected-feature test; no global firewall disable |

No code/build/install/release/rule change, no app/process started/stopped. Read-only firewall enumeration finished; no owned process remains. Working directory canonical source, PowerShell7.6.5; caches/output paths unchanged. Exact resume: Get-NetConnectionProfile; Get-NetFirewallProfile -PolicyStore ActiveStore; Get-NetTCPConnection -State Listen -LocalPort 43872; Get-NetUDPEndpoint -LocalPort 43871; read user's symptom. User data not accessed.

## FINAL large-folder fix checkpoint — 2026-10-17 22:17 Asia/Jerusalem

LF-01..04 complete. User-authorized removal implemented and Windows3.0.11 portable released locally. No fixed folder-entry count or picker listing rejection; virtual rows/full cancellable enumeration. Selected File/Fast pipelines and their existing byte/depth/link/source-change/lifetime guards unchanged. Source44/44 focused checks PASS and actual published helper2/2 PASS, each mode preserves65,540 ZIP64 entries and extracts content; large picker65,537 children. All288 payload hashes/runtime/native/private gates pass; source427 entries verified without .private/signing/build data. Memory remains proportional to entries (source peak112MiB, actual published two-archive run peak163MiB on this fixture). User's own folder and physical peers not tested.

Artifacts `D:\Projects AI\LAN-Messenger\outputs`:

- `LanMessenger-3.0.11-Windows.zip`,64,894,056 bytes, SHA2565E5292B795DBE6D46F7F0455FDC08036BEB657659414ABA54D5F504E808E735B.
- `LanMessenger-3.0.11-Source.zip`,186,258,184 bytes, SHA25691F93021BEB214DCD9EC37DC6C459C122494E35AFD8DEA2D896CDE89E98D1E78.
- `SHA256SUMS-Windows-3.0.11.txt`, `RELEASE-NOTES-Windows-3.0.11.md`, expanded `LanMessenger-3.0.11-Windows` with per-file hashes/README/notes.

Source snapshot freezes app sources before final status/checksum postlude; application bytes verified against current source. Not a Git commit; uncommitted feature and unrelated dirty changes preserved, no push.3.0.10 retained. Android remains3.0.9/code107 without code/build/install.

No owned processes remain. User app PID11820 (path/version not readable) untouched; no standalone startup probe to avoid its single-instance dialog. Published self-contained9.0.9/runtime/native/notices verified; native identical to3.0.10 (prior138 tests not rerun). Previous clean-VM/physical/microphone-stall acceptance remains open. Next user action: Exit the old app via tray, extract entire3.0.11 ZIP and run LanMessenger.exe; existing user data retained. No further implementation/release task required for this count-cap fix.

Evidence `D:\BuildCache\logs\LAN-Messenger\large-folders-20261017`: source/build.log, source/tests.log, publish.log, published-check-build.log, published-large-check.log, published-assembly.sha256, package-verification.json. Exact commands/environment and synthetic fixture path follow. Future fresh source check: `& .\tests\FolderSend\run.ps1 -OutputRoot 'D:\Projects AI\LAN-Messenger\outputs\.build\large-folders-next'`; publish using windows/NuGet.Release.Config to a NEW output directory. No heavy suites should be repeated without relevant change/failure.

## Large-folder fix verified — Windows3.0.11

User explicitly authorized removal. LF-01/02/03 complete: no fixed folder-entry or picker-listing cap; retain selected File/Fast transport, byte/depth/link/source-change/lifetime guards. Virtual list accepts65,537 children. Source44/44 checks PASS (source/tests.log), exact published app helper2/2 PASS (published-large-check.log), each mode verifies65,540 ZIP64 entries and extracts content. Actual published gate19.007s/16.520s, peak163MiB after two archives; source workload peak112MiB. This is synthetic fixture evidence; user's real folder not accessed. Archive central-directory/metadata memory is proportional to entry count; no infinite-memory promise or arbitrary replacement count threshold.

LF-04 portable3.0.11 ZIP finalizing, source snapshot/checksum next. Runtime9.0.9/native/notices preserved; no repeat native tests for unchanged binary (prior138/138 applies). Running LanMessenger.exe observed PID11820 with unreadable path/version; it is user-owned, untouched. Do not stop it automatically or infer installed version. Startup probe skipped to avoid its single-instance dialog. Source code version/About3.0.11; no Android changes/install/push/commit. No owned test/publish process remains; archive worker may still be finishing per tool session.

Exact published gate reproduction from source root (test output assembly must first be replaced by published DLL; hashes compared):

```powershell
Copy-Item 'D:\Projects AI\LAN-Messenger\outputs\LanMessenger-3.0.11-Windows\LanMessenger.dll' 'D:\Projects AI\LAN-Messenger\outputs\.build\large-folders\published-check\LanMessenger.dll' -Force
dotnet 'D:\Projects AI\LAN-Messenger\outputs\.build\large-folders\published-check\FolderSend.dll' 'D:\Projects AI\LAN-Messenger\outputs\.build\large-folders\evidence\folder-send-44bc2d8fc11b461eb468257b3e92adeb\many' --published-large
```

This reuses the synthetic fixture. If missing, rebuild/run tests/FolderSend/run.ps1 in a fresh outputs/.build/large-folders run to recreate it. Logs D:\BuildCache\logs\LAN-Messenger\large-folders-20261017 (source/build.log, source/tests.log, publish.log, published-check-build.log, published-large-check.log, published-assembly.sha256, package-verification.json). Host PowerShell7.6.5/.NETSDK9.0.305. Current source and release app identical since44PASS; subsequent changes are test-mode/docs only. No full UI/microphone regression rerun; historical stall remains separate.

## LF verification checkpoint — 2026-10-17 22:15 Asia/Jerusalem

LF-01/02 complete, source focused suite44/44 PASS/exit0. Includes actual virtual picker65,537 children; both ZIP mode policies retain all65,540 entries with ZIP64 and content/empty dirs. Archive timings23.708s(File)/32.265s(Fast), peak process working set112MiB for this synthetic workload, total large-fixture phase86.128s. Memory remains proportional; no disk-manifest refactor justified by this evidence. Existing cancellation/change/link/depth/size/mode/queue/restart/resume checks pass. Sources and tests uncommitted; unrelated dirty changes untouched.

Self-contained Windows3.0.11 publish passed (publish.log), runtime/native assets same as3.0.10. Current exact published helper gate tests the actual app assembly by reflection (not source-linked helper) using existing65,540-entry fixture; exec session recorded by tool, log published-large-check.log, SHA in published-assembly.sha256. Test code added a --published-large flag to avoid regenerating65k fixtures for each release gate; application code unchanged after44PASS. LF-04 staged output outputs/LanMessenger-3.0.11-Windows, prior3.0.10 intact; no Android changes/install/push.

## LF implementation checkpoint

LF-01/02 implemented: removed every MaxEntries guard/Take limit from FolderArchive; full enumeration remains cancellation-aware in scan and final directory verification. Metadata arrays and ZIP central directory are proportional to entries; no disk manifest added without measured need. Shared AttachmentPicker now uses virtual rows with cancellable full listings, no20,000-item rejection. File/Fast queue/wire/resume logic unchanged. Version/About3.0.11 for corrected local release. LF-03 tests running in exec session30743; logs D:\BuildCache\logs\LAN-Messenger\large-folders-20261017\source, outputs/.build/large-folders. Test fixtures grow beyond65,535 entries; this is synthetic filesystem data, not user's selected folder. No Android changes/install/push.

## Large-folder execution — 2026-10-17 22:10 Asia/Jerusalem

User explicitly authorized “remove it”. LF-01/02 in progress, LF-03 next. Remove10,000-entry archive and20,000-item picker rejections; retain mode/size/depth/link/change/lifetime guards. Use virtual ListView rows for picker, cancellable enumeration for archive. Existing ZIP central directory and archive metadata remain proportional to entry count; measure >65,535-entry run before considering disk-backed manifest complexity. Package corrected Windows3.0.11 as continuation of the just-delivered release; preserve3.0.10. Android unchanged. No process owned yet; logs D:\BuildCache\logs\LAN-Messenger\large-folders-20261017, outputs/.build/large-folders.

## Current follow-up — large-folder entry count, 2026-10-17 22:09 Asia/Jerusalem

User screenshot reports the existing3.0.10 count cap. Confirmed `windows/FolderArchive.cs`: MaxEntries10000 rejects scan totals and per-directory children; root and every subfolder count as entries. This was introduced by Codex, not a transport/protocol restriction. Prior39 checks include a test enforcing this limit; that is evidence of implemented policy, not acceptance for the user's large folder. Exact selected folder and total count are unknown; no access to user's folder was requested or taken.

Proposed fix: Windows-only, remove the arbitrary entry-count rejection, support large trees efficiently, and keep the existing File/Fast modes and transfer-size limits. No separate Folder action, silent mode switch, Android changes or automatic extraction. Keep existing cancellation, link handling, source-change checks, depth guard and temporary/durable archive lifetimes. ZIP central-directory memory grows with entry count; measure it and avoid promising literally unlimited folders. Inspect/replace scan-wide source lists and per-directory arrays as needed, with disk-backed metadata where justified. The shared picker also has a separate20,000-item display rejection: large-directory browsing should not prevent choosing the current folder; use virtual/incremental listing without adding a new attachment action.

Planning only. AGENTS.md requires explicit start for a new implementation task; the screenshot alone is not an explicit start of this changed policy. Existing3.0.10 release remains intact.

| Task | Platform | Status | Dependencies | Notes | Acceptance / testing |
|---|---|---|---|---|---|
| LF-01 Remove count rejection / scalable traversal | Windows | Complete | Explicit user start | Review MaxEntries guards, source/children arrays and final membership verification; retain path/link/change checks and selected-mode size limits | >10,000 entries including nested/empty directories prepares successfully within mode limits; measured memory/cancellation |
| LF-02 Shared picker large-directory handling | Windows | Complete | LF-01 review | Avoid20,000-item display rejection blocking current-folder selection; no new attachment action | Large listings remain responsive and selectable; regular-file selection/cancel/mode unchanged |
| LF-03 Large-folder regressions | Windows | Complete | LF-01/02 | Replace old expected-count-failure test; include >10,000 and >65,535-entry ZIP64 fixtures, names/content/count verification, cancellation and cleanup; both TLS/RAW pipeline checks | Automated results/memory/elapsed logs saved; receiver archive extraction verifies content; physical acceptance separate |
| LF-04 Corrected release / checkpoint | Windows | Complete | LF-03 | Prepare new patch3.0.11 if user authorizes implementation and corrected release; retain3.0.10 | Exact published assembly checks, bundled runtime/native/notices, ZIP/source/checksums; no Android release |

No implementation or test run this follow-up. No owned process. Current last verified/released code remains3.0.10 with the unwanted10,000-entry cap. Root `PROJECT_STATUS.md` and Windows status updated; Android unchanged. Resume from `D:\Projects AI\LAN-Messenger\source`, PowerShell7.6.5/.NETSDK9.0.305:

```powershell
Get-Content PLAN-SEND-FOLDER.md -TotalCount 45
Get-Content windows/FolderArchive.cs
Get-Content windows/AttachmentPicker.cs
Get-Content tests/FolderSend/Program.cs
```

After explicit start, execute LF-01 before running builds. Focused test entry point `.\tests\FolderSend\run.ps1`; logs `D:\BuildCache\logs\LAN-Messenger\<phase>`. Actual build/release outputs under `D:\Projects AI\LAN-Messenger\outputs`; dedicated runtime publish config windows/NuGet.Release.Config. Shared caches D:\BuildCache (Gradle gradle-user-home, AndroidSDK android-sdk), unused for Windows-only work. Preserve existing dirty changes and signing material.

## FINAL release checkpoint — 2026-10-17 22:03 Asia/Jerusalem

R310-01..04 complete. Ready local artifacts under `D:\Projects AI\LAN-Messenger\outputs`:

- `LanMessenger-3.0.10-Windows.zip` — 64,894,151 bytes,288 entries, SHA2564E50AE6812FB3627EB9122B104ECDB6EBE229FF9727201CA45D6751748C23609.
- `LanMessenger-3.0.10-Source.zip` — 186,250,563 bytes,427 entries, SHA25658A7E13BAE0DAB206FA061E9CA6FAF255634223F83606D5E8E861AE77A2EB254.
- `SHA256SUMS-Windows-3.0.10.txt`, `RELEASE-NOTES-Windows-3.0.10.md`.
- Expanded portable directory `LanMessenger-3.0.10-Windows` with per-file SHA256SUMS.txt and README/notes.

ZIP integrity/all staged-entry hashes/runtime/native checks PASS, source private/key/build-data exclusion PASS; current released application/config bytes match source archive. Snapshot frozen just before final checksum/status postlude, not a Git commit. Source base HEAD3fc3a205 is NOT a commit containing this uncommitted feature. No unrelated dirty files reverted/staged; no local commit/push requested or made.

Exact published assembly39/39 PASS, native138/138 PASS, isolated offline app startup PASS (loads bundled9.0.9 runtime with empty DOTNET_ROOT). Clean no-.NET Windows VM and actual physical peers/calls/huge-folder/disk-full acceptance remain unverified. Broader WindowsUi recording stall is still incomplete, not a full pass. Android remains3.0.9/code107 without code/build/install. No owned process remains; app startup smoke PIDs stopped after checks, no existing-app replacement/install/OS changes.

Logs/evidence: `D:\BuildCache\logs\LAN-Messenger\release-3.0.10-20261017` (publish-2.log, focused-build.log, focused-assembly.txt, focused-tests.log, native-input-check.log, portable-host-trace-2.log, startup-modules-2.txt, startup-result.txt, package-verification.json). First publish source-CLI error and first too-short startup probe are documented below; corrected runs passed. Application/.pdb bytes unchanged after focused verification.

No required release task remains. Optional next action SF-07: physical Windows/Android transfer acceptance with the extracted3.0.10 app. Close the previous app via tray Exit before manually running `D:\Projects AI\LAN-Messenger\outputs\LanMessenger-3.0.10-Windows\LanMessenger.exe`; do not uninstall/clear data. Rebuild only after relevant source changes using the preserved release config/command below, in a NEW output directory. Preserve private keys and existing source tree.

## Current release checkpoint — 2026-10-17 22:03 Asia/Jerusalem

R310-01/02/03 complete. Windows3.0.10 self-contained portable ZIP created (64,894,151 bytes/288 entries), hash4E50AE6812FB3627EB9122B104ECDB6EBE229FF9727201CA45D6751748C23609. Actual published assembly39/39 PASS (focused-tests.log, exact hash in focused-assembly.txt), native138/138 PASS (same DLL hash copied to package). Isolated offline startup loaded package-local runtime with DOTNET_ROOT pointing to missing location; first6s probe was too short, bounded35s retry passed. Clean VM and physical acceptance remain open. No owned processes, no install/runtime switch/Android build/push. R310-04 source snapshot+outer checksum finalization underway.

Package verification initially falsely flagged System.Private.CoreLib.dll because the gate searched for `.private` as a substring. Gate corrected to exact path-component `.private` plus signing-key suffixes; all288 payload entries/hash/runtime/native/private-exclusion checks now PASS (package-verification.json). ZIP bytes were not changed by the gate correction.

Release rebuild command (dedicated checked-in config is equivalent to the temporary config actually used):

```powershell
Set-Location 'D:\Projects AI\LAN-Messenger\source'
dotnet publish windows/LanMessenger.csproj -c Release -r win-x64 --self-contained true --configfile windows/NuGet.Release.Config -p:PublishSingleFile=false -p:PublishTrimmed=false -o 'D:\Projects AI\LAN-Messenger\outputs\LanMessenger-3.0.10-Windows'
```

Do not run over shipped release output without first saving it and selecting a new output directory. After publish include approved native DLL and WEBRTC-NOTICE/VERSIONS from verified input, plus release README/notes/manifest. Base native hash5405FF31F0ABAF74E08033A6A8FAB51CD557E52D7DE9B19CE7510D09C1B4EA7A; source archive contains pinned SDK package and native build script for rebuilding. Published .NET DLL E4706F84CDD836F17CD67940380804F5D1C6EDFBC3EFA157C83F375606DB384C. Exact tests: build tests/FolderSend, replace only test output LanMessenger.dll with published assembly, compare hashes, then run FolderSend.dll with isolated outputs/.build/release-3.0.10/evidence. Existing focused script by itself tests a fresh source build, not necessarily the exact published binary.

## Windows 3.0.10 release — active checkpoint 2026-10-17 21:58 Asia/Jerusalem

User explicitly requested “make a new release”; execute packaging now, Windows-only scope retained. Version3.0.10 selected as next patch (prior3.0.9). Include native video dependency/notices and bundled .NET runtime so portable package does not repeat known3.0.9 missing-runtime prompt. Prior3.0.0 portable contains runtime9.0.9 and native DLL;3.0.9 ZIP is framework-dependent and omits native DLL. No Android rebuild/install; no push. Version identity must match About and PE metadata. Existing focused39PASS valid for feature; verify exact final release assembly/package separately.

| Task | Platform | Status | Dependencies | Notes | Acceptance / testing |
|---|---|---|---|---|---|
| R310-01 Identity / publish inputs | Windows | Complete | User release request | Set project/About3.0.10; locate approved native/notices and runtime packs | Identity3.0.10, input hashes recorded |
| R310-02 Publish portable x64 | Windows | Complete | R310-01 | Bundled .NET; preserve managed/native assets; outputs/LanMessenger-3.0.10-Windows | Publish exit0 and complete dependencies/runtimeconfig |
| R310-03 Verify exact package | Windows | Complete | R310-02 | Focused39 checks against published assembly, native checks, isolated offline startup | Version/hash/native/startup evidence; physical acceptance separate |
| R310-04 Archive/checkpoint | Windows | Complete | R310-03 | ZIP, SHA256, release notes and source snapshot without .private/logs/test data; local save only | Extracted ZIP hashes match, private exclusion, statuses current |

Build environment: source D:\Projects AI\LAN-Messenger\source, PowerShell7.6.5, pinned .NETSDK9.0.305; NuGet.Config vendored feed/cache outputs/.build/nuget unchanged. New runtime packages may be acquired from official NuGet with command-scoped source, no global feed change. Logs D:\BuildCache\logs\LAN-Messenger\release-3.0.10-20261017. Shared cache root D:\BuildCache (Gradle/AndroidSDK unused). Actual release outputs D:\Projects AI\LAN-Messenger\outputs. No owned build processes yet; unrelated dirty tree preserved.

# Unified file/folder attachments — Windows

Current checkpoint: 2026-10-17 21:54 Asia/Jerusalem, Codex. Implemented and focused automated checks passed. Physical-device acceptance remains open. No owned processes running. No release packaging/install/commit/push performed.

## Authorized scope and behavior

User authorized execution with “go ahed”, narrowed to Windows only, then required a single attachment workflow. The final design has NO separate Folder action or attachment menu. Both existing Attach/File and Fast file open the same file/folder picker. Select a folder row and use Select; double-click browses. The stock Windows file and folder dialogs cannot reliably commit both types through one action, so a shared Windows Forms picker handles both in the existing workflow.

- Ordinary file: original File or Fast source pipeline and mode retained.
- Folder via File: background ZIP, existing encrypted normal file queue, temporary ZIP removed once the encrypted store owns it.
- Folder via Fast file: background ZIP, existing Fast source-reference pipeline and RAW payload with existing explicit unencrypted warning. Generated ZIP retained in app-owned storage for later downloads/retries/resume; deleted with its transfer record. Never switch modes.
- Preserve root name, subfolders, empty dirs, Unicode and binary bytes. Display ZIP name fits existing120-character metadata cap while retaining .zip; full root name stays inside ZIP. Receiver saves/extracts ZIP with its own file manager.
- Preparation progress/Cancel, conversation binding, remove/switch/shutdown cleanup, verification gate. Content and ZIP bounds follow selected mode: File1GiB, Fast1TiB; 10,000 entries/64 levels. Links/junctions are omitted with visible count; linked root rejected. Unreadable/changing files fail explicitly. No atomic filesystem snapshot is promised; detected size/time/membership changes fail.
- Android implementation deferred by explicit user request; no Android code/build/install in this execution. Existing receiver wire unchanged. Physical Windows/Android acceptance has not been run.

| Task | Platform | Status | Dependencies | Notes | Acceptance / testing |
|---|---|---|---|---|---|
| SF-01 Transfer/lifetime review | Windows | Complete | User start and scope | Normal queue copies encrypted bytes; Fast retains source reference; wire unchanged | Both transfer paths and limits reviewed |
| SF-02 Unified picker | Windows | Complete | SF-01 | Replaces earlier separate Folder menu proposal; both existing actions share picker | Actual picker file/folder selection and cancellation tested; screenshots inspected |
| SF-03 Android sender UI | Android | Deferred by user | Later Android authorization | No parity implementation inferred | Not run |
| SF-04 ZIP preparation | Windows | Complete | SF-02 | Bounded, background, cancelable, integrity/path/lifetime checks | Archive bounds, contents, changes, Unicode, long names, links, cleanup pass |
| SF-05 Both selected pipelines/lifetimes | Windows | Complete | SF-04 | Durable Fast ZIP, independent normal encrypted copy; original files remain external | Ordinary files, mode identity, queue/restart, cleanup, RAW/TLS behavior pass |
| SF-06 Focused automated verification | Windows | Complete | SF-05 | Final suite39/39; broad UI suite incomplete at unrelated recording section | Build success; full focused log and native exit0; broader log40 PASS markers, interrupted, NOT a full pass |
| SF-07 Physical acceptance | Windows | Pending | SF-06 + physical peers | Installed app/actual network and existing Android receiver checks | Not run; loopback evidence is automated acceptance only |
| SF-08 Checkpoint/result | Windows | Complete | SF-06 | Source/status/evidence current; physical acceptance explicitly pending | No release/install/commit/push; no owned process remains |

## Implementation files

- windows/AttachmentPicker.cs — shared file/folder browsing and single Select/Cancel flow.
- windows/FolderArchive.cs — bounded ZIP streaming and draft leases; old abandoned temp-session cleanup.
- windows/ChatWindowAttachments.cs — same actions dispatch file/folder in selected mode; draft progress and cleanup.
- windows/Program.cs, ChatWindowRender.cs, ChatWindowVoice.cs — send/import lease, verification/send gate and preparation-panel lifecycle.
- windows/Transfers.cs — ordinary Fast API reuses common core; generated ZIP gets durable app-owned source, existing protected source-reference record, clear/expiry cleanup and crash-orphan reconciliation.
- windows/PeerEngine.cs — startup reconciliation of generated Fast ZIP sources.
- tests/FolderSend/{FolderSend.csproj,Program.cs,run.ps1,README.md} — archive, real Forms UI and loopback transfer checks.

## Verification and exact evidence

Working directory `D:\Projects AI\LAN-Messenger\source`, PowerShell7.6.5, .NETSDK9.0.305 pinned by global.json; installed9.0.305/10.0.401. Target net9.0-windows, application x64. Vendored NuGet.Config feed/cache unchanged: actual NuGet cache `D:\Projects AI\LAN-Messenger\outputs\.build\nuget`.

Final exact command:

```powershell
Set-Location 'D:\Projects AI\LAN-Messenger\source'
& .\tests\FolderSend\run.ps1 -LogRoot 'D:\BuildCache\logs\LAN-Messenger\send-folder-20261017\final-2'
```

Build: SUCCESS, exit0, zero errors, one pre-existing CS1998 in ChatWindowVoice.cs19. Focused suite: PASS=39 FAIL=0, exit0, tied to final application code. Logs:

- `D:\BuildCache\logs\LAN-Messenger\send-folder-20261017\final-2\build.log`
- `D:\BuildCache\logs\LAN-Messenger\send-folder-20261017\final-2\tests.log`

Outputs: `D:\Projects AI\LAN-Messenger\outputs\.build\send-folder\tests\LanMessenger.dll` (+exe/deps/runtimeconfig) and FolderSend.dll. These are validation outputs, not a packaged release/runtime switch. Final log first line gives unique evidence directory under `outputs\.build\send-folder\evidence`, with combined-picker-file.png, combined-picker-folder.png, folder-draft.png and real received ZIP/extracted fixtures. Screenshots from prior final pass inspected; clipped picker footer corrected before final tests. Includes actual normal TLS and Fast RAW loopback downloads, offline durable queue/sender restart, cancelled Fast after first256KiB and receiver restart/resume from saved block, integrity comparison and safe generated-source cleanup.

Earlier evidence: first normal-only design21PASS, superseded scope36PASS, expanded37/38PASS. Initial harness build failed CS0542 (report class same name as Report member), fixed to InlineProgress; later builds passed. Final2 added120-character ZIP-extension regression. No known focused failure remains.

Broader UI regression command (not a complete pass):

```powershell
dotnet build tests/WindowsUi/WindowsUi.csproj -c Release --configfile NuGet.Config -o 'D:\Projects AI\LAN-Messenger\outputs\.build\send-folder\ui-regression'
dotnet 'D:\Projects AI\LAN-Messenger\outputs\.build\send-folder\ui-regression\WindowsUi.dll' 'D:\Projects AI\LAN-Messenger\outputs\.build\send-folder\ui-evidence'
```

Build passed; ui-regression.log has40 PASS markers through hide-to-tray recording safe-stop, then no further progress. Stalled in subsequent unrelated microphone-recording section; exact cause NOT proved. Stopped only owned PID23028 after verifying exact WindowsUi command line; exec session18041 exit-1. Do not rerun unchanged full suite without diagnosing stalled recording/modal behavior. Logs `...\send-folder-20261017\ui-build.log` and `ui-regression.log`; no full-suite pass claim. Native media/real device/emulator gates and huge folders/disk exhaustion are not verified.

## Resume / remaining work

No owned processes remain (all builds/focused checks finished; stalled broad UI process stopped). Existing dirty changes unrelated to this feature, including path/cache maintenance, remain preserved; no branch/remote reconfiguration. No app data, existing release, signing key or OS setting changed. Prior latest packaged LAN Messenger remains3.0.9; this feature has not been packaged/installed.

Next action: inspect current Windows UI/FolderSend evidence, then SF-07 physical-peer acceptance if devices are available. If packaging requested, confirm intended release identity and include existing native/runtime assets using project release workflow; pending self-contained Windows packaging plan is separate. Do not run stale Android/default release scripts. To rerun focused checks after relevant changes:

```powershell
Set-Location 'D:\Projects AI\LAN-Messenger\source'
Get-Content PROJECT_STATUS.md -TotalCount 25
Get-Content windows/STATUS.md -TotalCount 25
Get-Content PLAN-SEND-FOLDER.md
& .\tests\FolderSend\run.ps1
```

Global cache policy: `D:\BuildCache`, Gradle `D:\BuildCache\gradle-user-home`, AndroidSDK `D:\BuildCache\android-sdk`; not used for Windows build. New logs live under `D:\BuildCache\logs\LAN-Messenger`. Release outputs `D:\Projects AI\LAN-Messenger\outputs`; preserve private signer and exclude .private from source archives. No phone is a substitute for any required emulator gate when Android work is resumed.

### Release publish checkpoint

R310-01/02 complete: csproj Version and About AppVersion now3.0.10. Self-contained win-x64 publish succeeded with includedFrameworks9.0.9 and native assets copied from verified previous portable. Native input SHA2565405FF31F0ABAF74E08033A6A8FAB51CD557E52D7DE9B19CE7510D09C1B4EA7A, full WEBRTC notices retained; existing native integration138/138 PASS (native-input-check.log). Original --source CLI attempt failed NU1301 (URL normalized as local source), corrected to explicit temporary NuGet.Release.Config under outputs/.build/release-3.0.10 with absolute vendored+official NuGet sources. No global feed change. Publish retry log publish-2.log exit0, one existing CS1998. Actual outputs/LanMessenger-3.0.10-Windows includes bundled coreclr/hostfxr. Published EXE FileVersion3.0.10.0, app DLL E4706F84CDD836F17CD67940380804F5D1C6EDFBC3EFA157C83F375606DB384C. R310-03 next: test exact published assembly and isolated offline host startup; no owned publish/native process remains. No Android build/install.
