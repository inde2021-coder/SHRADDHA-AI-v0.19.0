# Shraddha AI — biometric model catalog

Models are downloaded only after the user taps the corresponding button. Files are stored under the app-private `files/biometric-models` directory and accepted only after the pinned SHA-256 and file-size checks pass. No biometric sample is uploaded by this model downloader.

## Face recognition — OpenCV Zoo SFace

- File: `sface.onnx` (upstream `face_recognition_sface_2021dec.onnx`)
- Source: https://huggingface.co/opencv/face_recognition_sface
- Pinned source revision: `89e1f6f`
- SHA-256: `0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79`
- Expected bytes: `38,696,353`
- Model-directory license: Apache-2.0 (retain upstream license/attribution when redistributing)
- Input contract used by the Android adapter: RGB aligned 112×112, NCHW float32, values 0–255. This follows OpenCV FaceRecognizerSF's `blobFromImage(scale=1, mean=0, swapRB=true)`; the Android pipeline already provides RGB.
- OpenCV documentation lists a cosine similarity reference threshold of 0.363 and L2 threshold 1.128 on published benchmarks. This is a starting reference, not a phone/device-specific calibration or authentication guarantee.
- **Compliance caution:** the public model card identifies the model family and license, but does not fully resolve the exact training-data provenance. Obtain additional rights-holder clarification before commercial redistribution/use if that matters to your deployment.

## Speaker similarity — SpeechBrain ECAPA-TDNN ONNX export

- File: `ecapa-any-length.onnx` (downloaded from upstream `model_fp16.onnx`)
- Source: https://huggingface.co/Pawls2theWall/equivl-ecapa-voxceleb-onnx
- Pinned source revision: `dd6e443`
- SHA-256: `c59760b0a30dd905e3757cbb9ddf5dde22f9a099dc3e74de77c263cbb202e67c`
- Model card size: approximately 42.3 MB
- License: Apache-2.0; derived from SpeechBrain's `spkrec-ecapa-voxceleb`. Preserve attribution and the license.
- Input contract: `waveform` float32 `[1, samples]`, mono 16 kHz audio. The ONNX graph includes the 80-band filterbank and normalization. Output: `embedding`, float32 192-dimensional vector compared with cosine similarity. The Android adapter must use these exact tensor names.
- Training data: VoxCeleb 1 and 2, mostly English public-figure interviews. Accent, language, age, microphone and room conditions can materially affect similarity.
- Similarity is not proof of identity. Do not use it as sole authentication, access control, attendance enforcement or other high-stakes decision.

## Runtime safety

- The downloader uses HTTPS, a pinned upstream revision, a 100 MB hard cap, exact byte-length verification where available, SHA-256 verification, temporary-file writes and atomic rename.
- A checksum mismatch, unexpected length, HTTP error or interrupted download rejects the file and removes the temporary artifact.
- Face/voice enrollment still requires explicit per-person consent. Templates remain encrypted in the local Android Keystore-backed vault.
- Blink/head-turn is a basic interaction challenge, not certified presentation-attack detection. Physical-device accuracy, false-accept/false-reject and spoof testing remain required.
