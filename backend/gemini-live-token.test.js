import test from 'node:test';
import assert from 'node:assert/strict';
import { buildEphemeralTokenRequest } from './gemini-live-token.js';

test('token request is one-use and short lived', () => {
  const now = new Date('2026-11-19T00:00:00.000Z');
  const body = buildEphemeralTokenRequest(now);
  assert.equal(body.uses, 1);
  assert.equal(body.expireTime, '2026-11-19T00:30:00.000Z');
  assert.equal(body.newSessionExpireTime, '2026-11-19T00:01:00.000Z');
});

test('token request constrains the approved Live model and audio mode', () => {
  const body = buildEphemeralTokenRequest(new Date('2026-11-19T00:00:00.000Z'));
  assert.equal(body.liveConnectConstraints.model, 'models/gemini-3.1-flash-live-preview');
  assert.deepEqual(body.liveConnectConstraints.config.responseModalities, ['AUDIO']);
});
