# Windows unified file/folder attachments

Run from `D:\Projects AI\LAN-Messenger\source` in PowerShell:

```powershell
& .\tests\FolderSend\run.ps1
```

Requires .NET SDK 9.0.305 (global.json), Windows Forms and the existing vendored NuGet feed.
No Android build, release, install or real user data is used. The test creates isolated
data below outputs/.build/send-folder/evidence, briefly opens transparent Windows Forms,
and uses loopback addresses 127.0.0.25/26 on TCP46792/UDP46791. Junction fixtures use
`cmd /c mklink /J` within the test's generated evidence folder. Evidence is retained.

Checks ZIP contents and bounds, changes/cancellation/cleanup, generated archive leases,
actual shared picker selection, both existing attachment actions and ordinary file paths,
verification gates, encrypted normal ZIP delivery, RAW Fast ZIP delivery, offline queue
and sender restart, partial Fast cancellation/receiver restart/resume, hash integrity,
orphan recovery and clearing generated sources without deleting ordinary external files.

The same archive source is compiled into the harness for direct archive checks; native
UI and transfer cases exercise the actual Windows application assembly. Snapshots include
the shared picker and ready-to-send ZIP. Real physical devices, old installed binaries,
large (>1GiB) production workloads, disk exhaustion, shell extensions and Android
interoperability still require separate acceptance. The unrelated WindowsUi full regression
suite is not part of this focused command.

Large-folder regressions (3.0.11): creates10,001 then65,536 synthetic empty files,
plus nested content/empty dirs; verifies all65,540 ZIP entries in both File/Fast size
policies, cancellation and a virtual picker with65,537 children. No arbitrary file-count
or picker-listing rejection. This adds filesystem/antivirus time; elapsed time and peak
process working set are printed in the complete log. The ZIP central directory and
metadata still scale with entry count; the change does not promise infinite memory.
