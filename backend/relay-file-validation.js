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

/** Keep clipboard payloads text-only and smaller than ordinary document transfers. */
export function isAllowedSharedPayloadType(category, contentType, sizeBytes) {
  if (!Number.isSafeInteger(sizeBytes) || sizeBytes < 1 || sizeBytes > MAX_SHARED_FILE_BYTES) return false;
  if (contentType === 'clipboard_text') return category === 'clipboard' && sizeBytes <= 80_000;
  if (contentType === 'file') return category !== 'clipboard';
  return false;
}

/** Categories eligible for one-time peer-approved data sharing. */
export function isAllowedShareCategory(category) {
  return ['contacts','media','documents','messages','calendar','location','device_status','ai_memory','clipboard'].includes(category);
}

export const MAX_TRANSFER_CHUNK_BYTES = 48 * 1024;
export const MAX_TRANSFER_BYTES = 100 * 1024 * 1024;
export const MAX_TRANSFER_CHUNKS = Math.ceil(MAX_TRANSFER_BYTES / MAX_TRANSFER_CHUNK_BYTES);

/** Validate one bounded, independently verifiable transfer chunk before relay. */
export function validateTransferChunk(payload, transferId, index, totalChunks, sizeBytes, sha256) {
  if (typeof transferId !== 'string' || !/^[A-Za-z0-9_-]{12,64}$/.test(transferId)) return false;
  if (!Number.isSafeInteger(totalChunks) || totalChunks < 1 || totalChunks > MAX_TRANSFER_CHUNKS) return false;
  if (!Number.isSafeInteger(index) || index < 0 || index >= totalChunks) return false;
  if (!Number.isSafeInteger(sizeBytes) || sizeBytes < 1 || sizeBytes > MAX_TRANSFER_CHUNK_BYTES) return false;
  if (typeof payload !== 'string' || payload.length < 4 || payload.length > Math.ceil(MAX_TRANSFER_CHUNK_BYTES * 4 / 3) + 4) return false;
  if (typeof sha256 !== 'string' || !/^[a-f0-9]{64}$/i.test(sha256)) return false;
  const bytes = Buffer.from(payload, 'base64');
  if (bytes.length !== sizeBytes || bytes.toString('base64') !== payload) return false;
  return createHash('sha256').update(bytes).digest('hex') === sha256.toLowerCase();
}
