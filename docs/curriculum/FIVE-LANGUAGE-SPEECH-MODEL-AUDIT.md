# Shraddha AI — Five-language ASR, TTS and Translation Model Audit

Audit date: 2026-11-26
Scope: Hindi, English, Sanskrit, Marathi and Pawari
Result: language selection is explicit. An optional PC-side local inference adapter and Language Lab have now been added for Sherpa-ONNX ASR, IndicTrans2 and IndicF5. No model weights are included and no offline speech/translation runtime is integrated into the Android phone voice session yet. Do not present a model as working until its licensed checkpoint is installed and the runtime tests pass.

## Current application reality

| Capability | Current implementation | Actual status |
|---|---|---|
| Voice input | Gemini Live streaming over the configured online gateway; Android SpeechRecognizer is also used for the phone-contact voice flow. Optional Windows Language Lab now has a Sherpa-ONNX CTC adapter | Android remains online/OS-dependent; PC adapter requires separately installed model files and has not passed native-speaker accuracy testing |
| Voice output | `ShraddhaSpeechClient` sends text to `/api/voice/synthesize`; optional Windows Language Lab has an IndicF5 adapter for Hindi/Marathi | Existing app backend is online Hindi/English; PC IndicF5 requires licensed cached weights and a consented reference clip; Sanskrit/Pawari unsupported |
| Translation | Visual translation sends frames/text to the configured AI gateway; optional Windows Language Lab has a local IndicTrans2 adapter | Android remains online; PC adapter requires a local checkpoint and is not yet benchmarked |
| Offline speech weights | No ASR or TTS model files found in app assets | Not installed |
| Offline translation weights | No IndicTrans2 or other MT checkpoint found in app assets | Not installed |

## Candidate model audit

| Language | ASR candidate | TTS candidate | Translation candidate | Pawari/community status |
|---|---|---|---|---|
| Hindi | AI4Bharat IndicConformer; Sherpa-ONNX Android-ready CTC export | IndicF5; Indic-Speak Production | IndicTrans2 | PC adapters added; checkpoint install and validation pending |
| English | Sherpa-ONNX NeMo FastConformer CTC export | Indic-Speak Production; existing Piper custom service | IndicTrans2 English ↔ Indic | PC ASR adapter added; English TTS remains existing Piper service, not IndicF5 |
| Sanskrit | AI4Bharat IndicConformer; Sherpa-ONNX Android-ready CTC export | Indic-Speak Preview only; IndicF5 does not list Sanskrit | IndicTrans2 lists Sanskrit | PC ASR adapter added; no Sanskrit TTS adapter |
| Marathi | AI4Bharat IndicConformer; Sherpa-ONNX Android-ready CTC export | IndicF5; Indic-Speak Production | IndicTrans2 | PC ASR/TTS adapters added; checkpoint install and validation pending |
| Pawari | No verified model | No verified voice model | LDC-IL lists a Pawari Parallel Text Corpus, but access/licence/variety are not yet verified | Must collect consented variety-labelled speech and obtain community review; app explicitly rejects unsupported Pawari inference |

## Sources and licensing gates

1. AI4Bharat IndicConformer: https://github.com/AI4Bharat/IndicConformerASR — repository states support for 22 scheduled Indian languages, including Hindi, Marathi and Sanskrit; MIT licence. Model checkpoints and Android runtime export still need size, latency and accuracy testing.
2. AI4Bharat IndicTrans2: https://github.com/AI4Bharat/IndicTrans2 — 22 scheduled Indian languages, including English, Hindi, Marathi and Sanskrit. Open-source checkpoints are available; exact checkpoint licence, redistribution terms, quantization and on-device footprint must be reviewed before packaging.
3. AI4Bharat IndicF5: https://github.com/AI4Bharat/IndicF5 — 11 Indian languages including Hindi and Marathi; model card lists MIT. It is a TTS candidate, not a five-language solution.
4. Bodhan AI / AI4Bharat Indic-Speak: https://huggingface.co/bodhan-ai/indic-speak — 3B-parameter TTS model; English/Hindi/Marathi are Production tier and Sanskrit is Preview tier. The model card currently shows a non-standard/other licence and gated access conditions; do not bundle or redistribute until legal terms and model access are reviewed. Its size makes it a PC/self-hosted candidate, not a default phone APK asset.
5. BHASHINI model catalogue: https://bhashini-developer-portal.bhashini.co.in/docs/get-started/models — useful for hosted ASR/TTS/translation discovery, but hosted pipelines are online and require account/configuration. Do not assume they are free, offline or integrated.
6. Sherpa-ONNX Android runtime: https://github.com/k2-fsa/sherpa-onnx — native Android AAR/JNI is required for an Android integration; the current PC adapter uses the Python runtime only.

## Runtime changes in this audit

- Added explicit language choices for Hindi, English, Marathi, Sanskrit and Pawari.
- The language prompt now distinguishes classical Sanskrit and community-specific Pawari; it must not fabricate Pawari vocabulary.
- Devanagari script detection is separate from language selection because Hindi, Marathi and Sanskrit share Devanagari.
- The current custom voice adapter now rejects Marathi, Sanskrit and Pawari rather than silently speaking them with the English model.
- The selected language override is passed to the custom voice adapter.
- Unit tests cover script detection and the unsupported-language rejection path.


## New runtime and data-source findings (2026-11-26)

### On-device ASR integration candidate

A community Hugging Face repository now documents Android-ready, INT8 ONNX exports of AI4Bharat IndicConformer for all 22 scheduled Indian languages, including Hindi, Marathi and Sanskrit, plus a separate English NeMo FastConformer model. The published layout uses Sherpa-ONNX `OfflineRecognizer.from_nemo_ctc`, a shared Indic `tokens.txt`, per-language `model.int8.onnx` files and an English-specific token file. The model card reports approximately 190 MB per Indic language model and approximately 170 MB for English. This is a practical downloadable-on-demand integration path, but it is a third-party conversion: pin exact repository commit, verify source lineage and licence, verify file SHA-256 from the release metadata, and run our own WER/CER and Android ABI tests before marking it supported.

The current app already has ONNX Runtime but does **not** have Sherpa-ONNX's Android JNI/API wired in. Do not try to load a Sherpa CTC export as a generic ONNX graph without the matching frontend, decoder, vocabulary and metadata. The official Sherpa Android build documentation describes native Android ABI packaging; adding that runtime must be an explicit APK-size and ABI decision. AAR/native libraries and model files must remain downloadable or optional, not bundled in the base APK.

### Translation runtime

IndicTrans2 has a Python/Transformers/IndicTransToolkit inference path and published language codes for Hindi, English, Sanskrit and Marathi. No verified official lightweight Android runtime/export was found in this audit. The realistic first adapter is a separately installed PC companion / self-hosted service using a pinned IndicTrans2 checkpoint, with model licence acceptance, memory preflight, CPU/GPU capability detection, and a local-only endpoint. It must not be described as on-device Android or offline phone translation until an actual Android-compatible export is benchmarked.

### TTS runtime

IndicF5 supports Hindi and Marathi among 11 Indian languages, but official inference requires a reference audio clip and its transcript to guide voice characteristics; it is a Python/Transformers runtime, not a drop-in Android TTS engine. Sanskrit is not listed among its supported languages. Indic-Speak lists English/Hindi/Marathi as Production and Sanskrit as Preview; its 3B model is not an appropriate default phone download. First integration target should be an optional self-hosted PC/desktop TTS adapter with licensed reference voice assets and explicit user consent. Pawari remains unsupported pending community data and voice permission.

### Pawari data discovery

- A Seoni-based community Pawari Dictionary project describes words, Hindi/English meanings, usage examples and audio. It is a contact lead, not an open training dataset; written permission and licence are required.
- LDC-IL publicly lists a Pawari Parallel Text Corpus. The listing alone does not confirm exact variety, access terms or commercial training permission.
- VAANI provides district-diverse speech discovery, but Pawari must be confirmed by its own language label and dataset card; district alone is not a dialect label.
- Pawari/Pawri naming is not uniform across sources. Glottolog references Pawari/Pawri in relation to Bareli-Rathwi, while community usage in Seoni may identify a local Pawari variety. Keep varieties separate until fluent speakers resolve the relationship.

See [PAWARI-COMMUNITY-DATASET-PLAN.md](PAWARI-COMMUNITY-DATASET-PLAN.md) for consent, recording, annotation, split and community sign-off requirements.

## Current code delivery

- Added `pc_companion/language_runtime.py`: local-only Sherpa-ONNX ASR, IndicTrans2 translation and IndicF5 TTS adapters. Model loading is local-files-only; no model download, cloud fallback or automatic upload occurs.
- Added `pc_companion/language_lab.py` and an **Offline Language Lab** button in the Windows companion.
- Added optional dependencies in `pc_companion/requirements-language.txt`; the default companion build stays small.
- Added WAV validation and fail-closed unsupported-language tests.
- This is not yet the Android microphone runtime. The Android native Sherpa AAR/JNI, model download/checksum manager and live-session adapter remain to be implemented and tested on arm64 devices.

## Release acceptance tests still required

- ASR: 100+ consented utterances per language, multiple speakers/accents/noise levels; report WER/CER, code-switch accuracy, proper-noun accuracy, latency and peak RAM.
- TTS: native-speaker review for pronunciation, prosody, numbers, names, Sanskrit sandhi/visarga and code-switching; disclose synthetic speech.
- Translation: held-out parallel test sets per direction; bilingual reviewer scoring for meaning preservation, names, idioms and domain terms.
- Pawari: two independent fluent speakers per target local variety where feasible; contributor permission and attribution; preserve variants rather than force one standard.
- Offline: airplane-mode test, model checksum verification, cancellation, low-storage recovery, ABI compatibility, memory/thermal profiling and APK-size measurement.
- No raw microphone recording is uploaded for dataset creation without separate informed consent.

## Decision

Do not download or embed multi-gigabyte/3B weights in the Android APK. Keep the app small and offline-first: use a separately downloaded, signed/hashed on-device ASR model only after mobile benchmark approval; keep heavy TTS/translation models as an optional self-hosted PC companion service. Until then, show clear text-only or online-consent-required states. No paid API fallback is allowed.
