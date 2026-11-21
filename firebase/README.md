# Firebase setup for Shraddha AI

The Android app is registered as `in.shraddha.ai` in Firebase project `shraddha-ai-1d3bf`. Its public client configuration and Realtime Database URL are prefilled in the Android build from the supplied Firebase Android configuration.

## Required Firebase Console setup

1. Enable **Authentication > Sign-in method > Email/Password**.
2. Create/enable **Cloud Firestore**.
3. Enable **Realtime Database** and confirm its URL is `https://shraddha-ai-1d3bf-default-rtdb.firebaseio.com`.
4. Publish the Firestore rules and `firebase/database.rules.json` in this folder. Do not use public/test-mode rules in production.
5. Build/install the app, open **Cloud & Data**, create an account, then explicitly opt in before backup or restore.
6. Enable Firebase App Check. The Android app now installs the Debug provider for debug builds and Play Integrity for non-debug/release builds.
7. For debug builds, copy the App Check debug token printed by the SDK to Logcat and register it only in the Firebase Console's App Check debug-token list. Never commit or share that token.
8. Register the release app's signing certificate fingerprints and Play Integrity configuration in Firebase Console. Verify valid tokens in App Check metrics before enforcing App Check on Auth/Firestore/Realtime Database.
9. Do not enforce App Check until the debug token and Play Integrity release path have both been verified on the intended distribution channel.

## Cloud memory format

The app encrypts the selected memory note locally with AES-256-GCM. A key is derived from the account password using PBKDF2-HMAC-SHA256 (210,000 iterations). The encrypted payload is written to both Firestore and Realtime Database at `users/{uid}/vault/{key}`. Restore reads Realtime Database first and independently tries Firestore if the RTDB record is missing, unavailable, corrupt or cannot be decrypted. Backup writes attempt both stores and report partial mirror failures.

The account password is not uploaded. The vault encryption key is derived from the Firebase account password. If the password is lost or changed, old backups cannot be decrypted with the new password. Before changing the Firebase password, restore the vault using the old password and create a fresh encrypted backup using the new password. A dedicated recovery-key workflow is not implemented yet. Face/voice biometric templates, child records, provider API keys, OTPs, and payment data are excluded.

## Security and deployment boundary

Realtime Database rules restrict each authenticated account to its own UID path and validate the encrypted record shape/size. Rules files in this repository are **not automatically published** to the Firebase project. The project owner must publish them in Firebase Console or authorize a Firebase CLI deployment. Never put service-account private keys in this repository.

Storage uploads and FCM handlers are not implemented by this change; their SDK dependencies remain preparatory only. The encrypted cloud vault is an on-demand backup/restore mirror, not a continuous real-time sync listener.
