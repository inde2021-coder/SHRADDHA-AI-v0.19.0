# Shraddha AI vs STONIC — Verified Feature Gap Audit

Audit basis: current `optimization/apk-size-without-feature-loss` branch source, plus STONIC's public product and release notes checked 2026-11-22. This is a code-evidence audit, not a claim that every existing module is production-ready.

## Already present in Shraddha AI — do not duplicate

| Capability | Evidence in repository | Audit status |
|---|---|---|
| Natural voice conversation | `VoiceSessionManager`, `GeminiLiveClient`, `LiveAudioIO`, Hindi/English language handling | Present; improve reliability and tool integration |
| Persistent conversation / personal memory | `ConversationMemory`, `PersistentChatMemory`, `SecureMemoryVault`, `UnifiedMemoryRepository` | Present; encrypted history is opt-in and cloud sync is consent-gated |
| Phone app and UI control | `LiaToolCatalog`, Accessibility Operator, trained workflows, screen OCR | Present; actions are constrained and sensitive actions require confirmation |
| Windows PC companion | `pc_companion/agent.py`, secure pairing, E2E peer keys, local approval | Present but currently limited to mouse, keyboard, shortcuts, scrolling and screen sharing |
| Web/mobile work surface | `WorkAgentActivity` | Present; embedded browser + chat + reviewed hand-off |
| Tasks and reminders | `TaskPlannerActivity`, `ReminderReceiver` | Present; local reminders and a basic daily briefing schedule |
| Visual understanding | `vision/LiveEyesCameraService`, `backend/vision-analysis.js`, OCR and screen share | Present in progress; live camera analysis is not yet a complete conversational visual agent |
| Website/code creation | `WebsiteForgeClient`, Website Forge UI, PC companion source includes coder tools | Present in parts; end-to-end code agent is not yet integrated as one verified workflow |

## Verified gaps to build — avoid pretending stubs are complete

### P0 — Real multi-agent execution (not only planning)
- Current `AgentCouncil.plan()` creates role descriptions and explicitly says it does not contact external agents or execute actions.
- Build a real orchestrator with Planner, Researcher, Builder/Specialist, Critic and Verifier workers.
- Shared task state, explicit dependencies, bounded parallelism, cancellation, retries, progress events and a final verification report.
- Agents must use real configured providers/tools; UI must distinguish planned, running, completed, failed and blocked work.

### P0 — Full PC work agent with observable verification
- Current Windows `actions.py` supports a small allowlist: tap, swipe, type, open Start-menu shortcut, home, back and scroll. It does not provide file operations, code editing/running or process management.
- Add permission-scoped file search/read/write/move, app/window inspection, process diagnostics and project-aware code workflows.
- Keep local PC approval for control sessions; show a visible action timeline and Stop control.
- Require exact confirmation before overwrite, delete, install, send, publish, shutdown or settings changes. Never expose arbitrary unrestricted shell execution.

### P1 — Background task engine
- Current Task Planner stores reminders and schedules a daily briefing; it is not a durable multi-step job runner.
- Add persistent task queue, resumable checkpoints, retry policy, foreground/background progress notifications, cancellation and completion callbacks.
- Long-running downloads, code builds, file operations and research should continue safely without blocking the voice conversation.

### P1 — Proactive Awareness / personalised “Your World”
- Current daily briefing is a scheduled local reminder, not a source-aware personalised briefing.
- Add user-selected interests/goals, quiet hours, a reviewable daily digest, current web research with citations, and explicit pause/disable controls.
- Do not infer sensitive interests or send unsolicited messages/actions.

### P1 — Shared phone ↔ PC workspace and conversation continuity
- Current PC pairing and Android work surfaces exist, but the PC README documents that the companion does not read files, capture microphone audio or silently send messages.
- Unify task handoff, selected files, conversation history, status and approvals across phone and PC.
- Preserve end-to-end encryption for peer data and re-verify device identity after reconnects.

### P2 — Skills and integrations framework
- Add installable, versioned skills with declared permissions, tool schemas, provenance, health checks and revocation.
- Skills must not gain permissions implicitly; every sensitive action remains user-approved.
- Avoid duplicate one-off integrations when a reusable skill can serve multiple workflows.

### P2 — Character and awareness controls
- Provide editable character name, voice, personality and behaviour as a single profile applied consistently across voice, chat, notifications and PC companion.
- Add an explicit Awareness toggle, quiet hours, interruption controls and user-visible reasons when Shraddha speaks first.

## Differentiators to take Shraddha beyond STONIC

1. **Phone-native + PC-native agent:** one task can work across Android apps, Windows apps and browser surfaces with shared progress and approvals.
2. **Hindi-first multimodal interaction:** Hindi/English voice, camera-as-eyes, screen context, OCR and files in one conversation.
3. **Verified execution:** every action reports evidence of success/failure; never say a task is complete merely because a command was sent.
4. **Offline/local-first modes:** local reminders, encrypted memory and supported on-device tasks remain useful without cloud connectivity.
5. **Open provider routing:** Gemini, OpenAI, Claude, Groq and compatible providers can be configured without tying the product to one vendor.
6. **Privacy dashboard:** clear indicators for microphone, camera, screen, memory, cloud sync and connected devices, with immediate stop/revoke controls.

## Delivery order and acceptance criteria

1. Finish the current camera-as-eyes implementation and prove start/stop, camera privacy indicator, stale-frame suppression, live question context and voice handoff on a physical Android device.
2. Implement the durable task engine and connect existing Task Planner/Work Agent to it.
3. Upgrade AgentCouncil from a plan-only stub to real provider-backed workers and verifier tests.
4. Expand Windows PC companion using least-privilege tools, local approval and a visible action/verification timeline.
5. Add source-aware personalised briefing, shared workspace and versioned skills.
6. Run Android unit tests, backend tests, PC companion tests, build/release checks and physical phone↔PC integration tests. Merge to `main` only after required checks pass.

## STONIC references

- https://stonicai.com/
- https://stonicai.com/releases
- https://stonicai.com/changelog/
- https://stonicai.vercel.app/capabilities

STONIC is primarily a Windows desktop product. Its public release notes describe voice, memory, characters, PC computer agent, file attachments, web search, background work and agent workflows. The feature list above is based on published descriptions; availability can vary by release.