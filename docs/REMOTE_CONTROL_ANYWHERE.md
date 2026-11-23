# Shraddha AI — Remote Control Anywhere

## Goal
Android and Windows PC can communicate over the public internet. They do not need to be on the same Wi-Fi, LAN, or physically near each other. Both devices must be online and connected to the same secure relay.

## Relay deployment requirement
The device relay uses authenticated WebSockets (WSS) and keeps live socket/session state in process memory. It must therefore run as one continuously running Node.js service with WebSocket upgrade support. Do not deploy this relay as a Vercel serverless function, do not enable multiple instances, and do not put it behind a proxy that drops WebSocket upgrades. A normal HTTP health check alone does not prove WSS works.

This repository includes:
- `backend/Dockerfile` — Node 22 container for the relay.
- `render.yaml` — one always-on Render Starter service in Singapore, with `/health` check and a single instance.

The Render Starter plan is a paid always-on service. Confirm the current price in Render before creating it. A free service that sleeps is not suitable for reliable unattended remote access. The blueprint does not create an account or deploy by itself; connect this repository to Render and create the service from the Blueprint.

## Pairing and use
1. Deploy the relay and copy its HTTPS base URL (the app converts it to WSS for sockets).
2. In Shraddha Android, create a device session and generate the six-digit pairing code.
3. On Windows, open Shraddha AI PC Companion, enter the same HTTPS relay URL and pairing code, then Join & Connect.
4. Confirm the end-to-end fingerprint on both devices using a separate trusted channel.
5. Enable remote-control permission on the target device. Screen sharing is a separate opt-in.
6. Request remote control from the other device. The target must approve the request; sensitive actions still require per-action confirmation.

## Connectivity behavior
- The Windows companion now reconnects automatically with bounded exponential backoff while the companion app remains open.
- The Android client reconnects its saved pairing when its app process is alive.
- The relay and both devices must have internet access. No port forwarding, public PC IP, or same-network discovery is needed.
- If a PC is asleep, shut down, or its companion is closed, it cannot receive commands. Configure Windows power settings and keep the companion running for unattended reachability.
- Android battery optimization/background execution may pause the app. For best availability, exempt Shraddha from battery optimization where appropriate and keep the Android foreground connection service enabled; Android OS restrictions and user permission still apply.

## Security boundaries
- Pairing tokens are stored using Windows Credential Manager / Android encrypted storage.
- Peer messages and visual frames use end-to-end encryption after mutual fingerprint verification.
- Remote-control permission is opt-in and time-limited (up to five minutes). A target can revoke access or disconnect.
- Never expose the relay token, pairing code, private keys, or device IDs in screenshots or public repositories.
- This is interactive remote control, not an unattended bypass. Android screen control still depends on the user's Accessibility/MediaProjection grants and the target app's OS permissions.

## Important limitation — horizontal scaling and process restarts
The current relay session registry is in-memory. Keep exactly one live relay instance. Restarting/redeploying the relay clears active sessions, so devices must reconnect/re-pair if the session record no longer exists. Multi-instance failover and durable pairing require a shared Redis/Firestore registry plus cross-instance WebSocket pub/sub and are not enabled by this blueprint.
