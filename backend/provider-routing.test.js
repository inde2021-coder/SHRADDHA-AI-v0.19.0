import test from 'node:test';
import assert from 'node:assert/strict';
import { resolveProviderOrder, configuredProviderMap, supportedProviders } from './provider-routing.js';

test('auto routing only includes providers with server-side keys', () => {
  const env = { GEMINI_API_KEY: 'g', GROQ_API_KEY: 'q' };
  assert.deepEqual(resolveProviderOrder({ requested: 'auto', env }), ['gemini', 'groq']);
});

test('custom order is respected, deduplicated and ignores unavailable or unknown providers', () => {
  const env = {
    GEMINI_API_KEY: 'g', OPENAI_API_KEY: 'o', GROQ_API_KEY: 'q',
    AI_PROVIDER_ORDER: 'groq,unknown,gemini,groq,openai'
  };
  assert.deepEqual(resolveProviderOrder({ requested: 'auto', env }), ['groq', 'gemini', 'openai']);
});

test('default provider takes precedence over configured order', () => {
  const env = { GEMINI_API_KEY: 'g', OPENAI_API_KEY: 'o', AI_DEFAULT_PROVIDER: 'openai' };
  assert.deepEqual(resolveProviderOrder({ requested: 'auto', env }), ['openai', 'gemini']);
});

test('auto routing returns empty when no provider keys exist', () => {
  assert.deepEqual(resolveProviderOrder({ requested: 'auto', env: {} }), []);
});

test('explicit provider remains fixed and does not silently fall back', () => {
  assert.deepEqual(resolveProviderOrder({ requested: 'groq', env: { GEMINI_API_KEY: 'g' } }), ['groq']);
  assert.deepEqual(resolveProviderOrder({ requested: 'unknown', env: {} }), []);
});

test('provider status never exposes key material', () => {
  const status = configuredProviderMap({ GEMINI_API_KEY: 'secret', OPENAI_API_KEY: '' });
  assert.deepEqual(status, { gemini: true, openai: false, anthropic: false, groq: false, perplexity: false });
  assert.equal(JSON.stringify(status).includes('secret'), false);
  assert.deepEqual(supportedProviders, ['gemini', 'openai', 'anthropic', 'groq', 'perplexity']);
});
