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

## Step 5 — Device Universe (relay hardening implemented; end-to-end validation pending)

- Pairing/session creation and WebSocket relay require HTTPS/WSS; relay credentials are validated before session creation and URL parameters are encoded with OkHttp.
- Relay control requests require target-side approval, expire after 60 seconds while pending, and produce grants capped at five minutes. Data-share requests require per-category approval, expire after 60 seconds while pending and grants are one-shot with a maximum 15-minute lifetime.
- Screen/camera frames are relayed only when the sending device has explicitly enabled the matching permission; frame rate is capped at 10/sec. Payloads and chat messages are size-limited.
- Disconnect now revokes local and peer-side grants, pending requests and share permissions. Reconnect replacement is guarded against a late close event clearing the new socket.
- Android client requires HTTPS for chat/provider/vision gateway APIs, limits vision payload size, validates remote tap/swipe coordinates and bounds click-text targets. Screen/camera toggles are checked again after OS permission prompts.
- Remaining: automated protocol tests, token rotation/revocation review, replay protection, Android local authorization verification and cross-platform companion validation. Arbitrary control of every app/device is not possible; Android and remote OS restrictions apply.

## Step 6 — AI Intelligence (core provider integration implemented; capability expansion pending)

- Provider preference is persisted and validated for Gemini, OpenAI, Groq, Claude and Perplexity. Non-Gemini provider keys are stored in the Android Keystore-backed encrypted feature store and only sent to the selected provider.
- Text chat adapters are implemented for OpenAI-compatible chat APIs, Anthropic Messages and Perplexity. Chat history is bounded to 40 turns / 8,000 characters per turn; system instruction is capped at 8,000 characters. Compose state is snapshotted before background requests.
- Backend includes authenticated expert-domain and multi-provider council endpoints with bounded input, provider allowlists, per-IP rate limiting and explicit advice-only output (no external actions executed).
- Current adapters are text-only: streaming, image/audio multimodality, browsing, citations, tool execution, automatic failover and offline model routing are not implemented. Provider model IDs and live API compatibility still require current-provider verification with real user keys.

## Step 7 — Premium UI (not yet fully audited)

- Existing application screens and design-system documentation are present, but this implementation pass has not completed a screen-by-screen visual/accessibility audit.
- Remaining: verify dynamic assistant-name replacement across every screen/notification/shortcut, responsive layouts, navigation consistency, animation performance, reduced-motion support, contrast, TalkBack labels and real-device rendering. Do not claim premium UI completion until these checks pass.

## Step 8 — Firebase and Security (source rules implemented; deployment/integration pending)

- Firestore vault reads/writes are scoped to the authenticated user's UID; document IDs, exact field allowlist, ciphertext format/type, payload size and integer timestamp are validated. All other document paths are denied.
- Storage rules scope paths to the authenticated user's UID, cap uploads below 50 MB, allow only image/video/PDF/JSON/plain-text content types and deny all other paths. Storage upload code is not yet integrated into the app.
- Root firebase.json points Firebase CLI deployment at the checked-in Firestore and Storage rules. Firebase config is supplied via local.properties/BuildConfig; no service-account credential is committed.
- Cloud Vault signs in with Firebase Email/Password, encrypts backup text client-side using AES-256-GCM with a PBKDF2-HMAC-SHA256-derived key (210,000 iterations), and validates encrypted payload structure/size on restore.
- Remaining: run Firebase Emulator rules tests, deploy rules to the user's actual Firebase project, verify App Check/Auth providers/authorized domains, account deletion/export, token lifecycle and backup recovery. Checked-in rules do not prove production deployment.

## Step 9 — Final Testing
Not started. Requires successful unit tests, lint/static checks, debug and release APK builds, install/upgrade tests, physical-device tests, crash/performance checks and final artifact checksums.

### People Memory model integration — 2026-11-20
- Added explicit, user-started downloads for pinned SFace and ECAPA ONNX files. Downloads are HTTPS-only, size-limited, written to temporary files, SHA-256 verified and atomically installed into app-private storage.
- Added a model catalog documenting source revisions, checksums, tensor contracts, attribution and limitations.
- Corrected SFace preprocessing to match OpenCV FaceRecognizerSF: aligned RGB 112×112, NCHW float32, raw 0–255 values. Added unit tests for channel order, layout and value range.
- Added consent-gated live 1:1 face comparison against a selected saved person using OpenCV's published 0.363 cosine reference threshold. This is similarity only, not authentication.
- ECAPA ONNX export accepts 16 kHz mono waveform input and includes its own filterbank frontend; the five-second sample path is compatible at the tensor-shape level. A consent-gated voice comparison can now show raw same-profile cosine similarity, but speaker decision threshold and real-device accuracy remain uncalibrated, so no speaker identity verdict is exposed.
- The models have not yet been downloaded and executed on a physical Android phone in this environment. CI must pass before APK build status can be claimed.

### Additional security/privacy hardening — 2026-11-20
- Microphone permission is no longer requested at app launch; it is requested only when the user opens Voice or explicitly starts a voice command.
- Remote accessibility commands now require a fresh peer-specific approval, expire after five minutes, and can be revoked immediately. The persistent opt-in switch alone no longer authorizes incoming commands.
- Deleting all People Memory profiles now also clears the encrypted biometric template vault, not just the visible directory.
- Gemini API keys now use the Android Keystore-backed AES-GCM encrypted store; an existing plaintext SharedPreferences key is migrated and cleared only after the encrypted write succeeds.
- These changes are source-level hardening; relay-server authorization, Android device behavior and multi-device tests still need end-to-end verification.

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

### Build verification — 2026-11-20
- Latest observed run `44664745187` (`dbc413b…`) had a successful backend-check job, but the Android verify job was cancelled during unit tests and the APK build was skipped. This is not a passing Android build.
- Earlier run `44664268684` was superseded/cancelled as additional commits were pushed. Do not treat a cancelled run as a test pass.
- No physical-device validation, certified biometric spoof testing, Firebase Emulator test or production Firebase rules deployment has been performed.
