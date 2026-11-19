# Firebase setup for Shraddha AI

1. Create a Firebase project and register Android package `in.shraddha.ai`.
2. Enable **Authentication → Email/Password**, **Cloud Firestore**, **Cloud Storage**, and **Cloud Messaging**.
3. Publish `firestore.rules` and `storage.rules` from the repository root. Do not use test mode in production. The rules enforce owner-only access, exact encrypted backup paths/metadata, size limits and hashed device IDs.
4. Copy `local.properties.example` to `local.properties` in the Android project root and replace every placeholder with the values from Firebase Project settings, including the Storage bucket.
5. Firebase App Check is installed on both Firebase app instances: debug builds use the Debug provider (register its debug token in Firebase Console), and release builds use Play Integrity. Configure the correct SHA-256 signing certificate and Play Integrity project linkage before enforcing App Check in production.
6. In Shraddha AI → Firebase Account & Encrypted Cloud Vault, create/sign in to the owner account. Explicitly opt in before backup/restore.
7. For small recovery snapshots, use Firestore backup (100 KB plaintext limit). For larger selected snapshots, use Firebase Storage (up to 1.4 MB plaintext; encrypted object size capped at 2 MB). Both retain up to 10 timestamped revisions. Restore always shows an overwrite preview and asks for confirmation.
8. For push, explicitly opt in and tap **REGISTER THIS DEVICE FOR PUSH**. Grant Android notification permission when requested. Send a data-only test message from Firebase Console → Cloud Messaging with no notification title/body fields (the app maps approved data event types to generic local notices). **DISABLE PUSH & REMOVE THIS DEVICE** deletes the owner-scoped device token record, disables FCM auto-init and asks FCM to delete the token.

## Encryption and data scope

Cloud recovery payloads are encrypted client-side with AES-256-GCM; the key is derived from the account password using PBKDF2-HMAC-SHA256. Firebase stores ciphertext, not the plaintext note or selected device records. If the user loses the password, the backup cannot be decrypted. The password is not uploaded by this feature.

Selected snapshots include consented person/child records, trained app profiles and workflow recipes, encrypted Expert Project Library, workflow checkpoints, task inbox, active project, digital twin, knowledge graph and private memory. Face/voice biometric templates, API keys, gateway credentials, pairing tokens, OTPs and payment data are excluded. Restore validates allowed record keys and schemas, replaces only selected record families, and attempts to roll back the prior local state if a restore step fails.

FCM device tokens are stored in the Android Keystore-backed encrypted feature store on-device and in the signed-in owner's private Firestore `devices` subcollection. A cached token is never uploaded during ordinary cloud backup/sign-in unless the explicit push-consent flag is enabled; revocation disables that flag locally before attempting remote cleanup. The notification service displays generic event categories only; it does not show message contents, biometric data or other sensitive payloads. Token rotation cleanup and user-initiated revocation are supported. This build receives notifications; outbound FCM delivery must be sent by Firebase Console or a separately secured trusted server.

Storage objects are owner-scoped under `users/{uid}/backups/`, use `application/octet-stream`, and are limited to 2 MB. Firestore metadata and device-token rules are owner-only. Deploy App Check and verify the published rules before production.


## Security-rules tests

The repository includes Firebase Emulator tests for Firestore and Storage ownership, backup metadata, invalid paths, and FCM device records. From the repository root run:

```sh
npm install --prefix firebase/rules-tests --no-audit --no-fund
./firebase/rules-tests/node_modules/.bin/firebase emulators:exec --project demo-shraddha-ai --only firestore,storage "npm --prefix firebase/rules-tests test"
```

The Android GitHub Actions workflow runs these tests before the Android build. Emulator tests validate the rules source; production deployment and App Check still need to be verified in the target Firebase project.
