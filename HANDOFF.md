# Handoff — Android call screen, 2026-10-02

Windows is untouched by this session. Branch `master`, local commits only, nothing pushed.

## Where things stand

`tests/CallCheck.java` **PASS=370 FAIL=0** against the working tree. Two builds exist:

- **2.2.30** (versionCode 57) — installed on *both* phones, but assembled **before** the tap-through
  fix. It does not contain it.
- **2.2.31** (versionCode 58) — installed on **SM-ultraaysar only**, as asked. Same signer as every
  prior release, arm64 only.

**The two-device acceptance run has not produced a valid result**, and the tap-through fix — the bug
the user reported last — **has never been exercised on hardware**. SM-A075F (192.168.1.44) is off
the network entirely: 100% packet loss from ultra, and it shows as `Offline` in the people list.
`startCallTo` refuses an offline peer before creating a call, so no call overlay can be raised from
ultra alone. Bring the other phone back onto the LAN and this is the first thing to check.

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
- **TalkBack read the clock aloud once a second** for the whole call. Now gated to transitions
  (`N156`–`N159`).
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

## Unverified

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