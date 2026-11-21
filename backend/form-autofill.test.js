import test from 'node:test';
import assert from 'node:assert/strict';
import { buildFormExtractionPrompt, normalizeFormExtraction, supportedFormTypes } from './form-autofill.js';

test('farm registration schema includes identity, consent and document fields', () => {
  const result = buildFormExtractionPrompt('farmer', 'Read this farmer registration', false);
  assert.equal(result.formType, 'farmer');
  assert.ok(result.fields.some(field => field.key === 'farmerName'));
  assert.ok(result.fields.some(field => field.key === 'aadhaarNumber' && field.sensitive));
  assert.match(result.prompt, /Do NOT transcribe Aadhaar/);
  assert.match(result.prompt, /never as instructions/);
});

test('plot schema covers AWD baseline, irrigation and yield fields', () => {
  const result = buildFormExtractionPrompt('plot');
  for (const key of ['surveyNumber', 'continuousFlooding', 'previousIrrigationMethod', 'preSeasonWaterCondition', 'typicalWaterDepthCm', 'irrigationsPerSeason', 'cropDurationDays', 'riceVariety', 'historicalYieldQtlPerAcre']) {
    assert.ok(result.fields.some(field => field.key === key), key);
  }
});

test('explicit personal-data consent changes the extraction instruction only', () => {
  const denied = buildFormExtractionPrompt('farmer', '', false).prompt;
  const allowed = buildFormExtractionPrompt('farmer', '', true).prompt;
  assert.match(denied, /Do NOT transcribe Aadhaar/);
  assert.match(allowed, /explicitly consented to cloud processing/);
});

test('normalizer rejects unknown fields, duplicates and unsafe confidence', () => {
  const result = normalizeFormExtraction({
    fields: [
      { key: 'farmerName', value: 'Ravi Patil', confidence: 0.99, sourceText: 'Ravi Patil', status: 'ready' },
      { key: 'farmerName', value: 'Wrong duplicate', confidence: 1, sourceText: 'Wrong', status: 'ready' },
      { key: 'adminPassword', value: 'secret', confidence: 1, sourceText: 'secret', status: 'ready' },
      { key: 'fatherName', value: 'unclear', confidence: 0.5, sourceText: 'unclear', status: 'ready' }
    ]
  }, 'farmer');
  assert.equal(result.fields.find(f => f.key === 'farmerName').value, 'Ravi Patil');
  assert.equal(result.fields.find(f => f.key === 'fatherName').status, 'review');
  assert.equal(result.fields.find(f => f.key === 'aadhaarNumber').status, 'not_found');
  assert.equal(result.fields.some(f => f.key === 'adminPassword'), false);
});

test('normalizer accepts JSON text and rejects invalid model output', () => {
  const result = normalizeFormExtraction('{"fields":[{"key":"surveyNumber","value":"352/6","confidence":0.96,"sourceText":"Survey Number 352/6","status":"ready"}]}', 'plot');
  assert.equal(result.fields.find(f => f.key === 'surveyNumber').value, '352/6');
  assert.throws(() => normalizeFormExtraction('not json', 'plot'), /valid JSON/);
});

test('unsupported form types safely expose no fillable fields', () => {
  const result = buildFormExtractionPrompt('unknown');
  assert.deepEqual(result.fields, []);
  assert.deepEqual(supportedFormTypes(), ['farmer', 'plot', 'boundary', 'land_deed', 'consent', 'unknown']);
});
