# Android Indic Speech Runtime Feasibility Audit

Status: implementation in progress; no end-to-end Android device validation has been completed.

## 1. ASR runtime selected

- Runtime: sherpa-onnx Android artifact, pinned to v1.13.5 through JitPack (the Android-specific Maven coordinate).
- Android API: `OfflineRecognizer` + `OfflineNemoEncDecCtcModelConfig`, matching the IndicConformer CTC exports.
- Audio contract: mono Float32 PCM, 16 kHz.
- Model files are downloaded to app-private storage, written as `.part`, checked against source-controlled SHA-256 pins, then installed. Unpinned artifacts are blocked.
- Hindi and Sanskrit have pinned model digests and the shared tokenizer digest in the current implementation.
- Marathi model digest is not yet independently pinned; its download/load remains blocked.
- No model weights are bundled in the APK.

Sources: sherpa-onnx Android/JitPack documentation; IndicConformer Android model card and Xet file metadata. See links in PR notes.

## 2. Live microphone and Brain routing

The Android implementation now has an explicit offline microphone pipeline:
- User selects Hindi, Marathi, or Sanskrit; the chosen language is not auto-switched silently.
- AudioRecord input is captured by the existing 16 kHz LiveAudioIO layer.
- Speech segments are closed after a short silence window and sent to the local IndicConformer recognizer.
- Recognized text is stored in local chat memory and, when a verified/imported LiteRT-LM model exists, passed to LocalOfflineLlm.
- This offline class has no network client and no cloud fallback.
- If the ASR model is missing or not verified, the service does not start.
- If the local text model is not imported, the transcript is shown and the app explains that a local Brain model is still required.

Still pending: Android build verification, real microphone tests, device memory/latency measurements, and a UI pass to distinguish offline-active from online-active state in every lifecycle edge case.

## 3. Translation feasibility

A small Android-oriented IndicTrans2 ONNX export is available as a technical candidate:
- INT8 encoder/decoder files total about 333 MB, plus tokenizer metadata.
- Its documented language direction is Indic-to-English and the published tested languages are Hindi and Tamil.
- The repository includes a Kotlin/JVM reference implementation, but this is not yet an Android integration or a validated Hindi↔Marathi/Sanskrit translator.
- The larger general IndicTrans2 ONNX export is multi-gigabyte and is not a sensible default APK payload.

Decision: do not silently wire this one-way Hindi/Tamil candidate as a general-purpose multilingual translator. A separate, explicitly selected, direction-aware Android translation runtime and per-language test corpus are required.

## 4. TTS feasibility

sherpa-onnx supports Android VITS/Piper-style TTS. Community Indic packs demonstrate that Hindi/Marathi voices can run locally, but some available MMS voice packs are marked CC-BY-NC 4.0 (non-commercial). A different pack advertises Indic-TTS FastPitch + HiFi-GAN exports, but its per-voice model provenance and licence must be checked before redistribution.

Sanskrit: no verified, appropriately licensed, device-ready voice has been selected in this audit.

Decision:
- Hindi/Marathi: technically feasible to prototype with sherpa-onnx after licence and checksum review.
- Sanskrit: remain clearly unavailable until a verified voice pack is identified.
- Do not replace Shraddha's configured custom voice or use Android/Google TTS as a hidden fallback.

## 5. Release gates

1. Pin the Marathi ONNX model SHA-256 and verify the shared tokenizer hash against the exact pinned repository revision.
2. Run Gradle dependency resolution and `assembleDebug` on CI.
3. Test Hindi and Sanskrit on a real Android phone with airplane mode enabled after download.
4. Test checksum mismatch, missing model, permission denial, service stop, and app background/foreground transitions.
5. Measure peak RAM, download size, real-time factor, and battery use.
6. Integrate translation only after bidirectional language coverage and licence review.
7. Integrate TTS only after per-voice licence review, SHA-256 pinning, and offline playback tests.


## 5. Licence and release gates (checked 2026-11-27)

- The AI4Bharat IndicConformer source model is MIT-licensed. The selected sherpa-onnx export repository currently identifies its Indian-language model source as MIT; the Android sherpa-onnx runtime is a separate dependency whose own licence notices must be retained.
- Hindi and Sanskrit model artifacts in the current app code have pinned SHA-256 values. They are not bundled in the APK; the user downloads them on demand. The app verifies the exact bytes before installation and inference.
- Marathi is listed as a candidate language but remains blocked in the app because its exact ONNX digest has not been independently confirmed. A model being listed by the publisher is not enough to enable a download.
- AI4Bharat IndicF5 is MIT-licensed and lists Hindi and Marathi among its supported languages. It requires a reference voice and does not itself provide a consented Shraddha identity. The Android TTS runtime, speaker consent, audio quality checks and native-speaker review are not implemented in this branch.
- AI4Bharat IndicTrans2 has MIT-licensed model releases. An Android translation runtime, model packaging/download, memory/performance budget and translation evaluation are not implemented in this branch.
- English offline ASR, English/Hinglish offline TTS, Sanskrit TTS and all Pawari/Powari ASR/TTS/translation remain unimplemented or unverified.
- Pawari collection is limited to four separate operational profiles: Balaghat, Seoni, Gondia and Bhandara. No recordings are included. The field validator only checks metadata/consent/review conditions; it does not create rights, verify identity or authorize training/publication.

## 6. Required acceptance tests before claiming full multilingual support

1. Download Hindi and Sanskrit artifacts on a real Android device; verify SHA-256 and successful sherpa-onnx model initialization.
2. Run recorded and live microphone samples with speaker-disjoint evaluation; report WER/CER, latency, memory and battery by language.
3. Pin Marathi model bytes from an authoritative source and repeat the same tests before enabling Marathi.
4. Integrate and test offline IndicF5 TTS with a separately consented reference voice; verify no hidden Google/system voice fallback.
5. Integrate and test IndicTrans2 with reviewed held-out sentence pairs and a bounded on-device memory profile.
6. Implement English/Hinglish offline recognition separately and evaluate code-switch performance.
7. Collect Pawari/Powari speech only after local community consent, preserve district variety labels, and obtain two independent native-speaker reviews plus model-use rights.
8. Run a full Android emulator and physical-device pass. Unit tests alone do not prove microphone capture or model inference.
