import test from 'node:test';
import assert from 'node:assert/strict';
import { createDurableMessageReplayGuard } from './message-send-store.js';

function fakeCollection() {
  const records = new Map();
  const collection = {
    firestore: {
      async runTransaction(callback) {
        const tx = {
          async get(ref) {
            const value = records.get(ref.id);
            return { exists: value !== undefined, data: () => value ? { ...value } : undefined };
          },
          set(ref, value) { records.set(ref.id, { ...value }); },
          update(ref, value) { records.set(ref.id, { ...records.get(ref.id), ...value }); }
        };
        return callback(tx);
      }
    },
    doc(id) { return { id }; }
  };
  return { collection, records };
}

const fingerprint = 'a'.repeat(64);

test('durable guard blocks duplicate and conflicting request IDs across guard instances', async () => {
  let now = 1000;
  const { collection, records } = fakeCollection();
  const first = createDurableMessageReplayGuard({ collection, now: () => now });
  const second = createDurableMessageReplayGuard({ collection, now: () => now });

  assert.deepEqual(await first.claim('request_123456789012', fingerprint), { ok: true });
  assert.deepEqual(await second.claim('request_123456789012', fingerprint), { ok: false, reason: 'in_progress' });
  assert.deepEqual(await second.claim('request_123456789012', 'b'.repeat(64)), { ok: false, reason: 'request_id_conflict' });
  assert.equal(records.size, 1);
});

test('sent and ambiguous provider outcomes remain blocked for the full TTL', async () => {
  let now = 5000;
  const { collection } = fakeCollection();
  const guard = createDurableMessageReplayGuard({ collection, now: () => now, ttlMs: 86400000 });
  const id = 'request_123456789012';

  assert.deepEqual(await guard.claim(id, fingerprint), { ok: true });
  assert.equal(await guard.markUnknown(id, fingerprint), true);
  assert.deepEqual(await guard.claim(id, fingerprint), { ok: false, reason: 'in_progress' });

  now += 86400001;
  assert.deepEqual(await guard.claim(id, fingerprint), { ok: true });
  assert.equal(await guard.markSent(id, fingerprint), true);
  assert.deepEqual(await guard.claim(id, fingerprint), { ok: false, reason: 'already_submitted' });
});

test('definite provider rejection uses only a short retry cooldown', async () => {
  let now = 1000;
  const { collection } = fakeCollection();
  const guard = createDurableMessageReplayGuard({ collection, now: () => now, ttlMs: 86400000 });
  const id = 'request_123456789012';

  await guard.claim(id, fingerprint);
  assert.equal(await guard.markFailed(id, fingerprint), true);
  assert.deepEqual(await guard.claim(id, fingerprint), { ok: false, reason: 'in_progress' });
  now += 5 * 60 * 1000 + 1;
  assert.deepEqual(await guard.claim(id, fingerprint), { ok: true });
});

test('storage records contain only a fingerprint and lifecycle metadata', async () => {
  const { collection, records } = fakeCollection();
  const guard = createDurableMessageReplayGuard({ collection });
  await guard.claim('request_123456789012', fingerprint);
  const record = [...records.values()][0];
  assert.deepEqual(Object.keys(record).sort(), ['createdAt', 'expiresAt', 'fingerprint', 'state', 'updatedAt']);
  assert.ok(record.expiresAt instanceof Date);
  assert.equal(JSON.stringify(record).includes('+919876543210'), false);
  assert.equal(JSON.stringify(record).includes('secret message body'), false);
});

test('invalid fingerprints fail closed without creating a record', async () => {
  const { collection, records } = fakeCollection();
  const guard = createDurableMessageReplayGuard({ collection });
  assert.deepEqual(await guard.claim('request_123456789012', 'not-a-hash'), { ok: false, reason: 'invalid_claim' });
  assert.equal(records.size, 0);
});
