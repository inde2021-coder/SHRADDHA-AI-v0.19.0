# Phone-to-phone implementation notes

## Session lifecycle

1. Session creator calls `POST /api/device/session` with a display name.
2. Relay returns a random session ID, random per-device bearer token, device ID and six-digit invitation code (valid for five minutes).
3. Other phones call `POST /api/device/join` with the invitation code and their device name. Each receives a distinct device token.
4. Phones open `WSS /ws?sessionId=...&deviceId=...` and provide the bearer token in the Authorization header.
5. WebSocket JSON messages relay remote actions, peer presence, control-mode changes and AI chat messages. Binary WebSocket messages carry a one-byte source-ID length, UTF-8 source device ID, then JPEG screen frame. The viewer tags the live preview with the source device so preview taps are routed back to the correct phone.

## Control modes

- `mutual`: any enrolled device can send a remote action to another enrolled device.
- `master`: only the session creator (role `owner`) can send remote actions. Only the owner can change this mode.
- The receiving Android app enforces a local consent switch before invoking Accessibility actions. The relay does not override local consent.

## Supported action payloads

- `{ "kind": "tap", "x": 0.5, "y": 0.5 }`
- `{ "kind": "swipe", "x1": 0.5, "y1": 0.8, "x2": 0.5, "y2": 0.2 }`
- `{ "kind": "type", "text": "..." }`
- `{ "kind": "back" }`
- `{ "kind": "scroll" }`

Coordinates are normalized to the target display. Text is limited to 2,000 characters in the Android receiver and is inserted only into the currently focused, visible, editable, non-password field.

## Future hardening before public release

- End-to-end encryption for screen frames and AI-to-AI messages is not yet integrated. Current WSS protects transport only; the relay can read JPEG frame contents.
- Owner-controlled device revocation and self-revocation are implemented through `POST /api/device/revoke`; revocation state is in-memory with the relay session and is lost if the backend process restarts. Owner transfer and a complete device-specific permission matrix remain future work.
- Android manual disconnect now closes the live socket but preserves the Keystore-encrypted pairing for `reconnectSaved()`. Confirmed server-side revocation clears the local credential.
- Per-session quotas, stronger abuse controls, audit log and relay authentication/rate limits.
- WebRTC direct transport or TURN fallback for lower latency and reduced relay bandwidth.
- Physical-device QA across OEM Android builds, screen density/orientation changes and app-specific Accessibility behavior.


## AI screen understanding

- The viewer stores the latest JPEG frame received from a paired device.
- The user explicitly taps **Ask Shraddha to Understand Remote Screen**.
- Android posts that single image plus the user's prompt to the relay's `/api/vision` route. The relay calls the selected Gemini, OpenAI or Anthropic vision model using server-side API keys.
- The answer is displayed in the app. This is snapshot analysis only; continuous autonomous monitoring and automatic multi-step actions are not part of this build.
