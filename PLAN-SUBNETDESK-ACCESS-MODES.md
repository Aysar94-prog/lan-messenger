# SubnetDesk three access modes

Date: 2026-10-08. User explicitly approved execution with "execute everything".
Target: separate SubnetDesk; first stage Android sender -> Windows receiver (Both).
Keep authentication security/interop acceptance separate from buzzer acceptance.

## Requested choices and proposed safety defaults

1. Saved password: opt-in encrypted Android credential storage bound to the verified
   receiver fingerprint, saved only after successful login. Reuse the existing password
   plan and its forget action; do not put passwords in chat history or plaintext files.
2. Receiver code: Windows displays a cryptographically random, single-use temporary
   code that the receiver shares with the sender. Proposed default: 6 digits, expires
   after 2 minutes, limited attempts; regeneration invalidates the previous code.
   Code verification must stay on the authenticated encrypted LAN channel. Never log it.
3. Manual approval: Android sends a connection request; Windows displays the requested
   session type and address, clearly separating claimed device name from verified identity.
   No remote input, capture, chat, file or audio access before explicit local approval.
   Deny/timeout/disconnect cancel the pending request. Never auto-approve on reconnect.

Receiver explicitly enables the available methods. Keep the current password method
working; do not silently open passwordless access when an approval UI is unavailable.
Installed service/prelogin, locked desktop and old app versions need an explicit safe policy.
Do not change remote-control permissions just because a different login method succeeds.
Reverse-direction/Android receiver behavior is not automatically included.

## Small tasks

| ID | Platform | Task | Status | Dependencies | Notes / acceptance |
|---|---|---|---|---|---|
| SD-AM-01 | Both | Trace LAN login/auth gate and existing CM approval flow | Initial source trace complete | None | This fork has no surviving CM authorization command; extending the pending request IPC/UI is necessary. More gate/lifecycle audit required during implementation. |
| SD-AM-02 | Both | Define encrypted capability/method/request/result contract | Planned | 01 | No handshake downgrade; older versions retain password login; reject unsupported methods safely. |
| SD-AM-03 | Android | Implement saved-password option | Plan already prepared; not started | 01 | Follow PLAN-SUBNETDESK-ANDROID-CREDENTIALS.md; fingerprint binding, Keystore, opt-in and forget tests. |
| SD-AM-04 | Windows | Temporary code UI, secure generator, expiry and revocation | Planned | 02 | One-use, bounded attempts, no plaintext storage/logging, atomic consumption with concurrent requests. |
| SD-AM-05 | Windows | Pending manual request UI and lifecycle | Planned | 02 | Explicit accept/deny, bounded queue/timeouts; unavailable/locked/prelogin UI fails closed. |
| SD-AM-06 | Android | Method selector, code entry and waiting/denied/expired states | Planned | 02 | Clearly show chosen method; cancellation sends no access-grant; no automatic method fallback. |
| SD-AM-07 | Both | Server authorization gates and permission preservation | Planned | 03, 04, 05, 06 | All three routes converge on audited authorization; no pre-auth capture/input/file/audio. |
| SD-AM-08 | Both | Contract/security/interop tests | Planned | 07 | Wrong/expired/reused code, brute force, replay, cancellation, duplicate/concurrent requests and old peers. |
| SD-AM-09 | Both | Distinct signed candidates and physical acceptance | Planned | 08 | Side-by-side Android candidate approved by user; preserve original app/data/key; build success != device acceptance. |

## Testing tasks / acceptance

- Saved credentials: off/on, failed login, new IP/same fingerprint, old IP/different identity,
  forget, process/device restart and storage failure without insecure fallback.
- Code: wrong, expired, already used, revoked/regenerated; simultaneous attempts consume
  only once; per-source and aggregate limits prevent unlimited guessing.
- Manual: approve, deny, cancel, timeout, multiple pending requests and disconnect/reconnect.
  Instrument that every protected action remains blocked until approval.
- Service/no interactive receiver/locked desktop: no hidden auto-approval or unreachable
  prompt that accidentally grants access. Receiver can disable each optional method.
- New Android/new Windows and both mixed-version directions; existing password login remains
  usable. Check actual running binaries, signing identity and upgrade/rollback separately.
- No credentials/codes/chat contents/private keys in logs or source archives.

Next checkpoint: trace and implement the approved three modes. Buzzer and microphone retest
remain independent; neither is evidence that these access modes exist.

## Source review and implementation constraints:2026-10-08

- Existing Windows LAN receiver verifies a single configured username/password in
  server/connection.rs:handle_lan_login_request, then sets permissions/configures scope,
  starts CM and authorizes. Desktop identity profiles are sender credentials, not receiver
  user accounts. Do not mistake them for existing code/manual login methods.
- Pre-auth network dispatch accepts login/test-delay/close only. Protected messages remain
  under authorized gating. CM IPC currently starts after credential success; if manual mode
  starts CM earlier, its inbound command handler must be hardened too (chat/audio/file/etc
  cannot run before local approval).
- PeerInfo capabilities arrive only after login; they cannot negotiate a new pre-auth method.
  Use an optional versioned probe/reply on the verified encrypted LAN stream for code/manual
  modes, with bounded timeout, no password fallback or handshake-version downgrade. Existing
  password mode must remain wire-compatible with older peers.
- Code generation/consumption must reside in the actual backend process, not the separate CM.
  Do not offer code/manual methods from an unavailable/non-interactive/locked/prelogin UI or
  assume main and installed-service globals are shared. First private portable flow may fail
  closed for service-owned backends until authenticated IPC state propagation is verified.
- Manual acceptance binds the pending connection ID and random nonce; bounded queue and expiry.
  Reject stale/duplicate/cancelled/changed-login requests. Claimed device name is not verified
  sender identity. All successful methods converge on the existing audited scope/permission
  initialization; code/manual must never persist their secrets as remembered passwords.
- Android application context is available at FFI.onAppStart, even without MainService.
  A narrow Keystore/AES-GCM bridge should use that application context, noBackupFilesDir,
  fingerprint AAD and atomic writes; no plaintext PeerConfig credential fallback.
- User subsequently approved permanent SubnetDesk View camera removal. Camera requests must
  remain denied under every access method; remote desktop screen transport and voice stay.
- No access-mode/Keystore code has been implemented yet. Execution is already approved;
  do not request the same start authorization again. Android native/APK build remains a gate.
