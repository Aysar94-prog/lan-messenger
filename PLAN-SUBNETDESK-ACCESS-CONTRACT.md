# Encrypted LAN optional-access contract (approved execution)

Latest automated checkpoint,2026-10-08: frozen feature source
bd628d9cf9f6ebb8287b710e6d162be90da46cf6, common3bc0a6d; native access policy/CM
preauth regressions10/10, wire contract3/3, final Flutter96/96 PASS. Additional native
regressions PASS: immutable updater3/3, camera denial3/3, credential payload4/4,
encrypted LAN handshake/transport6/6. Final full release82 builds running; actual
legacy/new paired acceptance, Keystore device instrumentation and receiver decisions
are not established by synthetic tests. Older table verification statements below
are historical; source tasks are implemented/automated-verified, device gates remain.

2026-10-08; Both, Android sender -> Windows portable interactive receiver first.
Dependencies: PLAN-SUBNETDESK-ACCESS-MODES.md and Android credentials plan.
No reverse-role parity or LAN Messenger changes. Optional methods off by default.

| Task | Platform | Status | Dependency | Acceptance / tests |
|---|---|---|---|---|
| AC-01 | Both | Source implemented; compiler verification in progress | Existing encrypted handshake/trust | Post-trust encrypted capability probe/reply version1; password0 remains unchanged for old peers, code1/manual2 require positive reply, no fallback or trust bypass. |
| AC-02 | Windows | Core wired in source; policy7/7 PASS | AC-01 | Code120s/5 failures/single consumption; regeneration revokes. Manual60s/nonce32/8 global/2 per source, cancellation/denial/expiry and duplicate/replay handling. Production supplies libsodium randomness and constant-time code verification. |
| AC-03 | Both | Source implemented; verification in progress | AC-01,02 | Optional LanLoginRequest method/code fields and probe/capability/pending Messages. Client probes before optional login; 4s timeout/negative capabilities fail without fallback. Optional methods skip credential loading/save/forget on success. |
| AC-04 | Windows | Source implemented; security tests pending | AC-03 | Early unauthorized CM card; only Close/CmErr/scoped nonce decision before auth. Raw/chat/audio/file/clipboard/privacy/portable-service dropped. Additional video/general/authed channels gated. UI matches connId+nonce. |
| AC-05 | Windows | Source implemented; verification in progress | AC-04 | All successful routes converge on permission/scope/audit/logon initializer. Camera denied; duplicate login closes pending; disconnect cancels. Native WTS known-active-unlocked query fails closed, listener must be in same process; requests and decisions recheck. |
| AC-06 | Both | Source implemented; Flutter verification in progress | AC-05 | Android explicit selector/code field, pending cancel; Windows volatile opt-in toggles/code regeneration/local approval panel. FRB generation PASS. Optional modes off on process restart. No auto-approval or fallback. |
| AC-07 | Both | In progress; no release/device claim | AC-06 | Policy7/7, wire3/3 (legacy bytes/defaults, optional credential-free fields, encrypted TCP probe/caps/manual/pending), full Flutter93/93 PASS after import repair; optional widget analyzer no issues. Native policy/CM gate tests running against ff1de48/1.3.4. Mixed actual peers and protected-action/device acceptance remain. |

Backend policy owns random code/nonce, never the CM process. Local UI may show the code;
network probe only returns enabled-method booleans, never code/nonce/password. Code issuance
requires actual listener in the same interactive user process; service-backed UI fails closed
until authenticated backend-state routing is verified. Re-check interactive/unlocked policy
at request AND local decision. Never derive authority from claimed sender name/address.

Keep backend connection unauthorized while pending; no early capture/input/audio/files/chat.
Protect the inbound CM command branch too, not merely network dispatch. Local decision only
consumes that connection's nonce once. Approval timeout/cancel/disable prevents stale acceptance.
Do not hold locks across awaits. Aggregate/source auth limits remain in addition to code5/global
manual8/source2 bounds. No secrets in logs or plaintext files. Session chat cleanup remains
independent from saved password storage. Camera and immutable no-updater policies stay enabled.

Final packaging/runtime checkpoint: Windows native8m50s/Flutter130.1s/tests96 PASS,
Android native4m18s/APK390.2s PASS. Signatures/versions/no-camera/alignment/native matches/
corresponding source private-material gates PASS; helper2082 installed, original untouched.
Actual Keystore synthetic instrumentation PASS using Java test driver f678dbd. Windows82
running1.3.4 owns21118; Firewall prompt needs human choice and prevents further UI testing.
No actual code/manual/old-peer paired acceptance or final-all-work completion claim.
