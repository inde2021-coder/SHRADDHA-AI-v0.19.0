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

const OPERATOR_ACTIONS = new Set(['OPEN_APP', 'CLICK_TEXT', 'TYPE_TEXT', 'BACK', 'HOME', 'SCROLL_FORWARD']);
const SECRET_INPUT = /(?:\b(?:password|passcode|otp|one\s*time(?:\s*(?:password|code|pin))?|verification\s*code|security\s*code|secret\s*key|recovery\s*(?:phrase|code)|cvv|cvc|upi\s*pin|bank\s*pin|pin)\b|पासवर्ड|पासकोड|ओटीपी|ओ टी पी|वन टाइम पासवर्ड|सत्यापन कोड|सुरक्षा कोड|गुप्त कुंजी|रिकवरी वाक्यांश|रिकवरी कोड|सीवीवी|यूपीआई पिन|बैंक पिन|पिन)/i;

function containsSensitiveInput(parts) {
  const source = parts.map(value => String(value ?? '')).join(' ');
  const normalized = source.normalize('NFKC').replace(/[\u200B-\u200D\uFEFF]/g, '');
  if (SECRET_INPUT.test(normalized)) return true;

  // Catch letter-by-letter voice transcription without matching substrings
  // inside ordinary words (for example, "spinach" contains the letters "pin").
  if (/(?:^|[^a-z])(?:o\s*t\s*p|p\s*i\s*n|c\s*v\s*v)(?:$|[^a-z])/i.test(normalized)) return true;

  const compactHindi = normalized.replace(/[^\u0900-\u097F]/g, '');
  return /(?:पासवर्ड|पासकोड|ओटीपी|वनटाइमपासवर्ड|सत्यापनकोड|सुरक्षाकोड|गुप्तकुंजी|रिकवरीवाक्यांश|रिकवरीकोड|सीवीवी|यूपीआईपिन|बैंकपिन|पिन)/.test(compactHindi);
}

/** Convert untrusted model JSON into a tiny, explicit Android accessibility action vocabulary. */
export function normalizeOperatorActions(rawSteps) {
  if (!Array.isArray(rawSteps)) return [];
  const seen = new Set();
  const result = [];
  for (const item of rawSteps.slice(0, 12)) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) continue;
    const id = typeof item.id === 'string' ? item.id.trim() : '';
    const action = typeof item.action === 'string' ? item.action.trim().toUpperCase() : '';
    const title = typeof item.title === 'string' ? item.title.trim() : '';
    const target = typeof item.target === 'string' ? item.target.trim() : '';
    const value = typeof item.value === 'string' ? item.value : '';
    const expectedVisibleText = typeof item.expectedVisibleText === 'string' ? item.expectedVisibleText.trim() : '';
    if (!/^[A-Za-z0-9_-]{1,48}$/.test(id) || seen.has(id)) continue;
    if (!OPERATOR_ACTIONS.has(action) || !title || title.length > 100) continue;
    if (target.length > 120 || value.length > 500 || expectedVisibleText.length > 120) continue;
    if (containsSensitiveInput([title, target, value, expectedVisibleText])) continue;
    if (action === 'OPEN_APP' && (!target || value)) continue;
    if (action === 'CLICK_TEXT' && (!target || value)) continue;
    if (action === 'TYPE_TEXT' && (!value.trim() || target)) continue;
    if (!['OPEN_APP','CLICK_TEXT','TYPE_TEXT'].includes(action) && (target || value)) continue;
    seen.add(id);
    result.push({
      id, title, action, target, value, expectedVisibleText,
      requiresApproval: true,
      risk: ['OPEN_APP','BACK','HOME','SCROLL_FORWARD'].includes(action) ? 'READ_ONLY' : 'SENSITIVE'
    });
  }
  return result;
}
