# LAN Messenger — Android 0.8.7 / Windows 0.8.8

Windows 0.8.8 fixes fragmented image/card repainting during scrolling and conversation switching. It remains compatible with Android 0.8.7.

0.8.7: ordinary and Fast downloads ask where to save, write directly there, and open on click after completion. Fast file bytes use plaintext TCP; authenticated TLS still authorizes the transfer. Normal file bytes use TLS. Both save one plaintext copy at your chosen destination. Update both endpoints. Ordinary inline photos still appear automatically. Partial downloads resume at 256 KiB boundaries; clearing chat preserves your saved file.

Local project layout: this `source` folder is the Git repository; release files and build/test history are in the sibling `outputs` folder. The Android signing key is kept privately in `source/.private/development.keystore` and is excluded from Git and source archives.

Android 0.8.4 speeds up conversation opening by showing the newest 10 messages first and loading older messages on upward scroll. It caches decoded image thumbnails in memory, avoids decoding non-image attachments, and shows text messages after local save while network delivery continues in the background. This release has not been timed on a physical Android device yet.

Android 0.8.6 fixes small encrypted reads causing excessive TLS writes, certificate hashing and cancelled watchdog accumulation. Updated Android peers negotiate one continuous file stream, saving encrypted progress in 256 KiB blocks. A connection drop resumes from the last saved block; Pause and app restart keep that progress. Update both phones. TLS, certificate verification, encrypted local files and the daily upload policy remain enabled. Older senders use the existing 100 MiB segment protocol. A changed/corrupt source or local storage failure stops with an error instead of retrying the same file indefinitely. Physical-phone Wi-Fi timing is still required; loopback timing is not a Wi-Fi throughput guarantee.

0.8.3 automatically downloads incoming photos and renders supported images inline. Other files still require Download. Windows layout fixes and the Android daily upload policy remain. Update each receiving device to 0.8.3 for automatic photos.

Private LAN messaging without accounts, a host computer or a cloud server. English interface with Unicode messages. **Update both devices and every group member to 0.8.0 for attachments and group synchronization.**

## Android daily upload policy (0.8.2)

Per app/device identity, all ordinary, Fast file and relayed file payloads share one daily upload counter and one aggregate speed cap:

| Payload sent today | Aggregate upload cap |
|---|---|
| Below 2 GiB | Unlimited |
| 2–5 GiB | 30 MiB/s |
| 5–10 GiB | 20 MiB/s |
| 10 GiB and above | 10 MiB/s |

Uses binary units: 1 GiB = 1024³ bytes; 1 MiB/s = 1024² bytes per second. The cap changes within a transfer at the threshold, rather than waiting for the next file. Repeated uploads, retries and group relay bytes count again because they consume bandwidth. Offers, local preparation, downloads, text, avatars and TLS overhead do not count. Each completed socket payload write is counted; it is not an acknowledgement of recipient disk storage.

Profile displays today's uploaded amount and current cap. The encrypted counter persists separately from conversations, resets when the local calendar advances to a new day, and is not reset by clearing chat or normal restart. Clock rollback does not grant another allowance. Usage is checkpointed at 4 MiB or one second of active upload and flushed after transfers/on service close; abrupt process/power failure may lose up to approximately 4 MiB since the last checkpoint. No disk write per network packet.

This is Android-only, local-device enforcement, not a centrally managed account quota or protection against a modified/rooted app. Windows 0.8.1 remains compatible and uncapped. No other proposed anti-flood rules were added in this release. Install LanMessenger-0.8.7.apk over the existing app without uninstalling.

## Sending files

Both attachment actions show a draft preview. Choosing a file does not send it: press Send.

- **Attach / Photo / File / Camera:** up to 1 GiB; prepares an encrypted local snapshot.
- **Fast file:** a dedicated action for large files, capped at 1 TiB in metadata. It stores a protected reference to the original instead of another encrypted sender copy. Preparation reads the original once to calculate SHA-256. Keep the source unchanged and available until recipients finish.

The receiver first gets name, size, caption and integrity metadata. **Ordinary incoming images download automatically** (JPG/JPEG, PNG, GIF, BMP, WebP, TIFF, HEIC/HEIF and AVIF filename extensions). Other files and Fast offers require Download and a destination choice. Supported downloaded images appear inline; the existing 20 MiB/32-megapixel preview guards and platform decoder support still apply. The original image remains available to Save when preview is unsupported. Profile avatars remain separate. Two background image downloads run at a time; pending incoming images can resume automatically after restart. Pause stops that image for the current session; manual Download can retry. Source must be reachable and verified, or a verified group member must hold the complete image. Sender preview and explicit Send remain unchanged.

Downloads use separate connections and small streaming buffers. Two simultaneous file-serving streams are allowed; queued text precedes group sync and dispatches promptly. TLS and device verification remain enabled.

Between Android 0.8.6 peers, files stream over one pinned TLS connection with encrypted **256 KiB checkpoints**. The progress percentage counts stored blocks and does not reset on a connection drop. Only the unfinished block is retransmitted. After an app restart, press Download again for other files; pending images are scheduled automatically. The final SHA-256 is calculated during reception; a saved prefix is read once when reopening a paused download. Existing verified 100 MiB checkpoints are imported when upgrading. Transfers involving an older sender still use verified **100 MiB segments**, and an incomplete legacy segment can restart. These are streaming checkpoint sizes, not whole-file RAM buffers or parallel striping.

In 0.8.7, manual Download writes directly to the chosen location; Open launches that same file. There is no additional private receiver copy. Existing cached files migrate on destination selection. Auto-downloaded ordinary photos keep their private image cache. Speed depends on network, storage, CPU and the Android file provider; no real-Wi-Fi throughput guarantee is made.

## Interface fixes

Windows retains existing message cards when new messages arrive or statuses change. Progress updates labels without rebuilding the feed, on both platforms. Progress never creates message notifications. Blue presence dots indicate online; gray indicates offline.

Android keeps Send visible next to scrollable attachment actions. Unknown-size ordinary files stage to disk with a bounded buffer instead of one large byte array. Fast file uses persisted document read permission; removing the original or revoking access makes it unavailable.

## Install and update

Android: install LanMessenger-0.8.6.apk on both phones over the existing app signed by the original development key. Do not uninstall if you want to preserve data.

Windows: Exit through the tray menu, extract LanMessenger-Windows-0.8.3.zip and run LanMessenger.exe with its companion files. Requires .NET Desktop Runtime 9.

Identities, verification, contacts, groups, local history and old downloaded attachments remain. Existing file queues become manual offers. Older clients cannot push unsolicited file bodies into 0.8.0. Direct text retains LM4 framing, but attachments and group sync need 0.8.0 on both ends. Do not downgrade.

## Pair, chat and groups

Open apps on the same reachable LAN. Discovery is automatic; Add by IP is available. Open Verify device on BOTH devices, compare the complete safety code through a trusted channel, and confirm only if it matches.

Queued means saved on the sender. Delivered means the message or **file offer** was saved on the recipient, not that the file was downloaded. Seen means the conversation was opened; group status aggregates members.

Create a group with 2–15 verified contacts. Every pair must independently verify each other. Membership is fixed; create a new group to change it. Signed metadata relays through verified members. Download can use a verified member holding the complete attachment; a member with only an offer cannot serve it. Direct downloads need the original sender online.

New group messages and data expire 168 hours after original sending; relaying never renews expiry. Old history predating the expiry feature is retained. Clear conversation deletes only local history, pending sends, private cached data and source references; files at user-selected destinations remain. Contacts/groups remain. External original Fast file sources are never deleted.

## Background, network and storage

Windows receives while hidden/minimized. Close hides to tray; Exit stops it. Notifications contain generic text and open the corresponding conversation. Windows notification settings may suppress banners.

Android uses a foreground service with an ongoing notification. Allow notifications; Profile > Go offline stops reception. Reopen after reboot. Force-stop, Doze, manufacturer restrictions or Wi-Fi loss can delay reception.

UDP 43871 discovery; TCP 43872 transport. Router client isolation can block communication. The app does not change firewall/router settings. Discovery exposes names, IDs and presence and does not establish trust.

Network protection remains mutual-certificate TLS 1.2 ECDHE-RSA/AES-GCM with pinned certificates and explicit safety-code verification. Changed keys are not silently trusted. Group metadata is signed by its original sender.

Data: %LOCALAPPDATA%/LanMessenger on Windows; private files/peer-data on Android. DPAPI / Android Keystore protect history, identity and source references. Snapshots and downloaded segments use the existing AES-256-CBC streaming format with random keys wrapped by the platform protector. Segment hashes are checked over authenticated TLS; the whole-file SHA-256 is checked before completion. This is not a new independently audited authenticated-storage scheme. Old blobs remain readable. Original Fast file sources retain their existing storage protection.

Clear is logical deletion, not forensic erasure. OS keys are needed to read protected storage; encrypted files alone are not portable backups.

## Build and verify

```powershell
dotnet build windows/LanMessenger.csproj -c Release --configfile NuGet.Config -o windows-dist-v080
.\android\build.ps1
.\tests\run.ps1
```

Android: JDK 17, SDK 34, build-tools 35.0.0. Build script accepts SDK/JDK/build paths. Restore the ORIGINAL development.keystore; missing keys fail the build. Never publish the key. Windows restores the vendored BouncyCastle package using NuGet.Config.

Tests run real Java/C# engines with isolated data and test protectors: migration, verification, tampering/spoof rejection, restart queues, groups/relay/expiry, manual downloads, corruption, local clear, receipts and avatars. Transfer regression uses 128 MiB with Java heap capped at 64 MiB, pause/restart across a 100 MiB checkpoint, verified export and text latency during a throttled transfer. Native Windows tests cover notifications, draft previews, manual Download, retained controls and screenshots.

An additional optional tests/large_transfer.py run transferred and fully verified 1025 MiB with Java heap capped at 64 MiB (12.221 s on local loopback, not a Wi-Fi guarantee). Physical Android picker/camera/Keystore/background behavior, real Wi-Fi throughput and larger files still require device acceptance testing. See HANDOFF.md for exact measured results and release paths. Work and commits remain local; no remote is configured.
