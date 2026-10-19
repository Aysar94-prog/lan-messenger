"""WT06/W10 (Windows) and AT06/A10 (Android): architecture-boundary and canonical-fixture-source
checks for Voice Messages, both platforms. Static, no build/runtime dependency — safe to run
any time.

1. Architecture boundary: windows/VoiceMessages.cs and android/src/net/lanmsg/chat/
   VoiceMessages.java (the transport-independent PCM/WAV/marker/seek core, per I01/I02) must not
   depend on WinForms/Android UI, PeerEngine, the attachment store, LM4 types, or any device API.
   Only later platform-adapter files (VoiceRecorder.*/VoicePlayer.*/ChatWindowVoice*.cs/
   VoiceUi.java/VoiceCard.java/VoicePlayback.java) may touch those.
2. Canonical fixture source: nothing under windows/ or android/ may keep its own copy of the
   shared tests/voice_messages/vectors/ fixtures, and tests/run.ps1 must invoke the real
   production classes (--voice-check / VoiceMessagesCheck) against the canonical path, never a
   redirected/platform-local copy.
"""
import pathlib, re, sys

root = pathlib.Path(__file__).resolve().parent.parent

def fail(msg):
    print('FAIL: ' + msg)
    sys.exit(1)

def check_core(path, forbidden, import_pattern, label, allowed_import_prefix=None):
    raw = path.read_text(encoding='utf-8')
    core = '\n'.join(line for line in raw.splitlines() if not line.strip().startswith('//'))
    for m in re.finditer(import_pattern, core, re.MULTILINE):
        imported = m.group(1)
        if allowed_import_prefix and imported.startswith(allowed_import_prefix):
            continue  # e.g. Java has no C#-style implicit usings; plain java.* stdlib imports are normal, not a coupling.
        fail(f'{label} imports "{imported}"; the transport-independent PCM/WAV/marker/seek core must not depend on any external namespace')
    for token in forbidden:
        if token in core:
            fail(f'{label} references forbidden dependency "{token}"')
    print(f'PASS: {label} has no forbidden imports and no forbidden UI/PeerEngine/attachment/LM4/device dependencies', flush=True)

check_core(root / 'windows' / 'VoiceMessages.cs',
           ['System.Windows.Forms', 'PeerEngine', 'AttachmentStore', 'LM4', 'WaveIn', 'WaveOut', 'winmm', 'VideoCall'],
           r'^\s*using\s+([\w.]+)', 'windows/VoiceMessages.cs')
check_core(root / 'android' / 'src' / 'net' / 'lanmsg' / 'chat' / 'VoiceMessages.java',
           ['android.widget', 'android.media', 'android.app', 'android.content', 'PeerEngine', 'AttachmentStore', 'LM4', 'AudioRecord', 'AudioTrack'],
           r'^\s*import\s+([\w.]+)', 'android/src/net/lanmsg/chat/VoiceMessages.java', allowed_import_prefix='java.')

FIXTURE_NAMES = ('manifest.json', 'wav_cases.bin', 'pcm_cases.bin', 'contract.md', 'pcm-contract.md')
for platform_dir in ('windows', 'android'):
    local_copies = [p for p in (root / platform_dir).rglob('*') if p.name in FIXTURE_NAMES]
    if local_copies:
        fail(f'platform-local copy of the shared voice fixture/contract files found under {platform_dir}/: ' + ', '.join(str(p) for p in local_copies))
    print(f'PASS: no platform-local copy of the canonical tests/voice_messages/vectors/ fixtures exists under {platform_dir}/', flush=True)

run_ps1 = (root / 'tests' / 'run.ps1').read_text(encoding='utf-8')
if not re.search(r'--voice-check\s+\(Join-Path\s+\$PSScriptRoot\s+[\'"]voice_messages/vectors[\'"]\)', run_ps1):
    fail("tests/run.ps1 does not invoke --voice-check against the canonical (Join-Path $PSScriptRoot 'voice_messages/vectors') path")
print('PASS: tests/run.ps1 invokes --voice-check against the canonical tests/voice_messages/vectors/ path, not a redirected copy', flush=True)
if not re.search(r'VoiceMessagesCheck\s+\(Join-Path\s+\$PSScriptRoot\s+[\'"]voice_messages/vectors[\'"]\)', run_ps1):
    fail("tests/run.ps1 does not invoke VoiceMessagesCheck against the canonical (Join-Path $PSScriptRoot 'voice_messages/vectors') path")
print('PASS: tests/run.ps1 invokes VoiceMessagesCheck against the canonical tests/voice_messages/vectors/ path, not a redirected copy', flush=True)
