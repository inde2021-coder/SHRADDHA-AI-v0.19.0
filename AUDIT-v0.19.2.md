# Shraddha AI v0.19.2 — Source Audit & Verification Notes

## Scope
This report records source-level checks performed on the `audit-fixes-v0.19.1` branch and targeted fixes committed to `redesign-v0.19.2`. It is not a claim that every feature has passed runtime testing.

## Changes in v0.19.2
- Voice foreground service now accepts only the explicit `ACTION_START` and `ACTION_STOP` actions.
- Null, stale, or unknown service intents stop the service instead of activating microphone capture.
- Voice service now returns `START_NOT_STICKY`; Android must not silently revive a previous microphone session after process death.
- Persistent notification clearly indicates microphone use and offers a Stop microphone action.
- Accessibility service labels now distinguish the primary Shraddha device-control service from the optional advanced app-operator compatibility service.
- Dashboard status text uses actual line breaks.
- Shraddha orb visual was upgraded with layered radial lighting, three animated orbital rings, orbiting particles, and lifecycle-aware animation start/stop.
- Android version bumped to versionCode 22 / versionName 0.19.2.
- Android build workflow now runs for this redesign branch.

## Source observations
- The dashboard has Home, Assistant, Devices, and Experts bottom navigation and separate workspaces for AI Lab, People Memory, Cloud/Data, Permissions, Future Intelligence and related systems.
- The main dashboard contains a custom animated orb and a Live Workspace panel. The panel correctly says it is waiting for an active task rather than fabricating live activity.
- Voice uses Gemini Live for the real-time audio session. The multi-provider text/council and expert features call the configured HTTPS backend.
- Local memory/project/library features are explicitly local-only in their UI labels; Firebase cloud sync requires Firebase project configuration and user consent.
- Device pairing, remote screen sharing, camera sharing and app control require a paired/connected companion, Android permissions and explicit user approval.
- The manifest contains two AccessibilityService implementations for distinct code packages. They have been retained rather than deleting one without tracing all callers. The optional app-operator service is labelled as optional to reduce confusion.

## Known limitations / not yet verified
- No Android emulator or physical device test has been performed in this audit.
- The Android Gradle build and unit tests must pass in GitHub Actions before an APK can be called build-verified.
- Continuous microphone operation depends on an explicit user-started foreground service, microphone permission, Android foreground-service policy, network availability and valid Gemini credentials. It is not an always-on covert listener.
- The source includes several roadmap or configuration-required capabilities. A menu entry or capability catalogue item does not mean the feature is fully implemented.
- PC control requires a separately installed and paired PC companion; remote phone control requires the companion app and consent on the target device.
- Android secure screens, banking/OTP/password screens, DRM/protected content and apps that block Accessibility cannot be universally controlled or captured.
- Firebase cloud sync needs correct Firebase app configuration and secure database/storage rules.
- API provider calls require the corresponding backend routes and server-side provider credentials. Do not place production provider secrets in the Android app.
- The current dashboard is a native Android interface with a custom animated orb; it is not a rendered 3D video scene. Live task visualization can show only real events received from an active operator/paired device.

## Required verification
1. GitHub Actions: `:app:testDebugUnitTest`.
2. GitHub Actions: `:app:assembleDebug`.
3. Install the debug APK on a real Android 13+ device and verify permissions, foreground notification, voice start/stop, process-death behavior, accessibility setup and camera/screen-share consent.
4. Test every backend route with valid and invalid credentials, offline network, provider timeout and malformed responses.
5. Test UI on small and large screens, including font scaling and Android Back navigation.


## Follow-up operator hardening (2026-11-19)
- Voice screen tools now route through the primary `Shraddha AI — Device Control` Accessibility service used by the Universal App Operator, rather than requiring the separate optional LIA compatibility service.
- Added directional swipe commands (up/down/left/right) alongside screen reading, unique-label taps, safe text entry, scroll, Back and Home.
- Screen snapshots redact password, OTP, PIN, CVV, verification-code and UPI-labelled fields before their values can reach the voice tool response.
- Focused and resource-ID text entry reject sensitive fields. Trained workflows now assess resolved runtime values, not only template names.
- Ambiguous visible labels are not tapped. Ambiguous installed-app name matches are not auto-launched.
- Destructive UI labels require an explicit user-confirmation signal before a tap; the model must not interpret text shown inside an app as consent.
- Added a Control Any App entry to Quick Actions to open the Universal App Operator console.
- Clarified the primary and optional Accessibility service labels and updated Hindi setup/communication documentation.

## Remaining gaps after this follow-up
- These changes are source/CI work. Physical-device tests are still required for Android 13–16, different OEM accessibility trees, gesture coordinates, split-screen, keyboard overlays and apps with custom Compose/WebView/canvas controls.
- Voice-driven app control provides screen-observe + single-action tools and now exposes saved learned workflows through `list_app_workflows` / `run_app_workflow`. Training remains in the Operator/Training console; pause/resume checkpoints and voice-driven workflow creation are not yet integrated.
- OCR/vision fallback for apps that expose no Accessibility nodes is not implemented. Screen capture and OCR need a separate consent-based path and privacy filtering.
- The optional WhatsApp compatibility service still needs real-device validation. The current send path is brittle because WhatsApp UI and recipient metadata vary by version/language; do not treat the feature as production-reliable until verified on the user's device.
- Generic social-media publishing, in-app search/form automation templates, file upload flows across arbitrary apps, notification action handling, clipboard control, app-specific API integrations and robust recovery from dialogs/errors remain incomplete.
- PC companion currently supports a limited consent-gated set of mouse/keyboard actions; it does not yet stream the PC screen or provide OCR, window-aware targeting, app-specific workflows or remote file management.
- The assistant's own non-Google bilingual voice is not integrated yet. Gemini Live currently returns Gemini-generated audio; the voice selector is a Gemini preset selector, not a custom Shraddha voice.
- Firebase cloud memory and paired-device/remote camera features still require real Firebase credentials, secure rules, a second device and end-to-end tests.
- Build CI does not replace installation and interaction testing on a physical Android phone.


## Additional completion work (2026-11-19)
- Replaced Gemini Live audio output with text-only responses. All spoken assistant replies now route to the dedicated `ShraddhaSpeechClient`; there is no Android system TTS or Gemini voice fallback.
- Added authenticated backend `POST /api/voice/synthesize` proxy with bounded text/audio payloads, HTTPS-only upstream, server-side TTS token, 24 kHz mono PCM validation and no-store response.
- Added `voice-engine/` self-hosted Piper HTTP service container. It requires licensed Hindi and English model files that represent the same Shraddha voice identity; no third-party voice model or model weights are silently bundled.
- Added custom voice gateway tests and Python syntax validation to CI.
- Added `EncryptedBackupCodec`: PBKDF2-HMAC-SHA256 (240,000 iterations) + AES-256-GCM authenticated backup envelope, strict schema/size validation and passphrase-based restore. UI exports/restores selected notes, knowledge graph, active project and digital-twin preferences. API keys, pairing tokens, permissions, voice transcripts and biometric data are intentionally excluded.
- Added JVM tests for backup round-trip, wrong passphrase and malformed/unsupported envelopes.

## Remaining external dependencies and limits
- The custom TTS engine code is integrated, but a branded voice is not yet active until the user supplies or commissions licensed Hindi and English voice models trained/designed for the same Shraddha identity, deploys the `voice-engine` service, and configures backend `SHRADDHA_TTS_URL` and `SHRADDHA_TTS_TOKEN`. No custom model weights or voice recordings are present in the repository.
- Self-hosted Piper is a CPU/GPU service and should run on a persistent HTTPS host; Vercel serverless is not the intended inference host.
- Encrypted backup covers only the selected app-managed records listed above. It does not yet export Android Keystore-protected vault ciphertext, Firebase cloud vault, installed app data, contacts, messages, device pairing secrets or media.
- Voice response playback, microphone barge-in timing and TTS latency require physical-device validation with actual Hindi/English model files.
- OCR reading and unique text-target tapping are now implemented with opt-in local ML Kit; arbitrary non-text canvas controls, OCR-driven gestures and custom visual-target tracking remain limited. Durable resume checkpoints, native offline LLM inference, biometric identity models, smart-home connectors, generic social-media publishing and rendered video/CAD pipelines remain integration work rather than shipped capability.


## Advanced OCR and PC streaming follow-up
- Added optional on-device Hindi/English OCR using bundled ML Kit Latin + Devanagari recognizers. User must enable it in Settings; OCR is processed locally, sensitive labels and adjacent lines are omitted, and long numeric strings are redacted.
- Added unique OCR text tapping with normalized screenshot coordinates. It is opt-in, refuses ambiguous/sensitive targets, and applies the same destructive-action confirmation policy as Accessibility text taps. It still cannot reliably interact with every canvas or protected Android surface.
- Added live PC screen streaming to the Windows companion. The PC user must opt in with `--share-screen` and confirm locally; stream permission is explicitly sent to the relay, frame rate is capped at 2 FPS, JPEG payloads are bounded, optional target device ID is supported, and likely sensitive active-window titles pause capture.
- Fixed PC companion reconnect when using saved Windows Credential Manager pairing without re-entering the base URL.
- Added unit tests for the PC frame protocol and payload bounds; CI now validates OCR dependencies and Kotlin compile.
