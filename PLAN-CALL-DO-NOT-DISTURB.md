# Plan: "Messages only" presence mode (stay reachable for chat, unavailable for calls)

Status: **Plan only — no code/build changes made.** Awaiting explicit user start.

## User's actual goal (clarified 2026-10-08)

Not a buried settings checkbox — the user wants this presented **like a presence mode**,
alongside the existing Online/Offline control: "Offline" already exists; they want a parallel
**"Messages only"** state where they stay Online (reachable for chat, presence shown, messages
delivered normally) but incoming voice/video calls are automatically rejected without ringing.
This resolves open decision #1 below: it is a distinct, clearly-labeled mode, not a renamed
generic toggle.

**Placement, clarified further (2026-10-08, second round)**: not a sibling button next to
Offline — the mode lives **inside** the existing Offline control itself. Clicking the
Offline control now prompts the user to choose between two options: "Offline (everything)"
(today's existing behavior, unchanged) or "Messages only" (stay Online for chat, auto-decline
calls). So the Online/Offline/Messages-only state machine becomes a single control with a
choice prompt on the action that used to just mean "go offline," rather than three peer buttons.

**Trusted contacts and caller feedback, resolved**: trusted contacts (existing per-contact
trusted-call grant) are an **exception** — their calls still ring through normally while
"Messages only" is active for everyone else. Non-trusted callers must see a distinct message
reason, not plain "Declined": something to the effect of "this user is in messages-only mode."
This requires a new wire-level decline reason (see Task 6, now **required**, not optional) sent
and understood identically on both platforms.

## Background (read before starting any task)

A global "reject all incoming calls" switch already exists end-to-end on **both** platforms —
it is not a new feature to design from scratch:

- **Wire protocol**: no new frame needed. `DECLINE` already exists and is already reused for
  both a manual decline and a policy-based block (Windows `CallSignaling.Decline` /
  `windows/CallProtocol.cs` `EndReason.Declined`/`LocalDecline`; Android `CallProtocol.java`
  `DECLINED`, comment: "same wording for manual and policy"). `BUSY` remains the separate
  already-in-a-call/rate-limit response. No distinction is currently sent to the caller between
  "manually declined" and "DND is on" — both just look like a decline.
- **Windows**: `windows/CallSettings.cs` (`AllowIncomingCalls`, plain-text `call-settings.txt`,
  default `true`). Gated in `windows/CallController.cs:182`, which runs **before** the trusted
  auto-answer check at lines 203-209 — i.e. today, turning this off already blocks trusted
  contacts too, with no override.
  - **No visible UI toggle was found** for this setting anywhere under `windows/` (grep for
    `AllowIncomingCalls` only matches `CallSettings.cs` and `CallController.cs`). A user cannot
    currently turn this on/off from the Windows app.
- **Android**: `CallSettings.java` (`allowIncomingCalls`/`setAllowIncoming`, parallel pattern to
  Windows), read via `MessengerService.java:164-180`. **Already has a visible toggle**: a
  `Switch` in the people-list side menu, `PeopleListView.java:49-53`, wired to
  `MainActivity.setAllowIncomingCalls` (`MainActivity.java:909-912`).
- Trusted-call auto-answer grants (`PeerEngine.TrustedCallMask`, persisted in
  `windows/Storage.cs`'s `trustedCallGrants`) are a **separate** mechanism from this toggle; see
  `PLAN-TRUSTED-CALL-ACCESS-AND-APP-ICON.md`.

So the real gap is narrower than "build DND": **Windows has the backend but no UI; Android has
both; and nobody has decided the product semantics for how this interacts with trusted
auto-answer, or whether the caller should see a distinct reason.** This plan scopes exactly
that remaining work. Label: **Both** (Windows UI + settings decision; Android is mostly
confirmation/polish; any wire-reason addition is **Both** and needs interoperability review).

## Product decisions — all resolved

1. ~~Naming~~ — **Resolved**: a distinct "Messages only" presence mode, chosen from inside the
   existing Offline control (see Placement above), backed by the existing `AllowIncomingCalls`
   persisted flag (no new storage field needed for the mode itself — Online +
   `AllowIncomingCalls=false` **is** "Messages only"; full Offline is unchanged and still blocks
   everything including messages).
2. ~~Trusted contacts~~ — **Resolved**: trusted contacts are an exception and still get through
   (ring normally) while "Messages only" is active. This is a **behavior change** from today's
   Windows code, where the `AllowIncomingCalls` check at `CallController.cs:182` runs *before*
   the trusted-mask check at lines 203-209 and would currently block even trusted contacts — the
   check order must be swapped (or the trusted path given an explicit early-out) on both
   platforms.
3. **Video vs. voice**: one mode covering both call types (current behavior — the check is not
   call-type-specific), kept as a single combined mode per the user's original request ("صوت
   وفيديو" together). Not reopened.
4. ~~Caller-side feedback~~ — **Resolved**: callers must see a specific reason, not generic
   "Declined" — wording along the lines of "this user is in messages-only mode." Requires a new
   wire-level decline/end reason, implemented identically on both platforms (Task 6, now
   required).

## Tasks

| # | Task | Platform | Depends on | Status |
|---|---|---|---|---|
| 1 | Find and review the existing Windows Online/Offline control's exact UI location and current click handler (toolbar/tray — not yet inspected) so the new choice prompt replaces/extends that one action instead of adding a parallel control | Windows | — | Not started |
| 2 | Find and review Android's existing Online/Offline control's click handler (people-menu or equivalent — not yet inspected) for the same reason | Android | — | Not started |
| 3 | Add the "Offline (everything) / Messages only" choice prompt to Windows' Offline action; "Messages only" sets Online presence + `CallSettings.AllowIncomingCalls=false` (no new persisted field); choosing "Offline (everything)" keeps exactly today's behavior | Windows | Task 1 | Not started |
| 4 | Add the equivalent choice prompt to Android's Offline action, reusing the existing `CallSettings`/`MessengerService.setAllowIncomingCalls` plumbing instead of the current standalone `PeopleListView` switch (that switch is superseded by this prompt — remove or repurpose it per Task 1's findings) | Android | Task 2 | Not started |
| 5 | Give trusted contacts an exception: reorder the Windows check so `CallController.cs:182`'s `AllowIncomingCalls` gate runs *after* (or is skipped by) the trusted-mask check at lines 203-209, so a trusted contact's call still rings through under "Messages only"; make the equivalent change in Android's `CallController.java`. Full Offline is unaffected (still blocks everyone, trusted or not). | Both | — | Not started |
| 6 | Add a new wire-level decline/end reason distinct from manual `Declined` (e.g. `MessagesOnly`) so the caller's UI shows "this user is in messages-only mode" instead of generic "Declined." Update `CallProtocol.cs`/`CallSignaling` and `CallProtocol.java` in lockstep, update the shared frame/capability corpus and hand-authored expectations, and update both call-screen UIs (`CallView.cs`, Android's call UI) to render the new reason's text | Both | — | Not started |
| 7 | Update `windows/STATUS.md` and `android/STATUS.md` with the change; update `PROJECT_STATUS.md`'s feature comparison row for call handling | Both | Tasks 3-6 | Not started |

## Testing tasks

- Automated: Windows `tests/run.ps1` full pass; Android existing call/permission/direct suites
  (same suites already exercising `AllowIncomingCalls`/`CallSettings`); rerun the shared
  frame/capability corpus for three-way agreement (Windows/Android/hand-authored) to cover the
  new decline reason from Task 6.
- Device acceptance (separate from automated pass, per project convention): on a real two-device
  LAN pair with at least one trusted-contact pair configured: (a) choose "Messages only" on
  Windows from the Offline control's prompt, confirm an Android-originated call from a
  non-trusted contact is auto-declined with no ring and the caller sees the new "messages-only"
  reason text, while a text message from the same peer still arrives and shows as delivered;
  (b) confirm a call from a *trusted* contact still rings through normally under the same mode;
  (c) same two checks in the opposite direction (Android in "Messages only", Windows caller);
  (d) confirm presence still shows Online (not Offline) to peers while in this mode, and the
  mode persists across restart; (e) confirm choosing "Offline (everything)" from the same prompt
  behaves exactly like today's Offline (blocks messages too, no exception for trusted contacts);
  (f) confirm returning to full Online from either sub-mode leaves no stale call-blocking state.

## Acceptance criteria

- A user can choose "Messages only" vs. "Offline (everything)" from the existing Offline
  control's prompt, on **both** platforms (today Windows has no UI at all for the underlying
  flag, and Android's is an unrelated standalone menu switch, not part of the Offline flow).
- While in "Messages only": presence still shows Online to peers, text messages send/receive
  normally, non-trusted incoming voice/video calls are auto-declined with no ring and the caller
  sees a "this user is in messages-only mode" reason, and a trusted contact's call still rings
  through normally.
- Full "Offline (everything)" keeps its exact current behavior (blocks messages and calls alike,
  no trusted exception).
- Both platforms agree on the new decline-reason wire value; existing `DECLINE`/`BUSY` semantics
  for all other cases (manual decline, busy, rate limit) are unchanged.
- `PROJECT_STATUS.md` and both platform `STATUS.md` files reflect the final state, separating
  implemented-in-source from device-accepted, per standing project convention.

## Explicitly out of scope unless asked

- Per-contact or scheduled/time-based control over this mode (only the single global
  Online / Messages-only / Offline three-way choice is in scope here).
- Any change to trusted-call grant storage/format itself (`windows/Storage.cs` trustedCallGrants
  rows) — Task 5 only changes check *order*, not the grant data model.
- Any build, packaging, or release step — this plan stops at source-level implementation and
  automated verification; device acceptance and release are separate, later, user-authorized
  steps per `PROJECT_STATUS.md`'s working arrangement.
