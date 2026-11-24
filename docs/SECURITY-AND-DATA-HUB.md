# Security and Data Hub

## Device identity and stored credentials

- Android pairing credentials are encrypted with AES-GCM. The encryption key is non-exportable and held by Android Keystore; SharedPreferences stores only the IV and ciphertext.
- The Windows companion stores its pairing token through Windows Credential Manager.
- Pairing codes are single-use and expire. The relay authenticates WebSocket connections with device-scoped bearer credentials.
- The relay's live session/device registry is currently in process memory. A relay restart clears active sessions; devices must reconnect or pair again. Run one always-on relay instance until a durable shared session registry is deployed.

## End-to-end encrypted device traffic

- Android and PC use ephemeral P-256 ECDH identities and session-bound HKDF-SHA256 key derivation (protocol v2).
- Both devices must compare and confirm the same short fingerprint through a separate trusted channel. A mismatch or unconfirmed peer blocks encrypted actions, transfers and visual frames.
- AES-256-GCM protects remote-action envelopes, approved data payloads, file chunks and screen/camera frames. The relay validates envelope metadata, permissions, bounds and rate limits, then forwards ciphertext; it does not decrypt these protected payloads.
- Screen/camera binary frames use the authenticated visual-frame v2 envelope: source/target identity, frame type, monotonic sequence, nonce and ciphertext. Plain JPEG frames are rejected by the relay and desktop companion. Replay and malformed-frame checks are applied at the receiving endpoint.
- Some routing metadata (device IDs, message kinds, action kinds, transfer IDs, file names/sizes and timing) remains visible to the relay; E2E encryption does not hide metadata.

## Resumable file transfer

- Android uses the Storage Access Framework: the sender chooses a source file and the receiver chooses the final save destination.
- Files are split into bounded 48 KiB chunks (maximum 100 MiB / 2,134 chunks). Each chunk is protected by the mutually fingerprint-verified, session-bound E2E AES-GCM peer channel; the relay forwards ciphertext only.
- The receiver persists its partial transfer state and missing-chunk bitmap in app-private storage, validates exact chunk lengths, and verifies the completed file with SHA-256 before acknowledging completion.
- Resume requests are accepted only for a live, approved transfer from the paired source device. The sender checks the requester device ID, transfer ID, expected chunk count, uniqueness and bounds of each missing index before retransmission.
- Relay-side transfer manifests are short-lived and in-memory. Resume after a relay restart or expired session is not guaranteed; clients must start a new approved transfer.
- File names, sizes, device IDs, transfer IDs and timing remain routing metadata visible to the relay; file contents remain end-to-end encrypted.

## Consent and remote control

- Screen and camera sharing are separate opt-in permissions. Android screen capture also requires the operating-system MediaProjection consent flow; camera use requires Android camera permission and a visible user-started session.
- Remote control requires target-device opt-in, a live time-limited grant and explicit approval of the initial request.
- The relay rechecks the current target permission, grant expiry, peer identity and current control mode for each encrypted remote action and action result.
- Switching to owner-only (master) mode immediately revokes non-owner grants, pending control requests and pending actions. A target can also revoke access or disconnect.
- Each received control action still requires local per-action approval on the target companion. Sensitive screens and actions blocked by Android, Windows, app policy or OS security remain unavailable.
- Data sharing follows request → recipient approval → short-lived one-time category grant → encrypted payload. Plaintext data-share payloads are rejected.

## Operational and assurance limits

- The relay session registry is not yet durable or multi-instance. Production deployment must use one continuously running WebSocket-capable relay until a shared registry/pub-sub design is implemented.
- Physical Android↔Windows tests are still required for the exact target Android versions, OEM battery policies, screen/camera consent flows, reconnects and real network conditions.
- A formal independent penetration test and security review have not been performed. Passing CI is not a substitute for that assessment.
- Android cannot capture or control every protected surface: banking/OTP/password screens, DRM, apps that block Accessibility, secure system dialogs and OEM-restricted surfaces may be unavailable.
- This design is consent-based remote assistance, not hidden surveillance or a permission bypass.
