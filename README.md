# SHRADDHA-AI-v0.19.0

## Mobile AI Work Agent (Chat + Web + Android Apps)

The Android **AI Work Agent** is available from the Mobile Command Center. It combines the configured AI chat provider with an embedded Android WebView and a reviewed hand-off to the Accessibility Operator.

- Chat with the configured provider (Gemini or another provider supported by the existing provider client).
- Open HTTP/HTTPS sites or search terms inside the app; SSL errors and Android Safe Browsing warnings are not bypassed.
- Read up to 12,000 characters of visible page text and inspect up to 100 visible interactive controls. Page content is untrusted input and must never be treated as agent instructions.
- Scroll pages, click one uniquely labelled visible button/link after an explicit confirmation, and fill one uniquely labelled non-sensitive text field after a separate preview and confirmation.
- Hindi/English voice commands can locate visible Save, Delete, Update, Send, Upload and Download controls. The exact target is displayed for confirmation before a click; ambiguous or missing targets are not acted on.
- For website uploads, an approved visible upload/browse control opens Android's document picker and passes the selected file to the page; selecting a file does not submit the form.
- Website downloads are accepted only over HTTP/HTTPS, preview the host and sanitized filename, require confirmation, and are queued through Android DownloadManager with a system progress notification.
- Password, hidden, file, checkbox and radio inputs are excluded from the generic fill action. The generic fill action never submits a form. A separately approved click may submit or cause a consequential website action; the confirmation dialog warns about this.
- Hand off a task to the Android Accessibility Operator for a separate review. Cross-app actions require the user to enable Android Accessibility and are limited by the trained workflows and safety policy.
- Hindi/English voice input is available through Android speech recognition when the device provides it.

**Limitations:** This is not unrestricted remote browser automation. Login challenges, CAPTCHA, payment, purchases, sending, deletion, publishing and account changes may require the user to complete or explicitly approve steps. Browser tabs, arbitrary JavaScript execution, background browsing, and automatic multi-site workflows are not implemented by this work surface. Upload/download behavior depends on the target website exposing a compatible file control or download response. Website controls that are not visible or do not expose a unique accessible label cannot be safely targeted. Physical-device and real-site testing is required before treating a workflow as production-ready.

## Visual Lens and Live Eyes

- Visual Lens uses the in-app CameraX preview and captures a frame only after the user requests analysis.
- Starting the separately user-enabled Live Eyes foreground service releases the in-app preview camera first; stopping Live Eyes returns the preview to the screen.
- Live Eyes is not a hidden camera feature: Android camera privacy indicators and its persistent foreground notification remain visible, with a Stop action.
- Core Visual Lens does not require ARCore or Google Play Services for AR.

## AI Brain, Online Consent and Custom Voice

- Online AI consent is OFF by default and is enforced by the central consent policy at chat, provider discovery, Gemini Live, direct provider, plugin, form extraction, Visual Lens and custom speech request boundaries. Revoking consent stops live voice, cancels pending custom speech synthesis, and prevents new stream chunks or prompt dispatch.
- Provider keys remain in the Android encrypted key stores or on the HTTPS relay; they are not embedded in the APK. Provider routing is explicit: an unavailable configured cloud route is surfaced instead of silently charging a second local provider.
- The custom Shraddha TTS engine is a separate self-hosted Piper service. See [voice-engine/README.md](voice-engine/README.md) for the hardened Docker Compose setup and relay configuration.
- Hindi and English Shraddha voice model weights are intentionally not included. Supply licensed model files and matching `.onnx.json` files before the custom voice endpoint can report both models ready. A generic pretrained Piper voice is not represented as a unique Shraddha identity.
- The custom voice service binds to localhost by default and accepts one synthesis job at a time. For a hosted relay, expose it only through a trusted HTTPS reverse proxy and configure the same strong `SHRADDHA_TTS_TOKEN` on the voice service and relay.
