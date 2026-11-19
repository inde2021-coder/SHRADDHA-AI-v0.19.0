# Shraddha AI implementation roadmap – v0.4.0 baseline

## Implemented in this source update
- Mutual paired-device control with per-device local consent.
- Remote screen capture via MediaProjection.
- Explicit rear/front camera capture prototype using Camera2 and foreground service.
- Distinct screen/camera frame types over the existing WebSocket relay.
- Custom category permissions, request/approve/deny, short-lived one-time grants and small selected-file transfer.
- Gemini/OpenAI/Anthropic/Groq/Perplexity text chat gateway; Gemini/OpenAI/Anthropic image analysis.
- Local-only memory notes.

## Next implementation stages
1. Replace JPEG-over-WebSocket camera transport with WebRTC PeerConnection + TURN; test on Wi-Fi/mobile networks and NAT-restricted networks.
2. Add durable session/device registry, refreshable credentials, revocation, reconnect and server-side rate limits.
3. Add robust command acknowledgements, per-action consent, action queue and auditable task planner.
4. Implement SAF/MediaStore based category-specific data providers (contacts, calendar, media, documents) with granular item selection; keep SMS/call logs out unless policy/permission eligibility is confirmed.
5. Add resumable encrypted large-file transfer and verified end-to-end key exchange.
6. Add opt-in cloud memory sync and notification delivery.
7. Add provider failover, tool execution policy and memory-grounded prompts.
8. Add automated tests, threat model, dependency scanning, Android Gradle release build and two-device acceptance tests.

## Release gate
A release is not complete until Android Gradle builds successfully, backend tests pass, two physical phones pair across different networks, camera/screen permissions can be revoked, and unauthorized device/data requests are rejected.
