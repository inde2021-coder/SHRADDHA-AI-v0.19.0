# Knowlarity integration — setup and verified limits

This backend adds a server-side Knowlarity integration foundation. It does **not** yet make Shraddha AI answer a forwarded SIM call and speak live.

## What is implemented

- GET /api/telephony/knowlarity/status — authenticated configuration/capability status.
- POST /api/telephony/knowlarity/call — authenticated outbound PSTN click-to-call request using Knowlarity's documented makecall API.
- POST /webhooks/knowlarity — accepts Knowlarity call lifecycle / post-call events only when the configured webhook token is supplied in the X-Knowlarity-Webhook-Token header.
- GET /api/telephony/knowlarity/events — authenticated latest event view (in-memory only; cleared on restart).

The webhook stores event metadata only. It does not download call recordings, transcribe calls, or send WhatsApp messages.

## Required server environment variables

Set these in the backend host's environment settings (never in Android resources, source control, or a client app):

- KNOWLARITY_API_KEY: Knowlarity application access key.
- KNOWLARITY_SR_API_KEY: SuperReceptionist API key.
- KNOWLARITY_NUMBER: purchased/allocated Knowlarity number in E.164 format.
- KNOWLARITY_CHANNEL: one of Basic, Advance, Premium, Enterprise (defaults to Basic).
- KNOWLARITY_WEBHOOK_TOKEN: long random secret. Configure Knowlarity to send it as the X-Knowlarity-Webhook-Token header. If the Knowlarity webhook product/plan cannot set custom headers, do not expose this webhook publicly until an agreed secure authentication mechanism is configured.

Existing GATEWAY_TOKEN is also required for the three /api/telephony/knowlarity/* routes.

## API examples

Status:

~~~http
GET /api/telephony/knowlarity/status
Authorization: Bearer <GATEWAY_TOKEN>
~~~

Outbound click-to-call:

~~~http
POST /api/telephony/knowlarity/call
Authorization: Bearer <GATEWAY_TOKEN>
Content-Type: application/json

{
  "agentNumber": "+919876543210",
  "customerNumber": "+919812345678"
}
~~~

Knowlarity callback:

~~~http
POST /webhooks/knowlarity
X-Knowlarity-Webhook-Token: <KNOWLARITY_WEBHOOK_TOKEN>
Content-Type: application/json
~~~

## Important gap before enabling SIM forwarding to an AI

Knowlarity's public API reference documents outbound click-to-call, call lifecycle notifications, call logs, and a recording URL after a bridged call ends. Those features do not prove that Knowlarity can stream two-way live call audio to an AI agent or route an inbound call to an arbitrary SIP URI.

Before buying a plan or forwarding the user's primary SIM number, obtain written confirmation from Knowlarity sales/technical support for all of these:

1. Can the purchased Indian virtual number accept forwarded calls from the user's mobile carrier?
2. Can inbound calls be delivered to an external SIP URI / SIP trunk (not merely a human phone agent)?
3. Is two-way, low-latency RTP/SRTP media available to the external SIP endpoint for the entire call?
4. Can the trunk route directly to a custom AI SIP endpoint? If yes, provide supported codecs, transport (TLS/UDP), authentication/IP allow-list requirements, media IP ranges, concurrency, and per-minute charges.
5. Can call lifecycle webhooks include a stable call ID and recording URL, and can custom webhook headers or request signing be configured?

A possible target, if Knowlarity confirms compatible SIP trunking, is an AI SIP endpoint. OpenAI's Realtime API documents an India-region SIP URI pattern: sip:<PROJECT_ID>@sip-in.api.openai.com;transport=tls. This still requires a configured OpenAI project/webhook, provider interoperability confirmation, consent flow, and real call testing. It is not wired into this repository yet.

## Privacy and consent requirements

- Play an upfront Hindi/English notice that the caller is speaking with an AI.
- Do not record or transcribe unless the required notice/consent and applicable legal requirements are satisfied.
- Ask the caller before sending any transcript or summary to WhatsApp. Never send it automatically by default.
- Restrict call recordings/transcripts, set retention/deletion rules, and do not expose recording URLs in client logs.

## Validation status

No Knowlarity account, number, API keys, webhook credentials, SIP trunk, or WhatsApp Cloud API credentials have been supplied. Therefore provider calls, inbound forwarding, live voice, transcript generation, WhatsApp delivery, and real-device call tests remain unverified. The status endpoint reports these gaps instead of claiming the feature is active.
