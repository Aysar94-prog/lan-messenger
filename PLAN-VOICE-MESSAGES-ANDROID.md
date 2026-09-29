# Voice Messages — Android (Phase 1) execution progress

Tracks execution of `plan-v003` (`.ai-planner/sessions/20260928-025216-b2673f/planning/plan-v003.md`),
Phase 1 — Android, task IDs A01-A11 / AT01-AT06. Picked up after another agent (a concurrently
running opencode/OAK session) spent ~6 hours hardening the shared I01-I04 contract layer
(`tests/voice_messages/check_contract.py` grew to a ~2,050-line validator with 51 check groups
and mutation self-testing; new `tests/voice_messages/validation-contract.md` (I04); `manifest.json`
and `wav_cases.bin` extended significantly) but had not yet written any Android production code —
confirmed via `git log`/`git status` showing zero `android/*.java` changes before this session
picked the work up. That contract-hardening work is committed separately (`aa5f006`).

Mirrors [PLAN-VOICE-MESSAGES-WINDOWS.md](PLAN-VOICE-MESSAGES-WINDOWS.md)'s tracking discipline:
update this file's status table after every task, commit locally after each meaningful chunk
(checking `git status` first, per AGENTS.md), and keep an honest "Resume point" section so work
can continue across a session boundary or a different agent picking it up.

## Status

| ID | Task | Status | Notes |
|---|---|---|---|
| A01 | Marker recognition, bounded streaming WAV validation, PCM normalization, WAV sink/source, checked seek conversion | **Done, verified** | `android/src/net/lanmsg/chat/VoiceMessages.java` (new file): `VoicePcmAssembler` (frame/epoch/event assembly per pcm-contract.md), `VoiceMarker` (marker parse/classify), `VoiceWav` (bounded streaming validation + canonical WAV builder), `VoiceSeek` (7-step seek procedure). Faithful method-for-method port of `windows/VoiceMessages.cs` (W01), adapted to this codebase's existing Java conventions: String constants instead of enums (matching `Message.status`'s style), plain mutable-field classes instead of C# records (matching `Peer`/`Message`), `Math.multiplyExact`/`addExact` for checked arithmetic (matching C#'s `checked(...)`). No Android UI/PeerEngine/attachment-store/device-API dependency (forbidden-dependency boundary respected, matching the file-header comment). **Verified against the real shared fixture manifest, not just compiled**: new `tests/VoiceMessagesCheck.java` (AT01) + `tests/MiniJson.java` (a minimal hand-rolled JSON reader — no JSON library exists anywhere in this codebase) run the actual production classes against `tests/voice_messages/vectors/manifest.json` and get `PASS=38 FAIL=0 SKIP=30` — identical to Windows's WT01 result, first try, confirming both platforms agree on every shared vector. |
| A02 | Durable application-private registry, atomic state transitions, ten-entry enforcement, stale-review, startup reconciliation | Not started | Depends on A01 (done). Mirrors `windows/VoiceDrafts.cs` (W02): needs a `VoiceDraft` model, encrypted per-draft file writer (mirror `AttachmentStore`'s existing encryption construction), registry persistence (mirror `PeerEngine.java`'s `save()`/`load()` row format), reconciliation on startup. |
| A03 | Reusable `AudioRecord` adapter, `RECORD_AUDIO` permission | Not started | Depends on A01, A02. Mirrors `windows/VoiceRecorder.cs` (W03) but using `android.media.AudioRecord` instead of `winmm.dll` P/Invoke — a different device API shape (no native callback marshaling concerns like Windows's MM-callback deadlock risk, but real Android lifecycle/permission concerns instead: runtime permission request/denial/Settings recovery, `RECORD_AUDIO` manifest entry). |
| A04 | Record/Stop/Preview/Delete/Send/recovery UI, Activity lifecycle + service-owned engine coordination, forced safe stop | Not started | Depends on A03. Mirrors `windows/ChatWindowVoice.cs` (W04) but for Android's Activity/Service split (`MainActivity.java`/`MessengerService.java`) rather than a single WinForms process — rotation and process death are real additional concerns Windows didn't have. |
| A05 | Transactional Send (ten-step durable write order) | Not started | Depends on A02, A04. Mirrors `windows/VoiceDrafts.cs`'s `SendVoiceDraft` (W05) and `Conversations.cs`'s `QueueContentAsync` explicit-id extension — Android equivalents are `PeerEngine.java`'s queue/send methods. |
| A06 | Extend `TransferManager.queueImageDownloads()`/`imageSlots`/`imageAttempts` into the fixed automatic-media scheduler | Not started | Depends on A01, A05. Mirrors `windows/Transfers.cs`'s `QueueAutomaticMedia` (W06) — same nine fixed rules, same fairness-counter semantics, now formally specified in the new `validation-contract.md`'s "Scheduler admission rules" section. |
| A07 | Candidate/Fetching/Playable/Invalid marked content/Unavailable receiver cards | Not started | Depends on A01, A06. Mirrors `windows/ChatWindowVoiceCard.cs` (W07) — Android's card-building lives in `MainActivity.java`'s render loop / `AttachmentFlow.java`. |
| A08 | One active inline player using `AudioTrack`, seven-step seeking, SAF Export | Not started | Depends on A01, A07. Mirrors `windows/VoicePlayer.cs` (W08) — `AudioTrack` instead of `waveOut`; Android adds audio-focus and route-change handling Windows didn't need. |
| A09 | Accessibility: labels, textual states, touch targets, focus order, announcements, notification privacy | Not started | Depends on A04, A07, A08. Mirrors W09. |
| A10 | Persistence/regression + Android-local architecture-boundary + canonical-fixture checks | Not started | Depends on A01-A09. Mirrors W10. |
| A11 | Update `android/STATUS.md` + `PROJECT_STATUS.md` comparison entry | Not started | Must merge, not replace — W11 (Windows) already touched the same comparison entry (commit `24c0073`); see plan finding R3-01. |
| AT01 | Run shared WAV, PCM, stream-event, receiver-state, seek vectors | **Done, passing** | `tests/VoiceMessagesCheck.java` + `tests/MiniJson.java`, wired into `tests/run.ps1` right after the existing Android `javac` step. `PASS=38 FAIL=0 SKIP=30` (identical to Windows WT01). Only wav-validation/pcm-frames/seek/marker categories are in scope for AT01 (A01's scope) — the manifest's newer `receiver`/`registry`/`scheduler` categories (added by the Android-phase contract-hardening work) are skipped here and belong to AT02/AT04+ once their production code exists. |
| AT02-AT06 | Remaining automated tests | Not started | Blocked on A02-A09 respectively. |
| Android manual acceptance | Not started | Requires physical Android hardware/device; cannot be performed by an agent. |

## Resume point

**A01 + AT01 done and verified**, first task of Android Phase 1. Next: **A02** (durable draft
registry) — no blockers, same shape as Windows's W02, straightforward to port next. Coordination
note: `tests/voice_messages/check_contract.py`, `manifest.json`, `generate_wav_cases.py` and
`wav_cases.bin` are the *other* agent's (already-committed) work — read-only reference material
now, not to be modified as part of Android Phase 1 execution unless a genuine I01-I04 contract
bug is found (none found so far; AT01 passing 38/38 on the first try is strong evidence the
contract and the Windows-side fixture corrections are solid).

`android/STATUS.md` not yet touched — correctly deferred to A11, matching how `windows/STATUS.md`
was only updated at W11, not incrementally.
