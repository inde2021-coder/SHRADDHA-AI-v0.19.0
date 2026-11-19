# Shraddha AI — Consent-based face and speaker recognition

## What is now in the Android source

- `LocalBiometricModelEngine` uses ONNX Runtime on the Android device. It has two real inference paths:
  - OpenCV SFace-style face embedding: `data` input, `fc1` output, aligned 112x112 RGB face, raw 0–255 channel-first values, 128-dimensional output.
  - SpeechBrain ECAPA-TDNN ONNX speaker embedding: `wav` and `lens` inputs, mono 16 kHz float32 waveform, `emb` output, 192-dimensional vector.
- `BiometricConsentVault` stores only normalized embedding templates and consent metadata in the existing Android Keystore AES-GCM encrypted feature store. It does not store source photos or raw audio.
- A person can be deleted/withdrawn from the local biometric vault. Matching is opt-in and requires an explicit caller-supplied calibrated threshold; no default threshold silently labels someone.
- `BiometricMathTest` covers normalization, cosine similarity and threshold decisions.

## Compatible model sources

1. Face: OpenCV Zoo SFace (`face_recognition_sface_2021dec.onnx`), Apache-2.0 model. The matching YuNet detector is MIT-licensed, but the current Android biometric engine expects the face to have already been detected, aligned and resized to 112x112 before inference. See [model provenance and checksums](https://github.com/gitakoos/face-models#previous-models-v1).
2. Speaker: `kenrunio/transcriber-speaker-onnx` `ecapa-any-length.onnx`, Apache-2.0. It includes its 80-band feature extraction and expects `wav` float32 16 kHz and `lens` float32 inputs. It returns a 192-value embedding. See [the model card](https://huggingface.co/kenrunio/transcriber-speaker-onnx).

The model weights are not committed to the app and are not downloaded automatically. Import only the exact compatible ONNX models after reviewing their licences and provenance. The speaker model is approximately 84 MB.

## Important limitations — not yet production-ready

The inference and encrypted template primitives are real, but the complete capture-to-identification workflow is not finished yet:
- Camera face detection, five-point alignment and quality gating are not wired into the People Memory screen.
- Microphone sample capture, silence/noise rejection and speaker enrollment are not wired into the People Memory screen.
- The model files have not been imported or validated on the user's phone.
- Thresholds must be calibrated with genuine consenting participants under the target camera/microphone conditions; no universal threshold is safe.
- Presentation-attack detection (printed photo, replayed video/audio, deepfake) is not implemented. Recognition must not unlock devices, authorize payments, place calls or replace PIN/biometric system authentication.
- Similarity is not proof of identity. If no match passes the calibrated threshold, the app must say “Unknown”.

## Consent and privacy requirements

Get explicit informed consent from each person before collecting samples. Explain the purpose, local storage, retention and deletion. Do not enroll children or bystanders without the legally appropriate guardian/consent process. Never perform covert/background identification. Keep the camera/microphone session visible and user-started. Withdrawal must delete that person's face and voice templates; old encrypted backups may need separate deletion/rotation.

## Model contracts

### SFace
Input name `data`, shape `[1,3,112,112]`, RGB channel-first, raw byte values represented as float32 0–255. Output `fc1`; the engine L2-normalizes it. Caller must align face landmarks before calling.

### ECAPA-TDNN
Inputs `wav` `[1,samples]` float32 and `lens` `[1]` float32 (1.0 for no padding), mono 16 kHz, 1–10 seconds. Output `emb`; the engine L2-normalizes it.