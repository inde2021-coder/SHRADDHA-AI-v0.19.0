/** Explicit per-device consent policy for relayed visual frames. */
export function canShareVisualFrame(sharePermissions, frameType) {
  if (frameType !== 1 && frameType !== 2) return false;
  const category = frameType === 1 ? 'screen' : 'camera';
  return sharePermissions?.[category] === true;
}


/** Consume a pairing code exactly once; expired or malformed codes are removed. */
export function consumePairingCode(pairCodes, code, now = Date.now()) {
  if (!(pairCodes instanceof Map) || typeof code !== 'string' || !/^\\d{6}$/.test(code)) return null;
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
