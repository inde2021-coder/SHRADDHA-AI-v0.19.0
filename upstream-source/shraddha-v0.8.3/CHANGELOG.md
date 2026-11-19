# Changelog

## v0.8.3 — Agent Orb & Navigation Redesign
- Added a layered, animated 3D-style floating orb overlay with quick actions, plus an animated Shraddha agent orb to the home dashboard and AI Assistant screen. The orb is explicitly decorative until the user taps to start voice recognition; it does not claim always-on microphone access.
- Added a fixed bottom navigation bar for Home, Assistant, Devices, and Experts. Workspace content remains inside the scroll area and opens one workspace at a time.
- Kept Data Sharing, AI Lab, and App Permissions available as separate Home workspace tiles.
- Increased Android versionCode to 9 and versionName to 0.8.3.
- Validation: ZIP integrity and static source checks; Android Gradle build/device test not available in this environment.

# Changelog

## 0.5.0 – Advanced AI Lab
- Added AI Agent Planner endpoint that returns a bounded, reviewable JSON task proposal; it never executes device actions.
- Added multi-model AI Council endpoint to compare up to three configured providers in parallel.
- Added Android Advanced AI Lab UI for task planning and provider comparison.
- Added server-side action/tool allowlisting, bounded plan steps, mandatory approval metadata and proposal-only execution mode.
- Kept provider API keys on the backend; no keys are embedded in the Android app.


## 0.4.0 – Device foundation, Visual Lens camera prototype and Custom Data Hub
- Added explicit rear/front Camera2 sharing service with Android camera privacy indicator and persistent foreground notification.
- Extended binary relay frames with a frame type so remote screen and remote camera previews are displayed separately.
- Added per-category custom data sharing preferences and publish-on-connect behavior.
- Added one-time data-share request/approval flow, short-lived category grants and bounded small-file transfer (650 KB file cap in Android UI).
- Added local file receipt in app-private storage (`files/shared`).
- Added AI chat gateway support for Groq and Perplexity alongside existing Gemini, OpenAI and Anthropic support.
- Added local-only AI memory notes.
- Added permission and privacy caveats; camera streaming is a prototype JPEG relay, not production WebRTC.

## 0.3.0
- Reworked the main dashboard and added paired-device picker, remote app launch, visible-text click and additional remote actions.
- Added multi-device relay and enhanced floating orb.


## v0.6.0 — On-device intelligence and private memory
- Added bilingual offline command intent recognition (proposal-only; no automatic execution).
- Added AES-GCM encrypted personal memory using a non-exportable Android Keystore key.
- Added local keyword retrieval over encrypted notes (RAG-lite; stays on device).
- Added provider configuration status endpoint and AI text summarization endpoint.
- Added in-app controls for provider diagnostics, summarization, encrypted memory, and offline intents.
- Bumped Android/backend version to 0.6.0.

## 0.7.0 — Expert Course, Repair & Creator Studio
- Added a domain-aware AI expert endpoint for electronics, electrical, mechanical, medical education, agriculture, civil engineering, law, repair troubleshooting, creator studio, and general STEM.
- Added six task modes: structured learning, problem solving, conceptual project/design, diagnosis, exam preparation, and content creation.
- Added Android Expert Studio controls for domain, mode, provider, question, and formatted answer display.
- Added domain-specific safety guardrails for medical, legal, electrical, mechanical, repair, agriculture, and civil engineering requests.
- AI output remains advisory; no physical repair, professional certification, diagnosis, legal representation, or construction approval is claimed.


## v0.8.0 — Complete Expert Hub
- Added a new authenticated `POST /api/studio/work` endpoint with 20 expert workspaces: AI University, electronics, electrical, mechanical, CAD/3D, circuit/PCB, civil/building concepts, medical education, agriculture, law, repair, creator, software, research, data science, robotics, drone, GIS, smart home/IoT and business.
- Added 10 work modes: teach, full course blueprint, quiz, solve, design, diagnose, research, create, estimate and review.
- Added domain-specific guardrails, input limits, provider selection and structured response metadata.
- Added Android Complete Expert Hub UI connected to the backend, local project library (up to 12 saved outputs), reopen and Android share-sheet export.
- Existing v0.7.0 Expert Studio, device link, local encrypted memory and other modules are retained.
- Scope note: CAD/PCB/building deliverables are AI-generated conceptual/text outputs; no native CAD renderer, PCB fabrication validation, medical diagnosis, legal representation, structural certification, verified web citations or automatic background cloud sync is included.

## v0.8.1 build-fix (2026-11-13)
- Fixed Android Accessibility GestureDescription import: the class belongs to `android.accessibilityservice`, not `android.view`.
- Added OkHttp 4 extension imports for `MediaType.Companion.toMediaType` and `RequestBody.Companion.toRequestBody` used by DeviceLinkManager.
- Called `super.onServiceConnected()` before registering the active AccessibilityService instance.
- This patch addresses the reported unresolved-reference Kotlin errors. A full Android Gradle build still needs verification in Android Studio with the Android SDK installed.
