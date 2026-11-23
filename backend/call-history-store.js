import { createHash } from 'node:crypto';
import { encryptCallHistoryRecord, decryptCallHistoryRecord } from './call-history-crypto.js';

export const CALL_HISTORY_RETENTION_DAYS = 90;
const MAX_LIST = 50;

export function decodeCallHistoryKey(encoded) {
  if (typeof encoded !== 'string' || !/^[A-Za-z0-9+/]{43}=$/.test(encoded)) {
    throw new Error('CALL_HISTORY_ENCRYPTION_KEY must be base64 for exactly 32 random bytes.');
  }
  const key = Buffer.from(encoded, 'base64');
  if (key.length !== 32 || key.toString('base64') !== encoded) {
    key.fill(0);
    throw new Error('CALL_HISTORY_ENCRYPTION_KEY must decode to exactly 32 bytes.');
  }
  return key;
}

function documentId(recordId) {
  return createHash('sha256').update(recordId).digest('hex');
}

/** Persist only AES-256-GCM ciphertext. The Firestore document ID does not reveal the call SID. */
export async function persistEncryptedCallRecord(collection, record, key, now = Date.now()) {
  if (!collection || typeof collection.doc !== 'function') throw new Error('Durable call-history collection is unavailable.');
  if (!record || typeof record.id !== 'string' || !record.id || record.id.length > 120) throw new Error('Call-history record is invalid.');
  const storageId = documentId(record.id);
  const safeRecord = { ...record, originalId: record.id, id: storageId };
  const envelope = encryptCallHistoryRecord(safeRecord, key);
  const startedAt = new Date(record.startedAt || now).toISOString();
  const expiresAt = new Date(now + CALL_HISTORY_RETENTION_DAYS * 24 * 60 * 60 * 1000).toISOString();
  await collection.doc(documentId(record.id)).set({
    version: 1,
    envelope,
    startedAt,
    expiresAt,
    updatedAt: new Date(now).toISOString()
  }, { merge: true });
  return { id: record.id, startedAt, expiresAt };
}

/** Decrypt a bounded newest-first page; malformed/tampered rows are skipped, never returned as plaintext. */
export async function listEncryptedCallRecords(collection, key, { limit = 30, now = Date.now() } = {}) {
  if (!collection || typeof collection.orderBy !== 'function') throw new Error('Durable call-history collection is unavailable.');
  const count = Math.max(1, Math.min(MAX_LIST, Number.isSafeInteger(limit) ? limit : 30));
  const snapshot = await collection.orderBy('startedAt', 'desc').limit(count).get();
  const calls = [];
  for (const doc of snapshot.docs || []) {
    const data = doc.data();
    if (!data || data.version !== 1 || typeof data.expiresAt !== 'string' || Date.parse(data.expiresAt) <= now) continue;
    try {
      const envelope = data.envelope;
      const storageId = doc.id;
      if (typeof storageId !== 'string' || !/^[a-f0-9]{64}$/.test(storageId)) continue;
      const record = decryptCallHistoryRecord(envelope, key, storageId);
      if (typeof record.originalId !== 'string' || !record.originalId) continue;
      record.id = record.originalId;
      delete record.originalId;
      calls.push(record);
    } catch { /* fail closed for corrupt records */ }
  }
  return calls;
}
