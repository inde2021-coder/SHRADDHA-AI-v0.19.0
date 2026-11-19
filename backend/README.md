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

## AI task plan safety

The task planner output is normalized server-side. Device controls, app launches, text entry, camera/screen viewing, and data sharing are always marked as requiring user approval even if a model incorrectly returns `requiresApproval: false`. Unknown tools, malformed steps, oversized fields, and steps beyond the first eight are discarded.

## Tests

Run:

```sh
node --test backend/provider-routing.test.js
node --test backend/plan-safety.test.js
node --check backend/server.js
```

Automatic routing requires a deployed gateway and at least one provider API key. Provider API usage and billing remain subject to each provider account.

## Authorized social publishing

`POST /api/social/publish` is protected by the gateway token and rate limit. It supports Telegram Bot API and Mastodon only. The Android UI shows the exact post and requires the user to confirm before sending. The backend independently requires `confirmed: true` and an exact `preview === text` match.

Server-only configuration:
- Telegram: `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID`.
- Mastodon: `MASTODON_BASE_URL`, `MASTODON_ACCESS_TOKEN`, optional `MASTODON_VISIBILITY` (defaults to `unlisted`).

The Mastodon host must be a public HTTPS origin. Tokens are never returned to the app or logged. This connector does not implement Instagram, Facebook, YouTube, or X publishing. Telegram bot posting is limited to the configured destination; Mastodon account scopes and visibility remain subject to the token owner's grants.


## Visual Lens analysis modes

The authenticated `POST /api/vision` route accepts `mode` values `general`, `objects`, `document`, and `machine`. Object mode structures visible objects/counts/relationships without identifying people. Document mode transcribes legible content and marks uncertain text. Machine mode separates visible observations from possible causes and includes electrical/mechanical safety boundaries. These are model-guidance modes, not calibrated measurement, guaranteed OCR, or a certified equipment diagnosis. Image quality and provider capability affect results.
