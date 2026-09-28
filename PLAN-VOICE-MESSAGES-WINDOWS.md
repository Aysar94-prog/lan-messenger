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
| W02 | Durable application-private draft registry, atomic transitions, 10-entry cap, stale-review, reconciliation | **Code written, uncompiled** | `windows/VoiceDrafts.cs` (new file, `partial class PeerEngine`): `VoiceDraft` record (all 10 registry fields), `VoiceDraftWriter` (open/append/close/abort an encrypted per-draft PCM file, same AES-256-CBC + protector-wrapped-key construction as `Conversations.cs` attachment storage), CRUD + `ReconcileVoiceDrafts()` (runs at startup, wired into the `PeerEngine` constructor right after `PurgeExpired()`). Registry rows persist as new `R` rows in `Storage.cs`'s existing encrypted `state.txt` (Load/Save both updated). `DeleteAllData()` (`Conversations.cs`) now also clears `voiceDrafts` and sweeps the `voice-drafts/` folder, matching how it already handles `attachments/`/`avatars/`. **Design note**: per-frame progress (`RecordVoiceDraftProgress`) is deliberately in-memory only, not persisted — a `Recording`-state entry is unconditionally diagnosed `Invalid` on the next startup regardless of recorded byte size (no writer survives a process exit), so durably rewriting the whole encrypted store on every ~20 ms frame (up to ~15,000 times for a 5-minute recording) would cost real performance for zero recovery benefit. Not yet cross-checked against `tests/voice_messages/vectors/manifest.json`. |
| W03 | Reusable `waveIn` adapter | Not started | Next up. |
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

Currently just past **W02**, both W01 and W02 code written but unverified (build still
blocked — see above). Next action: **W03**, a reusable `waveIn` adapter (serialized native
control, rooted delegates/buffers, bounded frame delivery via `VoicePcmAssembler`, safe
completion signaling, idempotent stop/disposal per the plan's Windows implementation notes).

Before going further into W04+, it would be worth pausing to wire a small test entry point
(likely a new `tests/CsharpHarness` command, mirroring the existing `OWNER`/`TRANSFEROWNER`
pattern) that loads `tests/voice_messages/vectors/manifest.json` and runs
`VoicePcmAssembler`/`VoiceWav`/`VoiceSeek`/`VoiceMarker` against every vector (WT01's actual
required coverage) — this is the earliest point a real correctness signal becomes possible
once the dotnet build is unblocked, and W01/W02 are both already self-contained enough to
test in isolation without W03-W09.
