import test from 'node:test';
import assert from 'node:assert/strict';
import { requestProviderJson } from './provider-http.js';

function response(status, body, headers = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: {
      get(name) { return headers[name.toLowerCase()] ?? null; }
    },
    async text() { return typeof body === 'string' ? body : JSON.stringify(body); }
  };
}

test('provider transport accepts HTTPS and parses successful JSON', async () => {
  const result = await requestProviderJson('https://provider.example/v1', {}, {
    fetchImpl: async () => response(200, { answer: 'नमस्ते' })
  });
  assert.deepEqual(result, { answer: 'नमस्ते' });
});

test('provider transport retries rate limits and then succeeds', async () => {
  let calls = 0;
  const waits = [];
  const result = await requestProviderJson('https://provider.example/v1', {}, {
    fetchImpl: async () => ++calls === 1
      ? response(429, { error: { message: 'Rate limited' } }, { 'retry-after': '0' })
      : response(200, { answer: 'ok' }),
    delay: async ms => waits.push(ms)
  });
  assert.equal(result.answer, 'ok');
  assert.equal(calls, 2);
  assert.equal(waits.length, 1);
});

test('provider transport does not retry authentication and validation failures', async () => {
  let calls = 0;
  await assert.rejects(() => requestProviderJson('https://provider.example/v1', {}, {
    fetchImpl: async () => { calls++; return response(401, { error: { message: 'Invalid API key' } }); },
    delay: async () => {}
  }), /Invalid API key/);
  assert.equal(calls, 1);
});

test('provider transport retries transient server errors but respects maximum attempts', async () => {
  let calls = 0;
  const waits = [];
  await assert.rejects(() => requestProviderJson('https://provider.example/v1', {}, {
    fetchImpl: async () => { calls++; return response(503, { message: 'Unavailable' }); },
    delay: async ms => waits.push(ms)
  }), /Unavailable/);
  assert.equal(calls, 3);
  assert.equal(waits.length, 2);
});

test('provider transport rejects non-HTTPS endpoints and malformed or oversized payloads', async () => {
  await assert.rejects(() => requestProviderJson('http://provider.example/v1', {}, {
    fetchImpl: async () => response(200, {})
  }), /HTTPS/);
  await assert.rejects(() => requestProviderJson('https://provider.example/v1', {}, {
    fetchImpl: async () => response(200, 'not-json')
  }), /invalid JSON/);
  await assert.rejects(() => requestProviderJson('https://provider.example/v1', {}, {
    fetchImpl: async () => response(200, 'x'.repeat(2 * 1024 * 1024 + 1))
  }), /2 MiB/);
});
