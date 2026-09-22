# LAN Messenger LM4 protocol (0.4.0, extended 0.5.0, extended 0.6.0)

## Discovery

UDP 43871, approximately every 2–3 seconds, to broadcast and remembered addresses:

`LM4<TAB>HELLO<TAB>device-uuid<TAB>base64-utf8-display-name<TAB>tcp-port`

The packet's source address is used. Presence lasts 12 seconds after a valid discovery packet/handshake. Discovery is plaintext and unauthenticated; identity/name/address claims can be spoofed. It never grants message trust. There is no central server.

## Encrypted direct transport

TCP 43872; mutual-certificate TLS 1.2, `TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384` or `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`. Android uses JSSE and Windows Bouncy Castle TLS. Stable self-signed RSA-3072 identities are generated per installation and protected locally. Both endpoints require a certificate and check its validity dates. Public CA/hostname validation is replaced by explicit certificate fingerprint pinning in PeerEngine.

Unknown certificates can establish TLS only for pairing metadata. The initiator sends HELLO, the receiver responds HELLO then `LM4<TAB>PAIR` if unverified, or `LM4<TAB>READY` when its saved verification matches the actual TLS certificate. Sender checks recipient ID and its saved pin before transmitting message content; receiver checks authenticated sender ID and pin before accepting it.

Frames inside TLS are UTF-8, LF-delimited, bounded to 16 KiB. Message text is at most 2,000 UTF-16 code units. Empty text without an attachment, wrong recipients, malformed IDs and out-of-range timestamps are rejected.

Message: `LM4<TAB>MSG<TAB>message-uuid<TAB>sender-uuid<TAB>recipient-uuid<TAB>base64-name<TAB>epoch-ms<TAB>base64-text<TAB>group-id-or-empty<TAB>base64-filename-or-empty<TAB>file-size<TAB>sha256-or-empty`

For internal regression harnesses, an 8-field MSG is accepted as a direct text message. Production 0.4 sends all 12 fields. If the filename is present, exactly file-size raw bytes follow the LF inside the same TLS stream. Size is 0–10,485,760 bytes; SHA-256 is mandatory, including for empty files. Plain text has empty filename/hash and size zero. Reject unsafe names, invalid metadata and bad hashes; store encrypted blob and history before ACK. File display names never determine internal paths. No auto-execution or external opening. Retries restart the entire attachment.

Acknowledgement: `LM4<TAB>ACK<TAB>message-uuid<TAB>recipient-uuid`

## Seen receipts (0.5.0)

Seen is local-first and asynchronous: it is never sent inline with ACK, because a message can be durably delivered long before anyone opens the conversation. Once the recipient's UI marks a message read, the recipient's own periodic peer loop (the same loop that flushes queued sends) opens a new authenticated, pinned connection to the original sender and sends `LM4<TAB>SEEN<TAB>message-uuid<TAB>reader-uuid`, exactly like a queued MSG send. The sender replies `LM4<TAB>SEENACK<TAB>message-uuid` once it updates its own record, and only then does the reader consider that message confirmed. A `SEEN` frame's `reader-uuid` must equal the authenticated peer ID from that connection's HELLO; a receiver that does not recognize the frame (any 0.4.x peer) simply drops the connection, so mixed-version networks keep working — the older peer just never reports Seen.

The reader's own status field is reused for this: `Received` becomes `Read` the moment the local user views it (this is also what "unread" is computed from — see below), then `Seen` once the sender's SEENACK lands. On the sender's side, a matching outgoing row moves `Delivered` → `Seen` when a valid SEEN frame arrives, and only from a row that is not already `Seen`. For a group message, the sender holds one outgoing row per recipient (see Groups below); a SEEN frame only ever updates the row for that specific authenticated reader, so the UI's aggregate status is `Seen` only once every recipient's row is `Seen`, `Delivered` once every row is at least `Delivered`, and the existing partial-count form otherwise. Clearing a conversation removes the underlying rows, so a clear silently cancels any Seen confirmation still in flight for it, the same way it already cancels unsent queued sends.

Unread counts are purely local and never touch the wire: a conversation's unread count is the number of its messages still in `Received` status (i.e. not yet marked `Read`). Marking a conversation read is a UI action (opening it while the window is visible and not minimized/backgrounded), not a protocol event.

## Verification

Fingerprint = lowercase SHA-256 hex of certificate DER. Sort these two strings ordinally: `localUUID:localFingerprint`, `peerUUID:peerFingerprint`. Safety code = uppercase SHA-256 hex of UTF-8 `LAN Messenger pairing v3\n` followed by the sorted strings separated by LF. Show all 64 hex characters in eight groups.

Users compare the code out of band and confirm on each device. Confirmation checks the code again to reject a stale dialog. A different fingerprint never replaces the saved verified fingerprint: it displays KEY CHANGED and requires explicit revocation before accepting a replacement key. Return of the original pinned key is still trusted. Untrusted discovery cannot override the pin.

## Delivery semantics

1. Persist outgoing messages as Queued before reporting success to the UI.
2. Retry while the sender is online, including after restart.
3. Receiver validates the sender pin, intended recipient and bounds, then durably saves a new message as Received.
4. Emit the received/notification event only for a newly saved `(sender ID, message ID)` pair. Retransmissions receive ACK without duplicate history or alert.
5. Only a matching ACK over a trusted connection marks the sender's message Delivered. It is not a read receipt.
6. A read receipt is a separate, later event: see Seen receipts above.

Both peers must eventually be reachable simultaneously. No third party holds messages when the sender is off.

## Local persistence and migration

`state.txt` and `.bak`: ASCII `LMSEC3\n` followed by protected snapshot bytes. Decrypted header: `LMSTORE4<TAB>UUID<TAB>base64-name`. Peer rows store ID, name, host, port, observed fingerprint and verified fingerprint. Message rows retain ID, sender, recipient, timestamp, text and status. END terminates a complete snapshot.

Windows: current-user DPAPI. Android: Android Keystore AES-256-GCM; 12-byte nonce followed by ciphertext and 16-byte tag. Identity is separately protected in `identity.sec`. Production never uses the harness test protectors.

Write a temporary file, flush, retain old primary as backup, then rename temporary into place. At startup, try backup if primary is unreadable; fail with a recovery message if neither loads. Plain LMSTORE2 and protected LMSTORE3 snapshots migrate to LMSTORE4, preserving UUIDs, contacts, history, verified fingerprints and pending messages. Identity and the pairing-code v3 domain string are unchanged so existing verification remains valid. Two initial saves replace both primary and backup with encrypted snapshots. This does not securely erase older plaintext from filesystem snapshots or previous backups. No downgrade conversion is provided.

LM4 is not wire-compatible with LM3/LM2. Upgrade every peer; 0.3 pins and safety codes remain valid. See tests/integration.py and tests/WindowsUi for executable protocol and notification checks; they are not an independent security audit or hardware certification.

## Groups

Creator sends `LM4<TAB>GROUP<TAB>group-uuid<TAB>owner-uuid<TAB>base64-name<TAB>comma-separated-member-uuids` over an explicitly verified TLS connection. Receiver requires owner == authenticated sender, 3–16 unique valid IDs including sender and itself, and a nonempty name of at most 50 UTF-16 units. Group ID must not collide with the receiver or a contact. Existing definitions accept only an identical owner/name/ordered membership; replacement is rejected. Reply: `LM4<TAB>GROUPACK<TAB>group-uuid` after durable save.

The creator queues invitations independently and persists recipients' acknowledgements. It need only be online until initial distribution. Members send directly; a nonempty MSG group ID must exist locally and include both authenticated sender and recipient. No implicit certificate trust or third-party relay. Sender duplicates one logical message into per-recipient outbox rows with the same message ID. UI groups those rows by sender/ID and aggregates delivery/seen status; ACK and SEEN each update only the addressed recipient's row (see Seen receipts above).

G snapshot rows: group ID, owner, Base64 name, member CSV, acknowledged-invitation recipient CSV. M snapshot rows extend the old eight fields with group ID, Base64 filename, size, hash. Protected attachment paths are `attachments/<sender-uuid>-<message-uuid>.sec` and never use untrusted filenames.

## Group relay and 7-day expiry (0.6.0)

1:1 messages are completely unaffected by everything in this section — no wire, storage or behavior change. This section only ever applies where `group-id` is non-empty.

### Why a signature is needed

Before 0.6.0, a message's sender identity was authenticated purely by the live TLS connection it arrived on (`sender-uuid` in a MSG frame must equal the authenticated peer from that connection's HELLO). That is sufficient for direct delivery, but it cannot survive being relayed by a third member: if member B simply forwarded A's bytes to C, C would be trusting *B's* connection for content claiming to be *A's*, which is exactly the "third-party relay with implicit trust" this project has always explicitly ruled out. 0.6.0 keeps that principle intact instead of weakening it: relayed content carries its own proof of authorship, independent of who is currently relaying it, so a relaying member can pass bytes along but can never forge or alter what a fellow member is credited with saying.

Every device already has a stable RSA-3072 identity (its TLS certificate/key, generated once per install — see Encrypted direct transport above). 0.6.0 reuses that exact key pair for signing; no new key material, key exchange or group-shared secret is introduced. A device signs (`SHA256withRSA`/PKCS#1) the UTF-8 bytes `message-id<TAB>group-id<TAB>sender-uuid<TAB>epoch-ms<TAB>base64-text<TAB>base64-filename<TAB>file-size<TAB>sha256-or-empty` — i.e. exactly the content fields of the message, so the signature is invalidated by any tampering with any of them, including by a relaying member.

**Consequence a reader should know:** a device can only accept a *relayed* group message if it has already independently paired and verified the *original sender* at some point (their public key must already be pinned) — receiving it via a relay never substitutes for that. If C has never verified A directly, C simply cannot yet trust anything relayed on A's behalf; once C does verify A (even much later, even while A is offline), sync will deliver A's still-unexpired backlog on the next cycle.

### Wire changes

Peer rows now also carry each peer's public key once observed, alongside its existing fingerprint (captured at the same point in the already-authenticated TLS handshake that fingerprints are captured — no new exchange): `P<TAB>id<TAB>base64-name<TAB>host<TAB>port<TAB>fingerprint<TAB>verified-fingerprint<TAB>base64-SubjectPublicKeyInfo`. Older 5/7-field P rows still load; the public key is simply empty until the next handshake with that peer refills it.

Direct group MSG frames (group-id non-empty) grow one field: `LM4<TAB>MSG<TAB>...<TAB>sha256-or-empty<TAB>base64-signature` (13 fields). **1:1 MSG frames are untouched (still exactly 8 or 12 fields, byte-for-byte as in 0.4.0/0.5.0)** — the frame only grows when a group ID is present, so this is a group-only wire change. A direct group MSG's signature is verified immediately using the sender's own pinned key (the sender is, by definition, already independently verified to reach `READY` at all) — this is a consistency check, not new trust, but it does mean a **0.5.0-or-earlier group member cannot understand a 0.6.0 group message anymore** (its frame has an unrecognized field count, so the connection is dropped the same harmless way an unrecognized `SEEN` frame already is) — unlike Seen receipts, group relay is not backward-compatible, so every member of a group that wants to keep using it must update. 1:1 messaging between any version combination is completely unaffected.

New frames, all group-only, all requiring an already-`Trusted` connection exactly like MSG/GROUP/SEEN:

- `LM4<TAB>RELAY<TAB>message-id<TAB>group-id<TAB>original-sender-uuid<TAB>base64-name<TAB>epoch-ms<TAB>base64-text<TAB>base64-filename<TAB>file-size<TAB>sha256-or-empty<TAB>base64-signature`, followed by `file-size` raw attachment bytes on the same stream if present (identical convention to MSG). Sent by whichever member currently holds a copy, to a fellow member who is missing it. Reply: `LM4<TAB>RELAYACK<TAB>message-id`.
- `LM4<TAB>SYNCREQ<TAB>group-id<TAB>comma-separated-known-message-ids>` — sent by a device, each cycle, to every other reachable and verified fellow member of each group it belongs to (this is what makes "C comes back online and pulls from B" work without B needing to do anything special: C is always the one asking). The list is the requester's own non-expired message IDs for that group, so the responder can compute exactly the delta.
- The responder streams one `RELAY` frame per message it holds for that group that (a) is not in the requester's list, (b) is not expired, and (c) the requester's peer/group membership checks out (same `AllowedGroup` gate MSG/GROUP already use) — waiting for `RELAYACK` before sending the next — then sends `LM4<TAB>SYNCDONE` and closes. An unauthorized or non-member requester simply gets no useful response.

A `RELAY` (or a signed direct group MSG) is accepted only if: the relaying/sending connection is `Trusted`; `group-id` names a group the receiver actually belongs to, that the connected peer also belongs to, and that `original-sender-uuid` also belongs to (`AllowedGroup`, reused unchanged); the receiver already has a **pinned, verified** public key for `original-sender-uuid`; the signature verifies against that pinned key over the exact fields listed above; it is not expired (see below); and it passes the same bounds/file validation MSG already applies. Deduplication reuses the exact existing `(sender-uuid, message-id)` check — relayed or direct, a message is stored and surfaced at most once, and a duplicate (from any source) still gets acked without re-adding or re-notifying.

### 7-day expiry

A group message and its attachment stop being valid exactly `Time + 168h` after the message's own original `epoch-ms` — which never changes, no matter how many times or through how many members it is relayed or re-synced, so re-forwarding cannot renew it. Each device purges its own expired group messages (and deletes their attachment file the same way Clear conversation already does) at startup, before any sync exchange runs, and once per periodic cycle thereafter. Independently of local purging, *any* incoming expired group content — a direct MSG, a RELAY, doesn't matter — is rejected outright on arrival, so an old device with a stale, out-of-date clock-behind-on-cleanup copy can never hand expired content back to someone who has already purged it.

To avoid ever deleting pre-existing history, only messages created from 0.6.0 onward are expiry-eligible at all (a per-message flag, not a device-wide cutover date, so it survives group members updating at different times without disagreement): a group message a 0.6.0+ device sends, or accepts (directly or via relay), is marked eligible; anything already on disk from 0.4.x/0.5.0 loads as not-eligible and is never auto-deleted by this feature, exactly as before. Manual "Clear conversation" is unaffected and still works on anything, eligible or not.

M snapshot rows grow two more fields for this: `...<TAB>base64-signature-or-empty<TAB>eligible-flag(0-or-1)`. Older 8/12-field M rows still load, with an empty signature and `eligible=0`.

## Clear conversation

Clear removes only matching local messages, cancels their unsent outbox rows and deletes their local blobs. It retains contacts, pins, group definitions and `(sender,id)` tombstones as H snapshot rows. Both primary and backup snapshots are rewritten. A duplicate already-cleared message is ACKed but neither displayed nor notified again. It does not clear anyone else's device or recall bytes already sent. In-flight sends recheck queue existence before transmitting and skip updating removed rows on later ACK.

The storage envelope remains LMSEC3 followed by OS-protected bytes; the decrypted schema is LMSTORE4. There is no downgrade migration.
