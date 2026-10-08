# SubnetDesk Android: remember remote-device credentials

Date: 2026-10-07; execution approved on2026-10-08 with "execute everything".
Assumption stated to user: this is SubnetDesk Android, not LAN Messenger Android.
Windows dock execution remains authorized and independent.

## Verified source findings

Upstream v1.3.0 helper checkout:
`D:\LAN-Messenger\reference\SubnetDesk-v1.3.0\source\zibo-chen-SubnetDesk-1d3ac5a`.

- `flutter/lib/mobile/pages/connection_page.dart:onConnect` packages LAN username/password
  with `remember: false` and clears the password field after connection.
- `src/client.rs:load_remembered_lan_credential_for_fingerprint` loads credentials from the
  operating-system keyring on Windows/macOS/Linux, but returns None on Android/iOS.
- `save_remembered_lan_credential` explicitly rejects platforms outside those desktop OSes.
- Existing credential identity uses the remote device's 64-hex fingerprint, not its IP address.
  Preserve this binding; changing IP must not cause credential disclosure to a different device.
- `src/ui_session_interface.rs:handle_peer_info` already saves a session credential on successful
  authentication when remember is true, and clears it when false. Initial-login loading and
  failed-credential clearing paths already exist. Reuse these hooks rather than saving a password
  when the Connect button is pressed. The low-level client helper ignoring its remember argument
  is not, by itself, proof that the session-level remember implementation is absent.
- No Android Keystore/Cipher store was found in the existing Kotlin/Java source. Existing desktop
  LAN identity profiles also reject Android; do not expand identity-profile scope automatically.

## Scope and tasks

| ID | Platform | Task | Status | Dependencies | Notes / acceptance |
|---|---|---|---|---|---|
| SD-AP-01 | Android | Trace connection, successful authentication, fingerprint and forget flows | Initial findings complete; deeper trace planned | None | Find every connect entry: typed address, discovery/history cards, reconnect and auth dialog. |
| SD-AP-02 | Android | Add opt-in "Remember this device's password" and a per-device forget action | Approved; not implemented | SD-AP-01 | Off by default; username/password entry remains masked; saved-state indication does not reveal the secret. |
| SD-AP-03 | Android | Secure credential store using Android Keystore-backed encryption | Approved; not implemented | SD-AP-01 | Prefer existing safe Android/native facilities if present; otherwise narrow JNI/platform bridge. Never plaintext prefs/files, logs or LAN frames beyond existing authenticated flow. |
| SD-AP-04 | Android | Save only after successful authenticated login; load only for verified fingerprint | Approved; not implemented | SD-AP-02, SD-AP-03 | Save username + password per fingerprint. IP change may reuse same verified identity; different fingerprint must not reuse it. No trust bypass or server/protocol change. |
| SD-AP-05 | Android | Forget, failure and lifecycle behavior | Approved; not implemented | SD-AP-04 | Forget removes secret and metadata. Wrong saved password returns to editing; lock/store errors surface safely. Review backup exclusion and reinstall/upgrade behavior. |
| SD-AP-06 | Android | Automated checks, signed candidate and physical acceptance | Approved; pending implementation gates | SD-AP-05 | Record source checks, APK build and actual phone acceptance separately. Do not rebuild Windows solely for Android UI/storage. |

## Test tasks / acceptance

1. Unchecked remember: connect successfully, restart app, password is not saved.
2. Checked remember: successful login, app/process/device restart, reconnect without retyping.
3. Failed authentication never persists newly entered wrong credentials.
4. Same verified device at new IP works; another fingerprint at the old IP never receives its secret.
5. Two peers keep separate username/password entries; discovery, history and typed-address paths agree.
6. Forget removes entry; next connection asks again. Wrong saved password can be corrected.
7. Storage/Keystore failure is handled explicitly without insecure plaintext fallback.
8. Verify backup/export/logs contain no cleartext secret; real device checks include current Android API.
9. No LAN Messenger signing key or application source change. Do not claim Android/Windows parity
   before actual interoperability and credential-store acceptance.

## Packaging/signing boundary

SubnetDesk's official Android signing key is not available here. An APK signed with another key
cannot upgrade the official installed package. Prefer a clearly identified side-by-side helper
candidate if the official key cannot be provided; agree on package identity before packaging.
Do not reuse or expose LAN Messenger's private signing key for this separate application.
Keep all private key material outside source archives.

## Next checkpoint

Execution is authorized; secure store/UI implementation and device acceptance remain pending.
If the user intended LAN Messenger Android, revise this plan's target before implementation.

## Additional source audit:2026-10-08

- Native Android release now builds; APK/device gates remain independent. Do not change
  feature source while the1.3.3 candidate is being compiled/archived.
- handle_peer_info already runs credential saving in spawn_blocking and reports failures
  through interface.msgbox. Reuse it; avoid a second early save from the Connect button.
- Android load must branch before desktop PeerConfig username lookup if both username and
  password are encrypted; otherwise loading would wrongly require plaintext metadata.
- libs/scrap/src/android/ffi.rs owns JavaVM and application-context GlobalRefs. Current
  Java_ffi_FFI_onAppStart sets application context but NOT JavaVM; JavaVM is set later
  by MainService/clipboard initialization. Credential access must initialize/reuse it at
  application start without requiring the receiver MainService. JNI should reuse this lifecycle;
  native attached threads must not assume FindClass uses the app's class loader.
- Current save/clear hooks validate64-hex fingerprint and username/password. Retain validation
  and failure UI, no insecure fallback. Per-device forget must delete encrypted entry rather
  than merely removing the old PeerConfig metadata.

2026-10-08 continuation: reviewed all four approved areas separately. Session-chat cleanup
is being implemented first under PLAN-SUBNETDESK-CHAT-MEMORY.md; it must not erase saved
credentials. Android saved-password UI/store/native bridge and device tests remain NOT
implemented. Do not mark SD-AP-02..06 complete based on the chat build.

Verified credential trust order for the next implementation: client/io_loop.rs checks
confirm_lan_device before setting lan_fingerprint and calling send_initial_lan_login.
Known fingerprint at a new unpinned endpoint can reuse trust; a changed identity at an
already pinned endpoint requires explicit human verification first. Preserve that ordering
and never select or send saved credentials using IP/address alone. Existing load/clear
return Option/void, so storage errors and the per-device forget result need an explicit
safe UI outcome when adding Android support; no silent plaintext fallback.
