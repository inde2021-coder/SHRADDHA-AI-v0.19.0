# v0.15.0 verification report

## Passed in this environment
- `node --check backend/server.js`.
- Backend HTTP smoke tests: public health 200; AI endpoint without token 401; with token 200; malformed JSON 400; missing provider key produces structured 502; pairing session creation 201.
- All Android XML resources parse successfully.
- `backend/package.json` parses as JSON.
- Standalone Kotlin parser found no syntax errors in the edited `MainActivity.kt` and `DeviceLinkManager.kt` (Android and OkHttp symbols were unavailable to the standalone compiler).
- Remote-control approval protocol and UI wiring are present in source: explicit request, receiving-device dialog, five-minute grant, server-side grant check, and local receiving-side consent check.

## Not verified / blocked
- No actual WebSocket peer-to-peer session test was run; HTTP smoke tests used a temporary WebSocket stub that was removed after testing.
- No full Android Gradle build or APK was produced. Gradle 8.11.1 distribution was not available in the offline environment.
- No physical Android device/emulator test was run.
- No live AI provider calls were made because API keys were not supplied.
- Deployment behind HTTPS/WSS, real provider quotas, background behavior and remote accessibility actions need end-to-end verification.

## Security boundaries
- All AI/provider routes now require `GATEWAY_TOKEN`; device pairing bootstrap endpoints remain public by design and are protected by short-lived six-digit codes and join-attempt limits.
- AI gateway has a per-IP 30-request/10-minute limit; council retains a stricter per-IP limit.
- Remote-control messages are forwarded only while a valid target-side, requester-specific, time-limited approval grant exists.
- User confirmation and Android OS permission remain required; this relay does not bypass Android permission controls.
