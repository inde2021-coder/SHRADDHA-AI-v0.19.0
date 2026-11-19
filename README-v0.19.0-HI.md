# SHRADDHA AI v0.19.0 — Unified Android + MYRA PC Companion

यह v0.18.0 का updated source है। Android पर केवल SHRADDHA AI app/launcher है। LIA का Android voice/Compose experience उसी APK में internal Activity के रूप में जोड़ा गया है। MYRA का Windows/Linux companion `desktop-companion/` में है और SHRADDHA के Device Universe से pair किया जाना है।

## मुख्य स्थान
- `app/src/main/java/in/shraddha/ai/` — SHRADDHA core, Universal App Operator, security, Firebase, device link, vision.
- `app/src/main/java/com/Lia/assistant/` — उसी APK में merged LIA voice, Compose UI, live audio, memory/personality, Forge, accessibility agent.
- `desktop-companion/MYRA_ULTRA_APP/` — MYRA Ultra desktop companion, MYRA Coder and Website Forge.
- `docs/integration/LIA-MYRA-FULL-MERGE-v0.19.0-HI.md` — merge ledger and boundaries.
- `desktop-companion/README-SHRADDHA-MYRA-PAIRING-HI.md` — MYRA PC setup.

## Build
Open this project root in Android Studio. Use JDK 17 and Android SDK 35. Allow Gradle to download dependencies, then run `:app:assembleDebug`. Latest GitHub Actions run 44482262665 passed Android unit tests and debug APK build for commit d357b1db10a345cccbfb33a7de3b238f6063669c. Debug APK artifact is available from the linked Actions run. Physical-device testing is still required. Configure Firebase and AI provider keys using the documented local configuration; never commit secrets.

## Secure Gemini Live Voice setup (v0.19.2)
1. Backend में `GATEWAY_TOKEN` और `GEMINI_API_KEY` server environment variables में set करें। Permanent Gemini key APK में न डालें।
2. Backend deploy/restart करें; `POST /api/ai/gemini-live-token` route authenticated one-use, short-lived Live token देता है।
3. Android Settings → Secure Voice Backend में HTTPS backend URL और वही gateway token save करें। Gateway token Android Keystore से encrypted है।
4. Gemini Live अब केवल Hindi/English conversation reasoning और text response देता है; spoken output अलग Shraddha Speech Engine से आता है। Android app Google/Gemini voice या system TTS पर fallback नहीं करती।
5. Backend API key rotation और rate limiting अपने hosting dashboard में करें; gateway token को किसी public repository में commit न करें।

## Shraddha की अपनी गैर-Google आवाज़
1. Backend .env में SHRADDHA_TTS_URL, optional SHRADDHA_TTS_TOKEN और SHRADDHA_TTS_VOICE=shraddha सेट करें।
2. यह HTTPS endpoint एक licensed custom bilingual voice model होना चाहिए और POST पर text, language, voice, pcm_s16le, 24000 Hz, mono input स्वीकार करे।
3. Response raw mono PCM signed-16 little-endian, 24 kHz होना चाहिए (audio/pcm या application/octet-stream)।
4. Gemini Live अब audio generation नहीं करता; वह text देता है। App उस text को authenticated /api/voice/synthesize endpoint पर भेजती है और लौटे PCM को सीधे play करती है।
5. कोई Google voice fallback नहीं है। TTS endpoint configure न होने पर app साफ error दिखाएगी। Standard Piper voice model अपने-आप uniquely Shraddha voice नहीं बनता; branded identity के लिए licensed/custom trained model deploy करें। किसी वास्तविक व्यक्ति की आवाज़ clone करने के लिए उसकी स्पष्ट अनुमति और उचित अधिकार जरूरी हैं।

## Persistent voice memory
Settings → Encrypted Voice Memory default में OFF है। ON करने पर हाल के अधिकतम 12 voice turns Android Keystore AES-GCM से local encrypted storage में save होते हैं। OTP/password/PIN जैसे शब्द या 4–8 digit code वाले turns save नहीं किए जाते। OFF करने पर saved voice-history ciphertext हटता है।

## Voice से दूसरे Apps में सीखे हुए काम
1. Quick Actions → **Control Any App** → **App Training / सीखने की लैब** खोलें।
2. जिस ऐप में काम सिखाना है उसे पहले स्वयं खोलें; Accessibility में **Shraddha AI — Device Control** चालू रखें।
3. स्क्रीन के बटन/फील्ड को पहचानकर workflow steps जोड़ें और नाम देकर सेव करें। केवल सामान्य non-sensitive fields उपयोग करें।
4. Voice में कहें: “मेरे saved app workflows दिखाओ”; Shraddha `list_app_workflows` से उपलब्ध ऐप और workflow बताएगी।
5. फिर exact app और workflow नाम के साथ चलाने को कहें। Shraddha हर चरण पर स्क्रीन फिर पढ़ेगी, target दोबारा खोजेगी और परिणाम verify करेगी।
6. Workflow सुरक्षित चरणों से आगे बढ़ेगा; Send/Delete/Pay/Publish/Submit जैसे जोखिम वाले चरण पर उसी चरण के लिए पुष्टि माँगी जाएगी। Voice में `confirmation_required` आने पर पुष्टि लें, फिर exact workflow को `resume=true` और `user_confirmed=yes` के साथ चलाएँ। OTP/PIN/password/UPI security fields block रहेंगे।
7. किसी ऐप के UI में बदलाव हो तो उसका workflow फिर से train/verify करें; पुराने coordinates replay नहीं किए जाते।
8. Operator Console में workflow चुनने पर यदि सुरक्षित checkpoint उपलब्ध है तो **Checkpoint से Resume** चुनें; अन्यथा **शुरू से चलाएँ**। Voice में saved workflows सूचीबद्ध कराकर उसी exact workflow को `resume=true` के साथ आगे बढ़ाया जा सकता है।
9. Resume केवल पहले से सत्यापित चरण के बाद से होता है। जिस चरण की कार्रवाई बीच में रुक गई हो, उसे सुरक्षा के लिए अपने-आप दोबारा नहीं चलाया जाता। हर अगले जोखिम वाले चरण पर अलग पुष्टि माँगी जाती है।

Voice operator tools: screen पढ़ना, unique label पर tap, safe field में text भरना, scroll, directional swipe, Back और Home। Accessibility text कम होने पर opt-in local OCR fallback उपलब्ध है; OCR-only custom canvas पर tap अभी उपलब्ध नहीं है।

## Windows PC companion
`desktop-companion/shraddha-pc-companion/README.md` देखें। यह Windows Credential Manager में pairing credentials रखता है, remote control के लिए PC पर explicit approval माँगता है, और text paste default में disabled रखता है।

## Communication safety
Voice-authorized call के लिए Contacts/Phone permissions आवश्यक हैं। SMS या WhatsApp भेजने से पहले श्रद्धा resolved phone number और पूरा message preview देती है। उपयोगकर्ता की स्पष्ट अनुमति के बाद ही वही exact number और exact message दोबारा भेजने पर स्वीकार होता है; recipient या message बदलने पर नई पुष्टि आवश्यक है। SMS permission और WhatsApp Accessibility verification भी आवश्यक हैं। WhatsApp chat reading/search उपलब्ध नहीं है। Social publishing में Telegram, Mastodon, Bluesky और LinkedIn पर केवल text post supported है; पहले exact platform और पूरा text preview दिखाकर आपकी स्पष्ट अनुमति जरूरी है। Media uploads, Reels, Stories, Instagram, Facebook, YouTube और X अभी connected नहीं हैं।

## Required device permissions
Microphone, notifications, Accessibility Service, overlay, camera, and media access are requested only for features that need them. Enable “Shraddha AI — Device Control” manually in Android Settings for voice-driven app control. The optional “Advanced App Operator” service is currently used only for WhatsApp send verification. Android secure screens, OTP/PIN/password fields and apps that block Accessibility remain unavailable. PC control needs a paired MYRA desktop companion.

## Important
Source integration is not a claim that every capability works without credentials, permissions, network, desktop companion, or external provider APIs. See `docs/BUILD-TEST-REPORT-v0.19.0.md` for what was and was not verified.


## Paired-device Task Handoff
Device Control → Remote App Control में Task Handoff title, description और steps भरकर चुने हुए paired phone को task plan भेजें। Receiver 15 मिनट के अंदर Accept & Save या Reject कर सकता है। स्वीकार किया गया plan Android Keystore-backed EncryptedFeatureStore में local encrypted Task Inbox में save होता है। Inbox में अधिकतम 10 task plans रखे जाते हैं। यह feature task plan भेजता है, remote app action स्वतः execute नहीं करता।


## People Memory: वास्तविक Face और Voice मॉडल
- Android build के समय OpenCV Zoo का SFace ONNX और SpeechBrain ECAPA-TDNN का ONNX मॉडल pinned upstream revision से download होता है।
- दोनों model files का SHA-256 verify होता है; checksum गलत होने पर build रुकता है।
- दोनों वास्तविक weights APK के assets में पैक होते हैं और People Memory के पहले उपयोग पर app-private storage में दोबारा verify होकर install होते हैं।
- मॉडल की license/attribution सूचना APK में शामिल है। इस कारण APK का आकार लगभग 123 MB model weights से बढ़ेगा।
- Blink/head-turn challenge को मजबूत किया गया है, लेकिन यह अभी certified anti-spoofing नहीं है। Certified PAD के लिए licensed SDK और ISO/IEC 30107-3 independent test evidence आवश्यक है।
- GitHub CI और source code अकेले वास्तविक फोन की accuracy या certification साबित नहीं करते; target phones पर FAR/FRR और spoof परीक्षण बाकी रहेंगे।


## Firebase Storage और Push Notifications
- Firebase Firestore पर छोटे encrypted recovery bundles (100 KB plaintext तक) और Firebase Storage पर बड़े encrypted recovery files (1.4 MB plaintext तक) upload/download किए जा सकते हैं।
- Recovery में person/child records, encrypted app-training profiles, workflow checkpoints, task inbox, active project, digital twin, knowledge graph और private memory शामिल होते हैं। Face/voice biometric templates, API keys, OTPs, payment details और pairing credentials शामिल नहीं होते।
- Restore से पहले overwrite preview आता है; selected encrypted record families को atomic तरीके से replace किया जाता है और अन्य preferences में failure होने पर rollback का प्रयास होता है।
- Firebase Cloud Messaging default में बंद है। User opt-in के बाद ही device token generate/register होता है। Token encrypted local storage और owner-only Firestore device record में रहता है; revoke button token और device record हटाता है।
- FCM receiver केवल generic, non-sensitive notifications दिखाता है। Outbound push delivery के लिए Firebase Console या अलग सुरक्षित trusted server चाहिए।
- Setup के लिए `firebase/README.md`, `firestore.rules` और `storage.rules` देखें। Firebase credentials, Storage bucket, App Check और owner-only rules configure किए बिना cloud features काम नहीं करेंगे।

## App-training profile encryption
पुराने plaintext app-training profiles को पहली बार उपयोग या backup पर Android Keystore-backed encrypted feature store में migrate किया जाता है। Recovery bundle में trained app profiles और workflow recipes encrypted रूप में शामिल होते हैं।

- Expert Hub की My Project Library अब Android Keystore-backed encrypted storage में है, पुरानी plaintext library पहली बार खुलने/backup पर migrate होती है, और अधिकतम 20 projects क्रमबद्ध रूप से सुरक्षित रहते हैं।
