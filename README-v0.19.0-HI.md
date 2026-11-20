# SHRADDHA AI v0.19.0 — Android + Windows PC Companion

यह v0.18.0 का updated source है। Android पर केवल SHRADDHA AI app/launcher है। LIA का Android voice/Compose experience उसी APK में internal Activity के रूप में जोड़ा गया है।

## मुख्य स्थान
- `app/src/main/java/in/shraddha/ai/` — SHRADDHA core, Universal App Operator, security, Firebase, device link और vision.
- `app/src/main/java/com/Lia/assistant/` — उसी APK में merged LIA voice, Compose UI, live audio, memory/personality, Forge और accessibility agent.
- `pc_companion/` — SHRADDHA AI का अलग Windows PC Companion; Android Device Universe से HTTPS relay के जरिए pair होता है।
- `desktop-companion/MYRA_ULTRA_APP/` — MYRA desktop assistant/Coder/Website Forge source. यह Windows PC Companion executable से अलग codebase है।
- `docs/integration/LIA-MYRA-FULL-MERGE-v0.19.0-HI.md` — merge ledger और boundaries.
- `pc_companion/README.md` — Windows companion setup और safety boundaries.
- `desktop-companion/README-SHRADDHA-MYRA-PAIRING-HI.md` — MYRA source/architecture notes.

## नवीनतम सत्यापित build

**Android CI run #280**

- Commit: `d8b9af6d9b780c0ce97fd9b471ce65455adfd272`
- Android unit tests: PASS.
- Backend JavaScript syntax और security/API tests: PASS.
- Debug APK build: PASS.
- `arm64-v8a` और `armeabi-v7a` APK generation तथा per-APK ABI verification: PASS.
- SHA-256 checksum verification: PASS.
- Artifact: `shraddha-ai-debug-apks-arm`, ID `14603898058`.
- [पूरा Android CI परिणाम](https://github.com/inde2021-coder/SHRADDHA-AI-v0.19.0/actions/runs/44680183713)

### Windows PC Companion
- Windows Python tests: PASS (13 tests).
- Windows EXE packaging: PASS.
- [Windows CI run #7](https://github.com/inde2021-coder/SHRADDHA-AI-v0.19.0/actions/runs/44679699614)
- Artifact: `ShraddhaAI-PC-Companion-Windows`, ID `14603647797`.
- EXE Android APK में शामिल नहीं है; Windows PC पर अलग चलाना होगा।

## Build

Android Studio में repository root खोलकर `:app:assembleDebug` चलाएँ। JDK 17 और Android SDK 35 आवश्यक हैं। Windows companion के लिए `pc_companion/README.md` देखें।

## Required device permissions

Microphone, notifications, Accessibility Service, overlay, camera और media access केवल संबंधित feature के लिए माँगे जाते हैं। Cross-app automation के लिए Android Settings में Accessibility manually enable करें। Windows PC control के लिए PC Companion pair करें और PC पर remote-control permission दें।

## महत्वपूर्ण सीमाएँ

- APK **debug build** है, production/release-signed APK या Play Store bundle नहीं।
- वास्तविक Android फोन पर camera/microphone, calls/SMS, background restrictions, Accessibility actions और app compatibility का hardware परीक्षण अभी आवश्यक है।
- Firebase Authentication, Firestore और Realtime Database को Firebase Console में enable करके rules publish करने होंगे।
- People Memory similarity matching है; इसे प्रमाणित पहचान या certified anti-spoofing न समझें।
- AI provider selector typed Chat के लिए है; लगातार Voice Live session अभी Gemini Live का उपयोग करता है।
- Social share केवल Android Sharesheet draft खोलता है; अंतिम publish/send उपयोगकर्ता करता है।
- API keys, Firebase credentials और Windows pairing tokens को source control में commit न करें।

Source integration और CI pass का अर्थ यह नहीं कि हर feature हर फोन पर बिना permission, network, provider key या companion के स्वतः काम करेगा। विस्तृत स्थिति के लिए `docs/BUILD-TEST-REPORT-v0.19.0.md` देखें।
