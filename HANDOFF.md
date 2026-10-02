# Handoff — Android call screen, 2026-10-02

Windows is untouched by this session. Branch `master`, local commits only, nothing pushed.

## Where things stand

`tests/CallCheck.java` **PASS=387 FAIL=0** against the working tree. The shipping build is
**2.2.34** (versionCode 61), installed on **both** phones, same signer as every prior release,
arm64 only.

Tap-through and the drag behaviour are verified on hardware (`C0`–`C3`, `D0`/`D1`). The
cold-start accept path is verified on hardware (`accept-coldstart.ps1`, `X0`–`X6` all pass on
2.2.34).

## Two things the user reported, and what they turned out to be

**"The 💬 did not redirect me to the conversation — why?"** The tap did exactly what it says, and
then lost it. `showChat()` ends in `frame()`, which ends in `CallView.forgetOverlay()` — and
`forgetOverlay()` cleared `dismissedCallId`, the record the button had just made. The next `render()`
therefore rebuilt the full call panel straight back over the conversation, in the same tap. A comment
above that button already described this exact failure and claimed it was fixed; the fix was undone
by `forgetOverlay()` three methods away. `forgetOverlay()` now drops only view references: `collapsed`
and `dismissedCallId` record what the *user asked for*, not anything about the discarded tree.

That fix alone would have broken **Return to call** — `render()` holds the overlay down while the
dismissal stands, and `forgetOverlay()` wiping it was the only thing letting Return through. So
`CallView.returnToCall()` now lifts the dismissal, and `showCallOverlay()` calls it: asking for the
call screen *is* the act of un-dismissing.

Verified on hardware: tapping 💬 opens the conversation, the call screen does not come back, and the
call keeps running (`chat-drag-check.ps1`, `Y3`/`Y3b`/`Y4`).

**"I want the minimise dialog to float — can be moved, to easily type or do anything else."** The
minimised bar was a full-width strip anchored to the foot of the stage, which is exactly where the
message box and its keyboard appear, so a call and typing could not both be used. It is now sized to
itself and free-floating, and draggable:

- A `GestureDetector` distinguishes a tap (reopen) from a drag (move), with a 6 dp slop so a tap that
  reopens does not also nudge the bar. The click listener is kept alongside it, so TalkBack
  activation still works. The hang-up button is a child and takes its own touches first.
- `CallUi.clampBarLeft`/`clampBarTop` pull the bar back inside the stage — draggable means it can
  otherwise be dropped where there is nothing left to grab it. Before the first layout pass neither
  size is known, so nothing is clamped and the next pass corrects it (`N184`–`N192`).
- The position lives outside the view tree, because the bar is rebuilt on every state change and a
  position held in the view would be lost each time, and is persisted in `lan_messenger_ui` so it
  survives calls and restarts.

**A third defect this uncovered, which would have trapped the user:** `callBar` was built **only**
into `showPeople()`, and its comment claimed it "stays across the top of every screen". It did not.
So with the chat control fixed, a live call had no way back into the call screen once you opened the
conversation from it — the tap now works, and leads somewhere with no controls, which is
indistinguishable from the call having ended. `buildCallBar()` is shared by both screens now.

## The most serious defect found in this session: a cold start could not answer a call

**You could not answer an incoming call at all until you left the app and came back.**

It presented as one dead button. Tapping **Accept** on the incoming-call screen did nothing: no
`ACCEPT` frame left the phone, no toast, no error, no visual change, and the call rang out. **Decline
worked fine.** Every screenshot of the screen looked completely correct.

The cause was two references to one model. `CallView.render` takes its view-model from
`activity.host.calls()` — the service's instance — while `acceptCall` read the Activity's *own*
field `callUi` and returned silently when it was null. That field is bound in `bindCalls()`, whose
only two callers are `onResume` and `onServiceConnected`. But the service does not create its
`CallUi` until it builds the LAN stack (`MessengerService:270`), which is seconds later. So both bind
attempts saw null, `bindCalls()` gave up, and **nothing ever asked again**. Decline survived because
it captures the instance it was built with, as the on-screen Accept button now does.

Three fixes, because the first alone would have left the failure mode in place:

1. `render()` retries `bindCalls()` while the field is null. It runs once a second anyway, so the
   model is picked up the moment the service has one.
2. The Accept button acts on the model that drew it — `CallUi.resolveForAccept`, `N179`–`N183`.
3. No path returns silently any more. `acceptCall`, the notification's accept action and
   `onBackPressed` all fall back to the service's instance and, failing that, **tell the user**
   rather than doing nothing.

The notification's own Accept action and `onBackPressed` had the identical defect: on a cold start
the notification could not answer a call, and Back would navigate away from a live call instead of
minimising it.

How it was found: the scripted run failed, then a manual tap also failed, and the two facts together
— Decline working, Accept silent, no log line at all — ruled out a coordinate problem. `mCurrentFocus`
was `null` mid-run and sent me down a wrong path; it was the InputMethod display's value, not the
app's. The proof came from forcing a re-entry: the *same tap* then produced `ACCEPT → ANSWER →
MEDIA_READY`, which is what the re-entry fixes.

## What was asked for and done

The user supplied a reference call screen and asked for this app's to look like it. The call screen
was rebuilt to it (`CallView.build`): teal header with the peer's name as an uppercase headline and
the timer under it, the peer's picture in the window between, one large red hang-up disc floating
above a teal control bar holding 💬 / 🔈|🔊 / 🎤|🔇. Minimise is 🔽 in the header, so the bottom bar
keeps the reference's three controls.

Six defects the user hit are fixed:

1. **The hang-up disc covered Accept and Decline while ringing** — tapping Accept ended the call
   instead of answering it. The disc is now added only for non-ringing states.
2. **A minimised call could not be reopened** — `render()` compared call ID/state/mute/route, the bar
   matched all of them, so it decided there was nothing to rebuild. It now hides then re-renders.
3. **Back abandoned a live call** — it hid the overlay while the call kept running, leaving a screen
   that looks exactly like the call ended. Back now toggles (`CallView.backWhileLive`).
4. **The clock counted from invitation, not answer** — `CallSession.elapsedMs` measures from
   `connectedAtMs`.
5. **"Connection lost" meant nothing** — now "Disconnected" plus `CallUi.endHint`, a plain sentence
   under every end reason.
6. **The call screen let taps through to the conversation behind it** — see below.

## The bug the user reported last, and why it happened

> "when i call some one it show up a popup for call but when clicl any where it clicks like i'm in
> the conversation"

`CallView.attach` adds the overlay to `activity.stage` with `LayoutParams(-1,-1)`. A background
colour does **not** make a view swallow touches, and `FrameLayout.onTouchEvent` returns `false`.
Every tap that missed a child — the wall around the picture, the header padding, the gaps beside
the disc — was handed back to `stage`, which dispatched it to `chrome` underneath. So tapping
beside the avatar opened the conversation behind the call, and a vertical drag over the picture
scrolled it. `build()` now calls `holder.setClickable(true)`.

`buildCollapsed` and `buildTerminal` deliberately leave their holders transparent to touches — there
the chat underneath is meant to stay usable. Do not "fix" those the same way.

## Fixed after the independent review

An independent reviewer returned REJECT. All its actionable findings are now fixed:

- **💬 was a dead control.** `hide()` + `showChat()` was undone by `showChat` ending in `render()`,
  which rebuilt the panel over the chat in the same tap. `dismissedCallId` now records the request,
  keyed by call ID so it lapses by itself when that call ends.
- **`Return / End` bar frozen at `0:00`.** Its rebuild key was `call.state`, an enum that stops
  changing at `Connected`, while the text beside it is a duration that changes every second. Now
  keyed on the rendered words, and the summary is retargeted in place instead of rebuilt at 1 Hz.
- **`LOCAL_DECLINE` told the decliner the *other* phone declined.** It is only ever produced on the
  pressing phone. Now "You declined the call." (test `N154`/`N155`).
- **`SIGNALING_LOST` named a side the controller cannot know** — it is raised by heartbeat timeout, a
  local send failure *and* a local channel close, so it happens just as well when this phone drops.
  That sent a user with dead Wi-Fi to check the other phone. Now side-neutral (test `N152`).
- **TalkBack read the clock aloud once a second** for the whole call. **This took two passes and the
  first was wrong.** Gating a new `announce()` call on state transitions changed nothing, because
  the status line was *also* still an `ACCESSIBILITY_LIVE_REGION_POLITE` view being rewritten at
  1 Hz — so the clock was announced by the live region and the new code merely added a second
  announcement path on top of the first. Fixed properly: the live region is **removed** from both
  the full screen's status line and the collapsed bar's, and transitions are announced once each
  from a single call site.
  - The announcement had to move **above** the collapsed/full split and the rebuild test. Both return
    early, so announcing after them missed every transition that rebuilt the screen — which is most
    of them. Picking up a call rebuilds, so "Connected" was the one update a screen-reader user
    never heard.
  - Announcing `CallUi.stateLabel` would have said **"0:00"** at the moment of answering, since that
    label is the clock while connected. Hence `CallUi.stateSpokenLabel`, which says "Connected"
    (`N167`–`N170`). Pin this: if someone re-adds the live region, the clock is spoken again.
- **The peer's name and initial could go stale for the whole call.** The rebuild test was a chain of
  `||` clauses over call ID, state, mute and route — and the peer's *name* was never in it, so a
  contact renamed mid-call kept the old name, the old initial on the picture, and three accessibility
  labels naming them wrongly until something unrelated forced a rebuild. Replaced with one
  `CallUi.overlayKey` string (`N171`–`N178`), which cannot silently lose a clause the same way.
- The collapsed bar's hang-up said a bare "Hang up" and re-resolved its view-model from the service
  inside the click handler; it now names the peer and acts on the instance it was built from, like
  every other control on the call screen.
- The hang-up disc's specific label is no longer overwritten with a bare "Hang up"; dead
  `isCollapsed()` removed; `N147` de-flaked; `N146` rewritten around the reported symptom.

Also fixed this round, from the user directly: **"Group members" was offered on a direct
conversation** and opened a dialog that could not open. It is now group-only, and the menu dispatch
is explicit rather than ending in `else showMembers()`.

### Why the decisions moved into `CallUi`

The three rules worth testing — when to announce, when to keep the call screen dismissed, what the
return-to-call bar shows — all lived in `CallView` and `MainActivity`, and the pure-Java harness
**cannot load either class**: referencing them throws
`NoClassDefFoundError: android/content/Context`. That is precisely why they had no coverage, and why
the two staleness bugs in the call bar survived a green suite twice. They now live in `CallUi`
alongside `stateLabel`/`detailLabel`, with `CallView` and `MainActivity` calling into them, so there
is one copy of each rule and the test reaches it. Keep it that way: a rule that decides what a user
sees belongs where the test can see it.

## Not fixed — open, do these first

1. **Peer name and avatar are not in the rebuild key set**, so they go stale until the next rebuild.
   Cosmetic.
2. **No controller-level test that a delivered `Connected` snapshot always has `connectedAtMs > 0`.**
   `N144` currently pins the frozen-clock fallback as correct behaviour, which is the shape of the
   bug rather than the fix. The harness has `FakeCallMedia.Factory` and drives frames already.
3. Not yet written or tested at all: `CallRoutePolicy.reconcile` → `AudioManager`, proximity sensor,
   runtime microphone FGS promotion, A05 arbitration under a live call. Live two-device audio quality
   is unassessed.

## Traps in `accept-layout.ps1` that have already produced a false result

Read this before trusting any acceptance run. Four separate harness defects each made a *correct*
build look broken, and the run that exposed them had already been reported as evidence.

0. **`uiautomator` cannot dump the live call screens at all.** It waits for the window to go
   idle, gives up after ~10 s and prints `could not get idle state`, leaving no file to read — and
   the connected screen *never* goes idle, because the duration clock rewrites its text once a
   second. The minimised bar has the same clock, so it cannot be dumped either. The ring screen dumps
   fine only because its status line is the same string every second and so invalidates nothing.
   `settings put global *_animation_scale 0` does not help; the churn is our own `Handler` tick.
   **This is also a usable signal, not just a limitation:** a screen that will not dump *is* a live
   call screen, so "the dump succeeded" proves the call screen is not up. That is how the conversation
   control is verified — after tapping 💬 the dump succeeds and shows conversation controls, which is
   only possible if the call screen did not come back over it.
   **Consequence: every connected-state assertion in `accept-layout.ps1` — `L4`, `L6`–`L10`, `L14`,
   all of `M*` and `T1`–`T9` — cannot pass as written, and its failures say nothing about the app.**
   `accept-coldstart.ps1` asserts the connected state from the signalling trace instead, which is
   what actually establishes it. The *layout* of the connected screen is the user's manual
   acceptance. Do not "fix" those failures in the app.
1. **A failed dump silently returned the previous one.** `Dump` wrote to `/sdcard/t.xml` and then
   `cat`-ed it; when `uiautomator dump` failed, the old file was still there. The run asserted
   against a screen that had been gone for ten seconds — a *connected* call was reported as still
   showing `Accept` and `Decline`, and every minimise check failed behind it. The symptom that gave
   it away: the `[ring]` and `[connected]` screen-text dumps were **byte-identical**. Now the file is
   `rm -f`-ed before each attempt and an unusable result is reported and retried. If you ever see
   two identical `screen:` lines, the harness is lying again.
2. **An offline device makes `adb shell` block rather than return**, so the run hangs with no output
   at all. SM-A075F's USB adb drops every few minutes. `Dump` now checks `get-state`, calls
   `adb reconnect offline`, and gives each dump a 25 s deadline via a job.
3. **The assertions still grepped the old `"Hang up"` label** in five places, after it was
   deliberately replaced with `"End the call with <name>"`. The harness would have reported a missing
   hang-up control on every connected call. Search the script for `Hang up` before trusting it.

Also still true:

- `uiautomator dump` lists overlay nodes **and** everything underneath, so the presence of a node
  from the conversation behind a call screen proves nothing. Assert on whether the call screen
  *survives* the tap, not on whether the underlying nodes are absent — the first version of the
  tap-through check asserted their absence and failed every time on a correct build.
- A `Say` inside a helper is captured as that helper's **return value**, not printed, whenever the
  caller wraps the call — `if(-not (WaitTap ...))` swallows the "tap" line entirely. The absence of a
  tap line in the log proves nothing either way.
- `& $adb ... exec-out screencap -p > file.png` **corrupts the PNG**: PowerShell redirection is
  text. `screencap -p /sdcard/s.png` then `adb pull` is the only way that works.
- `dumpsys activity top` is a fallback that reads the view tree without waiting for idle, but it
  reports whichever activity is top — a Chrome custom tab left open on the phone will silently
  become the subject of the dump.
- **This model cannot read images** (`ERROR: ... this model does not support image input`), so a
  screenshot proves nothing to the agent. Capture them for the *user* and use logcat or the dump
  for anything the agent has to decide.
- `run-as net.lanmsg.chat` fails: `package not debuggable`. The app's own `SharedPreferences`
  cannot be read back off the device, so a persisted value (the bar's saved position) cannot be
  asserted by reading it — it can only be checked by the user seeing it.
- Clear logcat with `logcat -c` **before** placing a call. A `logcat -d` check of "the call did not
  end" is otherwise satisfied by the previous run's call, and will report a healthy call as broken.

**The two-device acceptance run is invalid.** Both phones failed the online gate (`E0-caller-online`,
`E1-callee-online`), so no call was placed and the ring and layout assertions that followed tested a
screen that was never there. The run before that one got to `PASS=32 FAIL=6` and settled most of the
flow — ring → accept → Connected → speaker on/off → mute/unmute → return → hang up, with graceful
close — but that predates the six fixes above.

To re-run: `outputs/.build/accept-layout.ps1 -Mode accept` (also `-Mode decline`). After
`adb install -r`, **both** apps need 5–15 s to rejoin the LAN, and calling an `Offline` peer silently
does nothing — that looks like a failed run but is a stale install. The `M1`–`M8` minimise/restore
checks are new and have never executed.

## If a phone drops to `unauthorized`

SM-A075F's USB adb went `offline`/`unauthorized` mid-session. `adb reconnect offline` recovers
`offline`. If it shows `unauthorized`, **the user has to tap "Allow"** on the phone's prompt — there
is no way around it from here.

Other traps that cost real time here, all still live:

- `adb logcat -d -s LANCALL` **without `-t`** is the only reliable tracing query.
- `uiautomator dump` lists overlay nodes *and* everything underneath, so a dump containing the people
  list is not evidence the overlay failed to cover the screen.
- Check `dumpsys window | mCurrentFocus` before concluding a control is inert — focus drifting to
  `com.sec.android.app.launcher` puts taps on Recents.
- Emoji appear as HTML entities: 🔽 `&#128317;`, 📞 `&#128222;`, 💬 `&#128172;`. Match on
  `content-desc`, never glyph text.
- Device sleep is the main obstacle. `settings put system screen_off_timeout 1800000`,
  `svc power stayon true`, `wm dismiss-keyguard`; confirm `mWakefulness`, don't assume.

## Environment

```
jdk  C:\Program Files (x86)\Android\openjdk\jdk-17.0.14\bin\javac.exe
jar  C:\Program Files (x86)\Android\android-sdk\platforms\android-34\android.jar
aar  D:\LAN-Messenger\outputs\.build\aar-cache\webrtc-150.7871.01\classes.jar
adb  C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe
callers  192.168.1.5:5555 (SM-ultraaysar, callee)
         R8YY80A8VLB    (SM-A075F,     caller, peer 051e89aa-bab6-4f09-8392-a6b9674d461e)
```

Compile the test suite in two steps — production sources against the AAR and `android.jar` into
`$prod`, then `CallCheck.java` + `MiniJson.java` + `TestProtector.java` with `$prod` on the
classpath. A production compile failure cascades into a wall of bogus
`package FakeCallMedia does not exist` errors: read the **first** error only.
`outputs/.build/cycle.ps1 -Version 2.2.30 -Code 57` does compile → test → build → install on both.

`android/build.ps1` refuses to overwrite a release artifact, so versions step forward rather than
being rebuilt.