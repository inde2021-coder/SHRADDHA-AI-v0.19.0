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


## Baby Care / Smart Cry Detection — duplicate-avoidance and implementation map

### Existing modules to reuse (must verify in code before implementation)
- Microphone and voice-session lifecycle: existing voice-session/service stack. Add a child-safety-specific mode only through the canonical audio capability contract; do not add a second always-on microphone service.
- On-device audio inference: inspect existing local model/runtime and device capability registry first. If no suitable audio event classifier exists, add a small, replaceable on-device cry-event classifier behind the existing model interface; do not use a general chat LLM as the audio detector.
- Knowledge and memory: use LocalKnowledgeDocumentStore for licensed baby-care packs and the existing approved memory repository for guardian-enabled feeding/diaper/sleep logs. Do not create another database or retain raw audio by default.
- Notifications and actions: reuse existing Android notification, permission, action-journal, and communication handlers. Add parent contacts as a narrowly scoped, guardian-managed contact configuration, not as a broad contact-harvesting feature.
- Lullabies/stories: reuse the existing TTS/audio playback and content-pack pipeline; add original/licensed content, not a second audio player.
- Camera/Visual Lens is not required for cry detection and must not be enabled implicitly.

### Capability status
- **Planned only:** Baby-care knowledge packs, age-aware parenting guidance, original lullabies/stories, cry-event detection, possible-cause checklist, and guardian alert workflow.
- **Not verified as implemented:** a validated baby-cry classifier, reliable cause classification, baby-monitor-grade continuous detection, guardian-configured automatic call/SMS escalation, or clinical validation.
- Existing microphone, voice, memory, notification, and Android action components are building blocks only; their presence does not prove this feature works end-to-end.

### Safe implementation sequence
1. Map current microphone foreground service, voice session, TTS player, notification channel, contacts/permissions, SMS/call handlers, action journal, and model runtime. Record one canonical owner for each.
2. Ship static, source-reviewed baby-care knowledge and original lullabies/stories first; include age band, jurisdiction, source, licence, medical review status, and last verification date.
3. Add a local cry-event detector as a separate event-classification task only after collecting/licensing a consented, representative dataset and evaluating false positives/false negatives. Keep cause suggestions as low-confidence hypotheses, combining cry event + guardian-entered context; do not claim sound-only diagnosis.
4. Add guardian setup, verified primary/backup numbers, visible monitoring state, pause control, notification-first alerts, optional SMS/call rules, cooldowns, and delivery verification. Provide system dialer/SMS composer fallback when direct actions are restricted.
5. Run privacy, child-safety, Android background execution, battery, device, SIM/network, and policy tests. Independent paediatric/child-safety review is required before health guidance is released broadly.
6. Keep the feature disabled by default and release behind an explicit guardian opt-in. Do not merge into main until the complete end-to-end test matrix passes and the user approves the merge.

### Non-negotiable acceptance checks
- Cry detection is described as “crying-like sound detected”; possible reasons are explicitly uncertain and non-diagnostic.
- A guardian—not a child—sets up, verifies, enables, pauses, and deletes contacts/logs.
- No silent microphone use, no hidden recording, no default cloud audio upload, and no automatic restart after stop/revocation.
- No automatic emergency-service call from classifier output.
- SMS/call actions require explicit guardian pre-authorisation, platform permission/policy compliance, rate limiting, and verified outcomes; otherwise use the system composer/dialer.
- Human supervision remains mandatory; the app never claims to replace a caregiver or certified baby monitor.
- All existing voice, memory, notification, and communication features are reused where suitable; no duplicate service, player, database, or action catalog is introduced.


### Specific requirement: baby crying while no adult is nearby

- Required intended behaviour: in guardian-enabled Baby Safety Mode, when sustained crying is detected and Visual Lens reports no adult visible in the configured camera field of view, automatically start the guardian's pre-authorised escalation sequence: call primary parent, call backup parent if unanswered, and send the configured SMS alert. This is stronger than a notification-only workflow.
- Visual Lens can establish only “adult not detected in this camera view,” not prove that no adult exists elsewhere in the home. Document camera placement, blind spots, occlusion, lighting, and model limitations. Never use face recognition or identify who the adult is.
- Add a visible monitoring health state for microphone, camera, network, battery, call/SMS permission, and last successful check. Alert the guardian when monitoring is unavailable; never show a false “protected” status.
- Automatic call/SMS implementation is conditional on Android OS/app-store policy, runtime permissions, carrier and regional requirements. If prohibited, provide a dialer/SMS composer fallback and disclose that automatic escalation cannot run on that device.
- Test sustained-cry thresholds, adult-in-view vs adult-not-detected paths, false positives, primary unanswered/backup call, SMS fallback, quiet hours, cooldowns, network loss, device shutdown, camera obstruction, and permission revocation.

### Implementation update — Smart Baby Safety policy foundation

- Added `app/src/main/java/in/shraddha/ai/baby/BabySafetyEscalationPolicy.kt`.
- Added unit tests in `app/src/test/java/in/shraddha/ai/baby/BabySafetyEscalationPolicyTest.kt`.
- The pure Kotlin policy requires guardian opt-in, verified contacts, healthy monitoring, a sustained high-confidence cry event, and the explicit camera result `NO_ADULT_IN_FRAME` before requesting a primary guardian call and SMS.
- If the primary call is reported unanswered, the policy permits a backup call only after the configured grace period. Cooldown prevents repeated new incidents but does not suppress an in-progress backup escalation.
- Camera uncertainty or an adult visible in the configured frame fails closed. This means only “no adult visible in this camera frame”; it does not prove the entire home is empty.
- **Still not implemented or verified:** the cry-audio model, adult-in-frame classifier, guardian setup UI, persistent incident state, Android call/SMS execution adapters, lullaby/story UI, baby-care content packs, background lifecycle integration, and end-to-end device tests. The policy emits decisions only; it does not itself place calls or send messages.
- No audio/video is recorded or uploaded by this policy. Do not advertise the feature as an operating baby monitor until the missing components and real-device tests are complete.

## Child Learning Tutor (ages 3–10)

### Learning experience
- Add a guardian-configured child profile with age band only (3–5, 6–8, 9–10), preferred language, learning level, accessibility preferences, and optional curriculum/board. Avoid collecting a child's full identity or unnecessary personal details.
- Teach like a patient, encouraging tutor: explain one small concept, ask a short question, wait for the child's answer, assess it, and respond at the learner's level.
- If the answer is incorrect or missing: give a gentle hint first, then a simpler explanation, then a worked example, and ask a fresh practice question. Never shame, scold, compare, or pressure a child.
- If the child answers correctly: explain briefly why, praise effort specifically, and offer a slightly harder follow-up or spaced review.
- Teach with pictures/objects, counting, stories, songs, phonics, drawing prompts, everyday examples, and short activities. Keep sessions age-appropriate with breaks and no manipulative engagement loops.
- Subjects: early language and literacy, Hindi/English phonics and vocabulary, numbers and arithmetic, shapes/patterns, science/nature, environment, general knowledge, creativity, reasoning, and social-emotional skills. Curriculum/board-specific lessons must be mapped to verified official syllabus sources before claiming alignment.
- For ages 3–5: mostly play, listening, speaking, matching, stories, shapes, colours and counting; no exam pressure or long text.
- For ages 6–8: foundational reading, writing, arithmetic, simple science and guided problem-solving.
- For ages 9–10: comprehension, multi-step arithmetic, basic science concepts, reasoning, projects and revision.
- Support Hindi, English and Romanized Hindi initially; only claim additional languages after content and speech quality tests.
- Include parent dashboard for progress summaries, topics practiced, strengths, concepts needing help, session limits and content controls. Guardian can review/delete child profile and learning history.

### Implementation reuse and safety
- Reuse the existing LocalKnowledgeDocumentStore / local knowledge retrieval, voice session, speech recognition, TTS/audio playback, memory controls and current quiz/question rendering where available. Do not add a second chat engine, speech stack, content database or audio player.
- Added `app/src/main/java/in/shraddha/ai/learning/ChildLearningPolicy.kt`: a pure Kotlin age-band and scaffold-step policy (ask → hint → simple explanation → worked example → retry; correct answer → encouragement/review). It does not generate curriculum content or evaluate answer correctness itself.
- Added unit tests in `app/src/test/java/in/shraddha/ai/learning/ChildLearningPolicyTest.kt`. Tests are authored but have not been run in this environment.
- Still required before claiming a working tutor: guardian profile/settings UI, vetted age-banded curriculum/content packs, child-safe answer evaluation and grounding, actual conversation/voice integration, progress storage with parent controls, session/break limits, accessibility checks, and end-to-end testing with adult supervision.
- Child mode must be guardian-enabled, transparent and easy to exit. No targeted advertising, purchases, external links, open social chat, public sharing, or collection of precise location/biometrics. Minimize retention; do not create voiceprints or face profiles. Avoid unsupervised web browsing and never ask a child for passwords, addresses, school name, phone number or other sensitive details.
- The tutor is educational support, not a replacement for a parent, teacher, special educator or professional assessment. Do not label/diagnose learning disabilities or mental-health conditions.


## Expanded curriculum catalogue and Skill Factory decision (2026-11-26)

The selected scope is all training domains and intelligence capabilities plus a future-extensible Skill Factory. The detailed architecture, lifecycle and rollout gates are in `docs/SKILL-FACTORY-ARCHITECTURE.md`.

Additional curriculum domains beyond the original ten:
- Parenting, infant care education, child development, original lullabies and stories.
- Age-aware Child Learning Tutor (3–10), with guardian controls.
- Cooking and food literacy.
- Health literacy and first-aid awareness (not diagnosis or treatment).
- Financial literacy and digital-payment safety.
- Repair/technical literacy, creativity/design, travel and daily-life skills.
- Accessibility and environmental/sustainability awareness.

Automatic learning means **automatic discovery and preparation**, not blind activation. A candidate must pass source allow-list, publisher-signature, licence, content safety, independent fact validation, duplicate detection and regression tests before auto-activation. User-imported knowledge requires explicit import consent. Private conversations, child data, camera frames and microphone recordings are not training sources by default.

### Skill Factory implementation foundation
- Added `app/src/main/java/in/shraddha/ai/learning/SkillPackTrustPolicy.kt`.
- Added `app/src/test/java/in/shraddha/ai/learning/SkillPackTrustPolicyTest.kt`.
- Added `docs/SKILL-FACTORY-ARCHITECTURE.md`.
- The policy rejects malformed metadata and queues candidates for review unless every independent activation gate passes and automatic learning is enabled.
- The policy is not a crawler, signature-verification implementation, fact-checker, downloader, model trainer or skill executor. Production adapters, persistent pack state, quarantine, WorkManager scheduling, review/rollback UI and full tests remain follow-up work.
