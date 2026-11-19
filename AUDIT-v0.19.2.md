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
- Voice-driven app control provides screen-observe + single-action tools and exposes saved learned workflows through `list_app_workflows` / `run_app_workflow`. Training remains in the Operator/Training console; encrypted verified-step checkpoints and explicit voice resume are implemented. Voice-driven workflow creation is not yet integrated.
- OCR/vision fallback for apps that expose no Accessibility nodes is not implemented. Screen capture and OCR need a separate consent-based path and privacy filtering.
- The WhatsApp compatibility service still needs real-device validation. SMS/WhatsApp sends now require a separate explicit approval bound to the exact resolved phone number and exact message body; any edit invalidates the approval. The final WhatsApp UI interaction remains version/language dependent; do not treat it as production-reliable until verified on the user's device.
- Generic social-media publishing, in-app search/form automation templates, file upload flows across arbitrary apps, notification action handling, clipboard control, app-specific API integrations and robust recovery from dialogs/errors remain incomplete.
- PC companion supports a limited consent-gated set of mouse/keyboard actions and opt-in bounded screen streaming. PC OCR, window-aware targeting, app-specific workflows and remote file management remain incomplete.
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
- Voice response playback, microphone barge-in timing and TTS latency require physical-device validation with actual Hindi/English model files. SMS and WhatsApp sending now require exact recipient + exact message confirmation; tests cover the approval policy.
- OCR reading and unique text-target tapping are now implemented with opt-in local ML Kit; arbitrary non-text canvas controls, OCR-driven gestures and custom visual-target tracking remain limited. Encrypted workflow checkpoints and explicit resume at verified step boundaries are implemented; an interrupted in-flight action is intentionally not replayed. Native offline LLM inference, smart-home connectors, generic social-media publishing and rendered video/CAD pipelines remain integration work rather than shipped capability. People Memory biometric capture/enrollment/matching now has a source implementation, but remains unverified until Android CI and target-phone tests complete.


## Advanced OCR and PC streaming follow-up
- Added optional on-device Hindi/English OCR using bundled ML Kit Latin + Devanagari recognizers. User must enable it in Settings; OCR is processed locally, sensitive labels and adjacent lines are omitted, and long numeric strings are redacted.
- Added unique OCR text tapping with normalized screenshot coordinates. It is opt-in, refuses ambiguous/sensitive targets, and applies the same destructive-action confirmation policy as Accessibility text taps. It still cannot reliably interact with every canvas or protected Android surface.
- Added live PC screen streaming to the Windows companion. The PC user must opt in with `--share-screen` and confirm locally; stream permission is explicitly sent to the relay, frame rate is capped at 2 FPS, JPEG payloads are bounded, optional target device ID is supported, and likely sensitive active-window titles pause capture.
- Fixed PC companion reconnect when using saved Windows Credential Manager pairing without re-entering the base URL.
- Added unit tests for the PC frame protocol and payload bounds; CI now validates OCR dependencies and Kotlin compile.


## Paired-device task handoff (2026-11-19)
- Added expiring 15-minute task-plan relay between paired devices. The sender explicitly chooses a target and confirms sharing; receiver must accept or reject.
- Server bounds task title, description and steps, relays only within the authenticated device session, and does not execute the plan.
- Receiver stores accepted plans in an Android Keystore AES-GCM encrypted Task Inbox (maximum 10 tasks); sender receives an acknowledgement.
- Task handoff is online-only and does not queue tasks for offline devices. Plans are informational and do not auto-run app actions.

## Latest verification
- GitHub Actions run 44482262665 succeeded for code commit d357b1db10a345cccbfb33a7de3b238f6063669c: Android unit tests, Debug APK build, backend checks, custom voice proxy tests, Python companion tests and artifact upload.
- Artifact: shraddha-ai-debug-apk, 44,598,886 bytes; SHA-256 e60a761f31ec4647f1392c7e9db03c640aedda7c7731e459968aa028f3284081.


## Safety hardening follow-up (2026-11-19)
- Installed-app launch now requires a unique exact label match, or a unique package among partial matches; ambiguous names are refused.
- Accessibility text targeting now preserves separate same-label controls by including screen bounds in candidate identity.
- Resource-ID clicks now require exactly one visible, enabled, clickable node instead of selecting the first duplicate ID.
- Workflow resume confirmation is scoped to the single checkpointed waiting step. A single confirmation can no longer authorize later destructive steps in the same workflow.
- Added unit tests for sensitive-field blocking, payment-security fields, external side-effect confirmation, security-setting blocks and normal navigation.
- CI verification for these follow-up changes is pending; these source changes still require real-device accessibility validation.


## Per-step workflow approval UI (2026-11-19)
- Connected the workflow runner's WAITING_CONFIRMATION state to a real Android confirmation dialog.
- The dialog approves only the current checkpointed step; each later risky step prompts again.
- Resume continues from the verified checkpoint rather than restarting the previous action. Declining leaves the plan stopped; it does not execute.

- Resource-ID text entry now also requires exactly one visible, editable, non-sensitive field; duplicate IDs are refused rather than filling the first match.


## People Memory model packaging follow-up (2026-11-19)
- Android Gradle now downloads the real OpenCV Zoo SFace 2021dec and ECAPA-TDNN ONNX weights from pinned upstream revisions.
- Build-time SHA-256 checks reject unexpected or corrupted model files. The weights are packaged in APK assets; the app verifies them again when copying into app-private storage before inference.
- Third-party notices and Apache-2.0 text are packaged alongside the model assets.
- The first People Memory capture installs both models on a worker thread; the app does not copy the large model files at every ordinary startup.
- Active blink/head-turn challenge now requires stable consecutive frames and expires after 45 seconds. It remains a heuristic, not certified PAD. A certified claim still requires a licensed PAD SDK/product and independent ISO/IEC 30107-3 evidence for the exact deployed version, plus physical-device validation.
- Android CI must complete the full model download, checksum validation, unit tests and APK packaging before the updated APK can be marked verified.


## People Memory live capture and model smoke-test follow-up (2026-11-19)
- CameraX face enrollment now runs a randomized blink + opposite head-turn challenge, rejects ambiguous/multiple-face frames, resets progress on missing/invalid frames, requires a centered face before capture, aligns five landmarks using a similarity transform, checks brightness/sharpness and discards the temporary image.
- Microphone enrollment now runs an on-device Hindi/English phrase challenge, records five seconds of mono 16 kHz PCM, checks voice activity/clipping, creates an ECAPA embedding and zeroes raw PCM/normalized buffers after inference or cancellation.
- People Memory requires an explicit per-session consent confirmation, resets the switch after each capture/match launch and limits matching to the selected consented profile. Encrypted vault read failures fail closed; deletion covers indexed and orphaned templates.
- Added unit coverage for liveness sequencing/expiry/invalid frames and five-point similarity alignment, plus an Android instrumentation smoke test that loads both bundled ONNX models and runs synthetic-input inference.
- Added GitHub Actions Android emulator step for connected instrumentation tests. The GitHub connector currently reports no run/status for the latest branch commits, so this CI is configured but not yet verified.
- Physical Android camera/microphone, consenting-person accuracy, FAR/FRR/ROC/EER and spoof/replay testing remain outstanding; the active challenge is not certified PAD.


## 2026-11-19 — People Memory hardening, real model diagnostics and recovery transactions
- Face and speaker enrollment/matching now reports calibrated similarity/threshold and measured model load/inference latency.
- SFace/ECAPA inference clears direct native input buffers and temporary output embeddings after inference. The vault clears normalized enrollment/probe/template arrays after save, matching and calibration.
- Enrollment saves the encrypted person profile and index in one SharedPreferences commit. Consent withdrawal atomically removes face/voice templates, both thresholds, and the person directory entry.
- Added an Android instrumentation test for encrypted multi-key write/delete atomicity and an end-to-end People Memory consent/calibration/selected-profile/withdrawal flow.
- Liveness requires consecutive same-direction head-turn frames and monotonic timestamps. The voice challenge now creates fresh three-word Hindi/English phrases rather than choosing from a short fixed list. This reduces trivial replay but is not certified PAD.
- Added a physical-device validation protocol covering device matrix, consenting participants, genuine/impostor trials, FAR/FRR/EER, replay and deepfake attacks. It is a protocol only, not measured results.

## 2026-11-19 — App-training privacy and full recovery
- Legacy plaintext app-training profiles are migrated to the Android Keystore-backed encrypted feature store; corrupt migration fails closed and leaves the old records intact.
- EncryptedFeatureStore now supports family-scoped decrypted snapshots and atomic replace/delete transactions.
- New recovery bundle v2 includes selected person/child/task records, app-training profiles, encrypted Expert Project Library, workflow checkpoints, active project, digital twin, knowledge graph and private memory. It excludes biometric templates, API credentials, pairing tokens, OTPs and payment data.
- Recovery validates allowed keys and data shapes, previews overwrite scope, and attempts rollback of all selected collections/preferences if a restore operation fails.
- Local passphrase-protected backup v3 and Firebase Firestore/Storage restore share the same validated recovery format. Firestore supports 100 KB plaintext bundles; Firebase Storage supports up to 1.4 MB plaintext / 2 MB encrypted objects with 10 retained revisions.

## 2026-11-19 — Firebase Storage, App Check and FCM
- Firebase Storage encrypted upload/download and timestamped revision history are wired to owner-scoped Firestore metadata and storage.rules.
- Firestore rules now include owner-only Storage metadata and device-token records; emulator rules tests cover owner isolation and invalid object metadata.
- Firebase App Check is installed on both Firebase app instances: Debug provider for debug builds and Play Integrity provider for release builds. Debug tokens must be registered; Play Integrity signing/configuration is required before enforcing production App Check.
- FCM auto-init is disabled in the manifest until explicit opt-in. Tokens are stored encrypted locally and in the owner's private Firestore device collection. Token rotation cleanup, generic-only notifications and user revocation are implemented.
- Outbound FCM sending is not part of this Android client; use Firebase Console data-only messages or a separately secured trusted sender. Notification payloads containing sensitive content must not be sent.

## 2026-11-19 — Voice social publishing
- The voice tool now publishes user-approved text posts to Telegram, Mastodon, Bluesky and LinkedIn through the authenticated backend.
- Draft mode is non-publishing. Publish mode first returns an exact platform/text preview and requires explicit approval with exact matching platform and text. Media, Reels, Stories and scheduled posts remain unsupported.
- Platform length rules match backend counting semantics; cancellation of the voice task cancels the in-flight HTTP request.

## Verification status
- These source changes are committed to redesign-v0.19.2; the GitHub connector still reports no workflow run or commit status for the latest branch head. Android compilation, unit tests, emulator ONNX smoke tests, Firebase emulator rules tests and APK artifact creation therefore remain **not verified**.
- Real Android camera/microphone tests, participant accuracy, FAR/FRR/EER and spoof evaluation require physical devices and consenting participants. No certified PAD claim is made.
- Firebase cloud tests require the user's Firebase project, bucket, App Check configuration and published owner-only rules. No credentials or live cloud account were used in this source audit.
- PR #2 remains open/unmerged and is one commit behind main; no direct changes were made to main.

- Expert Hub's prior plaintext StringSet project library is migrated to an Android Keystore-backed encrypted ordered library (up to 20 projects), included in consented recovery snapshots.
