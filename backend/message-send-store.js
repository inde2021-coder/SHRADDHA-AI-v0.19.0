import { createHash } from 'node:crypto';

const DEFAULT_TTL_MS = 24 * 60 * 60 * 1000;
const DEFINITE_FAILURE_TTL_MS = 5 * 60 * 1000;

function safeDate(value) {
  if (value && typeof value.toMillis === 'function') return value.toMillis();
  if (value instanceof Date) return value.getTime();
  return Number(value) || 0;
}

/**
 * Firestore-backed, cross-instance message idempotency.
 * Only a one-way fingerprint and request lifecycle metadata are stored; never
 * persist the recipient number or message body in this collection.
 */
export function createDurableMessageReplayGuard({ collection, now = () => Date.now(), ttlMs = DEFAULT_TTL_MS }) {
  if (!collection || typeof collection.doc !== 'function') throw new TypeError('A Firestore collection is required');

  const referenceFor = requestId => {
    const id = createHash('sha256').update(String(requestId)).digest('hex');
    return collection.doc(id);
  };

  return {
    async claim(requestId, fingerprint) {
      if (typeof requestId !== 'string' || !requestId || typeof fingerprint !== 'string' || !/^[a-f0-9]{64}$/i.test(fingerprint)) {
        return { ok: false, reason: 'invalid_claim' };
      }
      const ref = referenceFor(requestId);
      return collection.firestore.runTransaction(async tx => {
        const snapshot = await tx.get(ref);
        const current = snapshot.exists ? snapshot.data() : null;
        const currentExpiry = safeDate(current?.expiresAt);
        if (current && currentExpiry > now()) {
          if (current.fingerprint !== fingerprint) return { ok: false, reason: 'request_id_conflict' };
          return { ok: false, reason: current.state === 'sent' ? 'already_submitted' : 'in_progress' };
        }
        tx.set(ref, {
          fingerprint,
          state: 'pending',
          createdAt: now(),
          updatedAt: now(),
          expiresAt: now() + ttlMs
        });
        return { ok: true };
      });
    },

    async markSent(requestId, fingerprint) {
      return this._mark(requestId, fingerprint, 'sent', ttlMs);
    },

    async markUnknown(requestId, fingerprint) {
      return this._mark(requestId, fingerprint, 'unknown', ttlMs);
    },

    async markFailed(requestId, fingerprint) {
      return this._mark(requestId, fingerprint, 'failed', Math.min(ttlMs, DEFINITE_FAILURE_TTL_MS));
    },

    async _mark(requestId, fingerprint, state, retentionMs) {
      const ref = referenceFor(requestId);
      return collection.firestore.runTransaction(async tx => {
        const snapshot = await tx.get(ref);
        if (!snapshot.exists || snapshot.data()?.fingerprint !== fingerprint) return false;
        tx.update(ref, { state, updatedAt: now(), expiresAt: now() + retentionMs });
        return true;
      });
    }
  };
}
