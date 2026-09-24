# LAN Messenger continuation

The user chose one shared Git branch with separate Android and Windows status records.

- Read `PROJECT_STATUS.md` and the relevant `windows/STATUS.md` or `android/STATUS.md` before continuing platform work. `HANDOFF.md` is historical context, not a claim that every older feature exists identically on both platforms.
- Identify each task as Windows, Android, or Both. Keep platform-specific work scoped accordingly. Do not infer feature parity or automatically add missing features solely because they exist on the other platform.
- Update the affected platform status after changes. Update the comparison when parity or compatibility changes. Separate implemented code, automated verification and actual device acceptance.
- For shared wire/protocol changes, review both implementations and run relevant interoperability checks. Platform UI-only changes do not require rebuilding the other app.
- Canonical source is `D:\LAN-Messenger\source`; release/build outputs are in `D:\LAN-Messenger\outputs`. Keep one source tree; do not create duplicate platform repositories as an organizational shortcut.
- Keep the Android signing key private and preserve upgrade compatibility. Never include `.private` in source archives.
- Latest user preference is local commits; push only when requested. Do not rename or reconfigure Git branches/remotes as part of routine status maintenance.
