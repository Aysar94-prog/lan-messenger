2026-10-17 release3.0.10: WP-04 portable ZIP/manifest gates PASS (288 entries,
SHA2564E50AE6812FB3627EB9122B104ECDB6EBE229FF9727201CA45D6751748C23609).
Package-local runtime startup confirmed; clean-machine acceptance remains open.
Dedicated windows/NuGet.Release.Config now preserves release-runtime restore inputs.

2026-10-17 release3.0.10 execution: user explicitly requested new Windows release.
WP-01/02 complete: official runtime packs9.0.9 restored via temporary release config,
proper self-contained win-x64 publish, includedFrameworks and coreclr/hostfxr present.
WP-03 local startup passed with package-local runtime modules and missing DOTNET_ROOT;
clean no-.NET VM/call/camera acceptance remains pending. WP-04 packaging in progress,
outputs D:\Projects AI\LAN-Messenger\outputs; Android unchanged.
See PLAN-SEND-FOLDER.md release checkpoint and release3.0.10 logs under D:\BuildCache.

# Windows portable with bundled .NET

2026-10-12 — Planning only; await explicit execution instruction.

User reports LAN Messenger 3.0.9 portable asks to install .NET 9.0.
Confirmed ZIP runtimeconfig references Microsoft.NETCore.App and
Microsoft.WindowsDesktop.App 9.0.0; ZIP has no coreclr.dll or hostfxr.dll.
Windows project targets net9.0-windows/WinForms/x64. Current artifact requires
an installed runtime. Goal: Windows x64 portable runs without installing .NET.
Android and shared protocol are unaffected.

| Task | Platform | Status | Dependencies | Notes | Acceptance / testing |
|---|---|---|---|---|---|
| WP-01 Review publish dependencies | Windows | Pending | Explicit start | Check SDK/runtime packs and offline NuGet feed; resolve required packs without changing machine settings | Publish inputs available; document package constraints |
| WP-02 Publish self-contained x64 | Windows | Pending | WP-01 | Bundle Windows Desktop runtime; preserve native/media assets and existing app behavior; choose release identity before packaging | Publish succeeds; runtime and native dependencies included |
| WP-03 Verify portable startup | Windows | Pending | WP-02 | Use Windows environment without installed .NET Desktop runtime where available | App opens without download prompt; check chat, voice and video; distinguish static checks from actual device acceptance |
| WP-04 Package and record | Windows | Pending | WP-03 | ZIP and SHA256 under D:\LAN-Messenger\outputs; update Windows/project status | ZIP contents/hashes verified; unresolved device checks stated; Android remains unchanged |

No application code edits, publish/build or runtime installation performed.
