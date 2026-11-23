# Shraddha AI Hybrid Architecture

## Runtime boundaries

### Android application
The Android APK contains only Android responsibilities: microphone/camera sessions, accessibility and phone actions, local encrypted memory, the Compose UI, WorkManager checkpoints, and authenticated HTTPS clients. Device actions remain local and permission-gated. The APK never embeds Claude/OpenAI/Gemini provider secrets or a Windows executable.

### Windows PC Companion
The Windows agent is a separate Python application under `desktop-companion/shraddha-pc-companion/`. It is packaged and tested by the Windows CI job only. It owns Windows desktop capture, keyboard/mouse control, Windows process integration and PC-specific permissions. Android talks to it through the paired-device relay protocol; Android Gradle does not include Python or Windows packages.

### Cloud HTTP gateway
The backend under `backend/` is the provider and policy boundary. It stores provider credentials in server environment variables and accepts authenticated HTTPS requests. Claude is exposed through Anthropic's Messages API using the server-side `ANTHROPIC_API_KEY`; Android sends only the role, bounded prompt and provider identifier. The gateway's `/api/ai/providers` response determines which server-side providers are available. A gateway route is selected only when that provider is configured; otherwise the app uses its encrypted on-device API key and direct HTTPS adapter.

## Multi-agent role routing

| Agent role | Preferred provider | Fallback order |
|---|---|---|
| Planner | Gemini | OpenAI, Groq, Claude, Perplexity |
| Researcher | Perplexity | Claude, Gemini, OpenAI, Groq |
| Specialist | Claude | Gemini, OpenAI, Groq, Perplexity |
| Critic | OpenAI | Claude, Groq, Gemini, Perplexity |
| Verifier | Groq | OpenAI, Claude, Gemini, Perplexity |

The router balances call counts while honoring role preferences. It uses only configured providers. Each call travels over HTTPS, either through the authenticated cloud gateway or through the provider adapter with an encrypted local key. No API key is copied into task prompts, logs or PC relay messages. If gateway token/provider status fails, the route fails closed instead of silently changing to a different provider.

## APK size and delivery

- Release builds enable R8 minification and resource shrinking.
- CI builds an Android App Bundle (`.aab`) as well as a debug APK. Google Play can generate device-specific split APKs from the bundle.
- Large model weights remain downloaded on demand and are SHA-256 checked; they are not packaged as assets.
- The `shraddhaAbi` Gradle property remains available for device-specific native builds.
- The current Android source is still one application module. This change does **not** pretend that AR/vision, Expert Studio or Smart Home have already been extracted into dynamic feature modules. Those screens currently have direct navigation references and their dependency graph must be moved with them before Play Feature Delivery can safely unload them. The next extraction candidates are Vision/AR (ARCore + ML Kit), Expert Studio, and Smart Home (MQTT). Keep base voice, settings, chat and local memory in the base module.

## Data and action flow

1. Android receives a user request and classifies it as local-device, PC, or cloud reasoning.
2. Local-device actions go to the Android permission-gated operator.
3. Windows actions are handed to a paired PC agent and require the PC's own consent.
4. Reasoning tasks are assigned to role-specialized providers through the HTTPS gateway or encrypted local keys.
5. Long-running work is checkpointed by WorkManager. Provider usage is recorded only when the provider reports counters; cloud `/api/chat` currently does not return token usage, so usage remains unknown rather than estimated.
6. Publishing, messaging, deletion and remote control still require exact preview/approval gates.

## Current limitations

The gateway currently provides synchronous provider calls with token-usage evidence returned to the app for budget enforcement; when usage is absent and a monthly cap is enabled, the app marks usage unknown and stops further council calls. The app also maintains local durable workflow checkpoints. It is not a general-purpose cloud task queue. Remote desktop media streaming and a full Windows screen-control session remain PC-specific and must not be moved into the Android APK. Play dynamic delivery requires a later source/module extraction and App Bundle validation before those modules can be called on-demand.
