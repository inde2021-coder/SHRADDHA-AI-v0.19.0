# AI provider integration

The Android Chat screen routes text turns through the selected provider. API keys are stored in the encrypted local vault.

## Supported adapters

- **Gemini:** Gemini Developer API `generateContent`. Model discovery prefers the highest available 3.x Flash model and falls back to `gemini-3.8-flash`; it deliberately avoids selecting Gemini 2.5 for new projects because Google restricts generation access for projects without prior 2.5 usage.
- **OpenAI:** OpenAI Chat Completions API, default model `gpt-4o-mini`.
- **Groq:** OpenAI-compatible Chat Completions API, default model `openai/gpt-oss-120b`. The prior `llama-3.3-70b-versatile` default was retired for developer-tier traffic on 2026-08-16.
- **Perplexity:** Perplexity Agent API `POST /v1/agent`, using the `fast` preset. Response parsing reads typed `output[]` messages and attaches returned web-source URLs to the answer. This replaces the retired Sonar Chat Completions integration.
- **Claude:** Anthropic Messages API with the required version header, default model `claude-sonnet-5-5`.

Open Settings > AI & Voice Intelligence. Tap AI Chat Provider to choose a provider. For non-Gemini providers, save that provider's API key in the encrypted local vault. Keys are not stored in SharedPreferences and are not embedded in the APK.

## Scope

The provider selector applies to the typed **Chat** screen. The always-on low-latency **Voice Live** session currently uses Gemini Live (`gemini-3.1-flash-live-preview`) and its separate Gemini API key. Selecting OpenAI, Groq, Claude, or Perplexity does not switch the live microphone session to that provider.

## Model lifecycle

Provider model catalogs and account entitlements change. The app surfaces provider HTTP errors rather than silently switching providers. OpenAI's `gpt-4o-mini` remains callable but is deprecated with a published shutdown date of 2027-01-14; update the default before that date after validating the replacement against the app's request format. Verify model access with the user's own key before release.

## Security and limitations

- API keys are sent only to the selected provider's HTTPS endpoint when the user sends a message.
- This is direct client-to-provider traffic. It does not conceal the key from a rooted/compromised device or a user with debugging access.
- Do not commit real API keys, Firebase credentials, or `local.properties`.
- Provider-specific function calling, image input, and streaming are not implied by this text-only adapter.
- Perplexity's `fast` preset can use web search; source URLs are displayed when the response includes search results. Presets may resolve to a third-party model.
- The backend proxy, per-provider usage accounting, and failover routing are not implemented by this client adapter.
- Validate each provider with a real user-owned key and test Hindi/English responses before release.
