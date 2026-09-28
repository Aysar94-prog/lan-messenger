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
| W03 | Reusable `waveIn` adapter | **Code written, uncompiled** | `windows/VoiceRecorder.cs` (new file): P/Invoke `waveIn*` bindings + `VoiceRecorder` class. Native control serialized under one lock; the native callback itself does nothing but queue a thread-pool work item (avoids the classic MM-callback deadlock where the callback blocks on a lock the stopping thread already holds inside `waveInStop`/`waveInReset`); buffers are unmanaged (`Marshal.AllocHGlobal`), never pinned managed arrays; the callback delegate is rooted as an instance field; `Start()`/`Stop()`/`Dispose()` are idempotent. Feeds captured bytes into `VoicePcmAssembler` (W01) frame-by-frame, raises `FrameReady`/`DeviceFailed`. Highest-risk piece of this whole phase to have written without being able to compile it — the P/Invoke signatures and WAVEHDR layout are standard/well-known, but this has had zero verification of any kind. Treat as the first thing to sanity-check once the build is unblocked. |
| W04 | Record/Stop/Preview/Delete/Send UI + lifecycle-safe stop | **Code written, uncompiled** | `windows/ChatWindowVoice.cs` (new file) + edits to `Program.cs` (new toolbar buttons, wiring), `ChatWindowRender.cs` (enable/visibility state + `RenderVoicePanel()` call each render cycle), `ChatWindowAttachments.cs` (hands the shared `attachmentDraft` panel back to `RenderVoicePanel()` when a file-attachment draft is cleared). Reuses the existing `attachmentDraft`/`pendingAttachmentRow` panel for both the live recording clock and the finalized-draft Preview/Delete/Send row — a pending file attachment and an active/pending voice draft are mutually exclusive composition states, so sharing one panel is deliberate. Lifecycle-safe stop wired at: explicit Stop button, conversation switch (`SelectedIndexChanged`), window hide-to-tray (`FormClosing`) and final shutdown (`FormClosed`), Offline transition + 5-minute limit (both via the existing 1 s `timer.Tick`), and device failure (`VoiceRecorder.DeviceFailed`). **Preview is a stub** ("not implemented yet" message box) since it depends on W08 (`waveOut` player), not yet built — the draft itself is unaffected, still fully Delete/Sendable. **Known gap**: `FormClosed`'s call to `StopVoiceRecording()` is `async void` (WinForms event handlers can't await), so on final shutdown its async finalize work races the process actually exiting; if it loses that race, `ReconcileVoiceDrafts()` (W02) safely diagnoses the leftover `Recording`-state entry as `Invalid` on next startup rather than anything worse — acceptable but not gold-plated. |
| W05 | Transactional Send (ten-step durable write order) | **Code written, uncompiled** | `windows/Conversations.cs`: `QueueContentAsync` gained a backward-compatible optional `explicitId` parameter (defaults to `null` → unchanged `Guid.NewGuid()` behavior for every existing caller). `windows/VoiceDrafts.cs`: new `PeerEngine.SendVoiceDraft(draftId, caption)` — allocates the message id and marked filename together (step 7), decrypts the finalized draft to plaintext WAV bytes and imports it via `QueueContentAsync` with that explicit id (steps 8-9), then deletes the registry entry (step 10). Wired to the UI's Send button in `ChatWindowVoice.cs`. |
| W06 | Extend `PeerEngine.QueueImageDownloads()`/`imageSlots`/`imageAttempts` (`windows/Transfers.cs`) into the fixed 9-rule automatic-media scheduler | Not started | |
| W07 | Candidate/Fetching/Playable/Invalid/Unavailable receiver cards | Not started | |
| W08 | `waveOut` inline player, 7-step seeking, Save/Export | Not started | |
| W09 | Accessibility | Not started | |
| W10 | Persistence/regression + architecture-boundary + canonical-fixture checks | Not started | |
| W11 | Update `windows/STATUS.md` + `PROJECT_STATUS.md` comparison entry | Not started | Must merge, not replace — A11 (Android) touches the same comparison entry; see plan finding R3-01. |
| WT01-WT06 | Automated tests | Not started | Blocked on the dotnet build issue above even once written. |
| Manual acceptance | Not started | Requires physical Windows hardware/devices; cannot be performed by an agent. |

## Resume point

Currently just past **W05**. W01-W05 all code written but completely unverified — the
dotnet build is still blocked in this session (see above), so none of this has compiled even
once. The visible, user-facing send flow (record → stop → finalize → preview stub/delete/send)
is now wired end to end. Next action: **W06** (fold Voice Message retrieval into the fixed
9-rule automatic-media scheduler, extending `PeerEngine.QueueImageDownloads()`/`imageSlots`/
`imageAttempts` in `windows/Transfers.cs`).

Strong recommendation for whoever resumes this: before going further into W04+, stop and
wire a small test entry point (likely a new `tests/CsharpHarness` command, mirroring the
existing `OWNER`/`TRANSFEROWNER` pattern) that at minimum runs `VoicePcmAssembler`/
`VoiceWav`/`VoiceSeek`/`VoiceMarker` (W01) against every `tests/voice_messages/vectors/
manifest.json` vector (WT01's actual required coverage), and get an actual `dotnet build`
to succeed at all. W03 (`VoiceRecorder.cs`, native P/Invoke) is the highest-risk file written
so far — real Win32 interop, zero verification — and should be sanity-checked before more
code is built on top of it blind. Coordination note: `tests/voice_messages/check_contract.py`
and `manifest.json` are being actively edited by the Android-phase agent (confirmed twice by
`git status` during this session) — do not touch those files without re-checking `git status`
first and re-reading them fresh; they were correctly left alone throughout this session.
