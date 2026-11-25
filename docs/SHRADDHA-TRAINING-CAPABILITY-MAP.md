# Shraddha AI — Training Capability Map and Duplicate-Avoidance Audit

Date: 2026-11-26  
Branch: `feat/free-api-offline-integration`  
Scope: static repository inventory of the active Android source tree and canonical Windows companion. This is not a runtime test report or a claim that every feature is complete.

## Audit rules

- Count active app source and tests, not archived/imported/reference copies.
- Map new training content to existing engines and UI routes before adding classes or screens.
- One canonical owner per capability; platform-specific adapters may exist, but they must call the shared capability contract.
- Do not add a second memory database, RAG index, speech stack, OCR engine, tool catalog, or PC agent without a documented gap and migration plan.
- Treat documentation, prompt text, and sample data as plans/content—not proof of working functionality.

## Existing implementation inventory (source-path evidence)

| Capability | Existing source discovered | Current evidence / limitation | Direction |
|---|---|---|---|
| Local knowledge retrieval | `in/shraddha/ai/brain/LocalKnowledgeBase.kt`, `LocalKnowledgeDocumentStore.kt` | Lexical retrieval and encrypted imported-document RAG; document store describes BM25 plus deterministic hashed n-gram vectors, not a neural embedding model. PDF import performs Latin + Devanagari OCR. | Extend this store/schema and add curated packs; do not create another RAG database. |
| Personal memory | `PersistentChatMemory.kt`, `PersonalKnowledgeStore.kt`, `SecureMemoryVault.kt`, `UnifiedMemoryRepository.kt` | Encrypted local recap, timeline/skill examples/routine proposals, optional explicit Firebase sync. Personal store comments say it does not execute routines or ingest sensors. | Audit screens, consent and CRUD end-to-end; add only missing memory categories to the existing repository. |
| Offline text AI | `LocalOfflineLlm.kt`, `OfflineModelScreen.kt` | Existing LiteRT-LM local text path; model weights are imported separately. Not a complete offline voice stack. | Keep separate from knowledge packs; do not bundle model weights in APK. |
| Commands and planning | `OfflineCommandEngine.kt`, `LiaToolCatalog.kt`, `LiaCapabilityPrompts.kt`, `VoiceToolActionJournal.kt` | Tool declarations and safety descriptions exist. Prompt documentation distinguishes local planning from execution; each command still needs implementation and result verification. | Maintain one canonical tool registry; audit each tool declaration against handler, permission and tests. |
| Language and voice | `LanguageDetector.kt`, `PersonalityPromptBuilder.kt`, `ConversationMemory.kt`, `VoiceSessionManager.kt`, `ShraddhaSpeechClient.kt`, voice service/client files | Existing language heuristic detects Devanagari Hindi and simple Roman Hinglish/English; not comprehensive Indic-language detection. Voice services exist but per-language offline support must be tested separately. | Add tested language packs and provider/runtime capability metadata; do not claim all Indian languages or offline speech prematurely. |
| Android OCR and screen context | `ScreenOcrEngine.kt`, `LocalKnowledgeDocumentStore.kt`, `NoteOcrActivity.kt`, Visual Lens/vision sources | Screen OCR and PDF/document OCR paths exist; sensitive screen text filtering is present in OCR flow. | Reuse; test accuracy, consent, protected-screen behaviour and correction UI. |
| Multimodal / camera | `vision/VisualLensScreen.kt`, `LiveEyesCameraService.kt`, `LiveFrameIntelligence.kt`, `AmbientIntelligencePolicy.kt`, `RemoteCameraPolicy.kt` | Camera/visual components and policy gates exist; exact supported models, permissions, remote behaviour and device coverage need runtime validation. | One vision capability contract; no parallel camera pipeline. |
| Windows companion | `desktop-companion/shraddha-pc-companion/` | Canonical Shraddha companion README documents optional control, OCR/screen sharing, voice, QR and offline model dependency packs with local approval. | Extend this companion; keep optional dependencies modular. |
| Personal knowledge / skill examples | `PersonalKnowledgeStore.kt` | Stores approved skill examples and routine proposals; comments explicitly say it does not execute routines. | Reuse for approved examples; execution remains in the existing tool/action layer. |
| Quality tests | `app/src/test`, `app/src/androidTest`, companion tests | Tests exist for memory, language, voice, vision, PC command interpreter, OCR/document store and other components. Their existence does not establish complete coverage or passing CI. | Add acceptance tests tied to the curriculum and run CI/device suites before release. |

## Duplicate and legacy-source risks

The repository tree also contains multiple legacy/reference trees and old package namespaces, including:

- `integrations/lia-ai-source/`
- `integrations/lia-android-source/`
- `companions/myra-desktop-source/`
- `desktop-companion/MYRA_ULTRA_APP/`
- `desktop-companion/MYRA_LINUX/`
- Active Android Kotlin files still under `com/Lia/assistant/` package paths and class names such as `LiaToolCatalog`.

These paths must not be treated as additional active Shraddha features. Before any branding/namespace cleanup, verify Gradle source sets, imports, manifests, build variants, workflows, and runtime entry points. Do not blindly delete or bulk-rename legacy folders; first establish whether they are build inputs, migration sources, or archival material. User-facing identity must remain Shraddha AI.

## Ten training domains mapped to existing systems

1. Human behaviour and everyday interaction → existing conversation memory, personality prompt and conversation quality tests; extend content and test behaviour, not a second personality engine.
2. General knowledge and education → LocalKnowledgeDocumentStore + versioned content packs; corpus is not yet evidenced as complete.
3. India, culture, civic and practical life → same knowledge-pack pipeline with official sources and freshness metadata.
4. Personal assistant and organisation → PersonalKnowledgeStore, PersistentChatMemory, UnifiedMemoryRepository and existing reminder/routine/action paths; verify complete UI/CRUD before adding anything.
5. Reasoning and decision support → OfflineCommandEngine and existing multi-role planning tool; planning is not equivalent to executing or verifying a task.
6. Technical/Android/Windows literacy → existing knowledge store, Android command tools and canonical Windows companion.
7. Languages and speech → current language detector and voice clients; staged language/ASR/TTS validation required.
8. Professional productivity → existing PDF/OCR, notes and file/document flows; identify missing end-to-end export/edit workflows before adding.
9. Safety/security/privacy → existing encrypted memory, OCR sensitive-field handling, consent policies and action journal; add cross-feature regression tests.
10. Domain-specific practical skills → loadable knowledge/skill packs using the shared store and deterministic tool layer; no separate domain-specific assistant.

## Five additional capabilities mapped to existing systems

- Context/style awareness: current session context, conversation recap and personality prompt; no hidden sensitive profiling.
- Extendable learning library: LocalKnowledgeDocumentStore imports and indexes user-selected material; add review/provenance/version/rollback workflow rather than another library.
- Multimodal understanding: existing PDF OCR, ScreenOcrEngine, Visual Lens and PC OCR/screen paths; one consent/capability contract.
- Permissioned task execution: existing tool catalog, action journal, Android handlers and paired PC companion; ensure preview-confirm-execute-verify state machine.
- Self-evaluation: existing unit/instrumentation/companion tests; add domain and action acceptance suites. Do not silently train on private conversations.

## Gaps requiring code-level follow-up

1. Produce a call graph from each user-facing screen to the actual handler and result verifier for every advertised skill.
2. Confirm whether reminders, calendar, tasks, file generation/export, Excel/CSV, web research and local learning-pack management have complete end-to-end flows.
3. Validate personal-memory edit/delete UI and ensure cloud sync is opt-in at every entry point.
4. Confirm which OCR languages are installed and tested; current PDF importer explicitly wires Latin and Devanagari recognizers.
5. Confirm the actual ASR/TTS model/runtime and offline status for each language on supported devices.
6. Audit the active source set and remove only proven-unreferenced legacy files in a separate reviewed change.
7. Run Android unit tests, instrumentation tests, Windows companion tests, APK/AAB build, and real-device airplane-mode tests. No pass result is implied by this inventory.

## Definition of done

- No duplicate implementation for memory, RAG, language detection, voice, OCR, camera, command catalog or PC companion.
- Every curriculum domain is represented by licensed, versioned content or a tested skill—or clearly marked not yet implemented.
- User can review/correct/delete imported knowledge and approved memories.
- Local/offline and cloud/paid boundaries are enforced and tested.
- Every action is permission-checked, confirmed when consequential, and verified from its real result.
- All active user-facing branding says Shraddha AI; legacy source namespaces are handled without breaking builds.
- CI and supported-device tests pass before merging to main.
