# Shraddha AI — Consent-based face and speaker recognition

## Implemented Android workflow

- People Memory profiles receive stable random IDs and a consent timestamp. The person must confirm the consent switch again before each capture session.
- The user imports compatible ONNX weights locally. Face model: OpenCV Zoo SFace; speaker model: SpeechBrain ECAPA-TDNN ONNX.
- Model sessions are loaded off the UI thread and are recreated inside the foreground capture activity.
- Face flow uses CameraX front-camera preview and ML Kit face tracking. A randomized active challenge asks for a blink and head turns to one side and then the opposite side. Only after all steps pass can the user capture one still frame.
- Face capture rejects multiple/no faces, small faces, excessive head pose, closed eyes, poor brightness and low sharpness. Five landmarks align the face to 112×112 before SFace embedding.
- Speaker flow uses Android on-device speech recognition to verify a random English phrase. If on-device recognition is unavailable, it fails closed and does not use cloud speech recognition. It then records 5 seconds of mono 16 kHz PCM and checks silence/activity and clipping before ECAPA inference.
- Enrollment stores only normalized embeddings in the Android Keystore AES-GCM encrypted feature store. Temporary face image files are deleted after processing; raw speaker PCM remains in memory and is discarded.
- Live matching returns the best consented profile only when the user has supplied a calibrated threshold. Otherwise the result is Unknown.
- Threshold calibration requires at least two consented people with at least two templates each. It computes genuine within-person and impostor cross-person cosine scores. If the distributions overlap or lack a minimum 0.02 margin, calibration fails closed.
- Consent withdrawal deletes the selected profile and its local face/speaker templates. A previously created encrypted cloud backup may still contain an older copy and must be separately replaced/deleted.

## Compatible model files

1. Face: [OpenCV Zoo SFace](https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface), Apache-2.0. Import the compatible `face_recognition_sface_2021dec.onnx` file and verify the model exposes `data` input and `fc1` output.
2. Face tracking: Google ML Kit Face Detection, run locally by the Android app; it provides face box, eye-open probability, head pose and landmarks. It is not a biometric identity model.
3. Speaker: [SpeechBrain ECAPA-TDNN ONNX](https://huggingface.co/kenrunio/transcriber-speaker-onnx), Apache-2.0. Import `ecapa-any-length.onnx`; expected inputs are `wav` and `lens`, output `emb`.

Model weights are not committed to this repository or bundled into the APK. The user must obtain the exact model files, review their licences and import them in People Memory. The speaker model is approximately 84 MB.

## Important security boundary

The randomized blink/head-turn and spoken random phrase checks are basic active challenge-response checks. They can deter a static printed photograph and simple replay, but they are **not certified presentation-attack detection** and cannot reliably stop sophisticated replay video, masks, deepfakes, voice conversion or real-time synthetic media. Android on-device speech recognition availability varies by device and language pack.

Biometric similarity is not proof of identity. Do not use it to unlock a device, authorize payments, approve transactions, grant access, place calls, or replace Android system authentication. Do not run background or covert identification. Camera and microphone operate only after a user tap and visible system permission.

## Threshold and evaluation guidance

Threshold calibration is only a small-sample separation check, not a statistically validated FAR/FRR estimate. Before real-world use, collect a sufficiently large and diverse consenting evaluation set on the target phone: multiple sessions, lighting, pose, glasses, distance, noise and different speakers. Keep identities disjoint between training/enrollment and evaluation where appropriate; report false-accept rate (FAR), false-reject rate (FRR), ROC/EER, and confidence intervals. If false accepts are unacceptable, disable matching.

## Automated checks

`BiometricMathTest` verifies embedding normalization, cosine scores and fail-closed threshold separation. `BiometricLivenessGateTest` verifies that a static face and multi-face frames cannot advance the randomized blink/turn sequence. These are unit tests only; they do not establish real camera accuracy or spoof resistance.

## Physical-device test checklist

- Install a debug APK on each target Android version/chipset and import the exact licensed model weights.
- Verify front-camera permission denial, camera unavailable, app backgrounding, rotation and low-light failure handling.
- Test printed photo, still image on another screen, prerecorded video, replayed audio, synthesized voice and live deepfake attempts. Confirm all results remain non-authoritative.
- Run consenting same-person and different-person trials, calibrate per-device thresholds and measure FAR/FRR.
- Test on-device ASR availability and verify network traffic is not used for biometric capture or speech challenge.
- Confirm temporary JPEG deletion, no raw PCM persistence, encrypted template deletion on consent withdrawal and cloud-backup retention behavior.

Physical Android phones and spoof artefacts are not available to GitHub CI. Passing automated tests must not be described as completed physical-device or security certification.