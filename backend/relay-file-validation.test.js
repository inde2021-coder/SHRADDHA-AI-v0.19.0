import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { validateSharedFileEnvelope, matchesOneTimeShareGrant, isAllowedSharedPayloadType, isAllowedShareCategory, validateTransferChunk, MAX_TRANSFER_CHUNK_BYTES, MAX_TRANSFER_BYTES, MAX_TRANSFER_CHUNKS } from './relay-file-validation.js';

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

test('one-time data grant is bound to the exact request and expiry', () => {
  const grant = { requestId: 'request_1234567890', expiresAt: 5000 };
  assert.equal(matchesOneTimeShareGrant(grant, 'request_1234567890', 4999), true);
  assert.equal(matchesOneTimeShareGrant(grant, 'different_123456', 4999), false);
  assert.equal(matchesOneTimeShareGrant(grant, 'request_1234567890', 5001), false);
  assert.equal(matchesOneTimeShareGrant(null, 'request_1234567890', 1000), false);
});

test('clipboard payload policy accepts only bounded text in clipboard category', () => {
  assert.equal(isAllowedSharedPayloadType('clipboard', 'clipboard_text', 20_000), true);
  assert.equal(isAllowedSharedPayloadType('clipboard', 'clipboard_text', 80_001), false);
  assert.equal(isAllowedSharedPayloadType('documents', 'clipboard_text', 20), false);
  assert.equal(isAllowedSharedPayloadType('clipboard', 'file', 20), false);
  assert.equal(isAllowedSharedPayloadType('documents', 'file', 650_000), true);
  assert.equal(isAllowedSharedPayloadType('documents', 'unknown', 20), false);
});

test('clipboard is an explicitly supported one-time share category', () => {
  assert.equal(isAllowedShareCategory('clipboard'), true);
  assert.equal(isAllowedShareCategory('documents'), true);
  assert.equal(isAllowedShareCategory('remote_control'), false);
  assert.equal(isAllowedShareCategory('unknown'), false);
});

test('transfer chunk envelope verifies index, bounds, canonical base64 and SHA-256', () => {
  const bytes = Buffer.from('bounded chunk payload');
  const payload = bytes.toString('base64');
  const digest = createHash('sha256').update(bytes).digest('hex');
  assert.equal(validateTransferChunk(payload, 'transfer_123456', 0, 1, bytes.length, digest), true);
  assert.equal(validateTransferChunk(payload, 'transfer_123456', 1, 1, bytes.length, digest), false);
  assert.equal(validateTransferChunk(payload, 'bad', 0, 1, bytes.length, digest), false);
  assert.equal(validateTransferChunk(payload, 'transfer_123456', 0, 1, bytes.length, '0'.repeat(64)), false);
  assert.equal(validateTransferChunk(payload, 'transfer_123456', 0, 0, bytes.length, digest), false);
});


test('transfer protocol uses 48 KiB chunks and supports the full 100 MiB file cap', () => {
  assert.equal(MAX_TRANSFER_CHUNK_BYTES, 48 * 1024);
  assert.equal(MAX_TRANSFER_BYTES, 100 * 1024 * 1024);
  assert.equal(MAX_TRANSFER_CHUNKS, 2134);
  const bytes = Buffer.alloc(MAX_TRANSFER_CHUNK_BYTES, 7);
  const payload = bytes.toString('base64');
  const digest = createHash('sha256').update(bytes).digest('hex');
  assert.equal(validateTransferChunk(payload, 'transfer_123456', 0, MAX_TRANSFER_CHUNKS, bytes.length, digest), true);
  const tooLarge = Buffer.alloc(MAX_TRANSFER_CHUNK_BYTES + 1, 7);
  assert.equal(validateTransferChunk(tooLarge.toString('base64'), 'transfer_123456', 0, MAX_TRANSFER_CHUNKS, tooLarge.length,
    createHash('sha256').update(tooLarge).digest('hex')), false);
});
