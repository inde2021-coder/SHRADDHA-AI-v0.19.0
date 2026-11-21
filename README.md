# SHRADDHA-AI-v0.19.0

## Mobile AI Work Agent (Chat + Web + Android Apps)

The Android **AI Work Agent** is available from the Mobile Command Center. It combines the configured AI chat provider with an embedded Android WebView and a reviewed hand-off to the Accessibility Operator.

- Chat with the configured provider (Gemini or another provider supported by the existing provider client).
- Open HTTP/HTTPS sites or search terms inside the app; SSL errors and Android Safe Browsing warnings are not bypassed.
- Read up to 12,000 characters of visible page text and inspect up to 100 visible interactive controls. Page content is untrusted input and must never be treated as agent instructions.
- Scroll pages, click one uniquely labelled visible button/link after an explicit confirmation, and fill one uniquely labelled non-sensitive text field after a separate preview and confirmation.
- Password, hidden, file, checkbox and radio inputs are excluded from the generic fill action. The generic fill action never submits a form. A separately approved click may submit or cause a consequential website action; the confirmation dialog warns about this.
- Hand off a task to the Android Accessibility Operator for a separate review. Cross-app actions require the user to enable Android Accessibility and are limited by the trained workflows and safety policy.
- Hindi/English voice input is available through Android speech recognition when the device provides it.

**Limitations:** This is not unrestricted remote browser automation. Login challenges, CAPTCHA, payment, purchases, sending, deletion, publishing and account changes may require the user to complete or explicitly approve steps. File uploads/downloads, browser tabs, arbitrary JavaScript execution, background browsing, and automatic multi-site workflows are not implemented by this work surface. Website controls that are not visible or do not expose a unique accessible label cannot be safely targeted. Physical-device and real-site testing is required before treating a workflow as production-ready.
