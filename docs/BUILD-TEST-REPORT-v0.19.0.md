# v0.19.0 Build/Test Report

## CI verification
- Android unit tests: PASS (GitHub Actions run #205, commit `635b2c571326681ef7bcd53268c8162bb1734d5a`)
- Android debug APK build: PASS
- ARM ABI split generation: PASS (`arm64-v8a`, `armeabi-v7a`)
- APK architecture verification: PASS (each APK contains only its intended ARM native ABI)
- SHA-256 checksum generation and verification: PASS
- Backend JavaScript syntax check: PASS
- Backend security/API tests: PASS
- Debug APK artifact: `shraddha-ai-debug-apks-arm`; artifact ID `14602413064`

## Not yet verified
- Installation and runtime behavior on a physical Android phone.
- Microphone, camera, Accessibility, overlay, calls/SMS, notifications and background-service behavior on device.
- Gemini Live/provider/network flows with real user credentials.
- MYRA PC pairing and remote-control end-to-end operation.
- Firebase Console configuration and publication of Firestore/Realtime Database/Storage rules.
- Production signing, release APK/AAB and Play Store compliance.
- Licensed SFace, ECAPA and MiniFASNet model weights are not included as verified production model assets; model import, provenance, tensor-contract and accuracy testing remain required.

Do not treat CI build success as proof that every capability works on every device. This is a debug artifact, not a production release.
