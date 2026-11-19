# Shraddha AI v0.8.3

This release builds on the v0.8.2 workspace redesign with an animated agent orb and fixed bottom navigation. The orb is a visual identity element, not an indicator of microphone state; voice recognition starts only after the user taps Speak.

Open `app/` in Android Studio and sync Gradle to build. See `docs/` for pairing, privacy, and feature limitations.

# Shraddha AI — Independent Android app (v0.6.0)

A standalone native Android assistant project. It is separate from Radhe Krishn Tent House Manager and does not reuse OpenJarvis.

## v0.6.0 — Private on-device intelligence + v0.5 device control, Visual Lens prototype and Custom Data Hub

- Reorganized the app into a dark, layered dashboard: Quick Actions, App Control Setup, Worldwide Device Link, Device Permissions & Mode, Remote App Control, and Live Screen & AI Vision.
- Added a paired-device picker showing device names and online/offline state; choose a target instead of manually guessing a device ID.
- Added remote open-app-by-name, tap-visible-text, Home, Back, scroll, gesture and focused-field typing actions across compatible apps.
- Added clearer permission, privacy, and Android compatibility guidance in the UI.
- Updated Android app version to 0.5.0.

## Worldwide phone-to-phone operation

The app is designed to connect over the public internet, not just the same Wi-Fi. To use it from different cities or countries:

1. Deploy the Node.js relay on a publicly reachable host with HTTPS and WSS (TLS). A single always-on Node service is required for this starter. Do not use a localhost URL or a LAN-only IP.
2. Configure the same relay base URL on every phone.
3. Install Shraddha AI on every phone that should be remotely viewed/controlled.
4. On the main phone create a session and share its six-digit code privately. Other phones join with that code.
5. Select the target phone in the device picker. On each phone that may receive control, its owner must enable Android Accessibility and switch ON its own remote-control consent.
6. To view a phone, that phone's owner must start Share This Phone Screen and accept Android's MediaProjection system dialog.
7. Choose Mutual mode or Main phone only mode on the session owner phone.

The relay supports up to eight enrolled devices per in-memory session. Pairing code expires after five minutes. The relay currently stores sessions in process memory: restarting the server loses active sessions. A single relay process is required; horizontal multi-instance deployment is not supported by this starter. Add a persistent database, shared pub/sub and device-revocation lifecycle before production-scale deployment.

## What “control all apps” means on Android

Shraddha uses Android Accessibility APIs for visible UI gestures and text entry, and its custom IME keyboard for normal text fields. It can work across many installed apps when the owner has enabled the required permissions. It cannot promise control of literally every app or every screen: Android secure/lock screens, password and OTP fields, banking/protected surfaces, DRM/FLAG_SECURE content, system permission dialogs, and apps that block Accessibility actions may refuse viewing or control. It does not bypass those restrictions. The receiving user can turn remote consent OFF or disconnect at any time.

A phone without Shraddha AI installed and paired cannot be remotely controlled by this app. This is not covert surveillance: screen sharing is user-started and Android displays an ongoing system notification.

## Current feature map

| Area | Current implementation |
|---|---|
| Voice | Hindi speech recognition intent and simple write/type commands |
| Data entry | Custom keyboard and focused visible non-password text insertion |
| App control | Open installed launcher apps by name, tap visible text, Home/Back, scroll, tap/swipe and safe focused-field typing |
| Floating Orb | Overlay quick actions |
| Device groups | Six-digit pairing, up to eight phones, online device picker |
| Control modes | Mutual and main-phone-only |
| Live view | User-started MediaProjection frames relayed over authenticated WSS |
| Remote camera | Explicit rear/front Camera2 sharing to the selected peer; low-rate JPEG over WSS |
| Custom Data Hub | Per-category opt-in, request/approve, one-time small-file transfer |
| AI chat | Gemini/OpenAI/Anthropic/Groq/Perplexity server-side gateway |
| AI memory | Local-only notes on each phone |
| AI vision | Latest received screenshot can be sent to Gemini/OpenAI/Anthropic through relay |

The AI vision route analyses the latest received screen frame; camera stream viewing is a low-rate JPEG prototype. The AI chat endpoint supports Gemini, OpenAI, Anthropic, Groq and Perplexity. v0.5.0 added a proposal-only task planner and multi-model comparison. v0.6.0 adds offline bilingual intent proposals, Android-Keystore AES-GCM local memory, local keyword retrieval, provider diagnostics and AI text summarization. These additions do not execute device actions automatically.

## Security notes

- The relay requires HTTPS/WSS in the Android UI. Device bearer tokens are sent in the WebSocket Authorization header, not in the URL.
- Transport TLS protects data between phone and relay, but screen frames are not end-to-end encrypted in this starter; the relay process can access them. Deploy only on infrastructure you control.
- Pairing/session state is in memory. Do not treat the current starter as a production remote-access product until persistent identity, token rotation/revocation, E2E media encryption, audit logs, abuse controls and security review are implemented.
- Never share OTP, passwords, banking screens or private information.

## Backend setup

1. Open `backend/`.
2. Install Node.js 20+ and run `npm install`.
3. Copy `.env.example` to `.env`; configure provider keys server-side only if AI vision is needed. Never put provider keys in the APK.
4. Run `npm start`.
5. Put the service behind an HTTPS/WSS reverse proxy or deploy to an always-on Node hosting provider. Confirm `/health` returns `{\"ok\":true}`.

See `docs/DEPLOY-WORLDWIDE.md` for the public relay deployment checklist.

## Android Studio build

1. Open the `shraddha/` folder in Android Studio.
2. Use JDK 17 and install Android SDK Platform 35.
3. Sync Gradle with internet access.
4. Build > Build APK(s). Install the APK on two or more Android phones for pairing tests.

## Verification

- Node syntax check: `node --check backend/server.js`.
- Static XML parsing and ZIP integrity checks are performed for this source archive.
- A full Android Gradle build and real multi-country/two-phone test must be completed on Android Studio + physical devices; they are not claimed as passed here.


## Version 0.5.0 additions

- **Two-way paired-device control:** existing mutual mode remains the default; the receiving phone must separately enable remote-control consent and Accessibility.
- **Visual Lens camera prototype:** rear/front Camera2 stream can be started manually from the app. Android camera privacy indicator and an ongoing foreground notification remain visible. Remote camera frames are JPEGs relayed over the existing WebSocket transport; this is a low-rate prototype, not WebRTC and not suitable as a production CCTV/security camera.
- **Live screen:** Android MediaProjection remains user-started and requires Android's system confirmation.
- **Custom Data Hub:** each device can opt into categories independently. A requester asks; the receiving device must approve. Approved grants expire after a short window and are one-time. The app currently transfers small selected files only (650 KB cap) and stores received files in app-private storage.
- **AI chat providers:** Gemini, OpenAI, Anthropic, Groq and Perplexity are supported by the backend chat endpoint. Configure provider API keys in the backend environment; never put provider keys in the APK. Vision analysis remains Gemini/OpenAI/Anthropic only.
- **Local memory notes:** stored only on this phone; they are not yet synchronized to other devices or automatically injected into provider prompts.

### Required backend environment variables

See `backend/.env.example`. `GATEWAY_TOKEN` should be a long random secret. Set only the API keys for providers you intend to use.

### Important implementation limits

This release is not the full feature roadmap. It does not yet include WebRTC/TURN media, durable cloud session storage, background automatic sync of contacts/calendar/messages, full file transfer for large files, continuous always-on wake-word listening, autonomous multi-app task planning, cloud memory sync, push notifications, location sharing, a family device group UI, or a completed security audit. SMS/call-log access and protected app screens remain subject to Android/Play policy and device restrictions. The relay is an in-memory Node.js service; server restart loses pairing sessions. Use a relay you control and do not expose private screen/camera data through an untrusted server.

### Build and test status

Source-level checks are performed where available. A full Android Gradle build and real two-device test must still be run in Android Studio with Android SDK installed. Do not treat a successful ZIP integrity or Node syntax check as proof of an installable APK or a production-ready camera stream.


### Advanced AI Lab (v0.6.0)
- Offline Hindi/English command intent recognition (proposal-only).
- AES-GCM encrypted personal memory using Android Keystore.
- Local keyword retrieval over private notes (RAG-lite).
- Backend provider configuration diagnostics and AI text summarization.

### Advanced AI Lab (v0.5.0)

- **AI Agent Planner:** `/api/agent/plan` converts a goal into up to eight bounded JSON steps. Only known tool labels are accepted. The API returns `executionMode: proposal_only` and always marks user approval as required. It does not dispatch remote actions or execute Android operations.
- **Multi-Model AI Council:** `/api/ai/compare` queries two or three selected configured providers in parallel and returns separate answers/errors so the user can compare them.
- **Safety boundary:** model output is untrusted. Planner steps are proposals only; the current UI displays the plan/result as JSON and does not run it. Camera, screen, file sharing, messaging, or other sensitive actions still require the device's own permissions and explicit confirmation.

### Additional advanced features still planned

WebRTC/TURN media, durable session storage, end-to-end media encryption, resumable large-file transfer, native contacts/calendar sync, cloud memory, push notifications, local on-device model inference, wake-word listening, robust autonomous execution with per-step approval, audit export, and security review remain future work.

## Expert Course, Repair & Creator Studio (v0.7.0)
The Expert Studio lets users choose a field and task mode, then ask a configured AI provider for structured guidance. Fields include Electronics, Electrical Engineering, Mechanical Engineering, Medical & Health Education, Agriculture, Civil Engineering, Law & Legal Studies, Repair & Troubleshooting, Creator Studio, and General STEM. Modes include course learning, problem solving, conceptual design, fault diagnosis, exam preparation, and content creation.

Backend endpoint: `POST /api/expert/assist` (optional `GATEWAY_TOKEN` authentication). Provider keys remain on the backend. The feature is an AI learning/advisory assistant, not a substitute for licensed medical, legal, engineering, electrical, or repair professionals. Verify safety-critical outputs and applicable local standards before acting.


## v0.8.0 Complete Expert Hub
The Android app now includes a Complete Expert Hub for 20 departments and 10 task modes. It calls the authenticated backend route `POST /api/studio/work` using the selected server-configured AI provider. Configure at least one provider key in the backend environment (`GEMINI_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GROQ_API_KEY`, or `PERPLEXITY_API_KEY`) and set the same optional `GATEWAY_TOKEN` in the Android app and backend. The app can save up to 12 generated outputs locally and share the current output through Android's share sheet.

The Hub is an AI-assisted learning and drafting workspace, not a substitute for verified professional tools: CAD/PCB/building outputs are conceptual/text-based, and safety-critical medical, legal, electrical, mechanical and structural decisions require qualified review. Research mode does not fabricate citations and does not itself perform web browsing.
