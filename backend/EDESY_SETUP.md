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
- `EDESY_STREAM_TOKEN` — long random secret (32+ characters) for the provider WebSocket.
- `EDESY_API_KEY` — Edesy provider API key, used only by the server to run the provider preflight.
- `EDESY_STREAM_URL` — exact public `wss://YOUR_BACKEND_HOST/ws/telephony/edesy?token=YOUR_EDESY_STREAM_TOKEN` URL.
- `PUBLIC_ORIGIN` — the matching HTTPS origin, for example `https://YOUR_BACKEND_HOST`.
- `OPENAI_API_KEY` — OpenAI project key with GPT-Live access.
- Optional: `OPENAI_LIVE_MODEL` (default `gpt-live-1.1`), `OPENAI_LIVE_VOICE` (defaults to the Hindi-capable `nira` voice), `SHRADDHA_CALL_INSTRUCTIONS`.
- `CALL_TRANSCRIPTS_ENABLED=false` by default. If enabled, the assistant asks the caller to press 1 before any transcript is stored; pressing 0 at any time stops storage and purges the transcript accumulated for that call. If the assistant does not clearly issue the consent prompt, transcript capture remains off. No audio is recorded. Enable only after legal notice and retention review.

## Provision Edesy

1. Create and verify an Edesy tenant, complete KYC, and provision an Indian DID.
2. Provision an Edesy phone number and configure its inbound route to **Media Streams** (or the portal's AI Voice Agent Stream node).
3. Set the Media Streams WebSocket URL to `wss://YOUR_BACKEND_HOST/ws/telephony/edesy?token=YOUR_EDESY_STREAM_TOKEN`.
4. Configure 16 kHz mono L16/PCM streaming (8 kHz is also supported) and the documented JSON metadata first frame. The backend reads `sampleRate` and `mixType` from that frame.
5. Enable DTMF passthrough for the Media Stream. The per-call transcript consent gate depends on Edesy delivering `{"event":"dtmf","dtmf":"1"}` text frames; without it, transcript saving stays off.
6. Route the DID to this WebSocket stream and call it from a separate phone.
7. In the app's Phone Call Agent page, check readiness and run the authenticated Edesy preflight. It calls Edesy's `POST /v1/stream-test` from the backend; no provider API key is exposed to Android.
8. Check `GET /api/telephony/edesy/status` with the existing gateway bearer token.

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

## Deployment topology

- This Node backend currently keeps paired-device sessions, active relay sockets and recent call history in process memory. Run it as one persistent WebSocket-capable service instance for reliable Android/Windows pairing and Edesy call handling.
- Do not horizontally scale this in-memory relay across multiple instances without first moving session state and socket fan-out to a shared Redis/pub/sub layer. Two paired devices reaching different instances will not share the same in-memory session.
- Vercel Functions support WebSockets, but each connection is pinned to one function instance and closes at the configured maximum duration. A Vercel deployment therefore needs an external shared store/pub-sub for the device relay and explicit reconnect/session recovery for long calls.

## Release gates and limitations

- Code integration does not prove that an Edesy tenant, DID, provider flow or paid API access has been provisioned.
- No forwarding should be enabled on the personal SIM until real two-way calls pass on the actual Edesy account.
- Android `InCallService` cannot capture ordinary SIM-call audio on standard Android. The SIM-first/no-answer behavior depends on Jio's network forwarding to the Edesy DID.
- Transcript capture is off by default. When enabled, a per-call DTMF consent gate requires the caller to press 1 after the assistant's explicit prompt; pressing 0 stops capture and purges that call's transcript. Transcripts and summaries are held in bounded server memory and disappear on restart. Android can explicitly save a masked call-history snapshot encrypted locally. No persistent audio recording, automatic encrypted backend history, caller screening, human takeover, WhatsApp summary or call notifications are implemented yet.
- Configure an upfront AI disclosure in the Edesy flow and the GPT-Live prompt. Review recording/transcription notice and consent requirements before enabling transcript capture.
