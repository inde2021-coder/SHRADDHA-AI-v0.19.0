# Shraddha AI — Hybrid Module Boundaries (Hindi)

**तारीख:** 23 नवम्बर 2026  
**Branch:** `feature/complete-pending-shraddha-features`

## 1. Android APK — Core

Base Android app में केवल shared identity और essential control plane रहना चाहिए:
- Shraddha UI, 3D orb state, Hindi/English conversation shell
- Main Brain task router, capability registry और approval/audit
- Microphone ON/OFF lifecycle, permission/foreground-service handling
- Secure settings, encrypted local memory और provider selection
- Accessibility/Operator bridge और Device Link pairing shell
- HTTPS Cloud Gateway client; gateway URL/token Android Keystore में

Android app cloud models के model weights download नहीं करता। Gemini, OpenAI, Groq, Claude और Perplexity के requests HTTPS agent gateway पर जाते हैं; provider credentials server-side configured हों तो Android को provider model files रखने की जरूरत नहीं। Claude को gateway पर `anthropic` provider ID से route किया जाता है। Gateway अनुपलब्ध/configured न होने पर केवल user-configured local API key वाला explicit direct-provider route इस्तेमाल हो सकता है।

## 2. Android optional capabilities

बड़े model weights base APK assets में embed नहीं होंगे:
- LiteRT-LM / ONNX / GGUF weights: external, versioned, checksum-verified model/data packs; user chooses compatible model.
- Advanced OCR/vision/AR and optional voice packs: future Play Dynamic Feature modules where code isolation is safe.
- Play Store delivery: Android App Bundle + Play Feature Delivery; sideload builds must not download executable code at runtime.

**Important current boundary:** source अभी एक `:app` Android module है। CameraX, ML Kit, ONNX Runtime, LiteRT-LM, ARCore और Firebase dependencies अभी app dependency graph में हैं। R8/resource shrinking enabled है, लेकिन actual APK Analyzer baseline और safe dynamic-feature migration अभी बाकी है। इसलिए APK size reduction को पूरा या measured नहीं कहा जाएगा।

## 3. Windows PC Companion — Separate product/runtime

PC features Android APK से अलग Python companion में रहें:
- Core pack: authenticated WebSocket relay, pairing, Windows Credential Manager and E2E crypto.
- Control pack: mouse/keyboard, active-window interaction and Windows automation.
- Vision pack: active-window capture, OCR and screen sharing.
- Clipboard pack: explicit one-shot clipboard transfer.
- Voice pack: optional offline voice recognition.
- QR pack: camera-based pairing.
- Offline model pack: optional llama.cpp + user-supplied GGUF; inference PC पर, Android APK में नहीं।

Dependency manifests:
- `requirements.txt` — minimal core
- `requirements-control.txt`
- `requirements-vision.txt`
- `requirements-clipboard.txt`
- `requirements-voice.txt`
- `requirements-qr.txt`
- `requirements-offline.txt` — अलग बड़ा runtime
- `requirements-all.txt` — Windows CI/package build profile

Missing optional packages केवल उस feature को unavailable करें; pairing/core relay का startup उन पर निर्भर नहीं होना चाहिए।

## 4. Cloud AI Agent boundary

Android `VoiceGatewaySettings` से authenticated HTTPS `/api/chat` request भेजता है। Provider name और agent role स्पष्ट रहते हैं। Backend provider adapter selected provider को execute करता है और provider/model/usage metadata लौटाता है। Claude/Anthropic route उसी interface का हिस्सा है; Claude weights APK/PC में download नहीं किए जाते। User अपने provider account/API key का उपयोग करता है; quota, model availability और billing provider account पर निर्भर हैं।

## 5. Release gate

यह boundary implementation/source organization है, test result नहीं। Android dynamic feature migration, APK Analyzer size report, live provider requests, Windows EXE build और real Android↔PC pairing को consolidated verification में अलग-अलग prove करना होगा। इस branch की policy के अनुसार source-completion pass के दौरान tests स्वतः नहीं चलेंगे; Android CI केवल manual dispatch से चलेगा।
