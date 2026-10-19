# A02 — provisional video contract (not a production contract)

Approved plan: plan-v007, SHA-256
`4ea7877d6643b5c89246b075d7a301d73b38bea28f644303383fa42f7ca0efbf`.
Every choice below must be confirmed or revised at **Both A02b** after AP01/WT01.
The draft model and fixtures live in `tests/video-contract`; production handlers
and production compilation are unchanged by A02/AT01.

## Authenticated capability transaction

Use the existing TLS HELLO/certificate verification/READY transaction, then send
`LM4\tCALLCAPS`. Draft response: `LM4\tCALLCAPS\t2\tVP8,VP9,H264`.
Candidate names indicate possibilities for feasibility, not negotiated codecs.
Codec profiles, especially H264 profile-level-id and packetization-mode, remain
unselected. Do not advertise a candidate in production merely because libwebrtc
supports it. Draft bounds: 128 UTF-8 bytes, 1–3 distinct known codec tokens,
10-second deadline. Missing response, unverified channel, rejection, malformed
response, unknown version, duplicate/unknown codec, timeout or empty common codec
set selects version-1 audio. Do not cache a capability success across calls.
`SimulateLegacyBuild` suppresses CALLCAPS alongside its existing CAPS suppression
when production implements the token at A07/W03. The test-only model demonstrates
this suppression. Group `LM4\tCAPS\t2`, all `>=2` consumers and FILECAPS=STREAM1
remain independent.

## Draft version-2 envelope and states

Retain `v/t/cid/seq/gen/b`, four-byte big-endian UTF-8 length, 64 KiB frame,
48 KiB SDP and 128 ICE candidates per generation. Call IDs are canonical UUIDs;
sequences and generations are integral, nonnegative, within Java/C# signed-64 range;
sequences start at 1 and must increase strictly per authenticated call peer.
Bind every message to the verified peer, active call ID and selected version.
Do not let discarded duplicates reset timeouts or authorize camera actions.

| Message | Draft body / permission |
| --- | --- |
| INVITE | caller, callee, media=`video`; only mutually capable verified peers; generation 0 |
| ACCEPT | media=`video` or `audio`; audio selection never opens the camera |
| VIDEO_REQUEST | request UUID; audio stays Connected; one outstanding request |
| VIDEO_ACCEPT / VIDEO_DECLINE | exact outstanding request UUID; neither implies microphone mute |
| VIDEO_STATE | camera boolean, revision integer; increasing revision, no capture command |
| OFFER / ANSWER | SDP string; authorized generation > prior committed generation |
| ICE | candidate, sdpMid, integer sdpMLineIndex; exact active generation |

Draft request collision: lexicographically lower request UUID wins; reject/decline
the other without disturbing audio. The call's original caller is the draft SDP
offerer even when the callee requests upgrade, to prevent simultaneous re-offers.
Timeout/rejected codec/video-only errors return the proposal to audio-only and
invalidate its generation; late answers/ICE and permission results are ignored.
In-progress initial connection uses the existing voice offerer rule. A02b must
freeze body allowlists, rejection vs ignore, retry deadlines, counter exhaustion,
and recovery appropriate to the selected media mechanism.

Camera acquisition requires an explicit local action, present camera permission,
eligible foreground state, peer consent and authorized media setup. Initial
accept-as-audio and rejected upgrades keep camera stopped. Receive-only mode is
not selected. No diagnostics or local self-view fields enter signaling.

## Feasibility candidates (none selected)

1. Add video with a Connected-state re-offer and preserve existing G722 transport.
2. Negotiate inactive video at capable-peer setup, then activate after consent.
3. Separate DTLS-SRTP video peer connection bound to the authenticated call.

Try VP8, VP9 profiles and H264 profiles with generated motion before cameras.
Evidence must include negotiated codec, decoded frames and RTP counters in both
directions plus audio counters/observations before/during/after upgrade. Accepted
SDP alone is insufficient. Android loopback cannot satisfy Windows pairing.
If none works, revise the plan; do not implement an unproven production mechanism.

## Fixtures and acceptance

AT01 covers existing version-1 framing/admission, authenticated capability failure,
timeout/legacy suppression, accept-as-audio, declined upgrade, duplicate/stale
frames, wrong peer/call ID, unauthorized SDP and UTF-8 SDP limits. The independent
draft model is executable specification only; tests must be rerun against the final
contract and production controller after A02b/A07. It does not claim production
enforcement of the new rules.
