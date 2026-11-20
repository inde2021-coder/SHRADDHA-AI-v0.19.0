# v0.19.0 Build/Test Report

## Latest automated verification

### Android and backend
- GitHub Actions run: #280
- Commit: `d8b9af6d9b780c0ce97fd9b471ce65455adfd272`
- Android unit tests: **PASS**
- Backend JavaScript syntax: **PASS**
- Backend security/API tests: **PASS**
- Android debug APK build: **PASS**
- ARM split generation: **PASS** (`arm64-v8a`, `armeabi-v7a`)
- APK native ABI verification: **PASS** (each APK contains only its matching ABI)
- SHA-256 generation and verification: **PASS**
- Artifact: `shraddha-ai-debug-apks-arm`; ID `14603898058`
- [Android CI run #280](https://github.com/inde2021-coder/SHRADDHA-AI-v0.19.0/actions/runs/44680183713)

### Windows PC Companion
- GitHub Actions run: #7
- Python protocol/action tests: **PASS** (13 tests)
- Windows PyInstaller executable build: **PASS**
- Artifact: `ShraddhaAI-PC-Companion-Windows`; ID `14603647797`
- [Windows CI run #7](https://github.com/inde2021-coder/SHRADDHA-AI-v0.19.0/actions/runs/44679699614)

## Implemented in this verification batch
- Voice service now stops its foreground notification and wake lock if the manager reports a fatal microphone/API-key failure; it does not silently restart after process death.
- Speaker AudioTrack failures are contained and can be recreated; fatal microphone errors release audio resources and leave an actionable error state.
- Password/PIN input contents are redacted from both current and legacy Accessibility screen readers; typing into password fields is blocked.
- Remote Windows keyboard actions reject password, OTP, PIN and CVV-labelled content.
- Accessibility coordinate taps are reported as dispatched, not falsely described as completed.
- Screen-share frame bitmaps are released on success and failure.
- Firebase restore tries Firestore when RTDB is missing, unavailable or cannot decrypt; dual-write failures now disclose which cloud copy succeeded.
- Perplexity text chat uses the Agent API; Groq, Gemini, Claude and OpenAI defaults were refreshed/documented.
- Social sharing opens Android Sharesheet for user review; there is no unattended auto-publishing.

## Not yet verified on physical devices / external accounts
- Install and first-run behavior on a real Android phone.
- Continuous microphone capture, audio routing, background restrictions, battery optimization and reconnect behavior across Android OEMs.
- Camera/Visual Lens, screen projection, live frame relay and remote camera sharing on actual devices.
- Accessibility app-control actions against real third-party apps, including OEM-specific UI variations.
- Calls, SMS and WhatsApp draft flows with actual contacts and installed apps.
- MYRA/Windows pairing and real remote mouse/keyboard/screen-sharing operation across two physical devices.
- Real provider requests using the user's own API keys and account entitlements.
- Firebase Console enablement, Auth sign-in, rule publication, backup/restore round-trip and recovery testing.

## Release blockers / boundaries
- The Android artifact is a **debug APK**, not a production-signed release APK/AAB. A production build needs the owner's release signing key and credentials.
- Firebase rules are committed but are not automatically published to the user's Firebase project.
- The encrypted cloud vault key is derived from the Firebase account password. Losing/changing that password without first restoring and re-encrypting the vault can make old backups unreadable.
- Face/voice matching is similarity evidence only. Liveness is an interaction challenge, not certified anti-spoofing or proof of identity.
- SFace, ECAPA and MiniFASNet model downloads are revision/hash controlled, but accuracy and spoof-resistance still need representative device/user testing.
- The Windows companion is a separate executable, not embedded in the Android APK.
- The provider selector controls typed Chat. Persistent Voice Live still uses Gemini Live.
- Social publishing, scheduled posts, analytics and account management are not implemented; only a user-confirmed share draft is supported.

CI success proves source compilation and the listed automated checks only. It does not prove that every hardware, provider, permission or account-dependent capability works on every device.


## Latest verification update — 2026-11-20 (Android CI #288)
- Tested commit: `c66e7c1970849e7b3b5db6166570a379de6bcadb` (includes masked Gemini and non-Gemini provider-key entry).
- Backend syntax and security/API tests: **PASS**.
- Android unit tests: **PASS**.
- ARM64/ARMv7 debug APK build: **PASS**.
- ABI verification and SHA-256 manifest verification: **PASS**.
- Artifact: `shraddha-ai-debug-apks-arm`; ID `14604401663`; artifact digest `sha256:7206fa42c51e3e6c4e75f54ed5f2c6903cd492991997eced977458bdf469ab3b`.
- APK SHA-256: ARM64 `1f0aecd69a4229d73a13ed2c0ce4ee7c209f8a0a1d248611deea300a14406c09`; ARMv7 `3460feabf3cc5212ca63ef587fa5ada279b4d723f6b17ede26abec40bfcff9f1`.
- [Android CI run #288](https://github.com/inde2021-coder/SHRADDHA-AI-v0.19.0/actions/runs/44681099078)
- The #288 APK artifact was downloaded and both APK checksums and ABI contents were independently verified in the current work session.
