# Signed plugin trust boundary

Shraddha's plugin gateway accepts **declarative JSON manifests only**. The gateway verifies an Ed25519 signature against server-pinned public keys supplied through `PLUGIN_TRUSTED_PUBLIC_KEYS` (JSON object mapping key IDs to Base64 DER SubjectPublicKeyInfo).

The signature covers canonical JSON for every manifest field except `keyId` and `signature`. Object keys are sorted recursively; arrays retain order. The verifier rejects unknown capabilities, unknown action types, malformed identifiers/versions, oversized/nested data, dangerous object keys and executable/remote-code fields.

Allowed capabilities are deliberately narrow: `read_profile`, `read_memory`, `create_note`, `show_notification`. Allowed actions are currently only declarative `create_note` and `show_notification` requests. The verifier does **not** execute plugin code, load DEX/JAR/JS, fetch remote entrypoints, grant Android permissions or bypass user approval. The host app must separately implement each action with its own permission and confirmation checks.

## Provisioning

1. Generate an Ed25519 signing keypair offline.
2. Keep the private key in a protected release/signing environment; never put it in the Android app or repository.
3. Add the public key's DER/SPKI bytes as Base64 in the backend's `PLUGIN_TRUSTED_PUBLIC_KEYS` JSON map.
4. Sign the canonical UTF-8 JSON payload described above and send the manifest to authenticated `POST /api/plugins/verify`.
5. Rotate/revoke keys by removing or replacing the server-pinned key entry.

The current endpoint verifies trust and returns a safe declarative manifest. It is not yet a full third-party plugin marketplace, executable-code sandbox, permission UI or client-side plugin installer. Do not treat a verified signature as proof that a plugin is safe beyond the narrow manifest schema.
