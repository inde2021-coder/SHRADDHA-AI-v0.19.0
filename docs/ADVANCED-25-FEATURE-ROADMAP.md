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
| 15 | Cross-Device Clipboard | AES-256-GCM expiring text packet added. | Authenticated relay transport, key agreement/rotation, consent, replay protection and device UI. |
| 16 | Universal File Transfer | Safe manifest, chunk validation and SHA-256 verification added. | Encrypted resumable transport, peer approval, storage quotas, progress UI and malware scanning. |
| 17 | App Workflow Automation | Accessibility Operator, app learning profiles, adaptive workflow runner and checkpoints exist. | Voice-created training, robust OCR fallback for canvas controls, broader app recovery and device matrix. |
| 18 | Remote Desktop Workspace | Paired-device control and consent-gated screen streaming exist. | Full remote desktop session UX, keyboard/mouse parity, multi-monitor, file transfer and security review. |
| 19 | Smart Notification Manager | Metadata-only quiet-hour/filter policy added. | Notification-listener permission UI, rules persistence, per-app controls and Android version tests. |
| 20 | Offline AI Mode | Android LiteRT-LM and Windows GGUF runtime paths exist. | Ship/import compatible model weights, device-memory preflight, acceleration matrix and offline task coverage. |
| 21 | Life Timeline | Explicit user-added searchable timeline model added. | Encrypted persistence, import/export, timeline UI, retention controls and delete propagation. |
| 22 | Context Recall | ConversationMemory, knowledge search and graph primitives exist. | Unified time-aware retrieval, user corrections, source citations and privacy controls. |
| 23 | Personal Skill Learning | Explicit-consent approved-example store added. | Encrypted persistence, workflow adaptation integration, per-skill reset/export and regression evaluation. |
| 24 | Smart Routine Automation | Reminder scheduling, workflow lifecycle and approval-gated routine proposal added. | Trigger sources, recurrence engine, durable scheduling, execution verification and routine UI. |
| 25 | Personal Knowledge Vault | SecureMemoryVault, encrypted backup codec and FirebaseCloudVault exist. | Unified vault UI, conflict resolution, restore drills, retention/export controls and complete device-level recovery tests. |

## New code in this implementation pass

- `AdvancedPersonalAssistantSuite.kt`: task DAG planning, self-review, meeting transcript marker extraction, daily briefing composition, consent-gated wake phrase lifecycle, voice profile validation, translation request validation, safety-first troubleshooting playbooks, 3D landmark validation, AES-GCM clipboard packets, file transfer manifests/chunk verification, notification quiet-hour policy, searchable life timeline, opt-in skill examples and approval-gated routine proposals.
- `AdvancedPersonalAssistantSuiteTest.kt`: JVM tests for these foundations, including dependency cycles, approval/consent, encryption expiry, chunk integrity, quiet hours and deletion.

## Safety and capability boundaries

- Sensitive actions (messages, payments, deletion, device control and file writes) must retain explicit confirmation and an auditable result.
- Wake phrase state logic is not a wake-word acoustic model. No always-listening wake-word accuracy is claimed until an actual model is integrated and tested.
- Translation request preparation is not a translation engine. Camera/voice streaming translation still requires a provider or offline model.
- 3D landmark records are not SLAM or a live 3D scanner.
- Clipboard encryption and file chunk validation are not a network transport. Pairing, authenticated relay, consent, replay protection and end-to-end device tests are still required.
- Meeting marker extraction is not automatic meeting recording, diarization or speech transcription.
- Feature status must not be promoted to complete until UI integration, persistence, permission handling, tests and supported-device validation pass.
