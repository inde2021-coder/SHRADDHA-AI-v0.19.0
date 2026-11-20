import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import net from 'node:net';
import { once } from 'node:events';
import WebSocket from 'ws';
import { validRemoteAction } from '../relay-policy.js';

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


function nextWsMessage(socket, predicate, timeoutMs = 5_000) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { cleanup(); reject(new Error('Timed out waiting for WebSocket message')); }, timeoutMs);
    const onMessage = (data, isBinary) => {
      if (!predicate(data, isBinary)) return;
      cleanup();
      resolve([data, isBinary]);
    };
    const onClose = () => { cleanup(); reject(new Error('WebSocket closed before expected message')); };
    const cleanup = () => {
      clearTimeout(timeout);
      socket.off('message', onMessage);
      socket.off('close', onClose);
    };
    socket.on('message', onMessage);
    socket.once('close', onClose);
  });
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

test('screen frames are blocked until the sending device explicitly enables screen sharing', async () => {
  const ownerResponse = await fetch(`${baseUrl}/api/device/session`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'Frame Owner' })
  });
  const owner = await ownerResponse.json();
  assert.equal(ownerResponse.status, 201);

  const memberResponse = await fetch(`${baseUrl}/api/device/join`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'Frame Viewer', pairingCode: owner.pairingCode })
  });
  const member = await memberResponse.json();
  assert.equal(memberResponse.status, 200);

  const wsBase = baseUrl.replace(/^http/, 'ws');
  const ownerSocket = new WebSocket(`${wsBase}/ws?sessionId=${encodeURIComponent(owner.sessionId)}&deviceId=${encodeURIComponent(owner.deviceId)}`, {
    headers: { authorization: `Bearer ${owner.deviceToken}` }
  });
  const memberSocket = new WebSocket(`${wsBase}/ws?sessionId=${encodeURIComponent(member.sessionId)}&deviceId=${encodeURIComponent(member.deviceId)}`, {
    headers: { authorization: `Bearer ${member.deviceToken}` }
  });

  try {
    await Promise.all([once(ownerSocket, 'open'), once(memberSocket, 'open')]);
    const waitForPermission = expected => nextWsMessage(memberSocket, (data, isBinary) => {
      if (isBinary) return false;
      try {
        const message = JSON.parse(data.toString());
        return message.type === 'permission_status' && message.deviceId === owner.deviceId &&
          message.permissions?.screen === expected;
      } catch { return false; }
    });

    const firstFrame = nextWsMessage(memberSocket, (_data, isBinary) => isBinary);
    ownerSocket.send(JSON.stringify({ type: 'permission_update', permissions: { screen: false, camera: false } }));
    await waitForPermission(false);
    const deniedJpeg = Buffer.from([0xff, 0xd8, 0x00, 0x11]);
    ownerSocket.send(Buffer.concat([Buffer.from([1, 0]), deniedJpeg]));

    ownerSocket.send(JSON.stringify({ type: 'permission_update', permissions: { screen: true, camera: false } }));
    await waitForPermission(true);
    // A sender must not bypass the Android client's 2.5 MB image limit.
    ownerSocket.send(Buffer.concat([Buffer.from([1, 0]), Buffer.alloc(2_500_001)]));
    // Reject unknown frame types and oversized target-device routing headers.
    ownerSocket.send(Buffer.from([3, 0, 0xff, 0xd8]));
    ownerSocket.send(Buffer.concat([Buffer.from([1, 65]), Buffer.alloc(65, 0x61), Buffer.from([0xff, 0xd8])]));
    // Reject arbitrary binary payloads that do not begin with the JPEG SOI marker.
    ownerSocket.send(Buffer.concat([Buffer.from([1, 0]), Buffer.from([0x00, 0x11, 0x22, 0x33])]));
    const allowedJpeg = Buffer.from([0xff, 0xd8, 0x7a, 0x55]);
    ownerSocket.send(Buffer.concat([Buffer.from([1, 0]), allowedJpeg]));

    const [frameData, isBinary] = await firstFrame;
    assert.equal(isBinary, true);
    const frame = Buffer.from(frameData);
    const sourceLength = frame[0];
    assert.equal(frame.subarray(1, 1 + sourceLength).toString('utf8'), owner.deviceId);
    assert.equal(frame[1 + sourceLength], 1);
    assert.deepEqual(frame.subarray(2 + sourceLength), allowedJpeg,
      'a frame sent while screen permission was false must never reach the paired viewer');

    const waitForCameraPermission = expected => nextWsMessage(memberSocket, (data, isBinary) => {
      if (isBinary) return false;
      try {
        const message = JSON.parse(data.toString());
        return message.type === 'permission_status' && message.deviceId === owner.deviceId &&
          message.permissions?.camera === expected;
      } catch { return false; }
    });
    const firstCameraFrame = nextWsMessage(memberSocket, (_data, isBinary) => isBinary);
    ownerSocket.send(JSON.stringify({ type: 'permission_update', permissions: { screen: true, camera: false } }));
    await waitForCameraPermission(false);
    ownerSocket.send(Buffer.concat([Buffer.from([2, 0]), Buffer.from([0xff, 0xd8, 0x00, 0x22])]));
    ownerSocket.send(JSON.stringify({ type: 'permission_update', permissions: { screen: true, camera: true } }));
    await waitForCameraPermission(true);
    // Camera frames use the same strict per-image limit as screen frames.
    ownerSocket.send(Buffer.concat([Buffer.from([2, 0]), Buffer.alloc(2_500_001)]));
    const allowedCameraJpeg = Buffer.from([0xff, 0xd8, 0x7a, 0x66]);
    ownerSocket.send(Buffer.concat([Buffer.from([2, 0]), allowedCameraJpeg]));
    const [cameraData, cameraBinary] = await firstCameraFrame;
    assert.equal(cameraBinary, true);
    const cameraFrame = Buffer.from(cameraData);
    const cameraSourceLength = cameraFrame[0];
    assert.equal(cameraFrame[cameraSourceLength + 1], 2);
    assert.deepEqual(cameraFrame.subarray(cameraSourceLength + 2), allowedCameraJpeg,
      'a camera frame sent while camera permission was false must never reach the paired viewer');
  } finally {
    ownerSocket.close();
    memberSocket.close();
  }
});


test('remote action schema rejects malformed and out-of-range commands', () => {
  assert.equal(validRemoteAction({ kind: 'tap', x: -1, y: 50 }), false);
  assert.equal(validRemoteAction({ kind: 'tap', x: 50, y: 50 }), true);
  assert.equal(validRemoteAction({ kind: 'swipe', x1: 1, y1: 2, x2: 10001, y2: 4 }), false);
  assert.equal(validRemoteAction({ kind: 'type', text: 'hello' }), true);
  assert.equal(validRemoteAction({ kind: 'type', text: 'x'.repeat(2001) }), false);
  assert.equal(validRemoteAction({ kind: 'click_text', text: '   ' }), false);
  assert.equal(validRemoteAction({ kind: 'open_app', appName: 'Chrome' }), true);
  assert.equal(validRemoteAction({ kind: 'open_app', appName: 'x'.repeat(81) }), false);
  assert.equal(validRemoteAction({ kind: 'shell', command: 'rm -rf /' }), false);
  assert.equal(validRemoteAction(null), false);
});


