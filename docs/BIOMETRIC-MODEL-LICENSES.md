# Biometric model provenance, licenses and operating limits

This project pins model artifacts by immutable Hugging Face revision and verifies SHA-256 before moving a download into the app's private model directory. A failed download or checksum mismatch is rejected.

## Face embedding — SFace

- Artifact: `opencv/face_recognition_sface/face_recognition_sface_2021dec.onnx`
- Revision: `89e1f6f`
- SHA-256: `0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79`
- Published license: Apache-2.0 (the model repository states all files are Apache 2.0).
- Source/model card: https://huggingface.co/opencv/face_recognition_sface
- Paper: SFace, https://arxiv.org/abs/2205.12010

## Speaker embedding — ECAPA-TDNN

- Artifact: `Pawls2theWall/equivl-ecapa-voxceleb-onnx/model_fp16.onnx`
- Revision: `dd6e443`
- SHA-256: `c59760b0a30dd905e3757cbb9ddf5dde22f9a099dc3e74de77c263cbb202e67c`
- Published license: Apache-2.0; the model card identifies the weights as SpeechBrain's and requests SpeechBrain attribution.
- Source/model card: https://huggingface.co/Pawls2theWall/equivl-ecapa-voxceleb-onnx
- Paper: SpeechBrain, https://arxiv.org/abs/2106.04624
- Attribution: Mirco Ravanelli et al., “SpeechBrain: A General-Purpose Speech Toolkit,” 2021.

## Delivery and privacy

- Model weights are **not bundled in the APK**. They are downloaded only after the user explicitly starts the model download, stored in app-private storage, and verified against the pinned SHA-256.
- Once downloaded, inference and biometric templates are local. Original capture files are temporary and deleted after processing.
- Do not use biometric matching as the sole basis for identity, access, employment, financial, legal, medical, or other high-impact decisions.
- SFace and ECAPA are statistical similarity models, not proof of identity. ECAPA's published training/evaluation data is VoxCeleb (mostly English public-figure interview audio); performance across languages, accents, ages, health, microphones and environments is not established here.
- The blink/head-turn challenge is a basic interaction check, **not certified presentation-attack detection**. It does not establish resistance to printed photos, replay, masks, deepfakes or sophisticated injection attacks.
- Before production use, run documented device-level accuracy, false-accept/false-reject, demographic, replay and presentation-attack tests; obtain informed consent and provide deletion controls.
