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
