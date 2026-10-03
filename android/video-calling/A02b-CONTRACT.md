# A02b — confirmed shared video contract, 2026-10-03

Platform: Both. Frozen plan-v007/hash unchanged. This is its authorized A02b
decision, not a replacement plan. Codec: VP8 only. Mechanism: separate video-only
ICE/DTLS-SRTP PeerConnection; audio remains on its original G722 connection.
Paired generated motion/encoded/decoded/RTP evidence in
../../windows/video-calling/PAIRED-FEASIBILITY.md proves both call/upgrade directions
and audio continuity through video failure/disposal. Single-PC reoffer rejected
because tested rollback stopped audio. Inactive-m-line alternative not selected.
The user explicitly accepts audio testing complete; do not repeat listening.
Physical-camera, production-controller, packaged UI and release tests stay open.

## Capability and compatibility

Fresh authenticated verified-peer transaction per outgoing call: request
`LM4\tCALLCAPS`; exact response `LM4\tCALLCAPS\t2\tVP8` within 10 seconds,
at most 128 UTF-8 bytes. Missing/invalid/timeout/unverified/legacy response means
v1 voice only. SimulateLegacyBuild suppresses CALLCAPS. Group CAPS=2/its >=2
consumers and FILECAPS remain unchanged. No success cached across calls.
Use v2 only after successful fresh capability exchange; v2 audio invitations
permit later consensual video. Old peers receive original v1 INVITE/SDP/ICE only,
with neither video fields, m-lines, capability-driven camera prompt nor capture.

## Envelope and exact bodies

Retain four-byte big-endian length and v/t/cid/seq/gen/b. Maximum frame 64 KiB,
SDP 48 KiB UTF-8, ICE candidate 4 KiB, 128 ICE per connection generation.
Canonical UUID cid/request; integral signed-64 seq/gen/revision only. seq begins
at 1 and increases across all messages within this authenticated call. Reject
foreign peer/cid/version before heartbeat or media work; ignore duplicates/stale
frames without resetting deadlines. Do not reuse exhausted counters.

| Type | Exact v2 body keys |
|---|---|
| INVITE | caller, callee, media (audio/video); gen=0 |
| ACCEPT | media (audio/video); gen=0; audio answer cancels initial video |
| VIDEO_REQUEST / VIDEO_ACCEPT / VIDEO_DECLINE | request UUID; gen=0 |
| OFFER / ANSWER | media, sdp; plus request UUID only for video |
| ICE | media, candidate, sdpMid, sdpMLineIndex; plus request UUID only for video |
| VIDEO_STATE | request, camera boolean, revision; active video gen |
| MEDIA_READY | media; plus request UUID only for video |
| ERROR | media, code; plus request UUID only for video; code=failed/timeout/unsupported |
| RINGING / DECLINE / BUSY / CANCEL / HANGUP / PING / PONG | empty body |

No additional keys. caller/callee must equal authenticated roles. Audio SDP
has only one audio m-line, no video/application m-line; video SDP has only one
video m-line and no audio/application m-line, VP8/90000, fingerprint and secure
UDP/TLS/RTP/SAVPF transport. Native parsers still validate complete SDP/profile.
No application data channel, STUN service or TURN. sdpMid 1..64 characters,
sdpMLineIndex=0 (one m-line per connection); bounded candidate has candidate:
prefix. VIDEO_STATE is informational, never a command to open a camera.

## Consent, binding, generation and recovery

Original caller is the sole SDP offerer for both connections. v2 audio gen=1;
video gen>=2 is allocated strictly monotonically by that caller, never reused.
Each video SDP/ICE/ready/state/error is bound to the same authenticated call
channel, verified peer, cid, accepted request UUID and exact active generation.
Authenticated SDP fingerprints bind the independent secured video transport.
Do not admit any Connected-state SDP to the audio connection.

Video INVITE requests, not commands, video. Answer with voice remains audio.
Initial both-video acceptance creates a fresh request UUID equal to cid; establish
audio first, then video. In-call VIDEO_REQUEST waits for explicit local acceptance;
permission and incoming frames alone cannot authorize local capture. Require
local action, bilateral consent, current camera permission, available camera,
foreground eligibility and authorized media setup on every actual capture start.
Receive-only mode is not selected; either side can subsequently turn its camera
off without withdrawing authorization for remote video or muting audio.

One outstanding request/negotiation. Simultaneous requests: lower UUID wins;
At most 128 accepted/proposed video request UUIDs per call; remember retired IDs
until hangup, reject reuse, then refuse further video requests without ending voice.
decline the loser and bind replies to the winner. Explicit local consent to a
losing request is not silently transferred to a different request. Both sides
can accept the winning request with a new explicit action. Request timeout 30s;
video negotiation timeout 15s. Expired/declined request is invalidated. Active
video generation is invalidated on failure; dispose only video, leave healthy
audio connected. Retry requires a fresh bilateral request/new generation.
ERROR media=video is recoverable; audio ERROR retains fatal voice behavior.
Late answers/ICE/callbacks and stale permission results cannot resurrect video.
Increasing VIDEO_STATE revision communicates camera off/on/switch; reject stale
revision. Camera switch keeps the track and cannot acquire a camera while off.

Self-view placement and diagnostics are local only, no new wire fields. R05
Windows production replacement remains separately authorized/in progress;
the completed media feasibility/contract gate permits Android A04 onward.
Post-gate AT01 fixtures must verify this contract before A05/A06 production media.
