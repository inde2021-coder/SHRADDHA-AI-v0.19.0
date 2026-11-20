# SHRADDHA AI v0.19.0 — LIA + MYRA एकीकृत संरचना

## मुख्य निर्णय
- मोबाइल पर केवल एक Android application ID: `in.shraddha.ai` और एक launcher: SHRADDHA AI.
- LIA का Android implementation अब उसी `:app` source set में `com.Lia.assistant.*` internal namespaces के रूप में compile होने के लिए शामिल है। यह अलग APK/module नहीं है।
- LIA का मूल Compose UI अब `com.Lia.assistant.MainActivity (internal Activity)` के रूप में उसी SHRADDHA APK के भीतर खुलता है।
- MYRA का Windows/Linux/Python/Node companion `desktop-companion/MYRA_ULTRA_APP/` में paired companion है; यह अलग मोबाइल ऐप नहीं है।
- शिक्षा/कोर्स/स्टूडेंट/टीचर फीचर शामिल नहीं किए गए हैं।

## LIA से Android app में शामिल source modules
- Compose Home/Chat/Voice/History/Settings/Profile/Personality/Privacy/Permissions/Quick Actions/Orb UI.
- 3D Orb, listening edge glow, splash and depth/theme components.
- Gemini Live audio client, live audio I/O, Hindi/English language detection, voice session manager, echo guard, latency tracker, tool-call deduplication.
- Conversation memory, personality prompt builder, API key preferences.
- Forge intent/controller, website forge client, SSE parser, preview/result UI.
- App launcher, contacts resolver, communication intents, tool catalog and capability prompts.
- Accessibility-based screen driver, task state machine, UI action executor and share-to-assistant activity.
- Foreground microphone service and its manifest registration.

## MYRA companion capabilities retained
- MYRA Ultra desktop UI and orb, voice actions, app controls, screen sharing and camera vision.
- MYRA Linux and Termux companion source is retained under `desktop-companion/MYRA_LINUX/` and `desktop-companion/MYRA_TERMUX/`.
- MYRA Coder Node/TypeScript agent and website builder/WebForge integration.
- Coding tools, app integrations, office management utilities, preferences/language, guardian/security modules.
- Windows installer/build scripts and Linux/Termux companion source where included in the original desktop companion package.

## Shared SHRADDHA mobile entry points
- Home Command Center opens LIA Voice Engine from within SHRADDHA AI.
- Device Universe is the entry point for pairing and PC/phone companion management.
- Existing SHRADDHA Universal App Operator remains the primary mobile app automation engine; LIA's ScreenDriver/Accessibility agent is retained as a second capability implementation, not presented as a separate app.

## Important integration boundaries
- Android microphone, overlay, accessibility, contacts, calls, camera and notifications require runtime permission or explicit Settings enablement.
- MYRA desktop control requires the MYRA companion to be installed/running on the PC and paired securely. Android cannot execute Windows/Linux local commands without that companion.
- Gemini Live and Forge generation require provider credentials/network. The app must not report live service availability when credentials are absent.
- LIA's upstream direct-flavor license check is retained as source code, but it is not used to make SHRADDHA's launcher a separate app. The integration must still be reviewed for licensing/redistribution rights before public release.

## Verification status
- Source merge: done for LIA Android main + direct source sets.
- Single launcher/app ID: checked in manifest/source.
- Kotlin/Gradle build and Android unit tests: GitHub Actions CI पर PASS; ARM-specific debug APKs प्रकाशित।
- Device-level voice, accessibility, call/SMS, Gemini Live और PC pairing: अभी physical-device end-to-end testing बाकी है।
- Production signing और Play Store release: अभी नहीं किया गया।
