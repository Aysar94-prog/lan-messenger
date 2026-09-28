# Voice Messages — Windows (Phase 2) execution progress

Tracks execution of `plan-v003` (`.ai-planner/sessions/20260928-025216-b2673f/planning/plan-v003.md`),
Phase 2 — PC (Windows), task IDs W01-W11 / WT01-WT06. Running in parallel with another
agent's Phase 1 (Android) work per the plan's explicit "either order or in parallel" note
(I01-I04 are already frozen: see `tests/voice_messages/contract.md`, `pcm-contract.md`,
`vectors/manifest.json`).

**Known blocker this session:** `dotnet build` fails in this sandboxed shell with NU1101
(cannot resolve `Microsoft.NETCore.App.Ref` etc. from `C:\Program Files\dotnet\packs\...`,
even though those packs exist on the real filesystem — a sandbox/session restriction, not a
code problem; `dangerouslyDisableSandbox` is blocked by the auto-mode classifier). **All work
below is written but NOT YET COMPILED OR TESTED** until this is resolved. Do not assume any
of it builds — verify with `dotnet build windows/LanMessenger.csproj -c Debug --configfile
NuGet.Config` before relying on it.

## Status

| ID | Task | Status | Notes |
|---|---|---|---|
| W01 | Marker recognition, WAV validation, PCM normalization, WAV sink/source, checked seek conversion | **Code written, uncompiled** | `windows/VoiceMessages.cs` (new file): `VoicePcmAssembler` (frame/epoch/event assembly per pcm-contract.md), `VoiceMarker` (marker parse/classify), `VoiceWav` (bounded streaming validation + canonical WAV builder), `VoiceSeek` (7-step seek procedure). No WinForms/PeerEngine/attachment-store/device dependencies (forbidden-dependency boundary respected). Not yet cross-checked against `tests/voice_messages/vectors/manifest.json` (WT01) — no test harness wired yet. |
| W02 | Durable application-private draft registry, atomic transitions, 10-entry cap, stale-review, reconciliation | Not started | Next up. |
| W03 | Reusable `waveIn` adapter | Not started | |
| W04 | Record/Stop/Preview/Delete/Send UI + lifecycle-safe stop | Not started | |
| W05 | Transactional Send (ten-step durable write order) | Not started | Integration point identified: `windows/Conversations.cs` `QueueContentAsync`/`QueueFileFromPathAsync` (~line 187-213) already does steps 8-9 (import into encrypted Normal store, durably save message) but generates its own `Guid.NewGuid()` message id internally — this conflicts with step 7 ("allocate the message ID and final marked filename on Send" as one act, since the filename `voice-<id>.lanvoice.wav` must embed that same id). Will need a small, backward-compatible optional-parameter change to let a caller supply the id up front. Not yet made. |
| W06 | Extend `PeerEngine.QueueImageDownloads()`/`imageSlots`/`imageAttempts` (`windows/Transfers.cs`) into the fixed 9-rule automatic-media scheduler | Not started | |
| W07 | Candidate/Fetching/Playable/Invalid/Unavailable receiver cards | Not started | |
| W08 | `waveOut` inline player, 7-step seeking, Save/Export | Not started | |
| W09 | Accessibility | Not started | |
| W10 | Persistence/regression + architecture-boundary + canonical-fixture checks | Not started | |
| W11 | Update `windows/STATUS.md` + `PROJECT_STATUS.md` comparison entry | Not started | Must merge, not replace — A11 (Android) touches the same comparison entry; see plan finding R3-01. |
| WT01-WT06 | Automated tests | Not started | Blocked on the dotnet build issue above even once written. |
| Manual acceptance | Not started | Requires physical Windows hardware/devices; cannot be performed by an agent. |

## Resume point

Currently on **W01**, code written but unverified. Next action: continue W01 by wiring a
small test entry point (likely a new `tests/CsharpHarness` command, mirroring the existing
`OWNER`/`TRANSFEROWNER` pattern) that loads `tests/voice_messages/vectors/manifest.json` and
runs `VoicePcmAssembler`/`VoiceWav`/`VoiceSeek`/`VoiceMarker` against every vector — this is
WT01's actual required coverage, and doing it now (rather than after W02-W09) gives the
earliest possible real correctness signal once the build is unblocked. Then proceed to W02
(draft registry).
