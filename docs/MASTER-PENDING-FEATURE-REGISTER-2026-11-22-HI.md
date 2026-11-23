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

## Provider compatibility audit (23 November 2026)

- Operator console's event/recovery log now has its own scrollable, bordered teal-glass viewport inside the full-page scroll layout, preventing long audit messages from pushing controls off-screen.


- One Android CI push run had already started before the manual-only trigger change and exposed compiler errors in credential decrypt return expressions, scheduler snapshot return expressions, Device Link save result handling, About email copy callback, Agent Council numeric slider typing, Quick Actions tile delimiter and Mobile Command Center assistant-name scope. Source fixes for each diagnostic are committed; no new CI run was started to verify them.


- Android CI no longer runs on every feature-branch push; the complete unit-test/build/emulator smoke workflow is now `workflow_dispatch` only, so source-audit commits do not trigger repeated test runs. The owner can start one consolidated verification when implementation review is complete.


- Operator recovery now includes both process-interrupted and runtime-FAILED workflows whenever the encrypted action journal is EXECUTING/FAILED; the user can inspect the target app and explicitly attest completion before only the remaining steps are queued. Failed steps are never auto-replayed.


- Operator restart-recovery console now distinguishes an unreadable encrypted action journal and an unavailable Main Brain scheduler vault from an empty recovery list; it refuses to offer resume/replay when the recovery evidence cannot be opened.


- Task Planner's create/edit dialogs and daily-briefing/research switches now use matching inset teal fields and cyan/teal switch colors, completing the native module's primary form controls.


- In-call controls now use the configured assistant name and dark-teal/cyan raised control styling, with a distinct red decline/end button; the honest notice that normal SIM-call audio is not captured remains visible.


- Task Planner cards and summary panels now use layered teal glass surfaces, cyan section accents and raised cyan controls; its reminder scheduling and opt-in daily research behavior are unchanged.


- Device Transfer native workspace now uses the dark-teal glass canvas, inset paired-device ID field, cyan progress indicator and raised cyan transfer controls; SHA-256/receiver-approval behavior is unchanged.


- Form Autofill and private Notes/OCR native screens now use the configured assistant name, teal glass fields/cards, cyan action controls and consistent dark canvas. Notes/OCR editor now has a density-aware fixed viewport inside its ScrollView (previous zero-height weighted child could collapse).


- File Access and App Training native activities now use the configured assistant name, dark-teal backgrounds, inset glass fields/spinners and raised cyan controls instead of the old purple/default Android widgets.


- Work Agent's chat/browser surface now uses the configured assistant name in its header, message bubbles and browser accessibility description; its controls and text fields use the same raised-cyan/inset-teal glass treatment as the other native work screens.


- Phone Agent native screen now follows the configured assistant name and shared dark-teal/cyan visual language, with raised rounded action controls and inset glass call-history panels.


- Mobile Command Center native screen now uses the configured assistant name, a dark teal canvas, inset teal input/clipboard fields and raised cyan action buttons; its existing full-page ScrollView keeps the long command list reachable on small phones.


- Operator console now scrolls as one full page on small phones rather than hiding controls below a fixed-height log area; the log viewport uses density-independent sizing. The screen has not yet been device-checked for every display size.


- Native Operator console now follows the dark-teal/cyan premium direction: inset teal text fields, rounded raised cyan action buttons, cyan secondary text and a dark navy-teal canvas replace the old purple controls. The native activity has not been visually verified on a device.


- Settings refreshes the voice action journal on screen resume, so a manual verification completed in the Operator console is reflected without requiring an app restart.


- Operator console's header now uses the configured assistant name rather than a fixed SHRADDHA product label.


- Operator console's Voice Action Journal / Restart Recovery view now reports encrypted-journal read failures distinctly (instead of claiming no record exists) and allows manual attestation for both EXECUTING and FAILED entries after the user checks the target app.


- Settings now provides an explicit, confirmation-gated recovery panel for unresolved voice actions (EXECUTING/FAILED). The user must inspect the target app and attest manually; this only records MANUALLY_VERIFIED and never replays the call/message/action. Encrypted journal read errors are shown separately.


- Voice action journal now treats both EXECUTING and FAILED as unresolved and blocks a new action from overwriting either. The user can explicitly mark either state as manually verified after checking the target app; only then can a new voice action replace the journal entry.


- Voice connection startup now uses a distinct cyan-blue edge glow; violet remains for listening/processing, pink for speaking and red for errors, so connection is not visually represented as active listening.


- Gemini, Voice Gateway and Device Link credential writers now also zero their AES-GCM IV arrays after the encoded IV is durably persisted, completing cleanup of their short-lived encryption buffers.


- Main Brain scheduler initialization and its persistence-fault check now run under one synchronized critical section, closing a race where a concurrent caller could obtain the scheduler while another thread was latching a storage failure.


- Shared NovaTextField now exposes its placeholder/field purpose as an accessibility content description and identifies secret fields for screen readers, improving keyboard and settings form navigation.


- Accessibility settings title now reads the configured assistant name instead of a fixed Shraddha AI label.


- Legacy hardcoded-purple Accessibility and Access Key components were moved onto the shared teal/cyan layered-glass theme, including the blocked/error state color and common elevation/rim treatment.


- The user-configured assistant name now propagates into Gemini Live function declarations/tool descriptions (including council planning and secure file manager) rather than retaining hardcoded Shraddha AI branding in the model-facing tool catalog.


- Voice screen's single mic control now distinguishes CONNECTING and ERROR from an actually ready microphone session; it no longer labels the microphone ON while the secure token/WebSocket connection is still being established or has failed.


- Voice foreground notification no longer claims the microphone is already in use during token acquisition/connection setup; it reports a running voice session and retains the explicit stop action.


- EncryptedFeatureStore batch writes now wipe each intermediate AES-GCM ciphertext buffer in `finally` blocks, including failures during Base64 encoding or editor updates.


- Gemini API-key storage now distinguishes a genuinely absent encrypted record (eligible for one-time legacy plaintext migration) from a partial ciphertext/IV record; incomplete encrypted records raise a recovery error and are never treated as an empty key or overwritten automatically.


- Firebase password-protected cloud backup encryption/decryption now wipes UTF-8 plaintext, salt, IV, decoded ciphertext and intermediate AES-GCM buffers in `finally` blocks; PBKDF2 password-char/spec cleanup remains in place.


- MainBrainSchedulerRuntime now latches a process-local persistence fault after any failed durable snapshot write and refuses subsequent task execution in that process until restart/recovery. This prevents later callers from continuing to use a scheduler whose in-memory state may no longer match its encrypted durable snapshot.


- Background WorkManager and Main Brain foreground task claim paths now catch durable scheduler persistence failures and return safe error results before running the side effect; an in-memory state transition is not treated as a successful durable claim.


- Main Brain scheduler snapshots now wipe plaintext JSON, decoded ciphertext, IV and intermediate AES-GCM buffers on save/load/decrypt paths, while retaining the encrypted snapshot if Keystore authentication fails.


- Main Brain scheduler restoration errors are now converted into safe user/worker failure results at voice tool, Website Forge, form extraction, learned-workflow and WorkManager entry points; a missing scheduler Keystore key no longer bubbles out as an uncaught initialization exception or causes an empty replacement queue to be saved.


- ConversationMemory no longer deletes encrypted voice history when decryption/Keystore access fails, and it refuses to generate a replacement key while history ciphertext exists. Persist/disable operations use synchronous commits, decoded buffers are wiped, and Settings reports persistence errors and rolls the toggle back when opt-in cannot be saved.


- Telephony gateway settings now read through an explicit Config result with a visible decryption/recovery error, save URL+token atomically in one encrypted-store transaction, and clear both keys atomically. Phone Call Agent initializes its settings message from the read result rather than silently showing blank credentials after a vault error.


- Extended Keystore-loss fail-closed behavior to SecureMemoryVault, EncryptedFeatureStore and MainBrainSchedulerStore: existing encrypted records prevent automatic key regeneration on subsequent writes; decrypt paths require the original key and clear decoded buffers. OperatorActionJournal and WorkflowCheckpointStore now expose read failures; an unreadable action journal requires review, and AdaptiveWorkflowRunner stops before any new workflow side effect when its encrypted checkpoint cannot be read or cleared.


- EncryptedFeatureStore now zeroes decoded IV/ciphertext buffers in a `finally` block even when Keystore lookup, AES-GCM authentication or decryption fails; plaintext continues to be wiped after conversion. This is source hardening, not a substitute for device-side memory/security testing.


- Voice action write-ahead journal now propagates encrypted-store read/parse errors internally; starting a new call/message/download action is denied when the prior journal cannot be read, and finish/manual-verify operations fail closed. This prevents a storage/Keystore error from overwriting an unresolved in-flight action.


- Device pairing credentials now decrypt only with the existing Android Keystore key. Missing/invalid keys or ciphertext return a visible recovery error without clearing stored ciphertext; explicit pairing clear persists preference deletion before deleting the now-unused Keystore alias. Restore UI reports the recovery issue rather than silently claiming no pairing exists.


- Screen sharing now validates a paired-device ID before starting MediaProjection foreground service, caps outgoing JPEG frame size, and zeroes both the transmitted frame copy and reusable in-memory compression buffer after dispatch. The Android media-projection consent dialog remains mandatory; no physical-device test has been run.


- Voice entry points now enforce RECORD_AUDIO permission inside `VoiceSessionManager.start()` as well as in the foreground-service boundary, so future/direct callers cannot bypass the runtime permission check.
- Live Eyes now checks CAMERA permission before calling `startForeground()` for its camera service. This avoids Android 14+ foreground-service startup failures when camera permission has been revoked and records OFF without briefly starting camera capture.
- Capability system prompt now uses the configured assistant name and explicitly distinguishes supported tool behavior from unsupported WhatsApp controls, draft-vs-publish, Website Forge preview-vs-deployment and local planning-vs-external-agent execution. Added a regression test for those honesty constraints.


- Windows PC Companion now rejects replayed E2E remote-action IDs per peer within a live connection using a bounded replay cache; added regression-test source for duplicate IDs, peer scoping, malformed IDs and cache bounds. Physical PC/phone integration remains untested.


- Firebase deploy-source drift corrected: `firebase/firestore.rules` and `firebase/storage.rules` now match the stricter owner-scoped root rules. Storage is limited to encrypted backup objects (no generic user file uploads); Firestore deletion is restricted to the app's explicit backup keys. Emulator test source now covers restricted vault deletion and denial of non-backup Storage uploads. These tests have not been run.


- Gemini API-key reads now require the existing Android Keystore key instead of creating a replacement when ciphertext is already present. This closes the same key-loss overwrite edge case for the Gemini key vault.


- Voice Gateway decryption now requires the original Android Keystore key; a missing key no longer triggers silent key regeneration over still-present ciphertext. Saving new settings can create a key, while reading existing encrypted settings fails closed and preserves the saved bytes.


- Live Eyes camera service now rejects null/unknown service intents and only starts on the explicit `ACTION_START`; an unreadable encrypted gateway configuration is shown as a recovery error in both the service notification and Visual Lens screen.


- Firebase restore now validates the backup format and non-empty ciphertext for Firestore, Realtime Database mirror, named recovery revisions and Storage metadata/object consistency; malformed records fail closed instead of being presented as a missing backup. Source changes are not yet verified against a live Firebase project.


- Corrected the Claude Messages adapter model identifier from the obsolete `claude-sonnet-5` to the currently documented `claude-sonnet-5-5`. This is a source-level compatibility correction only; a live Claude API request still requires the user's own key and provider-side verification.
- OpenAI `gpt-5.5` and Perplexity `POST /v1/agent` identifiers/endpoints were checked against current provider documentation. Provider model availability can still depend on the user's account and rate limits.

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
| Multi-agent council | Parallel Planner/Researcher, 1–5 Specialists, Critic and Verifier are routed through Main Brain and WorkManager. 23 Nov source pass adds the Multi-Agent Workspace route from Home/Quick Actions. | Source now includes provider-call/time limits, per-provider call caps, encrypted per-stage evidence, cancellation/retry status and provider usage accounting. Exact monetary spend estimation and runtime verification remain; provider-matched checkpoint resume is now implemented in source but not yet executed in tests. |
| Autonomous project manager | Task planner, workflow lifecycle and project tracker exist. | Persisted dependency-aware execution, real task adapters, resumable steps, progress, checkpoints, rollback and explicit approvals. |
| Self-review / verification engine | Acceptance-check scoring and reporting exist. | Make verification mandatory for every generated artifact and workflow; block completion on failed required checks. |
| Deep research with citations | Backend/provider research paths exist. | Real source retrieval, URL validation, evidence provenance, citation checks and no unsupported “researched” claims. |
| Personal knowledge brain / graph | Encrypted local graph and memory repository exist. | Unified ingestion from user-approved sources, source provenance, embeddings/RAG, cross-device sync, conflict handling and deletion propagation. |
| Predictive assistant / digital twin | Opt-in repeated-event suggestions and editable memory primitives exist. | Persistent signal source, feedback loop, schedule/quiet hours, explainability and strict no-covert-monitoring controls. |
| Meeting intelligence | Transcript marker extraction exists. | Consent-based audio/transcription, diarization, correction UI, calendar and export integration. |
| Voice-created workflows | Existing app training and workflow router exist. | Voice authoring, safe review, app-specific recovery and reusable workflow editing. |
| Background task runner | WorkManager queue and scheduler exist. 23 Nov source pass adds encrypted per-stage evidence, attempt history, cancellation status, retry-pending status, per-provider call counts and elapsed time. | Process-death/provider-timeout recovery verification, task-history UX and exact monetary spend estimation remain. |

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
- Cancellation now records a durable CANCELLED status; transient failures can be marked RETRY_PENDING and partial evidence is retained. Retries now reuse only completed encrypted stages whose recorded provider still matches the deterministic provider assignment; missing/mismatched stages execute again. Checkpoint reads and initial status writes fail closed rather than leaving a claimed task stuck RUNNING.
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


The local background council now has a 1–5 specialist-count control, persists that choice into WorkManager, exposes task cancellation, enforces provider-call/time budgets, stores encrypted per-stage evidence and resumes provider-matched completed stages after safe retries. Exact monetary cost estimation and runtime verification remain pending.


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

- Background council retries now restore completed encrypted Planner/Researcher/Specialist/Critic/Verifier outputs only when the saved provider matches the current deterministic assignment; incomplete or mismatched stages are re-executed. Added a regression test for stage reuse and provider mismatch. Checkpoint-read or initial status-persistence failures now fail the scheduler claim closed instead of leaving it RUNNING. Tests/build remain intentionally unrun.


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
- Gemini API key input is now bounded to 512 characters, matching the provider-key store limit and rejecting oversized values before encryption.
- Non-Gemini provider-key reads now propagate encrypted-store/Keystore failures instead of silently presenting them as an absent key; the API Keys screen catches the failure and shows a recovery warning without crashing.
- No build, tests, emulator or live provider/Firebase checks were run. Verify voice permission grant/revoke, explicit activation, service notification lifecycle, Gemini legacy migration, Keystore loss and scheduler restore in the consolidated verification pass.


## 23 November 2026 — Device Link transport security (source-only, not tested)

- Device Link AI chat now reuses the pairing relay URL validator and rejects non-HTTPS URLs before sending a request. This prevents an incorrectly configured HTTP endpoint from receiving the optional Bearer gateway token in cleartext.
- Applied the same HTTPS enforcement to provider-status, vision-analysis and authenticated POST endpoints (including summarize, task planning, studio, expert assistance, provider comparison and confirmed social publishing).
- Vision requests now reject unsupported provider IDs, blank prompts and empty image payloads before network dispatch. AI chat similarly validates provider selection and non-empty prompts.
- Pairing and WebSocket paths already required HTTPS/WSS; these additional checks close the separate HTTP-capable API paths.
- Source-only change. No unit test, build, emulator test or live relay/API request has been run yet.


## 23 November 2026 — Background council retry recovery (source-only, not tested)

- Fixed a retry-state deadlock in the WorkManager background council adapter. Previously, a retryable provider/network failure saved RETRY_PENDING evidence but left the shared scheduler task in RUNNING; the next WorkManager attempt could not claim it and would retry without executing the council.
- Retryable failures now transition the scheduler claim into the explicit interrupted/retry-safe FAILED marker. The next worker invocation requeues only the typed `background_council` task to PENDING before claiming it. Device-action task types are not eligible for this retry path.
- Added a scheduler regression test covering RUNNING → retry-safe FAILED → PENDING → re-claim.
- This is source/test coverage only. The test, Android build, WorkManager process-death behavior and provider timeout/429/5xx retry behavior have not been executed yet.


## 23 November 2026 — Assistant-name fallback audit (source-only, not tested)

- The reusable 3D orb components contained a stale hardcoded default name ("Lia"). Callsites that did not explicitly pass a name could therefore show the old name after the user renamed the assistant.
- Both `LiaOrb3D` and `NovaOrb` now default to `AssistantBrand.NAME`, so fallback semantics use the configured Shraddha identity instead of a separate legacy literal.
- The Windows companion still contains fixed product-brand text in its standalone window titles, local prompt strings and README. Synchronizing a user-defined Android display name into that separately installed companion requires an explicit, authenticated name-setting protocol; this remains open rather than being hidden by a static rename.
- No tests, APK build or device/PC pairing test has been run.


- Provider discovery no longer converts encrypted API-key read/Keystore failures into a silent "provider not configured" result. Missing keys remain unconfigured; vault read failures now propagate to the caller so the user can be shown a recovery error instead of silently losing a provider from the routing list. Source-only; error UX and regression behavior still need verification.

- The council entry points now convert provider-vault read failures into an explicit failed pipeline result, rather than allowing the newly surfaced Keystore exception to escape before the worker can persist failure status. This preserves the distinction between "no provider key configured" and "encrypted credentials could not be read". Source-only; worker/UI error propagation remains unverified.

## 23 November 2026 — Voice Gateway encrypted-state recovery (source-only, not tested)

- Voice Gateway settings previously converted every decryption/Keystore failure into an empty configuration. This incorrectly looked like the user had never configured the backend and hid the actual recovery problem.
- The settings reader now returns an explicit recovery error while preserving the encrypted ciphertext. A missing IV for an existing encrypted payload is also reported as incomplete encrypted state.
- The speech client and Gemini Live token path now stop with that explicit error rather than attempting a request with silently blank credentials.
- Settings opens the gateway dialog with the recovery warning visible. Clearing the encrypted settings is now guarded so a failed durable clear is reported instead of crashing or closing the dialog as if it succeeded.
- No tests/build/emulator/live gateway calls were run. Verify normal save/read/restart, corrupted ciphertext, missing Keystore key, partial preferences, explicit clear and speech-client error propagation in the single consolidated verification pass.

- Agent Council provider discovery previously caught encrypted-key/Keystore read exceptions and replaced them with an empty provider list. The screen now displays a specific recovery message and directs the user to API Keys; a genuinely absent key remains a normal unconfigured state. Source-only; UI and vault failure cases remain pending consolidated verification.

- Background council worker now catches execution-evidence persistence exceptions in the final result/retry handling path, fails the claimed scheduler task closed, attempts to record a FAILED status, and returns a WorkManager failure instead of leaving the task stuck RUNNING. If evidence storage itself is unavailable, the scheduler still receives a best-effort terminal failure transition. This failure-injection path has not been tested.

- Firebase cloud restore no longer swallows Firestore/Realtime Database permission or network failures and reports them as "backup not found". Firestore remains authoritative: RTDB fallback is attempted only when the Firestore document is genuinely absent. An existing Firestore record with a missing/blank payload is reported as malformed instead of silently returning an empty backup. Live Firebase auth/rules/network behavior remains unverified until credentials and a configured project are available.
- Background council final/retry handling now catches evidence-store exceptions and best-effort transitions the claimed scheduler task to FAILED, preventing a persistence exception from leaving the shared scheduler permanently RUNNING. No failure-injection test or Android build has been run.


## 23 November 2026 — Additional source audit repairs (not yet tested)

- Live Visual Translation now wipes its copied camera frame and releases its single-flight gate on both synchronous gateway exceptions and asynchronous callback completion, including when a UI/audit callback throws. Previously, an exception before callback registration or inside the callback could leave the translation gate permanently in-flight and retain a copied frame.
- Project Lab plan saves now atomically write the new plan/index while removing encrypted records evicted by the 20-plan retention cap. Plan deletion now atomically updates the index and removes the encrypted plan in the same EncryptedFeatureStore transaction; previously the delete result was ignored and failed removal could leave an orphaned record.
- These are source-level fixes only. Unit tests, Android build, emulator, Firebase and physical camera/translation tests remain unrun.


- Project Lab validation now rejects duplicate, missing, self-referential and cyclic milestone dependencies. Unknown persisted milestone states now fail as malformed/newer data instead of silently resetting progress to TODO. No tests/build have been run.

- Offline LiteRT-LM system prompt now reads the assistant identity from the shared `AssistantBrand.NAME` source instead of embedding a second hardcoded "Shraddha AI" label. This prevents the local-model prompt from drifting away from the app's central branding source. This is source-only; compile and runtime prompt verification remain pending.
