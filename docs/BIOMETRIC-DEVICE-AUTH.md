# Device-owner authentication for biometric profiles

## What is enforced

- Opening the face/speaker enrollment and matching activity first invokes AndroidX BiometricPrompt.
- Android 11/API 30 and newer accept a strong biometric or the system device credential (PIN, pattern or password).
- Android 8/API 26 through Android 10/API 29 require a strong enrolled biometric because the AndroidX combined biometric/device-credential prompt is not supported on those platform versions.
- Missing hardware, no enrolled authenticator, hardware errors, cancellation and authentication errors fail closed. The model is not downloaded and no biometric profile is opened before successful device-owner verification.
- The biometric activity sets Android's secure-window flag to block screenshots and screen recording while profile operations are visible.
- A successful device-owner check is retained only in the activity's saved instance state to avoid prompting again during a normal configuration recreation. It is not persisted as a long-lived unlock token.

## Existing biometric protections retained

- Face and speaker templates remain in the app-private Android Keystore-backed encrypted feature store.
- Explicit profile consent is still required. New samples invalidate prior calibration thresholds.
- Raw camera frames and PCM samples are temporary and cleared/deleted after processing.
- Matching requires an explicit calibrated threshold. Overlapping genuine/impostor calibration data fails closed.
- Active blink/head-turn and spoken-phrase checks are heuristics, not certified presentation-attack detection (PAD).

## Important limitations

- Android device-owner verification protects the local biometric profile workflow; it does not turn face/speaker similarity into a secure device-unlock factor.
- Do not use face or speaker similarity alone for payments, account recovery, legal/medical/employment decisions or access control.
- Physical fingerprint/face sensor behavior, credential fallback, camera/microphone quality, model accuracy and presentation-attack resistance must still be validated on real supported devices. Emulator CI cannot prove physical sensor behavior.
- Users without a strong enrolled biometric on API 26–29 must enroll one in Android Settings before this workflow can be used.
