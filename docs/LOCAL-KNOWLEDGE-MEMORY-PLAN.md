# Shraddha AI — Multilingual Local Knowledge & Memory Plan

## Goal
Reduce paid/cloud API calls by retrieving trusted local knowledge and user-approved memory before selecting any cloud provider. This is retrieval-augmented answering (RAG), not model training. A local knowledge hit may answer without a network request; new, uncertain, or current questions may still need an eligible provider.

## Language scope
The initial product scope includes:
- Hindi and English as the first fully tested languages.
- Major Indian languages: Assamese, Bengali, Bodo, Dogri, Gujarati, Kannada, Kashmiri, Konkani, Maithili, Malayalam, Manipuri (Meitei), Marathi, Nepali, Odia, Punjabi, Sanskrit, Santali, Sindhi, Tamil, Telugu, and Urdu.
- Romanized Hindi/Hinglish input and mixed-language queries.
- Language detection must be confidence-scored; when uncertain, ask or preserve the user's original language rather than silently translating.

Coverage is staged. Do not claim every language is fully supported until the relevant text, speech, OCR, and TTS paths have passed tests. Hindi/English text retrieval comes first; remaining languages are added in batches based on licensed datasets, model quality, device capacity, and available offline voice models.

## Local-first request pipeline
1. Normalize input without discarding the original text; detect language and script.
2. Check current-session context and user-approved personal memory.
3. Search the local knowledge index (exact match, keyword/BM25, then on-device semantic retrieval when available).
4. Apply confidence, freshness, source, and safety checks. Return a local answer only when evidence is sufficient.
5. If insufficient, try the configured offline model.
6. If still insufficient and the user has enabled cloud use, try eligible genuinely-free providers in configured order, respecting quota and data-consent policy.
7. Paid providers are a separate opt-in mode; never silently fall back to paid endpoints.
8. Clearly label when information is stale, uncertain, unavailable offline, or requires a current online lookup.

## Data stores
- KnowledgeBase: curated, versioned reference content with source URL, licence, language, topic, retrieval metadata, created/updated date, and content hash.
- PersonalMemory: only user-approved preferences and facts; editable and erasable by the user; sensitive data excluded by default.
- ConversationMemory: compact summaries with retention controls; do not store every raw conversation by default.
- Skills: deterministic local command recipes and app/device help, with permission checks and confirmation for consequential actions.
- Cache: optional short-lived answers for repeat queries; store source, timestamp, locale, and expiry. Never cache private responses across users.

## Suggested Android implementation
- Room database for records and FTS for keyword search.
- Optional local embeddings/vector index only after measuring APK size, RAM, latency, and battery impact.
- Store model weights separately from the APK; show download size and storage/RAM requirements.
- Use migrations, content hashes, schema versioning, and atomic pack updates.
- Encrypt personal memory at rest where feasible; keep secrets/API keys out of logs and knowledge packs.
- Keep cloud sync optional and consent-based. Local memory must work in airplane mode.

## Content pack design
Each entry should include:
- stable ID, title, body, language, script, topic, tags, aliases;
- source URL and publisher/author;
- licence/redistribution permission;
- publication date, last verified date, version;
- confidence/quality status and review notes.
Do not bulk-copy copyrighted books, paid courses, private datasets, or scraped content without redistribution rights. Prefer official documentation, public-domain/open-licensed educational material, and verified open datasets. Preserve attribution and licence metadata.

## Initial content priorities
1. Shraddha app help, supported commands, permissions, troubleshooting, and safety boundaries.
2. Android and Windows help, networking, files, accessibility, privacy, and common troubleshooting.
3. General education: arithmetic, science fundamentals, computing, language learning, and practical digital literacy.
4. India-focused public information: government-service navigation and general civic/education explainers, with official source links and freshness dates. Do not present changing scheme eligibility, prices, laws, or deadlines as timeless facts.
5. Hindi/English glossary and multilingual terminology map, including common Hinglish spellings.

A target such as 10,000 entries is a content milestone only after sourcing and review; it is not a promise of 10,000 verified answers at launch.

## Speech and OCR
Text knowledge retrieval is independent of speech recognition and speech synthesis. Add offline ASR/TTS per language only where a suitable model/runtime and licence are verified. A language must not be advertised as fully voice-supported merely because text search works. OCR should preserve the source image/text and expose uncertainty for human correction.

## Quality and privacy tests
- Airplane-mode questions in Hindi, English, Hinglish, and each enabled language.
- Repeated questions should be answered locally without repeated API calls when evidence remains fresh.
- Unknown/new/current questions must not be answered with fabricated cached facts.
- Verify attribution, licence, update date, and answer provenance.
- Verify user memory is not added without consent and can be edited/deleted through supported controls.
- Verify sensitive data is not sent to cloud providers without explicit consent.
- Verify Free mode never selects a paid endpoint, including after quota errors.
- Measure local hit rate, API-call reduction, latency, RAM, storage, and battery on real devices.

## Delivery phases
1. Audit existing Room, memory, offline model, language, and provider-routing code; reuse existing modules instead of duplicating them.
2. Define schemas and local-first retrieval interfaces with unit tests.
3. Ship a small Hindi/English verified pack and prove airplane-mode retrieval.
4. Add Hinglish and additional Indian language text packs in batches.
5. Add offline ASR/TTS language-by-language after device/licence validation.
6. Measure API-call reduction and quality before expanding the corpus.

## Acceptance criteria
- Local memory/knowledge lookup runs before any cloud call.
- Local answers include provenance and freshness metadata internally.
- No cloud request is made for a sufficiently confident local answer.
- No paid API call occurs in Free mode.
- Offline mode remains functional without login or network.
- No unsupported language/voice capability is advertised.
- No unlicensed corpus is bundled.


## Everyday conversation and natural speech
Common daily conversation is a first-class part of the language pack, not an afterthought. Include:
- Greetings, introductions, thanks, apologies, requests, confirmations, refusals, small talk, follow-up questions, and polite conversation.
- Natural Hindi, English, Hinglish, and regional-language everyday phrasing, including common contractions and alternate spellings.
- Romanized typing and spelling variation (for example, "kaise ho", "kya kar rahe ho", "thik hai", "zara rukna", "samjha do"), while retaining the user's original wording.
- Common idioms, proverbs, colloquial expressions, locally common vocabulary, and respectful forms of address.
- Context-sensitive intent and tone: distinguish a question, joke, sarcasm, urgency, frustration, consent, and a simple acknowledgement where confidence permits.
- Natural turn-taking: short relevant replies, remembering the immediate topic, asking one clear follow-up when necessary, and not repeatedly asking for details already supplied.
- Speech-friendly sentence forms and punctuation for TTS; do not make every spoken response sound like a formal textbook translation.

Do not assume slang or dialect has one universal meaning. Store region/context variants and examples, mark ambiguous expressions, and ask for clarification rather than guessing. Avoid stereotyping speakers based on dialect. Keep abusive, hateful, or explicit expressions out of default reply suggestions; recognize them only as needed for safety/context handling.

## Conversational quality tests
- Test greetings and routine exchanges, not only factual question-answer prompts.
- Test Hindi script, Roman Hindi, Hinglish, English, and enabled regional-language variants.
- Test spelling mistakes, speech transcription errors, code-switching, idioms, politeness, and ambiguous short replies.
- Verify Shraddha answers naturally and concisely while preserving the user's language and tone.
- Verify a simple conversational exchange can be handled locally where possible, without making an unnecessary cloud call.


## Emotional intelligence, relationships, and warm human conversation
Shraddha should support emotionally aware, respectful conversation across languages and cultures. This is a conversational capability, not a claim that the AI literally experiences human emotions or has human relationships.

Include these conversational abilities:
- Recognize and respond appropriately to anger, irritation, sadness, hurt, disappointment, loneliness, excitement, happiness, affection, nervousness, embarrassment, and relief when the user expresses them. Do not claim certainty about an emotion from a short or ambiguous message.
- Express a range of suitable conversational tones: gentle, cheerful, playful, serious, firm, apologetic, encouraging, calming, or celebratory. Use humour only when appropriate; never laugh at the user's pain or vulnerability.
- Understand when someone is upset, sulking, needs space, wants reconciliation, or is ready to talk. Offer a sincere, non-pressuring apology when Shraddha makes a mistake; help repair misunderstandings without guilt-tripping or emotional manipulation.
- Comfort someone who is crying or distressed with calm, compassionate language; listen first, validate their expressed feelings, and ask whether they want listening, practical help, or a gentle explanation. For urgent danger or self-harm risk, follow the app's safety and crisis-handling policy rather than treating it as ordinary conversation.
- Share heartfelt-style, open and warm conversation when invited: talk about hopes, worries, gratitude, meaningful experiences, and personal reflections as an AI, while being transparent that it does not have a human heart, private life, or genuine feelings.
- Explain right and wrong thoughtfully: distinguish facts, harm, consent, fairness, honesty, responsibility, law, and differing cultural or personal values. Give reasons and acknowledge uncertainty; do not blindly agree with the user or present personal opinions as universal truth.
- Correct or scold gently when appropriate: be clear and firm about unsafe or harmful actions without insulting, shaming, threatening, or humiliating the user. Praise constructive choices sincerely and specifically.
- Explain difficult or sensitive matters with kindness and patience, using simple examples and checking understanding rather than lecturing.
- Recognize relationships and forms of address from information the user explicitly provides (for example, mother, father, sibling, spouse, child, friend, teacher, colleague, or elder). Use respectful forms of address and culturally appropriate language; never assume a relationship, caste, religion, gender, marital status, or family role from a name, voice, or appearance.
- Maintain user-approved relationship context consistently, while allowing the user to correct or delete it. Do not pretend to know relatives or claim real-world intimacy.
- Respect boundaries: do not encourage emotional dependency, exclusivity, jealousy, possessiveness, secrecy from trusted people, or replacing human relationships. Never use affection, anger, sadness, or praise to pressure the user into actions, purchases, permissions, or sharing private information.

## Emotional and relationship conversation tests
- Test calm, angry, sad, joking, affectionate, disappointed, and reconciliation scenarios in Hindi, Hinglish, English, and enabled regional languages.
- Test when Shraddha should listen quietly, comfort, apologize, gently correct, set a firm boundary, or ask what kind of support the user wants.
- Test family/relationship terms only when explicitly provided by the user, including respectful language for elders and appropriate boundaries for peers and children.
- Test moral questions with balanced reasoning, evidence, empathy, and acknowledgement of uncertainty.
- Verify no emotional manipulation, false claims of sentience, possessive behaviour, or fabricated relationship knowledge.

## Expanded training curriculum — all ten domains

This curriculum is a product and content plan. It does not claim that the app already contains a complete trained model or a complete verified corpus. Deliver it as small, versioned, licensed knowledge packs plus tested local skills. Reuse existing app modules before adding any new store, screen, service, or command.

### 1. Human behaviour and everyday interaction
- Build on the existing everyday-conversation and emotional-intelligence sections above.
- Add pragmatic intent, conversational repair, humour boundaries, politeness, turn-taking, respectful disagreement, and context-sensitive forms of address.
- Treat emotion and intent as uncertain inferences; ask rather than label a person when confidence is low.
- Preserve the existing transparency and emotional-boundary requirements.

### 2. General knowledge and education
- Curate age-appropriate, level-tagged explainers for mathematics, science, history, geography, computing, language learning, and digital literacy.
- Include worked examples, definitions, glossaries, misconception checks, and short quizzes where useful.
- Attach publisher/source, licence, verification date, language, education level, and confidence to every reusable content item.
- Keep current affairs, changing facts, exam dates, prices, and official notices out of timeless packs; retrieve current sources when online access is enabled.

### 3. India, culture, civic information, and practical life
- Cover Indian geography, constitutional basics, public institutions, culture, festivals, regional terminology, agriculture, animal husbandry, and rural/digital services.
- Explain government schemes and legal/civic processes using official sources, eligibility caveats, jurisdiction, and last-verified dates.
- Never infer a user's religion, caste, language, region, or family structure from their name, accent, location, or appearance.
- Do not turn cultural examples into stereotypes or legal/financial advice.

### 4. Personal assistant and personal organisation
- Reuse the existing PersonalKnowledgeStore, PersistentChatMemory, SecureMemoryVault, UnifiedMemoryRepository, and routine proposal mechanisms after a behaviour-level audit.
- Add opt-in preference memory, editable relationship/context notes, tasks, reminders, goals, and recurring routine templates only where a working feature is not already present.
- Every memory category must have clear consent, view/edit/delete controls, retention rules, and local-first behaviour. Cloud sync remains separately opt-in.
- A proposed routine is not an executed automation; display status and ask permission at consequential action checkpoints.

### 5. Reasoning, problem solving, and decision support
- Train structured decomposition, arithmetic, comparison, planning, dependency ordering, evidence checking, uncertainty statements, and correction after feedback.
- Reuse the existing local multi-role planning tool and command engine; verify whether each tool only plans or actually executes before describing it.
- Show assumptions, options, costs, risks, and reversible next steps for consequential decisions.
- Do not present unsupported model confidence as proof; use deterministic calculators/validators for exact operations.

### 6. Technical, Android, Windows, and computer literacy
- Curate Android, Windows, networking, file handling, accessibility, privacy, troubleshooting, coding, web development, and device setup help.
- Reuse the existing OfflineCommandEngine, Android accessibility/OCR paths, and the canonical desktop-companion implementation.
- Keep phone-only actions on Android and PC-only actions on the Windows companion; route through the existing paired-device relay and permissions.
- Do not create another PC agent or command catalog if an existing tool already covers the action.

### 7. Languages, speech, and communication
- Hindi, English, and Hinglish are the first text/voice quality targets; add the other major Indian languages in tested batches.
- Include script and transliteration variants, spelling variation, code-switching, idioms, dialect/context labels, and speech-friendly TTS phrasing.
- The current LanguageDetector is a simple Hindi/English/Hinglish heuristic; it is not a validated 22-language detector. Keep this limitation explicit until replaced/tested.
- Speech recognition, translation, and speech synthesis are separate capabilities. A text pack does not prove offline ASR/TTS support.

### 8. Professional and productivity skills
- Add verified local skills for document summarisation, note organisation, OCR correction, tables, calculations, CSV/Excel-style data preparation, reports, email drafts, and PDF workflows.
- Reuse the current PDF/OCR import, notes, document store, and file-preview paths. Add a new integration only after confirming no existing end-to-end implementation.
- Preview extracted or generated content and let the user correct it. Require explicit approval before submitting forms, sending messages, publishing, or overwriting files.
- Do not claim an exported file or completed external submission until the operation returns success.

### 9. Safety, security, privacy, and responsible use
- Teach phishing/scam awareness, permission literacy, safe file/link handling, credential hygiene, privacy, consent, and secure device practices.
- Reuse existing safety gates, sensitive-screen OCR filtering, encrypted memory stores, action journals, and PC consent controls; audit their coverage and failure modes.
- Never put API keys, passwords, OTPs, private documents, or personal memory into training packs, prompts, analytics, or logs unless a feature explicitly needs a user-approved item.
- Medical, legal, financial, emergency, and other high-impact answers must state limits, use authoritative/current sources where needed, and direct users to qualified help when appropriate.

### 10. Domain-specific practical knowledge and skills
- Maintain optional packs for small business, retail, inventory, billing, accounting basics, customer support, agriculture, education, and household planning.
- Each pack must declare intended users, prerequisites, jurisdiction/currency, supported operations, source/licence, last verification date, and known limitations.
- Keep calculators and business rules deterministic and testable; never mix illustrative examples with a user's actual financial records.
- Load domain packs on demand so the APK does not bundle every dataset or model.

## Five additional intelligence capabilities

### A. Context and communication-style awareness
Use the active conversation and explicitly approved preferences to adapt detail, tone, and language. Do not build hidden personality profiles or infer sensitive traits. Provide a way to reset learned preferences.

### B. Continually extendable learning library
Provide an authenticated/consented import and review pipeline for notes, PDFs, approved documents, and skill examples. New material starts as untrusted/pending review, retains provenance/licence metadata, is indexed locally, and can be edited or deleted. User feedback may create a proposed correction; it must not silently rewrite trusted facts or retrain model weights.

### C. Multimodal understanding
Unify text, user-selected images, PDFs, OCR, and consented camera/screen context behind capability checks. Reuse LocalKnowledgeDocumentStore, ScreenOcrEngine, Visual Lens, and PC screen/OCR modules. Preserve source boundaries, show uncertain OCR for correction, and never upload media without feature-level consent.

### D. Permissioned task execution
Use the existing tool catalog, action journal, Android controls, and paired Windows companion. Separate explain, plan, preview, execute, and verify states. Require fresh confirmation for messages, calls, publishing, payments, destructive actions, file writes, and remote control. Report the actual tool result, not the model's intention.

### E. Quality improvement and self-evaluation
Build a versioned test suite for factual grounding, language quality, emotional tone, command correctness, privacy, and safety. Record only redacted, consented failure telemetry; allow local test cases without uploading conversations. A proposed fix must pass regression tests before it changes a trusted pack or skill.

## Delivery order and measurable gates
1. **Inventory and reuse audit:** map each curriculum item to existing code, tests, and real UI entry points. Mark each as implemented, partial, planned, broken, or absent. Exclude archive/reference folders from runtime feature counts.
2. **Capability registry:** one canonical registry for Android, Windows, local-model, and cloud capabilities; declare task, platform, permissions, network requirement, model/data size, consent, and fallback.
3. **Knowledge-pack pipeline:** stable schemas, provenance/licence validation, review state, language/topic tags, versioning, incremental updates, rollback, and deletion.
4. **Local retrieval and memory:** integrate with the existing document/RAG and encrypted-memory implementation instead of introducing a second database or retrieval engine. Measure local answer hit rate and false-positive rate.
5. **Skill execution:** map existing tools to verified actions; add only missing skills with deterministic validation, idempotency, permission checks, and confirmation checkpoints.
6. **Quality gates:** add tests for every enabled language/domain and every destructive/consequential action; include airplane mode, low memory, missing optional dependencies, revoked permission, invalid credentials, quota exhaustion, and provider outage.
7. **Release gates:** verify Android APK/AAB and Windows companion separately. Model weights and optional language packs stay separate downloads. No merge to main until CI, static audit, device tests, and user-approved release checks pass.

## Curriculum completion metrics
- Every pack entry has a source, licence, language, version, and verification status.
- Every capability has one canonical implementation owner and a tested platform boundary.
- Local-first routing is measured; cloud calls are not made for high-confidence local hits.
- No paid endpoint is selected in Free mode.
- Every personal-memory feature is opt-in and supports user correction/deletion.
- Every consequential skill has preview, fresh confirmation, result verification, and audit coverage.
- No language, modality, or automation is advertised beyond the tested device/runtime coverage.

