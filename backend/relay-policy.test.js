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
