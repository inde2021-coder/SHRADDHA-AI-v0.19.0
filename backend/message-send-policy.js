import { createHash } from 'node:crypto';

const E164 = /^\+[1-9]\d{7,14}$/;
const REQUEST_ID = /^[A-Za-z0-9_-]{16,80}$/;

export function validateMessageSendIntent(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error('Invalid message request');
  if (body.confirmed !== true || typeof body.preview !== 'string' || body.preview !== body.text) {
    throw new Error('Explicit confirmation of the exact recipient and message preview is required');
  }
  const channel = String(body.channel || '').toLowerCase();
  if (!['sms', 'whatsapp'].includes(channel)) throw new Error('channel must be sms or whatsapp');
  const to = typeof body.to === 'string' ? body.to.trim() : '';
  if (!E164.test(to)) throw new Error('Recipient must be a valid E.164 number');
  if (typeof body.text !== 'string' || !body.text.trim() || body.text.length > 4000 ||
      /[\u0000-\u0008\u000B\u000C\u000E-\u001F]/.test(body.text)) {
    throw new Error('Message must contain 1-4000 safe characters');
  }
  const requestId = String(body.requestId || '');
  if (!REQUEST_ID.test(requestId)) throw new Error('A unique requestId is required');
  return { channel, to, text: body.text, requestId,
    fingerprint: createHash('sha256').update(channel + '\0' + to + '\0' + body.text).digest('hex') };
}

/** Process-local replay guard. It prevents duplicate sends within one live server process. */
export function createMessageReplayGuard({ now = () => Date.now(), ttlMs = 24 * 60 * 60 * 1000, maxEntries = 10000 } = {}) {
  const entries = new Map();
  return {
    claim(requestId, fingerprint) {
      for (const [key, value] of entries) if (value.expiresAt <= now()) entries.delete(key);
      const existing = entries.get(requestId);
      if (existing) {
        if (existing.fingerprint !== fingerprint) return { ok: false, reason: 'request_id_conflict' };
        return { ok: false, reason: existing.state === 'sent' ? 'already_submitted' : 'in_progress' };
      }
      if (entries.size >= maxEntries) {
        const oldest = entries.keys().next().value;
        if (oldest) entries.delete(oldest);
      }
      entries.set(requestId, { fingerprint, state: 'pending', expiresAt: now() + ttlMs });
      return { ok: true };
    },
    markSent(requestId) {
      const entry = entries.get(requestId);
      if (entry) { entry.state = 'sent'; entry.expiresAt = now() + ttlMs; }
    },
    markFailed(requestId) {
      const entry = entries.get(requestId);
      if (entry) { entry.state = 'failed'; entry.expiresAt = now() + Math.min(ttlMs, 5 * 60 * 1000); }
    },
    markUnknown(requestId) {
      const entry = entries.get(requestId);
      // Provider may have accepted the message even if the response was lost.
      // Keep this request ID locked for the full idempotency window.
      if (entry) { entry.state = 'unknown'; entry.expiresAt = now() + ttlMs; }
    }
  };
}