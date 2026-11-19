# Worldwide device control and UX architecture

## User flow
1. **Home** — clear assistant identity, connection status and a primary Talk action.
2. **App Control Setup** — enable Accessibility, floating overlay and Shraddha keyboard.
3. **Worldwide Device Link** — enter public HTTPS relay, name the phone, create/join a six-digit pairing session.
4. **Device Permissions & Mode** — select a paired target, grant local receiving consent, select Mutual or Main phone only.
5. **Remote App Control** — type into the focused safe field, scroll, Back, tap/swipe the remote preview.
6. **Live Screen & AI Vision** — start sharing with Android's system prompt and ask a configured AI provider to inspect the latest frame.

## Global network model
Every client connects outbound to the same public WSS relay. This allows phones behind different Wi-Fi networks, mobile carriers and countries to connect without inbound port forwarding on the phones. A stable public domain, valid TLS certificate and always-on Node relay are required. Current in-memory sessions are single-process and ephemeral; production requires durable storage and shared pub/sub.

## Trust model
- Pairing proves possession of a short-lived invite code; per-device bearer token authenticates the socket.
- The receiver's own local consent switch is authoritative for remote actions.
- Main-phone-only mode allows the session owner to issue remote actions; mutual mode allows enrolled devices to request actions.
- MediaProjection consent is separately requested by Android every time screen sharing starts.
- Remote control is visible, revocable and not a way to bypass Android's secure-screen protections.

## Cross-app coverage
Accessibility handles visible UI nodes and supported gestures; IME handles text input in compatible text fields. Apps can hide nodes, use custom canvases, block accessibility or mark windows secure. Therefore the product should describe broad cross-app compatibility, not universal unrestricted control.
