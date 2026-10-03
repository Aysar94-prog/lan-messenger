# AP01 Android libwebrtc feasibility endpoint

Test package: `net.lanmsg.chat.videofeasibility`. Uses the cached
`io.github.webrtc-sdk:android:150.7871.01` AAR. Source is outside `android/src`,
and neither `android/build-voice.ps1` nor `tests/run.ps1` discovers it.
Build/signing outputs and the generated throwaway key stay under
`D:\LAN-Messenger\outputs\.build\video-feasibility`. No `.private` input is read.

Explicit build: `tests/video-feasibility/android/build.ps1`.
Install its returned `TEST_APK` using **USB adb**, e.g. `adb -d install -r <path>`.
Grant only the test package microphone permission before audio tests:
`adb -d shell pm grant net.lanmsg.chat.videofeasibility android.permission.RECORD_AUDIO`.
Camera permission is optional and needed only for `-Source camera`.
Keep the test Activity foreground. It releases media when backgrounded or stopped.
Failures release media; failure confined to video-only node b releases b and
preserves audio node a. Other failures still release all nodes.
Test installation does not replace or access
`net.lanmsg.chat`; record its version/install timestamps before and after.
The Activity requires the shell's `android.permission.DUMP`; start it through adb,
not a launcher icon. Ordinary apps cannot send test commands to the endpoint.

## USB control and LAN transport

Pass the actual USB serial returned by `adb devices -l` to `command.ps1 -Serial`.
There is no adb port-forward, media file streaming or TURN dependency. Full SDP
with gathered LAN host candidates is exchanged via adb-controlled commands/files;
actual encoded RTP travels over ICE + DTLS-SRTP between the peer connections.
SDP files contain test network addresses/fingerprints and belong only in local
test evidence, not status reports or production diagnostics.

Commands are `init`, `offer`, `answer -SdpFile`, `remote -SdpFile`, `upgrade`,
`activate`, `stats`, `camera-off`, `stop-node`, `stop`, and `loopback`. Endpoint `a` or `b`
allows two independent secured peer connections for loopback or fallback trials.
Input is bounded to 48 KiB SDP and 64-character request names. Complete JSON
responses are atomically written under the test app's private files directory;
the command runner polls the matching request for up to 60 seconds.
Use `-Summary` for compact loopback output; full counter snapshots still go to the
matching evidence JSON file.

`init` accepts `-Mode audio|inactive|video|video-only`, `-Codec VP8|VP9|H264`,
optional `-Profile` (VP9 profile-id or H264 profile-level-id), and
`-Source generated|camera`. Codec preferences are applied to real transceivers;
an unavailable profile fails explicitly. Audio selects G722. `init -Mode audio`
and `inactive` create no video source and acquire no camera. Initial video or
upgrade is an explicit test command. Generated I420 motion defaults to 320×240,
15 fps. `camera-off` stops capture independently of audio.
`video-only` creates no audio source/track and rejects audio SDP. Use node b for
isolated video alongside audio a; `stop-node` tears down only its selected node.

Latest actual paired generated tests and rejected single-PC rollback are recorded
in windows/video-calling/PAIRED-FEASIBILITY.md. Explicit Windows pair-android.py
--mechanism separate is the accepted feasibility recipe, not production signaling.

## Local native smoke test

Run `command.ps1 -Serial <USB serial> -Command loopback -Codec VP8 -Mode audio`.
It establishes two audio connections, snapshots both directions, adds video by
re-offer, and checks packets sent/received, frames encoded/decoded, decoded sink
frames and changing decoded luminance. It then stops video and verifies audio
counters continue. `-Mode inactive` trials an inactive setup m-line and later
activation. Repeat with candidate codecs/profiles and after stopping/reinitializing.
These are Android-local generated-frame results; they do not prove Windows
interoperability, audible voice continuity or real-camera acceptance.

## WT01 Windows pairing recipe

1. Initialize Android node a as audio, with the candidate codec/profile. Obtain
   a complete offer with `offer` and pass its `.sdp` file to the separate Windows
   prototype. Apply the Windows answer with `remote -SdpFile`.
2. Record Android `stats` and Windows RTP/audio baseline counters. Use
   `upgrade` to produce a video re-offer; Windows answers without replacing the
   healthy audio connection. Apply answer and record decoded motion, actual
   negotiated codec and continuing audio in both directions.
3. Reverse the offerer: initialize Android a, give Windows's audio offer to
   `answer -SdpFile`, then give its answer to Windows. A Windows re-offer is
   processed by `answer` again; the existing audio peer connection stays alive.
4. Repeat `-Mode inactive` + `activate`, codec/profile candidates, declined or
   failed video, and camera-off. Node b can host a separate secured video peer
   connection alongside a, if that fallback is needed. Binding that connection
   to production authenticated call identity must be designed at A02b.
5. Record Android stats (RTP/codec/frames) and Windows evidence together. Do not
   mark A02b passed on SDP acceptance or Android loopback alone.

The endpoint does not implement production invitations, verification storage,
CALLCAPS or consent UI. It supplies the native media evidence required by A02b.
Do not copy this test code into the production media adapter.
