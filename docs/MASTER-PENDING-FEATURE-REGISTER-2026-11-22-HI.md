# Shraddha AI — Master Pending Feature Register

**Date:** 22 November 2026
**Branch:** `feature/complete-pending-shraddha-features`
**Rule:** A screen, button, API route, source file or unit test alone does not make a feature complete. Completion requires integration, persistence, permissions, failure handling, security review and relevant device/provider verification.

This register consolidates the user's earlier feature requests and the current source audit. It is a living work list, not a release claim. Status labels are deliberately conservative.

## Status meanings

- **Source present** — relevant code exists; integration or runtime validation may remain.
- **Partial** — only a foundation or a subset of the requested behavior exists.
- **Blocked externally** — requires a provider account, API key, licensed model, companion installation, hardware or user permission.
- **Not implemented** — no complete production path has been verified.
- **Do not claim complete** — safety, legal, licensing or physical-device evidence is missing.

## A. Product identity, voice and design

| Request | Current source observation | Remaining work |
|---|---|---|
| Renameable assistant identity; new name replaces “Shraddha AI” in UI and voice | Assistant name preference and dynamic voice prompt/status use exist. 23 Nov source pass updates Settings labels, About clipboard label, Home core label and Voice fallback/error strings. | Continue auditing every screen, notification, accessibility label, TTS string, desktop companion and exported document; launcher app label remains static by Android design. |
| Continuous microphone after explicit activation; one mic control; no repeated connect/disconnect sounds | Voice session manager, foreground service and reconnect backoff exist. | Verify audio focus, mute, app background/foreground, process death, permission revocation, OEM battery restrictions and explicit stop on physical devices. |
| Hindi and English conversations | Language detection/preference and Hindi/English voice challenge paths exist. | End-to-end Hindi/English quality and provider fallback evaluation. |
| Custom Shraddha voice | TTS client/gateway configuration exists. | Licensed voice weights, deployed HTTPS TTS service, secrets and quality/latency verification. No model or voice license may be fabricated. |
| Full cinematic Premium 3D / layered glass design | Home has a custom animated orb; pending branch now adds shared deep-teal/cyan layered panels, inset glass inputs and raised aqua actions. | Redesign all workspaces consistently: Voice, Visual Lens, Device Command Center, Expert Studio, Memory/Identity, Project Lab, Cloud/Data, Privacy/Permissions, Settings and Future Intelligence. Preserve the existing MainActivity/Launcher, old LIA source and all working feature connections; do not replace or remove them. Add functional listening/thinking/speaking states, motion preferences and accessibility. No fake/demo controls or fabricated Connected/Active statuses. |
| Orb styles, energy rings, particles, listening/response pulse and touch response | 3D-style orb and orbit points are present. | Connect animation states to actual session state; provide Classic/Energy/Neon themes and reduced-motion behavior; do not imply fake online/work status. |
| Developer/About details and version/copyright | AboutScreen already reads the installed version/build dynamically and displays developer Shrad Patel, inde2021@gmail.com and © 2026 Shrad Patel. | Verify About navigation and actual packaged APK display during the final app/device pass. |

## B. AI intelligence, agents and autonomous work

| Request | Current source observation | Remaining work |
|---|---|---|
| Multi-agent council | Parallel Planner/Researcher, 1–5 Specialists, Critic and Verifier are routed through Main Brain and WorkManager. 23 Nov source pass adds the Multi-Agent Workspace route from Home/Quick Actions. | Source now includes provider-call/time limits, per-provider call caps, encrypted per-stage evidence, cancellation/retry status and provider usage accounting. Exact monetary spend estimation and checkpoint-level resume remain; no final build/device verification yet. |
| Autonomous project manager | Task planner, workflow lifecycle and project tracker exist. | Persisted dependency-aware execution, real task adapters, resumable steps, progress, checkpoints, rollback and explicit approvals. |
| Self-review / verification engine | Acceptance-check scoring and reporting exist. | Make verification mandatory for every generated artifact and workflow; block completion on failed required checks. |
| Deep research with citations | Backend/provider research paths exist. | Real source retrieval, URL validation, evidence provenance, citation checks and no unsupported “researched” claims. |
| Personal knowledge brain / graph | Encrypted local graph and memory repository exist. | Unified ingestion from user-approved sources, source provenance, embeddings/RAG, cross-device sync, conflict handling and deletion propagation. |
| Predictive assistant / digital twin | Opt-in repeated-event suggestions and editable memory primitives exist. | Persistent signal source, feedback loop, schedule/quiet hours, explainability and strict no-covert-monitoring controls. |
| Meeting intelligence | Transcript marker extraction exists. | Consent-based audio/transcription, diarization, correction UI, calendar and export integration. |
| Voice-created workflows | Existing app training and workflow router exist. | Voice authoring, safe review, app-specific recovery and reusable workflow editing. |
| Background task runner | WorkManager queue and scheduler exist. 23 Nov source pass adds encrypted per-stage evidence, attempt history, cancellation status, retry-pending status, per-provider call counts and elapsed time. | Checkpoint-level resume (skip already completed stages safely), process-death/provider-timeout recovery verification and task-history UX remain. |

## C. Phone, app and screen control

| Request | Current source observation | Remaining work |
|---|---|---|
| Open/control apps by voice | Accessibility Operator and app launch/action tools exist. | Expand supported actions, app-specific workflows, OCR fallback, canvas/WebView limitations and Android-version/device matrix. |
| Read current screen | Accessibility snapshot and opt-in OCR exist. | Permission-denied/revoked flows, sensitive-field filtering, protected-screen handling and user-visible session indicator. |
| WhatsApp/SMS hands-free | Message composer and explicit send approval paths exist. | Official supported APIs/intent flows, robust recipient resolution, exact preview and user-tap send; chat history/search is not claimed. |
| Calls by voice | Dialer/call controls and PhoneCallAgentScreen exist; 23 Nov pass registers the SIM Call Agent route and exposes it in Quick Actions. | Verify real SIM call flows, contact disambiguation, call audio routing, Edesy DID/Jio forwarding and emergency handling on device. |
| Notifications | Metadata-only encrypted listener/history and quiet hours exist. | Rich per-app rules, action support, retention controls and Android OEM/version testing. |
| Clipboard control | Consent-gated relay and integrity checks exist. | True E2E encryption/key agreement, key rotation, replay protection and multi-device tests. |
| File transfer | SAF-based 100 MiB sender/receiver, 48 KB encrypted chunks, SHA-256, receiver approval, save-location picker, pause/resume and cancellation exist in source. | Real-device interruption/process-death resume, relay quotas, malware scanning and device-matrix tests remain. |

## D. Visual Lens and spatial intelligence

| Request | Current source observation | Remaining work |
|---|---|---|
| Camera Visual Lens Q&A | Camera/screen capture services exist. Pending branch now encrypts visual frames with mutually verified ECDH/AES-GCM keys, session/device/frame/sequence-bound AAD and replay checks; 23 Nov pass routes Live Eyes through the selected AI provider and adds a voice Q&A entry from Visual Lens. | Full live camera-frame-to-AI grounding, camera permission lifecycle, physical-device latency and privacy tests. |
| Camera live translation/subtitles | Translation request validation and provider text path exist. | Streaming speech/camera translation, subtitle overlay, offline language packs and quality testing. |
| Object intelligence/tracking | Vision request path exists. | Continuous frame pipeline, object grounding/tracking and on-device fallback. |
| 3D environment mapping | Validated landmark model exists. | ARCore/depth capture, SLAM, persistence, renderer and calibration. |
| Visual memory | Encrypted memory primitives exist. | Image indexing/storage lifecycle, consent, expiry, search and deletion propagation. |
| Visual troubleshooting | Safety-first troubleshooting playbooks exist. | Camera-guided step verification, trusted device manuals and escalation UX. |

## E. PC, device fabric and external hardware

| Request | Current source observation | Remaining work |
|---|---|---|
| Windows PC control from phone | Advanced Windows companion source and Android device relay source exist separately. | Verify one authoritative Shraddha-branded companion, real pairing, action protocol, permission parity, verified result acknowledgements and real phone/PC tests. |
| PC screen awareness/OCR | Advanced companion has active-window OCR source. | Verify real target apps, secure screen sharing, multi-monitor and failure behavior. |
| PC file operations | Approved-root list/read/search and bounded single-file update exist. | Multi-file context, diff preview, build/test sandbox, logs, rollback, move/delete approval and diagnostics. |
| Phone-to-phone control | Relay pairing, E2E message envelopes, target approval and result observation exist. | Expiry/revocation race tests, key persistence/rotation, reconnect and device-matrix validation. |
| Remote desktop | Screen sharing and remote actions exist. | Complete session UX, keyboard/mouse parity, multi-monitor, clipboard/file integration and independent security review. |
| Smart home | No complete production connector verified. | Matter, MQTT or Home Assistant adapters, device allowlists, confirmation and manual override. |
| Phone-to-PC transfer | Small-file and chunk-validation groundwork exists. | Complete resumable encrypted transport and end-to-end interruption tests. |

## F. Expert Studio, Creator and engineering

| Request | Current source observation | Remaining work |
|---|---|---|
| Expert Studio across engineering, agriculture, finance, GIS, legal and medical research | Expert project library, templates and assist routes exist. | Domain-specific verified data sources, calculation engines, provenance and professional review boundaries. |
| Electrical/electronics | Templates and general assistant paths only. | Real schematic/PCB tools, simulation, parts/libraries, ERC/DRC and export. |
| Mechanical/CAD/civil architecture | Project planning and design prompts exist. | Real CAD geometry, constraints, simulation, IFC/DWG/STL export and validation. |
| App/game/website creator | Website Forge and coding/project tools exist. | Sandboxed build/test runner, logs, reproducible outputs, app packaging, preview and safe deploy workflow. |
| Video/3D/image creator | Some provider and design workflows exist. | Real licensed generation/rendering pipelines, project files, timeline editing and export. |
| Document intelligence | OCR, PDF/document utilities and project features exist. | Reliable import/export, tables, batch processing, multilingual layout and validation. |

## G. Security, privacy, cloud and recovery

| Request | Current source observation | Remaining work |
|---|---|---|
| User-owned API keys for multiple providers | Gemini key uses Android Keystore AES-GCM; provider-specific stores exist. | Audit every provider store, migration, backup exclusion, logs and key-loss behavior. |
| Firebase cloud memory | Firestore vault and encrypted local repository exist. | Real project configuration, auth, rules/emulator and live round-trip tests; reconcile conflict/deletion semantics. |
| Encrypted backup/restore | Password-protected backup and v3 preference backup exist. | Restore drills across app versions/devices, corrupted backup handling and recovery UX. Secrets/biometrics remain excluded intentionally. |
| Device pairing/revocation | Relay owner revocation and encrypted peer message support exist. | Durable revocation, key rotation, reconnect and server restart tests. |
| Signed plugins | No complete isolated signed plugin framework verified. | Signed manifests, capability scopes, sandbox, audit, version pinning and revocation. |
| Privacy/security dashboard | Consent and permission controls exist across modules. | Unified permission inventory, session visibility, audit history, emergency stop/revoke and data export/delete. |
| Licensing/Pro entitlement | Historical local activation code requires review. | Server-side signed entitlement validation; never claim paid entitlement from a local non-empty key. |
| Biometric recognition | ONNX inference and consent vault source exist. | Licensed/provenance-checked model distribution, calibration, FAR/FRR/EER studies and certified PAD evidence. Basic active challenge is not certified anti-spoofing. |

## H. Separate Shraddha OS request

The user also explicitly asked to replace the phone's existing operating system with **Shraddha OS**. This is not an Android app feature and cannot be delivered by installing the current Shraddha AI APK. It requires a device-specific Android Open Source Project/compatible ROM build, exact target device codename and vendor blobs, bootloader unlock and recovery/fastboot path, kernel/device trees, verified boot/signing strategy, data backup and a recoverable rollback image. No destructive flash, wipe or OS replacement should be attempted without the exact device target and explicit confirmation. Current branch is the Shraddha AI app/companion codebase, not a complete bootable Shraddha OS image.

## I. Real-world acceptance blockers

The following cannot be completed by source changes alone and must remain clearly labelled until supplied and verified:
- Android phones and supported OS/OEM matrix for microphone, camera, Accessibility, notifications, SMS, WhatsApp and calls.
- A real Windows PC with the companion installed and paired.
- Firebase project, configured rules, account and storage access.
- Valid provider API keys, quotas, endpoints and user-selected consent.
- Licensed custom voice and wake-word model/service.
- Licensed biometric model provenance and independent presentation-attack evaluation.
- Edesy DID, Jio forwarding setup and a real two-way call test.
- Production signing keystore and release credentials.

## Work policy for this branch

1. All implementation stays on `feature/complete-pending-shraddha-features`; do not write to `main`.
2. Do not run tests during the feature-completion pass, per user instruction.
3. Source review and static reasoning are allowed, but must not be described as test results.
4. Do not fabricate providers, API keys, model weights, licences, test results or external account setup.
5. After implementation work is complete, run one consolidated verification pass; fix every failure before reporting release readiness.
6. Keep every external/device-dependent feature marked as requiring setup or real-world validation until it has actually passed.


## 22 November source work added on this branch

- Parallel multi-agent execution (Planner/Researcher concurrent; 3 Specialists concurrent; Critic and Verifier gates) was added and wired to WorkManager plus an Advanced AI Lab action with progress/result observation.
- Shared workspace panels received a layered glass-gradient/elevation pass; this is not the complete screen-by-screen redesign.
- Remaining pairing/timeline UI copy now reads the configured assistant name in those locations; a full hardcoded-name audit is still pending.
- Screen/camera frame relay was corrected to use mutually verified E2E encryption, session-bound AAD and replay checks. Tests were authored but deliberately not run yet.
- No tests or builds have been run for these pending-branch commits, following the user's instruction to finish the implementation pass first.


### File transfer protocol correction — 22 November

A cross-layer source review found Android's previous 48,000-byte chunk sizing did not match the relay's 48 KiB (49,152-byte) contract and could create an empty trailing chunk at exact boundaries. The pending branch now uses one shared Android protocol helper for chunk count, sender/receiver offsets and final chunk size, aligned with the backend. Regression tests were added but not run. The 100 MiB app cap, resumable/process-death edge cases, malware scanning and real-device transfer tests remain.


## Source completion pass — 23 November 2026 (not yet verified)

- Added `CouncilEvidenceStore` backed by the existing encrypted feature store. Planner, Researcher, each Specialist, Critic and Verifier outputs are stored per task and retry attempt; status, final answer, provider call counts and elapsed time are retained without placing raw outputs or API keys in WorkManager Data.
- Added `CouncilExecutionBudget`: maximum total provider calls, per-provider call cap, global execution deadline and bounded per-stage output. Background queue persists the selected limits and Main Brain enforces them.
- Added a Multi-Agent Workspace with configured-provider selection, 1–5 specialist control, total/per-provider call budgets, time limit, language selection, live WorkManager status, saved stage evidence, result display and cancellation. Registered it in Home, Quick Actions and navigation.
- Cancellation now records a durable CANCELLED status; transient failures can be marked RETRY_PENDING and partial evidence is retained. A retry currently starts a new attempt rather than resuming from the last completed stage.
- Added regression-test source for provider budgets, stage callbacks and encrypted evidence persistence. Tests have not been run for these newest commits.
- Registered the existing Phone Call Agent screen in navigation and Quick Actions.
- Live Eyes now uses the selected AI provider rather than hardcoded Gemini; Visual Lens has an explicit voice Q&A route when camera service is active.
- Assistant rename audit updated Settings labels, About clipboard label, Home core label and Voice fallback/error text. Full cross-app/desktop/export audit remains open.
- Added Device Center UI and navigation: create/join HTTPS pairing, restore encrypted saved credentials, show paired peers, require E2E fingerprint confirmation before protected PC actions, request remote control/OCR/file list, open encrypted file transfer, revoke peers and clear this phone's saved pairing. DeviceLinkManager now persists/restores pairing through DeviceCredentialStore and clears local credentials if this phone is revoked. No live relay/Windows pairing test has been run on these newest commits.
- Added Expert Studio UI for the documented expert domains/modes, real authenticated Expert Assist/Studio Work/provider-comparison calls and encrypted Expert Project Library save/open/delete. Medical/legal/engineering limitations are shown in the UI. Live backend/provider credentials still need verification.
- Added Project Lab UI and encrypted ProjectPlanStore for local-first project plans and milestone status; project IDs are collision-resistant. It explicitly labels DONE as user-marked, not AI-verified.
- Added Data Vault UI for passphrase-protected backup export/import through Android's file picker, with explicit restore confirmation, size limits, and a clear exclusion list for API keys, gateway tokens, biometrics and device-pairing secrets. This is an encrypted local file backup; cloud sync/server persistence is not implemented and is not claimed.
- Added explicit per-provider API connection checks to API Keys. A test sends one short request only after the user taps the button and warns that provider usage charges may apply; there is no automatic background test.
- All 14 existing source branches were compared and consolidated into the current working branch. Neon Glass Home Screen/avatar and the emulator diagnostic fix are included as merge parents. Main remains untouched.
- A draft integration PR triggered CI verification runs prematurely before this no-test rule was re-read. Those runs exposed and prompted fixes for whitespace, a shared test-IP rate-limit collision and missing Compose imports. They are not final acceptance results and do not cover the newest source changes. The draft PR was closed during the source-completion pass; do not trigger further tests until implementation is complete.

## Historical design constraints recovered from prior chats

The user explicitly accepted the complete redesign on 18 November 2026 with these binding requirements: dark teal + cyan Premium Glass direction; redesign every module, not just Orb/Home; preserve existing functionality, MainActivity/Launcher and the retained LIA source; keep Hindi/English; show real loading/success/error/permission states; do not create fake/demo controls or indicate the microphone is active when it is not. The current shared styling is only the common visual foundation, not the completed screen-by-screen redesign.


### Transfer capacity update — 22 November

The Android chunked file-transfer cap is now aligned with the relay's 100 MiB manifest limit (48 KiB chunks, 2,134 maximum chunks). The earlier Android-only 20 MB cap has been raised in the protocol helper, sender, receiver and boundary test. This is source-only; 100 MiB throughput, storage pressure, process-death resume, and low-storage behavior must be validated on physical devices before calling the larger limit production-ready.


The local background council now has a 1–5 specialist-count control, persists that choice into WorkManager, and exposes task cancellation. Per-provider cost/time budgets and durable per-stage evidence are still pending. Source changes and regression tests are not yet verified.


Visual Lens UI now uses the shared dark-teal/cyan glass components, the configured assistant name, and a process-aware service state (OFF/STARTING/ACTIVE) rather than a screen-local running flag. Service status is polled while the screen is open and refreshed on resume. This source change is not yet built or device-verified.


### Historical project-scope correction

The user's request to replace the phone's existing OS with Shraddha OS is a separate OS/ROM deliverable. Do not misrepresent this Android app branch or its APK as an operating-system replacement. It needs a device-specific ROM project and explicit recovery/backup safeguards.


The repository also contains the user's LIA visual reference at `reference/lia-app-recording/LIA-APP-REFERENCE.mov`. The current GitHub text-file connector cannot decode that binary video, so this pass does not claim to have watched or reproduced its exact frame sequence. Preserve the file and use its actual visual details when a video-capable inspection path is available.


### Assistant rename boundary

The pending branch updates the configured name in the main UI, About/Profile/Quick Actions/Visual Lens/phone-agent copy, voice agent prompts, overlay, keyboard, Firebase notifications and screen/camera foreground notifications. Android's app-drawer/launcher label remains a package-level static label; arbitrary per-user dynamic launcher labels require a user-pinned dynamic shortcut and cannot be silently rewritten by the app. Do not claim the launcher label itself is dynamically renamed.


The Privacy screen's old generic claims have been replaced with a source-accurate description of encrypted local memory, provider/backend data paths, Visual Lens, E2E device transfer and known limitations. A confirmation-gated **Stop all local live sessions** action now stops voice, live camera, remote camera, screen sharing and disconnects this phone's device relay. It deliberately does not claim to revoke paired-device records; permanent revocation remains in Device Center.

## Follow-up source work — 23 November 2026 (not yet verified)

- Voice session now routes transient AudioRecord failures through the existing bounded reconnect path. If Android microphone permission was revoked, it stops retrying and reports that permission must be restored.
- Voice screen now exposes one microphone ON/OFF action rather than separate mute/stop controls. Setup-incomplete states do not show an inert microphone action; the screen close action only dismisses the screen.
- OpenAI text chat now selects the currently documented gpt-5.5 model and sends max_completion_tokens rather than the deprecated max_tokens field. Provider account access, quota and live API compatibility still require later verification.
- These are source changes only. No manual build, unit test, emulator test or live provider call has been run during this feature-completion pass.
- API Keys screen now uses the shared layered-glass cards and raised aqua action buttons; provider key entry/reveal/remove behavior is retained.


Quick-share attachment payloads (not only clipboard) now use peer AES-GCM with category/request-bound AAD. The relay rejects plaintext `data_share_payload`, enforces the target's category permission and consumes the exact one-time grant. The Windows companion decrypts, verifies the bounded file and SHA-256, asks for local save approval and writes to a sanitized Downloads path. Android, relay and Windows regression tests have been added but not executed.

## 23 November 2026 — Source-only repair (not yet tested)

- Fixed the Kotlin parser blocker in `HomeScreen.kt` by escaping the reserved `in` package segment in the generated Android `R` import. The earlier CI log showed `Syntax error: Expecting qualified name` on that import; the reported unresolved `R` at the avatar reference was a follow-on compiler error. The avatar asset exists at `app/src/main/res/drawable-nodpi/shraddha_ai_avatar.jpg`.
- Corrected the Windows desktop-companion CI job to use `desktop-companion/shraddha-pc-companion` instead of the obsolete `pc_companion` path; the actual workflow and package entry point are now used for Python tests and EXE packaging.
- The integration workflow now explicitly includes the pending feature branch in its source-branch ancestry guard.
- No build, emulator test, instrumentation test or release verification has been run after these edits. Keep all three items marked pending until a consolidated run provides evidence.
- Corrected multi-agent stage prompt formatting: the parallel Planner/Researcher/Specialist/Critic/Verifier pipeline now sends actual line breaks between goal, role, responsibility and prior-stage evidence instead of literal \\n text. This is a source correction only; provider execution and regression tests remain pending.

## 23 November 2026 — Credential persistence hardening (source-only, not tested)

- Gemini API key save and removal now use synchronous SharedPreferences commits and fail explicitly if the encrypted value cannot be durably persisted. This prevents the settings UI from reporting a successful save/remove while the write is still only queued asynchronously.
- Voice Gateway encrypted configuration save and clear now use the same durable-commit check. A failed disk write is surfaced as an error rather than being silently treated as success.
- Android Keystore AES-GCM encryption and existing encrypted storage formats are unchanged; no plaintext credential storage was added.
- No tests/builds were run, in accordance with the current feature-completion instruction.
- Provider-specific OpenAI/Groq/Claude/Perplexity key removal now checks the encrypted-store commit result; the UI can no longer report a successful removal when the persisted record could not be deleted.
- Main Brain provider fallback now follows an explicit stable order (preferred provider first, then Gemini/OpenAI/Groq/Claude/Perplexity) rather than relying on Set iteration order. Verify these paths in the consolidated test/build pass, including key migration, save/remove, app restart and Keystore-loss behavior.

## 23 November 2026 — Voice activation and encrypted-state recovery hardening (source-only, not tested)

- Voice screen no longer auto-requests microphone permission or starts the microphone merely because the screen opens. A user must tap the permission action or the single microphone control; an already-granted permission does not cause an automatic session start on navigation.
- Microphone permission state is refreshed when the screen resumes. If Android revokes microphone permission during capture, the session stops reconnecting and the foreground service notification is removed instead of continuing to claim that the microphone is active.
- Gemini key migration now relies on the encrypted save's single synchronous commit. A decryption failure no longer deletes the stored ciphertext; it reports a recoverable error so transient Keystore failures or device restores cannot silently destroy the user's only saved credential copy.
- Main Brain scheduler restore now refuses to generate a replacement Keystore key when the original encryption key is missing. Existing encrypted task state is preserved and restore fails closed rather than overwriting the key and making the snapshot permanently unreadable.
- No build, tests, emulator or live provider/Firebase checks were run. Verify voice permission grant/revoke, explicit activation, service notification lifecycle, Gemini legacy migration, Keystore loss and scheduler restore in the consolidated verification pass.
