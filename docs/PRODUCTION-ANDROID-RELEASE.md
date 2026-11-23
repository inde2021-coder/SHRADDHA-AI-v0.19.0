# Shraddha AI — Production Android Release

This release process creates a **release-signed APK and Android App Bundle (AAB)**, verifies both signatures, runs JVM unit tests and Android release lint, and uploads immutable build artifacts. Google Play upload is an explicit, separate manual choice and is restricted to the `main` branch.

## One-time signing key setup

Create the upload/release key on a trusted machine. Keep the resulting JKS file and passwords in a secure offline backup. **Never commit the keystore, passwords, or private key to Git.** Loss of the key can block future updates (unless the app is enrolled in Play App Signing and the relevant upload-key reset process is used).

Use JDK `keytool`:

```bash
keytool -genkeypair -v \
  -keystore shraddha-release.jks \
  -alias shraddha-release \
  -keyalg RSA -keysize 4096 -validity 10000
```

Convert the keystore to one-line Base64 without adding line breaks:

- Linux: `base64 -w 0 shraddha-release.jks`
- macOS: `base64 < shraddha-release.jks | tr -d '\\n'`
- PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("shraddha-release.jks"))`

## Required GitHub Actions secrets

Repository Settings → Secrets and variables → Actions → New repository secret:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded JKS bytes |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | `shraddha-release` (or the alias chosen above) |
| `ANDROID_KEY_PASSWORD` | Private-key password |

Do not put any of these values in workflow files, Gradle files, issues, or chat messages.

## Optional Google Play publishing

To enable the explicit Play upload step:

1. Create a Google Cloud service account with only the minimum required Play Console release permissions.
2. Invite its service-account email in Play Console → Users and permissions, grant access to the **Shraddha AI** app and the required release track.
3. Store the complete service-account JSON as `PLAY_STORE_SERVICE_ACCOUNT_JSON` in GitHub Actions secrets.
4. Confirm the Play Console app exists with package ID `in.shraddha.ai` and that its first-time setup requirements are complete.

The workflow's `publish_to_play` input defaults to **false**. If enabled, it refuses to publish from any ref other than `main`. Choose `internal` first for validation; alpha, beta, and production are explicit track choices. Google Play may still reject a release for account, app listing, policy, signing, version-code, or review requirements.

## Run

GitHub → Actions → **Signed Android Release** → Run workflow.

- Keep `publish_to_play=false` to build and download the signed APK/AAB without uploading to Play.
- Enable it only after Play Console and the service-account secret are configured.
- Download `shraddha-ai-signed-release`; it contains the APK, AAB, and `SHA256SUMS.txt`.

## What the workflow validates

- All four Android signing secrets exist and decode to a valid JKS/alias.
- `:app:testDebugUnitTest` and `:app:lintRelease` pass.
- Release APK and AAB are produced by Gradle with R8 and resource shrinking enabled.
- APK signature is verified with Android `apksigner`; AAB signature is verified with `jarsigner`.
- SHA-256 checksums are generated for both deliverables.

## Current external prerequisites

A signed production artifact cannot be created until the owner supplies the signing secrets. Play upload cannot run until the Play Console app and service-account access are configured. These credentials are intentionally not generated, guessed, or committed by this repository.
