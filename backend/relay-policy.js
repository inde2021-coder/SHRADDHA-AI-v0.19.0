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

/** Resolve a cancellation only when the actor is the active sender or receiver. */
export function resolveFileTransferCancellation(actor, target, actorId, targetId, transferId, now = Date.now()) {
  if (!actor || !target || !(actor.incomingTransfers instanceof Map) ||
      !(target.incomingTransfers instanceof Map) ||
      typeof actorId !== 'string' || !actorId ||
      typeof targetId !== 'string' || !targetId || actorId === targetId ||
      typeof transferId !== 'string' || !/^[A-Za-z0-9_-]{12,64}$/.test(transferId) ||
      !Number.isFinite(now)) return null;
  const targetRecord = target.incomingTransfers.get(transferId);
  if (targetRecord?.fromDeviceId === actorId &&
      Number.isFinite(targetRecord.expiresAt) && targetRecord.expiresAt > now) return 'sender';
  const actorRecord = actor.incomingTransfers.get(transferId);
  if (actorRecord?.fromDeviceId === targetId &&
      Number.isFinite(actorRecord.expiresAt) && actorRecord.expiresAt > now) return 'receiver';
  return null;
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
  target.incomingTransfers?.clear(); target.pendingTaskHandoffs?.clear();
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


export function validMissingTransferIndexes(indexes, chunkCount) {
  if (!Array.isArray(indexes) || !Number.isSafeInteger(chunkCount) || chunkCount < 1 || chunkCount > 2200 ||
      indexes.length > chunkCount || indexes.length > 2200) return false;
  const seen = new Set();
  for (const index of indexes) {
    if (!Number.isSafeInteger(index) || index < 0 || index >= chunkCount || seen.has(index)) return false;
    seen.add(index);
  }
  return true;
}


/**
 * Remove expired or malformed per-device grants and in-flight work.
 * Some entries carry numeric expiries; request/transfer records carry
 * an object with an expiresAt field. Invalid expiry data fails closed.
 */
export function canCompleteFileTransfer(transfer, ok) {
  if (!transfer || ok !== true || !Number.isSafeInteger(transfer.chunkCount) ||
      transfer.chunkCount < 1 || transfer.chunkCount > 2200 ||
      !(transfer.chunks instanceof Map) || transfer.chunks.size !== transfer.chunkCount) return false;
  for (let index = 0; index < transfer.chunkCount; index += 1) {
    if (transfer.chunks.get(index) !== true) return false;
  }
  return true;
}

export function canStartFileTransfer(device, transferId, maxActive = 1) {
  const transfers = device?.incomingTransfers;
  return transfers instanceof Map &&
    typeof transferId === 'string' && /^[A-Za-z0-9_-]{12,64}$/.test(transferId) &&
    Number.isSafeInteger(maxActive) && maxActive >= 1 && maxActive <= 16 &&
    transfers.size < maxActive && !transfers.has(transferId);
}

/** Synchronize relay ACK state with the receiver's persisted missing-chunk bitmap. */

export function recordPersistedFileTransferChunks(transfer, missingIndexes) {
  if (!transfer || !Number.isSafeInteger(transfer.chunkCount) ||
      !(transfer.chunks instanceof Map) ||
      !validMissingTransferIndexes(missingIndexes, transfer.chunkCount)) return false;
  const missing = new Set(missingIndexes);
  for (let index = 0; index < transfer.chunkCount; index += 1) {
    if (missing.has(index)) transfer.chunks.delete(index);
    else transfer.chunks.set(index, true);
  }
  return true;
}

export function pruneExpiredDeviceState(device, now = Date.now()) {
  if (!device || typeof device !== 'object' || !Number.isFinite(now)) return 0;
  let removed = 0;
  const prune = (map, numeric = false) => {
    if (!(map instanceof Map)) return;
    for (const [key, value] of map) {
      const expiresAt = numeric ? value : (typeof value === 'number' ? value : value?.expiresAt);
      if (!Number.isFinite(expiresAt) || expiresAt <= now) {
        map.delete(key);
        removed += 1;
      }
    }
  };
  prune(device.pendingControlRequests);
  prune(device.pendingDataShares);
  prune(device.pendingRemoteActions);
  prune(device.incomingTransfers);
  prune(device.pendingTaskHandoffs);
  prune(device.controlGrants, true);
  prune(device.grants);
  return removed;
}


/** Bound queued relay output so a slow paired device cannot grow server memory without limit. */
export function canQueueRelayPayload(bufferedAmount, payloadBytes, maxBufferedBytes = 8 * 1024 * 1024) {
  return Number.isSafeInteger(bufferedAmount) && bufferedAmount >= 0 &&
    Number.isSafeInteger(payloadBytes) && payloadBytes >= 0 &&
    Number.isSafeInteger(maxBufferedBytes) && maxBufferedBytes > 0 &&
    payloadBytes <= maxBufferedBytes && bufferedAmount <= maxBufferedBytes - payloadBytes;
}
