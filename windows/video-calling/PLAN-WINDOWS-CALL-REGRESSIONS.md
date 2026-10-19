# Windows call regressions after the v2 video enablement

Planning only. **No application code has been changed and nothing has been built or released.**
Diagnosis is complete and evidence-backed; the tasks below are awaiting an explicit instruction to
start execution.

## 1. What changed, and why every call now fails

The last update (uncommitted working tree, on top of local commit `902e92d`) made two changes that
together put **every** Windows call onto the previously-dormant v2 signalling path.

**Change 1 — `windows/ChatWindowCalls.cs:38-42`**

```csharp
if (LanMessenger.Windows.CallVideoNativeBridge.FindLatestBridgeDll() != null)
{
    callController.VideoMediaFactory = () => new LanMessenger.Windows.CallVideoNativeMedia();
    callController.VideoEnabled = true;
}
```

Before this, `VideoEnabled` was false everywhere. Because `CallController.StartVideoLocked`
(`windows/CallController.cs:304`) returns immediately when `videoConsent == null`, and `videoConsent`
was only ever assigned when `capable` was true (`:123`, `:172`), a large part of the v2 code — the
video coordinator, `videoActions`, video frame routing — was **structurally unreachable**. The
`CallVideoProtocol.Valid` check inside `SendFrameTo` (`:667`) was reachable but harmless, because
`protocolVersion` was always 1 (`:120`, `:166`) so `SendFrameTo` never entered the v2 branch.

**Change 2 — `windows/PeerEngine.cs:236-238`**

```csharp
// Outbound LM4 transactions speak first. The inbound Receive path reads our HELLO
// before it writes its own; waiting to read here deadlocked both peers until timeout.
await Write(tls, Hello());
var hello = (await Read(tls)).Split('\t');
```

This is a **correct fix**: every other outbound transaction in the tree already writes HELLO first
(`Conversations.cs:133`, `Avatars.cs:40`, `Transfers.cs:150`, `PeerEngine.cs:437`/`:453`,
`PeerEngine.Calls.cs:30`), and the inbound `Receive` reads first (`PeerEngine.cs:336`). It was never
noticed because `ProbeCallVideo` was never called while `VideoEnabled` was false.

**Combined effect:** the CALLCAPS probe now succeeds for the first time, so `capable` is true, so
`protocolVersion == 2`, so two latent v2 bugs that had never executed in production now run on every
call. Confirmed by the diagnostic log written by the new code
(`%LOCALAPPDATA%\LanMessenger\call-diagnostics.log`): `v2=True` on every single line.

## 2. Symptom A — Windows calls Android, the call closes immediately

### Evidence (UTC; machine local time is UTC+3)

```
18:38:21.961 state=OutgoingRinging caller=True v2=True video=none   end=none
18:38:22.056 state=Connecting       caller=True v2=True video=none   end=none
18:38:22.311 state=Connecting       caller=True v2=True video=Ended end=none
18:38:22.312 state=Ending           caller=True v2=True video=none   end=MediaError
```

The identical 5-line pattern occurred 4 times out of 4 attempts that got past ringing. The call dies
130–255 ms after `Connecting`, and the user sees the label "Call failed"
(`CallView.cs:240`, `MediaError => "Call failed"`).

`video=Ended` is a **consequence, not a cause**: `EndCallLocked` disposes video at
`CallController.cs:878` *before* it sets `Ending` at `:879`, and `CallVideoCoordinator.Dispose`
publishes a final snapshot synchronously, so that intermediate line still reads `State=Connecting`.
Its existence proves a coordinator existed, which proves `StartMediaLocked()` completed, which
proves the throw happened **inside** the `try` at `:538-543` — not at `:535`.

### Root cause (proven by execution)

`windows/CallVideoProtocol.cs:158`

```csharp
if (section[2] != "UDP/TLS/RTP/SAVPF") return false;
```

SIPSorcery 10.0.17 — the stack that produces the real audio OFFER — emits the shorter DTLS/SRTP
profile name:

```
m=audio 9 UDP/TLS/RTP/SAVP 9 0 8 18 117 118 101
a=rtpmap:9 G722/8000
a=fingerprint:sha-256 2E:41:…:D7:8D          (32 pairs)
```

Every other clause of `ValidSdp(sdp, video:false)` passes on this exact SDP. Substituting
`SAVP` → `SAVPF` and nothing else makes it valid. Reproduction with the real production classes
linked in (driver built outside the repository; no repo file touched):

```
CallVideoProtocol.ValidSdp(real SIPSorcery audio OFFER, video:false) = False
CallVideoProtocol.ValidSdp(same, SAVP -> SAVPF, video:false)         = True
```

Single-variable run of the whole controller, changing only `SAVP`→`SAVPF`:

```
with SAVP :  state=Connecting -> video=Ended -> state=Ending end=MediaError   (offer never reaches the wire)
with SAVPF:  state=Connecting                                                    (OFFER v2 seq=2 gen=1 keys=[sdp,media] sent)
```

The chain is therefore:

| Step | Location |
| --- | --- |
| `WebRtcCallMedia.CreateOfferAsync` returns a valid SIPSorcery offer | `WebRtcCallMedia.cs:99-104` |
| `CallController.cs:542` sends it as a v2 `OFFER` | `SendFrameTo` |
| `CallVideoProtocol.Valid` → `ValidSdp` rejects `UDP/TLS/RTP/SAVP` | `CallVideoProtocol.cs:158` |
| `SendFrameTo` throws `IOException("Refusing to send an invalid call frame")` | `CallController.cs:667` |
| bare `catch { }` → `HANGUP` + `EndCallLocked(MediaError)` | `CallController.cs:544` |

The exception object is discarded entirely. Nothing is logged, nothing is shown. That silent `catch`
is why this looked like a mystery instead of a one-line validator mismatch.

## 3. Symptom B — Android calls Windows, cannot answer, cannot end

### Evidence

```
19:08:44.852 state=IncomingRinging caller=False v2=True video=none end=none
19:09:00.854 state=Ending           caller=False v2=True video=none end=LocalHangup
19:09:08.617 state=IncomingRinging caller=False v2=True video=none end=none
19:09:21.162 state=Ending           caller=False v2=True video=none end=Canceled
```

There is **no `Connecting` line**, and there never was. Note that `EndReason.LocalHangup` has exactly
one producer in the whole tree (`CallController.cs:257`, reached only from
`ChatWindowCalls.cs:174` `view.HangupClicked`), and `CallView.cs:218` hides the Hang-up button while
ringing — so the window the user was looking at cannot have been a healthy incoming-call window.

### Root cause (proven by execution)

`windows/CallSignaling.cs:207-208` and `:217`

```csharp
static CallProtocol.Frame Make(string type, string callId, long seq, long gen = 0) =>
    new() { T = type, Cid = Cid, Seq = seq, Gen = gen };      // <-- no B

public static CallProtocol.Frame Accept(string callId, long seq) => Make(CallProtocol.ACCEPT, callId, seq);
```

`CallProtocol.Frame.B` is nullable (`CallProtocol.cs:60`), and `Accept` is the only builder that
leaves it null. But `CallController.cs:222` assumes otherwise:

```csharp
var accept = CallSignaling.Accept(callId, ++sequence);
if (protocolVersion == 2) accept.B!["media"] = wantVideo ? "video" : "audio";
```

Runtime, driving the exact UI action from `ChatWindowCalls.cs:150`:

```
AcceptAsync() faulted: System.NullReferenceException
   at LanMessenger.CallController.AcceptAsync(Boolean video, Boolean trusted) in …\CallController.cs:line 222
frames the callee put on the wire: RINGING v2 seq=1 gen=0 keys=[]
```

The null-forgiving `!` is the whole bug: every reader in the tree is written
`frame.B != null && …`, so the omission is invisible everywhere else.

The throw lands **after** `session.State = Connecting` (`:218`) and **before** `NotifyCallback`
(`:226`), which skips everything in between:

| Line | Skipped | Consequence |
| --- | --- | --- |
| `:223` | `SendFrameTo(t, accept)` | **no ACCEPT on the wire** — Android never sends an OFFER |
| `:224` | `ScheduleMediaTimeout()` | no media watchdog |
| `:225` | `StartHeartbeat()` | no heartbeat |
| `:226` | `NotifyCallback(...)` | **no snapshot, no redraw, no `Connecting` diagnostic line** |
| `:228` | `await StartMediaAsCalleeAsync()` | no media at all |

`ChatWindowCalls.cs:150` is `_ = callController.AcceptAsync();` — fire-and-forget, so the
`NullReferenceException` is discarded with no `TaskScheduler.UnobservedTaskException` subscriber
anywhere in the tree. The call is left **alive, connecting, with all three watchdogs cancelled and
no way for either peer to progress.**

What the user then sees: the 1 Hz UI tick (`Program.cs:141` → `ChatWindowCalls.cs:101-109`)
re-renders from the *live* controller state via `Snapshot()`, not from the last published snapshot.
Within one second the window flips itself from "Incoming call / Accept / Decline" to "Connecting…"
with a **Hang up** button (`CallView.cs:270`, `:218`). That is why the hang-up button was reachable
at all, and why pressing it produced `end=LocalHangup` 16 s later.

The same defect exists at `CallController.cs:783` (`SendAnswerLocked`, the "Accept with video"
path) — verified:

```
AnswerVideo threw: System.NullReferenceException
   at …CallController.SendAnswerLocked(String expectedCallId, Boolean video) in …\CallController.cs:line 783
   at …CallController.VideoEffects.Answer(String callId, Boolean video) in …\CallController.cs:line 737
```

`CameraEligible = _ => false` (`ChatWindowCalls.cs:43`) currently masks this second site, because
`CallVideoConsent.AcceptInitial` returns `Denied` before line 783 is reached.

### Why Android never saw this

`android/.../CallProtocol.java:117` allocates `body` eagerly in the constructor:

```java
this.body = new LinkedHashMap<>();
```

Windows makes it nullable and never allocates it in `Make`. The two platforms silently disagree,
and the divergence only bites where Windows uses `!`.

## 4. Why the automated suite stayed green

| Gap | Location |
| --- | --- |
| The fake media adapter hardcodes `SAVPF`, so no test ever validates a real SIPSorcery offer | `windows/FakeCallVideoMedia.cs:221` |
| The shared cross-platform frame corpus contains **only** `SAVPF` m-lines — zero `SAVP` cases | `tests/video-contract/fixtures/call-frames.txt` |
| `ICallMedia` is an interface, so `WebRtcCallMedia` is never exercised by the harness | `tests/CsharpHarness/CallVideoCheck.cs:852-854` |
| No WinForms UI test exists for calls, so no test presses Accept | `tests/WindowsUi` |

324/324 call-video checks pass against fixtures that cannot express either failure.

## 5. Task plan

Platform: **Both** where the shared v2 validator corpus is involved, **Windows** otherwise.
Nothing here is Android-app work beyond the one mirrored validator line.

| ID | Task | Platform | Status | Depends on | Acceptance criterion |
| --- | --- | --- | --- | --- | --- |
| T1 | Accept `UDP/TLS/RTP/SAVP` in `CallVideoProtocol.ValidSdp` **in addition to** `SAVPF` (`CallVideoProtocol.cs:158`), and mirror the same change in `android/src/net/lanmsg/chat/CallVideoProtocol.java` in the same commit — the port-parity warning at `CallVideoProtocol.cs:10-14` requires it. Accept the exact two names only; a prefix match would wrongly admit `TCP/TLS/RTP/SAVPF`, which `ConfirmedVideoContractCheck.java` asserts must stay rejected. | Both | Pending | — | A v2 audio `OFFER` carrying `UDP/TLS/RTP/SAVP` is admitted by both implementations and produces identical verdicts on the shared corpus. |
| T2 | Add `SAVP` audio and video cases to `tests/video-contract/fixtures/call-frames.txt`, and add a `CallVideoCheck` case that runs the **real** `WebRtcCallMedia.CreateOfferAsync()` output through `CallController.SendFrameTo` so a production SDP is validated in CI, not on a user's machine. | Both | Pending | T1 | The shared corpus contains `SAVP` rows and both platforms agree on them; the real-SDP check would have failed before T1. |
| T3 | Give `CallSignaling.Accept` an allocated body. Change `Accept` only — **do not** change `Make`: allocating in `Make` would make `CallSignaling.Serialize` emit `"b":{}` for every bodyless frame and break the `gen == 0 && b.Count == 0` gates at `CallVideoProtocol.cs:119`. | Windows | Pending | — | A v2 callee `ACCEPT` carrying `media` actually reaches the transport; harness asserts the serialized frame body is `{"media":"audio"}`. |
| T4 | Stop swallowing the exception that kills a call. Replace the bare `catch { }` at `CallController.cs:544`, `:556`, `:562` with one that records the exception type/message into the existing diagnostic stream, and replace `_ = callController.AcceptAsync()` at `ChatWindowCalls.cs:150` with an observed task that routes `IOException` through the existing `ReportVideoOutcome` (`:204-213`) — `AcceptAsync` throws `IOException` by design at `:206`/`:216`. | Windows | Pending | — | A forced send failure on accept produces a logged exception and a visible message instead of an invisible dead call. |
| T5 | An accept must not strand a call. Reorder `AcceptAsync` so the state mutation and the `ACCEPT` send cannot interleave badly: today `try { SendFrameTo } catch { }` at `:223` already turns a send failure into a silent state change with no watchdog. | Windows | Pending | T3, T4 | A call whose `ACCEPT` cannot be sent does not sit in `Connecting` with every watchdog cancelled. |
| T6 | `CallView` lifetime. `callView` is nulled only in `FormClosed` (`ChatWindowCalls.cs:201`) and closed 2.5 s after a terminal state (`:255-256`); if `closing.Close()` throws, `callView` stays non-null for the session and no later call ever gets a window. Clear it on the terminal state, not on `FormClosed`. | Windows | Pending | — | A call arriving in the 2.5 s window after a previous call ends gets its own window with `isIncoming` bound correctly. |

### Decision required before execution

`ChatWindowCalls.cs:41` (`callController.VideoEnabled = true`) is a single switch that activates the
v2 frame validator, `videoConsent`/`videoActions`, the native adapter and the video-capable
`CallView` layout, and advertises VP8 to Android. Two options:

- **Option 1 (recommended):** ship T1–T5 as one fix and keep video enabled. The working tree is
  already dirty, so the enabler and the fixes would land as one inseparable commit unless the
  enabler is committed separately first.
- **Option 2 (fastest way to a working product):** revert `ChatWindowCalls.cs:41` (leave the factory
  assigned but `VideoEnabled` false) to restore the proven v1 voice path, land T1–T5, then re-enable.
  Voice calls are unaffected by T1–T5 and this loses nothing that was previously working.

## 6. Testing tasks

### Automated (no device needed)

| ID | Check | Expected |
| --- | --- | --- |
| AT1 | `dotnet build windows\LanMessenger.csproj -c Release` | 0 errors, only the pre-existing `ChatWindowVoice.cs(19,16) CS1998` |
| AT2 | `CsharpHarness --call-video-check` | all pass, including the new real-SDP case (T2) and ACCEPT-body case (T3) |
| AT3 | `tests/video-contract/run.ps1` | Android and Windows verdicts agree on the extended corpus including `SAVP` rows |
| AT4 | Full `tests/run.ps1` | exit 0; the historical `group_membership.py` 16-member flake may recur and is already recorded as unresolved |

### Manual (genuinely needs two physical devices — cannot be automated)

| ID | Check | Expected |
| --- | --- | --- |
| MT1 | Windows → Android voice call, accept on Android | reaches `Connected`, live timer on both sides, clean hang-up from **both** ends |
| MT2 | Android → Windows voice call, accept on Windows | the incoming window shows Accept; the call connects; audio both ways |
| MT3 | Android → Windows, hang up from Windows | Android's call screen ends too (confirms the ACCEPT/HANGUP frames actually reach the wire) |
| MT4 | Windows → Android, hang up from Windows | Android's call screen ends; `end=LocalHangup` appears in `call-diagnostics.log` |
| MT5 | Either direction, "Accept with video" | no silent no-op; either video connects or a visible refusal is shown |
| MT6 | Check `%LOCALAPPDATA%\LanMessenger\call-diagnostics.log` after MT1–MT5 | every transition present; no unexplained `MediaError` |

The `MediaError` path can only be observed with a real Android peer on a verified connection. No
automated substitute exists, so MT1/MT2 are the acceptance gate for T1–T5, not a nice-to-have.

## 7. Notes and limitations

- `EndReason.MediaError` has four producers (`CallController.cs:387`, `:544`, `:556`, `:562`) and
  **all four discard the exception**. Symptom A was only provable by reading a real SDP through the
  real validator; the shipped diagnostic log records state, never cause.
- `CallVideoNativeBridge.FindLatestBridgeDll()` (`CallVideoNativeBridge.cs:45-63`) hardcodes
  `D:\LAN-Messenger\outputs\.build\video-media`. On any other machine it returns null and video
  silently stays off. Not a cause here, but it means the production switch is not portable.
- `CallVideoNativeBridge._buffer` (`:42`, written at `:114`) is a single 131072-byte shared buffer
  with **no lock**, while `CallVideoNativeMedia.PollNative` calls the bridge from a 100 ms timer
  thread (`CallVideoNativeMedia.cs:43`) and `CreateOffer`/`AddIce` run on the coordinator thread.
  Independent of both symptoms, but it will surface once video actually connects.
- `CallVideoNativeMedia.OnNativeFrame` (`:166-175`) allocates up to 64 MB per decoded frame on a
  native callback thread with no pool and no back-pressure, and `CallView.ShowFrame`
  (`CallView.cs:132`) `BeginInvoke`s with no frame drop. Independent of both symptoms.
- Unrelated observations from the same review, not part of this regression:
  `ChatWindowCalls.cs:134` resets `recipientGrantRefreshing` inside a `BeginInvoke`, so an
  accepted-but-undelivered delegate stalls the recipient-control refresh permanently;
  `CallChannel.cs:96`/`:99` discard every exception from the entire call state machine.