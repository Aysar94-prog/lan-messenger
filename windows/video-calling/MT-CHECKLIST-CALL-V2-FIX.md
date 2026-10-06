# Manual two-device acceptance — Windows call v2 regression fix (MT1–MT6)

Companion to `TASK-CONTRACT-CALL-V2-FIX.md` §6. This is the device gate that blocks final
completion: the automated suite proves the wire and consent rules, but neither reported symptom is
reachable from a test harness (`OnInvite` needs two paired live engines; the WinForms accept button
has no UI test).

**Platform tag: Both.** Windows PC and one Android phone, same LAN, previously paired and mutually
Verified.

---

## 0. What is being proven

| | Reported symptom | Where the fix lives |
|---|---|---|
| Bug 1 | Windows → Android call closes instantly with `MediaError` | `windows/CallVideoProtocol.cs:158` + `android/…/CallVideoProtocol.java:94` (accept `SAVP` as well as `SAVPF`) |
| Bug 2 | Android → Windows call cannot be answered or ended | `windows/CallSignaling.cs` (`Accept` body), `windows/CallController.cs` (`SendAnswerLocked`/`AcceptAsync` ordering), `windows/ChatWindowCalls.cs` (observed accept continuation, `cause=`) |

Do not treat a quiet log as success. The old symptom was silence; **absence of `MediaError` proves
nothing**. Every MT below has an explicit positive PASS condition.

---

## 1. Preconditions

- [ ] Both devices on the same LAN, paired, mutually Verified, both **online** in the peer list.
- [ ] Windows app **not** running (the fix must be built into the binary you launch).
- [ ] Phone has ≥ 80% battery or is on a charger; screen will stay on for the call tests.
- [ ] Nothing else holds port **43872** on the PC (`tests/WindowsUi` and the app both want it).

### 1a. Rebuild the Windows app

```powershell
cd D:\LAN-Messenger\source
dotnet build windows\LanMessenger.csproj -c Release --configfile NuGet.Config
# then run: D:\LAN-Messenger\source\windows\bin\Release\net9.0-windows\LanMessenger.exe
```

Expected: `0 Error(s)`. The existing `bin\Release\…\LanMessenger.exe` dates from 10-05 and is
**pre-fix** — do not test against it.

### 1b. Build + install a phone candidate containing the Android fix

The installed phone app is **2.2.70 / code 97** (status record: 2.2.69 physically installed, 2.2.70
packaged but not reinstalled). The Android `ValidSdp` change is **required for Bug 1**, so a
rebuilt APK must go on the phone — a Windows-only build cannot pass MT1.

`android\build.ps1` defaults to `2.2.6 / code 33`, which the phone will **refuse to install**
(version downgrade). Call the pipeline directly with a higher code and a dev suffix so no release
artifact is touched:

```powershell
cd D:\LAN-Messenger\source
.\android\build-voice.ps1 -VersionName '2.2.71' -VersionCode 98 -ApkSuffix '-callfix-dev' -Arm64Only
adb install -r ..\outputs\LanMessenger-2.2.71-callfix-dev.apk
```

- Signed with `source\.private\development.keystore` — the original key, so it upgrades in place and
  keeps identity/history/settings. **Never regenerate the key**; `build.ps1` throws rather than
  silently creating one.
- `-Arm64Only` keeps it ~6 MB. Drop it only if the phone is not arm64.
- Verify after install: `adb shell dumpsys package net.lanmsg.chat | findstr versionName versionCode`
  → `versionName=2.2.71 versionCode=98`.

### 1c. Clear the evidence before every run

Windows writes one file for the whole run. Move it aside rather than deleting it, so the previous
session stays available:

```powershell
$log = "$env:LOCALAPPDATA\LanMessenger\call-diagnostics.log"
if (Test-Path $log) { Move-Item $log "$log.before-callfix-$(Get-Date -f yyyyMMdd-HHmmss)" -Force }
adb logcat -c
```

Read it as you go:

```powershell
Get-Content $log -Tail 40          # Windows
adb logcat -s LANCALL              # Android, live; -d for a dump
```

---

## 2. How to read the Windows log

Two line shapes, both tab-separated.

**State line** — one per published snapshot:

```
<ISO-8601>  state=<State>  caller=<True|False>  v2=<True|False>  invitedVideo=<True|False>
            video=<Phase|none>  end=<EndReason|none>  [cause=<text>]
```

- `caller=` is what tells the two sides apart. `True` = this machine originated the call.
- `v2=` is "both peers proved they speak v2 **for this call**", not "this machine has a camera".
- `video=` is `Voice | Waiting | Negotiating | Video | Ended`, or `none` for a pure voice call.
  **`video=Ended` immediately before an `end=` line is a teardown artifact** — `CloseVideoLocked`
  runs before the state change — so it is not on its own evidence of failure.
- `end=` is `none` on every line except the one(s) that end the call.
- `cause=` appears **only when `end != none`**, and is `none` unless something actually failed during
  that call (it is reset when a new session starts, so it cannot carry a previous call's reason).

`EndReason` values you may legitimately see: `LocalHangup`, `RemoteHangup`, `Declined`,
`TimeoutRinging`, `TimeoutMedia`, `SignalingLost`, `NetworkFailure`, `MediaError`.

**Event line** — a failure that produced no snapshot of its own:

```
<ISO-8601>  event=accept-failed  cause=<text>  type=<.NET exception type>
```

Android has no equivalent file; logcat tag `LANCALL` is the only on-device trace.

### The two old failure signatures

Keep these in front of you — they are what "still broken" looks like:

- **Bug 1 — the caller gives up at once.** Windows logs `state=OutgoingRinging`, then within about a
  second `state=Connecting … video=Ended … end=MediaError`, and **never** logs `state=Connected`.
  The phone may ring or may show the call appear and drop.
- **Bug 2 — the callee never leaves ringing.** Windows logs `state=IncomingRinging … end=none` and
  then **nothing at all**. No `Connecting`, no `Connected`, no `end=` line. The window sits on
  "Connecting…" with a hang-up button that does nothing. (Previously nothing was written at all;
  now a failure here at least produces `event=accept-failed`.)

---

## 3. The checks

Do them in order; several depend on the previous call having ended cleanly. After each one, capture
the Windows log tail and, where noted, `adb logcat -s LANCALL`.

### MT1 — Windows → Android, voice, connects with two-way audio

1. On Windows press **Call** (voice). On the phone, accept.
2. Let it run ≥ 20 s. Speak both ways; listen for your own voice returning.

**PASS**
- Windows log, in order: `state=OutgoingRinging … end=none` → `state=Connecting … end=none` →
  `state=Connected … end=none`. **`Connected` must be present.**
- Phone actually answers (not just rings) and both directions carry audio.
- Hang up from Windows: `state=Ending … end=LocalHangup cause=none`, and the phone call closes.

**FAIL (old Bug 1)**
- `state=Connecting` immediately followed by `end=MediaError` with no `Connected` — **copy the whole
  line**, it now carries `cause=` and that text is the diagnosis.
- Or: phone never leaves the ringing screen while Windows already shows `Connected`.

### MT2 — Android → Windows, answers and connects

1. On the phone, place a call to Windows. On Windows, press **Accept** (plain, not "Accept with
   video"). Let it run ≥ 20 s.

**PASS**
- Windows log shows `state=IncomingRinging … end=none` **then `state=Connecting … end=none` then
  `state=Connected … end=none`.**
- Two-way audio works.
- No `event=accept-failed` line anywhere in the file.

**FAIL (old Bug 2)**
- `IncomingRinging` with no `Connecting` after it, and the window stuck on "Connecting…".
- Or an `event=accept-failed` line — copy it whole, including `cause=` and `type=`.

### MT3 — hang up from Windows ends the call on both devices

Run MT1 or MT2 to `Connected`, then press **Hang up** on Windows.

**PASS**
- Windows: `state=Ending … end=LocalHangup cause=none`.
- Phone: call screen closes by itself (no lingering "call ended" banner, no ring that will not
  clear), and the peer list returns to normal on both.

### MT4 — hang up from the phone ends the call on Windows

Run a call to `Connected`, then end it on the phone.

**PASS**
- Windows: `state=Ending … end=RemoteHangup cause=none`.
- Windows call window closes by itself; the Hang up button is not left live against a dead session.

### MT5 — "Accept with video"

From the phone, place a **video-offering** call to Windows (the phone's call screen offers video on
a v2 call). On Windows, press **Accept with video**.

**PASS (any one of these is acceptable, and it must be one of them)**
- The call answers and reaches `state=Connecting` → `state=Connected`, with `video=` moving
  `Waiting|Negotiating` → `Video`; or
- Windows shows an **explicit message box** explaining why video cannot start, the call is still
  answerable, and nothing is written as `event=accept-failed` with a `NullReferenceException`.

**FAIL**
- The button does nothing at all: no state change, no message, no log line.
- A crash, or `event=accept-failed … type=System.NullReferenceException`.

> **Known behaviour on this build — record it, do not report it as a regression.**
> `ChatWindowCalls.cs:43` hard-codes `CameraEligible = _ => false` (the camera is deliberately
> disabled on Windows). `AcceptVideo` therefore returns `Denied` *before* any answer is sent, so the
> message shown is the pre-existing generic *"Video is not available right now. The call continues
> with audio only."* — but the call is in fact **still ringing**, and you must then press plain
> **Accept** to answer it. That message overstates what happens, and whether "Accept with video"
> should silently fall back to a voice answer instead is an open product decision. Log what you saw
> and flag it; it is not one of the two reported regressions.

### MT6 — `cause=` is real

**6a (deterministic — always do this).** After MT3 or MT4, confirm the field exists:

- Every line with `end=` also has `cause=`, and it is `cause=none` for an ordinary hang-up.
- No line with `end=none` carries `cause=`.

**6b (opportunistic — do it if you can, skip it if you cannot).** Force a genuine answer failure so
a non-`none` cause is captured:

1. Start a phone → Windows call and let it reach `IncomingRinging`.
2. Before pressing Accept, disable the PC's network adapter (unplug Ethernet or toggle Wi-Fi off).
3. Press **Accept**.

**PASS**
- `event=accept-failed` with a non-empty `cause=` **and** a `type=`; and/or
- `state=Ending … end=NetworkFailure cause=<the send error>` — not `cause=none`.
- The window reports the failure instead of sitting on "Connecting…".

If the network cannot be disturbed safely, 6b is optional: record `6b not attempted`. 6a alone proves
the field is wired; a real non-`none` cause only proves the failure path, and no automated test
reaches it either.

---

## 4. Also watch for on a now-live media path

Media negotiation previously died before it could do anything interesting. Now that it runs:

- `new RTCPeerConnection(null)` was measured at **192–1389 ms** inside `CallController.gate`, and
  it is reachable from a WinForms button handler — report any UI freeze, however brief, with how long
  it lasted.
- Audio: no sound, one-way sound, or echo/feedback (both ends are on the same LAN and may be in the
  same room).
- Call does not tear down within 15 s of connecting (`TimeoutMedia`), or the ring watchdog fires at
  30 s (`TimeoutRinging`).
- A second call immediately after the first fails, or the peer list shows a stale "in call" state.

---

## 5. Record the results

Per contract §6, fill in MT1–MT6 with **PASS / FAIL / not attempted** plus evidence:

- Windows: the full log tail for each call (or the whole file if short).
- Android: `adb logcat -s LANCALL` for each call.
- Any APK/exe actually installed: version, path, build time.

Then update `windows/STATUS.md`, `android/STATUS.md` and `PROJECT_STATUS.md`, keeping **implemented
code**, **automated verification** and **device acceptance** as three separate claims. Device
acceptance stays *Pending* until every one of MT1–MT5 is PASS (MT6b may be `not attempted`).

**Out of scope here:** no release, no version bump of a shipped artifact, no packaging to
`D:\LAN-Messenger\outputs` beyond the dev-suffixed test APK, no push.
