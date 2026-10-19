# Android compose row: Messenger-style icons, camera photo+video, gallery photo+video

Platform: **Android only** (UI + attachment-picking change; no wire/storage change expected).

## Current state (`android/src/net/lanmsg/chat/`)

The compose row (`MainActivity.java`'s `showChat`, around the `composeActions` block) is five
text buttons in a horizontal scroll: **Camera**, **Photo**, **File**, **Fast file**, then 🎤
(already an icon), then a separate **Send** text button.

- **Camera** → `AttachmentFlow.capturePhoto`: `MediaStore.ACTION_IMAGE_CAPTURE` only. No video
  capture exists anywhere in the app (no `ACTION_VIDEO_CAPTURE` usage).
- **Photo** → `AttachmentFlow.pickFile(activity, true)`: `ACTION_OPEN_DOCUMENT` with
  `setType("image/*")` — videos are not selectable from this picker at all.
- **File** → `pickFile(activity, false)`: `setType("*/*")` — picks anything, including video
  today, but as a plain file (this is actually how the video from the previous session's test was
  sent), not through a media-first picker.
- **Fast file**: unrelated large/unencrypted transfer mode, out of scope here.
- 🎤: voice recording, out of scope here.

## What the user asked for (reference: a Messenger conversation screenshot)

1. Restyle the compose row as icons, Messenger-style, not text-labeled buttons.
2. Tapping the camera icon lets the user **record a video or take a photo** (today: photo only).
3. Tapping the gallery/image icon lets the user **pick a photo or a video to upload** (today:
   photo only).

Messenger's own camera is a full custom in-app capture screen (live preview, hold-to-record /
tap-to-photo, flip camera, flash). Rebuilding that is a much larger project than this request's
plain-language ask. The proposed approach instead reuses Android's own system camera via two
existing-shaped intents (`ACTION_IMAGE_CAPTURE`, new `ACTION_VIDEO_CAPTURE`), offering a small
"Photo / Video" choice before launching one — same end result (camera opens, user records or
shoots, comes back with a file), far less work and no new permissions beyond what photo capture
already has. Flagged as an open question below in case a true in-app camera screen is actually
wanted.

## Tasks

| # | Task | Depends on | Status |
|---|---|---|---|
| C01 | Add video capture: `AttachmentFlow.captureVideo`, mirroring `capturePhoto` but `MediaStore.ACTION_VIDEO_CAPTURE`, a new request code (48), and `onActivityResult` branch writing to `pendingAttachment*` the same way the photo-capture result does today. | — | **Done** |
| C02 | Camera icon shows a small chooser ("Take photo" / "Record video") before launching `capturePhoto` or `captureVideo`. | C01 | **Done** |
| C03 | Gallery/image icon: widen `pickFile`'s photo mode to accept video too (`ACTION_OPEN_DOCUMENT` with `EXTRA_MIME_TYPES = {"image/*","video/*"}`, `setType("*/*")`), so one picker returns either, same as Messenger's single "Gallery" entry. | — | **Done** |
| C04 | Restyle the compose row: replace the five text buttons with an icon row. Mapped as: a `+` icon folding in **File** and **Fast file** (a popup menu), a 📷 camera icon (C02), a 🖼 gallery icon (C03), and the existing 🎤 unchanged. New `composeIcon` helper (neutral light-gray circle, since `dotButton`'s translucent-on-dark style is tuned for the call screen). | C01, C02, C03 | **Done** |
| C05 | Send becomes an icon: paper-plane (➤) when there's content ready, thumbs-up (👍) otherwise — confirmed with the user. | C04 | **Done** |

## Open questions for the user before execution

1. **Camera**: is the "choose Photo or Video, then launch the system camera for that" approach
   (described above) acceptable, or is a genuine in-app camera screen (live preview, single
   shutter control, hold-to-record) actually wanted? The latter is a substantially bigger build
   (camera preview surface, permission flow beyond what exists today, its own recording pipeline)
   and would need its own plan if so.
2. **Send button**: keep it as-is, or also convert to an icon (paper-plane, greying/filling based
   on whether there's content, matching Messenger's thumbs-up-when-empty behavior)?
3. **The "+" menu**: Messenger's `+` typically offers several more entries (camera roll, GIFs,
   stickers, location, contact, polls...). This app only has File and Fast file to fold into it —
   confirm that's all that belongs there, nothing new is being asked for beyond relocating those
   two.
4. Any preference on which emoji/glyphs to use for camera (📷) and gallery (🖼) — matching the
   call screen's existing glyph-based icon style (🔈🎤📞 etc.), or something else?

## Acceptance criteria

- Compose row shows icon buttons instead of text-labeled ones, visually consistent with the
  existing call-screen icon style.
- Tapping the camera icon offers Photo or Video, and both actually launch and attach correctly
  (same `pendingAttachment*` → Send pipeline as today).
- Tapping the gallery icon's picker shows both photos and videos and either can be selected and
  attached.
- File and Fast file remain reachable (wherever C04 relocates them) and unaffected in behavior.
- No wire, storage, or Windows change.

## Testing tasks

- CT01: Manual on-device acceptance — record a video via the camera icon and send it; take a
  photo via the camera icon and send it; pick an existing video from the gallery icon and send it;
  pick an existing photo from the gallery icon and send it. Confirm each renders correctly through
  the thumbnail/play-disc rendering from the prior media-preview work.
- CT02: Confirm File and Fast file still work from their relocated entry point.
- CT03: Confirm no regression to voice recording (🎤) or Send.

## Confirmed with the user (2026-10-02)

1. Camera: system camera with a Photo/Video chooser first (not a custom in-app camera screen).
2. Send becomes an icon too (paper-plane / thumbs-up when empty), matching Messenger.
3. File and Fast file fold into a "+" icon's popup menu.

## Implementation record (2026-10-02)

Implemented in `android/src/net/lanmsg/chat/`:
- `AttachmentFlow.java`: new `captureVideo` (mirrors `capturePhoto`, `ACTION_VIDEO_CAPTURE`,
  request code 48); new `chooseCameraMode` (an `AlertDialog` with "Take photo"/"Record video",
  dispatching to `capturePhoto`/`captureVideo`); `pickFile`'s `photo` parameter now means "media"
  — `ACTION_OPEN_DOCUMENT` with `EXTRA_MIME_TYPES = {"image/*","video/*"}` instead of a single
  `image/*` type, so one picker returns either.
- `CameraAttachmentProvider.java`: the filename regex and `getType` now accept both `.jpg`
  (photo) and `.mp4` (video) capture targets, returning the correct MIME type for each — the
  provider is keyed only by this app's own generated cache filename, never anything the camera
  app supplies, so widening the accepted extension set doesn't widen what it will serve.
- `MainActivity.java`: `onActivityResult` handles request 48 alongside 44 (same cleanup-on-cancel
  and cleanup-on-error paths, extended to both); the compose row is now a `composeIcon`-styled
  icon row (`+`, 📷, 🖼, 🎤) instead of text buttons, with `+` opening a `PopupMenu` for File /
  Fast file; Send is now `dotButton("➤", ..., true, 48)`, with a new `refreshSendIcon()` method
  swapping the glyph between ➤ (something ready to send) and 👍 (empty composer, no pending
  attachment) — called from the composer's `TextWatcher`, from `AttachmentFlow.renderPendingAttachment`/
  `clearPendingAttachment`, and from `render()`'s per-second refresh (which previously had its own
  hardcoded `send.setText("Send")` that would have silently overwritten the icon every render
  pass — found and fixed during this work, not a pre-existing separate bug report).

No wire, storage, or Windows change. No new permissions: `RECORD_AUDIO` and the camera feature
declaration already existed (for voice messages/calls and photo capture respectively), and the
system camera app handles its own audio-recording permission for video capture, same as it
already handled camera access for photo capture.

**Verification:**
- Real javac compile of all 42 production source files against `android.jar` + the WebRTC AAR:
  0 errors (caught the `render()` icon-overwrite bug this way on the first pass, before any
  on-device test — the compile itself doesn't catch it, this was found by observing the device
  screenshot show "Send" text instead of the glyph).
- Full `build-voice.ps1` pipeline succeeded twice (once before, once after the render() fix),
  `apksigner verify` confirmed v2+v3 both times.
- **On-device acceptance (one device, since this is peer-independent UI/OS-intent behavior, not
  a wire/interop concern):**
  - Compose row renders as four neutral gray circular icons plus a green circular Send, matching
    the user's Messenger reference.
  - Send shows 👍 with an empty composer and no attachment; typing text flips it to ➤ live, with
    no re-render delay.
  - `+` opens the popup menu with File / Fast file.
  - Camera icon opens an AlertDialog with "Take photo" / "Record video". "Take photo" opens the
    system camera in photo mode (regression-checked, still works). "Record video" opens the
    system camera in video mode (red record button, FHD 30 indicator, no shutter button) —
    recorded a real ~3-second clip, confirmed it, and it attached as
    `Video-20261002-183516.mp4 · 3.9 MB · Ready to send`, with the send icon correctly flipping
    to ➤. Sent it and it rendered as a real first-frame thumbnail with the play-disc overlay
    from the prior media-preview work — no regression, full pipeline integration confirmed.
  - Gallery icon's picker shows both "Images" and "Videos" category chips (previously
    video-only picks weren't possible at all from this entry point).
