# Shraddha AI — Edesy inbound AI call bridge

## What is implemented

- `GET /api/telephony/edesy/status` reports server-side readiness (gateway token required).
- `GET /api/telephony/edesy/calls` returns a bounded in-memory call list (gateway token required).
- `wss://YOUR_BACKEND_HOST/ws/telephony/edesy?token=...` is the provider WebSocket ingress.
- Edesy sends a JSON metadata frame, then binary 16 kHz mono PCM16 frames. The backend streams them to GPT-Live over a server-owned WebSocket and sends generated 16 kHz PCM16 audio back.
- The backend resamples Edesy 16 kHz audio to GPT-Live's 24 kHz PCM format and back.
- The OpenAI key and Edesy stream token remain server-side. No normal SIM audio is intercepted by Android.

## Server environment

Set these only in the backend host's secret/environment settings:

- `GATEWAY_TOKEN` — existing app API gateway secret.
- `EDESY_STREAM_TOKEN` — long random secret for the provider WebSocket.
- `OPENAI_API_KEY` — OpenAI project key with GPT-Live access.
- Optional: `OPENAI_LIVE_MODEL` (default `gpt-live-1.1`), `OPENAI_LIVE_VOICE`, `SHRADDHA_CALL_INSTRUCTIONS`.
- `CALL_TRANSCRIPTS_ENABLED=false` by default. Enable only after caller notice/consent and retention review.

## Provision Edesy

1. Create and verify an Edesy tenant, complete KYC, and provision an Indian DID.
2. Create an inbound AI Voice Agent / Stream flow.
3. Set its WebSocket URL to `wss://YOUR_BACKEND_HOST/ws/telephony/edesy?token=YOUR_EDESY_STREAM_TOKEN`.
4. Configure 16 kHz mono L16/PCM streaming and the documented JSON call-metadata first frame.
5. Route the DID to this flow and call it from a separate phone.
6. Check `GET /api/telephony/edesy/status` with the existing gateway bearer token.

The stream token is in a query string because the provider connector uses a WebSocket URL. Configure the hosting proxy and access logs to redact query strings for this route. Rotate the token if it may have appeared in logs.

## Personal SIM: ring first, then Shraddha AI

The caller continues to dial the existing Jio SIM number. This is carrier call forwarding, not Android call interception.

1. First complete an inbound DID test and verify two-way AI audio, hang-up, interruption and cost.
2. Only after that, ask Jio to confirm the supported **no-answer / no-reply conditional forwarding** activation method, delay and charges for the specific plan.
3. Forward **no-answer only** to the Edesy DID. Keep unconditional forwarding disabled. Leave busy/unreachable forwarding disabled unless separately requested.
4. Set the no-answer delay so the SIM handset rings first (typically 20–30 seconds where the carrier allows it).
5. Test from another phone: answer the SIM call (AI must not join); ignore it (it should forward after the delay); separately verify rejected/busy and unreachable cases.
6. Keep the carrier method to disable forwarding available as a fallback.

Do not use generic USSD codes without Jio confirming the current code and plan support. Forwarded-call charges, Edesy DID/minute charges and GPT-Live usage are separate.

## Release gates and limitations

- Code integration does not prove that an Edesy tenant, DID, provider flow or paid API access has been provisioned.
- No forwarding should be enabled on the personal SIM until real two-way calls pass on the actual Edesy account.
- Android `InCallService` cannot capture ordinary SIM-call audio on standard Android. The SIM-first/no-answer behavior depends on Jio's network forwarding to the Edesy DID.
- Transcript capture is off by default. When enabled, transcripts and summaries are held in bounded server memory and disappear on restart. No persistent audio recording, encrypted durable call history, caller consent workflow, WhatsApp delivery or human takeover is implemented yet.
- Configure an upfront AI disclosure in the Edesy flow and the GPT-Live prompt. Review recording/transcription notice and consent requirements before enabling transcript capture.
