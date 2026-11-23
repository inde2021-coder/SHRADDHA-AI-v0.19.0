import { randomUUID } from 'node:crypto';
import { validateScheduleIntent } from './social-publishing.js';

/** Firestore-backed queue. A claimed post is never blindly replayed after an ambiguous crash. */
export function createSocialScheduler({ collection, publish, now = () => Date.now() }) {
  if (!collection || typeof publish !== 'function') throw new Error('A durable collection and publisher are required');

  async function schedule(uid, body) {
    if (typeof uid !== 'string' || !/^[A-Za-z0-9_-]{10,128}$/.test(uid)) throw new Error('Authenticated Firebase user is required');
    const intent = validateScheduleIntent(body, now());
    const id = uid + '_' + intent.idempotencyKey;
    const ref = collection.doc(id);
    const db = collection.firestore;
    return db.runTransaction(async tx => {
      const existing = await tx.get(ref);
      if (existing.exists) {
        const saved = existing.data();
        if (saved.uid !== uid || saved.platform !== intent.platform || saved.text !== intent.text || saved.scheduledAtMs !== intent.scheduledAtMs) {
          throw new Error('Idempotency key was already used for a different scheduled post');
        }
        return { id, status: saved.status, scheduledAt: saved.scheduledAt, idempotent: true };
      }
      const record = {
        uid, platform: intent.platform, text: intent.text, preview: intent.text,
        scheduledAt: intent.scheduledAt, scheduledAtMs: intent.scheduledAtMs,
        idempotencyKey: intent.idempotencyKey, status: 'pending',
        createdAtMs: now(), claimToken: null, result: null, lastError: null
      };
      tx.create(ref, record);
      return { id, status: 'pending', scheduledAt: intent.scheduledAt, idempotent: false };
    });
  }

  async function list(uid, limit = 50) {
    if (typeof uid !== 'string' || !uid) throw new Error('Authenticated Firebase user is required');
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 50) throw new Error('List limit must be 1-50');
    const snapshot = await collection.where('uid', '==', uid).orderBy('scheduledAtMs', 'desc').limit(limit).get();
    return snapshot.docs.map(doc => {
      const row = doc.data();
      return {
        id: doc.id, platform: row.platform, text: row.text,
        scheduledAt: row.scheduledAt, status: row.status,
        result: row.status === 'published' ? row.result : null,
        lastError: row.status === 'failed' ? row.lastError : null
      };
    });
  }

  async function cancel(uid, id) {
    if (typeof uid !== 'string' || !uid) throw new Error('Authenticated Firebase user is required');
    if (typeof id !== 'string' || !/^[A-Za-z0-9_-]{20,220}$/.test(id)) throw new Error('Scheduled post id is invalid');
    const ref = collection.doc(id);
    return collection.firestore.runTransaction(async tx => {
      const snap = await tx.get(ref);
      if (!snap.exists || snap.data().uid !== uid) throw new Error('Scheduled post was not found');
      if (snap.data().status !== 'pending') throw new Error('Only pending scheduled posts can be cancelled');
      tx.update(ref, { status: 'cancelled', cancelledAtMs: now() });
      return { id, status: 'cancelled' };
    });
  }

  async function dispatchDue(limit = 10) {
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 25) throw new Error('Dispatch limit must be 1-25');
    const due = await collection.where('status', '==', 'pending')
      .where('scheduledAtMs', '<=', now()).orderBy('scheduledAtMs', 'asc').limit(limit).get();
    const summary = { scanned: due.size, published: 0, failed: 0, skipped: 0 };
    for (const candidate of due.docs) {
      const ref = collection.doc(candidate.id);
      const claimToken = randomUUID();
      const claimed = await collection.firestore.runTransaction(async tx => {
        const current = await tx.get(ref);
        if (!current.exists || current.data().status !== 'pending' || current.data().scheduledAtMs > now()) return null;
        tx.update(ref, { status: 'dispatching', claimToken, dispatchStartedAtMs: now() });
        return current.data();
      });
      if (!claimed) { summary.skipped++; continue; }
      try {
        const body = { platform: claimed.platform, text: claimed.text, preview: claimed.preview, confirmed: true };
        const result = await publish(body);
        await collection.firestore.runTransaction(async tx => {
          const current = await tx.get(ref);
          if (!current.exists || current.data().claimToken !== claimToken || current.data().status !== 'dispatching') return;
          tx.update(ref, { status: 'published', result, publishedAtMs: now(), claimToken: null });
        });
        summary.published++;
      } catch (error) {
        await collection.firestore.runTransaction(async tx => {
          const current = await tx.get(ref);
          if (!current.exists || current.data().claimToken !== claimToken || current.data().status !== 'dispatching') return;
          tx.update(ref, { status: 'failed', lastError: String(error?.message || 'Provider rejected post').slice(0, 240), failedAtMs: now(), claimToken: null });
        });
        summary.failed++;
      }
    }
    return summary;
  }

  return { schedule, list, cancel, dispatchDue };
}
