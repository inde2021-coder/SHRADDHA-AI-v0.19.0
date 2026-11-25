# Shraddha AI Skill Factory and Verified Learning Architecture

Status: architecture + first fail-closed policy foundation. This is not a claim that a live crawler, curriculum, model trainer, or production skill marketplace exists.

## User decision
- Include all training domains and intelligence capabilities already mapped in `SHRADDHA-TRAINING-CAPABILITY-MAP.md`.
- Add a future-extensible Skill Factory.
- Automatically discover and prepare updates, but activate knowledge only after independent verification gates pass.
- Prefer Free APIs and on-device/offline runtimes. No paid endpoint or paid fallback in Free mode.

## One canonical owner per concern
| Concern | Existing owner to reuse | Rule |
|---|---|---|
| Knowledge documents and retrieval | `LocalKnowledgeDocumentStore`, `LocalKnowledgeBase` | Extend existing encrypted store and index; do not add a second RAG database. |
| Personal facts/preferences | `UnifiedMemoryRepository`, `PersistentChatMemory`, `PersonalKnowledgeStore`, `SecureMemoryVault` | Keep personal memory separate from public curriculum; explicit opt-in and edit/delete. |
| Tool execution | `LiaToolCatalog`, `OfflineCommandEngine`, `VoiceToolActionJournal` | One registry and action journal; add only missing handlers. Keep user-visible branding Shraddha AI. |
| Offline text inference | `LocalOfflineLlm`, `OfflineModelScreen` | Model weights are optional downloads, not APK assets. |
| Voice, OCR and vision | Existing voice clients, `ScreenOcrEngine`, Visual Lens and Windows companion | Capability adapters reuse current platform owners; no duplicate microphone, camera, OCR, or speech engines. |
| Quality gates | Existing Android/companion tests and CI | Every pack and skill ships with tests and a rollback version. |

## Training curriculum catalogue
Every domain is a versioned content pack or a tested deterministic skill. A category in this list is a target, not evidence that its feature is already implemented.

1. Human behaviour, conversation and emotional communication.
2. General knowledge, school learning, mathematics, science, history and geography.
3. India, culture, civic literacy, public services and practical local life.
4. Personal assistant, organisation, goals, tasks and reminders.
5. Reasoning, decision support, planning and problem solving.
6. Android, Windows, computer, networking and technical literacy.
7. Hindi, English, Hinglish and staged Indian-language speech/translation.
8. Productivity: documents, notes, OCR, tables, CSV/Excel, reports and PDFs.
9. Safety, privacy, cybersecurity, scams and emergency awareness.
10. Domain skills: business, accounting basics, agriculture, retail and household operations.
11. Parenting, infant care education, child development, original lullabies and stories.
12. Child Learning Tutor ages 3–10 with guardian-managed age bands and curriculum.
13. Food, cooking, nutrition education and kitchen planning.
14. Health literacy and first-aid awareness with authoritative sources and no diagnosis.
15. Financial literacy, budgeting, banking and digital-payment safety.
16. Technical repair literacy for common devices and household equipment.
17. Creativity: writing, design, presentation and media workflows.
18. Travel, planning, time management and everyday life skills.
19. Accessibility, inclusive communication and assistive workflows.
20. Environmental awareness, climate, energy and sustainability.

## Intelligence capability catalogue
- Context-aware conversation without hidden sensitive profiling.
- Structured reasoning and deterministic calculation/validation.
- Feedback-based correction with source tracking.
- Multilingual text, speech recognition and speech synthesis as separately tested capabilities.
- User-selected image, document, OCR, screen and camera understanding with consent.
- Plan → preview → confirm → execute → verify for external/device actions.
- Personalisation from explicitly approved preferences only.
- Self-evaluation and regression tests before trusted knowledge changes.
- Offline-first routing and graceful degradation.
- Skill discovery, installation, versioning, rollback and capability reporting.

## Skill Factory lifecycle
1. **Discover:** check allow-listed official/open-source catalogues or user-selected imports. No unrestricted crawling.
2. **Fetch:** download only over HTTPS, enforce size/type limits, and store in quarantine. Do not execute downloaded code.
3. **Verify provenance:** validate source allow-list, publisher signature (required for external catalogue packs), manifest identity, version, content digest and licence. User-selected local imports instead require explicit import consent and provenance metadata; a publisher signature is not assumed. A digest alone is not proof of publisher authenticity.
4. **Safety and privacy scan:** reject secrets, credentials, personal data, malware-like payloads, unsafe instructions and content outside the declared domain.
5. **Fact-check:** compare factual claims with independent authoritative sources; preserve jurisdiction, date, uncertainty and citations. LLM agreement alone is not verification.
6. **Duplicate/conflict check:** compare pack IDs, versions, content fingerprints and existing canonical skills. Extend current owners rather than creating parallel engines.
7. **Test:** run schema validation, deterministic unit tests, age/safety/domain tests, offline tests and regression tests in a sandbox.
8. **Activate:** only a verified, signed, licensed, safe, tested and non-duplicate pack from an approved source may auto-activate when the user has enabled automatic learning.
9. **Observe:** record pack version, source, licence, verification time, test result and activation decision. Do not store raw private conversations as training data.
10. **Rollback/delete:** keep the prior known-good version; support user review, disable, rollback and deletion. Never overwrite the only good version.

## First code foundation
Added `SkillPackTrustPolicy.kt` and unit tests. It is a pure Kotlin fail-closed activation gate. It rejects malformed metadata and queues a candidate for review unless automatic learning is enabled and the source, publisher signature, safety scan, independent fact validation, regression tests and duplicate check all pass. User-imported material also requires explicit import consent.

Important: this policy does not fetch or verify content itself, does not train model weights, and does not make external actions. Production adapters and independent validators remain to be implemented and tested.

## Data contract for every knowledge pack
Required metadata: stable pack ID, semantic version, title, supported language(s), domain, source URL, publisher identity, signature, SHA-256 digest, licence identifier, jurisdiction where relevant, published/updated date, verification date, expiry/recheck policy, content format, declared dependencies, minimum app version, test-suite version and rollback target.

Personal/child/health/legal/financial data must not be silently imported into shared training packs. Child profiles, voice, photos, contacts and personal documents are excluded from automatic learning by default.

## Free API and offline routing
- Local retrieval and deterministic local skills first.
- Local LLM/ASR/TTS/OCR only when the device has the model/runtime and language pack; show capability availability accurately.
- Optional Free API providers only when the provider currently offers a genuine free quota and the user has enabled online use. Re-check quotas, terms and privacy before release.
- Quota exhaustion, timeout, invalid credentials or outage must fall back to another eligible free provider or local engine; never route to paid in Free mode.
- Never upload personal documents, camera frames, screen content, contacts or child data without separate feature-level consent.
- Keep model weights and large language/domain packs as separately downloadable assets with licence, size and device requirements.

## Delivery phases and acceptance gates
### Phase 0 — Inventory and deduplication
- Build source-set/call-graph inventory for all advertised capabilities.
- Mark each as implemented, partial, planned, broken or absent.
- Confirm one canonical owner for memory, RAG, voice, OCR, camera, tools and PC companion.
- Remove nothing from legacy/reference folders until build inputs and references prove it is safe.

### Phase 1 — Shared trust and pack foundation
- Add manifest schema, signature verification, quarantine store, trust-state persistence and migration.
- Integrate policy with existing LocalKnowledgeDocumentStore; add pack provenance fields without breaking existing document imports.
- Add review/rollback UI and admin diagnostics.

### Phase 2 — Curriculum and skills
- Build licensed packs in priority order: Hindi/English general knowledge, child tutor content, parenting/lullabies, productivity, business, agriculture, safety and finance.
- Implement answer evaluation with deterministic checks where possible; source-ground LLM-generated explanations.
- Add only missing tool handlers after call-graph audit.

### Phase 3 — Automatic update pipeline
- Schedule constrained update checks with Android WorkManager and equivalent Windows companion scheduler.
- Check only allow-listed manifests; respect charging/Wi-Fi/data/battery settings.
- Download into quarantine, verify, test, then activate atomically. Preserve rollback.
- User can pause automatic updates, inspect changelog and disable any pack.

### Phase 4 — Validation and release
- Unit, integration, migration, security, content, privacy and regression tests.
- Airplane-mode tests; provider quota/outage tests; low-storage and interrupted-download tests.
- Android APK/AAB and Windows companion build/test independently.
- No main merge until CI and supported-device tests pass and user approves.

## Explicit non-goals
- No silent training on personal chats, microphone recordings, camera frames or child data.
- No automatic execution of downloaded code/plugins.
- No LLM-only fact verification or self-modification of app binaries.
- No paid API calls or silent paid fallback in Free mode.
- No claim that a listed curriculum domain is complete until content, UI, integration and tests exist.


## Offline-first runtime routing (2026-11-26)
Default request order:
1. Local deterministic skill for bounded tasks (arithmetic, formatting, validated local actions).
2. On-device model for ordinary chat, tutoring, drafting and reasoning when a compatible model is installed.
3. Local Knowledge/RAG for imported documents and verified offline packs.
4. Eligible Free API only for live information, external service actions, or tasks explicitly classified as requiring online specialist capability—and only when online use is enabled and free quota remains.
5. If no local route or eligible Free provider exists, explain the limitation. Never silently select a paid endpoint.

The pure policy foundation is `OfflineFirstRoutingPolicy.kt`, with unit tests in `OfflineFirstRoutingPolicyTest.kt`. It makes a route decision but does not itself execute engines or prove that an API request has completed. Cloud execution must separately enforce feature-level consent and redact/minimise content. Ordinary tasks must not be sent to cloud just because a network is available.

Offline learning should normally add verified, licensed knowledge packs to the existing local encrypted Knowledge/RAG store. Model fine-tuning is a separate optional PC workflow, only for suitable open-weight models and legally reusable data; it is not required for each new fact and must not train on private chats, child data, recordings or personal documents without explicit, separate consent.


## Firebase-backed memory and cloud/offline split

### Existing implementation facts
The current `UnifiedMemoryRepository` already wraps `EncryptedFeatureStore` and `FirebaseCloudVault`. Its current behavior is **local-first**, not cloud-only: it writes the encrypted device vault first, and uploads/restores Firebase data only when explicit cloud consent and Firebase credentials are supplied. Cloud restore also refreshes the local copy. It exposes record listing, comparison, explicit conflict resolution, single-record deletion and clear operations. Do not create a second memory repository or a second Firebase vault.

### Recommended target architecture
- Firebase should hold the durable, account-scoped backup/sync copy of approved memory and knowledge-pack metadata.
- The app should keep only a small encrypted hot cache: auth/session state, user-approved preferences needed immediately, recent conversation context, sync cursor and pending offline changes.
- Large documents, embeddings/indexes and downloadable model weights should live in cloud object/file storage or as separately downloadable assets; Firebase Realtime Database should hold metadata, compact records and sync state, not huge binary model files.
- Use Firebase Authentication and strict per-user database rules. Never embed service-account credentials or admin secrets in the APK. Do not treat a Firebase client API key as authorization.
- Encrypt sensitive memory before upload with a user/device-controlled key where feasible; use TLS in transit, key rotation/recovery design, and exclude credentials, child audio/video, biometrics and private raw recordings from automatic memory.
- Cloud-only mode must clearly disclose that memory access and updates require network. Offline mode must continue from the encrypted cache and local model/skills; queue changes and sync after connectivity returns.
- Avoid writing every token or every microphone frame to Firebase. Persist compact, useful memory summaries and explicitly approved facts with provenance, timestamps, retention and deletion support.
- Separate namespaces/retention for personal memory, general verified knowledge packs, child profiles/progress, and application diagnostics. Child data requires guardian consent and stricter minimisation; never use it as model-training data by default.

### APK-size and device-load implications
Moving durable memory and large content packs off-device can reduce installed APK size and long-term device storage. It does not remove the need for a small local cache, offline model runtime, or downloaded model weights. The APK should not bundle large models or the whole knowledge corpus; download optional models/packs on demand and allow the user to remove them. Cloud storage/network usage may incur Firebase billing depending on plan, reads, writes, bandwidth and stored data; this is not automatically free or unlimited.

### Migration and acceptance gates
1. Audit all current memory writers/readers and confirm they use `UnifiedMemoryRepository`; identify any direct local-store or Firebase writes before changing defaults.
2. Add per-user Firebase Authentication and deploy/test restrictive Realtime Database rules; verify one user cannot read another user's memory.
3. Add a cloud-first preference only after consent, with an explicit offline-cache size and clear cache control. Keep local-first as the safe migration fallback until cloud auth/rules and restore are verified.
4. Implement a durable sync queue, conflict/version policy, retries/backoff, tombstones for deletions and resumable sync.
5. Add export/restore, account deletion, cache clearing, encryption-key recovery and data-retention controls.
6. Test airplane mode, login expiry, two devices, simultaneous edits, interrupted uploads, quota exhaustion, account deletion and Firebase rule denial.
7. Measure APK/AAB size and device storage before/after. Do not claim savings until measured.

**Important:** this is the target design. The existing repository remains local-first and opt-in; no cloud-only switch or automatic migration has been enabled by this documentation update.


## Authored curriculum content delivery
Five lesson libraries have been authored under `docs/curriculum/` and indexed in `SHRADDHA-CURRICULUM-CATALOG.md`. They are not yet installed in the Android knowledge store. Ingestion must reuse `LocalKnowledgeDocumentStore` and `LocalKnowledgeBase`, preserve pack provenance/licence/version, and add retrieval tests. Do not create a parallel RAG store. Baby-care and health-related content requires specialist review before being exposed as advice. The Skill Factory must keep these packs in review/quarantine until trust, licence, safety and validation gates pass.
