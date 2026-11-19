import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizePlanSteps } from './plan-safety.js';

test('device, camera, screen and data actions always require approval', () => {
  const tools = ['open_app', 'click_text', 'type', 'tap', 'swipe', 'scroll', 'back', 'home', 'camera_view', 'screen_view', 'share_file'];
  const steps = normalizePlanSteps(tools.map(tool => ({ title: tool, detail: 'Do action', tool, requiresApproval: false, risk: 'low' })));
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
