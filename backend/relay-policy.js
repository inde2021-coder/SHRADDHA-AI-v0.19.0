/**
 * Strict schema validation for remote accessibility actions.
 * Coordinates are bounded to avoid malformed/extreme gesture payloads.
 */
export function validRemoteAction(action) {
  if (!action || typeof action !== 'object' || Array.isArray(action)) return false;
  const finiteCoordinate = value => typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 10000;
  switch (action.kind) {
    case 'tap': return finiteCoordinate(action.x) && finiteCoordinate(action.y);
    case 'swipe': return ['x1','y1','x2','y2'].every(key => finiteCoordinate(action[key]));
    case 'type': return typeof action.text === 'string' && action.text.length > 0 && action.text.length <= 2000;
    case 'click_text': return typeof action.text === 'string' && action.text.trim().length > 0 && action.text.length <= 120;
    case 'open_app': return typeof action.appName === 'string' && action.appName.trim().length > 0 && action.appName.length <= 80;
    case 'home':
    case 'back':
    case 'scroll': return true;
    default: return false;
  }
}
