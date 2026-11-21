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
   - The Cloud & Data screen now supports reauthenticated deletion of the Firebase account and both encrypted vault copies. Live deletion, backup restore, permission-denial and account-lifecycle tests remain outstanding; Firestore and RTDB deletion cannot be atomic across both services.
   - The Android named vault app now installs the Firebase App Check Debug provider in debug builds and Play Integrity provider in non-debug builds. Debug-token registration, release signing fingerprint registration, App Check metrics and enforcement remain Firebase Console owner actions.
   - Firebase account authentication is now separated from a 12+ character backup encryption passphrase. The passphrase is never sent to Firebase; old backups require the original account password in the passphrase field if no separate passphrase was used.
   - Password/passphrase field clearing and PBKDF2 transient-buffer wiping are implemented. Recovery of a forgotten backup passphrase is intentionally impossible; users must preserve it securely.
   - No Firebase credentials should be committed.

4. **Voice service is now wired into the primary Android application; physical-device validation remains outstanding.**
   - The primary manifest now registers `com.Lia.assistant.LiaApplication`, the integrated Compose voice activity and the microphone foreground service with the microphone FGS type.
   - The main Shraddha workspace now exposes a dedicated 3D Voice Agent entry.
   - The voice screen uses one microphone activation/deactivation control; explicit user intent is persisted so a manual stop remains off across screen recreation. The foreground service and persistent notification keep an active user-started session alive when the screen is closed. The service is not sticky; OS process death recovery still depends on the user reopening Voice.
   - The same microphone control requests both microphone and notification permissions, and is also the only retry path; separate connect/retry buttons have been removed. Permissions are rechecked when the screen resumes.
   - Android microphone foreground-service restrictions, visible notification, stop/restart behavior, lock-screen behavior, OEM battery restrictions, permission revocation and Android 14/15 behavior have not been physically tested.
   - Continuous listening must remain explicitly user-controlled and visibly indicated.

5. **Incoming SIM call handling is carrier-dependent and not yet production-verified.**
   - The Edesy inbound WebSocket/GPT-Live backend bridge is present, with server-side secrets and configurable inbound call-agent naming.
   - The Android Phone Call Agent page can run an authenticated, rate-limited Edesy provider preflight. Its dedicated WebSocket mode echoes bounded PCM and does not create an AI session or real call-history entry.
   - Backend tests now cover the authenticated preflight route, missing provider configuration, PCM echo, and the guarantee that preflight does not pollute call history. These new tests have not been run yet.
   - Transcript saving is off by default. If enabled, the assistant must clearly issue a consent prompt, the caller must press DTMF 1, and DTMF 0 at any time purges the transcript. Transcript text is capped at 24,000 characters per call and call history at 100 in-memory records. No audio is recorded.
   - A real Edesy tenant, DID, configured inbound flow, GPT-Live access and real two-way call test are still external prerequisites.
   - Jio no-answer forwarding must not be enabled until the Edesy call path passes real-device tests. Android InCallService cannot capture ordinary SIM call audio on standard Android.
   - The Android phone page can explicitly save, view and clear a masked call-history snapshot encrypted locally with Android Keystore AES-GCM. Automatic durable backend history, legal notice/retention review, human takeover and WhatsApp delivery remain incomplete.

6. **Device-to-device encryption has cryptographic primitives but is not integrated into transport.**
   - `DeviceE2ECrypto` provides ephemeral P-256 ECDH, session-bound HKDF-SHA256, AES-256-GCM and session-bound fingerprints.
   - The Windows companion now has a wire-compatible Python implementation of the same P-256/HKDF/AES-GCM derivation and bounded frame encryption/decryption primitives, with dedicated unit tests added (not run yet).
   - Unit tests cover matching peer keys, AAD authentication, different sessions, malformed inputs, nonce freshness and tamper rejection; these tests have not been run after the latest source changes.
   - Android frame transport and Windows Companion still use the existing relay frame protocol. The relay can inspect JPEG frames. Do not claim end-to-end encrypted screen/camera sharing or encrypted command transport yet.
   - A secure transport rollout still requires two-sided fingerprint comparison/approval, encrypted frame envelopes, replay/session binding, Android + Windows client support, and relay rejection of plaintext after migration.

7. **Paired-device revocation has a server API and Android/Windows UI paths.**
   - Relay revocation authenticates the device token, restricts cross-device revocation to the owner, clears grants/pending requests, and closes revoked sockets with code 4003.
   - Android manual disconnect now preserves Keystore-encrypted pairing for reconnect; confirmed revocation clears it. Windows persists a newly joined token only after relay WebSocket authentication and clears stale tokens after definitive 401/403. Android and Windows clear locally stored credentials when the relay confirms or signals revocation. Android can also revoke its own saved pairing over HTTPS from Keystore credentials while the WebSocket is disconnected; it does not claim success if the relay cannot confirm.
   - The authenticated WebSocket `connected` event now reports the caller's owner/member role; Android restores owner-only controls after reconnect and Windows shows its role.
   - Backend tests cover owner/member role restoration and revoke authorization; these new tests have not been run yet.
   - Full physical paired-device tests remain outstanding.

8. **Release verification remains a separate gate.**
   - CI must be run after the implementation pass, then every Android/backend/Firebase failure must be fixed.
   - A debug APK is not a production-signed release. Production signing requires owner-provided secrets and a physical install/upgrade test.

## Existing Step 1 changes rechecked

- Duplicate `RECORD_AUDIO` and `SYSTEM_ALERT_WINDOW` entries are removed in the work branch manifest.
- Shraddha remains the only MAIN/LAUNCHER activity.
- Android CI workflow exists and runs unit tests plus `:app:assembleDebug` on pushes to `main` and `work/**`.
- Main branch remains untouched; work is isolated on `work/step-01-source-audit-fixes`.

## Remaining implementation order (no final CI run until implementation pass is complete)

1. Complete E2E key exchange, two-sided fingerprint trust and encrypted Android/Windows frame + command transport. Make relay forwarding opaque and reject plaintext only after both clients are upgraded.
2. Complete paired-device lifecycle UX: reconnect, revoke, owner/member role visibility, offline revocation behavior and device-to-device recovery.
3. Finish Firebase Console App Check registration/enforcement, production rules publication, live account-deletion/backup-restore/permission-denial tests and live project verification. A forgotten backup passphrase remains unrecoverable by design; no recovery-key workflow exists.
4. Finish call handling: Edesy DID provisioning, real bidirectional call test, Jio no-answer forwarding verification, durable encrypted backend call history and human takeover/summary delivery. The authenticated provider preflight and per-call DTMF transcript consent gate are implemented.
5. Finish Windows/Android file transfer and encrypted screen/camera transport. Both direct OperatorEngine actions and learned workflows capture fresh post-action screens and poll for expected visible text for up to five seconds; the shared verifier excludes password fields and is unit-tested. Real-app end-to-end verification, stronger state-difference checks, interruption/reconnect and permission-revocation tests remain outstanding.
6. Review the remaining feature matrix and implement all source-level gaps. Only after the implementation pass, run Android unit tests/lint/debug APK, backend tests, Firebase rules tests and CI; fix all failures.
7. Create signed release only after owner configures GitHub signing secrets and the release APK passes physical install/upgrade checks.

## Acceptance criteria for Step 2

- [x] Repository and source layout inspected.
- [x] Gradle, manifest, app source, biometric, Firebase, voice, backend and test layout reviewed.
- [x] Build/test claims cross-checked against the repository's v0.19.0 report.
- [x] Confirmed defects and missing dependencies recorded.
- [ ] Android build passes (blocked pending implementation; Step 9 verification).
- [ ] Physical-device and end-to-end tests pass (not run).
