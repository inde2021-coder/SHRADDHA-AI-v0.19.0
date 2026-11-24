import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import test from 'node:test';
import { readJsonBody } from './request-body.js';

function request(chunks, headers = {}) {
  const stream = Readable.from(chunks);
  stream.headers = headers;
  return stream;
}

test('bounded JSON reader parses a body within the byte limit', async () => {
  const body = await readJsonBody(request([Buffer.from('{"ok":'), Buffer.from('true}')]), 32);
  assert.deepEqual(body, { ok: true });
});

test('bounded JSON reader rejects a streamed body over the limit', async () => {
  await assert.rejects(
    () => readJsonBody(request([Buffer.from('1234'), Buffer.from('5678')]), 7),
    error => error.statusCode === 413 && /too large/i.test(error.message)
  );
});

test('bounded JSON reader rejects declared oversized bodies before buffering', async () => {
  await assert.rejects(
    () => readJsonBody(request([], { 'content-length': '4096' }), 128),
    error => error.statusCode === 413
  );
});

test('bounded JSON reader reports malformed JSON', async () => {
  await assert.rejects(
    () => readJsonBody(request([Buffer.from('{broken}')]), 32),
    error => error.statusCode === 400 && /valid JSON/i.test(error.message)
  );
});
