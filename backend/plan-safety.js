const ALWAYS_APPROVAL = new Set([
  'open_app', 'click_text', 'type', 'tap', 'swipe', 'scroll', 'back', 'home',
  'camera_view', 'screen_view', 'share_file'
]);

const TOOLS = new Set([
  'chat', 'open_app', 'click_text', 'type', 'tap', 'swipe', 'scroll', 'back',
  'home', 'camera_view', 'screen_view', 'share_file', 'ask_user'
]);

export function normalizePlanSteps(rawSteps, allowedTools = TOOLS) {
  if (!Array.isArray(rawSteps)) return [];
  return rawSteps.slice(0, 8)
    .filter(step => step && typeof step === 'object' && !Array.isArray(step)
      && typeof step.title === 'string' && step.title.trim()
      && typeof step.detail === 'string' && step.detail.trim()
      && allowedTools.has(step.tool) && TOOLS.has(step.tool))
    .map(step => {
      const requiresApproval = ALWAYS_APPROVAL.has(step.tool) || step.requiresApproval !== false;
      const modelRisk = ['low', 'medium', 'high'].includes(step.risk) ? step.risk : 'medium';
      const risk = ALWAYS_APPROVAL.has(step.tool) && modelRisk === 'low' ? 'medium' : modelRisk;
      return {
        title: step.title.trim().slice(0, 120),
        detail: step.detail.trim().slice(0, 500),
        tool: step.tool,
        requiresApproval,
        risk
      };
    });
}
