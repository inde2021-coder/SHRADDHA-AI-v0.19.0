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
- Voice-driven app control currently provides screen-observe + one-action tool calls. The learned multi-step workflows remain in the separate Operator/Training console; a voice-callable workflow planner with approval/resume checkpoints is not yet integrated.
- OCR/vision fallback for apps that expose no Accessibility nodes is not implemented. Screen capture and OCR need a separate consent-based path and privacy filtering.
- The optional WhatsApp compatibility service still needs real-device validation. The current send path is brittle because WhatsApp UI and recipient metadata vary by version/language; do not treat the feature as production-reliable until verified on the user's device.
- Generic social-media publishing, in-app search/form automation templates, file upload flows across arbitrary apps, notification action handling, clipboard control, app-specific API integrations and robust recovery from dialogs/errors remain incomplete.
- PC companion currently supports a limited consent-gated set of mouse/keyboard actions; it does not yet stream the PC screen or provide OCR, window-aware targeting, app-specific workflows or remote file management.
- The assistant's own non-Google bilingual voice is not integrated yet. Gemini Live currently returns Gemini-generated audio; the voice selector is a Gemini preset selector, not a custom Shraddha voice.
- Firebase cloud memory and paired-device/remote camera features still require real Firebase credentials, secure rules, a second device and end-to-end tests.
- Build CI does not replace installation and interaction testing on a physical Android phone.
