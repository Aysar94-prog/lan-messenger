"""WT06/W10: architecture-boundary and canonical-fixture-source checks for Voice Messages
(Windows). Static, no build/runtime dependency — safe to run any time.

1. Architecture boundary: windows/VoiceMessages.cs (the transport-independent PCM/WAV/marker/
   seek core, per I01/I02) must not depend on WinForms, PeerEngine, the attachment store, LM4
   types, or any device API. Only later platform-adapter files (VoiceRecorder.cs/VoicePlayer.cs/
   ChatWindowVoice*.cs) may touch those.
2. Canonical fixture source: nothing under windows/ may keep its own copy of the shared
   tests/voice_messages/vectors/ fixtures, and tests/run.ps1 must invoke --voice-check against
   the canonical path, never a redirected/platform-local copy.
"""
import pathlib, re, sys

root = pathlib.Path(__file__).resolve().parent.parent

FORBIDDEN = ['System.Windows.Forms', 'PeerEngine', 'AttachmentStore', 'LM4', 'WaveIn', 'WaveOut', 'winmm', 'VideoCall']

def fail(msg):
    print('FAIL: ' + msg)
    sys.exit(1)

core_raw = (root / 'windows' / 'VoiceMessages.cs').read_text(encoding='utf-8')
core = '\n'.join(line for line in core_raw.splitlines() if not line.strip().startswith('//'))
if re.search(r'^\s*using\s', core, re.MULTILINE):
    fail('windows/VoiceMessages.cs has a using directive; the transport-independent PCM/WAV/marker/seek core must not depend on any external namespace')
for token in FORBIDDEN:
    if token in core:
        fail(f'windows/VoiceMessages.cs references forbidden dependency "{token}"')
print('PASS: windows/VoiceMessages.cs has no using directives and no forbidden WinForms/PeerEngine/attachment/LM4/device dependencies', flush=True)

local_copies = [p for p in (root / 'windows').rglob('*')
                if p.name in ('manifest.json', 'wav_cases.bin', 'pcm_cases.bin', 'contract.md', 'pcm-contract.md')]
if local_copies:
    fail('platform-local copy of the shared voice fixture/contract files found under windows/: ' + ', '.join(str(p) for p in local_copies))
print('PASS: no platform-local copy of the canonical tests/voice_messages/vectors/ fixtures exists under windows/', flush=True)

run_ps1 = (root / 'tests' / 'run.ps1').read_text(encoding='utf-8')
m = re.search(r'--voice-check\s+\(Join-Path\s+\$PSScriptRoot\s+[\'"]voice_messages/vectors[\'"]\)', run_ps1)
if not m:
    fail("tests/run.ps1 does not invoke --voice-check against the canonical (Join-Path $PSScriptRoot 'voice_messages/vectors') path")
print('PASS: tests/run.ps1 invokes --voice-check against the canonical tests/voice_messages/vectors/ path, not a redirected copy', flush=True)
