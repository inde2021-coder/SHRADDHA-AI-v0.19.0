# Shraddha AI — Non-main Branch Deep Audit (2026-11-20)

Repository: `inde2021-coder/SHRADDHA-AI-v0.19.0`
Audit type: read-only branch comparison + targeted source review.
Important: this is a static source audit. I did not claim physical-device, carrier, live-provider, Firebase-emulator, or Windows runtime verification.

## 1. Branch inventory

GitHub branch search returned these five branches (including main):

| Branch | Compare with main | Static assessment |
|---|---:|---|
| `audit-fixes-v0.19.1` | 18 ahead, 2 behind; diverged | Focused fixes; not safe to merge blindly |
| `feature/ai-phone-agent-foundation` | 58 ahead, 0 behind | Phone-agent work; prior deep audit remains applicable |
| `redesign-v0.19.2` | 884 ahead, 1 behind; diverged | Largest redesign; many added systems, high integration/test risk |
| `work/step-01-source-audit-fixes` | 270 ahead, 1 behind; diverged | Broad hardening/integration branch; CI and security gaps remain |
| `main` | baseline | Not modified by this audit |

The ahead/behind values are GitHub compare results against the current main ref at audit time. A large ahead count does not mean the branch has 884 independently useful features; it means its history differs substantially and needs careful ancestry/diff review.

## 2. Branch: audit-fixes-v0.19.1

### Improvements found
- `ApiKeyStore.kt` encrypts the Gemini key with AES-GCM and a non-exportable Android Keystore key.
- It attempts one-time migration from the old plaintext preference and removes the legacy value.
- `VoiceSessionManager.connectSession()` now resets active state, releases audio capture and stops the foreground service when the Gemini key is missing.
- A branch-specific Android build workflow runs backend syntax validation, Kotlin unit tests, debug APK assembly and artifact upload.

### Remaining concerns
- The manifest still declares the optional legacy `com.Lia.assistant.agent.NovaAccessibilityService` with the visible label “LIA Screen Agent (Optional)”. This conflicts with the requirement to remove Lia/Myra branding and leaves two Accessibility service implementations in the app.
- `QUERY_ALL_PACKAGES` remains declared. This requires a strong Play policy justification and should be replaced with scoped package visibility where possible.
- The branch is 2 commits behind main and diverged. Its changes should be compared and cherry-picked/rebased deliberately, not merged as a wholesale branch replacement.
- The encrypted API-key migration is source-reviewed only; no device upgrade/restore test was run in this audit.

**Verdict:** useful focused security/lifecycle fixes, but not a final release branch.

## 3. Branch: redesign-v0.19.2

### Improvements found
- Adds a substantial native Android redesign and new source modules for persistent memory, offline model integration, biometric enrollment/matching, device E2E messaging, live visual translation, Firebase Realtime Database, voice synthesis, social publishing and a separate Shraddha PC companion.
- `VoiceForegroundService` changes documented in `AUDIT-v0.19.2.md` require explicit start/stop actions and use `START_NOT_STICKY`, preventing silent microphone restart after process death.
- `DeviceE2ECrypto.kt` implements ephemeral P-256 ECDH, HKDF-SHA256 and AES-256-GCM, with a human fingerprint intended for out-of-band peer verification.
- `DeviceLinkManager.kt` uses encrypted relay message types for selected remote actions, results, file chunks and clipboard payloads, and blocks messages until peer fingerprint confirmation.
- Backend binary frame handling checks the sender's `screen`/`camera` permission before relaying.
- The branch audit document itself records that physical-device tests and real provider/Firebase/PC end-to-end tests are still required.

### High-risk gaps / review findings
- E2E protection is not universal: the WebSocket binary screen/camera frame path still receives raw JPEG bytes and dispatches them directly to `onScreenFrame` / `onCameraFrame`. The E2E message envelope covers selected JSON message kinds, not the binary visual stream. Therefore the relay can still read screen/camera frames; do not claim full device-to-device E2E encryption.
- The ECDH fingerprint is explicitly designed for out-of-band verification. Until both device owners actually compare and confirm the fingerprint, the relay-mediated key exchange is vulnerable to a man-in-the-middle substitution. The source also uses an ephemeral in-process key pair; persistence/rotation and device identity binding need review.
- The branch contains a very large change surface (884 commits ahead and 1 behind main). Its features need dependency/build/runtime verification individually; source presence and tests alone do not prove production behavior.
- The branch's own audit notes admit unfinished work: custom Shraddha voice requires licensed Hindi/English models and a deployed Piper service; Firebase and companion flows need credentials and real E2E tests; arbitrary app automation, social workflows and some OCR/visual fallback cases remain incomplete.
- Biometric model integration is not equivalent to certified authentication or anti-spoofing. The audit documentation itself describes limits and calibration/device validation still needed.
- Firebase build configuration includes fallback project identifiers/API key values. These are client configuration, not server secrets, but silent fallback to a project can cause accidental cross-environment use; production configuration and Firebase rules/App Check must be explicitly verified.
- No claim is made here that this branch's CI currently passes. A successful run for another branch/commit does not validate this branch.

**Verdict:** promising feature-rich redesign, but high integration risk and not merge/release-ready until CI plus device/provider/Firebase/PC tests pass and visual-frame E2E is addressed.

## 4. Branch: work/step-01-source-audit-fixes

### Improvements found
- Adds a Windows PC companion that joins through the relay using a pairing code, stores its device token in Windows Credential Manager when available, and presents local approval for remote control requests.
- `OperatorSafetyPolicy` defines LOW / CONFIRM / BLOCK decisions; `OperatorEngine` observes before and after an action and can verify expected visible text.
- Backend binary relay now rejects screen/camera frames unless the sender's corresponding `sharePermissions` flag is exactly true; it rate-limits visual frames.
- Disconnect clears permissions, grants and pending requests on the relay.
- Adds Firebase rules, backend tests, a debug CI workflow, a Windows companion test file, and a manual signed-release workflow.
- Adds encrypted provider-key storage through the Android Keystore-backed `EncryptedFeatureStore`.

### High-risk gaps / review findings
- The branch's `.github/workflows/android-ci.yml` push trigger is only `branches: ["main"]`; it does not run on pushes to `work/**`, despite the implementation tracker describing work-branch CI. PRs targeting main can still trigger it. This discrepancy must be fixed or the claimed branch CI coverage is inaccurate.
- The branch's `DeviceLinkManager.kt` stores `sessionId`, `deviceId` and `deviceToken` in process memory and does not implement E2E encryption for relay payloads/visual frames. HTTPS/WSS protects transport to the relay, not against the relay itself.
- `OperatorEngine.execute()` is safer than a blind executor, but the API accepts a caller-supplied `confirmed=true`; the policy class alone does not prove every caller has shown a fresh, action-bound confirmation dialog. All execution entry points, including voice, learned workflows and remote actions, must be traced and tested.
- The visible LIA/Myra package/service remnants and duplicate Accessibility-service architecture still need consolidation and a user-facing setup audit.
- The workflow file adds lint and backend tests, but its APK validation expects ABI-specific APK filenames. Confirm the Gradle ABI split configuration produces those exact files on CI; otherwise the workflow can fail after a successful assemble.
- The signed release workflow depends on four GitHub Actions signing secrets. The workflow definition is not proof that secrets exist, that signing succeeds, or that a release APK/AAB was produced.
- The implementation tracker labels several capabilities “implemented” while also listing real Firebase deployment, physical-device validation, model accuracy, WhatsApp reliability and release checks as pending. Treat those capabilities as source-integrated, not production-verified.

**Verdict:** strongest candidate for systematic hardening, but not merge-ready until CI trigger mismatch, relay E2E, approval-path coverage and real-device verification are resolved.

## 5. Branch: feature/ai-phone-agent-foundation

The previous report `docs/DEEP-AUDIT-REPORT-2026-11-20-HI.md` remains applicable. This branch is 58 commits ahead of main and includes the incoming-call/Edesy foundation, phone agent UI/service, transcript support and prior contact/action safety fixes. The incoming normal-Jio-SIM → no-answer forwarding → Edesy → AI two-way audio flow has not been verified on a real Jio line and Edesy DID. Do not enable call forwarding until the full two-way call is proven. Call recording/consent, durable history, caller screening, human takeover and notifications remain incomplete as previously documented.

## 6. Cross-branch blockers before any merge/release

1. Pick one integration base. Do not merge the 884-commit redesign branch and the 270-commit work branch wholesale into main without ancestry and file-level reconciliation.
2. Resolve duplicate legacy namespaces/services and remove all visible LIA/Myra branding.
3. Enforce per-action authorization and action-bound confirmation across voice, accessibility, learned workflows, remote phone and PC actions.
4. Decide and implement a complete E2E protocol for screen/camera binary frames, not just selected JSON messages; verify peer identity and key confirmation.
5. Persist/rotate/revoke device credentials securely and test reconnect/revocation.
6. Fix CI branch triggers and ensure every candidate branch has its own passing run for unit tests, lint, backend integration tests and APK build.
7. Run Firebase Emulator rules tests and then verify the actual configured Firebase project, Auth, RTDB/Firestore/Storage rules and App Check.
8. Run real Android phone tests for microphone lifecycle, Accessibility, camera, screen sharing, biometric model execution, Hindi/English voice and OEM behavior.
9. Run Windows pairing/control tests and prove local approval, disconnect/revocation and reconnect.
10. Build a signed release APK/AAB only after signing secrets are configured and artifact checksums are verified.

## 7. Final decision

- `audit-fixes-v0.19.1`: keep as a source of focused fixes; do not wholesale merge.
- `feature/ai-phone-agent-foundation`: keep Draft PR and do not merge until phone-agent P0/P1 blockers are resolved.
- `redesign-v0.19.2`: do not merge wholesale; audit/reconcile feature groups and verify all new code.
- `work/step-01-source-audit-fixes`: use as a hardening candidate after fixing CI trigger and E2E/approval gaps; do not call it release-ready.
- `main`: left untouched.

This audit does not modify or merge any branch.
