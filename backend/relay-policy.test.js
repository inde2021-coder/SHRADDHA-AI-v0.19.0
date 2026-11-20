import test from 'node:test';
import assert from 'node:assert/strict';
import { canShareVisualFrame } from './relay-policy.js';

test('screen frames require explicit screen permission', () => {
  assert.equal(canShareVisualFrame({}, 1), false);
  assert.equal(canShareVisualFrame({ screen: false, camera: true }, 1), false);
  assert.equal(canShareVisualFrame({ screen: true }, 1), true);
});

test('camera frames require explicit camera permission', () => {
  assert.equal(canShareVisualFrame({}, 2), false);
  assert.equal(canShareVisualFrame({ camera: false, screen: true }, 2), false);
  assert.equal(canShareVisualFrame({ camera: true }, 2), true);
});

test('unknown frame types are always rejected', () => {
  assert.equal(canShareVisualFrame({ screen: true, camera: true }, 0), false);
  assert.equal(canShareVisualFrame({ screen: true, camera: true }, 3), false);
  assert.equal(canShareVisualFrame(null, 2), false);
});


import { consumePairingCode, resolveControlApproval } from './relay-policy.js';

test('pairing codes are single-use and expire closed', () => {
  const pairs = new Map([['123456', { sessionId: 's1', expiresAt: 200 }]]);
  assert.deepEqual(consumePairingCode(pairs, '123456', 100), { sessionId: 's1', expiresAt: 200 });
  assert.equal(pairs.has('123456'), false);
  assert.equal(consumePairingCode(pairs, '123456', 100), null);
  pairs.set('654321', { sessionId: 's2', expiresAt: 100 });
  assert.equal(consumePairingCode(pairs, '654321', 100), null);
  assert.equal(pairs.has('654321'), false);
  assert.equal(consumePairingCode(pairs, 'bad', 0), null);
});

test('control approval requires an unexpired request from the same requester', () => {
  const pending = new Map([['device-A', { expiresAt: 200 }]]);
  assert.equal(resolveControlApproval(pending, 'device-B', true, 100), false);
  assert.equal(pending.has('device-A'), true);
  assert.equal(resolveControlApproval(pending, 'device-A', true, 100), true);
  assert.equal(resolveControlApproval(pending, 'device-A', true, 100), false);
  pending.set('device-C', { expiresAt: 100 });
  assert.equal(resolveControlApproval(pending, 'device-C', true, 100), false);
  pending.set('device-D', { expiresAt: 200 });
  assert.equal(resolveControlApproval(pending, 'device-D', false, 100), false);
});
