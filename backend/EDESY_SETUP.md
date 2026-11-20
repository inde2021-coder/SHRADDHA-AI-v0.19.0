# Shraddha AI — Edesy Media Streams + OpenAI GPT-Live

## Implemented backend path

- `GET /api/telephony/edesy/status` — gateway-authenticated readiness status.
- `GET /api/telephony/edesy/calls` — gateway-authenticated, bounded in-memory call history.
- `wss://YOUR_BACKEND_HOST/ws/telephony/edesy?token=...` — provider WebSocket ingress, protected by a long random server-side token.
- Edesy sends one JSON metadata frame, then binary mono L16 PCM16 frames. The bridge forwards audio to an OpenAI GPT-Live primary WebSocket and returns generated audio to Edesy.
- The bridge resamples Edesy 16 kHz PCM to GPT-Live 24 kHz PCM in both directions.
- Caller interruption triggers Edesy's documented `{"type":"killAudio"}` control frame.
- OpenAI API keys and the Edesy stream token stay on the backend; neither is stored in Android.

## Backend environment

Configure these only in the backend hosting dashboard:

- `GATEWAY_TOKEN` — existing app gateway token.
- `EDESY_STREAM_TOKEN` — long random token used by the Edesy WebSocket URL.
- `OPENAI_API_KEY` — project key with GPT-Live access.
- Optional: `OPENAI_LIVE_MODEL` (default `gpt-live-1`), `OPENAI_LIVE_VOICE` (default `marin`), `SHRADDHA_CALL_INSTRUCTIONS`.
- `CALL_TRANSCRIPTS_ENABLED=false` by default. Only enable after caller notice/consent, retention and legal review.

Never commit real secrets or paste them into chat.

## Edesy setup

1. Create an Edesy tenant at https://voice-app.edesy.in and complete required KYC.
2. Provision an Indian DID number. Record its Edesy phone-number resource ID and account API key.
3. Deploy the Shraddha backend on a public HTTPS host and verify `/health`.
4. Configure Edesy's Media Streams inbound route. Example request (replace placeholders; keep the Edesy API key private):

```bash
curl -X PUT "https://voice-api.edesy.in/v1/phone-numbers/PHONE_NUMBER_ID/inbound" \
  -H "Authorization: Bearer EDESY_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "target": "media_stream",
    "stream_url": "wss://YOUR_BACKEND_HOST/ws/telephony/edesy?token=YOUR_EDESY_STREAM_TOKEN",
    "sample_rate": 16000
  }'
```

5. Run Edesy's `POST /v1/stream-test` preflight for the stream URL before placing a call. Resolve DNS, TLS, WebSocket upgrade and audio checks first.
6. Confirm the number is routed to `media_stream` at 16 kHz mono. The adapter expects the first text frame to be call metadata, followed by binary L16 PCM frames.
7. With the app's secure gateway URL/token configured, open Phone Agent and tap **Refresh Edesy AI call history**. The UI calls the protected status/history endpoints; it does not expose provider secrets.

### Expected provider frames

Metadata is a JSON text frame with fields such as `callSid`, `direction`, `from`, `to`, `callerName` and `sampleRate`. The server masks caller numbers in stored history.

- Edesy → backend: binary signed 16-bit little-endian mono PCM.
- Backend → Edesy: binary signed 16-bit little-endian mono PCM.
- Backend → Edesy barge-in: `{"type":"killAudio"}`.
- Backend → Edesy hang-up: `{"type":"disconnect"}`.

## Personal SIM fallback: ring the owner first, then Shraddha AI

The caller continues to dial the user's existing Jio SIM number.

1. On the Jio account/SIM, enable **conditional call forwarding on no reply / no answer** to the provisioned Edesy DID. Do not enable unconditional forwarding.
2. Set a no-answer delay (where supported) so the handset rings first, typically 20–30 seconds.
3. Leave busy and unreachable forwarding disabled unless separately requested.
4. Verify with Jio the activation method, supported delay and forwarding charges; carrier codes and charges can vary.
5. Test from another phone: answer the SIM call (AI must not join); then ignore a call and confirm that it forwards to Edesy and the AI can hear and speak in both directions.
6. Test caller hang-up, caller interruption/barge-in, network loss and forwarding disable/recovery.

**Important:** No-answer forwarding is a carrier feature, not an Android app feature. The handset rings first; if unanswered, Jio forwards the call to the Edesy DID. The caller continues to dial the original SIM number. Forwarded-leg charges may apply in addition to Edesy DID/minute and OpenAI usage.

Do not enable personal-SIM forwarding until a real Edesy test call confirms bidirectional audio and hang-up behavior.

## Important limitations / release gate

- This is source-level backend integration, not proof of a provisioned Edesy account or a successful PSTN call.
- Edesy tenant, DID, API credentials, backend secrets, inbound route and OpenAI GPT-Live access must be configured by the account owner.
- The adapter assumes Edesy's documented initial JSON metadata frame and binary L16 frames. The real account's deployed stream must be tested before production.
- Transcript capture and AI summaries are disabled by default. If enabled, records are bounded in server memory and disappear on restart. Durable encrypted storage, caller-specific consent, recording, caller screening, human takeover and WhatsApp delivery are not implemented.
- The assistant must disclose that it is an AI. Recording/transcription needs appropriate notice, consent and retention controls.
- The Edesy token is in the WebSocket URL query string because it is used as the provider connection secret; ensure reverse-proxy access logs redact query strings.
- Do not enable unconditional SIM forwarding before inbound routing, two-way audio, interruption, cost and failover are verified.

## Official provider references

- Edesy Media Streams: https://www.edesy.in/docs/voice-api/streaming/media-streams
- Edesy WebSocket streaming: https://www.edesy.in/docs/sip-trunk-for-ai-voice-agents/connect/websocket-streaming
- OpenAI GPT-Live WebSockets: https://developers.openai.com/api/docs/guides/voice-websockets
