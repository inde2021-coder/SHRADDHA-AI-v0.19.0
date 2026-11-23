# AI provider integration

The Android Chat screen routes text turns through the selected provider. API keys are stored in the encrypted local vault.

## Supported adapters

- **Gemini:** Gemini Developer API `generateContent`. Model discovery prefers the highest available 3.x Flash model and falls back to `gemini-3.8-flash`; it deliberately avoids selecting Gemini 2.5 for new projects because Google restricts generation access for projects without prior 2.5 usage.
- **OpenAI:** OpenAI Chat Completions API, default model `gpt-4.1-mini`.
- **Groq:** OpenAI-compatible Chat Completions API, default model `openai/gpt-oss-120b`. The prior `llama-3.3-70b-versatile` default was retired for developer-tier traffic on 2026-08-16.
- **Perplexity:** Perplexity Agent API `POST /v1/agent`, using the current `effort: fast` setting (the older `preset` field has been superseded). Response parsing reads typed `output[]` messages, search-result items and citation annotations, then attaches returned source URLs to the answer. This replaces the legacy Sonar Chat Completions integration.
- **Claude:** Anthropic Messages API with the required version header, default model `claude-sonnet-5`.

Open Settings > AI & Voice Intelligence. Tap AI Chat Provider to choose a provider. For non-Gemini providers, save that provider's API key in the encrypted local vault. Keys are not stored in SharedPreferences and are not embedded in the APK.

## Scope

The provider selector applies to the typed **Chat** screen. The always-on low-latency **Voice Live** session currently uses Gemini Live (`gemini-3.1-flash-live-preview`) and its separate Gemini API key. Selecting OpenAI, Groq, Claude, or Perplexity does not switch the live microphone session to that provider.

## Model lifecycle

Provider model catalogs and account entitlements change. The app surfaces provider HTTP errors rather than silently switching providers. The client defaults to `gpt-4.1-mini` for compatibility with the existing Chat Completions adapter. Newer OpenAI model families may require the Responses API; do not swap a model ID until its endpoint and response format are verified. Verify model access with the user's own key before release.

## Security and limitations

- API keys are sent only to the selected provider's HTTPS endpoint when the user sends a message.
- This is direct client-to-provider traffic. It does not conceal the key from a rooted/compromised device or a user with debugging access.
- Do not commit real API keys, Firebase credentials, or `local.properties`.
- Provider-specific function calling, image input, and streaming are not implied by this text-only adapter.
- Perplexity's `fast` effort level enables quick web-grounded research; source URLs are displayed when the response includes search results or citation annotations. Presets may resolve to a third-party model.
- The backend proxy, per-provider usage accounting, and failover routing are not implemented by this client adapter.
- Validate each provider with a real user-owned key and test Hindi/English responses before release.


## Backend provider gateway hardening

The authenticated backend gateway now exposes a safe provider catalog at `GET /api/ai/providers`: configured state, selected model IDs, and supported capabilities only. It never returns API key material. Supported server-side keys are `GEMINI_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GROQ_API_KEY`, and `PERPLEXITY_API_KEY`. Optional model overrides are `GEMINI_MODEL`, `OPENAI_MODEL`, `ANTHROPIC_MODEL`, `GROQ_MODEL`, and `PERPLEXITY_MODEL`.

Server-side text, Visual Lens, and Perplexity requests use a shared HTTPS-only transport with a 2 MiB response ceiling and at most three attempts for network failures, timeouts, HTTP 408/425/429 and selected 5xx responses. Authentication, permission, invalid-request and quota policy errors are not blindly retried. The transport honors a bounded Retry-After delay when provided. Automatic provider failover remains opt-in through provider=auto and only selects providers with configured server-side keys; explicit provider selection never silently changes providers.

The Android direct-key text adapter remains a separate client-side mode and is not automatically routed through the backend gateway. For a centrally managed deployment, use the authenticated backend endpoints and keep provider keys only in server environment secrets. Provider-specific real account access, quotas, model entitlements, and billing must still be validated with owner-controlled keys.
