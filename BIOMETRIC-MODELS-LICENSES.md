# Shraddha AI biometric model assets

The Android build downloads fixed model revisions, verifies file size and SHA-256, and packages the ONNX weights in APK assets. Binary weights are not committed to Git.

| APK asset | Upstream | Declared licence | SHA-256 |
|---|---|---|---|
| `biometric-models/sface.onnx` | OpenCV Zoo SFace 2021dec | Apache-2.0 (model directory) | `0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79` |
| `biometric-models/ecapa-any-length.onnx` | kenrunio ONNX conversion of SpeechBrain ECAPA-TDNN | Apache-2.0 (model repository; upstream SpeechBrain model is Apache-2.0) | `700b63064ee98981186f59f247d28e56f7f6ab265d8b50ebb450db5a8ce21dc7` |

## Attribution

- SFace: OpenCV Zoo / SFace, model contributed by Yaoyao Zhong.
- ECAPA-TDNN: SpeechBrain, `spkrec-ecapa-voxceleb`, trained on VoxCeleb 1 and 2; ONNX conversion by kenrunio.
- References: M. Ravanelli et al., “SpeechBrain: A General-Purpose Speech Toolkit” (2021); B. Desplanques et al., “ECAPA-TDNN” (Interspeech 2020).

Keep the upstream Apache-2.0 licence and attribution notices with distributed APKs. Review the upstream model and training-data terms for the intended deployment jurisdiction and use.

## Anti-spoofing boundary

The bundled SFace and ECAPA models are identity-embedding models, not PAD detectors. The app's randomized blink/head-turn and spoken-phrase checks are active challenge-response heuristics, not certified anti-spoofing. They must not be used for authentication, payment approval, access control, or as a replacement for Android system biometrics.

A certification claim requires a specific PAD SDK/product and version, deployment licence, supported-device validation, and independent testing under the applicable ISO/IEC 30107-3 protocol by an accredited laboratory. Source changes and CI cannot certify the system.
