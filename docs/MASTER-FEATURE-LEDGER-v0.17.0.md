# SHRADDHA AI v0.17.0 — Master Feature Ledger

This ledger consolidates the previously requested 55 core capabilities, the later advanced capability requests, and Sonic-style voice/agent capabilities. It deliberately distinguishes source presence from a feature being integrated and validated at runtime.

## Release truth
- The v0.16.0 Shraddha source is the primary Android/backend baseline.
- The complete upstream Lia archive is preserved at `upstream-source/lia/`.
- The complete upstream MYRA archive is preserved at `upstream-source/myra/`.
- The earlier Shraddha v0.8.3 baseline is preserved at `upstream-source/shraddha-v0.8.3/`.
- Existing Lia and MYRA integration trees from v0.16.0 remain in place. Upstream copies are retained for source parity and audit; they are not represented as merged into one APK.

## Status definitions
- **Core source**: implementation source exists in the primary v0.16 codebase; still requires device/runtime validation.
- **Companion/source**: implementation exists in a separate upstream or companion project; requires integration, install, pairing, or platform-specific validation.
- **Partial**: only a subset or planning/calculation/template layer exists.
- **External dependency**: requires provider credentials, service account, hardware, model weights, or third-party API.
- **Not validated**: no end-to-end test result is available for this release.

## Previously requested 55 core capabilities

| # | Capability | Honest status / acceptance gate |
|---:|---|---|
| 1 | Self-Learning App Operator | v0.18.0 source: per-app semantic anchors, named workflows, adaptive re-resolution and step verification; target-app/device validation pending |
| 2 | Multi-App Workflow | Core source/partial; validate across installed apps and OEMs |
| 3 | Visual Mouse & Keyboard | Companion/source; platform-specific input permissions required |
| 4 | Task Recorder | v0.18.0 source: user-authored steps, reusable variable keys and persistent app profiles; adaptive recording/rollback still limited |
| 5 | Workflow Creator | Partial; visual editor and executable workflow validation required |
| 6 | Error Recovery | Partial; recovery and rollback tests required |
| 7 | Smart Form Filler | Per-app semantic field anchors and variable-based TYPE steps; custom widgets and validation rules need app-specific tests |
| 8 | Browser Agent | Companion/source; browser session and anti-abuse controls required |
| 9 | Multi-Agent Team | Partial; provider-backed council exists, autonomous tools are gated |
| 10 | AI Manager | Partial; task assignment and monitoring acceptance tests required |
| 11 | Self-Improvement Engine | Not autonomous; reviewed updates and regression suite required |
| 12 | Deep Research Agent | Partial; live retrieval/citations must be enabled and verified |
| 13 | Fact Verification | Partial; independent evidence and source provenance required |
| 14 | AI Debate System | Partial; multi-provider configuration required |
| 15 | Personal Knowledge Graph | Partial; relationship/provenance/delete lifecycle required |
| 16 | Context Memory | Core source; Firebase configuration and restore tests required |
| 17 | Live Object Recognition | External dependency; camera/model and device tests required |
| 18 | Visual Troubleshooter | Partial; camera/OCR/model integration required |
| 19 | 3D Object Scanner | External dependency; depth/photogrammetry pipeline required |
| 20 | Remote Visual Lens | Companion/source; explicit session and network tests required |
| 21 | Screen-to-Screen Assistance | Companion/source; secure paired screen stream required |
| 22 | Live Translation | External dependency; live speech/vision translation model required |
| 23 | Visual Memory | Partial; user-approved image storage, indexing and deletion required |
| 24 | Electrical Design Studio | Partial; calculators and safety-reviewed design workflow required |
| 25 | Electronics Lab | Partial; simulator/instrument connectors required |
| 26 | Mechanical CAD Studio | Partial; real CAD kernel/export integration required |
| 27 | Civil Architecture Studio | Partial; CAD/BIM and code-compliance review required |
| 28 | Agriculture Intelligence | Partial; local agronomy data and image models required |
| 29 | Medical Information Assistant | Partial; educational only, clinician review for clinical use |
| 30 | Legal Research Studio | Partial; jurisdiction-specific current sources required |
| 31 | Coding & Debugging Lab | Partial; isolated build/test runner and repository integration required |
| 32 | Science Simulation Lab | Partial; domain simulation engines required |
| 33 | Website Builder Pro | Companion/source; deploy credentials and live build validation required |
| 34 | Android App Creator | Partial; isolated Gradle build/test/sign pipeline required |
| 35 | Game Creator | Partial; game engine and export pipeline required |
| 36 | Video Production Agent | Partial; actual media rendering engine/provider required |
| 37 | Image Design Studio | Partial; image generation/edit provider required |
| 38 | Document Intelligence | Partial; OCR, Office parsing and export validation required |
| 39 | Business Manager | Partial; connectors and user-authorized business data required |
| 40 | Social Media Manager | Companion/source; official platform APIs and approvals required |
| 41 | Universal Device Hub | Core/partial; per-platform pairing and capability matrix required |
| 42 | Remote Desktop | Companion/source; secure pairing, session consent and tests required |
| 43 | Smart Home Control | External dependency; Matter/MQTT/Home Assistant hardware required |
| 44 | File Intelligence | Partial; indexed file permissions and safe operations required |
| 45 | Clipboard Sync | Companion/source; secure cross-device sync and opt-in required |
| 46 | Notification Intelligence | Core/partial; notification access permission and device tests required |
| 47 | Device Health Monitor | Partial; Android snapshot exists, deeper OS metrics vary by platform |
| 48 | Cross-Device Task Transfer | Partial; resumable authenticated task protocol required |
| 49 | Personal Digital Twin | Partial; editable local preferences exist, predictive model not complete |
| 50 | Autonomous Project Manager | Partial; project planning exists, autonomous execution gated |
| 51 | Holographic Live Workstation | UI/status panel exists; real live stream is not fabricated and needs connected agent |
| 52 | AI Security Guardian | Partial; security controls exist, full incident response testing required |
| 53 | Offline AI Mode | Partial; integrity helper exists, local inference runtime/model download required |
| 54 | AI Plugin Marketplace | Partial; manifest/scope validation exists, isolated execution not enabled |
| 55 | Predictive Assistant | Partial; opt-in suggestions exist, continuous predictive intelligence not complete |

## Later advanced feature requests

| Capability | Status / dependency |
|---|---|
| AI Agent Swarm | Partial; council planning/provider calls exist; tool-executing agents need sandbox and approval engine |
| Hybrid Offline Brain | External model runtime and compatible model weights |
| Universal Device Control | Platform companions, permissions, pairing and per-action confirmation |
| Voice-to-Action Automation | Android voice/accessibility permissions and verified action loop |
| Smart Home | Matter/MQTT/Home Assistant integration and physical-device testing |
| Face Recognition | Not enabled; informed consent, anti-spoofing, measured false-match rates and deletion lifecycle required |
| Voice Identity | Not enabled; informed consent, anti-spoofing and biometric security review required |
| Scene Understanding | Vision model and camera runtime required |
| Live Translation Lens | OCR/vision + speech translation model required |
| Electrical/Mechanical/Civil Labs | Calculator/template subsets exist; CAD/EDA/simulation engines and professional review required |
| Medical and Legal Labs | Educational assistance only; current jurisdictional sources and qualified review required |
| Video Studio / 3D Studio | Actual rendering engines and export pipeline required |
| Research Studio | Live retrieval, citation verification and source provenance required |
| Automated Testing Lab | Isolated runners, device farm/emulators and report verification required |
| Permission Guardian | Partial; complete OS-level audit, anomaly detection and revocation tests required |
| Emergency Lock | Requires app/device-level lock policy and recovery tests |
| Encrypted Memory Backup | Encrypted export source exists; full restore rehearsal and cloud recovery tests required |
| Plugin Sandbox | Not enabled; OS/container isolation and signed packages required |
| Self-Healing System | Not autonomous; diagnostics and human-approved repair workflow required |
| Task Scheduler | One-shot scheduler exists; recurring/reboot persistence and exact alarm permission tests required |

## Sonic-style voice and assistant capabilities

These are product capabilities, not an assertion that a proprietary Sonic model or service is bundled:
- Streaming speech-to-speech conversation: external voice model/provider and streaming transport required.
- Barge-in / interruption handling: audio focus, duplex capture and playback cancellation tests required.
- Natural turn-taking and low-latency response: streaming STT/TTS or speech-to-speech provider required.
- Hindi/English auto language handling: multilingual model and real-device evaluation required.
- Tool calling from voice: allowlisted tools, schema validation, approval gates and post-action verification required.
- Asynchronous long-running tasks: durable job queue, progress events, cancellation and restart recovery required.
- Persistent conversation memory: user-controlled encrypted memory and deletion/export required.
- Screen-aware voice assistant: explicit screen capture/accessibility permission and visible session indicator required.
- Email/calendar/group assistant: OAuth and official provider APIs required.
- Voice notes to tasks: transcription provider or local speech model plus confirmation before sending actions.

## v0.18.0 Universal App Operator additions

| Capability | v0.18.0 status |
|---|---|
| Per-app Training Profiles | Implemented in source; local SharedPreferences persistence keyed by package |
| Semantic UI Anchors | Implemented for resource ID, visible text, hint text, content description and widget class |
| Confidence-based matching | Implemented; ambiguous/low-confidence targets stop |
| Adaptive workflow runner | Implemented; re-reads screen before/after each step |
| Workflow authoring | Implemented for CLICK, TYPE, SELECT_FILE, BACK and SCROLL |
| Natural-language workflow routing | Implemented as local matching to user-taught workflows; not an unrestricted planner |
| Hindi voice command input | Implemented through Android speech recognition intent; device speech service and microphone permission required |
| Exact file-name locator | Implemented for MediaStore and SAF-granted folder trees |
| File-picker selection | Best-effort Accessibility search/select for supported DocumentsUI pickers; not universal |
| Risk gate | Implemented for secrets and high-impact actions; destructive workflows require explicit confirmation |
| Operator audit | Metadata-only local journal; no credentials or screen images |

## Production acceptance checklist
A capability is only promoted to **Validated** after all of the following pass:
1. Unit tests for normal, invalid, boundary and failure cases.
2. Permission-denied and permission-revoked behavior.
3. Network-offline, timeout, retry and duplicate-action behavior.
4. Android real-device/OEM validation where applicable.
5. Companion pairing, revocation and transport-security tests where applicable.
6. User confirmation for messages, calls, payments, deletion, account changes and physical-device actions.
7. Data export, deletion and recovery tests for stored personal data.
8. No API keys, biometric templates or secrets embedded in the APK or public repository.

## Important boundary
No software bundle can make a feature operational without required credentials, API access, model weights, permissions, target-device companion software, supported hardware or external provider availability. This release preserves the full source inputs and provides a single auditable capability ledger; it does not claim every row above is already production-complete.
