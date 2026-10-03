# Android 2.2.65 (versionCode 92)

User-authorized release, 2026-10-03. Existing signing key; ARM64, ARMv7, x86,
x86_64. APK: D:/LAN-Messenger/outputs/LanMessenger-2.2.65.apk.

SHA-256: `12d27c66e0eee09551c08d023d1908b40979dd239bfceb8e541c6c56976812e3`.
Build and APK v2/v3 signature verification passed; signer certificate SHA-256
matches the preceding installed candidate. This package was not installed during
the release step because the user requested stopping physical testing.

Includes app icon, trusted voice/video access, caller-side recipient camera
on/off/front/rear and speaker controls, directional Masters/Slave permission
lists, bounded idle delivery probing and removal of Online multicast acquisition.
Remote microphone control is not implemented. Windows is unchanged.

Known issue: Samsung SM-A075F loses Wi-Fi association during Online operation.
The SM-S908E did not exhibit the same fault. The new pacing/lock changes did not
resolve it. Physical stability acceptance failed; release requested by the user
after stopping the investigation. Do not describe this release as a Wi-Fi fix.

Automated evidence on current source: CallCheck422/0; pacing28/0. Earlier
candidate permission checks36/0 and video-contract suites passed. No claim of
full regression or ten-minute physical acceptance. No further device tests run.
Permissions, app identity and signing key preserved during preceding upgrades.
