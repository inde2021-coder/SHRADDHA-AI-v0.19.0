import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import net from 'node:net';
import { once } from 'node:events';

const TOKEN = 'shraddha-ci-test-token';
let child;
let baseUrl;

async function freePort() {
  const server = net.createServer();
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}

before(async () => {
  const port = await freePort();
  baseUrl = `http://127.0.0.1:${port}`;
  child = spawn(process.execPath, ['server.js'], {
    cwd: new URL('..', import.meta.url),
    env: { ...process.env, PORT: String(port), GATEWAY_TOKEN: TOKEN },
    stdio: 'ignore'
  });
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`Relay exited early: ${child.exitCode}`);
    try {
      const response = await fetch(`${baseUrl}/health`);
      if (response.ok) return;
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error('Relay did not become healthy within 10 seconds.');
});

after(async () => {
  if (child && child.exitCode === null) {
    child.kill('SIGTERM');
    await Promise.race([once(child, 'exit'), new Promise(resolve => setTimeout(resolve, 2_000))]);
    if (child.exitCode === null) child.kill('SIGKILL');
  }
});

test('health endpoint reports relay status without exposing credentials', async () => {
  const response = await fetch(`${baseUrl}/health`);
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.equal(body.ok, true);
  assert.equal(body.service, 'Shraddha AI secure device relay');
  assert.equal(JSON.stringify(body).includes(TOKEN), false);
});

test('AI gateway rejects missing and incorrect bearer credentials', async () => {
  for (const authorization of [undefined, 'Bearer wrong-token']) {
    const response = await fetch(`${baseUrl}/api/ai/providers`, {
      headers: authorization ? { authorization } : {}
    });
    assert.equal(response.status, 401);
    assert.match((await response.json()).error, /authentication/i);
  }
});

test('Visual Lens validates prompt and base64 before provider invocation', async () => {
  const headers = { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' };
  const emptyPrompt = await fetch(`${baseUrl}/api/vision`, {
    method: 'POST', headers, body: JSON.stringify({ prompt: ' ', imageBase64: 'aGVsbG8=', mimeType: 'image/jpeg' })
  });
  assert.equal(emptyPrompt.status, 400);
  const malformedImage = await fetch(`${baseUrl}/api/vision`, {
    method: 'POST', headers, body: JSON.stringify({ prompt: 'Describe this image', imageBase64: 'not base64!', mimeType: 'image/jpeg' })
  });
  assert.equal(malformedImage.status, 400);
});

test('device pairing rejects invalid names and invalid pairing codes', async () => {
  const blankName = await fetch(`${baseUrl}/api/device/session`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: '   ' })
  });
  assert.equal(blankName.status, 400);
  const invalidCode = await fetch(`${baseUrl}/api/device/join`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'CI Device', pairingCode: '000000' })
  });
  assert.equal(invalidCode.status, 400);
});

test('device session creates scoped random credentials and a six-digit pairing code', async () => {
  const response = await fetch(`${baseUrl}/api/device/session`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'CI Owner' })
  });
  const body = await response.json();
  assert.equal(response.status, 201);
  assert.match(body.sessionId, /^[A-Za-z0-9_-]{20,}$/);
  assert.match(body.deviceId, /^[a-f0-9]{24}$/);
  assert.match(body.deviceToken, /^[A-Za-z0-9_-]{40,}$/);
  assert.match(body.pairingCode, /^\d{6}$/);
  assert.equal(body.expiresInSeconds, 300);
});
