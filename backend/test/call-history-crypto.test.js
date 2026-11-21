import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { encryptCallHistoryRecord, decryptCallHistoryRecord } from '../call-history-crypto.js';

test('call history encrypts and decrypts with record-bound AES-GCM', () => {
  const key = randomBytes(32);
  const record = { id: 'a1b2c3d4', caller: '••••1234', status: 'ended', transcriptConsent: false };
  const envelope = encryptCallHistoryRecord(record, key);
  assert.equal(envelope.version, 1);
  assert.deepEqual(decryptCallHistoryRecord(envelope, key, record.id), record);
});

test('call history rejects a wrong key and a changed record identifier', () => {
  const key = randomBytes(32);
  const envelope = encryptCallHistoryRecord({ id: 'record-1', status: 'ended' }, key);
  assert.throws(() => decryptCallHistoryRecord(envelope, randomBytes(32), 'record-1'));
  assert.throws(() => decryptCallHistoryRecord(envelope, key, 'record-2'));
});

test('call history rejects tampered ciphertext and malformed envelope fields', () => {
  const key = randomBytes(32);
  const envelope = encryptCallHistoryRecord({ id: 'record-3', status: 'ended' }, key);
  const tampered = { ...envelope, ciphertext: (envelope.ciphertext[0] === 'A' ? 'B' : 'A') + envelope.ciphertext.slice(1) };
  assert.throws(() => decryptCallHistoryRecord(tampered, key, 'record-3'));
  assert.throws(() => decryptCallHistoryRecord({ ...envelope, iv: 'bad' }, key, 'record-3'));
});

test('call history rejects invalid identifiers, keys and oversized records', () => {
  assert.throws(() => encryptCallHistoryRecord({ id: '../unsafe' }, randomBytes(32)));
  assert.throws(() => encryptCallHistoryRecord({ id: 'valid' }, Buffer.alloc(16)));
  assert.throws(() => encryptCallHistoryRecord({ id: 'large', transcript: 'x'.repeat(70_000) }, randomBytes(32)));
});
