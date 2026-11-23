import test from 'node:test';
import assert from 'node:assert/strict';
import { validateScheduleIntent } from './social-publishing.js';

test('scheduled publishing requires exact preview, idempotency key and future timezone timestamp', () => {
  const now = Date.parse('2026-11-23T06:00:00.000Z');
  const valid = validateScheduleIntent({
    platform: 'telegram', text: 'Launch update', preview: 'Launch update', confirmed: true,
    idempotencyKey: 'schedule_1234567890', scheduledAt: '2026-11-23T07:00:00.000Z'
  }, now);
  assert.equal(valid.platform, 'telegram');
  assert.equal(valid.scheduledAtMs, Date.parse('2026-11-23T07:00:00.000Z'));
  assert.throws(() => validateScheduleIntent({
    platform: 'telegram', text: 'x', preview: 'y', confirmed: true,
    idempotencyKey: 'schedule_1234567890', scheduledAt: '2026-11-23T07:00:00.000Z'
  }, now), /exact post preview/);
  assert.throws(() => validateScheduleIntent({
    platform: 'telegram', text: 'x', preview: 'x', confirmed: true,
    idempotencyKey: 'short', scheduledAt: '2026-11-23T07:00:00.000Z'
  }, now), /idempotencyKey/);
});

test('rejects schedules too soon, too far away, timezone-less and media payloads', () => {
  const base = { platform: 'telegram', text: 'x', preview: 'x', confirmed: true, idempotencyKey: 'schedule_1234567890' };
  const now = Date.parse('2026-11-23T06:00:00.000Z');
  assert.throws(() => validateScheduleIntent({ ...base, scheduledAt: '2026-11-23T06:00:20.000Z' }, now), /1 minute/);
  assert.throws(() => validateScheduleIntent({ ...base, scheduledAt: '2027-03-01T06:00:00.000Z' }, now), /90 days/);
  assert.throws(() => validateScheduleIntent({ ...base, scheduledAt: '2026-11-23T07:00:00' }, now), /ISO-8601/);
  assert.throws(() => validateScheduleIntent({ ...base, scheduledAt: '2026-11-23T07:00:00.000Z', mediaBase64: 'abc' }, now), /text-only/);
});
