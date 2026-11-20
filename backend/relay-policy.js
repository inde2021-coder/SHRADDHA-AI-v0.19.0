// Relay-side privacy policy. Permissions must be explicit booleans; absent or malformed
// permission state always fails closed.
export function canShareVisualFrame(permissions, frameType) {
  if (!permissions || typeof permissions !== 'object') return false;
  if (frameType === 1) return permissions.screen === true;
  if (frameType === 2) return permissions.camera === true;
  return false;
}

// Per-device visual stream budget: maxFrames in each rolling one-second window.
// Mutates only the in-memory device counters owned by the relay session.
export function consumeVisualFrameBudget(device, now = Date.now(), maxFrames = 10) {
  if (!device || !Number.isFinite(now) || !Number.isInteger(maxFrames) || maxFrames < 1) return false;
  if (!Number.isFinite(device.frameWindowStart) || now - device.frameWindowStart >= 1000 || now < device.frameWindowStart) {
    device.frameWindowStart = now;
    device.frameCount = 0;
  }
  if (!Number.isInteger(device.frameCount) || device.frameCount < 0) device.frameCount = 0;
  if (device.frameCount >= maxFrames) return false;
  device.frameCount += 1;
  return true;
}
