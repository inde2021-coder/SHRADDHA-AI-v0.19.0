# SHRADDHA AI v0.19.0 — Step 2 Source Audit

Audit target: `work/step-01-source-audit-fixes` (compared with `main` where relevant).
Scope: Android Gradle configuration, manifest, package/source layout, biometric capture, Firebase vault, voice foreground service, backend, test and release evidence.

## Audit result

**Step 2 static source audit: COMPLETE. Android build/runtime: NOT VERIFIED.**
This report records source-level findings; it does not claim an APK build or device test.

## Confirmed blockers and defects (rechecked against current work branch)

1. **Biometric model files remain external prerequisites; source/dependency blockers listed in the original audit are stale.**
   - Current work branch contains `BiometricCaptureActivity`, `BiometricConsentVault`, `BiometricLivenessGate`, `BiometricModelDownloader`, `BiometricVoiceChallenge`, and `LocalBiometricModelEngine`.
   - `app/build.gradle.kts` declares CameraX, ML Kit Face Detection, ExifInterface, Google Tasks and ONNX Runtime dependencies.
   - `AndroidManifest.xml` already registers the consent-gated biometric activity.
   - Model weights remain user/device downloaded external files. Verify exact provenance, licenses, tensor contracts, SHA-256, preprocessing and real-device performance before use.
   - Blink/head-turn challenges and a MiniFASNet score are not certified presentation-attack detection. The feature must not be marketed as secure identity authentication or certified anti-spoofing.

2. **Accessibility service ownership has been consolidated in the current work branch manifest.**
   - Only Shraddha's `ShraddhaAccessibilityService` is declared in the primary app manifest; the duplicate Lia service is not registered there.
   - Runtime permission, service lifecycle, OEM compatibility and action safety still require physical-device validation.

3. **Firebase remains configuration-driven and requires production lifecycle verification.**
   - Firebase Auth, Firestore, Realtime Database, Storage and Messaging dependencies are present.
   - Rules, App Check, account lifecycle, key recovery, backup restore and permission-denial cases need live project tests.
   - The vault's password-derived encryption key still needs a reviewed password-change/recovery design before production use.
   - No Firebase credentials should be committed.

4. **Voice service requires real-device validation.**
   - The foreground voice service and session manager source are present.
   - Android microphone foreground-service restrictions, visible notification, stop/restart behavior, lock-screen behavior, OEM battery restrictions, permission revocation and Android 14/15 behavior have not been physically tested.
   - Continuous listening must remain explicitly user-controlled and visibly indicated.

5. **Incoming SIM call handling is carrier-dependent and not yet production-verified.**
   - The Edesy inbound WebSocket/GPT-Live backend bridge is now present on the active work branch, with server-side secrets and an opt-in transcript setting.
   - A real Edesy tenant, DID, configured provider flow, GPT-Live access and real two-way call test are external prerequisites.
   - Jio no-answer forwarding must not be enabled until the Edesy call path passes real-device tests. Android InCallService cannot capture ordinary SIM call audio on standard Android.
   - Persistent encrypted call history, caller notice/consent workflow, human takeover and WhatsApp delivery remain incomplete.

6. **Release verification remains a separate gate.**
   - CI must be run after the implementation pass, then every Android/backend/Firebase failure must be fixed.
   - A debug APK is not a production-signed release. Production signing requires owner-provided secrets and a physical install/upgrade test.

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
