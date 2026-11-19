# Shraddha AI — Android local LLM setup

Shraddha AI now has a native Android inference path using Google's LiteRT-LM Kotlin runtime. Inference runs in-process on the phone using the CPU backend; the local model prompt is not sent to the cloud gateway and there is no cloud fallback in this feature.

## Install a compatible model

1. Obtain a `.litertlm` model from the [LiteRT Community model collection](https://huggingface.co/LiteRT).
2. Review and accept that model's licence and usage terms before downloading it.
3. Choose a model compatible with LiteRT-LM and sized for the phone's RAM and free storage. Model files are large and are intentionally not bundled in the APK.
4. In Shraddha AI, open the AI Assistant workspace, select **SELECT ON-DEVICE LLM MODEL**, and choose the `.litertlm` file.
5. Enter a prompt and select **RUN LOCAL OFFLINE AI**. The first model load can take several seconds; inference speed depends on CPU, RAM, thermal limits, and model size.

The app copies the model into its private internal files directory. Import is capped at 5 GB and keeps at least 512 MB free. It writes to a temporary file and atomically replaces the previously imported model when supported. The model is not uploaded to Firebase or the backend.

## Runtime behaviour and limitations

- LiteRT-LM runs in the Android app process. The current implementation uses CPU backend for broad compatibility; GPU/NPU selection and benchmarking are future work.
- A compatible model must be supplied by the user. No model weights, download URL, or model licence is silently assumed.
- This is a local text-chat path, not a complete offline voice pipeline. Speech recognition and speech output may still depend on Android system services or separately configured voice models.
- The Android offline model is separate from the Windows companion's optional GGUF/llama.cpp local inference.
- The local engine is closed when the activity is destroyed to release native memory.
- Not all Android devices can run every model. If model loading fails, use a smaller compatible model or a device with more available memory.

## Build

The Android app uses the official Maven dependency `com.google.ai.edge.litertlm:litertlm-android`. The Gradle build resolves the current published release. See the official [LiteRT-LM Android guide](https://developers.google.com/edge/litert-lm/android) for runtime and model-format details.
