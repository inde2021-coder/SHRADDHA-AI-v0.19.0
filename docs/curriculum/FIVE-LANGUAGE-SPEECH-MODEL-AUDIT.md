# Shraddha AI — Five-language ASR, TTS and Translation Model Audit

Audit date: 2026-11-26  
Scope: Hindi, English, Sanskrit, Marathi and Pawari  
Result: language selection is now explicit, but none of the candidate offline speech/translation weights is integrated into the Android runtime yet. Do not present the following candidate models as already working in Shraddha.

## Current application reality

| Capability | Current implementation | Actual status |
|---|---|---|
| Voice input | Gemini Live streaming over the configured online gateway; Android SpeechRecognizer is also used for the phone-contact voice flow | Online/OS-dependent, not a bundled offline ASR model |
| Voice output | `ShraddhaSpeechClient` sends text to `/api/voice/synthesize` and plays returned 24 kHz PCM | Custom self-hosted backend, bilingual Hindi/English only; not offline and not model weights in the APK |
| Translation | Visual translation sends frames/text to the configured AI gateway | Online gateway task, not a dedicated local translation model |
| Offline speech weights | No ASR or TTS model files found in app assets | Not installed |
| Offline translation weights | No IndicTrans2 or other MT checkpoint found in app assets | Not installed |

## Candidate model audit

| Language | ASR candidate | TTS candidate | Translation candidate | Pawari/community status |
|---|---|---|---|---|
| Hindi | AI4Bharat IndicConformer (22-language family) | AI4Bharat IndicF5; Indic-Speak production tier | AI4Bharat IndicTrans2 | Not applicable |
| English | sherpa-onnx FastConformer English or Whisper multilingual | Indic-Speak production tier; Piper English voices are another candidate | IndicTrans2 English ↔ Indic | Not applicable |
| Sanskrit | AI4Bharat IndicConformer lists Sanskrit; validate pronunciation/domain accuracy before release | Indic-Speak lists Sanskrit in Preview, not Production | IndicTrans2 lists Sanskrit | Not applicable |
| Marathi | AI4Bharat IndicConformer | AI4Bharat IndicF5; Indic-Speak production tier | IndicTrans2 | Not applicable |
| Pawari | No verified model/corpus identified | No verified voice model identified | No validated parallel corpus/model identified | Requires consented recordings, orthography/variety labels, transcripts and community review |

## Sources and licensing gates

1. AI4Bharat IndicConformer: https://github.com/AI4Bharat/IndicConformerASR — repository states support for 22 scheduled Indian languages, including Hindi, Marathi and Sanskrit; MIT licence. Model checkpoints and Android runtime export still need size, latency and accuracy testing.
2. AI4Bharat IndicTrans2: https://github.com/AI4Bharat/IndicTrans2 — 22 scheduled Indian languages, including English, Hindi, Marathi and Sanskrit. Open-source checkpoints are available; exact checkpoint licence, redistribution terms, quantization and on-device footprint must be reviewed before packaging.
3. AI4Bharat IndicF5: https://github.com/AI4Bharat/IndicF5 — 11 Indian languages including Hindi and Marathi; model card lists MIT. It is a TTS candidate, not a five-language solution.
4. Bodhan AI / AI4Bharat Indic-Speak: https://huggingface.co/bodhan-ai/indic-speak — 3B-parameter TTS model; English/Hindi/Marathi are Production tier and Sanskrit is Preview tier. The model card currently shows a non-standard/other licence and gated access conditions; do not bundle or redistribute until legal terms and model access are reviewed. Its size makes it a PC/self-hosted candidate, not a default phone APK asset.
5. BHASHINI model catalogue: https://bhashini-developer-portal.bhashini.co.in/docs/get-started/models — useful for hosted ASR/TTS/translation discovery, but hosted pipelines are online and require account/configuration. Do not assume they are free, offline or integrated.
6. sherpa-onnx: https://github.com/k2-fsa/sherpa-onnx — evaluate a small English FastConformer export for local English ASR; validate model licence and Android ABI compatibility before use.

## Runtime changes in this audit

- Added explicit language choices for Hindi, English, Marathi, Sanskrit and Pawari.
- The language prompt now distinguishes classical Sanskrit and community-specific Pawari; it must not fabricate Pawari vocabulary.
- Devanagari script detection is separate from language selection because Hindi, Marathi and Sanskrit share Devanagari.
- The current custom voice adapter now rejects Marathi, Sanskrit and Pawari rather than silently speaking them with the English model.
- The selected language override is passed to the custom voice adapter.
- Unit tests cover script detection and the unsupported-language rejection path.

## Release acceptance tests still required

- ASR: 100+ consented utterances per language, multiple speakers/accents/noise levels; report WER/CER, code-switch accuracy, proper-noun accuracy, latency and peak RAM.
- TTS: native-speaker review for pronunciation, prosody, numbers, names, Sanskrit sandhi/visarga and code-switching; disclose synthetic speech.
- Translation: held-out parallel test sets per direction; bilingual reviewer scoring for meaning preservation, names, idioms and domain terms.
- Pawari: two independent fluent speakers per target local variety where feasible; contributor permission and attribution; preserve variants rather than force one standard.
- Offline: airplane-mode test, model checksum verification, cancellation, low-storage recovery, ABI compatibility, memory/thermal profiling and APK-size measurement.
- No raw microphone recording is uploaded for dataset creation without separate informed consent.

## Decision

Do not download or embed multi-gigabyte/3B weights in the Android APK. Keep the app small and offline-first: use a separately downloaded, signed/hashed on-device ASR model only after mobile benchmark approval; keep heavy TTS/translation models as an optional self-hosted PC companion service. Until then, show clear text-only or online-consent-required states. No paid API fallback is allowed.
