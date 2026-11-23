# Shraddha AI Smart Home Integrations

## Home Assistant HTTPS REST
- Configure the Home Assistant server root URL using HTTPS and a long-lived access token. Credentials are stored in the encrypted Android feature store.
- The Android Smart Home screen lists supported light, switch, fan, climate and cover entities.
- Each service action requires a fresh confirmation. Only the service allowlist in `HomeAssistantGateway` is callable.
- Climate setpoint is constrained to 16–30 °C. The app reports that Home Assistant accepted a command; it does not claim a physical device completed it.

## MQTT over TLS
- Configure an MQTT broker as `ssl://hostname:8883`, username/password, and a base topic. Plaintext `tcp://` brokers are rejected.
- Credentials are stored in the encrypted Android feature store. Paho uses TLS defaults with hostname verification, clean sessions, bounded connection/keepalive and automatic reconnect.
- Topic contract: subscribe to `<baseTopic>/+/state`; publish only `ON`, `OFF` or `TOGGLE` to `<baseTopic>/<deviceId>/set` after explicit user confirmation.
- Device IDs and base topics are validated; wildcard and traversal segments are rejected. QoS 1 publication acknowledgement confirms broker acceptance only, not physical device execution.

## Not implemented in this release
Matter commissioning, Thread radio commissioning, vendor-specific Bluetooth/Wi-Fi discovery and direct Zigbee/Z-Wave radio adapters require compatible hardware, SDKs and device-specific integration. The UI does not claim these transports are connected.
