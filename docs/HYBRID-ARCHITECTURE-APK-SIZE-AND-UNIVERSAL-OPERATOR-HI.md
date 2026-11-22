# SHRADDHA AI — Hybrid Architecture, APK Size और Universal Operator

**तारीख:** 23 नवम्बर 2026
**स्थिति:** Architecture decision और implementation plan; test report नहीं।

## 1. Hybrid architecture

Shraddha की एक ही identity, memory facade, permission manager और task router रहेगा। Execution चार जगह होगा:

- **Android Core:** UI/3D orb, voice controls, task router, consent/audit, secure settings, basic OCR/input bridge और lightweight local storage।
- **On-device:** microphone/camera capture, local OCR, accessibility semantics, छोटे intent models, cache और basic offline commands। बड़े AI models base APK में नहीं।
- **Cloud AI Gateway:** user-configured Gemini/OpenAI/Claude/Perplexity providers, long-context reasoning, image understanding, TTS, research और agent council। HTTPS, consent, quotas, timeout, provider fallback और audit IDs।
- **Windows Companion:** बड़े code projects, local GGUF, desktop OCR/control, build/test और बड़े files; device-bound pairing keys और explicit approvals के साथ।

Task Router capability, privacy, network, cost, latency और user preference देखकर route चुनेगा। Sensitive actions पर पहले approval और बाद में result verification होगा। Cloud न होने पर केवल सच में installed local capabilities चलेंगी—fake offline AI नहीं।

## 2. APK size कम करने का तरीका

### Base APK
Core UI, assistant identity, voice start/stop, permission/disclosure, task router, secure settings, basic intents, lightweight storage और capability registry।

### Optional modules/model packs
Advanced Visual Lens, biometric/People Memory models, offline speech/custom TTS, offline LLM/embeddings, Creator Studio और advanced PC tooling।

- Google Play के लिए Android App Bundle + Play Feature Delivery dynamic feature modules अपनाएँ, ताकि optional code जरूरत पर install हो।
- Direct sideload APK में Play dynamic delivery उपलब्ध नहीं। वहाँ executable code को runtime पर download करके चलाने के बजाय signed app update से दें; अलग से केवल signed model/data packs डाउनलोड करें।
- Release में पहले से enabled R8 minification/resource shrinking बनाए रखें।
- ABI-specific APK (जैसे arm64-v8a) और अलग universal APK दें।
- APK Analyzer से DEX, native .so, assets और resources का वास्तविक size baseline निकालें।
- Unused resources/dependencies हटाएँ; ML Kit/ONNX/LiteRT को optional module में ले जाने की feasibility मापें।
- बड़े ONNX/LiteRT/voice/vision model files base assets में embed न हों।
- Model manifest में ID, version, size, SHA-256, license, supported ABI/device, minimum storage और rollback version हो। Download resumable हो; checksum verify होने के बाद atomic activation हो।
- Model absent/unsupported/network failure पर स्पष्ट संदेश दें, crash या fake result नहीं।

## 3. Stonic Camera Eye / Live Eyes

मौजूदा source में `LiveEyesCameraService`, `VisualLensScreen`, `RemoteCameraService` और vision backend मौजूद हैं। Live Eyes user-started foreground camera service है; camera permission, visible notification, HTTPS gateway और gateway token जरूरी हैं। यह low-resolution frames backend को भेजता है और observations voice context में देता है। इसे offline camera AI नहीं कहना है।

आगे इसे पूरा करने के लिए:
- Lifecycle: OFF → REQUESTING_PERMISSION → STARTING → ACTIVE → PAUSED → ERROR; stop/revocation पर camera और buffers release।
- Local-first: blur/quality check और local OCR; cloud vision केवल user-enabled mode और visible indicator के साथ।
- Modes: Ask once, Live Eyes, Read text, Translate, Identify object, Scene change।
- Battery/network throttling, backpressure, timeout, bounded retry और stale-frame rejection।
- Frames resize/compress हों, memory buffers सीमित रहें, callback के बाद byte arrays zero हों; default रूप से frames persist न हों।
- Notification में STOP action और screen पर active state/last observation।
- No covert recording, no hidden camera, no face identity inference।
- Hindi/English response और user question context।
- Tests: permission denied/revoked, camera busy, background, screen lock, network loss, backend timeout, stop while request in flight, battery saver।

## 4. Universal App Control

“हर app में 100% guaranteed control” Android security, app UI और protected screens के कारण संभव नहीं। Execution order:
1. Official app intents, deep links, share sheet और public APIs।
2. App-specific connectors जहाँ उपलब्ध हों।
3. User-invoked IME/voice input और Android Autofill।
4. User-enabled Accessibility से visible UI labels, editable fields और navigation; clear disclosure, narrow scope और confirmation।
5. OCR/screen understanding जब semantic accessibility tree न मिले; coordinate tap आखिरी विकल्प और user-visible confirmation के साथ।
6. Payment, OTP, credential, protected और unsupported screens पर manual handoff; security controls bypass नहीं।

हर action का audit record: target package, screen state/hash, matched element, planned action, approval, execution और verification result। Payment/purchase/legal filing/message send/data deletion/account changes पर explicit approval। Password/OTP/banking credentials model prompt या logs में नहीं जाएँ।

**Google Play policy:** Accessibility API को unrestricted autonomous agent की तरह इस्तेमाल नहीं करना है। Prominent disclosure, affirmative consent, user-directed narrow actions और जहाँ लागू हो Play declaration/approval जरूरी हैं। General assistant को गलत तरीके से disability accessibility tool label नहीं करना है।

## 5. TerraMark

Repository tree में TerraMark नाम का स्वतंत्र source module या अलग TerraMark app project नहीं मिला। पिछली चर्चा में “TerraMark Smart Entry AI” नाम की basic form screen का उल्लेख था, लेकिन उसके fields, backend, workflows या app-specific API का source/spec उपलब्ध नहीं है।

अभी निश्चित काम:
- TerraMark को Shraddha Universal Operator के target-app profile के रूप में शामिल करना।
- Profile में package ID, screen labels, safe navigation, form field aliases, allowed actions और confirmation rules।
- Screen understanding, Hindi/English dictation, form preview/edit, undo जहाँ supported हो और submit से पहले user approval।
- यदि TerraMark का अलग source repository मिले तो native API integration करें; बिना source के buttons/database workflow न गढ़ें।
- TerraMark Smart Entry workspace अलग रहे; वह Shraddha Home का replacement नहीं बने।

## 6. Implementation order

1. Source/dependency inventory और APK size baseline (अभी tests चलाए बिना)।
2. Camera Eye lifecycle/privacy/backpressure सुधार।
3. Universal Operator service ownership, action policy, app profiles, verification और approval UX।
4. TerraMark target profile और Smart Entry workspace boundary।
5. Hybrid Task Router और capability registry।
6. Signed model pack manager: download, checksum, rollback और storage checks।
7. Gradle modularization: Play AAB dynamic features; sideload APK base-compatible path।
8. Stonic के agent orchestration, research, plugin, file transfer और PC control gaps।
9. सभी feature work पूरा होने के बाद consolidated testing; failures fix करके targeted retest।

## 7. Acceptance criteria

- Base APK में बड़े model files नहीं; core UI/voice model pack के बिना launch हो।
- Optional model absent/unsupported होने पर स्पष्ट status।
- Camera Eye user-started, foreground indicator visible और STOP पर camera बंद।
- Camera frames default रूप से persist न हों।
- Operator user intent के बिना action न करे; sensitive actions approval के बिना न हों।
- Unsupported app/screen पर manual step माँगा जाए।
- TerraMark app-specific flow तभी complete जब वास्तविक app/package और user-approved end-to-end workflow verify हो।
- Phone और Windows real-device validation के बिना device integration production-ready न कहलाए।

## 8. वर्तमान source audit facts

- `app/build.gradle.kts` में Release R8 minification और resource shrinking enabled हैं।
- CameraX, ML Kit face detection, ONNX Runtime, ML Kit text recognition (Latin/Devanagari), LiteRT-LM और Firebase अभी base app dependency graph में हैं; APK Analyzer से actual size contribution मापना होगा।
- Release में `shraddhaAbi` property से ABI filter का विकल्प है।
- `LiveEyesCameraService` Camera2 headless foreground service है और HTTPS gateway पर frames भेजती है।
- Manifest में दो AccessibilityService declarations हैं: `ShraddhaAccessibilityService` और `NovaAccessibilityService`; overlapping duties/ownership audit करें। Behavior समझे बिना कोई service न हटाएँ।
- TerraMark-named source file/module repository tree में नहीं मिला।

यह दस्तावेज़ implementation का विकल्प या test pass report नहीं है।
