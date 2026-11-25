# Shraddha AI — Free API + Offline Integration Register

Status: implementation register; this file does not claim that every adapter is already implemented.

## Non-negotiable routing policy
- Free providers and offline engines only in the Free mode.
- Never call a paid endpoint, paid model, or paid fallback from Free mode.
- A trial credit is not a permanent free tier and is not eligible as a Free-mode dependency.
- Quota exhaustion, 401/403, provider outage, timeout, or missing credentials must move to the next eligible Free provider or local engine; do not retry indefinitely.
- Never silently upload private files, camera frames, contacts, messages, or memory to a cloud provider. Require feature-level consent and show the provider used.
- API keys must never be logged, placed in source control, or included in diagnostics.

## Free / open-source candidates

### Indian language services
| Provider / project | Capability | Eligibility and constraints |
|---|---|---|
| BHASHINI Developer Portal | Indic ASR, TTS, translation, transliteration, OCR, language detection | Free to start; account/profile review and DIBD approval required. Confirm current quotas and terms after approval. |
| AI4Bharat / IndicTrans2 | Indic translation | Open-source model; self-host or run locally. Check each model and dataset licence. |
| AI4Bharat IndicConformer | Indic speech recognition | Open-source model family; deployment requires compatible runtime/compute. |
| AI4Bharat Indic TTS | Indic speech synthesis | Open-source model family; verify model licence and device/server requirements. |
| AIKosh datasets/models | Model and dataset discovery | Repository/catalog, not a universal hosted inference API. Verify each asset's licence. |

### Cloud AI free tiers (quota-limited; verify before enabling)
| Provider | Capability | Constraint |
|---|---|---|
| Google Gemini Developer API | Text and supported multimodal tasks | Free tier is model/project/region dependent and quota-limited. Apply provider data-use policy review before sending sensitive data. |
| GroqCloud | Fast text inference and supported audio models | Free developer limits are model-specific and change; handle 429 and quota errors. |
| OpenRouter free models | Text inference | Only models explicitly marked free; rate and daily limits apply and model availability changes. |
| Mistral API | Text inference | Include only if the account currently exposes a genuinely no-cost quota; no trial-credit assumptions. |

### Offline / self-hosted
| Engine | Capability | Placement |
|---|---|---|
| LiteRT-LM | Android local text LLM | Android; model downloaded/imported separately; check model licence and device RAM. |
| Ollama | Local text/multimodal models where supported | Windows PC companion; local hardware required. |
| Whisper | Speech-to-text | Local PC/server; model download and compute required. |
| Vosk | Offline speech-to-text | Android/PC; language model assets downloaded separately. |
| Piper | Offline text-to-speech | PC/local service; voice model licence must be checked. |
| Android SpeechRecognizer / Android TTS | Device speech input/output | Device and installed language-pack support varies; not guaranteed fully offline. |
| Google ML Kit on-device | OCR and barcode scanning | Android on-device SDK; confirm model availability/download requirements for each feature. |
| Tesseract OCR | OCR | Local engine and language packs. |
| LibreTranslate | Translation | Self-hosted deployment; public hosted instances are not assumed free or reliable. |
| Room database | Local memory | On-device storage; no cloud dependency. |
| Firebase Spark | Optional sync | No-cost quotas and product-specific limits; never treat all Firebase services as unlimited. |
| OpenStreetMap data | Map data | Data is open; public hosted endpoints have usage policies. Do not treat public Nominatim as an unrestricted API. |
| Open-Meteo | Weather | Free for qualifying non-commercial use under published fair-use terms; internet required. |

## Integration phases
1. Audit existing Android provider adapters, backend gateway, voice engine, OCR, offline model manager, and PC companion. Reuse working code; do not create duplicate clients.
2. Implement a provider capability registry with provider ID, supported tasks, free eligibility, model IDs, quota/error classification, consent requirements, and online/offline status.
3. Add BHASHINI adapter only after account approval and real credentials are available. Keep it disabled with a clear setup status until then.
4. Add or verify AI4Bharat local model runners and model licensing/size checks.
5. Verify existing Gemini/Groq/OpenRouter free-tier adapters and enforce Free-mode allow-list.
6. Complete offline voice/OCR/translation/model fallback paths, with download sizes and device requirements visible.
7. Add tests for no-network, invalid key, 429 quota, timeout, provider outage, no paid endpoint invocation, and private-data consent.
8. Build and test Android APK plus Windows companion. Do not merge into main until CI and device tests pass.

## Acceptance criteria
- Free mode can never select a paid provider/model.
- Paid credentials, if present in other modes, are ignored by Free mode.
- Free quota exhaustion never triggers billing.
- Every cloud call is HTTPS, redacted in logs, and feature-consented.
- Offline-capable actions still work with airplane mode enabled.
- Missing model/permission/account approval produces a truthful actionable status, not a fake success.
- Provider/model names and free-quota claims are refreshed from official provider docs before release.

## Official references
- BHASHINI developer portal: https://bhashini-developer-portal.bhashini.co.in/
- BHASHINI onboarding: https://bhashini.gov.in/
- BHASHINI model catalog: https://bhashini-developer-portal.bhashini.co.in/docs/get-started/models
- AI4Bharat: https://ai4bharat.iitm.ac.in/
- AI4Bharat GitHub: https://github.com/AI4Bharat
- Gemini API pricing: https://ai.google.dev/gemini-api/docs/pricing
- Groq rate limits: https://console.groq.com/docs/rate-limits
- OpenRouter free models: https://openrouter.ai/models?max_price=0
- LiteRT-LM Android: https://developers.google.com/edge/litert-lm/android
- Ollama: https://ollama.com/
- Whisper: https://github.com/openai/whisper
- Vosk: https://alphacephei.com/vosk/
- Piper: https://github.com/rhasspy/piper
- ML Kit: https://developers.google.com/ml-kit
- Tesseract: https://github.com/tesseract-ocr/tesseract
- Firebase pricing: https://firebase.google.com/pricing
- Open-Meteo pricing: https://open-meteo.com/en/pricing



## Skill Factory and automatic knowledge-update policy
- Free mode remains restricted to genuine no-cost quotas and local/offline engines; paid endpoints are never a fallback.
- Local knowledge retrieval and deterministic skills are preferred before network inference.
- Automatic update checks must use allow-listed manifests only; no unrestricted crawler or downloaded-code execution.
- New packs enter quarantine. Verify publisher signature, SHA-256 digest, licence, source, size/type limits, content safety, independent facts, duplicate/conflict status and regression tests before activation.
- LLM agreement is not independent factual verification. Failed/unknown checks remain pending review or are rejected; they do not become trusted knowledge.
- User-selected imports require explicit consent. Private conversations, child data, audio, camera frames, contacts and personal documents are not silently reused for training.
- Preserve version history and rollback; show source, licence, verification date, provider/model and download size.
- See `docs/SKILL-FACTORY-ARCHITECTURE.md` for the canonical lifecycle and delivery phases.
