import test from 'node:test';
import assert from 'node:assert/strict';
import { createSocialScheduler } from './social-scheduler.js';

class FakeDb {
  constructor() { this.records = new Map(); this.collectionRef = new FakeCollection(this); }
  collection() { return this.collectionRef; }
  async runTransaction(fn) {
    return fn({
      get: async ref => {
        const value = this.records.get(ref.id);
        return { exists: Boolean(value), data: () => value ? structuredClone(value) : undefined };
      },
      create: (ref, value) => {
        if (this.records.has(ref.id)) throw new Error('already exists');
        this.records.set(ref.id, structuredClone(value));
      },
      update: (ref, patch) => {
        const value = this.records.get(ref.id);
        if (!value) throw new Error('missing');
        this.records.set(ref.id, { ...value, ...structuredClone(patch) });
      }
    });
  }
}
class FakeCollection {
  constructor(db) { this.firestore = db; }
  doc(id) { return { id }; }
  where(field, op, value) { return new FakeQuery(this.firestore, [{ field, op, value }]); }
}
class FakeQuery {
  constructor(db, filters, max = 100) { this.db = db; this.filters = filters; this.max = max; }
  where(field, op, value) { return new FakeQuery(this.db, [...this.filters, { field, op, value }], this.max); }
  orderBy() { return this; }
  limit(max) { return new FakeQuery(this.db, this.filters, max); }
  async get() {
    const matches = [...this.db.records.entries()].filter(([, row]) => this.filters.every(f =>
      f.op === '==' ? row[f.field] === f.value : f.op === '<=' ? row[f.field] <= f.value : false
    )).slice(0, this.max);
    return { size: matches.length, docs: matches.map(([id, data]) => ({ id, data: () => structuredClone(data) })) };
  }
}
const valid = (scheduledAt, key = 'schedule_key_123456789') => ({
  platform: 'telegram', text: 'Approved announcement', preview: 'Approved announcement',
  confirmed: true, idempotencyKey: key, scheduledAt
});
const validInstagram = (scheduledAt, key = 'instagram_key_123456789') => ({
  platform: 'instagram', text: 'Approved Reel caption', preview: 'Approved Reel caption',
  confirmed: true, idempotencyKey: key, scheduledAt, mediaKind: 'reel', mediaUrl: 'https://cdn.example.com/reel.mp4'
});

test('Firestore queue is idempotent and scoped to the authenticated user', async () => {
  let now = Date.parse('2026-11-23T06:00:00.000Z');
  const db = new FakeDb();
  const scheduler = createSocialScheduler({ collection: db.collection(), publish: async () => ({ ok: true }), now: () => now });
  const first = await scheduler.schedule('firebaseUser123', valid('2026-11-23T07:00:00.000Z'));
  const again = await scheduler.schedule('firebaseUser123', valid('2026-11-23T07:00:00.000Z'));
  assert.equal(first.idempotent, false);
  assert.equal(again.idempotent, true);
  await assert.rejects(() => scheduler.schedule('firebaseUser123', { ...valid('2026-11-23T07:00:00.000Z'), text: 'Different post', preview: 'Different post' }), /different scheduled post/);
});

test('only pending jobs can be cancelled and due jobs publish once', async () => {
  let now = Date.parse('2026-11-23T06:00:00.000Z');
  const db = new FakeDb();
  let calls = 0;
  const scheduler = createSocialScheduler({ collection: db.collection(), publish: async () => { calls++; return { platform: 'telegram', messageId: 123 }; }, now: () => now });
  const scheduled = await scheduler.schedule('firebaseUser123', valid('2026-11-23T07:00:00.000Z'));
  await scheduler.cancel('firebaseUser123', scheduled.id);
  await assert.rejects(() => scheduler.cancel('firebaseUser123', scheduled.id), /Only pending/);

  const second = await scheduler.schedule('firebaseUser123', valid('2026-11-23T08:00:00.000Z', 'schedule_key_987654321'));
  now = Date.parse('2026-11-23T08:01:00.000Z');
  const result = await scheduler.dispatchDue(10);
  assert.equal(result.published, 1);
  assert.equal(calls, 1);
  assert.equal((await scheduler.dispatchDue(10)).published, 0);
  assert.equal(db.records.get(second.id).status, 'published');
});

test('Instagram media schedule persists and dispatches its approved media metadata', async () => {
  let now = Date.parse('2026-11-23T06:00:00.000Z');
  const db = new FakeDb();
  let sent;
  const scheduler = createSocialScheduler({ collection: db.collection(), publish: async body => { sent = body; return { ok: true, postId: 'ig-post-1' }; }, now: () => now });
  const item = await scheduler.schedule('firebaseUser123', validInstagram('2026-11-23T07:00:00.000Z'));
  const saved = db.records.get(item.id);
  assert.equal(saved.mediaKind, 'reel');
  assert.equal(saved.mediaUrl, 'https://cdn.example.com/reel.mp4');
  now = Date.parse('2026-11-23T07:01:00.000Z');
  assert.equal((await scheduler.dispatchDue()).published, 1);
  assert.equal(sent.mediaKind, 'reel');
  assert.equal(sent.mediaUrl, 'https://cdn.example.com/reel.mp4');
});

test('Facebook media schedule persists and dispatches its approved media metadata', async () => {
  let now = Date.parse('2026-11-23T06:00:00.000Z');
  const db = new FakeDb();
  let sent;
  const scheduler = createSocialScheduler({ collection: db.collection(), publish: async body => { sent = body; return { ok: true, postId: 'fb-post-1' }; }, now: () => now });
  const item = await scheduler.schedule('firebaseUser123', { platform: 'facebook', text: 'Page photo', preview: 'Page photo', confirmed: true, idempotencyKey: 'facebook_key_123456', scheduledAt: '2026-11-23T07:00:00.000Z', mediaKind: 'image', mediaUrl: 'https://cdn.example.com/photo.jpg' });
  now = Date.parse('2026-11-23T07:01:00.000Z');
  assert.equal((await scheduler.dispatchDue()).published, 1);
  assert.equal(sent.mediaKind, 'image');
  assert.equal(sent.mediaUrl, 'https://cdn.example.com/photo.jpg');
  assert.equal(db.records.get(item.id).status, 'published');
});

test('provider failures are terminal and are not blindly replayed', async () => {
  let now = Date.parse('2026-11-23T06:00:00.000Z');
  const db = new FakeDb();
  let calls = 0;
  const scheduler = createSocialScheduler({ collection: db.collection(), publish: async () => { calls++; throw new Error('network outcome ambiguous'); }, now: () => now });
  const scheduled = await scheduler.schedule('firebaseUser123', valid('2026-11-23T07:00:00.000Z'));
  now = Date.parse('2026-11-23T07:01:00.000Z');
  assert.equal((await scheduler.dispatchDue(10)).failed, 1);
  assert.equal((await scheduler.dispatchDue(10)).scanned, 0);
  assert.equal(calls, 1);
  assert.equal(db.records.get(scheduled.id).status, 'failed');
});

test('stale dispatch claims move to manual review and are never blindly replayed', async () => {
  let now = Date.parse('2026-11-23T09:00:00.000Z');
  const db = new FakeDb();
  let calls = 0;
  const scheduler = createSocialScheduler({
    collection: db.collection(),
    publish: async () => { calls++; return { ok: true }; },
    now: () => now
  });
  const scheduled = await scheduler.schedule('firebaseUser123', valid('2026-11-23T07:00:00.000Z'));
  const row = db.records.get(scheduled.id);
  db.records.set(scheduled.id, {
    ...row, status: 'dispatching', claimToken: 'interrupted-worker',
    dispatchStartedAtMs: now - 6 * 60 * 1000
  });
  const result = await scheduler.dispatchDue(10);
  assert.equal(result.needsReview, 1);
  assert.equal(result.scanned, 0);
  assert.equal(calls, 0);
  assert.equal(db.records.get(scheduled.id).status, 'needs_review');
  assert.match(db.records.get(scheduled.id).lastError, /Verify the provider account/);
  assert.equal((await scheduler.dispatchDue(10)).needsReview, 0);
});

