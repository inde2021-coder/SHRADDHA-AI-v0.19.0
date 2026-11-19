# Shraddha AI Backend Provider Routing

## Automatic AI provider failover

The authenticated gateway supports `provider: "auto"` for text chat, summarization, task planning, Studio work, Expert Assist, and vision analysis.

- Only providers with non-empty server-side API keys are eligible.
- The gateway tries providers sequentially and returns the provider that produced the successful response.
- A manually selected provider remains fixed; it does not silently switch to another provider.
- API keys are never returned by the provider-status endpoint.
- Vision auto-routing is limited to OpenAI, Gemini, and Anthropic because the other configured providers are not wired to the vision API.

Configure the order in the backend environment:

```dotenv
AI_DEFAULT_PROVIDER=gemini
AI_PROVIDER_ORDER=gemini,openai,anthropic,groq,perplexity
```

The default provider is attempted first, followed by the comma-separated order and then the remaining supported providers. Unconfigured providers are skipped. Unknown names and duplicates are ignored.

If all configured providers fail, the gateway returns an error describing provider names and bounded error messages; it never reports a fabricated answer.

## AI task plan safety\n\nThe task planner output is normalized server-side. Device controls, app launches, text entry, camera/screen viewing, and data sharing are always marked as requiring user approval even if a model incorrectly returns `requiresApproval: false`. Unknown tools, malformed steps, oversized fields, and steps beyond the first eight are discarded.\n\n## Tests

Run:

```sh
node --test backend/provider-routing.test.js\nnode --test backend/plan-safety.test.js
node --check backend/server.js
```

Automatic routing requires a deployed gateway and at least one provider API key. Provider API usage and billing remain subject to each provider account.
