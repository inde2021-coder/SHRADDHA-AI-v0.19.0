# Shraddha AI vs STONIC — Verified Feature Gap Audit

Audit basis: current `optimization/apk-size-without-feature-loss` branch source, including Android, backend, and both Windows companion implementations. This is a source audit, not a claim that every feature has passed physical-device testing.

## Capabilities already present — do not duplicate

| Capability | Repository evidence | Actual limits |
|---|---|---|
| Continuous voice | `VoiceSessionManager`, `GeminiLiveClient`, `LiveAudioIO`, foreground voice service | User-started; microphone pauses on disconnect; OEM/battery reliability needs device testing |
| Encrypted memory | `ConversationMemory`, `PersistentChatMemory`, `SecureMemoryVault`, `UnifiedMemoryRepository` | Local persistence is opt-in; cloud sync is separately consent-gated |
| Android app automation | `LiaToolCatalog`, Accessibility Operator, trained workflows, OCR, checkpoint/verification | Requires Android permissions; consequential actions need explicit confirmation |
| Camera-as-eyes | `LiveEyesCameraService`, `VisualLensScreen` | Headless Camera2 foreground service, no preview surface, privacy indicator/notification, throttled visual analysis and voice context handoff; requires camera permission + HTTPS vision gateway and physical-device testing |
| PC control and vision | `desktop-companion/shraddha-pc-companion/` | Advanced companion supports approved-root read-only file list/read/search, active-window OCR, offline GGUF inference, code-assistant prompts, web search, clipboard, screen sharing and selected phone control; local and Android approvals remain required |
| Legacy PC companion | `pc_companion/` | Separate older implementation with a narrower action set; do not confuse it with the advanced desktop companion |
| Mobile web work surface | `WorkAgentActivity` | Embedded browser, page inspection, reviewed click/fill, downloads and App Operator hand-off; not unrestricted browser automation |
| Reminders / planning | `TaskPlannerActivity`, `ReminderReceiver`, `AutonomousTaskPlanner` | Local reminders and DAG planning exist; they were not a durable AI job runner before this branch's new queue |
| File transfer | `SecureFileTransfer`, advanced Windows companion transfer protocol | Chunk manifest/relay support exists, but complete Android file-picker UI, disk-backed receive/resume queue and end-to-end device verification remain incomplete |
| Provider routing | `GeminiTextClient`, `MultiProviderChatClient` | Supports configured providers; credentials must be added by the user |
| Creator / engineering | `CreatorStudioEngine`, `EngineeringWorkbench`, Website Forge | Some outputs are plans/templates; rendered video, production CAD and complete build/deploy automation are not shipped |

## Implemented in this branch from the gap audit

### 1. Real provider-backed multi-role team
- Replaced the plan-only `AgentCouncil` with real sequential Planner → Researcher → Specialist → Critic → Verifier provider calls.
- Uses the user's selected configured provider (Gemini, OpenAI, Groq, Claude or Perplexity where configured).
- Exposes progress in the Mobile Work Agent.
- Stops at the first failed/empty stage; does not label partial output complete.
- Supports cancellation between stages.
- Added unit tests for role order, progress, fail-fast and cancellation.
- Limitation: this is a five-role pipeline using the selected provider, not parallel independent external agents or a multi-provider swarm.

### 2. Persistent background task queue
- Added WorkManager-backed network-constrained background execution for the five-role team.
- Added queue and Stop controls to the Mobile Work Agent.
- Persists scheduled work across process/app restarts; the OS may reschedule after interruption.
- Reports stage progress and final Verifier output; cancellation is checked between role calls.
- Limits task payload and WorkManager output to stay within its Data size constraints.
- Limitation: provider calls already in flight cannot be interrupted by the current synchronous provider clients; process death may restart the task from the beginning rather than resume at an individual role checkpoint.

### 3. Live Eyes compile fix
- Added the missing announcement-throttle state required by the existing headless camera service so the Kotlin source can compile.
- The feature still requires physical Android validation for camera start/stop, privacy indicator, OEM background behaviour, network failures, battery and voice handoff.

### 4. Windows companion verification pipeline
- Updated Windows CI to run tests and package both the legacy companion and the advanced `desktop-companion/shraddha-pc-companion` implementation on this working branch.

## Remaining Stonic gaps — continue implementation

### P0 — Cross-device durable workspace
- Finish file transfer end-to-end: Android picker, receiver approval, disk-backed chunk persistence, resume after app/process restart, hash verification, cancellation and Android↔Windows integration tests.
- Share task state, conversation handoff, selected files, status and approvals across Android and Windows.
- Keep E2E peer identity verification and per-device permissions.

### P0 — Full project-aware coding agent
- Existing code-assistant and Website Forge pieces are not a verified end-to-end code agent.
- Add project workspace selection, file tree/context, bounded file edits, diff preview, tests/build execution in a sandbox, logs, rollback and deploy preview.
- Never execute arbitrary shell commands from an untrusted model response. Require local approval for install, execute, overwrite, delete and deploy.

### P1 — True multi-provider orchestration
- Current new team uses one selected provider for all five roles.
- Add optional role-to-provider mapping for 2–5 configured providers, credential availability checks, bounded parallelism for independent research, shared evidence store, conflict resolution, cost/time budgets and deterministic final verification.
- A missing provider key must produce a clear setup requirement, not a simulated agent.

### P1 — Personalised, source-aware proactive briefing
- Current daily briefing is primarily local scheduling and agenda data.
- Add user-selected interests and goals, source-linked web research, quiet hours, review-before-action, pause/disable controls and clear data-source provenance.
- No hidden tracking or unsolicited messages.

### P1 — Versioned skills and integrations
- Current plugin metadata validates scopes but does not execute sandboxed third-party code.
- Add signed/versioned skill manifests, declared permissions, isolated execution, audit events, health checks, revocation and explicit user consent per scope.
- Do not load arbitrary downloaded code into the app process.

### P2 — Advanced PC file operations
- Advanced Windows companion deliberately supports safe read-only file browsing, not arbitrary editing/deletion/execution.
- Add transactional file edits with diff preview, backup/rollback and local confirmation. Add process/app diagnostics with a strict allowlist and visible action timeline.
- Keep arbitrary shell execution disabled.

### P2 — Unified awareness and personality
- Apply the editable assistant name, voice, personality and language profile consistently across Android voice/chat, notifications and Windows companion.
- Add user-visible awareness state, quiet hours, interruption rules and a global emergency stop/revoke dashboard.

## Differentiators to take Shraddha beyond STONIC

1. One Hindi-first agent across Android and Windows, not separate assistants.
2. Camera-as-eyes + screen + files + voice context in one conversation, with visible OS privacy indicators.
3. Verified execution: every action reports observed success/failure; a sent command is not proof of completion.
4. Local-first encrypted memory, reminders and offline inference when configured.
5. User-selected provider routing with transparent provider, source and cost reporting.
6. Privacy dashboard for microphone, camera, screen, memory, cloud sync and paired devices.

## Delivery order

1. Finish and test the new multi-agent pipeline and persistent background queue.
2. Finish cross-device file transfer and shared workspace.
3. Build project-aware coding agent with sandboxed execution and verification.
4. Implement true multi-provider orchestration and source-aware briefing.
5. Add signed skills framework and unified privacy/personality controls.
6. Run Android unit tests/build, emulator smoke, backend tests, both Windows companion suites/packages, then physical Android↔Windows testing. Merge into `main` only after required CI checks and device tests pass.

## STONIC public references

- https://stonicai.com/
- https://stonicai.com/releases
- https://stonicai.com/changelog/

STONIC public descriptions are used as a feature comparison reference. Feature availability can vary by release; this repository audit is grounded in source files, not marketing claims.