# Deploy the Shraddha AI relay for worldwide access

## What must be online

The Android phones do not connect directly to each other's private IP addresses. Each phone opens an outbound secure connection to one public relay URL. This works across different Wi-Fi networks and mobile carriers when both phones have internet access.

## Deploy steps

1. Choose an always-on Node.js hosting service or your own VPS. The service must support long-lived WebSocket connections and HTTPS/WSS.
2. Upload only the `backend/` directory.
3. Use Node.js 20 or newer.
4. Run `npm install`, then `npm start` (or configure the provider's equivalent start command).
5. Configure TLS using the host's custom domain / reverse proxy. Do not expose plain `ws://` or `http://` to production phones.
6. Open `https://YOUR_DOMAIN/health`. It should return an `ok: true` JSON response.
7. Copy the HTTPS origin into the Cloud Relay URL field on each phone. The Android client automatically upgrades the socket to WSS.
8. Create one session on the main phone. Enter its current six-digit code on each other phone.

## Operational limitations of this starter

- One always-on Node process only. Session membership and pairing codes are stored in RAM.
- A server restart clears active sessions; pair the phones again.
- Multiple server instances do not share session state. Do not scale horizontally until shared storage and pub/sub are implemented.
- The starter does not yet implement end-to-end encryption of screen frames. The relay can access the frame bytes. Use infrastructure you control and avoid sensitive screens.
- The current frame relay is JPEG snapshots (throttled), not a full WebRTC video call.
- For production, add database-backed device registration, revocation, token rotation, abuse monitoring, E2E encryption and a security review.
