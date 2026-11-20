# Shraddha AI — Advanced 25 Feature Roadmap

This roadmap tracks the 25 features proposed on 20 November 2026. A reusable engine is not the same as a complete user-facing feature: UI wiring, persistence, permissions, provider/model configuration and real-device verification are tracked separately.

## Status definitions
- **Existing / partial**: related implementation exists, but end-to-end behavior or integration is incomplete.
- **Engine added**: a deterministic, tested foundation exists; UI, persistence, sensor/provider integration or transport remains.
- **Not complete**: no production-ready end-to-end feature is claimed.

## 25 features

| # | Feature | Current implementation | Remaining integration |
|---:|---|---|---|
| 1 | Multi-Agent Intelligence | AgentCouncil role planner and provider comparison/council endpoints exist. | Real parallel specialist execution, shared evidence state, critic/verifier loops, cost/time budgets and UI. |
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
| 15 | Cross-Device Clipboard | Paired relay transport is integrated for manually triggered text sharing. Clipboard is an explicitly approved share category; exact one-time request grants, category opt-in, local expiry checks, SHA-256 integrity checks and a second receiver confirmation before copying are enforced. 20,000-character cap. | End-to-end encryption/key agreement, key rotation and replay-resistant packet IDs remain incomplete; relay can see payload text. |
| 16 | Universal File Transfer | One-time peer-approved small-file relay binds the grant to the exact request ID, validates canonical Base64, exact byte length and SHA-256 at relay and receiver; receiver commits only verified files atomically. 650 KB cap. A bounded per-chunk envelope validator (48 KB chunks, transfer ID/index/count, SHA-256) and unit test are now added as groundwork. | Chunk relay/reassembly, resumable persistence, progress/cancel UI, end-to-end encryption, quotas and malware scanning remain incomplete; chunk validation alone is not a working chunk transport. |
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
