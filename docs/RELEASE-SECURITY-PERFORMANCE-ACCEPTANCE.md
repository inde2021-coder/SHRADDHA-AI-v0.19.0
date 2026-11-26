# Shraddha AI release security and performance acceptance

## Static release gates
- App data backup remains disabled.
- Cleartext traffic and debuggable release configuration are rejected.
- Exported services require an explicit Android binding permission; exported activities/receivers need declared intent filters.
- Camera and microphone foreground services declare their required service types.
- Release R8 minification and resource shrinking remain enabled.
- Signing material stays in CI environment secrets; keystores, service-account JSON and raw `.env` files are not committed.
- High-confidence private-key, service-account, common provider-token and APK-embedded credential patterns are scanned by `tools/release_security_audit.py`.
- The static audit is a guardrail, not a replacement for a full independent mobile penetration test or Play policy review.

## Runtime resource protection
- Live OCR/object recognition checks Android heap pressure before starting another inference. At 90% heap use or invalid memory telemetry it pauses local inference rather than increasing crash risk.
- The shared runtime budget policy defines slower frame intervals for battery saver, memory pressure and thermal throttling, and blocks cloud analysis unless consent and healthy device conditions are present.
- Model downloads and optional AI assets must remain size-bounded and must never be bundled as large APK assets.

## Required final device test matrix
1. Android 8 through current supported Android versions; verify permission prompts, camera/microphone indicators and foreground notifications.
2. Real phone: cold launch, voice session, Visual Lens local-only, cloud-consented mode, camera permission revoke, background/foreground, screen lock and service stop.
3. Memory pressure and battery saver: run repeated camera sessions; confirm frame processing backs off and the app does not crash or leak camera sessions.
4. Airplane mode: offline chat, local knowledge, local skills, OCR and voice behavior; verify no silent paid API fallback.
5. Android↔Windows: pairing, reconnect, mutual fingerprint confirmation, one-action approval, OCR, approved file/folder access, bidirectional file transfer, checksum verification and emergency disconnect.
6. Security: replayed action IDs, altered E2E ciphertext, unverified peer, sensitive-window OCR, path traversal, revoked permission and expired grants must all fail closed.
7. Release: debug and optimized arm64 builds, release lint, APK/AAB signature verification, checksum report, APK size report, and install/launch from a clean device.
8. Confirm API keys are not embedded in the APK. Firebase client configuration is public client configuration, not authorization; server credentials and private keys must remain outside the app.

## Known external requirements
- Physical Android phone and Windows PC are required for the end-to-end checks above. GitHub-hosted CI cannot prove camera hardware, battery behavior, Windows desktop focus or real-world pairing.
- A production-signed release requires the owner's secure signing setup. Never create or guess release credentials in source control or chat.
- Play Store publishing is a separate explicit owner action after policy, listing, signing and account checks.
