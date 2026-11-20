import { createHash } from 'node:crypto';

export const MAX_SHARED_FILE_BYTES = 650_000;
export const MAX_SHARED_FILE_BASE64_CHARS = 900_000;

/** Validate an untrusted base64 file envelope before consuming its one-time grant. */
export function validateSharedFileEnvelope(payload, sizeBytes, sha256) {
  if (typeof payload !== 'string' || payload.length < 4 || payload.length > MAX_SHARED_FILE_BASE64_CHARS) return false;
  if (!Number.isSafeInteger(sizeBytes) || sizeBytes < 1 || sizeBytes > MAX_SHARED_FILE_BYTES) return false;
  if (typeof sha256 !== 'string' || !/^[a-f0-9]{64}$/i.test(sha256)) return false;
  const bytes = Buffer.from(payload, 'base64');
  if (bytes.length !== sizeBytes || bytes.toString('base64') !== payload) return false;
  return createHash('sha256').update(bytes).digest('hex') === sha256.toLowerCase();
}

/** A grant is valid only for the exact request that was approved and before expiry. */
export function matchesOneTimeShareGrant(grant, requestId, now = Date.now()) {
  return !!grant && typeof grant.requestId === 'string' && typeof requestId === 'string' &&
    grant.requestId === requestId && Number.isFinite(grant.expiresAt) && grant.expiresAt >= now;
}
