# Security and Data Hub design (v0.4.0)

- Category permissions are opt-in and stored locally per device.
- A device publishes only its own category policy.
- Data transfer uses request → receiving-device approval → short-lived one-time category grant → bounded payload.
- Current transfer cap is 650 KB in the Android UI; the relay caps JSON payloads at 900 KB.
- Received files are stored in the app-private `files/shared` directory.
- Camera sharing is user-started, permission-gated, routed only to the selected paired device, and accompanied by Android privacy indicators and an ongoing foreground notification. Rear/front selection is available.
- Screen sharing requires the Android MediaProjection system consent flow.

## Known security gaps to close before production
- Pairing/session data is in RAM and lost on relay restart.
- No end-to-end media/file encryption; TLS/WSS protects transport only. The relay can inspect relayed content. Current camera/screen frames are JPEGs over WebSocket, not WebRTC.
- Pairing tokens are currently held in app process memory and are not yet stored using Android Keystore-backed encrypted persistence.
- Data Hub supports selected small files, not true native Contacts/Calendar/SMS synchronization.
- No formal penetration test or independent security audit has been performed.
