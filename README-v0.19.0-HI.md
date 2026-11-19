# SHRADDHA AI v0.19.0 — Unified Android + MYRA PC Companion

यह v0.18.0 का updated source है। Android पर केवल SHRADDHA AI app/launcher है। LIA का Android voice/Compose experience उसी APK में internal Activity के रूप में जोड़ा गया है। MYRA का Windows/Linux companion `desktop-companion/` में है और SHRADDHA के Device Universe से pair किया जाना है।

## मुख्य स्थान
- `app/src/main/java/in/shraddha/ai/` — SHRADDHA core, Universal App Operator, security, Firebase, device link, vision.
- `app/src/main/java/com/Lia/assistant/` — उसी APK में merged LIA voice, Compose UI, live audio, memory/personality, Forge, accessibility agent.
- `desktop-companion/MYRA_ULTRA_APP/` — MYRA Ultra desktop companion, MYRA Coder and Website Forge.
- `docs/integration/LIA-MYRA-FULL-MERGE-v0.19.0-HI.md` — merge ledger and boundaries.
- `desktop-companion/README-SHRADDHA-MYRA-PAIRING-HI.md` — MYRA PC setup.

## Build
Open this project root in Android Studio. Use JDK 17 and Android SDK 35. Allow Gradle to download dependencies, then run `:app:assembleDebug`. Build has not been verified in this execution environment because Gradle distribution download was blocked by DNS/network. Configure Firebase and AI provider keys using the documented local configuration; never commit secrets.

## Secure Gemini Live Voice setup (v0.19.2)
1. Backend में `GATEWAY_TOKEN` और `GEMINI_API_KEY` server environment variables में set करें। Permanent Gemini key APK में न डालें।
2. Backend deploy/restart करें; `POST /api/ai/gemini-live-token` route authenticated one-use, short-lived Live token देता है।
3. Android Settings → Secure Voice Backend में HTTPS backend URL और वही gateway token save करें। Gateway token Android Keystore से encrypted है।
4. Voice screen अब permanent Gemini API key के बजाय short-lived token से Live WebSocket खोलता है। Backend/Gemini configuration न होने पर voice start स्पष्ट error देगा।
5. Backend API key rotation और rate limiting अपने hosting dashboard में करें; gateway token को किसी public repository में commit न करें।

## Persistent voice memory
Settings → Encrypted Voice Memory default में OFF है। ON करने पर हाल के अधिकतम 12 voice turns Android Keystore AES-GCM से local encrypted storage में save होते हैं। OTP/password/PIN जैसे शब्द या 4–8 digit code वाले turns save नहीं किए जाते। OFF करने पर saved voice-history ciphertext हटता है।

## Voice से दूसरे Apps में सीखे हुए काम
1. Quick Actions → **Control Any App** → **App Training / सीखने की लैब** खोलें।
2. जिस ऐप में काम सिखाना है उसे पहले स्वयं खोलें; Accessibility में **Shraddha AI — Device Control** चालू रखें।
3. स्क्रीन के बटन/फील्ड को पहचानकर workflow steps जोड़ें और नाम देकर सेव करें। केवल सामान्य non-sensitive fields उपयोग करें।
4. Voice में कहें: “मेरे saved app workflows दिखाओ”; Shraddha `list_app_workflows` से उपलब्ध ऐप और workflow बताएगी।
5. फिर exact app और workflow नाम के साथ चलाने को कहें। Shraddha हर चरण पर स्क्रीन फिर पढ़ेगी, target दोबारा खोजेगी और परिणाम verify करेगी।
6. Send/Delete/Pay/Publish/Submit जैसे जोखिम वाले workflow के लिए पहले आपसे स्पष्ट पुष्टि ली जाएगी। OTP/PIN/password/UPI security fields block रहेंगे।
7. किसी ऐप के UI में बदलाव हो तो उसका workflow फिर से train/verify करें; पुराने coordinates replay नहीं किए जाते।

Voice operator tools: screen पढ़ना, unique label पर tap, safe field में text भरना, scroll, directional swipe, Back और Home। जिन ऐप्स में Accessibility nodes उपलब्ध नहीं हैं, वहाँ OCR/canvas fallback अभी नहीं है।

## Windows PC companion
`desktop-companion/shraddha-pc-companion/README.md` देखें। यह Windows Credential Manager में pairing credentials रखता है, remote control के लिए PC पर explicit approval माँगता है, और text paste default में disabled रखता है।

## Communication safety
Voice call Android dialer खोलती है; अंतिम Call user करता है। SMS/WhatsApp केवल draft composer खोलते हैं; Send user को खुद दबाना होता है। WhatsApp chat reading/search, social media publishing और background auto-send अभी implemented नहीं हैं।

## Required device permissions
Microphone, notifications, Accessibility Service, overlay, camera, and media access are requested only for features that need them. Enable “Shraddha AI — Device Control” manually in Android Settings for voice-driven app control. The optional “Advanced App Operator” service is currently used only for WhatsApp send verification. Android secure screens, OTP/PIN/password fields and apps that block Accessibility remain unavailable. PC control needs a paired MYRA desktop companion.

## Important
Source integration is not a claim that every capability works without credentials, permissions, network, desktop companion, or external provider APIs. See `docs/BUILD-TEST-REPORT-v0.19.0.md` for what was and was not verified.
