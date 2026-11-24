import test from 'node:test';
import assert from 'node:assert/strict';
import { createMessageReplayGuard, validateMessageSendIntent } from './message-send-policy.js';

const valid = {
  channel: 'whatsapp', to: '+919876543210', text: 'नमस्ते', preview: 'नमस्ते',
  confirmed: true, requestId: 'request_123456789012'
};

test('message send requires exact preview confirmation and valid E.164 recipient', () => {
  assert.deepEqual(
    (({ channel, to, text, requestId }) => ({ channel, to, text, requestId }))(validateMessageSendIntent(valid)),
    { channel: 'whatsapp', to: '+919876543210', text: 'नमस्ते', requestId: 'request_123456789012' }
  );
  assert.throws(() => validateMessageSendIntent({ ...valid, confirmed: false }), /confirmation/);
  assert.throws(() => validateMessageSendIntent({ ...valid, preview: 'different' }), /exact recipient/);
  assert.throws(() => validateMessageSendIntent({ ...valid, to: '9876543210' }), /E.164/);
  assert.throws(() => validateMessageSendIntent({ ...valid, to: '+01234567890' }), /E.164/);
  assert.throws(() => validateMessageSendIntent({ ...valid, text: 'bad\u0001text', preview: 'bad\u0001text' }), /safe characters/);
});

test('message replay guard blocks duplicate and conflicting request IDs', () => {
  let now = 1000;
  const guard = createMessageReplayGuard({ now: () => now, ttlMs: 10000 });
  const first = validateMessageSendIntent(valid);
  assert.deepEqual(guard.claim(first.requestId, first.fingerprint), { ok: true });
  assert.deepEqual(guard.claim(first.requestId, first.fingerprint), { ok: false, reason: 'in_progress' });
  assert.deepEqual(guard.claim(first.requestId, 'different'), { ok: false, reason: 'request_id_conflict' });
  guard.markSent(first.requestId);
  assert.deepEqual(guard.claim(first.requestId, first.fingerprint), { ok: false, reason: 'already_submitted' });
  now += 10001;
  assert.deepEqual(guard.claim(first.requestId, first.fingerprint), { ok: true });
});

test('failed message attempt remains briefly blocked against immediate duplicate delivery', () => {
  let now = 1000;
  const guard = createMessageReplayGuard({ now: () => now, ttlMs: 60000 });
  const intent = validateMessageSendIntent(valid);
  guard.claim(intent.requestId, intent.fingerprint);
  guard.markFailed(intent.requestId);
  assert.deepEqual(guard.claim(intent.requestId, intent.fingerprint), { ok: false, reason: 'in_progress' });
  now += 5 * 60 * 1000 + 1;
  assert.deepEqual(guard.claim(intent.requestId, intent.fingerprint), { ok: true });
});
test('message replay guard fails closed at capacity without reopening prior request IDs', () => {
  let now = 1000;
  const guard = createMessageReplayGuard({ now: () => now, ttlMs: 10000, maxEntries: 1 });
  const first = validateMessageSendIntent(valid);
  const second = validateMessageSendIntent({ ...valid, requestId: 'request_abcdefghijkl' });
  assert.deepEqual(guard.claim(first.requestId, first.fingerprint), { ok: true });
  assert.deepEqual(guard.claim(second.requestId, second.fingerprint), { ok: false, reason: 'capacity_full' });
  assert.deepEqual(guard.claim(first.requestId, first.fingerprint), { ok: false, reason: 'in_progress' });
  now += 10001;
  assert.deepEqual(guard.claim(second.requestId, second.fingerprint), { ok: true });
});
