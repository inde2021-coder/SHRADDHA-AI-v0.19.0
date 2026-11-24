# Shraddha AI communications and social delivery

## SMS and WhatsApp provider relay

Android sends provider-based SMS and WhatsApp requests to the authenticated backend endpoint `POST /api/messages/send`. Twilio credentials remain server-side:

- `GATEWAY_TOKEN`: required bearer token for the private gateway.
- `TWILIO_ACCOUNT_SID` and `TWILIO_AUTH_TOKEN`: Twilio account credentials.
- `TWILIO_SMS_FROM`: approved SMS sender/number.
- `TWILIO_WHATSAPP_FROM`: approved WhatsApp sender, with or without the `whatsapp:` prefix.

A request must include channel (`sms` or `whatsapp`), an E.164 recipient, message text (1-4000 characters), a byte-for-byte identical preview, `confirmed: true`, and a unique request ID. The Android action first returns the full resolved recipient and exact message preview; the user must explicitly approve it. The same request ID must be reused for the approved send. A changed recipient or body requires a new preview and approval.

The backend rejects unauthenticated, unconfirmed, malformed, or duplicate requests. A bounded process-local replay guard blocks concurrent and repeated sends. It is not a durable distributed idempotency ledger: a server restart clears this guard. If a network timeout leaves the provider outcome unknown, check the provider console before creating a new send request. Never interpret provider acceptance as handset delivery; delivery/read status requires provider callbacks and applicable carrier support.

If provider credentials are absent, the endpoint returns a configuration error and does not silently fall back to another channel. The Android foreground composer path may be available as a separate user-visible action when no provider gateway is configured; it does not bypass the exact-recipient and exact-message approval gate.

## Social publishing

Supported platform adapters are Telegram, Mastodon, Bluesky, LinkedIn, Facebook Pages, and Instagram professional accounts. Publishing requires an exact preview and explicit confirmation. The app presents the full text and selected media details before confirmation. Scheduling requires Firebase sign-in, Firebase Admin credentials on the backend, and an authenticated owner UID. Scheduled posts use idempotency keys and Firestore-backed ownership/state.

Server environment credentials (only configure the platforms actually used):

- `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`
- `MASTODON_BASE_URL`, `MASTODON_ACCESS_TOKEN`, optional `MASTODON_VISIBILITY`
- `BLUESKY_IDENTIFIER`, `BLUESKY_APP_PASSWORD`
- `LINKEDIN_ACCESS_TOKEN`, `LINKEDIN_AUTHOR_URN`, `LINKEDIN_VERSION`
- `FACEBOOK_PAGE_ID`, `FACEBOOK_PAGE_ACCESS_TOKEN`
- `INSTAGRAM_ACCOUNT_ID`, `INSTAGRAM_ACCESS_TOKEN`
- `META_GRAPH_API_VERSION`
- `FIREBASE_SERVICE_ACCOUNT_JSON` for durable scheduling and protected media storage

Meta media publishing requires public HTTPS media URLs that Meta can fetch. Provider account permissions, review/app mode, token validity, quotas, media processing, and live posting must be verified with the owner's real accounts. Never put provider secrets in Android assets or commit them to Git.

## Validation boundaries

Automated tests validate request policy, exact-preview confirmation, E.164 formatting, replay protection, supported platform/media constraints, scheduler ownership/idempotency, and no-credential error handling. They do not send real messages or publish real social posts. Actual delivery depends on approved provider accounts, sender registration, recipient opt-in, account permissions, Firebase provisioning, and regional regulations.