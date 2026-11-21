import test from 'node:test';
import assert from 'node:assert/strict';
import { canShareVisualFrame, consumeVisualFrameBudget } from '../relay-policy.js';

test('visual frame permission is opt-in and fails closed', () => {
  assert.equal(canShareVisualFrame({}, 1), false);
  assert.equal(canShareVisualFrame({ screen: 'true' }, 1), false);
  assert.equal(canShareVisualFrame({ screen: true }, 1), true);
  assert.equal(canShareVisualFrame({ camera: true }, 2), true);
  assert.equal(canShareVisualFrame({ screen: true }, 2), false);
  assert.equal(canShareVisualFrame({ screen: true, camera: true }, 3), false);
  assert.equal(canShareVisualFrame(null, 1), false);
});

test('visual frame budget permits at most ten frames per second', () => {
  const device = {};
  for (let i = 0; i < 10; i += 1) assert.equal(consumeVisualFrameBudget(device, 1000, 10), true);
  assert.equal(consumeVisualFrameBudget(device, 1000, 10), false);
  assert.equal(device.frameCount, 10);
});

test('visual frame budget resets on next window and clock rollback', () => {
  const device = { frameWindowStart: 1000, frameCount: 10 };
  assert.equal(consumeVisualFrameBudget(device, 2000, 10), true);
  assert.equal(device.frameCount, 1);
  assert.equal(consumeVisualFrameBudget(device, 1500, 10), true);
  assert.equal(device.frameWindowStart, 1500);
  assert.equal(device.frameCount, 1);
});

test('invalid budget inputs fail closed', () => {
  assert.equal(consumeVisualFrameBudget(null, 0, 10), false);
  assert.equal(consumeVisualFrameBudget({}, 0, 0), false);
});
