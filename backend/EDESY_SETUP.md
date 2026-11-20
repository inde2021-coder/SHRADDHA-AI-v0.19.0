# Shraddha AI — Edesy Internet Calling

## Implemented backend path

- `GET /api/telephony/edesy/status` — gateway-authenticated readiness status.
- `wss://YOUR_BACKEND_HOST/ws/telephony/edesy?token=...` — provider WebSocket ingress, protected by a long random server-side token.
- Edesy sends its first JSON call-metadata frame, then binary 16 kHz mono L16 PCM frames. The bridge forwards audio to OpenAI Live over a server-owned WebSocket and returns generated audio to Edesy.
- PCM16 audio is resampled between Edesy's 16 kHz stream and OpenAI Live's 24 kHz stream.
- OpenAI API keys stay on the backend. The Android app does not receive provider credentials.

## Server environment

Set these in the backend hosting dashboard (not in Android or GitHub):

- `GATEWAY_TOKEN` — existing app gateway token.
- `EDESY_STREAM_TOKEN` — long random token for the Edesy WebSocket URL.
- `OPENAI_API_KEY` — server-side OpenAI key with Live API access.
- Optional: `OPENAI_LIVE_MODEL`, `OPENAI_LIVE_VOICE`, `SHRADDHA_CALL_INSTRUCTIONS`.

## Edesy setup

1. Create an Edesy tenant at https://voice-app.edesy.in and complete required KYC.
2. Provision an Indian DID number and verify inbound calling on the provider's test facility.
3. Create an AI Voice Agent / Stream flow and set the bot WebSocket URL to `wss://YOUR_BACKEND_HOST/ws/telephony/edesy?token=YOUR_EDESY_STREAM_TOKEN`.
4. Set the stream sample rate to 16 kHz and confirm the provider sends mono L16 PCM binary frames plus a JSON metadata frame first.
5. Route the purchased DID to that flow and place a test call from a separate phone.
6. Check `GET /api/telephony/edesy/status` with the existing gateway bearer token.

Provider documentation:
- https://www.edesy.in/docs/sip-trunk-for-ai-voice-agents/connect/websocket-streaming
- https://edesy.in/docs/ivr/nodes/stream-to-ai
- https://developers.openai.com/api/docs/guides/voice-websockets

## Important limitations / release gate

- This is a backend integration implementation, not proof of a working paid Edesy account or successful PSTN call.
- Edesy account, DID, API credentials, OpenAI Live API key/access, hosting environment variables, and provider flow configuration must be supplied/configured by the account owner.
- The current adapter assumes the Edesy Stream node's documented first JSON metadata frame and subsequent binary L16 frames. Confirm actual deployed provider frame behavior with a real test call before production.
- Call transcripts are not persisted; WhatsApp summaries are not sent. Add an explicit caller opt-in and WhatsApp consent/recipient confirmation before enabling those functions.
- Play an upfront notice that the caller is speaking with an AI assistant. Recording/transcription requires appropriate notice, consent, retention controls, and legal review.
- Never expose the stream token or OpenAI API key in the Android app, logs, client settings, or public repository.
- Do not point a personal SIM's unconditional forwarding at this number until inbound routing, audio in both directions, hang-up behavior, interruption handling, cost, and failover have been tested end-to-end.

## Cost reference

Edesy's published SIP trunk pricing lists ₹0.50/minute (60-second billing pulse), DID rental from ₹449/month, and 18% GST excluded. Confirm current charges, number availability, KYC, and whether the chosen AI Voice Agent Stream feature is included before paying:
https://edesy.in/docs/sip-trunking/getting-started/pricing-and-limits

OpenAI Live usage is billed separately. Actual total cost depends on model, audio duration, and usage pricing.
