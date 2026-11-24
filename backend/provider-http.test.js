import test from 'node:test';
import assert from 'node:assert/strict';
import { requestProviderJson } from './provider-http.js';

function response(status, body, headers = {}) {
  const raw = typeof body === 'string' ? body : JSON.stringify(body);
  const bytes = Buffer.from(raw);
  const stream = new ReadableStream({
    start(controller) { controller.enqueue(bytes); controller.close(); }
  });
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: {
      get(name) { return headers[name.toLowerCase()] ?? (name.toLowerCase() === 'content-length' ? String(bytes.byteLength) : null); }
    },
    body: stream,
    async text() { return raw; }
  };
}

test('provider transport accepts HTTPS and parses successful JSON from a stream', async () => {
  const result = await requestProviderJson('https://provider.example/v1', {}, {
    fetchImpl: async () => response(200, { answer: 'नमस्ते' })
  });
  assert.deepEqual(result, { answer: 'नमस्ते' });
});

test('provider transport retries a rate limit for an explicitly idempotent POST', async () => {
  let calls = 0;
  const waits = [];
  const result = await requestProviderJson('https://provider.example/v1', {
    method: 'POST', headers: { 'Idempotency-Key': 'request-123' }
  }, {
    fetchImpl: async () => ++calls === 1
      ? response(429, { error: { message: 'Rate limited' } }, { 'retry-after': '0' })
      : response(200, { answer: 'ok' }),
    delay: async ms => waits.push(ms)
  });
  assert.equal(result.answer, 'ok');
  assert.equal(calls, 2);
  assert.equal(waits.length, 1);
});

test('provider transport does not retry a mutating request without an idempotency key', async () => {
  let calls = 0;
  await assert.rejects(() => requestProviderJson('https://provider.example/v1', { method: 'POST' }, {
    fetchImpl: async () => { calls++; return response(503, { message: 'Unavailable' }); },
    delay: async () => {}
  }), /Unavailable/);
  assert.equal(calls, 1);
});

test('provider transport retries transient server errors only for safe requests', async () => {
  let calls = 0;
  const waits = [];
  await assert.rejects(() => requestProviderJson('https://provider.example/v1', {
    method: 'POST', headers: { 'idempotency-key': 'stable-request-456' }
  }, {
    fetchImpl: async () => { calls++; return response(503, { message: 'Unavailable' }); },
    delay: async ms => waits.push(ms)
  }), /Unavailable/);
  assert.equal(calls, 3);
  assert.equal(waits.length, 2);
});

test('provider transport retries a transient network error for GET', async () => {
  let calls = 0;
  const result = await requestProviderJson('https://provider.example/v1', {}, {
    fetchImpl: async () => {
      calls++;
      if (calls === 1) throw new Error('temporary network failure');
      return response(200, { answer: 'ok' });
    },
    delay: async () => {}
  });
  assert.equal(result.answer, 'ok');
  assert.equal(calls, 2);
});

test('provider transport does not retry malformed or oversized response bodies', async () => {
  let calls = 0;
  await assert.rejects(() => requestProviderJson('https://provider.example/v1', {}, {
    fetchImpl: async () => { calls++; return response(200, 'not-json'); },
    delay: async () => {}
  }), /invalid JSON/);
  assert.equal(calls, 1);

  calls = 0;
  const oversizedWithoutLength = response(200, 'x'.repeat(2 * 1024 * 1024 + 1));
  oversizedWithoutLength.headers.get = () => null;
  await assert.rejects(() => requestProviderJson('https://provider.example/v1', {}, {
    fetchImpl: async () => { calls++; return oversizedWithoutLength; },
    delay: async () => {}
  }), /2 MiB/);
  assert.equal(calls, 1);
});

test('provider transport rejects non-HTTPS endpoints', async () => {
  await assert.rejects(() => requestProviderJson('http://provider.example/v1', {}, {
    fetchImpl: async () => response(200, {})
  }), /HTTPS/);
});
