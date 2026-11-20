# Firebase setup for Shraddha AI

The Android app is registered as `in.shraddha.ai` in Firebase project `shraddha-ai-1d3bf`. Its public client configuration and Realtime Database URL are prefilled in the Android build from the supplied Firebase Android configuration.

## Required Firebase Console setup

1. Enable **Authentication > Sign-in method > Email/Password**.
2. Create/enable **Cloud Firestore**.
3. Enable **Realtime Database** and confirm its URL is `https://shraddha-ai-1d3bf-default-rtdb.firebaseio.com`.
4. Publish the Firestore rules and `firebase/database.rules.json` in this folder. Do not use public/test-mode rules in production.
5. Build/install the app, open **Cloud & Data**, create an account, then explicitly opt in before backup or restore.
6. Enable Firebase App Check (Play Integrity for Play builds) before production release.

## Cloud memory format

The app encrypts the selected memory note locally with AES-256-GCM. A key is derived from the account password using PBKDF2-HMAC-SHA256 (210,000 iterations). The encrypted payload is written to both Firestore and Realtime Database at `users/{uid}/vault/{key}`. Restore reads Realtime Database first and falls back to Firestore if the RTDB record is missing or unavailable.

The account password is not uploaded. If the password is lost, the encrypted backup cannot be decrypted. Face/voice biometric templates, child records, provider API keys, OTPs, and payment data are excluded.

## Security and deployment boundary

Realtime Database rules restrict each authenticated account to its own UID path and validate the encrypted record shape/size. Rules files in this repository are **not automatically published** to the Firebase project. The project owner must publish them in Firebase Console or authorize a Firebase CLI deployment. Never put service-account private keys in this repository.

Storage uploads and FCM handlers are not implemented by this change; their SDK dependencies remain preparatory only.
