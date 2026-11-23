import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import {
  decodeCallHistoryKey,
  persistEncryptedCallRecord,
  listEncryptedCallRecords,
  CALL_HISTORY_RETENTION_DAYS
} from '../call-history-store.js';

function fakeCollection() {
  const rows = new Map();
  return {
    rows,
    doc(id) {
      return { id, set: async value => rows.set(id, value) };
    },
    orderBy() {
      return {
        limit(count) {
          return {
            async get() {
              const docs = [...rows.entries()]
                .sort((a, b) => b[1].startedAt.localeCompare(a[1].startedAt))
                .slice(0, count)
                .map(([id, value]) => ({ id, data: () => value }));
              return { docs };
            }
          };
        }
      };
    }
  };
}

test('call history key decoder accepts only canonical 32-byte base64 keys', () => {
  const encoded = randomBytes(32).toString('base64');
  assert.equal(decodeCallHistoryKey(encoded).length, 32);
  assert.throws(() => decodeCallHistoryKey('short'));
  assert.throws(() => decodeCallHistoryKey(Buffer.alloc(16).toString('base64')));
});

test('call history persists encrypted records and decrypts a bounded newest-first page', async () => {
  const collection = fakeCollection();
  const key = randomBytes(32);
  const now = Date.parse('2026-11-23T10:00:00.000Z');
  const older = { id: 'provider-call-older', caller: '••••1234', status: 'ended', transcript: [{ speaker: 'caller', text: 'नमस्ते' }], startedAt: '2026-11-23T09:00:00.000Z' };
  const newer = { id: 'provider-call-newer', caller: '••••5678', status: 'ended', transcript: [], startedAt: '2026-11-23T09:30:00.000Z' };
  await persistEncryptedCallRecord(collection, older, key, now);
  await persistEncryptedCallRecord(collection, newer, key, now);
  assert.equal(collection.rows.size, 2);
  for (const row of collection.rows.values()) {
    assert.equal(typeof row.envelope.ciphertext, 'string');
    assert.equal(JSON.stringify(row).includes('provider-call-'), false);
    assert.equal(JSON.stringify(row).includes('••••'), false);
    assert.equal(Date.parse(row.expiresAt) - now, CALL_HISTORY_RETENTION_DAYS * 86400000);
  }
  const calls = await listEncryptedCallRecords(collection, key, { limit: 10, now });
  assert.deepEqual(calls.map(call => call.id), ['provider-call-newer', 'provider-call-older']);
  assert.equal(calls[1].transcript[0].text, 'नमस्ते');
});

test('call history fails closed for tampered and expired documents', async () => {
  const collection = fakeCollection();
  const key = randomBytes(32);
  const now = Date.now();
  await persistEncryptedCallRecord(collection, { id: 'tamper-me', status: 'ended', startedAt: new Date(now).toISOString() }, key, now);
  const [id, row] = [...collection.rows.entries()][0];
  collection.rows.set(id, { ...row, envelope: { ...row.envelope, ciphertext: 'AAAA' } });
  assert.deepEqual(await listEncryptedCallRecords(collection, key, { now }), []);
  collection.rows.set(id, { ...row, expiresAt: new Date(now - 1).toISOString() });
  assert.deepEqual(await listEncryptedCallRecords(collection, key, { now }), []);
});

test('call history list caps requested result count and rejects malformed records', async () => {
  const collection = fakeCollection();
  const key = randomBytes(32);
  await assert.rejects(() => persistEncryptedCallRecord(collection, { id: '../bad' }, key));
  await assert.rejects(() => persistEncryptedCallRecord(null, { id: 'valid-id' }, key));
});
