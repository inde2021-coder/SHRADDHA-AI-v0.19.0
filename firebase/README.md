# Firebase setup for Shraddha AI

1. Create a Firebase project and register Android package `in.shraddha.ai`.
2. Enable **Authentication > Email/Password** and create a **Cloud Firestore** database.
3. Publish `firestore.rules` from this folder. Do not use test mode in production.
4. Copy `local.properties.example` to `local.properties` in the Android project root and replace every placeholder with the values from Firebase Project settings.
5. Sync Gradle and run the app. Open **Secure Cloud Sync**. Create an account, then explicitly opt in before backup/restore.
6. Enable Firebase App Check (Play Integrity for Play builds) before production release.

The current implementation uses Firebase Authentication and Firestore. Cloud memory payloads are encrypted client-side with AES-256-GCM; a key is derived from the account password using PBKDF2-HMAC-SHA256. Firebase stores ciphertext, not the plaintext note. If the user loses the password, this backup cannot be decrypted. The password is not uploaded by this feature.

Storage and FCM SDKs are included as dependencies for the next integration stage, but file uploads and push-notification handlers are **not yet implemented**. `storage.rules` is a least-privilege starting point; deploy it only when Storage is enabled. Biometric templates, child records, API keys, OTPs, and payment data are not included in cloud sync.
