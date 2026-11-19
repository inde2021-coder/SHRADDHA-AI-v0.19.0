# Lia + MYRA integration status (v0.10.0)

This release is a multi-platform source bundle, not a claim that every upstream feature is already merged into one Android APK.

## Lia Android source preserved
`integrations/lia-android-source/` contains the upstream Kotlin/Compose source: Gemini Live voice, animated orb UI, chat/history, personality/preferences, quick actions, Android app launch/communication intents, Accessibility-based UI action engine, social-share task state machine, and Website Forge UI/client. It is retained as a separate source tree because it uses a different package (`com.Lia.assistant`), Compose navigation architecture, app flavors, and manifests. Blindly copying it into Shraddha's `in.shraddha.ai` package would create duplicate launcher/services/resources and break builds. Its capabilities must be selectively ported and tested before being labelled native Shraddha features.

## MYRA desktop companion source preserved
`companions/myra-desktop-source/` contains the MYRA ULTRA Windows/Linux/Termux source: PyQt desktop assistant, voice/vision, screen sharing, PC actions, office/document utilities, MYRA Coder and website builder/deployment integrations, floating orb, and CLI/Termux variants. It runs as a separate desktop/companion application, not inside the Android APK. A paired, authenticated, encrypted companion protocol and per-action approval are required before Shraddha can control a PC.

## Feature boundary
- Android app operator, local encrypted memory, device linking, screen/camera sharing and expert workspaces remain in the Shraddha Android app as documented in its feature matrix.
- Lia and MYRA upstream source is included intact for porting/reuse; not all of its features are wired to Shraddha's UI or backend in this release.
- MYRA's Windows-only APIs cannot run on Android; Android and PC capabilities remain platform-specific.
- Remote PC/mobile control requires both devices online, pairing, explicit target-device consent and OS permissions. It cannot be guaranteed worldwide/offline.
- External API providers require the user's own API credentials and may enforce quotas/billing.
