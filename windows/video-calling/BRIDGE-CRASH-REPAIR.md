# Windows native bridge repair — 2026-10-05

Original DLLs preserved. Portable ProcDump/cdb captured the original crashing variant,
without WER registry changes or elevation. Private dump is in its diagnostics directory:
`audio-only-isolate.exe_261005_055845.dmp`; analysis in `crash-stack.txt`.

At RVA 0x5dd08, `mov rax,[rcx]` dereferences RCX=0 after loading `[rsi+0x50]`, before
offer execution. A diagnostic-only relink of the saved original object/library maps this
to WebRtcVoiceEngine construction. It is supporting evidence, not a matching original
PDB: the relink's complete .text hash differs. Original transient source diff unavailable.
Exact upstream f89edcb7be1f4be029ee7186e36b2b35ec03373e voice-engine source queries its
audio encoder/decoder factories unconditionally. The bridge supplied neither; EnableMedia
does not create them. Null ADM also permits default physical audio initialization.
The pre-offer crash is not attributed to the old asynchronous-wait edit.

Separate empty-offer failure: code reads an asynchronous observer before completion,
and no audio transceiver was present. Strengthened audio isolate fails against the old
non-crashing baseline (exit 1), rather than reporting false success.

Repair: explicit built-in codecs, dummy hardware-free ADM, recording/playout disabled,
G722 audio transceiver, driven network SocketServer, refcounted Winsock/SSL lifecycle,
bounded 3-second offer/answer completion waits and ownership of returned descriptions.
Builds now retain source/script snapshots, compiler/SDK/flags/hashes, PDB and map.

Validated output: `bridge-19c5f268f79549c1b32b8220f75f403a` under outputs/.build/video-media.
DLL SHA256: `28923321244DD8398A57C5049B67BDAC0702D8A660995BF43042610429F78AD7`.

- NativeBridge: 39 PASS / 0 FAIL, exit 0 (synthetic video start/stop included).
- NativeAudioReadiness: 24 PASS / 0 FAIL, exit 0, hardware-free only.
- Audio isolate: 20 offer/answer/create/destroy cycles pass, exit 0; SDP requires audio,
  G722, and no video section.
- Windows Release build: 0 errors, existing CS1998 warning.
- Managed call-video checks: 317 PASS / 0 FAIL; shared frame/capability corpora unchanged.
- Full `tests/run.ps1`: exit 0, including group membership/migration/ownership, offline
  lifecycle and Windows UI tests. Log: `outputs/.build/bridge-repair-regressions.log`.
  The historical intermittent group-membership failure is not claimed fixed; this run passed.

WVC-06/T02 remain Partial: actual CoreAudio initialization/start, COM/thread ownership,
mute/route, physical startup-failure/repeated teardown and authenticated replacement-audio
HELLO/READY/CALLCONNECT are unverified. SDP tests do not prove audible communication.
Native set-remote parses offers only; local SDP/remote-answer application and end-to-end
transport remain production-integration gaps. WVC-08 blocked; production video disabled.
No release, production activation, shipping SDK package, or push.
