import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { validateSharedFileEnvelope } from './relay-file-validation.js';

test('accepts a bounded base64 file with the correct SHA-256', () => {
  const bytes = Buffer.from('Shraddha secure file transfer');
  const payload = bytes.toString('base64');
  const digest = createHash('sha256').update(bytes).digest('hex');
  assert.equal(validateSharedFileEnvelope(payload, bytes.length, digest), true);
});

test('rejects modified payload and mismatched digest', () => {
  const bytes = Buffer.from('original');
  const digest = createHash('sha256').update(bytes).digest('hex');
  assert.equal(validateSharedFileEnvelope(Buffer.from('tampered').toString('base64'), bytes.length, digest), false);
  assert.equal(validateSharedFileEnvelope(bytes.toString('base64'), bytes.length, '0'.repeat(64)), false);
});

test('rejects malformed base64, unsafe sizes and invalid hashes', () => {
  assert.equal(validateSharedFileEnvelope('not-base64!', 3, 'a'.repeat(64)), false);
  assert.equal(validateSharedFileEnvelope('YQ==', 650001, 'a'.repeat(64)), false);
  assert.equal(validateSharedFileEnvelope('YQ==', 1, 'bad'), false);
  assert.equal(validateSharedFileEnvelope('YQ==', 0, 'a'.repeat(64)), false);
});
