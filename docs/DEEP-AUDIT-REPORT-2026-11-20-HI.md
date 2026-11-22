# Shraddha AI v0.19.0 — Deep Project Audit
**Audit date:** 20 November 2026
**Audited branch:** `feature/ai-phone-agent-foundation`
**Audited commit:** `9bf499cacc30244e9416cf3effcf62c88d71206d`
**Main branch:** `cc962dd0b2f71b3edf3270317aca6c5ef2867624` (unchanged during this audit)
**PR:** #4 — Draft, not merged

## Executive verdict

**Status: NOT READY FOR PRODUCTION / NOT READY FOR FINAL RELEASE.**

The repository contains a substantial Android application, a Node backend, and a large Python/Node desktop companion source tree. However, a number of important systems are still prototypes, placeholders, unverified integrations, or not connected to the claimed end-to-end workflow. A source file or UI panel is not proof that the feature works.

The highest release blockers are:
1. Device-link screen/camera privacy enforcement and end-to-end encryption.
2. Real Windows companion pairing/control integration.
3. Real Jio → Edesy → AI inbound call verification.
4. Secure API-key handling and actual license validation.
5. Persistent, user-controlled memory and call history.
6. Android, backend, desktop and real-device test coverage.
7. Release signing and production build pipeline.

## Immediate safety fixes already committed on the feature branch

| Commit | Change |
|---|---|
| `371a7fba4c442ce5a4ad670bced91764d0583fa6` | Fixed missing `EXTRA_PENDING_CONTACT_QUERY` constant and resumed pending contact lookup after Contacts permission. |
| `661967656a16c3b61a949a69d4f273c79f58459b` | `ACTION_DIAL` now displays the requested number and requires a separate user tap before placing a call. |
| `9bf499cacc30244e9416cf3effcf62c88d71206d` | Message actions no longer auto-tap Send; ambiguous/unavailable contacts are rejected; social/WhatsApp workflow placeholders no longer report fake success. |

The old `autoTapSend` helper still exists in the legacy accessibility class, but the current `message_contact` path no longer invokes it.

## Findings by severity

### P0 — Must fix before real users' private data or remote devices are exposed

| ID | Finding | Evidence / impact |
|---|---|---|
| P0-1 | **Screen/camera frame relay does not enforce the device's published screen/camera permission.** | In `backend/server.js`, the binary WebSocket frame handler forwards screen/camera frames without checking `device.sharePermissions.screen` or `device.sharePermissions.camera`. Android's explicit MediaProjection/camera start is useful, but the relay must enforce the same policy server-side. |
| P0-2 | **Device-to-device payloads are not end-to-end encrypted.** | `DeviceLinkManager.sendSharedPayload` sends Base64 JSON through the relay; the relay decodes/parses and forwards the payload. TLS protects transport to the server, not confidentiality from the relay operator. Sensitive files, messages, memory or contacts must not be described as E2E encrypted. |
| P0-3 | **Remote actions have no per-action approval or verified result acknowledgement.** | A target can grant a time-limited remote-control window; later actions are applied through `ShraddhaAccessibilityService.applyRemoteAction`. The receiver does not show a separate approval dialog for each action, apply the central risk policy to every action, or return a verified before/after result to the controller. |

### P1 — Release blockers

| ID | Finding | Evidence / impact |
|---|---|---|
| P1-1 | **Windows/Linux companion is not connected to the Shraddha device relay.** | Android's relay uses `/api/device/session`, `/api/device/join`, and `/ws`. The MYRA Python desktop source uses its own Gemini WebSocket and has no corresponding Shraddha relay pairing/client implementation. The pairing README describes a workflow that is not present in the desktop code. PC control is therefore not verified end-to-end. |
| P1-2 | **Desktop companion is still a separate MYRA-branded application/source tree.** | Its README starts `python aria.py` and labels the product MYRA ULTRA. Android's user-visible Command Center also displays “MYRA PC COMMAND CENTER” and “LIA VOICE ENGINE”. This conflicts with the Shraddha-only branding requirement. |
| P1-3 | **The desktop licensing backend is legacy/unrelated and unsafe to deploy as-is.** | `desktop-companion/MYRA_ULTRA_APP/backend/main.py` references Firebase project `speen-to-earn`, a legacy credential path, wildcard CORS, and a default admin secret `change-this-in-production`. It is not the Shraddha device relay. Do not deploy it without replacing its project/configuration, removing defaults, and completing a security review. |
| P1-4 | **License activation is not server validation.** | `com/Lia/assistant/license/AccessKeyManager.kt` initializes access as active; `activate()` accepts any non-empty key and locally marks it Pro for one year. `liveCheck()` only reads local preferences. This is not a verifiable license/entitlement system. |
| P1-5 | **Gemini API key is stored in plain SharedPreferences and placed in the WebSocket URL.** | `ApiKeyStore.kt` stores the key as a normal preference; `GeminiLiveClient.connect()` appends it as `?key=...`. Prefer encrypted Android Keystore-backed storage and an authenticated header/short-lived server-issued credential where supported. Avoid secrets in URLs and logs. |
| P1-6 | **Voice session can become stuck active when the Gemini key is missing.** | `VoiceSessionManager.start()` sets `_isActive=true` before asynchronous connection; the missing-key branch sets ERROR but does not reset the active flag or release the audio object. A later Start attempt returns early until the user explicitly stops the service. |
| P1-7 | **Voice conversation memory is volatile, not long-term memory.** | `VoiceSessionManager` clears `ConversationMemory` at session start and stop. The encrypted local vault is a separate manual note field; it is not wired as durable conversational memory or automatic people memory. |
| P1-8 | **Firebase integration is Firestore, not Firebase Realtime Database.** | Gradle includes Firebase Auth, Firestore, Storage and Messaging; `FirebaseCloudVault` writes to Firestore. There is no Realtime Database SDK/configuration or RTDB rules file. If RTDB was the intended backend, that integration is missing. |
| P1-9 | **Cloud restore writes the restored note to unencrypted SharedPreferences.** | `MainActivity.addFirebaseCloudSection()` stores restored plaintext under `shraddha_local_memory`, while the local AES-GCM vault uses a separate encrypted store. Restore should write into the encrypted vault, not plaintext preferences. |
| P1-10 | **Biometric identity recognition is not implemented and the capture activity is not registered.** | `LocalBiometricModelEngine` deliberately throws instead of running SFace/ECAPA inference; `BiometricConsentVault.match()` always returns null. `BiometricCaptureActivity` is not declared in the main Android manifest. The current app correctly discloses that identity matching is unavailable; do not advertise face/speaker identification as working. |
| P1-11 | **File transfer is a small one-shot prototype.** | MainActivity rejects files over 650 KB, reads the whole file into memory, Base64-encodes it and sends one WebSocket message. There is no chunking, progress, pause/resume, retry, checksum, delivery receipt, or user-selected receive destination. |
| P1-12 | **Pairing and device revocation are incomplete.** | Device credentials live in volatile fields in `DeviceLinkManager`; they are not securely persisted for reconnect after app restart. `disconnect()` closes only the local socket; there is no server-side device revocation/removal UI/API. Pairing codes remain usable until expiry and can join multiple devices. |
| P1-13 | **Incoming Jio SIM → Edesy → AI call flow is code-only and unverified.** | Android InCallService provides call controls/history, not carrier-call audio to the AI. The actual AI path requires Jio no-answer forwarding to a provisioned Edesy DID. No real Jio forwarding, DID route, two-way audio, timeout, or recovery test is recorded. Do not enable forwarding until a real Edesy call passes. |
| P1-14 | **Edesy call features remain partial.** | Bridge code exists, but no real provider call is verified. Transcript/summary storage is bounded in-memory and disappears on restart; transcript capture is a global opt-in, not a per-caller consent workflow. No audio recording, durable history, caller screening, human takeover/transfer, WhatsApp delivery, or call notification UI is implemented. |
| P1-15 | **Camera Visual Lens does not yet provide camera → AI Q&A/translation.** | Camera frames are streamed to a paired device and displayed. The current AI vision call analyzes the last remote screen frame; there is no equivalent camera-frame AI analysis/continuous translation pipeline. |
| P1-16 | **Remote action verification is missing.** | MainActivity displays that an action was sent; the receiver applies it but does not send a structured execution result or post-action screen snapshot back to the controller. |
| P1-17 | **Safety policy is not consistently enforced.** | `OperatorSafetyPolicy` is used in `AdaptiveWorkflowRunner`, but not in every execution path such as `OperatorEngine` and remote actions. A single `confirmed=true` flag can authorize multiple steps in an adaptive workflow rather than requiring step-specific confirmation. |
| P1-18 | **SMS/WhatsApp and social automation are not full workflows.** | The current message tool now opens a composer and requires the user to tap Send. Full WhatsApp task automation/social publishing controls are not connected; they now correctly return `not_implemented` instead of pretending to queue work. |
| P1-19 | **Call history UI is not AI call history.** | Android local history records call state/time and a masked number. “Share call summary” shares those event notes, not an Edesy transcript or AI summary. |
| P1-20 | **Multi-provider support is split across subsystems.** | Backend text/vision endpoints support several providers; on-device live voice uses Gemini Live; Edesy inbound calls use OpenAI Live. There is no unified voice-provider selection/failover layer. |

### P2 — Quality, maintainability and release readiness

| ID | Finding |
|---|---|
| P2-1 | Repository has 1,393 tracked paths and several retained source snapshots: `integrations/`, `upstream-source/`, `companions/`, and `desktop-companion/`. These are not all Gradle inputs, but they create duplication, branding drift and uncertainty about the authoritative desktop source. |
| P2-2 | Android source has two namespaces (`in.shraddha.ai` and `com.Lia.assistant`) and two AccessibilityService implementations registered in the manifest. This needs a documented single ownership model and consolidation plan. |
| P2-3 | Main Android CI runs unit tests and `:app:assembleDebug`; backend is only checked with `node --check`. There are no backend integration tests, Firebase emulator/rules tests, Android instrumentation tests, emulator workflow, or Windows/Linux companion CI. |
| P2-4 | Current unit-test inventory is small (12 Kotlin test files) and does not cover ContactResolver ambiguity, permission flows, remote-control authorization, binary frame privacy, Edesy protocol, Firebase encryption/restore, or file-transfer interruption. |
| P2-5 | CI does not produce a signed release APK/AAB. No production keystore/signing, R8/proguard release validation, Play bundle, staged rollout or reproducible release workflow is verified. |
| P2-6 | `QUERY_ALL_PACKAGES`, Accessibility, overlay, camera, microphone and broad media permissions require clear user disclosure and Google Play policy review. |
| P2-7 | `VoiceForegroundService` uses `START_STICKY`; a service restart with a null intent can enter the normal start path. Verify that Android cannot silently resume microphone capture without a fresh user action. |
| P2-8 | `FirebaseCloudVault` uses `runBlocking`; current UI callers use background Threads, but the API itself can block a caller thread and should be converted to suspend/coroutine APIs. |
| P2-9 | Backend uses one shared `GATEWAY_TOKEN` for AI routes, in-memory IP rate limits, and trusts `x-forwarded-for` for the client IP. Add per-user/device authentication, robust proxy trust, quotas and persistent abuse controls before public exposure. |
| P2-10 | Backend Edesy token is accepted in a WebSocket query string. Query parameters can appear in proxy/access logs; prefer a provider-supported authorization header or a short-lived signed stream credential. |
| P2-11 | Android UI is concentrated in a very large programmatic `MainActivity.kt`; split screens, ViewModels, repository interfaces and state handling to make permissions/lifecycle/testability manageable. |
| P2-12 | User-visible branding still includes LIA and MYRA labels even though the Android launcher label is Shraddha AI. |

## What is implemented or partially working in source

- Android app namespace/application ID: `in.shraddha.ai`; single launcher entry is declared.
- Compose-based LIA-derived voice UI is compiled into the same Android app under an internal namespace.
- Gemini Live voice session, audio I/O, transcripts, tool calls and reconnect logic exist in source; real-device reliability still needs verification.
- On-device app opening, screen reading and accessibility actions exist, subject to permissions and device compatibility.
- Android local AES-GCM memory vault and encrypted feature store exist.
- Person Directory stores consent-flagged local profiles; it is not biometric identity recognition.
- Remote screen/camera capture and relay source exist; server-side permission enforcement and E2E security remain blockers.
- Backend AI text/vision/provider endpoints and device relay source exist.
- Edesy ↔ OpenAI Live audio bridge source exists; no real PSTN call is verified.
- Website Forge source and preview UI exist; provider credentials/network are required.
- MYRA desktop source exists but is not paired to the Shraddha relay.

## Build and verification evidence

- Workflow `Android CI` run **424** on commit `371a7fba4c442ce5a4ad670bced91764d0583fa6` completed successfully after the missing contact-query constant was fixed.
- The previous run on `a6a0cfde63515b939617d25cc858f57b791d7520` failed compilation with unresolved `EXTRA_PENDING_CONTACT_QUERY`; that specific compile error was fixed.
- GitHub Actions run **427** for commit `9bf499cacc30244e9416cf3effcf62c88d71206d` completed successfully: backend JavaScript syntax, Android unit tests, debug APK assembly and APK artifact upload all passed. A duplicate run 428 was still running at the time of this report.
- The audit-report-only commit does not change application code; GitHub also queued runs 429/430 for commit `4822175b5947479f003ee039266f47575c5d2f32`.
- The workflow validates backend JavaScript syntax and runs Android unit tests plus debug APK assembly. It does not run real device, carrier, provider, desktop, Firebase emulator, or instrumentation tests.
- No signed release APK/AAB or real-device test result is included in the repository evidence.

## Recommended completion order

1. Finish and pass the current Android CI after the safety fixes.
2. Enforce screen/camera permissions in the relay; add device revocation, persistent pairing, E2E encryption and remote-action approval/result acknowledgements.
3. Implement a real Shraddha desktop relay client and pair it with Windows/Linux; remove duplicate/legacy MYRA/LIA branding and unrelated backend deployment artifacts.
4. Encrypt API keys, implement real license validation, secure gateway authentication and fix voice-session lifecycle/restart behavior.
5. Wire durable encrypted memory, correct Firebase restore, decide Firestore vs Realtime Database, and add data deletion/export controls.
6. Finish Jio no-answer forwarding and Edesy provider setup; run real inbound/outbound audio tests before changing carrier settings.
7. Complete durable call history, consent workflow, summaries, screening, takeover, notifications and any recording only after privacy/legal review.
8. Complete file transfer with chunking, checksum, progress, pause/resume, retry and actual receive/save UI.
9. Complete camera AI Q&A/translation and biometric model inference only with validated models, liveness tests and accuracy evaluation.
10. Add Android emulator/instrumentation tests, backend integration/security tests, desktop CI, dependency/license scanning, release signing and AAB/APK release verification.

**Release rule:** Keep PR #4 as Draft and do not merge into `main` until P0/P1 blockers are fixed and the Android, backend, desktop, carrier and real-device tests have passed.


## Pending-branch source re-check — 22 November 2026

This addendum checks the current pending-features branch, not the older audited commit above. Several findings from the 20 November audit are now stale and must not be repeated as current facts without rechecking:

| Earlier finding | Current source observation | Remaining qualification |
|---|---|---|
| P0-1 visual relay ignored screen/camera permission | `backend/server.js` now calls `canShareVisualFrame(device.sharePermissions, frameType)` before forwarding binary visual frames and applies a per-device frame budget. | Permission toggles and encrypted frame transport still need real-device verification. |
| P0-3 remote actions had no per-action approval/result | Plaintext remote actions are rejected. Encrypted action envelopes require an active remote-control grant; Android displays a target-side approval dialog for each received action and sends a result with before/after screen observations. | Verify expiry/revocation races, screen-capture failure behavior and the complete phone-to-phone session on physical devices. |
| P1-5 Gemini API key was stored in plaintext | Current `ApiKeyStore.kt` stores the Gemini key using AES-GCM with a non-exportable Android Keystore key and migrates/removes the legacy plaintext preference. | Review provider-specific key stores and verify restore/Keystore-loss behavior on device. |
| P1-10 biometric engine was a deliberate stub | Current `LocalBiometricModelEngine.kt` contains ONNX Runtime loading and inference paths for face/speaker models; capture flow and consent vault are present. | Model provenance/licensing, actual device inference, calibrated FAR/FRR/EER and independent certified PAD evidence are still required. A blink/head-turn/phrase challenge is not certified anti-spoofing. |
| P1-11 file transfer was one-shot only | A bounded chunk-envelope validator and encrypted peer-message path exist in source. | End-to-end chunk scheduling, durable resume, progress/cancel, quotas and malware scanning still require implementation and device tests. |
| P1-12 device revocation was incomplete | Relay policy includes owner-only device revocation and clears grants/pending actions. | Persistent credential revocation across process/server restart and physical multi-device tests remain. |

The current pending branch also adds a parallel Android multi-agent execution path and exposes it in Advanced AI Lab through WorkManager. That new path is **not yet built or tested**. This addendum is a source inspection only; it does not change any item to runtime-validated.
