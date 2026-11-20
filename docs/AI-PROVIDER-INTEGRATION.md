# AI provider integration

The Android chat UI now routes text turns through the selected provider.

## Supported adapters

- Gemini: existing Gemini generateContent adapter and encrypted Gemini key store.
- OpenAI: OpenAI Chat Completions API.
- Groq: OpenAI-compatible Chat Completions API.
- Perplexity: Sonar Chat Completions API.
- Claude: Anthropic Messages API with the required version header.

Open Settings > AI & Voice Intelligence. Tap AI Chat Provider to cycle the provider. For non-Gemini providers, save that provider's API key in the encrypted local vault. Keys are not stored in SharedPreferences and are not embedded in the APK.

## Model defaults

The adapters use these initial model IDs: gpt-4o-mini, llama-3.3-70b-versatile, sonar, and claude-3-5-sonnet-latest. Provider model availability and account entitlements change; an HTTP error is surfaced to the chat rather than silently falling back to another provider. Update the model IDs when the provider retires or restricts one.

## Security and limitations

- API keys are sent only to the selected provider's HTTPS endpoint when the user sends a message.
- This is direct client-to-provider traffic. It does not conceal the key from a rooted/compromised device or a user with debugging access.
- Do not commit real API keys, Firebase credentials, or local.properties.
- Provider-specific web search, function calling, image input, streaming, and tool execution are not implied by this text-only adapter.
- The backend proxy, per-provider usage accounting, and failover routing are not implemented by this client adapter.
- Validate each provider with a real user-owned key and test Hindi/English responses before release.
