# Signed Plugin Vault

## Security model
Plugins are signed JSON manifests, not downloaded programs. The backend verifies Ed25519 signatures against `PLUGIN_TRUSTED_PUBLIC_KEYS`. The manifest schema is strict and supports only two local actions:
- `create_note`: stores a bounded note in the Android Keystore-encrypted feature store.
- `show_notification`: posts a bounded Android notification after notification permission checks.

The runtime never loads scripts, JavaScript, HTML, URLs, shell commands, DEX/JAR files, native libraries or arbitrary permissions. Unknown fields and undeclared capabilities are rejected. Every action requires a separate user preview and confirmation. The backend re-verifies the signature at action-authorization time; Android then independently checks the response shape and local allowlist.

## Example manifest before signing
```json
{
  "id": "example.notes",
  "name": "Example Notes",
  "version": "1.0.0",
  "capabilities": ["create_note"],
  "actions": [
    { "type": "create_note", "title": "Project reminder", "body": "Review the milestone plan." }
  ],
  "keyId": "shraddha.core",
  "signature": "REPLACE_WITH_ED25519_BASE64URL_SIGNATURE"
}
```

## Signing and trust
1. Generate an Ed25519 key pair offline using Node's `crypto.generateKeyPairSync('ed25519')`.
2. Export the public key as DER/SPKI and Base64; put only that public value in backend `PLUGIN_TRUSTED_PUBLIC_KEYS`, keyed by the manifest's `keyId`.
3. Keep the private key offline. Never place it in Android, GitHub source, app assets, or backend environment.
4. Sign `canonicalPluginPayload(manifest)` from `backend/plugin-registry.js` with `crypto.sign(null, payload, privateKey)`; encode the signature as Base64URL.
5. Configure the HTTPS gateway token in Android. Open Quick Actions → Plugin Vault, paste the signed JSON, verify, preview the exact action, and approve it.

## API
- `POST /api/plugins/verify`: gateway-authenticated signature/schema check.
- `POST /api/plugins/execute`: gateway-authenticated re-verification plus explicit approval and action-index validation. Returns a local allowlisted action descriptor; it does not execute code on the server.

Rotate trusted public keys by adding the new key under a new key ID, migrating signed manifests, then removing the old key after the migration window. A compromised private key must be removed from the trusted map immediately.
