# Shraddha AI — Authorized social publishing

## Supported platforms

- Telegram Bot API: text messages to the configured channel/chat.
- Mastodon API: text status; visibility is configured server-side and defaults to `unlisted`.
- Bluesky AT Protocol: text posts through a dedicated Bluesky app password.
- LinkedIn Posts API: text-only member or organization posts through a bearer access token and configured author URN.

Every request requires `confirmed: true` and `preview` exactly equal to `text`. The Android UI shows the exact text and requires a final Publish confirmation. The gateway token and platform credentials remain on the backend; do not put platform secrets in the Android app.

## Backend environment

Common:
- `GATEWAY_TOKEN` — required gateway bearer token.

Telegram:
- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_CHAT_ID`

Mastodon:
- `MASTODON_BASE_URL` — public HTTPS instance origin only.
- `MASTODON_ACCESS_TOKEN`
- `MASTODON_VISIBILITY` — `public`, `unlisted`, `private` or `direct`; defaults to `unlisted`.

Bluesky:
- `BLUESKY_IDENTIFIER` — handle or account email.
- `BLUESKY_APP_PASSWORD` — create a dedicated app password in Bluesky settings; never use the account password.

LinkedIn:
- `LINKEDIN_ACCESS_TOKEN` — OAuth access token issued to an app with the required Community Management / posting access.
- `LINKEDIN_AUTHOR_URN` — `urn:li:person:...` or `urn:li:organization:...`.
- `LINKEDIN_VERSION` — API version in `YYYYMM` format, set to a currently supported version.

LinkedIn member posting requires `w_member_social`. Organization posting requires `w_organization_social` and an appropriate organization page role. API access may require LinkedIn review/approval; a token alone does not guarantee entitlement.

## Advanced publishing status

The authenticated publishing endpoint supports text posts on Telegram, Mastodon, Bluesky and LinkedIn. Bounded image/video uploads (JPEG/PNG/WebP images and MP4/MOV videos, max 8 MiB) are currently implemented only for Telegram and Mastodon. Media upload is not yet wired to a native Android publisher UI. Scheduled publishing, Instagram/Facebook publishing, Reels/Stories and YouTube video upload are not implemented. Do not treat the share-sheet receiver as a publisher: it currently closes without uploading or publishing media. Scheduling requires durable queue storage, idempotent execution and explicit schedule approval; these are still pending.

## Scope and limits

- Bluesky is limited to 300 Unicode code points per post.
- LinkedIn is limited to 3000 characters by this connector.
- Telegram and Mastodon support bounded image/video uploads; Bluesky and LinkedIn remain text-only. The connector does not yet schedule posts, read comments/analytics, or delete/edit published posts.
- Instagram and Facebook Page publishing are not enabled; they require Meta app review, eligible account/page linkage and media-specific publishing flows. YouTube video upload is not enabled because it requires OAuth scopes, resumable media upload and upload processing/status handling.
- Never automate browser login or store a user's social password. Use official APIs and dedicated tokens/app passwords only.

## Official API references

- [Bluesky AT Protocol createRecord](https://docs.bsky.app/docs/api/com-atproto-repo-create-record)
- [LinkedIn Posts API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/posts-api)
- [YouTube videos.insert](https://developers.google.com/youtube/v3/docs/videos/insert)