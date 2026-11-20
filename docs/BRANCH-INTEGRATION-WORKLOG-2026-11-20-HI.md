# Shraddha AI — Branch Integration Work Log (2026-11-20)

## Scope
This branch is a conservative integration/hardening branch based on `main`. It does not merge or replace any of the existing feature branches, and it has not been merged into `main`.

## Changes made on this branch
1. Added relay-side visual-sharing policy: screen frames require `sharePermissions.screen === true`; camera frames require `sharePermissions.camera === true`; invalid frame types and absent permissions fail closed.
2. Added a per-device visual frame budget of 10 frames per second.
3. On disconnect, cleared the device's sharing permissions, data grants and remote-control grants; also invalidated peer grants associated with the disconnected device.
4. Corrected permission-update handling so disabling remote control clears control grants even when there are no data grants.
5. Added Node tests for explicit visual consent, invalid permission values, frame-type validation, per-second rate limits and budget reset.
6. Added a branch CI workflow that runs backend syntax/unit tests, Android unit tests and debug APK assembly on pushes to branches and PRs to main.
7. Fixed Gradle Kotlin DSL import for `java.util.Properties`.
8. Fixed duplicate Android string resource `accessibility_description` and replaced the legacy visible `LIA Voice Engine` string value.
9. Integrated the consent-gated biometric capture source, CameraX/ML Kit/ONNX dependencies and manifest activity from the build-verified hardening branch.
10. Ported the build-verified Android home UI and notification resource import fixes from `work/step-01-source-audit-fixes`; central assistant branding is now Shraddha AI.

## CI evidence for the source branches
As of this report, the branch CI workflow has completed successfully on the following branch heads, with debug APK artifacts uploaded:
- `feature/ai-phone-agent-foundation` — commit `ebff979f078f9db4fc6379bc33509ea7b63432cb`, run 12.
- `redesign-v0.19.2` — commit `7bd0ab2c` (full head verified in Actions), run 15.
- `work/step-01-source-audit-fixes` — commit `e90d902a` (full head verified in Actions), run 34.
- `audit-fixes-v0.19.1` — commit `6cb59acdaa1f86688076a2917b04c0de1f2c44d6`, run 39.

These are debug builds, not signed release APKs. A successful CI build does not replace physical-device, carrier, Edesy, Firebase, Windows companion or Play policy validation.

## Current integration branch verification
- Backend syntax check: successful.
- Backend unit tests: successful.
- Android unit tests + debug APK: latest run is pending/in progress at the time this file was authored. Do not call this branch build-verified until that job completes successfully.
- Previous Android attempts exposed and fixed: Gradle Properties import, duplicate accessibility resource, missing biometric source/dependencies, incorrect `R` package imports and old Android UI source compile errors. The latest run will confirm whether further compile errors remain.

## Still not complete
- Full E2E encryption of binary screen/camera frames is not implemented on this branch; the current fix is server-side consent enforcement and rate limiting only.
- Remote actions still need action-bound approval, structured ACK/result verification and central policy enforcement on every execution path.
- Device credential persistence/revocation, PC relay integration and cross-branch phone-call integration remain separate work.
- Biometric model weights are not bundled; enrollment/matching must not be represented as production identity verification until licensed model files, tensor contracts and physical-device validation are complete.
- Jio no-answer forwarding and Edesy two-way audio have not been verified on a real call.
- No signed Release APK/AAB has been produced.

## Merge rule
Keep this branch and all feature branches unmerged until the latest CI is green, code review reconciles divergent feature implementations, and real-device/provider tests are recorded. Do not wholesale merge the 884-commit redesign or 274-commit hardening branch.
