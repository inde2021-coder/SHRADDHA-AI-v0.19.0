# v0.19.0 Build/Test Report

## Latest automated verification

Do not treat source merge or static checks as proof that every upstream feature works on every device. Build and device verification are still required before release.


## v0.19.2 branch verification update (2026-11-19)

The Android GitHub Actions workflow is configured to run:
- backend JavaScript unit tests and source syntax checks;
- Windows PC companion Python tests;
- Firebase Firestore/Storage rules emulator tests;
- Android Gradle unit tests;
- debug APK packaging with pinned SFace and ECAPA model downloads plus SHA-256 verification;
- Android API 35 emulator instrumentation tests that install/load both ONNX models and exercise encrypted storage and recovery.

**Latest result:** the GitHub connector returned no workflow run and no commit status for the latest redesign-v0.19.2 head. Therefore none of those CI stages can currently be marked PASS. The source tree and workflow configuration are committed, but Android compilation, emulator tests, APK artifact creation and Firebase rules tests remain NOT VERIFIED.

Physical-device People Memory testing, consenting-participant FAR/FRR/EER measurements, photo/video/audio/deepfake spoof tests, Play Integrity/App Check production validation, and real Firebase account/bucket tests also remain NOT RUN. The basic blink/head-turn/phrase challenge is not certified presentation-attack detection.

Do not install or distribute this branch as a verified release until CI and target-device checks complete.
