import { createPublicKey, verify as verifySignature } from 'node:crypto';

const CAPABILITIES = new Set(['create_note', 'show_notification']);
const ACTIONS = new Set(['create_note', 'show_notification']);
const FORBIDDEN_FIELDS = new Set(['script', 'javascript', 'html', 'entrypoint', 'url', 'permissions', 'nativecode', 'dex', 'jar']);
const ID = /^[a-z][a-z0-9._-]{2,63}$/;

function assertJson(value, depth = 0) {
  if (depth > 8) throw new Error('Plugin manifest nesting is too deep');
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return;
  if (typeof value === 'number' && Number.isFinite(value)) return;
  if (Array.isArray(value)) {
    if (value.length > 100) throw new Error('Plugin manifest array is too large');
    value.forEach(item => assertJson(item, depth + 1));
    return;
  }
  if (typeof value === 'object') {
    const keys = Object.keys(value);
    if (keys.length > 100) throw new Error('Plugin manifest object is too large');
    for (const key of keys) {
      if (['__proto__', 'prototype', 'constructor'].includes(key)) throw new Error('Unsafe object key in plugin manifest');
      if (FORBIDDEN_FIELDS.has(key.toLowerCase())) throw new Error('Executable or remote plugin content is not permitted');
      assertJson(value[key], depth + 1);
    }
    return;
  }
  throw new Error('Plugin manifest must contain JSON data only');
}

function stable(value) {
  if (Array.isArray(value)) return '[' + value.map(stable).join(',') + ']';
  if (value && typeof value === 'object') {
    return '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + stable(value[key])).join(',') + '}';
  }
  return JSON.stringify(value);
}

export function canonicalPluginPayload(manifest) {
  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) throw new Error('Plugin manifest is required');
  const { signature, keyId, ...payload } = manifest;
  assertJson(payload);
  return Buffer.from(stable(payload), 'utf8');
}

export function validatePluginManifest(manifest) {
  const allowedManifestFields = new Set(['id', 'name', 'version', 'capabilities', 'actions', 'keyId', 'signature']);
  if (Object.keys(manifest || {}).some(key => !allowedManifestFields.has(key))) throw new Error('Plugin manifest contains an unsupported field');
  const bytes = canonicalPluginPayload(manifest);
  if (bytes.length > 24 * 1024) throw new Error('Plugin manifest exceeds 24 KB');
  if (!ID.test(manifest.id || '')) throw new Error('Plugin id is invalid');
  if (typeof manifest.name !== 'string' || !manifest.name.trim() || manifest.name.length > 80) throw new Error('Plugin name is invalid');
  if (typeof manifest.version !== 'string' || !/^[0-9]+\.[0-9]+\.[0-9]+$/.test(manifest.version)) throw new Error('Plugin version must use semantic version format');
  if (!Array.isArray(manifest.capabilities) || manifest.capabilities.length > 8 || new Set(manifest.capabilities).size !== manifest.capabilities.length) throw new Error('Plugin capabilities are invalid');
  for (const capability of manifest.capabilities) if (!CAPABILITIES.has(capability)) throw new Error('Plugin requests an unsupported capability');
  if (!Array.isArray(manifest.actions) || manifest.actions.length > 20) throw new Error('Plugin actions are invalid');
  for (const action of manifest.actions) {
    if (!action || typeof action !== 'object' || Array.isArray(action) || !ACTIONS.has(action.type)) throw new Error('Plugin action is not allowed');
    if (Object.keys(action).some(key => !['type', 'title', 'body'].includes(key))) throw new Error('Plugin action contains an unsupported field');
    if (action.type === 'create_note' && (typeof action.title !== 'string' || action.title.length > 120 || typeof action.body !== 'string' || action.body.length > 4000)) throw new Error('Plugin note action is invalid');
    if (action.type === 'show_notification' && (typeof action.title !== 'string' || action.title.length > 80 || typeof action.body !== 'string' || action.body.length > 240)) throw new Error('Plugin notification action is invalid');
  }
  if (typeof manifest.keyId !== 'string' || !ID.test(manifest.keyId)) throw new Error('Plugin signing key id is invalid');
  if (typeof manifest.signature !== 'string' || !/^[A-Za-z0-9_-]{80,200}$/.test(manifest.signature)) throw new Error('Plugin signature is invalid');
  return { id: manifest.id, name: manifest.name.trim(), version: manifest.version, capabilities: [...manifest.capabilities], actions: manifest.actions };
}

export function verifyPluginManifest(manifest, trustedKeys) {
  const plugin = validatePluginManifest(manifest);
  const encodedKey = trustedKeys?.[manifest.keyId];
  if (typeof encodedKey !== 'string' || encodedKey.length > 4096) throw new Error('Plugin signing key is not trusted');
  let publicKey;
  try {
    publicKey = createPublicKey({ key: Buffer.from(encodedKey, 'base64'), format: 'der', type: 'spki' });
  } catch {
    throw new Error('Trusted plugin public key is malformed');
  }
  if (publicKey.asymmetricKeyType !== 'ed25519') throw new Error('Plugin trust keys must use Ed25519');
  const signature = Buffer.from(manifest.signature, 'base64url');
  if (!verifySignature(null, canonicalPluginPayload(manifest), publicKey, signature)) throw new Error('Plugin signature verification failed');
  return { ...plugin, verified: true, keyId: manifest.keyId, execution: 'declarative-allowlisted-only' };
}

/** Authorizes one exact signed action for local execution after a separate user confirmation. */
export function authorizePluginAction(manifest, trustedKeys, actionIndex, confirmed) {
  const plugin = verifyPluginManifest(manifest, trustedKeys);
  if (confirmed !== true) throw new Error('Explicit user confirmation is required before plugin action execution');
  if (!Number.isSafeInteger(actionIndex) || actionIndex < 0 || actionIndex >= manifest.actions.length) {
    throw new Error('Plugin action index is invalid');
  }
  const action = manifest.actions[actionIndex];
  return {
    ok: true,
    verified: true,
    pluginId: plugin.id,
    pluginName: plugin.name,
    version: plugin.version,
    capabilities: [...plugin.capabilities],
    actionIndex,
    action: { ...action },
    execution: 'client-local-allowlisted',
    codeExecution: false,
    requiresLocalExecution: true
  };
}
