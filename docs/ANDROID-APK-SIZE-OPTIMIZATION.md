# Android APK size optimization — implementation status

## Implemented in this change

- Compress native `.so` libraries in APK packaging (`useLegacyPackaging = true`). Android extracts the libraries during installation; this trades some installed storage for a smaller sideload APK download.
- Add an arm64-only release size-audit workflow. It runs unit tests and release lint, builds both APK and AAB, records exact byte/MiB sizes, and uploads the artifacts and report.
- The existing release R8 minification and resource shrinking remain enabled.
- The existing `shraddhaAbi` Gradle property is used to avoid shipping unused CPU architectures in the arm64 APK.

## Important: what is not yet modularized

The Android source is still a single Gradle `:app` module. Camera/AR, ML Kit, ONNX Runtime, LiteRT-LM and MQTT dependencies are currently in that module's dependency graph. Moving these into Play Dynamic Feature modules safely requires extracting their implementation classes behind base-module interfaces and removing direct navigation/class references. This change deliberately does not add empty feature modules or hide dependencies, because that would not reduce the delivered APK and could break runtime behavior.

## Next extraction candidates

1. Vision/AR: `in.shraddha.ai.vision` and AR mapping screens.
2. On-device biometric inference: `in.shraddha.ai.biometric` and ONNX runtime.
3. Smart Home: MQTT bridge and Smart Home screen.
4. Offline inference: LiteRT-LM integration and model management.

Each extraction must include a base API contract, feature installation state handling, fallback UI, and install/uninstall instrumentation tests. Play Feature Delivery applies to Play-delivered AABs; sideload APKs must keep supported functionality in the installed package or use separately installed signed app updates. Model/data packs may remain on-demand downloads after checksum verification.
