# Inline image previews — 0.4.2

Supported image attachments now appear directly inside their message bubbles on Windows and Android. The filename, size, delivery status and Save action remain visible. Clicking or tapping the thumbnail opens the existing larger viewer. Ordinary files stay as compact file cards and never try to open automatically.

Before sending, the picker or Android camera creates a local draft. No message or attachment is added to the durable queue until the sender presses Send. The draft supports a caption and removal. Android camera output is written through a narrowly scoped, temporary URI into the app cache, read into the draft, then deleted.

## Decoding and limits

The preview reads the attachment through the existing encrypted attachment store and decodes it only in memory. Android inspects dimensions first and subsamples images larger than 1000 × 800 for the inline view. Windows rejects images over 32 million pixels and creates a thumbnail no larger than 420 × 320. A corrupt or unsupported image falls back to the ordinary file card.

The Android activity explicitly releases inline bitmaps whenever the feed is rebuilt or the screen changes. Windows disposes each thumbnail when its message card is disposed. The modal Android viewer still decodes on a background thread and limits either dimension to 1600 pixels; the Windows viewer opens a detached bitmap on a dark zoom surface and closes with Escape.

## Supported behavior

- PNG, JPEG, GIF, WebP, BMP and other formats depend on the platform decoder.
- Animated formats show a static frame.
- SVG is treated as a normal file because neither client includes an SVG renderer.
- Previewing does not create a plaintext cache file. Choosing Save exports the original bytes to the location selected by the user.
- Clearing the conversation removes the local encrypted attachment, its card and its inline preview.

Physical Android acceptance testing is still required for camera orientation, unusual device codecs and long conversations containing many images.
