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
