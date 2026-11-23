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

export const ALLOWED_REMOTE_ACTION_KINDS = new Set([
  'tap','swipe','scroll','back','home','type','click_text','open_app','ocr',
  'file_list','file_read','file_search','file_write','offline_chat','code_assistant',
  'media_control','system_control','screenshot','clipboard_transform','web_search'
]);

export function validRemoteActionEnvelope(actionId, action, now = Date.now()) {
  return typeof actionId === 'string' && /^[A-Za-z0-9_-]{12,64}$/.test(actionId) &&
    action && typeof action === 'object' && !Array.isArray(action) &&
    ALLOWED_REMOTE_ACTION_KINDS.has(action.kind) &&
    Number.isFinite(now);
}

export function revokePairedDevice(session, deviceId, actorId) {
  if (!session?.devices || typeof deviceId !== 'string' || !deviceId || deviceId === actorId) return false;
  const actor = session.devices.get(actorId);
  const target = session.devices.get(deviceId);
  if (!actor || !target || actor.role !== 'owner') return false;
  target.controlGrants?.clear(); target.pendingControlRequests?.clear(); target.grants?.clear();
  target.incomingTransfers?.clear();
  try { target.socket?.close(4003, 'Device revoked by owner'); } catch {}
  session.devices.delete(deviceId);
  return true;
}

/** Per-device visual relay budget. Clock rollback starts a fresh bounded window. */
export function consumeVisualFrameBudget(device, now = Date.now(), limit = 10) {
  if (!device || typeof device !== 'object' || !Number.isFinite(now) ||
      !Number.isSafeInteger(limit) || limit < 1 || limit > 60) return false;
  if (!Number.isFinite(device.frameWindowStart) || now < device.frameWindowStart ||
      now - device.frameWindowStart >= 1000) {
    device.frameWindowStart = now;
    device.frameCount = 0;
  }
  if (!Number.isSafeInteger(device.frameCount) || device.frameCount < 0) device.frameCount = 0;
  if (device.frameCount >= limit) return false;
  device.frameCount += 1;
  return true;
}

/** Strict schema validation for the legacy, local UI-action vocabulary. */
export function validRemoteAction(action) {
  if (!action || typeof action !== 'object' || Array.isArray(action)) return false;
  const inRange = value => Number.isFinite(value) && value >= 0 && value <= 10000;
  switch (action.kind) {
    case 'tap':
      return inRange(action.x) && inRange(action.y);
    case 'swipe':
      return inRange(action.x1) && inRange(action.y1) && inRange(action.x2) && inRange(action.y2);
    case 'type':
      return typeof action.text === 'string' && action.text.trim().length > 0 && action.text.length <= 2000;
    case 'click_text':
      return typeof action.text === 'string' && action.text.trim().length > 0 && action.text.length <= 120;
    case 'open_app':
      return typeof action.appName === 'string' && action.appName.trim().length > 0 && action.appName.length <= 80;
    default:
      return false;
  }
}


/**
 * Fail-closed authorization for an already-approved remote-control action.
 * The target's current opt-in and the session's current control mode are
 * checked on every action, not only when the original request was approved.
 */
export function canUseRemoteControl(session, requesterId, targetId, now = Date.now()) {
  if (!session?.devices || !(session.devices instanceof Map) ||
      typeof requesterId !== 'string' || typeof targetId !== 'string' ||
      !requesterId || !targetId || requesterId === targetId || !Number.isFinite(now)) return false;
  if (session.controlMode !== 'mutual' && session.controlMode !== 'master') return false;
  const requester = session.devices.get(requesterId);
  const target = session.devices.get(targetId);
  if (!requester || !target || target.sharePermissions?.remote_control !== true) return false;
  if (session.controlMode === 'master' && requester.role !== 'owner') return false;
  const expiresAt = target.controlGrants instanceof Map ? target.controlGrants.get(requesterId) : 0;
  return Number.isFinite(expiresAt) && expiresAt > now;
}
