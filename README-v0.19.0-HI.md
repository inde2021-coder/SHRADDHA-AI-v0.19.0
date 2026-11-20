# SHRADDHA AI v0.19.0 — Unified Android + MYRA PC Companion

यह v0.18.0 का updated source है। Android पर केवल SHRADDHA AI app/launcher है। LIA का Android voice/Compose experience उसी APK में internal Activity के रूप में जोड़ा गया है। MYRA का Windows/Linux companion `desktop-companion/` में है और SHRADDHA के Device Universe से pair किया जाना है।

## मुख्य स्थान
- `app/src/main/java/in/shraddha/ai/` — SHRADDHA core, Universal App Operator, security, Firebase, device link, vision.
- `app/src/main/java/com/Lia/assistant/` — उसी APK में merged LIA voice, Compose UI, live audio, memory/personality, Forge, accessibility agent.
- `desktop-companion/MYRA_ULTRA_APP/` — MYRA Ultra desktop companion, MYRA Coder and Website Forge.
- `docs/integration/LIA-MYRA-FULL-MERGE-v0.19.0-HI.md` — merge ledger and boundaries.
- `desktop-companion/README-SHRADDHA-MYRA-PAIRING-HI.md` — MYRA PC setup.

## Build और वर्तमान सत्यापन
- JDK 17 / Android SDK 35 के साथ GitHub Actions पर Android unit tests सफल हुए।
- Debug APK build सफल हुआ; CI ने ARM-specific `arm64-v8a` और `armeabi-v7a` APK बनाए और उनके SHA-256 checksums verify किए।
- Artifact: GitHub Actions run #205, `shraddha-ai-debug-apks-arm` (14 दिन तक उपलब्ध)।
- यह **debug build** है, Play Store release-signed APK नहीं। फोन पर installation और वास्तविक hardware/runtime tests अभी अलग से करने होंगे।
- Android Studio में root खोलकर `:app:assembleDebug` चलाया जा सकता है। JDK 17 और Android SDK 35 आवश्यक हैं।
- Firebase और AI provider credentials को local configuration में रखें; private secrets GitHub में commit न करें।

## Required device permissions
Microphone, notifications, Accessibility Service, overlay, camera, and media access are requested only for features that need them. Enable Accessibility manually in Android Settings for cross-app automation. PC control needs a paired MYRA desktop companion.

## Important
Source integration is not a claim that every capability works without credentials, permissions, network, desktop companion, or external provider APIs. See `docs/BUILD-TEST-REPORT-v0.19.0.md` for what was and was not verified.
