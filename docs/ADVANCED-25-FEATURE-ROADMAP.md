# Shraddha AI — Advanced 25 Feature Roadmap

This roadmap tracks the 25 features proposed on 20 November 2026. A reusable engine is not the same as a complete user-facing feature: UI wiring, persistence, permissions, provider/model configuration and real-device verification are tracked separately.

## Status definitions
- **Existing / partial**: related implementation exists, but end-to-end behavior or integration is incomplete.
- **Engine added**: a deterministic, tested foundation exists; UI, persistence, sensor/provider integration or transport remains.
- **Not complete**: no production-ready end-to-end feature is claimed.

## 25 features

| # | Feature | Current implementation | Remaining integration |
|---:|---|---|---|
| 1 | Multi-Agent Intelligence | Dependency-aware Android council engine now runs Planner + Researcher concurrently, fans out 1–5 independent Specialists across configured providers, shares their evidence with Critic and Verifier, and is wired into durable WorkManager background council jobs. | Android Advanced AI Lab now exposes a local background-council action with provider selection, a 1–5 specialist-count control, cancellation and WorkManager progress/result observation. Specialist/provider budgets, per-provider cost/time budgets, persistent per-stage evidence and cross-device cancellation/recovery remain. This path is source-added but not yet CI-verified. |
| 2 | Autonomous Task Planner | Backend task planning, WorkflowEngine lifecycle and new dependency-aware DAG planner. | Persisted plans, calendar/task inputs, planner UI, resumable execution wired to Operator. |
| 3 | Personal Knowledge Brain | KnowledgeGraph, LocalKnowledgeBase, encrypted memory and Firebase vault exist. | Unified ingestion/indexing, embedding retrieval, source provenance and cross-device sync UI. |
| 4 | Predictive Assistant | Opt-in suggestions from manually recorded repeated events added. | Persistent event source, user feedback loop and scheduled suggestion UI; no hidden tracking. |
| 5 | Self-Review Engine | Acceptance-check scoring and failed-check reporting added. | Attach checks to every generated artifact/workflow and block completion until required checks pass. |
| 6 | Natural Conversation | Gemini Live, language detection, echo guard and conversation memory exist. | Full multi-provider live audio parity, interruption/barge-in tests and real-device latency validation. |
| 7 | Wake Word Engine | Consent-gated wake phrase state machine added. | Licensed/on-device wake-word model, continuous audio front-end, battery/privacy tuning and false-accept testing. |
| 8 | Voice Profiles | Profile validation and existing custom voice gateway settings exist. | Persisted profile picker, licensed Hindi/English voice weights, deployment and output-quality tests. |
| 9 | Meeting Intelligence | Explicit transcript marker parser extracts decisions, owners/actions and questions. | Meeting recording consent, speech diarization/transcription, speaker corrections, calendar and export UI. |
| 10 | Smart Daily Briefing | Briefing composer groups urgent items, events and reminders. | Calendar/task/reminder connectors, scheduled delivery, personalization and UI. |
| 11 | Live Object Intelligence | Camera/screen vision request path exists through configured multimodal provider. | Live stream frame pipeline, object grounding/tracking, on-device fallback and latency testing. |
| 12 | Live Translation | Translation request validation/envelope added; provider text capability exists. | Streaming speech/camera translation, subtitle overlay, offline language packs and quality tests. |
| 13 | Visual Troubleshooting | Safety-first network, battery and electrical troubleshooting playbooks added. | Camera-guided step validation, device-specific trusted manuals and escalation UI. |
| 14 | 3D Environment Mapping | Validated 3D landmark/coordinate data model added. | Camera/ARCore or depth sensor capture, SLAM, persistence, renderer and device calibration. |
| 15 | Cross-Device Clipboard | Clipboard payloads use the mutually fingerprint-confirmed ECDH/AES-GCM peer-message path, one-time request grants, category opt-in, expiry checks and SHA-256 integrity checks; receiver confirmation is required before copying. 20,000-character cap. | Durable device keys, key rotation, replay-resistant request IDs and physical multi-device tests remain. |
| 16 | Universal File Transfer | SAF-based sender/receiver workflow supports files up to 100 MiB, one-time receiver approval, 48 KB encrypted E2E chunks, SHA-256 verification, receiver-selected save destination, pause/resume, missing-chunk requests and cancellation. | Increase tested size only after device validation; durable relay-side transfer state, robust process-death resume, quotas, malware scanning and interruption/device-matrix tests remain. |
| 17 | App Workflow Automation | Accessibility Operator, app learning profiles, adaptive workflow runner and checkpoints exist. | Voice-created training, robust OCR fallback for canvas controls, broader app recovery and device matrix. |
| 18 | Remote Desktop Workspace | Paired-device control and consent-gated screen streaming exist. | Full remote desktop session UX, keyboard/mouse parity, multi-monitor, file transfer and security review. |
| 19 | Smart Notification Manager | Opt-in Android NotificationListenerService captures metadata only (no title/body), stores a bounded 100-event history encrypted at rest with Android Keystore AES-GCM, exposes access/consent controls and clear-history action, and applies quiet-hour policy. If encryption/decryption fails, new history is not written in plaintext. | Rich per-app rule editor, encrypted history, retention/deletion UX and Android version/device matrix. |
| 20 | Offline AI Mode | Android LiteRT-LM and Windows GGUF runtime paths exist. | Ship/import compatible model weights, device-memory preflight, acceleration matrix and offline task coverage. |
| 21 | Life Timeline | Explicit user-added searchable timeline model added. | Encrypted persistence, import/export, timeline UI, retention controls and delete propagation. |
| 22 | Context Recall | ConversationMemory, knowledge search and graph primitives exist. | Unified time-aware retrieval, user corrections, source citations and privacy controls. |
| 23 | Personal Skill Learning | Explicit-consent approved-example store added. | Encrypted persistence, workflow adaptation integration, per-skill reset/export and regression evaluation. |
| 24 | Smart Routine Automation | Reminder scheduling, workflow lifecycle and approval-gated routine proposal added. | Trigger sources, recurrence engine, durable scheduling, execution verification and routine UI. |
| 25 | Personal Knowledge Vault | SecureMemoryVault, encrypted backup codec and FirebaseCloudVault exist. | Unified vault UI, conflict resolution, restore drills, retention/export controls and complete device-level recovery tests. |

## User-facing workspace integration (20 November 2026)

`MainActivity` now exposes controls for meeting transcript extraction, dependency-aware task planning and self-review, daily briefing composition, encrypted Life Timeline add/search/delete, consent-based Personal Skill Learning examples, approval-gated routine proposals, wake-phrase consent/state, configured-provider text translation and safety-first troubleshooting playbooks. These controls are usable local workflows, but they do not replace the external integrations listed in the table. The wake-phrase ARM/DEACTIVATE controls now operate on the same active gate instance; the UI explicitly states that no acoustic wake-word model or background microphone detection is running.

## New code in this implementation pass

- `AdvancedPersonalAssistantSuite.kt`: task DAG planning, self-review, meeting transcript marker extraction, daily briefing composition, consent-gated wake phrase lifecycle, voice profile validation, translation request validation, safety-first troubleshooting playbooks, 3D landmark validation, AES-GCM clipboard packets, file transfer manifests/chunk verification, notification quiet-hour policy, searchable life timeline, opt-in skill examples and approval-gated routine proposals.
- `AdvancedPersonalAssistantSuiteTest.kt`: JVM tests for these foundations, including dependency cycles, approval/consent, encryption expiry, chunk integrity, quiet hours and deletion.

## File-transfer implementation update (20 November 2026)\n\nThe small-file relay now requires a SHA-256 digest and exact byte count, validates canonical Base64 and the digest before consuming the recipient's one-time grant, and forwards the digest to the receiver. Android rejects mismatched size/digest and writes through a temporary file followed by an atomic rename. Files are capped at 650 KB. The relay currently forwards plaintext payload bytes; do not describe this as end-to-end encrypted.\n\n## Safety and capability boundaries

- Sensitive actions (messages, payments, deletion, device control and file writes) must retain explicit confirmation and an auditable result.
- Wake phrase state logic is not a wake-word acoustic model. No always-listening wake-word accuracy is claimed until an actual model is integrated and tested.
- Translation request preparation is not a translation engine. Camera/voice streaming translation still requires a provider or offline model.
- 3D landmark records are not SLAM or a live 3D scanner.
- Clipboard text now travels through the authenticated TLS relay after explicit sender/receiver consent and integrity checks, but the relay can read it; AES-GCM end-to-end key agreement is not yet implemented. File relay now verifies integrity and uses a one-time approval grant, but the relay sees the payload: this is not end-to-end encryption. Resumable chunks, malware scanning and end-to-end device tests remain required.
- Meeting marker extraction is not automatic meeting recording, diarization or speech transcription.
- Feature status must not be promoted to complete until UI integration, persistence, permission handling, tests and supported-device validation pass.

## Notification history security update (20 November 2026)

Notification metadata history now uses the existing Android Keystore-backed `EncryptedFeatureStore` (AES-GCM) rather than plaintext SharedPreferences. The listener keeps the 100-event bound, refuses plaintext fallback if key access or encryption fails, and purges the legacy plaintext history key before attempting a new encrypted write. The workspace count reads the encrypted record and the clear-history action deletes both encrypted and legacy keys. An Android instrumentation test verifies ciphertext-at-rest (the package name is absent from the raw stored value), successful decryption and deletion. Device/OS matrix testing is still required.


## Shared integration orchestration (2026-11-20)

A shared Android-domain orchestration boundary now exists in
`ShraddhaIntegrationHub.kt`. It connects dependency-aware task planning
(`AutonomousTaskPlanner`) to the workflow lifecycle (`WorkflowEngine`) and
the final acceptance gate (`SelfReviewEngine`). It records an append-only
in-memory event sequence for plan creation, per-step approval/decline,
dispatch, independent verification and final review. Sensitive and irreversible
steps wait for explicit approval; a declined step pauses without dispatch;
failed verification stops the remaining plan; a failed final review downgrades
a nominally completed workflow.

JVM tests cover the complete lifecycle, approval denial, verification failure,
and final-review rejection. This is the shared orchestration contract, not a
claim that every platform adapter is already connected: Voice/AI provider
routing, People Memory camera capture, Android Operator execution, Firebase
sync, Windows companion transport, Visual Lens streaming/translation, and
OS-level permission screens still require their own live adapters and device
tests. The hub itself performs no external action and cannot bypass platform
permissions. Its audit list is currently in-memory; durable encrypted audit
persistence is a separate integration task.


### Personal Memory ↔ Knowledge Vault ↔ Firebase adapter

`UnifiedMemoryRepository.kt` now provides a local-first repository boundary:
- Per-record local memory is encrypted with the Android Keystore-backed
  `EncryptedFeatureStore` and namespaced by a validated record key.
- Firebase sync/restore is performed only when the caller explicitly passes
  cloud consent and supplies valid account credentials; no background sync is
  started by this repository.
- Local save happens before cloud upload. A cloud failure is surfaced while
  preserving the local encrypted copy.
- Cloud restore writes the returned, authenticated/decrypted value back to the
  matching local encrypted record.
- The policy validator rejects path-like keys, empty values and oversized data.

JVM policy tests cover explicit cloud consent and record bounds. This adapter
does not yet automatically synchronize the live ConversationMemory,
KnowledgeGraph or People Memory stores; those sources need explicit export/
import mapping and UI consent before they can share records.


### Voice AI ↔ AI Brain ↔ Multi-Agent Council adapter

The live Gemini voice tool catalog now exposes `plan_multi_agent_task`.
`VoiceSessionManager` handles this tool directly and routes the spoken goal
through `AgentCouncil`, then converts the council roles into a dependency-ordered
`ShraddhaIntegrationHub` plan. The resulting roles, ordered tasks, guardrails
and explicit `execution=not_started` status are returned to the live voice
session and saved in the Android Keystore-backed encrypted feature store.

This is a real voice-to-local-planner integration. It deliberately does not
claim to contact five independent remote AI agents or execute phone actions;
those require separately configured model workers and permission-aware
platform executors.


### Shared Security / Permission / Audit integration update (2026-11-20)

- `LocalAuditTrail` now stores bounded metadata in the Android Keystore-backed
  AES-GCM feature store. It purges the previous plaintext audit preference
  before writing and has no plaintext fallback. Export decrypts on-device;
  clear removes the encrypted record and legacy key.
- The live OperatorActivity wires OperatorEngine outcomes to this encrypted
  audit trail. The adaptive learned-workflow runner already uses the same
  LocalAuditTrail class, so both operator paths now share the same storage.
- MainActivity records biometric capture start/end, remote camera share
  lifecycle, Visual Lens request/result, and translation request/result.
  Content, prompts, raw frames, typed text and biometric samples are not logged.
- Remote camera service startup and every frame dispatch now re-check Android
  CAMERA permission, explicit camera-sharing consent, target-device ID shape,
  and front/back facing. Relay send failures are audited without including
  frame content. A policy unit test covers consent, permission and invalid targets.
- Instrumentation tests verify audit data is encrypted at rest, legacy plaintext
  is purged, export works, and clear removes the encrypted record.

Remaining integration work: durable workflow-hub state across process death;
full task planner dispatch through OperatorEngine and learned workflows;
end-to-end encrypted phone/Windows command channel and device test matrix;
live streaming translation with timestamped partial segments; and Android/Firebase/
backend/Windows integration tests on real devices and a Windows host. Face and
speaker capture already have user-started on-device sample capture, model
inference, consent-gated encrypted templates and match/identify flows, but
threshold calibration and anti-spoof certification/device accuracy testing
remain release blockers.


## Parallel council implementation update (22 November 2026)

The pending-features branch now includes a dependency-aware parallel council execution path:
- Planner and Researcher execute concurrently.
- One to five independent Specialist workers execute concurrently after the shared foundation outputs are available.
- Critic compares the independent solutions; Verifier performs the final acceptance review.
- Provider assignment is distributed across one to five configured providers; background task payloads accept a comma-separated provider set.
- Durable WorkManager background council jobs now call this parallel path; progress is reported by stage and cancellation is checked between stages.
- The original sequential API remains available for compatibility.

**Not yet verified:** this change has not been built or tested, by instruction. UI for selecting specialist count, provider spend/time budgets, durable per-stage evidence, and process-death recovery of in-flight provider calls remain to be completed. The current UI selects providers but uses a fixed three-specialist configuration. A provider call already in flight cannot be forcibly interrupted by the current synchronous gateway client.


## Encrypted visual relay correction — 22 November 2026

A source review found that Android's `ScreenShareService` and `RemoteCameraService` called `DeviceLinkManager.sendFrame` with raw JPEG bytes while the backend relay had already been hardened to accept only the encrypted `0x81/0x82` frame envelope. The old Android sender/receiver therefore did not match the current relay protocol.

The pending branch now adds:
- a strict visual frame envelope codec matching the relay's source-ID/type/sequence/nonce/ciphertext format;
- AES-256-GCM encryption using the mutually fingerprint-confirmed peer key and AAD bound to session, sender, receiver, frame type and sequence;
- receiver authentication, JPEG validation and per-peer/frame-type replay rejection;
- a unit-test case for ECDH key agreement, relay envelope reconstruction, successful decryption and AAD tamper rejection.

This is a source-level protocol correction only. It has not been built, unit-tested or exercised between physical devices yet. Camera/screen streaming must remain labelled unverified until the consolidated test and real-device pass.


### File-transfer chunk-size defect found and corrected — 22 November

A source-only cross-layer review found that the Android sender used 48,000-byte chunks and an incorrect ceiling formula, while the relay requires exactly 48 KiB (49,152-byte) chunks. Exact 48,000-byte multiples could therefore produce an empty trailing chunk, and the relay could reject the sender's manifest. The pending branch now centralizes the Android transfer limit/chunk size in `DeviceFileTransferProtocol` and uses the relay's 48 KiB contract in manifest calculation, sender offsets, chunk reads, receiver offsets and final-chunk validation. Boundary regression tests were added for exact multiples, final chunks and the 100 MiB cap. These tests have not been run.


### Transfer capacity update — 22 November

The Android chunked file-transfer cap is now aligned with the relay's 100 MiB manifest limit (48 KiB chunks, 2,134 maximum chunks). The earlier Android-only 20 MB cap has been raised in the protocol helper, sender, receiver and boundary test. This is source-only; 100 MiB throughput, storage pressure, process-death resume, and low-storage behavior must be validated on physical devices before calling the larger limit production-ready.


### Configurable specialist fan-out — 22 November

The local background council now persists the user's selected 1–5 Specialist count through the queue payload and Worker into the parallel orchestration engine. The Advanced AI Lab validates the range before enqueueing; the task summary reports the selected fan-out. No tests have been run.


The Advanced AI Lab now restores the latest WorkManager council ID after Activity recreation and reattaches its progress/result observer. A cancel control can cancel the restored task as well. This survives screen/activity recreation; it does not claim that in-flight provider reasoning resumes mid-stage after process death.
