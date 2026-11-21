# SHRADDHA AI v0.12.0 — Feature completeness and advanced roadmap

## Product rule
No feature is described as complete merely because a screen, button, API interface, or roadmap entry exists. Each capability must pass implementation, permission, integration, failure-path, security, and real-device tests.

## Existing source areas carried forward
- Android AI shell, local encrypted memory, offline command rules, provider gateway client, Firebase Auth/Firestore/Storage/FCM dependencies and cloud vault integration.
- App Operator and Accessibility service, overlay, keyboard, screen-sharing and remote-camera service source. These require Android runtime permissions and device-specific validation.
- Kids Universe: age-wise 0–6 curriculum and local care journal. Not a medical monitor or diagnostic system.
- Person Directory: consent-based profile notes. Actual face matching and speaker biometrics are not enabled.
- Lia source integration: Gemini Live voice, orb UI, app intents, chat, history, website forge and navigation source. This remains an integration source tree, not proof all Lia code is merged into the primary app.
- MYRA desktop companion source: Windows/Linux/Termux code. It must be installed and paired separately; desktop control cannot run inside the Android APK alone.

## New v0.12.0 engineering additions
- CapabilityCatalog: 30-item status registry with Available, Permission Required, Companion Required, Setup Required and Roadmap states.
- WorkflowEngine: deterministic lifecycle with explicit approval gates for sensitive/irreversible steps and post-action verification state.
- LocalAuditTrail: bounded metadata-only local audit log; excludes credentials, biometrics and screen contents.
- Future Intelligence screen: visible capability inventory, workflow preview and audit log access.

## Advanced features to add in future releases
1. Multi-agent council: planner, researcher, specialist, critic and verifier, with disagreement handling and source citations.
2. Autonomous Project Manager: milestones, dependency graph, budget/time estimates, checkpoints, rollback and human approvals.
3. Personal Knowledge Graph: entities, relationships, provenance, expiry, user correction and per-topic deletion.
4. Offline AI runtime: model catalogue, download integrity, quantization, hardware fit checks, offline speech and local RAG.
5. Signed Plugin SDK: sandbox, capability scopes, permission prompts, audit logs, version pinning and revocation.
6. Predictive assistant: opt-in context signals, explainable suggestions, quiet hours and no covert monitoring.
7. Secure device fabric: QR pairing, mutual authentication, encrypted transport, device revocation, scoped remote sessions and visible recording indicator.
8. Creator pipeline: text/image/audio/video/document workflows with licensing, export formats and provenance.
9. Engineering workbench: CAD/EDA connectors, simulation adapters, unit-aware calculations and professional review gates.
10. Family mode: guardian accounts, age-appropriate profiles, data minimization and parent-managed learning.
11. Accessibility suite: voice navigation, switch access, captions, text scaling and reduced-motion modes.
12. Recovery center: encrypted export, backup verification, restore rehearsal and account/device recovery.
13. Smart-home hub: local network integrations, device allowlists, safety confirmations and manual override.
14. Personal digital twin: user-approved preferences and routines only; editable, inspectable and deletable.
15. Incident response: suspicious session alerts, token revocation, permission change history and emergency lock.

## Non-negotiable platform constraints
- Android permissions, background execution, battery optimization, protected screens and OEM restrictions remain in force.
- PC/other-phone control requires an online companion installed and authorized on that target device.
- Sending messages, calls, payments, deleting files, changing accounts or controlling physical devices requires explicit confirmation.
- Medical/legal/engineering output is educational or assistive unless independently verified by a qualified professional.
- Face/voice identity systems require explicit informed consent, anti-spoofing, measured false-match rates, secure deletion and applicable legal review. No covert identification.
- Provider quotas, API availability, network access, model quality and device hardware cannot be made unlimited by software.

## Verification status
Source-level changes are included. Android APK build and physical-device end-to-end tests were not performed in this environment. All capabilities must be reclassified only after their corresponding tests pass.


## v0.14.0 implementation update
- Live Multi-Provider Council is now implemented as an authenticated backend route plus Android UI. It runs Planner, Researcher, Specialist, Critic and Verifier role prompts concurrently across 2–5 configured providers, then synthesizes the successful outputs. Provider failures are shown rather than silently omitted.
- The council endpoint requires `GATEWAY_TOKEN`; set at least two provider API keys. It is advice-only and never executes device or account actions.
- The offline local council plan is retained as a separate no-network feature.
- Live provider success still depends on deployment, valid API keys, quotas and network; no provider round-trip is claimed in this offline build environment.

## v0.19.0 implementation and verification update — 2026-11-20

### Implemented and source/CI verified
- Local Knowledge Graph is now connected to the Future Intelligence UI and persisted in a dedicated Android Keystore AES-GCM slot. User-entered memories can be created, searched, linked, deleted and reset. Input and graph size are bounded; legacy preference data migrates to the encrypted slot on first load.
- Password-protected portable backup supports encrypted export and validated restore of supported user-managed app data. PBKDF2-HMAC-SHA256 (210,000 rounds) derives the AES-256-GCM key; tampering and wrong passphrases are rejected. Passphrase character arrays are wiped after use.
- Task Planner includes create/edit/delete/complete/reschedule, local reminders, reboot/app-update restoration and optional daily briefing. Backup restore rebuilds reminder alarms; disabling the briefing cancels its alarm.
- Operator learned workflows request approval immediately before each sensitive step, resume from the approved step without replaying completed steps, and can be stopped from the Operator UI. Closing the Operator activity requests stop; an already-dispatched external action cannot be recalled.
- Latest Android CI #331 passed backend tests, Android unit tests, Android static lint, debug APK build, ABI verification and checksum verification. See `docs/BUILD-TEST-REPORT-v0.19.0.md` for artifact and hashes.

### Not complete until tested or externally provisioned
- No physical Android device is attached to this CI environment; microphone, camera, Accessibility, OEM background behavior, calls/SMS/WhatsApp and notifications still need device testing.
- Firebase and AI provider account flows need the user's project configuration, rules, API keys and real round-trip tests.
- Windows companion must be installed and paired on an actual PC and phone.
- Offline LLM inference, smart-home integrations, signed plugin sandbox, unattended social publishing, certified biometric anti-spoofing, full live-citation research and automatic CAD/media generation remain incomplete or external-integration dependent.
- Debug APK success is not a production-signed release. Production signing requires the owner's keystore and secrets.
