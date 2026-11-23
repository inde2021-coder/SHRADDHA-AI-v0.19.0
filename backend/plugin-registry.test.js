import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, sign } from 'node:crypto';
import { canonicalPluginPayload, validatePluginManifest, verifyPluginManifest, authorizePluginAction } from './plugin-registry.js';

const { publicKey, privateKey } = generateKeyPairSync('ed25519');
const trustedKeys = { 'shraddha.core': publicKey.export({ type: 'spki', format: 'der' }).toString('base64') };
function signed(overrides = {}) {
  const payload = {
    id: 'sample.notes', name: 'Sample Notes', version: '1.0.0',
    capabilities: ['create_note'],
    actions: [{ type: 'create_note', title: 'Hello', body: 'A safe declarative note.' }],
    ...overrides
  };
  const manifest = { ...payload, keyId: 'shraddha.core' };
  manifest.signature = sign(null, canonicalPluginPayload(manifest), privateKey).toString('base64url');
  return manifest;
}

test('accepts a trusted Ed25519-signed declarative plugin', () => {
  const result = verifyPluginManifest(signed(), trustedKeys);
  assert.equal(result.verified, true);
  assert.equal(result.execution, 'declarative-allowlisted-only');
  assert.equal(result.id, 'sample.notes');
});

test('rejects modified payload after signing', () => {
  const manifest = signed();
  manifest.actions[0].body = 'tampered';
  assert.throws(() => verifyPluginManifest(manifest, trustedKeys), /signature verification failed/);
});

test('rejects unknown capabilities and arbitrary executable payloads', () => {
  assert.throws(() => validatePluginManifest(signed({ capabilities: ['shell_exec'] })), /unsupported capability/);
  assert.throws(() => validatePluginManifest(signed({ script: 'process.exit(0)' })), /Executable or remote/);
  assert.throws(() => validatePluginManifest(signed({ actions: [{ type: 'open_url' }] })), /not allowed/);
  assert.throws(() => validatePluginManifest(signed({ actions: [{ type: 'create_note', title: 'x', body: 'y', url: 'https://evil.example' }] })), /Executable or remote/);
});

test('rejects untrusted keys and malformed manifests', () => {
  assert.throws(() => verifyPluginManifest(signed(), {}), /not trusted/);
  assert.throws(() => validatePluginManifest(signed({ id: '../bad' })), /id is invalid/);
  assert.throws(() => validatePluginManifest(signed({ version: 'latest' })), /semantic version/);
});

test('authorizes only an exact signed declarative action after explicit approval', () => {
  const manifest = signed();
  const result = authorizePluginAction(manifest, trustedKeys, 0, true);
  assert.equal(result.verified, true);
  assert.equal(result.execution, 'client-local-allowlisted');
  assert.deepEqual(result.action, manifest.actions[0]);
  assert.throws(() => authorizePluginAction(manifest, trustedKeys, 0, false), /Explicit user confirmation/);
  assert.throws(() => authorizePluginAction(manifest, trustedKeys, 4, true), /index is invalid/);
  const tampered = signed();
  tampered.actions[0].body = 'changed';
  assert.throws(() => authorizePluginAction(tampered, trustedKeys, 0, true), /signature verification failed/);
});
