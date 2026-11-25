# Shraddha AI — Free Local Voice Mode (Hindi/English)

## Goal

Provide a voice-assistant path that does not require a paid speech API, paid Gemini Live session, or hosted custom-voice service. Cloud AI providers remain optional; local voice must continue to work when the cloud backend is unset or unavailable.

## What already exists in this repository

- Windows PC companion has an optional Vosk + sounddevice voice-command path. It currently recognizes a single allowlisted device command; it is not yet a continuous conversational speech interface.
- Windows PC companion can run a local GGUF language model through llama.cpp. Model weights are user-supplied and are not committed.
- Android has a local LiteRT-LM import/inference path, but it is a text-only local LLM path and does not itself provide speech recognition or speech synthesis.
- `voice-engine/` is a Piper-based custom TTS service. It requires user-supplied Hindi/English Piper model files and currently exposes an authenticated HTTP service. A hosted deployment can cost money; running it on the user's own Windows PC avoids a separate voice-hosting bill, but requires the PC to be on and reachable.
- Gemini Live token issuance and the hosted Shraddha TTS endpoint are optional cloud voice dependencies, not suitable as mandatory prerequisites for the free mode.

## Target architecture

### Mode A — Phone-only local voice (preferred where device resources permit)

1. Use Android's on-device speech recognition service only when the device reports that on-device recognition is available. Do not silently fall back to a network recognizer.
2. If on-device recognition is unavailable, show a clear option to download/use a compatible local speech model or switch to the paired-PC mode.
3. Use a user-selected locally installed/offline TTS engine or a separately downloaded, licensed local Piper voice pack. Do not require Google/Gemini TTS or a cloud voice URL.
4. Keep local model files outside the APK where possible, with license, checksum, storage and compatibility checks.

### Mode B — Paired Windows PC local voice

1. Run Vosk speech recognition and Piper TTS on the user's own paired Windows PC.
2. Run the user's local GGUF model on that PC for reasoning when selected.
3. Carry microphone audio, transcript, response text and audio over the existing authenticated paired-device channel; do not open an unauthenticated LAN port.
4. Make the PC state explicit: paired, local models ready, microphone permission, voice output ready. If the PC is offline, offer phone-only mode where available.
5. PC-local processing must not call cloud AI providers. The existing PC-local GGUF path already avoids cloud inference for its offline action; extend it to conversational voice only after the relay protocol and consent boundaries are implemented.

## Required product behavior

- Add a distinct **Free Local Voice** mode alongside **Cloud Voice**. Free Local Voice must not ask for `GATEWAY_TOKEN`, Gemini API key, paid STT key, or hosted TTS URL.
- Do not disable text chat, Groq, or other configured AI providers when Secure Voice Backend is missing.
- Do not silently switch from local processing to cloud processing. Ask for explicit opt-in before any cloud fallback.
- Show model download size, language, license, source, checksum, device/PC requirements and storage before downloading.
- Support Hindi and English; report a missing language model clearly rather than silently switching language.
- Never log raw microphone audio, transcripts, API keys or personal speech content.
- Preserve the existing foreground microphone permission and privacy indicators. Stop recording and release audio resources when the user ends the session.
- Do not claim that a stock Piper voice is a unique Shraddha identity. A branded voice requires a properly licensed voice model and documented rights/consent.

## Implementation sequence

1. **Discovery and safety:** map the current Android voice UI, audio lifecycle, paired-device relay protocol and Windows Vosk/llama.cpp integration. Add unit tests for mode selection and cloud opt-in boundaries.
2. **PC-local speech pipeline:** add a persistent Vosk conversational recognizer and Piper synthesis process to the Windows companion. Keep the existing one-shot allowlisted command parser separate. Add explicit local microphone start/stop and no-cloud tests.
3. **Authenticated phone relay:** extend the existing pairing protocol for bounded audio/text/audio-response messages, with session IDs, sequence numbers, size limits, timeouts, replay protection and explicit user consent. Do not expose a public unauthenticated voice endpoint.
4. **Android mode selection:** remove the Secure Voice Backend hard-block from the general voice entry point. Route to Free Local Voice when the paired PC is ready or on-device speech services/models are ready; otherwise explain what is missing.
5. **Phone-only speech:** implement on-device STT/TTS behind capability checks and optional model packs. Keep this separate from the Gemini Live client.
6. **Verification:** run Android unit tests, Python companion tests, protocol/security tests, debug APK build, emulator tests, and physical-device Hindi/English tests. Measure latency, memory, battery, thermal behavior and model storage on the target OPPO CPH2733. No release claim until these pass.

## Acceptance criteria

- With no backend URL, gateway token, Gemini key, or paid speech key configured, the user can still enter Free Local Voice when the local phone/PC prerequisites are satisfied.
- Local mode produces a Hindi and English transcript and spoken response without any network request to a speech/AI provider.
- Airplane-mode test passes for phone-only local mode; PC-paired mode is clearly labeled as requiring the local pairing network.
- Cloud mode remains available as an explicit separate choice.
- Missing model, unsupported language, PC offline, permission denied and low-storage states show actionable errors and never crash.
- Android and Windows automated tests pass, followed by physical-device voice tests.

## Important distinction

This document records the agreed implementation target, not a claim that the complete conversational local voice pipeline is already implemented. The repository currently has building blocks (Vosk one-shot PC voice commands, local GGUF inference, Android local text LLM, and a Piper TTS service); integration and end-to-end tests remain required.
