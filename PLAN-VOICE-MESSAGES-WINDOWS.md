# Voice Messages — Windows (Phase 2) execution progress

Tracks execution of `plan-v003` (`.ai-planner/sessions/20260928-025216-b2673f/planning/plan-v003.md`),
Phase 2 — PC (Windows), task IDs W01-W11 / WT01-WT06. Running in parallel with another
agent's Phase 1 (Android) work per the plan's explicit "either order or in parallel" note
(I01-I04 are already frozen: see `tests/voice_messages/contract.md`, `pcm-contract.md`,
`vectors/manifest.json`).

**Post-completion bug fix (found during the Android A05 verification pass, fixed on both
platforms in the same session)**: `VoiceDraft.SendTransactionId` (set by
`MarkVoiceDraftSendTransaction` at step 7 of the ten-step order) was written to the registry but
never actually *read* by `ReconcileVoiceDrafts` — meaning crash-outcome 8 (the message was
durably queued at step 9, but the crash landed before step 10 removed the registry entry) was
not handled: the stale draft would resurface after restart as an ordinary Finalized/sendable
draft, and clicking Send again would duplicate an already-sent message. This violated the
contract's explicit "reconciliation must never produce a duplicate Send of the same draft"
(Draft state recovery item 5). Fixed in `windows/VoiceDrafts.cs`'s `ReconcileVoiceDrafts`: any
Finalized draft whose `SendTransactionId` matches an existing self-authored message id is now
completed (registry entry removed, plaintext best-effort deleted) instead of left as a normal
draft. New deterministic regression test `tests/CsharpHarness/VoiceDraftReconcileCheck.cs`
(`--voice-draft-reconcile-check`, wired into `run.ps1`), using the same never-`Start()`,
reflection-seeded-state technique as `VoiceSchedulerCheck.cs` — both the fix (crash-outcome-8
draft removed) and the control case (a draft with a send transaction but no matching message yet
is left alone) pass. The identical bug existed in `android/src/net/lanmsg/chat/VoiceDrafts.java`
(a faithful port carries faithful bugs) and was fixed there too — see
`PLAN-VOICE-MESSAGES-ANDROID.md`'s A05 entry.

**Build blocker RESOLVED** (previously: `dotnet build` failed in this sandboxed shell with
NU1101 on `Microsoft.NETCore.App.Ref` etc.). `dotnet build windows/LanMessenger.csproj -c
Debug --configfile NuGet.Config` now succeeds cleanly (0 errors, 1 pre-existing unrelated
CS1998 warning in `ChatWindowVoice.cs`). All Windows Voice Messages code below has now
actually compiled for the first time.

**WT01 has now actually run against the real C# production classes**:
`VOICECHECK	PASS=38	FAIL=0	SKIP=30` — all green. Note this is 38 pass (not the
Python cross-check's 36) with 0 fail: the two previously-flagged fixture discrepancies
(`pcm-short-final`, `seek-align-odd-byte`) are no longer failing, meaning the Android-phase
agent's concurrent edits to `manifest.json` resolved them on the fixture side. No further
action needed on those two.

## Status

| ID | Task | Status | Notes |
|---|---|---|---|
| W01 | Marker recognition, WAV validation, PCM normalization, WAV sink/source, checked seek conversion | **Code written, uncompiled** | `windows/VoiceMessages.cs` (new file): `VoicePcmAssembler` (frame/epoch/event assembly per pcm-contract.md), `VoiceMarker` (marker parse/classify), `VoiceWav` (bounded streaming validation + canonical WAV builder), `VoiceSeek` (7-step seek procedure). No WinForms/PeerEngine/attachment-store/device dependencies (forbidden-dependency boundary respected). Not yet cross-checked against `tests/voice_messages/vectors/manifest.json` (WT01) — no test harness wired yet. |
| W02 | Durable application-private draft registry, atomic transitions, 10-entry cap, stale-review, reconciliation | **Code written, uncompiled** | `windows/VoiceDrafts.cs` (new file, `partial class PeerEngine`): `VoiceDraft` record (all 10 registry fields), `VoiceDraftWriter` (open/append/close/abort an encrypted per-draft PCM file, same AES-256-CBC + protector-wrapped-key construction as `Conversations.cs` attachment storage), CRUD + `ReconcileVoiceDrafts()` (runs at startup, wired into the `PeerEngine` constructor right after `PurgeExpired()`). Registry rows persist as new `R` rows in `Storage.cs`'s existing encrypted `state.txt` (Load/Save both updated). `DeleteAllData()` (`Conversations.cs`) now also clears `voiceDrafts` and sweeps the `voice-drafts/` folder, matching how it already handles `attachments/`/`avatars/`. **Design note**: per-frame progress (`RecordVoiceDraftProgress`) is deliberately in-memory only, not persisted — a `Recording`-state entry is unconditionally diagnosed `Invalid` on the next startup regardless of recorded byte size (no writer survives a process exit), so durably rewriting the whole encrypted store on every ~20 ms frame (up to ~15,000 times for a 5-minute recording) would cost real performance for zero recovery benefit. Not yet cross-checked against `tests/voice_messages/vectors/manifest.json`. |
| W03 | Reusable `waveIn` adapter | **Code written, uncompiled** | `windows/VoiceRecorder.cs` (new file): P/Invoke `waveIn*` bindings + `VoiceRecorder` class. Native control serialized under one lock; the native callback itself does nothing but queue a thread-pool work item (avoids the classic MM-callback deadlock where the callback blocks on a lock the stopping thread already holds inside `waveInStop`/`waveInReset`); buffers are unmanaged (`Marshal.AllocHGlobal`), never pinned managed arrays; the callback delegate is rooted as an instance field; `Start()`/`Stop()`/`Dispose()` are idempotent. Feeds captured bytes into `VoicePcmAssembler` (W01) frame-by-frame, raises `FrameReady`/`DeviceFailed`. Highest-risk piece of this whole phase to have written without being able to compile it — the P/Invoke signatures and WAVEHDR layout are standard/well-known, but this has had zero verification of any kind. Treat as the first thing to sanity-check once the build is unblocked. |
| W04 | Record/Stop/Preview/Delete/Send UI + lifecycle-safe stop | **Code written, uncompiled** | `windows/ChatWindowVoice.cs` (new file) + edits to `Program.cs` (new toolbar buttons, wiring), `ChatWindowRender.cs` (enable/visibility state + `RenderVoicePanel()` call each render cycle), `ChatWindowAttachments.cs` (hands the shared `attachmentDraft` panel back to `RenderVoicePanel()` when a file-attachment draft is cleared). Reuses the existing `attachmentDraft`/`pendingAttachmentRow` panel for both the live recording clock and the finalized-draft Preview/Delete/Send row — a pending file attachment and an active/pending voice draft are mutually exclusive composition states, so sharing one panel is deliberate. Lifecycle-safe stop wired at: explicit Stop button, conversation switch (`SelectedIndexChanged`), window hide-to-tray (`FormClosing`) and final shutdown (`FormClosed`), Offline transition + 5-minute limit (both via the existing 1 s `timer.Tick`), and device failure (`VoiceRecorder.DeviceFailed`). **Preview is a stub** ("not implemented yet" message box) since it depends on W08 (`waveOut` player), not yet built — the draft itself is unaffected, still fully Delete/Sendable. **Known gap**: `FormClosed`'s call to `StopVoiceRecording()` is `async void` (WinForms event handlers can't await), so on final shutdown its async finalize work races the process actually exiting; if it loses that race, `ReconcileVoiceDrafts()` (W02) safely diagnoses the leftover `Recording`-state entry as `Invalid` on next startup rather than anything worse — acceptable but not gold-plated. |
| W05 | Transactional Send (ten-step durable write order) | **Code written, uncompiled** | `windows/Conversations.cs`: `QueueContentAsync` gained a backward-compatible optional `explicitId` parameter (defaults to `null` → unchanged `Guid.NewGuid()` behavior for every existing caller). `windows/VoiceDrafts.cs`: new `PeerEngine.SendVoiceDraft(draftId, caption)` — allocates the message id and marked filename together (step 7), decrypts the finalized draft to plaintext WAV bytes and imports it via `QueueContentAsync` with that explicit id (steps 8-9), then deletes the registry entry (step 10). Wired to the UI's Send button in `ChatWindowVoice.cs`. |
| W06 | Extend `PeerEngine.QueueImageDownloads()`/`imageSlots`/`imageAttempts` (`windows/Transfers.cs`) into the fixed 9-rule automatic-media scheduler | **Code written, uncompiled** | `windows/Transfers.cs`: `QueueImageDownloads()` renamed to `QueueAutomaticMedia()` (both call sites in `PeerEngine.cs` updated), now scans both image and voice-marked candidates, admitting from the same `imageSlots` pool with a `consecutiveAutoVoiceAdmissions` fairness counter under a new `schedulerGate` lock (voice preferred by default per rules 2-3, forced to image after 3 consecutive voice admissions per rule 4, counter only moves on real admission per rule 7). **Interpretation note**: rules 1/6 ("user-initiated... considered first"/"next eligible admission after a slot is released") are satisfied structurally, not by new code — manual downloads (`DownloadAttachmentAsync` called directly from UI) never acquire `imageSlots` at all today, so they were already unaffected by automatic-pool exhaustion; this session did not introduce new manual-side gating since none existed to begin with. Voice candidate detection uses `VoiceMarker.TryParse` only (no separate Fast-store check) since `SendVoiceDraft` (W05) never produces a Fast-stored attachment, and the existing `DestinationRequiredException` safety net in `DownloadAttachmentAsync` already handles a hypothetical Fast-marked candidate the same way it silently does for images today. **Mistake made and fixed this session**: an earlier attempt used a raw PowerShell `Get-Content -replace / Set-Content -Encoding utf8` to rename the two call sites, which corrupted `PeerEngine.cs` (added a BOM, mangled every em-dash in the file into mojibake). Caught immediately via `git diff`, reverted with `git checkout --`, and redone correctly with the Edit tool. Lesson: never use PowerShell text replacement on source files in this repo — always use the Edit tool. |
| W07 | Candidate/Fetching/Playable/Invalid/Unavailable receiver cards | **Code written, uncompiled** | `windows/ChatWindowVoiceCard.cs` (new file) + a small `ChatWindowMessages.cs` `MessageCard` branch: received (non-mine) messages whose filename parses as a voice marker get `AddVoiceCard`'s state-specific rendering instead of the generic attachment treatment; the sender's own sent-voice bubble is deliberately NOT specially rendered (shows as an ordinary attachment) since the contract's receiver-state table is receiver-side only. Candidate/Fetching/Playable/Invalid/Unavailable map onto existing `HasAttachment`/`Downloading`/`Retained`-style engine state; Playable does a full `VoiceWav.Validate` re-check (defense in depth, catches forged/corrupted content even if it slipped past whatever the sender claimed). Retry/Resume call `engine.DownloadAttachmentAsync` directly (never a destination picker, per contract); Save/Export reuses the existing `SaveAttachment` (which does prompt, correctly, since that's a distinct explicit action). Play is a shared stub (`ShowVoicePlaybackNotImplemented`, also used by the own-draft Preview button) pending W08. **Known perf note, not fixed**: a Playable card's `VoiceWav.Validate` full-decrypt only re-runs when the message's render signature actually changes (existing `signatureFeed` diffing in `ChatWindowRender.cs` already gates this), so it's bounded to once per state transition, not per timer tick — acceptable for the 9.6 MB max size, same cost class as the existing image-thumbnail decrypt path, but not memoized/cached the way thumbnails are. |
| W08 | `waveOut` inline player, 7-step seeking, Save/Export | **Code written, uncompiled** | `windows/VoicePlayer.cs` (new file): mirrors `VoiceRecorder.cs`'s safety construction exactly (serialized native control, rooted callback delegate, unmanaged buffers, minimal-work native callback, idempotent Stop/Dispose). Reuses `VoiceSeek.Resolve` (W01) for the seek math; adds epoch-tagging (`WAVEHDR.dwUser`) so a stale `MM_WOM_DONE` callback for a buffer written before the last Seek/Stop is detected and discarded rather than corrupting the new position. The whole decrypted WAV lives in one bounded (≤9.6 MB) managed `byte[]` for the player's lifetime — no plaintext playback file is ever written to disk. `windows/ChatWindowVoicePlayback.cs` (new file) is the shared controller enforcing exactly one active player app-wide (contract Decision 6): starting playback for a different key always stops whatever was playing. Wired into both the own-draft row (`ChatWindowVoice.cs`, replacing the old stub) and the received-message Playable card (`ChatWindowVoiceCard.cs`, replacing its stub too) — both now have real Play/Pause and seek buttons; `VoiceDrafts.cs` gained a small public `ReadVoiceDraftWav(draftId)` wrapper so the UI can preview a draft without exposing its private on-disk encoding. **Deliberate scope simplification**: seek is exposed as fixed ±10 s buttons, not a drag scrubber — still exercises the real seven-step `VoiceSeek` procedure end to end, just without scrubber drag/click event-handling risk in a session that can't visually verify it. |
| W09 | Accessibility | **Code written, uncompiled** | Added `AccessibleName` to every interactive voice control (record/stop, play/pause, ±10s seek, delete, send, retrieve, save, and the "open/download/resume/pause" fallback on an Invalid card) across `Program.cs`, `ChatWindowVoice.cs`, `ChatWindowVoiceCard.cs`, and `AccessibleRole.StatusBar` (plus a live-updated `AccessibleName`) on the state-conveying labels (recording clock, playback position, Candidate/Fetching/Unavailable text). Most of W09's other requirements were already structurally satisfied by earlier tasks rather than needing new code, verified explicitly rather than assumed: **keyboard accessibility** — every voice control is a stock `Button`, inherently Tab/Enter-operable, no custom controls were introduced; **focus order** — controls are added to their `FlowLayoutPanel`s in the same order they read visually, so default WinForms tab order already matches; **non-color errors** — every state (Invalid, Unavailable, device failure) is conveyed via text, color is only ever supplementary; **notification privacy** — grepped every `ShowNotification` call site and confirmed the existing generic "New encrypted message" text (shared by all message types, not voice-specific) never reveals content, so no code needed to change. **Accepted limitation**: WinForms has no simple public API for live-region screen-reader announcements (the `AccessibleName` updates on tick/refresh keep the *current* value correct for a user who navigates to that control, but won't proactively interrupt to announce it changing) — implementing that would need a custom `AccessibleObject` subclass, judged out of proportion to the rest of this pass. The ±10s seek buttons (chosen in W08 as a scope simplification over a drag scrubber) turned out to double as an accessibility win: a button is more reliably screen-reader-operable than a `TrackBar` would have been. |
| W10 | Persistence/regression + architecture-boundary + canonical-fixture checks | **Done** | Build unblocked (see banner above). Ran the full `tests/run.ps1` regression suite; found one pre-existing, reproducible failure in `tests/group_membership.py` (committed 2026-09-26, before this session — group-capability/legacy-version gating logic, unrelated to Voice Messages, confirmed reproducible even in a fully isolated copy of the test build with zero resource contention, so it is not a Voice Messages regression). Architecture-boundary + canonical-fixture-source checks written as new `tests/voice_architecture_check.py` (WT06, see below) — all 3 sub-checks pass. |
| W11 | Update `windows/STATUS.md` + `PROJECT_STATUS.md` comparison entry | **Done** | Added a new "Voice Messages implemented in source" section to `windows/STATUS.md` (summary, build/WT01/WT06 results, the pre-existing `group_membership.py` finding, handoff pointers) and a new Feature-comparison row + paragraph to `PROJECT_STATUS.md`, explicitly marked **platform-local only; interoperability not yet verified**, and phrased so Android's own A11 update (not yet landed — checked `android/STATUS.md`, no Voice Messages entry there yet) can add its own state without conflicting — satisfies the "merge, not replace" requirement (R3-01) since there was nothing to merge with yet. |
| WT01-WT06 | Automated tests | WT01, WT02, WT06 **written and passing**; WT03 **written and passing (device-level + real UI lifecycle-stop)**; WT04 **written and passing**; WT05 **written and passing (device-level + real UI)** | `tests/CsharpHarness/VoiceMessagesCheck.cs` (WT01) — all green. `tests/voice_architecture_check.py` (WT06) — all pass. `tests/voice_drafts.py` (WT02) — 8 scenarios pass. `tests/voice_scheduler.py` (WT04, real end-to-end over a live network) — 6 scenarios pass. `tests/CsharpHarness/VoiceDeviceLifecycleCheck.cs` (`--voice-device-check`) — real `waveIn`/`waveOut` native-adapter lifecycle coverage, 20 checks pass, gracefully `SKIP`s with no audio device. Real UI-level scenarios added to `tests/WindowsUi/Program.cs`: **WT05** — one-active-player enforcement via real button clicks and real `waveOut` playback (found and fixed a real bug: `StopActivePlayer()` never refreshed the deactivated card's stale "Pause" label — see `ChatWindowVoicePlayback.cs`). **WT03** — three real lifecycle-safe-stop scenarios (conversation-switch, hide-to-tray, Offline-transition), all via the actual `recordVoice` button and real recordings. **New this pass — WT04's fairness-counter test, `tests/CsharpHarness/VoiceSchedulerCheck.cs` (`--voice-scheduler-check`)**: the "3 consecutive voice admissions then force an image" rule (`Transfers.cs` `QueueAutomaticMedia` rule 4), made fully deterministic by never calling `PeerEngine.Start()` at all (no real networking, no `TimerLoop`) and instead reflection-seeding `running`/`peers`/`messages` directly, then calling the private `QueueAutomaticMedia()` once per scenario with the fairness counter pre-set via reflection. Confirms both directions: with the counter at 0, both free scheduler slots go to voice (default preference, rules 2/3); with the counter at 3, the next admission is forced to the waiting image, and the counter then resets so the same call's second free slot goes back to voice. The two real (fake-peer, deliberately unreachable) `DownloadAttachmentAsync` calls each admission triggers retry forever by design until cancelled, so the test explicitly cancels and polls for both scheduler slots to free between scenarios rather than waiting on a network timeout. All 4 checks pass, confirmed stable across 5 repeated runs. Wired into `run.ps1`. **Known gap, honestly flagged**: final-shutdown forced-stop (`FormClosed`) and Save/Export for a voice card are not yet covered by a UI scenario (low marginal value — see reasoning already recorded); the in-flight (not just post-completion) admission-dedup guard is still untested (the upfront per-call candidate filtering already makes it structurally hard to exercise meaningfully without deeper engine changes, and it's pure defense-in-depth for a narrow same-tick race). A genuine physical device *failure* (unplug) still cannot be forced from software. |
| Manual acceptance | Not started | Requires physical Windows hardware/devices; cannot be performed by an agent. |

## Cross-check against the real fixture manifest (no dotnet needed)

Since `dotnet build` stayed blocked, wrote a standalone Python port of W01's core algorithms
(`VoiceWav.Validate`, `VoicePcmAssembler`, `VoiceSeek.Resolve`, `VoiceMarker.Classify`) and ran
it against the live `tests/voice_messages/vectors/manifest.json` fixtures — script saved at
the session scratchpad path `voice_crosscheck.py` (not committed; it's a diagnostic aid, not
the real WT01 test, which must exercise the actual C# code once the build works). Result:
**36/38 testable vectors pass** (30 skipped: recipe-only/large fixtures and non-algorithmic
categories). Found and fixed three real issues in the committed C# before they could compound
further:

1. **`VoiceMarker.Classify` bug**: returned `InvalidMarkedContent` for a wrong-store or
   mismatched-id marker; the fixtures (`marker-forged`, `marker-mismatched`) expect
   `ordinary_attachment` for both — "Invalid marked content" is a distinct, later-stage
   receiver state reached only after a genuine Candidate's retrieved bytes fail WAV
   validation, never a possible outcome of marker classification itself. Removed
   `InvalidMarkedContent` from `VoiceMarkerClassification` entirely (it only ever had two real
   outcomes) and fixed `Classify`.
2. **`MessageCard`'s voice-card gate** (W07) only checked `VoiceMarker.TryParse` succeeding,
   not that the extracted id actually matched the message's own id — meaning a mismatched
   marker would incorrectly enter the voice-card code path (landing on `VoiceCardState.Invalid`
   there instead of never entering it at all). Fixed to gate on
   `VoiceMarker.Classify(...)==Candidate`; simplified `ClassifyVoiceMessage` accordingly since
   the id-check is now guaranteed by the caller.
3. **Real design gap in `VoicePcmAssembler`** (W01): it had no bounded-capacity/overflow
   behavior at all — Push() just returned however many frames a fragment produced, unbounded.
   The `pcm-overflow` fixture (9 frames delivered in one push) expects the assembler itself to
   cap at 8 frames per Push and fail terminally on a 9th, per pcm-contract.md's Capacity
   items 1-3. Added a `MaxFramesPerPush=8` cap directly in `Push()`'s loop. No API change was
   needed elsewhere: `VoiceRecorder.cs` (W03) already checked `TerminallyFailed` after every
   `Push()` call, so this fix required touching only `VoiceMessages.cs`.

**Two flagged, unresolved discrepancies** (left as-is, not blindly "fixed"):
- `pcm-short-final`: expects `last_timestamp_ns=20125000` for a 2-byte (1-sample) final frame
  following a 640-byte (320-sample) first frame; every formula I could derive from
  pcm-contract.md's literal text gives `20062500` (321 samples × 62500) instead. The expected
  value implies crediting that final frame with 2 samples' worth of time, not 1.
- `seek-align-odd-byte`: expects seeking to 1 ms into a 1000 ms recording to resolve to byte 0
  (`effective_ns=0`), when 16 kHz/16-bit audio has exactly 32 bytes/ms — 1 ms can only ever
  resolve to byte 32 under any formula I can construct, never byte 0, and the "odd byte before
  alignment" the vector's own description promises is mathematically unreachable from a clean
  millisecond value at this fixed sample rate.

Both fixture vectors pass `tests/voice_messages/check_contract.py`'s own internal-consistency
checks (which validate self-consistency, e.g. `effective_ns` matching `aligned_byte`, not
correctness against the actual seek/timestamp formula) — so the checker wouldn't have caught
either discrepancy regardless of which side (my code or the fixture) is actually right. Given
`manifest.json` is being actively edited by the Android-phase agent throughout this session
(confirmed via repeated `git status` checks), these may simply not be finalized yet. Worth
raising with whoever owns the I01-I04 contract before WT01/AT01 are written against them.

## Resume point

**W01-W11 all done.** WT01 and WT06 written and passing. Remaining before Windows Phase 2's
exit gate (per plan-v003): **WT02-WT05** (fake-audio-input lifecycle faults; direct/group
queueing + scheduler/dedup coverage; one-player-enforcement/export/bounded-memory/notification-
privacy coverage) and **Windows manual acceptance** (needs physical hardware, cannot be done by
an agent). `windows/STATUS.md` and `PROJECT_STATUS.md` are updated (W11).

Next action if continuing: WT02 first (mirrors WT01's shape — a `CsharpHarness` mode or a new
Python harness command exercising `VoiceRecorder`/lifecycle edges with fake input), then WT03-
WT05. None of these are blocked on anything anymore now that the build works.

Known non-blocking issue, confirmed unrelated to Voice Messages: `tests/group_membership.py`
fails reproducibly (even fully isolated from the concurrently-running Android-phase agent) with
`AddMember`'s live `QueryCapability` call returning 0 under the heaviest 16-17-real-process
scenario — the same class of environmental network-timeout contention already documented in
`windows/STATUS.md`'s 2.0.0-era entry, predating this session and this feature. Left untouched;
out of scope for Voice Messages.

## First packaged release (2026-09-29)

By explicit user request — "build a Windows release with Voice Messages included" — packaged the
already-code-complete W01-W11 work into an actual release for the first time, rather than leaving
it source-only. `windows/LanMessenger.csproj`'s `<Version>` and `Program.cs`'s `AppVersion` both
bumped `2.1.0` → `2.2.0`. `dotnet build -c Release` passed (0 errors, the one pre-existing benign
`CS1998` warning in `ChatWindowVoice.cs`). Published framework-dependent via `dotnet publish -c
Release`, matching the shape of every prior Windows release exactly (`LanMessenger.exe`/`.dll`/
`.pdb`, `BouncyCastle.Cryptography.dll`, `.deps.json`/`.runtimeconfig.json` — requires .NET
Desktop Runtime 9, no self-contained/RID bundling). Zipped as
`outputs/LanMessenger-Windows-2.2.0.zip` (2,847,939 bytes, SHA-256 `fb5b2349…`; manifest:
`outputs/SHA256SUMS-Windows-2.2.0.txt`). Full `tests/run.ps1` run for this exact release exited 0,
including every native Windows UI test for Voice Messages: first/second voice message arrival and
auto-download, clicking Play on a received card starting real playback, one active player app-wide
(starting a second card stops the first, row updates immediately), pause/resume toggle, seek not
crashing, and force-stop-and-finalize of an active recording on conversation switch, tray-close,
and going Offline. WT02-WT05 remain unwritten and W11's manual two-device physical acceptance
remains Pending — this packaging step did not add or skip any verification, it just makes the
already-tested W01-W10/WT01/WT06 work installable for the first time. No wire or storage change
from 2.1.0, so this interoperates with every 2.1.0-and-newer Android build at the pre-Voice-
Messages feature level; actual cross-platform voice send/receive (Phase 3) is still unverified.

Coordination note: `tests/voice_messages/check_contract.py`, `manifest.json`,
`generate_wav_cases.py` and `wav_cases.bin` were under active concurrent edit by the
Android-phase agent throughout this session (confirmed repeatedly via `git status`) and were
correctly never touched. Re-check `git status` before touching them if resuming.
