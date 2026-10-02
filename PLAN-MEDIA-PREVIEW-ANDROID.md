# Android media preview cleanup (photo/video) — plan

Platform: **Android only** (UI-only change; no wire/storage format change expected).

## Problem (confirmed by device screenshot + source read, 2026-10-02)

A received image attachment currently renders as:
1. An inline thumbnail (correct, tap opens `AttachmentFlow.previewImage`).
2. A generic file card underneath it anyway: filename + size line, then a button row
   with the normal "Open"/"Download" action **plus a second, redundant "Open" button**
   that also calls `previewImage`.

Root cause (`MainActivity.render()`, `D:/LAN-Messenger/source/android/src/net/lanmsg/chat/MainActivity.java:350`,
helpers `inlineBitmap` at 371/373, `PeerEngine.isImageAttachment` at `PeerEngine.java:478`):
there is one generic attachment card. It always renders the filename/size line and the
primary action button. When a thumbnail decoded successfully, the same code additionally
bolts on a second "Open" button onto the same row instead of replacing the generic card.
This is not two rendering passes racing each other — it's one path with an un-removed
extra button.

Video has no equivalent at all: `isImageAttachment` only whitelists image extensions, there
is no `isVideoAttachment`/thumbnail/first-frame extraction anywhere in the chat package, so a
video attachment always renders as the plain generic file card (filename, size, one button),
with no preview.

There is no state-machine concept for image/video cards today (ad hoc `thumbnail!=null` /
`hasAttachment`/`downloading`/`pendingDestination` checks inline). Voice messages already have
this pattern (`VoiceCard.java`: `VoiceMarker.classify()` → `CANDIDATE`/`FETCHING`/`PLAYABLE`/
`INVALID`/`UNAVAILABLE`, one switch per state) — worth mirroring for consistency, not required.

## Target behavior (user's request, Messenger-style)

- A photo (or, once added, a video) attachment that has a usable preview shows **only the
  thumbnail** — no filename/size line, no button row underneath it.
- Tapping the thumbnail opens a fullscreen preview (`AttachmentFlow.previewImage` already
  exists and does this for images) with a single down-arrow control to download/save.
- A video attachment gets a first-frame thumbnail the same way, with a small play-icon
  overlay (so it's visually distinguishable from a photo), and tapping it opens the same
  kind of fullscreen view with a download arrow (playback itself is out of scope unless the
  user asks for it — confirm before building an in-app video player).
- When no preview is available yet (not yet downloaded, over the inline-preview size cap,
  unsupported type, or fetch failed) — the existing generic file card (filename, size,
  Open/Download/Retry) is the right fallback and should be kept as-is for that case.

## Tasks

| # | Task | Depends on | Status |
|---|---|---|---|
| M01 | Remove the duplicate "Open" button: when a thumbnail renders successfully, suppress the filename/size line and the generic button row entirely for that message — single thumbnail view only, `previewImage` on tap. | — | **Done** |
| M02 | Add a visible, obvious "download" affordance on the fullscreen preview (`AttachmentFlow.previewImage`'s view) — a down-arrow control, matching the user's Messenger reference. | M01 | **Done** |
| M03 | Add video-attachment recognition: `PeerEngine.isVideoAttachment` whitelisting common video extensions. | — | **Done** |
| M04 | Add a first-frame thumbnail extraction for video (`MediaMetadataRetriever.getFrameAtTime`), with a Messenger-style play-disc overlay on top of the frame. | M03 | **Done** |
| M05 | Route a video thumbnail through the same single-thumbnail-only rendering path as M01. Single tap plays the video inline in place (`VideoView`, replacing the thumbnail while playing, restoring it on completion/error); double tap opens the same fullscreen/download view as a photo (new `AttachmentFlow.previewVideo`, since a video's bytes are never valid image bytes for the photo path). | M01, M04 | **Done** |
| M06 | (Optional, consistency) Extract an `ImageMarker`/`MediaMarker`-style classify() helper mirroring `VoiceMarker`. | M01, M05 | Not done — skipped; the implemented version (`MediaCard.java`) is a focused rendering helper, not a full state machine, and stayed small enough that the extra abstraction wasn't worth it. Can be revisited if a Candidate/Fetching/Unavailable concept is later wanted for media the way it exists for voice. |

## Implementation record (2026-10-02)

Implemented in `android/src/net/lanmsg/chat/`:
- `PeerEngine.java`: added `isVideoAttachment(Message)` (receive-side extension classification only, mirroring `isImageAttachment`).
- `MediaCard.java` (new): `addImageCard`/`addVideoCard` — single-thumbnail rendering, video first-frame extraction via a one-time decrypt-to-cache-file (`decryptedVideoFile`, reused by both the thumbnail and inline playback so a tap-then-play never decrypts twice), a `GestureDetector` distinguishing single tap (inline `VideoView` playback, auto-restoring the thumbnail+play-disc on completion/error) from double tap (fullscreen).
- `AttachmentFlow.java`: `previewImage`'s dialog gained a neutral "⬇ Download" button wired to the existing `exportFile`; added `previewVideo` (same shape, using the already-extracted/cached frame rather than attempting to decode video bytes as an image).
- `MainActivity.java`: the non-voice attachment branch of `render()` now dispatches to `MediaCard.addImageCard`/`addVideoCard` when a preview exists, and keeps the original generic file card (filename, size, Open/Download/Resume) only as the no-preview fallback — the duplicate "Open" button is gone because that card is no longer built at all once a thumbnail/frame exists.

No wire, storage, or Windows change. No size cap on video thumbnail generation (per the user) since frame extraction streams the decrypted file to disk rather than holding it in memory, unlike the image path's `THUMBNAIL_PREVIEW_CAP`.

**Verification:**
- Real javac compile of all 42 production source files against `android.jar` + the cached WebRTC AAR: 0 errors.
- Full `build-voice.ps1` pipeline (javac → d8 → aapt → zipalign → apksigner): succeeded, `apksigner verify` confirmed v2+v3, one signer. Built as a dev artifact, `LanMessenger-2.2.39-media-preview-dev.apk`, not a numbered release (no version bump for a two-device validation build).
- **Two-device on-hardware acceptance, both directions:**
  - Existing photo history (both an old message and the one from the original bug report) renders thumbnail-only, no filename/size line, no button row, on both devices.
  - Tap opens fullscreen with exactly **↓ DOWNLOAD** / **CLOSE** (confirmed the Download button actually opens the save-destination picker).
  - A real video file (3.8 MB `.mp4`, sent as a plain file attachment, since there is no video-capture flow) sent device-to-device: sender's own copy renders thumbnail+play-disc immediately (mirroring the voice-message "mine" precedent); receiver shows the correct **generic file card with Download** before retrieval (no broken preview attempt against an unavailable attachment), then switches to the same thumbnail+play-disc card once downloaded.
  - Single tap played the video inline on both devices (confirmed via `dumpsys audio` showing a real `net.lanmsg.chat` `AudioAttributes usage=USAGE_MEDIA/CONTENT_TYPE_MOVIE` focus request/release spanning the clip's duration, not just a UI state change) and cleanly restored the thumbnail afterward.
  - Double tap opened the fullscreen frame with filename, ↓ DOWNLOAD, and CLOSE, same as a photo.
- One transient ANR ("LAN Messenger isn't responding") was observed once, immediately after a heavy manual scroll-back through conversation history; it self-recovered and is consistent with the **pre-existing**, unrelated behavior that image thumbnail decoding in `render()` is synchronous on the UI thread (true before this change too) — not a regression introduced here, and not re-encountered on any later interaction in this session.

## Confirmed with the user (2026-10-02)

- There is no dedicated video-capture/send flow on Android. A video can only reach the chat
  today by being sent as a generic file attachment (the normal "File" picker), the same as
  any other non-image file. So M03/M04 is purely a **receive/render-side** change: recognize
  that an already-arrived file attachment's extension is a video type and give it a preview,
  not "add a way to send video" — no new sender UI, no new send-side picker, no wire change.

## Confirmed with the user (2026-10-02, cont.)

- Video tap behavior: a single tap on the video thumbnail **plays it** (inline, in place —
  not a fullscreen player); a **double tap** opens the same fullscreen view as a photo, with
  the download arrow. So the thumbnail needs two gesture handlers (single vs. double tap),
  not just the one `previewImage` click used for photos today.

## Confirmed with the user (2026-10-02, final)

- Real inline-in-row video playback (single tap) is wanted as-is — not a simplified
  static-thumbnail/fullscreen-only alternative. M05 stands as written.
- No size/type cap on video thumbnail generation.

No open questions remain. Awaiting the user's explicit go-ahead to start execution (M01).

## Acceptance criteria

- A received photo under the preview cap shows thumbnail only; no filename/size text, no
  button row, in the chat list.
- Tapping that thumbnail opens a fullscreen view with exactly one download control.
- A received video (once support exists) shows a thumbnail with a play-icon overlay only;
  same tap behavior as above.
- A photo/video with no local preview yet (still fetching, too large, failed) keeps the
  existing generic file card — unaffected by this change.
- No change to the wire protocol, storage format, or Windows code.

## Testing tasks

- MT01: Update/extend the existing Android UI test coverage for attachment rendering (if
  any pure-Java-testable rendering checks exist for this path — likely needs a new check
  similar in spirit to `VoiceMessagesCheck.java`, since `MainActivity` itself isn't
  loadable by the pure-Java harness per the precedent noted in `android/STATUS.md`'s
  `CallUi` extraction).
- MT02: Manual on-device acceptance — a two-device send/receive of a photo and (once added)
  a video, confirming: thumbnail-only rendering, fullscreen-with-download-arrow on tap,
  correct fallback to the generic card for an attachment still downloading.
- MT03: Confirm no regression to the "Automatic inline ordinary photos" existing behavior
  recorded in `PROJECT_STATUS.md` (size/codec limits, non-image files still get the generic
  card).

## Status tracking

This file is the authoritative small-task plan for this item. Update per-task status here
as work proceeds; reflect completed work plus device-acceptance results in
`android/STATUS.md` and the feature comparison in `PROJECT_STATUS.md` once implemented and
verified, per the project's working arrangement.

## Follow-up round (2026-10-02): draft preview, play/pause, automatic receive

Three gaps the user found immediately after using the shipped feature, all fixed in the same
session:

- **M07 (new): pre-send video draft preview.** The photo draft already showed a thumbnail before
  Send; a video draft did not (just filename/size/Remove). `AttachmentFlow.renderPendingAttachment`
  now extracts a first frame directly from the picked/captured content `Uri` via
  `MediaMetadataRetriever` (the draft is still a plain unencrypted file at this point — never the
  `MediaCard`/decrypt-then-extract path, which is for an already-sent attachment) and renders it
  with the same play-disc overlay, tappable to enlarge (new `AttachmentFlow.previewFrame`). Added
  `PeerEngine.isVideoFile(String)`, factored out of `isVideoAttachment(Message)`, since a draft
  isn't a `Message` yet. **Done**, verified on-device: recorded two different real videos via the
  camera icon, both showed a correct first-frame preview before Send, tap-to-enlarge worked, send
  completed normally.
- **M08 (new): inline playback needed real play/pause, not restart-on-tap.** The first version's
  single tap always called the same "build a fresh VideoView" path, so a second tap while already
  playing started a duplicate overlapping playback instead of pausing. Fixed with an explicit
  `IDLE → LOADING → PLAYING ↔ PAUSED` state machine (`MediaCard.InlineVideo`, `toggleInline`):
  tap while idle builds and starts a `VideoView`; tap while playing calls `pause()` and shows the
  play-disc again; tap while paused calls `start()` and hides it; a tap during `LOADING` is
  ignored (prevents a double-start race from impatient double-tapping during the decrypt step).
  Natural completion or an error still tears down to `IDLE` and rebuilds fresh next time (keeping
  the "never trust resuming a drained media object" lesson from the voice-message `AudioTrack`
  saga — but that lesson applies to *finished* playback, not to an in-progress pause, which a
  `VideoView`/`MediaPlayer` handles natively and reliably). **Done**, verified on-device: tap →
  played (frame advanced); tap → paused (froze, play-disc reappeared, confirmed frame genuinely
  static across a 2-second wait with no tap); tap → resumed from the same point (frame continued
  advancing, not a restart); let it finish naturally → cleanly restored to the original
  first-frame thumbnail, ready to replay from scratch.
- **M09 (new): a video needed to auto-download like a photo, not require a manual Download tap
  like a plain File/Fast-file attachment.** `TransferManager.queueAutomaticMedia`'s scheduler only
  ever considered `PeerEngine.isImageAttachment` (and voice) for the automatic-fetch pool; a video
  fell through to the same "nothing automatic, user must tap Download" treatment as an arbitrary
  file. Fixed with a one-line widening: `isImageAttachment(m)||isVideoAttachment(m)`, reusing the
  exact same `imageSlots`/`imageAttempts` pool, admission fairness against voice, and retry/offline
  rules a photo already had — a video is now indistinguishable from a photo to this scheduler.
  **Done**, verified on-device: a received video rendered as a clean thumbnail+play-disc with no
  Download button or filename/size line visible at all, matching how a photo already behaved,
  before the receiver's confirmation tap was interrupted by an unrelated ADB "incremental install"
  serving-session glitch on that device (`ResourcesManager: failed to load asset path ... base.apk`
  — an environment artifact, not an app exception; resolved by a clean uninstall/reinstall, not a
  code change).

All three: real javac compile (42 files, 0 errors) and a full `build-voice.ps1` build/sign/verify
pass, same as the rest of this feature. No wire, storage, or Windows change.

- **M10 (new): a time bar.** The inline video player had no duration/progress indication at all —
  just the frame and a play-disc. `MediaCard.addVideoCard` now wraps the frame in a vertical
  column with a WhatsApp/Messenger-style row underneath: an elapsed/duration label (`0:07 / 0:29`)
  and a draggable `SeekBar`, present even before the first tap (duration extracted the same way as
  the thumbnail, from the cached decrypted file — new `videoDurationMs`). While `PLAYING`, a
  self-rescheduling 300 ms poll (`tick`, posted via `activity.ui.postDelayed`) reads
  `VideoView.getCurrentPosition()` and updates both the bar and the label unless the user is mid-
  drag (`InlineVideo.dragging`, same "don't fight the user's finger" pattern `VoicePlayback`'s own
  seek bar already uses) — `VideoView` has no push-based position callback, unlike `VoicePlayer`,
  so polling is correct here, not a missed opportunity for something better. Dragging seeks the
  live `VideoView` via `seekTo(ms)` on release. Natural completion or error resets the bar to
  `0:00` alongside the existing thumbnail/play-disc restore. **Done**, verified on-device across a
  full play-through: label and bar advanced live tick-by-tick while playing (`0:00` → `0:01` →
  `0:27`...), and on natural completion both the frame and the time bar cleanly reset to
  `0:00 / 0:29`, ready to replay from the start.

- **M11 (new): the fullscreen (double-tap) view opened like a photo, not a video.**
  `AttachmentFlow.previewVideo` only ever showed a static decoded frame plus Download/Close —
  functionally indistinguishable from a photo's fullscreen view, which is exactly what the user
  flagged, since a video is expected to actually play there. Rebuilt to embed a real
  `android.widget.VideoView` (reusing the same cached decrypted file as inline playback, via
  `MediaCard.decryptedVideoFile`) with the standard Android `MediaController` overlay
  (play/pause/seek, tap-to-show) wired in, autoplaying on open, `stopPlayback()` on dismiss by any
  path (Close, Download, back, outside-tap). **Done**, verified on-device: opened the fullscreen
  view and took two screenshots two seconds apart — the frame had genuinely advanced between them
  (a blurry mid-pan frame, then a completely different room), and a third screenshot after tapping
  the video showed it had progressed further still, all the way to content matching the clip's
  final seconds — conclusive proof of real continuous playback, not a static picture.

Real javac compile (42 files, 0 errors) and a full signed `build-voice.ps1` pass for this fix too.
