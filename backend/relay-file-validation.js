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


/**
 * Validate the opaque E2E envelope for one file chunk. The relay cannot decrypt
 * it, but it can enforce the exact nonce and ciphertext size implied by the
 * approved transfer manifest before forwarding bytes to another paired device.
 * Android encodes these values as canonical, unpadded standard Base64.
 */
export function validEncryptedTransferChunkEnvelope(nonce, ciphertext, totalBytes, totalChunks, index) {
  const CHUNK_BYTES = 48 * 1024;
  if (!Number.isSafeInteger(totalBytes) || totalBytes < 1 || totalBytes > MAX_TRANSFER_BYTES ||
      !Number.isSafeInteger(totalChunks) || totalChunks !== Math.ceil(totalBytes / CHUNK_BYTES) ||
      !Number.isSafeInteger(index) || index < 0 || index >= totalChunks) return false;
  if (typeof nonce !== 'string' || !/^[A-Za-z0-9+/]{16}$/.test(nonce) ||
      typeof ciphertext !== 'string' || !/^[A-Za-z0-9+/]+$/.test(ciphertext)) return false;
  const nonceBytes = Buffer.from(nonce, 'base64');
  if (nonceBytes.length !== 12 || nonceBytes.toString('base64').replace(/=+$/g, '') !== nonce) return false;
  const expectedPlaintextBytes = Math.min(CHUNK_BYTES, totalBytes - index * CHUNK_BYTES);
  const expectedCiphertextBytes = expectedPlaintextBytes + 16; // AES-GCM authentication tag
  const expectedBase64Length = Math.ceil(expectedCiphertextBytes * 4 / 3);
  if (ciphertext.length !== expectedBase64Length) return false;
  const encryptedBytes = Buffer.from(ciphertext, 'base64');
  return encryptedBytes.length === expectedCiphertextBytes &&
    encryptedBytes.toString('base64').replace(/=+$/g, '') === ciphertext;
}
