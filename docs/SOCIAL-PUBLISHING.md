# Shraddha AI — Communications and social publishing

## Social account ownership

The backend uses shared social-provider credentials. Set `SOCIAL_PUBLISHER_OWNER_UID` to the Firebase Authentication UID of the one account authorized to publish and manage scheduled posts. Immediate publish, schedule, list and cancel routes require both the gateway bearer token and a valid Firebase ID token for this exact UID. If the owner UID is missing, social publishing is disabled (HTTP 503); other signed-in accounts receive HTTP 403. Obtain the UID from Firebase Authentication for the intended owner account; do not use an email address.


## SMS and WhatsApp messaging

The Android voice tool `message_contact` resolves a contact and shows the exact E.164 recipient, message body and a one-time request ID before sending. It will not send until the user confirms that exact recipient and exact text. The follow-up tool call must echo `user_confirmed=yes`, `confirmed_recipient`, `confirmed_message` byte-for-byte and the same `request_id`.

Provider mode uses the authenticated backend `POST /api/messages/send`. The backend requires:
- `GATEWAY_TOKEN` — bearer token for the private gateway.
- `TWILIO_ACCOUNT_SID` and `TWILIO_AUTH_TOKEN`.
- `TWILIO_SMS_FROM` for SMS.
- `TWILIO_WHATSAPP_FROM` for WhatsApp (approved WhatsApp sender, with or without the `whatsapp:` prefix).

The destination must be E.164 format, for example `+919876543210`. Message text is limited to 1–4000 safe characters. Provider credentials remain on the server. A successful API response means Twilio accepted the message; it is not proof that the recipient handset received or read it.

The backend has a bounded process-local replay guard: a request ID cannot be submitted twice while pending, after acceptance, or during the short failed-attempt hold. The same request ID must be reused after an uncertain network outcome. The replay guard is in-memory and resets when the backend process restarts; durable cross-restart idempotency and Twilio delivery-status webhooks are not yet implemented. The app deliberately does not fall back to local SMS/WhatsApp sending after a provider error, to avoid accidental duplicate delivery.

When no provider gateway is configured, Android can use its local SMS service or the verified foreground WhatsApp flow after the same exact-preview confirmation. Local SMS handoff does not prove handset delivery. WhatsApp automation requires Android Accessibility permission and verifies the foreground recipient before submission.

## Supported social platforms

The authenticated Social Publisher supports:
- Telegram Bot API: text and bounded image/video posts to the configured chat/channel.
- Mastodon API: text and bounded image/video status posts; visibility defaults to `unlisted`.
- Bluesky AT Protocol: text posts using a dedicated app password.
- LinkedIn Posts API: text posts using the configured member/organization author URN.
- Facebook Pages: text, image and video posts.
- Instagram professional accounts: Feed image, Reel, Story image and Story video publishing.

Every publish request requires `confirmed: true` and `preview` exactly equal to `text`. The Android Social Publisher requires a final confirmation before publishing. Platform credentials stay in backend environment secrets; never place them in the APK.

## Backend environment

Common:
- `GATEWAY_TOKEN`
- `FIREBASE_SERVICE_ACCOUNT_JSON` for durable schedule storage and Firebase user-token verification.
- `SOCIAL_SCHEDULER_CRON_SECRET` for the scheduler worker.

Telegram:
- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_CHAT_ID`

Mastodon:
- `MASTODON_BASE_URL` — public HTTPS instance origin only.
- `MASTODON_ACCESS_TOKEN`
- `MASTODON_VISIBILITY` — `public`, `unlisted`, `private` or `direct`; defaults to `unlisted`.

Bluesky:
- `BLUESKY_IDENTIFIER` — handle or account email.
- `BLUESKY_APP_PASSWORD` — use a dedicated app password, never the account password.

LinkedIn:
- `LINKEDIN_ACCESS_TOKEN`
- `LINKEDIN_AUTHOR_URN` — `urn:li:person:...` or `urn:li:organization:...`.
- `LINKEDIN_VERSION` — supported API version in `YYYYMM` format.
Member posting requires `w_member_social`; organization posting requires `w_organization_social` and the appropriate organization page role. LinkedIn may require app review and approval.

Facebook:
- `FACEBOOK_PAGE_ID`
- `FACEBOOK_PAGE_ACCESS_TOKEN`
- `META_GRAPH_API_VERSION` — for example `v24.0`, subject to Meta's currently supported versions.

Instagram:
- `INSTAGRAM_ACCOUNT_ID`
- `INSTAGRAM_ACCESS_TOKEN`
- `META_GRAPH_API_VERSION`
The Instagram professional account must meet Meta's account/page linkage and API eligibility requirements. Meta must be able to fetch the supplied public HTTPS media URL. Video containers are polled before publishing; ambiguous publish outcomes must be checked in the account before any manual retry.

## Scheduled publishing

The Android screen supports schedule creation, history refresh and cancellation. Firestore collection `socialScheduledPosts` stores records scoped to the authenticated Firebase UID. The backend checks both the gateway bearer token and Firebase ID token for user-owned schedule operations. An idempotency key prevents duplicate schedule creation. Schedules can be created up to 90 days ahead and require exact-preview confirmation. Facebook and Instagram media schedules retain a public HTTPS URL; keep that URL reachable until dispatch time.

The GitHub workflow `.github/workflows/social-scheduler.yml` calls `POST /api/social/schedule/dispatch` every five minutes. Configure these GitHub Actions secrets:
- `SHRADDHA_BACKEND_ORIGIN` — backend HTTPS origin without trailing slash.
- `SOCIAL_SCHEDULER_CRON_SECRET` — same value as the backend environment secret.

A due job is claimed before the provider call. Failed or ambiguous outcomes are not blindly retried because a platform may have accepted the post before the network failed. Check the platform account before manual retry.

## Limits and external requirements

- Bluesky is limited to 300 Unicode code points per post.
- LinkedIn text is limited to 3000 characters by this connector.
- Telegram and Mastodon media uploads are bounded to 8 MiB by the backend.
- Android Firebase Storage media selection allows up to 100 MiB for Meta media URLs; Meta's own format, duration, aspect-ratio, account and API limits still apply.
- YouTube video upload, comment/analytics management, editing/deleting published posts, and automatic browser login are not provided.
- The share-sheet receiver is not yet wired into the publisher review flow; use the in-app Social Publisher to compose, preview and confirm posts.
- Never automate social website logins or store a user's social account password. Use official APIs and dedicated tokens/app passwords only.
