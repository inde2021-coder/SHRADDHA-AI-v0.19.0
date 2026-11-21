# Shraddha AI — Android local LLM setup

Shraddha AI has a native Android inference path using Google's LiteRT-LM Kotlin runtime. Model prompts and inference stay in the app process; this local feature has no cloud fallback.

## Install a compatible model

1. Obtain a compatible `.litertlm` model from the [LiteRT Community model collection](https://huggingface.co/LiteRT).
2. Review the model licence and usage terms.
3. Choose a model that fits the phone's RAM and storage. Weights are not bundled in the APK.
4. In the AI Assistant workspace, select **SELECT ON-DEVICE LLM MODEL** and import the file.
5. Select **CHECK DEVICE RAM & STORAGE** before inference.

## Memory preflight

The app reports total RAM, currently available RAM, model size, a conservative headroom estimate, free internal storage, Java heap usage, native heap usage and Android low-RAM mode. It blocks a run when Android reports low-memory mode or available RAM is below 1.25× model file size plus 450 MiB. This is a conservative preflight, not a guarantee: native runtime allocations, KV cache, prompt length, concurrent apps and thermal limits can still cause an out-of-memory failure.

Keep at least 256 MiB free internal storage. If preflight blocks, close heavy apps or import a smaller/quantized model. A 1B/2B model may be too large for some phones even if the file can be copied.

## Hardware backends

The backend selector offers:
- **Auto**: try NPU, then GPU, then CPU.
- **GPU**: try GPU, then CPU if the device/runtime cannot initialize GPU.
- **NPU**: try NPU, then GPU, then CPU.
- **CPU**: force CPU.

Android GPU support depends on vendor/SoC OpenCL availability. The manifest declares optional `libvndksupport.so` and `libOpenCL.so` native libraries as recommended by the official LiteRT-LM Android guide. NPU initialization uses the app native-library directory. Unsupported accelerators fall back to CPU. The status panel reports the backend that initialized successfully.

Acceleration is device- and model-dependent. Selecting GPU or NPU does not guarantee that the device supports it; verify the reported backend and benchmark on the actual phone. Do not describe a CPU fallback as accelerated inference.

## Runtime behaviour and limitations

- LiteRT-LM engine initialization runs on a background thread and may take several seconds.
- The app closes native engine/conversation resources when the activity is destroyed.
- This is a local text-chat path, not a complete offline voice pipeline. Speech recognition and speech output may still depend on Android system services or separately configured voice models.
- The Android offline model is separate from the Windows companion's optional GGUF/llama.cpp local inference.
- If model loading fails, try a smaller quantized model or CPU backend.

## Build and official references

- [LiteRT-LM Android guide](https://developers.google.com/edge/litert-lm/android)
- [LiteRT Android runtime and accelerators](https://developer.android.com/ai/custom)
- [LiteRT hardware backends](https://developers.google.com/edge/litert)

The Gradle dependency is pinned in `app/build.gradle.kts` to `com.google.ai.edge.litertlm:litertlm-android:0.19.1` for reproducible builds. Check the project's version catalog before upgrading it.