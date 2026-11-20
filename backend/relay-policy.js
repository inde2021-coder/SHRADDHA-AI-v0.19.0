/** Explicit per-device consent policy for relayed visual frames. */
export function canShareVisualFrame(sharePermissions, frameType) {
  if (frameType !== 1 && frameType !== 2) return false;
  const category = frameType === 1 ? 'screen' : 'camera';
  return sharePermissions?.[category] === true;
}
