# SHRADDHA AI v0.19.0 — Steps 3–9 Implementation Tracker

Working branch: `work/step-01-source-audit-fixes`
Main branch: untouched.

## Step 3 — Voice AI (in progress)

### Implemented in source
- Fixed Kotlin Gradle DSL Properties import that blocked project configuration.
- Fixed duplicate `accessibility_description` Android string resource that blocked resource merging.
- Hardened `LiveAudioIO`: validates unsupported microphone buffer formats, verifies AudioRecord enters RECORDSTATE_RECORDING, handles AudioRecord read errors, releases failed capture resources and reports failures instead of silently pretending the microphone is active.
- Wired capture errors into `VoiceSessionManager` so the orb/session state can report a microphone error.
- Added unit tests for voice conversation-memory trimming, role-aware recap, blank-turn handling and clearing.

### Not yet verified / remaining
- Latest GitHub Actions run is still in progress at the time of this report; no passing APK build is claimed.
- Runtime mic permission and Android 14/15 microphone foreground-service lifecycle need physical-device tests.
- Confirm service is started only from an eligible visible user action and that Stop/Mute work consistently.
- Verify Gemini Live model availability, API response schema, reconnect/resume and quota behavior against a real configured key.
- Validate microphone route changes, Bluetooth/headset, audio focus, echo cancellation and OEM battery restrictions.
- Add explicit voice consent indicator, lifecycle recovery and user-facing failure/retry states.
- Test Hindi/English speech recognition, transcript integrity, audio latency and interruption.

## Step 4 — People Memory and Visual Lens (source integration in progress)

- CameraX/ML Kit capture activity is registered and reachable from the People Memory screen.
- Person profiles receive stable local IDs; separate confirmation is required before face or speaker enrollment.
- Added SAF model import into app-private storage with a 250 MB cap and temporary-file/rename handling.
- Face capture runs a basic randomized blink/head-turn interaction challenge, quality checks and local ONNX inference; speaker flow uses on-device speech recognition followed by five seconds of local audio capture.
- Embeddings are stored in the encrypted local vault; profile deletion also deletes that person's biometric templates.
- No SFace/ECAPA weights are bundled. Imported model license, exact preprocessing/tensor contract and thresholds still need independent validation. Blink/head-turn is not certified anti-spoofing and must not be used as sole authentication.
- Speaker recognition is blocked when on-device speech recognition is unavailable; cloud speech upload is not used for this challenge.
- Physical-device capture, model compatibility, accuracy, false-accept/false-reject and spoof evaluation remain mandatory before calling this step complete.

## Step 5 — Device Universe (security hardening in progress)

- Pairing/session creation and WebSocket relay now reject plain HTTP and require HTTPS/WSS.
- Relay credentials are validated before opening a session; query parameters are built with OkHttp URL encoding rather than string concatenation.
- Remaining: server-side one-time pairing expiry/attempt limits, device-token rotation/revocation, per-device capability grants, command allowlists, replay protection, screen/camera consent lifecycle and cross-platform companion validation.
- Arbitrary control of every app/device is not possible; Android and remote OS restrictions apply.

## Step 6 — AI Intelligence
Not started in this implementation pass. Provider abstraction, capability routing, expert knowledge grounding, citations, tool permission boundaries, offline behavior and factuality tests remain to be implemented/verified.

## Step 7 — Premium UI
Not started in this implementation pass. Full-screen responsive design, navigation, accessibility, animation performance, reduced-motion support and dynamic assistant-name replacement require UI audit and device verification.

## Step 8 — Firebase and Security
Not started in this implementation pass. Firestore/Storage rules, auth lifecycle, account deletion/export, backup/restore, encryption key recovery, token handling, secret scanning and emulator/rules tests remain open. No credentials should be committed.

## Step 9 — Final Testing
Not started. Requires successful unit tests, lint/static checks, debug and release APK builds, install/upgrade tests, physical-device tests, crash/performance checks and final artifact checksums.

## Truthful completion gate
A step is complete only when its source changes are committed, tests pass, relevant device/integration checks are performed, limitations are documented and artifacts are reproducible. A GitHub CI run alone does not prove hardware behavior.

## Update — 2026-11-19 implementation pass

### Step 3 — Voice AI source changes
- Added fatal AudioRecord error handling and resource cleanup; failed microphone capture no longer leaves its capture loop marked active.
- Propagated microphone errors into the voice session state and clears VAD/amplitude indicators.
- When all five reconnect attempts are exhausted, microphone capture is paused (fail-closed) instead of continuing to collect audio while disconnected.
- Foreground notification wording no longer falsely claims that the assistant is actively listening while it may still be connecting or recovering.
- Added conversation-memory tests (12-turn retention, role-aware recap, blank input filtering and clear).
- Still requires a passing latest CI run and physical-device checks for actual continuous listening, Android microphone foreground-service restrictions, lock-screen/OEM behavior, Bluetooth/audio focus and real Gemini Live API connectivity.

### Step 4 — People Memory / Visual Lens source changes
- Added CameraX, ML Kit Face Detection, ExifInterface, Guava ListenableFuture, Google Tasks and ONNX Runtime Android dependencies.
- Registered the consent-gated biometric capture activity as non-exported.
- Added an encrypted local biometric template vault backed by Android Keystore AES-GCM, with explicit enrollment consent, person deletion and cosine similarity threshold matching.
- Added a strict ONNX adapter that checks input tensor contracts and refuses unknown model shapes rather than generating fake embeddings.
- Added a basic randomized blink/head-turn interaction gate and voice phrase normalization tests.
- Important limitations: blink/head-turn is not certified anti-spoofing; ECAPA exports with mel-feature inputs are rejected until exact preprocessing is validated; no licensed SFace/ECAPA model weights are bundled; no benchmark or physical-device biometric accuracy test has been performed. The current capture flow must not be treated as secure identity proof.

### Build verification
- CI run 44568205177 completed successfully for the earlier Step 3/4 engine and dependency changes.
- CI runs for the latest People Memory UI integration (bcd0c3a1…) and HTTPS/WSS relay hardening (a0fe74ad…) were triggered and must be checked separately; do not infer their outcome from the earlier passing run.
- No physical-device validation or certified biometric spoof testing has been performed.
