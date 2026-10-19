Continue LAN Messenger 0.4.2 from the existing source. Read HANDOFF.md, README.md and PROTOCOL.md and inspect Git status first. Keep user data, signing identity, package ID net.lanmsg.chat and English UI. Do not rebuild/redesign or add unrelated features.

Run tests/run.ps1 and android/build.ps1 when relevant. LM4 uses TLS with pinned certificates and explicit safety-code verification. Keep Java/C# interoperable and test data isolated. Unknown/changed keys must never become implicitly trusted. Preserve migration from LMSTORE2/LMSTORE3 to encrypted LMSTORE4 and pending messages.

Current automated builds, protocol tests and Windows notification tests pass. Next acceptance work is physical Android/Windows messaging over Wi-Fi, Android power/background operation, production OS storage protectors, and visible Windows notifications with real clicks. Test harness success does not prove these. Do not claim production release readiness without recording the remaining hardware results.

Android builds must use the existing signing key identified in HANDOFF.md; never publish it or silently replace it. Git is initialized and contains the user's staged snapshot; preserve unrelated staged work. Do not install onto devices, modify firewall/router rules, or publish without user authorization. Communicate in Arabic and report what was actually verified.

The user authorized local conversation clearing, UI polish, encrypted files/photos and fixed-membership groups. They are now part of the app. Preserve them and run tests/features.py via tests/run.ps1. Clear is local only, with duplicate tombstones; group members must verify pairwise. Files are limited to 10 MiB and never auto-open. Group membership is fixed, 3–16 members. Read the current README for actual scope.
