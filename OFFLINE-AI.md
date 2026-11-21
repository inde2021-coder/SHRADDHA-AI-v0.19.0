# Shraddha AI offline inference setup

Shraddha has two local language-model paths. Neither downloads model weights automatically or sends on-device prompts to a cloud AI provider.

## Android on-device LiteRT-LM

1. Obtain a compatible `.litertlm` model from a trusted source such as the Google AI Edge / LiteRT-LM model community. Check its license and device/RAM requirements.
2. On the phone, open **AI Assistant → On-device Intelligence**.
3. Choose **SELECT ON-DEVICE LLM MODEL** and import the `.litertlm` file.
4. The app copies it to private app storage as `files/offline-models/shraddha.litertlm`. Import is capped at 5 GB and reserves at least 512 MB of free storage.
5. Enter a prompt and choose **RUN LOCAL OFFLINE AI**. Inference uses the LiteRT-LM CPU backend on the phone. There is no automatic cloud fallback.

The model file is not included in the APK or Git repository. Large models may be too slow or exceed memory on low-RAM phones. Start with a small compatible model and test battery, heat, memory pressure, Hindi output and English output on the target phone.

## Windows PC GGUF

1. Install the optional dependency from `desktop-companion/shraddha-pc-companion/requirements-offline.txt`.
2. Obtain a compatible instruct GGUF model from a trusted source and verify its license.
3. Start the Windows companion with `--offline-model "C:\\Models\\assistant.gguf"`.
4. From Android Devices, choose the paired PC, request control, and send an offline prompt. The PC console requires local confirmation before generation.

The Windows model generation itself makes no cloud AI request. Sending the prompt from the phone still requires the authenticated paired-device relay network. This path is local inference on the PC, not offline inference on the phone.

## Important boundaries

- No model weights are bundled; model licensing and download are the user's responsibility.
- The app does not silently switch to a cloud provider if a local model is absent or fails.
- Local model output is not guaranteed to be correct. Do not rely on it alone for medical, legal, financial, electrical or other safety-critical decisions.
