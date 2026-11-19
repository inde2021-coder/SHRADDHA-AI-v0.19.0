# SHRADDHA AI v0.19.0 — Step 2 Source Audit

Audit target: `work/step-01-source-audit-fixes` (compared with `main` where relevant).
Scope: Android Gradle configuration, manifest, package/source layout, biometric capture, Firebase vault, voice foreground service, backend, test and release evidence.

## Audit result

**Step 2 static source audit: COMPLETE. Android build/runtime: NOT VERIFIED.**
This report records source-level findings; it does not claim an APK build or device test.

## Confirmed blockers and defects

1. **Biometric module cannot compile as currently represented.**
   - `app/src/main/java/in/shraddha/ai/biometric/` contains only `BiometricCaptureActivity.kt`.
   - That activity references `LocalBiometricModelEngine`, `BiometricLivenessGate`, and `BiometricConsentVault`, but those implementations are absent from this package tree.
   - `app/build.gradle.kts` does not declare the CameraX, ML Kit Face Detection, or AndroidX ExifInterface artifacts imported by the activity.
   - `BiometricCaptureActivity` is not declared in `AndroidManifest.xml`.
   - Therefore the camera/voice enrollment and matching flow is not a build-ready, callable feature. Do not advertise it as working.

2. **Biometric model files are external prerequisites.**
   - Runtime code explicitly requires app-private `filesDir/biometric-models/sface.onnx` or `ecapa-any-length.onnx`.
   - No model weights are present in the inspected Android biometric source directory. Model provenance, license, expected tensor shape, preprocessing, quantization and benchmark evidence are not documented here.
   - A random spoken phrase plus face landmark challenge is not certified presentation-attack detection. It must not be described as secure anti-spoofing.

3. **Manifest contains two independent Accessibility services.**
   - Shraddha's `ShraddhaAccessibilityService` and the merged LIA `NovaAccessibilityService` are both exported behind Android's `BIND_ACCESSIBILITY_SERVICE` permission.
   - This can confuse setup and produce overlapping event/action handling. Consolidation or an explicit supported-service choice and regression tests are needed.

4. **Firebase is configuration-driven, not verified end-to-end.**
   - `FirebaseCloudVault` initializes Firebase manually from BuildConfig values and uses Firebase Auth + Firestore.
   - The repository report says live Firebase tests were not run. Rules, account lifecycle, key recovery, backup restore and permission-denial cases remain unverified.
   - The vault derives an AES key from the account password. This couples data recovery to password retention/change and needs a reviewed key-management/recovery design before production use.
   - No Firebase credentials should be committed.

5. **Voice service requires real device validation.**
   - The merged voice foreground service starts `VoiceSessionManager` and returns `START_STICKY`.
   - Android microphone foreground-service restrictions, visible notification, stop/restart behavior, lock-screen behavior, OEM battery restrictions, permission revocation and Android 14/15 behavior have not been tested on physical devices.
   - Continuous listening must remain explicitly user-controlled and visibly indicated.

6. **Release verification is incomplete.**
   - The existing v0.19.0 build report explicitly records Gradle/Kotlin compile, APK build/install, runtime tests, provider tests and MYRA pairing as NOT RUN / NOT VERIFIED.
   - CI workflow was added in Step 1. The first observed run (#55, commit `b6a2d1d`) failed during `Run unit tests`; the APK build step was skipped. The available run metadata does not expose the failing assertion/stack trace, so the exact unit-test cause remains to be retrieved from Actions logs and fixed.
   - Gradle wrapper is configured for Gradle 8.11.1; AGP is 8.9.1 and JDK 17 is specified. Compatibility appears intentionally aligned, but only CI can confirm resolution and compilation.

## Existing Step 1 changes rechecked

- Duplicate `RECORD_AUDIO` and `SYSTEM_ALERT_WINDOW` entries are removed in the work branch manifest.
- Shraddha remains the only MAIN/LAUNCHER activity.
- Android CI workflow exists and runs unit tests plus `:app:assembleDebug` on pushes to `main` and `work/**`.
- Main branch remains untouched; work is isolated on `work/step-01-source-audit-fixes`.

## Next implementation order

1. Repair biometric build blockers: add reviewed dependencies, implement the three missing classes behind interfaces, register the activity safely, and add unit tests. Keep matching disabled until real model files and license checks are complete.
2. Consolidate Accessibility ownership and document exact user setup/consent.
3. Add Firebase rules tests, auth lifecycle, encrypted backup/restore tests and key recovery design.
4. Run CI and fix every compile/test failure.
5. Perform physical-device voice, camera, permission, lifecycle and OEM tests.
6. Only then update feature status to verified.

## Acceptance criteria for Step 2

- [x] Repository and source layout inspected.
- [x] Gradle, manifest, app source, biometric, Firebase, voice, backend and test layout reviewed.
- [x] Build/test claims cross-checked against the repository's v0.19.0 report.
- [x] Confirmed defects and missing dependencies recorded.
- [ ] Android build passes (blocked pending implementation; Step 9 verification).
- [ ] Physical-device and end-to-end tests pass (not run).
