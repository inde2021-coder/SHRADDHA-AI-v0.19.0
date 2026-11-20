/** Explicit per-device consent policy for relayed visual frames. */
export function canShareVisualFrame(sharePermissions, frameType) {
  if (frameType !== 1 && frameType !== 2) return false;
  const category = frameType === 1 ? 'screen' : 'camera';
  return sharePermissions?.[category] === true;
}


/** Consume a pairing code exactly once; expired or malformed codes are removed. */
export function consumePairingCode(pairCodes, code, now = Date.now()) {
  if (!(pairCodes instanceof Map) || typeof code !== 'string' || !/^\d{6}$/.test(code)) return null;
  const entry = pairCodes.get(code);
  pairCodes.delete(code);
  if (!entry || !Number.isFinite(entry.expiresAt) || entry.expiresAt <= now) return null;
  return entry;
}

/** A remote-control approval must match a live request created by that requester. */
export function resolveControlApproval(pending, requesterId, approved, now = Date.now()) {
  if (!(pending instanceof Map) || typeof requesterId !== 'string' || !requesterId) return false;
  const request = pending.get(requesterId);
  pending.delete(requesterId);
  return Boolean(request && request.expiresAt > now && approved === true);
}

export function validFileTransferManifest(sizeBytes, chunkCount, sha256, transferId) {
  return Number.isSafeInteger(sizeBytes) && sizeBytes >= 1 && sizeBytes <= 100 * 1024 * 1024 &&
    Number.isSafeInteger(chunkCount) && chunkCount >= 1 && chunkCount <= 2200 &&
    chunkCount === Math.ceil(sizeBytes / (48 * 1024)) &&
    typeof sha256 === 'string' && /^[a-f0-9]{64}$/i.test(sha256) &&
    typeof transferId === 'string' && /^[A-Za-z0-9_-]{12,64}$/.test(transferId);
}

export function validFileTransferChunk(index, chunkCount, byteLength, totalBytes) {
  if (!Number.isSafeInteger(index) || !Number.isSafeInteger(chunkCount) || !Number.isSafeInteger(byteLength) ||
      !Number.isSafeInteger(totalBytes) || index < 0 || index >= chunkCount || chunkCount < 1 || totalBytes < 1 || totalBytes > 100 * 1024 * 1024) return false;
  const expected = index === chunkCount - 1 ? totalBytes - index * 48 * 1024 : 48 * 1024;
  return expected > 0 && byteLength === expected;
}
