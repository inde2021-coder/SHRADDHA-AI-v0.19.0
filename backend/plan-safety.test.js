import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizePlanSteps, normalizeOperatorActions } from './plan-safety.js';

test('device, camera, screen and data actions always require approval', () => {
  const tools = ['open_app', 'click_text', 'type', 'tap', 'swipe', 'scroll', 'back', 'home', 'camera_view', 'screen_view', 'share_file'];
  const steps = [...normalizePlanSteps(tools.slice(0, 8).map(tool => ({ title: tool, detail: 'Do action', tool, requiresApproval: false, risk: 'low' }))), ...normalizePlanSteps(tools.slice(8).map(tool => ({ title: tool, detail: 'Do action', tool, requiresApproval: false, risk: 'low' })))];
  assert.equal(steps.length, tools.length);
  for (const step of steps) {
    assert.equal(step.requiresApproval, true, step.tool);
    assert.equal(step.risk, 'medium', step.tool);
  }
});

test('chat and ask_user may be non-action steps', () => {
  const steps = normalizePlanSteps([
    { title: 'Explain', detail: 'Give information', tool: 'chat', requiresApproval: false, risk: 'low' },
    { title: 'Clarify', detail: 'Ask a question', tool: 'ask_user', requiresApproval: false, risk: 'low' }
  ]);
  assert.deepEqual(steps.map(x => x.requiresApproval), [false, false]);
});

test('invalid tools, malformed steps and empty fields are discarded', () => {
  const steps = normalizePlanSteps([
    null, [], { title: ' ', detail: 'bad', tool: 'chat' },
    { title: 'Run shell', detail: 'execute arbitrary shell', tool: 'shell' },
    { title: 'Valid', detail: 'Explain safely', tool: 'chat' }
  ]);
  assert.equal(steps.length, 1);
  assert.equal(steps[0].title, 'Valid');
});

test('plan step count and field lengths are bounded', () => {
  const steps = normalizePlanSteps(Array.from({ length: 12 }, (_, i) => ({
    title: 'T'.repeat(150) + i, detail: 'D'.repeat(600), tool: 'tap'
  })));
  assert.equal(steps.length, 8);
  assert.equal(steps[0].title.length, 120);
  assert.equal(steps[0].detail.length, 500);
});

test('AI operator plans accept only bounded accessibility actions and force approval', () => {
  const steps = normalizeOperatorActions([
    { id: 'open', title: 'Open Settings', action: 'OPEN_APP', target: 'Settings', risk: 'LOW', requiresApproval: false },
    { id: 'type', title: 'Enter search', action: 'TYPE_TEXT', value: 'network settings' },
    { id: 'shell', title: 'Run shell', action: 'SHELL', target: 'cmd' },
    { id: 'otp', title: 'Enter OTP', action: 'TYPE_TEXT', value: 'OTP 123456' }
  ]);
  assert.deepEqual(steps.map(x => x.action), ['OPEN_APP', 'TYPE_TEXT']);
  assert.ok(steps.every(x => x.requiresApproval));
  assert.equal(steps[0].risk, 'READ_ONLY');
  assert.equal(steps[1].risk, 'SENSITIVE');
});

test('AI operator plans reject duplicate IDs, invalid fields and unsafe secret input', () => {
  const steps = normalizeOperatorActions([
    { id: 'same', title: 'Open', action: 'OPEN_APP', target: 'Settings' },
    { id: 'same', title: 'Open again', action: 'OPEN_APP', target: 'Chrome' },
    { id: 'bad action', title: 'Bad', action: 'OPEN_APP', target: 'Settings' },
    { id: 'secret', title: 'Fill', action: 'TYPE_TEXT', value: 'password: secret' },
    { id: 'type-target', title: 'Fill', action: 'TYPE_TEXT', target: 'field', value: 'hello' },
    { id: 'mixed-open', title: 'Open', action: 'OPEN_APP', target: 'Settings', value: 'extra' }
  ]);
  assert.equal(steps.length, 1);
  assert.equal(steps[0].id, 'same');
});

test('AI operator plans are capped to twelve steps and reject oversized content', () => {
  const raw = Array.from({ length: 15 }, (_, i) => ({ id: `s${i}`, title: 'Open app', action: 'OPEN_APP', target: 'A'.repeat(140) }));
  assert.equal(normalizeOperatorActions(raw).length, 0);
});


test('operator expected-visible-text cannot carry secret field labels', () => {
  const actions = normalizeOperatorActions([{
    id: 'safe-expected-text', title: 'Wait for prompt', action: 'CLICK_TEXT',
    target: 'Continue', expectedVisibleText: 'Enter OTP to continue'
  }]);
  assert.deepEqual(actions, []);
});


test('operator plans reject spaced, Unicode compatibility and Hindi secret labels', () => {
  const unsafeValues = [
    'O T P 123456',
    'P I N 1234',
    'one time password 1234',
    'security code 1234',
    'UPI PIN 1234',
    '\\u200bOTP 123456',
    'ＯＴＰ 123456',
    'ओ टी पी 123456',
    'यूपीआई पिन 1234',
    'सुरक्षा कोड 1234'
  ];
  for (const [index, value] of unsafeValues.entries()) {
    assert.deepEqual(normalizeOperatorActions([{
      id: `secret-${index}`, title: 'Enter details', action: 'TYPE_TEXT', value
    }]), [], value);
  }
});

test('operator plan allows ordinary text that merely mentions a normal task', () => {
  const actions = normalizeOperatorActions([{
    id: 'ordinary', title: 'Search settings', action: 'TYPE_TEXT', value: 'open network settings'
  }]);
  assert.equal(actions.length, 1);
  assert.equal(actions[0].value, 'open network settings');
});

test('operator plan does not mistake secret substrings inside ordinary words', () => {
  const actions = normalizeOperatorActions([{
    id: 'ordinary-food', title: 'Enter grocery item', action: 'TYPE_TEXT', value: 'spinach'
  }]);
  assert.equal(actions.length, 1);
  assert.equal(actions[0].value, 'spinach');
});
