# Security and Data Hub design (v0.5.0)

## Device sharing and file transfer

- Category permissions are opt-in and stored locally per device.
- A device publishes only its own category policy.
- Sharing follows request → receiving-device approval → short-lived one-time category grant → bounded transfer.
- Android file transfer uses the Storage Access Framework (SAF); the sender chooses a source and the receiver chooses a save destination.
- File transfers support files from 1 byte through 100 MiB using 48 KiB chunks (maximum 2,134 chunks).
- Each chunk is sent inside the mutually fingerprint-verified E2E AES-GCM peer-message channel. The relay forwards ciphertext; it does not receive the file bytes in plaintext.
- The receiver validates exact chunk lengths, persists its received-chunk bitmap, and verifies the complete file with SHA-256 before allowing the final save/complete acknowledgement.
- Resume requests are based on the receiver's persisted missing-chunk indexes rather than depending solely on the relay's in-memory ACK bitmap. The sender checks the requesting paired-device ID, transfer ID, chunk count, uniqueness and bounds of every requested index before retransmitting.
- Incoming partial file bytes are stored in the app-private `files/shraddha-transfers` directory. The selected final destination is written through SAF.
- The relay keeps a short-lived in-memory transfer manifest to validate live chunk routing and acknowledgements. Relay process restart may require re-establishing the approved transfer before retransmission; durable server-side transfer sessions are not yet implemented.
- File names, sizes, SHA-256 manifest, transfer IDs, and resume/ACK control metadata are currently visible to the trusted relay. Only file chunk payloads are E2E encrypted.
- Partial files are stored in app-private storage but are not yet encrypted at rest with an Android Keystore key. Device-level file-at-rest encryption remains a hardening item.
- The current Android transfer activity is not proof of a complete Windows PC native file-transfer UI. Android-to-Android protocol and Windows cross-device acceptance need separate validation before claiming full Android↔PC transfer support.

## Visual sharing

- Camera sharing is user-started, permission-gated, routed only to the selected paired device, and accompanied by Android privacy indicators and an ongoing foreground notification. Rear/front selection is available.
- Screen sharing requires the Android MediaProjection system consent flow.
- Screen and camera frames use authenticated E2E encryption and peer fingerprint confirmation.

## Security limitations and production gates

- Pairing/session registry and live transfer grants are in-memory; relay restart clears these sessions.
- Pairing credentials are stored using the Android device credential store, but server-side durable multi-instance session state is not implemented.
- No formal penetration test or independent security audit has been performed.
- Real Android↔Windows pairing, fingerprint confirmation, interrupted large-file transfer, relay restart, destination write and hash-mismatch rejection must be exercised on physical devices.
