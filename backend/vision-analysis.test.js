import test from 'node:test';
import assert from 'node:assert/strict';
import { buildVisionPrompt, supportedVisionModes } from './vision-analysis.js';

test('document mode requests faithful transcription and uncertainty markers', () => {
  const result = buildVisionPrompt('document', 'Read this invoice');
  assert.equal(result.mode, 'document');
  assert.match(result.prompt, /Transcribe only legible text/);
  assert.match(result.prompt, /\[unclear\]/);
});

test('object mode avoids identity inference', () => {
  const result = buildVisionPrompt('objects', 'Count tools');
  assert.match(result.prompt, /approximate counts/);
  assert.match(result.prompt, /Do not identify people/);
});

test('machine mode includes electrical and mechanical safety limits', () => {
  const result = buildVisionPrompt('machine', 'What is damaged?');
  assert.match(result.prompt, /definitive diagnosis/);
  assert.match(result.prompt, /energized equipment/);
});

test('unknown mode safely falls back to general and prompt length is bounded', () => {
  const result = buildVisionPrompt('unknown', 'x'.repeat(5000));
  assert.equal(result.mode, 'general');
  assert.ok(result.prompt.length < 4200);
  assert.deepEqual(supportedVisionModes, ['general', 'objects', 'document', 'machine']);
});
