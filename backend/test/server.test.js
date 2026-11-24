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


function waitForWebSocketOpen(socket) {
  if (socket.readyState === WebSocket.OPEN) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const onOpen = () => { cleanup(); resolve(); };
    const onError = error => { cleanup(); reject(error); };
    const onClose = () => { cleanup(); reject(new Error('WebSocket closed before opening')); };
    const cleanup = () => {
      socket.off('open', onOpen);
      socket.off('error', onError);
      socket.off('close', onClose);
    };
    socket.once('open', onOpen);
    socket.once('error', onError);
    socket.once('close', onClose);
  });
}

function closeWebSocket(socket) {
  if (socket.readyState === WebSocket.CLOSED) return Promise.resolve();
  if (socket.readyState === WebSocket.CONNECTING) {
    socket.terminate();
    return Promise.resolve();
  }
  return new Promise(resolve => {
    socket.once('close', resolve);
    socket.close();
  });
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
    env: { ...process.env, TRUST_PROXY: 'true', PORT: String(port), GATEWAY_TOKEN: TOKEN, EDESY_STREAM_TOKEN: 'e'.repeat(40), OPENAI_API_KEY: 'sk-test-openai-key-1234567890', EDESY_API_KEY: '', EDESY_STREAM_URL: '', PUBLIC_ORIGIN: '', CALL_TRANSCRIPTS_ENABLED: 'false', SHRADDHA_CALL_AGENT_NAME: '', FIREBASE_SERVICE_ACCOUNT_JSON: '', FIREBASE_SERVICE_ACCOUNT_BASE64: '', CALL_HISTORY_ENCRYPTION_KEY: '', TWILIO_ACCOUNT_SID: '', TWILIO_AUTH_TOKEN: '', TWILIO_SMS_FROM: '', TWILIO_WHATSAPP_FROM: '' },
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

test('provider catalog exposes only safe configuration, model and capability metadata', async () => {
  const response = await fetch(`${baseUrl}/api/ai/providers`, {
    headers: { authorization: `Bearer ${TOKEN}` }
  });
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.equal(body.providers.openai, true);
  assert.equal(body.providers.gemini, false);
  assert.equal(body.keysRemainServerSide, true);
  assert.equal(body.autoRouting.available, true);
  assert.equal(body.catalog.find(item => item.name === 'openai').model, 'gpt-4.1-mini');
  assert.deepEqual(body.catalog.find(item => item.name === 'groq').capabilities, ['text']);
  assert.equal(JSON.stringify(body).includes('sk-test-openai-key'), false);
});

test('SMS and WhatsApp relay requires exact preview, E.164 recipient and a unique request ID', async () => {
  const endpoint = `${baseUrl}/api/messages/send`;
  const headers = { 'content-type': 'application/json', authorization: `Bearer ${TOKEN}` };
  const base = { channel: 'whatsapp', to: '+919876543210', text: 'नमस्ते', preview: 'नमस्ते', confirmed: true, requestId: 'message_request_123456' };

  const unauthorized = await fetch(endpoint, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(base) });
  assert.equal(unauthorized.status, 401);

  const unconfirmed = await fetch(endpoint, { method: 'POST', headers, body: JSON.stringify({ ...base, confirmed: false }) });
  assert.equal(unconfirmed.status, 400);
  assert.match((await unconfirmed.json()).error, /confirmation/i);

  const invalidNumber = await fetch(endpoint, { method: 'POST', headers, body: JSON.stringify({ ...base, to: '9876543210', requestId: 'message_request_123457' }) });
  assert.equal(invalidNumber.status, 400);
  assert.match((await invalidNumber.json()).error, /E.164/i);

  const missingProvider = await fetch(endpoint, { method: 'POST', headers, body: JSON.stringify(base) });
  assert.equal(missingProvider.status, 503);
  const duplicate = await fetch(endpoint, { method: 'POST', headers, body: JSON.stringify(base) });
  assert.equal(duplicate.status, 409);
  assert.equal((await duplicate.json()).duplicate, true);

  const conflictingId = await fetch(endpoint, { method: 'POST', headers, body: JSON.stringify({ ...base, text: 'different message', preview: 'different message' }) });
  assert.equal(conflictingId.status, 409);
  assert.match((await conflictingId.json()).error, /different message/);

  const sms = await fetch(endpoint, { method: 'POST', headers, body: JSON.stringify({ ...base, channel: 'sms', requestId: 'message_request_123458' }) });
  assert.equal(sms.status, 503);

  const previewMismatch = await fetch(endpoint, { method: 'POST', headers, body: JSON.stringify({ ...base, requestId: 'message_request_123459', preview: 'edited preview' }) });
  assert.equal(previewMismatch.status, 400);
  assert.match((await previewMismatch.json()).error, /exact recipient and message preview/);
});

test('Edesy call bridge readiness is private and reports missing external provisioning honestly', async () => {
  const denied = await fetch(`${baseUrl}/api/telephony/edesy/status`);
  assert.equal(denied.status, 401);
  const response = await fetch(`${baseUrl}/api/telephony/edesy/status`, {
    headers: { authorization: `Bearer ${TOKEN}` }
  });
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.equal(body.provider, 'edesy');
  assert.equal(body.agentName, 'Shraddha AI');
  assert.equal(body.configured, true);
  assert.equal(body.preflightReady, false);
  assert.equal(body.capabilities.inboundWebSocketAudio, true);
  assert.equal(body.capabilities.liveBidirectionalAiAudio, true);
  assert.equal(body.capabilities.persistentCallRecording, false);
  assert.equal(body.callHistory.durable, false);
  assert.ok(body.callHistory.missing.includes('CALL_HISTORY_ENCRYPTION_KEY (base64-encoded 32-byte key)'));
  assert.ok(body.required.includes('EDESY_STREAM_TOKEN'));
  assert.ok(body.required.includes('OPENAI_API_KEY'));
  assert.ok(body.missing.includes('EDESY_API_KEY (sk_... Voice API key)'));
  assert.ok(body.missing.includes('EDESY_STREAM_URL'));
  assert.ok(body.missing.includes('PUBLIC_ORIGIN'));
});

test('Edesy preflight WebSocket echoes bounded PCM without opening an AI call', async () => {
  const token = 'e'.repeat(40);
  const socket = new WebSocket(`${baseUrl.replace('http:', 'ws:')}/ws/telephony/edesy?token=${token}&mode=preflight`);
  await once(socket, 'open');
  const frame = Buffer.from(Array.from({ length: 320 }, (_, index) => index % 256));
  const echoPromise = nextWsMessage(socket, (_data, binary) => binary);
  socket.send(JSON.stringify({ sampleRate: 16000, mixType: 'mono', callSid: 'preflight-only', direction: 'outbound' }));
  socket.send(frame, { binary: true });
  const [echo, isBinary] = await echoPromise;
  assert.equal(isBinary, true);
  assert.deepEqual(Buffer.from(echo), frame);
  const closed = once(socket, 'close');
  socket.close();
  await closed;
  const history = await fetch(`${baseUrl}/api/telephony/edesy/calls`, {
    headers: { authorization: `Bearer ${TOKEN}` }
  });
  assert.deepEqual((await history.json()).calls, []);
});

test('Edesy preflight requires gateway auth and server-side provider configuration', async () => {
  const denied = await fetch(`${baseUrl}/api/telephony/edesy/preflight`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({})
  });
  assert.equal(denied.status, 401);
  const unavailable = await fetch(`${baseUrl}/api/telephony/edesy/preflight`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${TOKEN}` },
    body: JSON.stringify({ sample_rate: 16000, seconds: 2 })
  });
  assert.equal(unavailable.status, 503);
});

test('Edesy call history requires gateway authentication', async () => {
  const denied = await fetch(`${baseUrl}/api/telephony/edesy/calls`);
  assert.equal(denied.status, 401);
  const allowed = await fetch(`${baseUrl}/api/telephony/edesy/calls`, {
    headers: { authorization: `Bearer ${TOKEN}` }
  });
  assert.equal(allowed.status, 503);
  const body = await allowed.json();
  assert.deepEqual(body.calls, []);
  assert.equal(body.durable, false);
  assert.match(body.warning, /FIREBASE_SERVICE_ACCOUNT_JSON|CALL_HISTORY_ENCRYPTION_KEY/);
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

test('end-to-end key offers and fingerprint confirmations are forwarded only to the addressed peer', async () => {
  const ownerResponse = await fetch(`${baseUrl}/api/device/session`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'E2E Owner' })
  });
  const owner = await ownerResponse.json();
  const memberResponse = await fetch(`${baseUrl}/api/device/join`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'E2E Member', pairingCode: owner.pairingCode })
  });
  const member = await memberResponse.json();
  const wsBase = baseUrl.replace(/^http/, 'ws');
  const connect = pair => new WebSocket(`${wsBase}/ws?sessionId=${encodeURIComponent(pair.sessionId)}&deviceId=${encodeURIComponent(pair.deviceId)}`, {
    headers: { authorization: `Bearer ${pair.deviceToken}` }
  });
  const ownerSocket = connect(owner);
  const memberSocket = connect(member);
  try {
    await Promise.all([once(ownerSocket, 'open'), once(memberSocket, 'open')]);
    const wait = (socket, type) => nextWsMessage(socket, data => {
      try { return JSON.parse(data.toString()).type === type; } catch { return false; }
    });
    const offerPromise = wait(memberSocket, 'e2e_key_offer');
    ownerSocket.send(JSON.stringify({ type: 'e2e_key_offer', targetDeviceId: member.deviceId, publicKey: 'A'.repeat(120) }));
    const [offer] = await offerPromise;
    const offerBody = JSON.parse(offer.toString());
    assert.equal(offerBody.fromDeviceId, owner.deviceId);
    assert.equal(offerBody.publicKey, 'A'.repeat(120));

    const confirmPromise = wait(ownerSocket, 'e2e_key_confirm');
    memberSocket.send(JSON.stringify({ type: 'e2e_key_confirm', targetDeviceId: owner.deviceId, fingerprint: 'ABCD-EF01-2345-6789-ABCD-EF01-2345-6789' }));
    const [confirm] = await confirmPromise;
    assert.equal(JSON.parse(confirm.toString()).fromDeviceId, member.deviceId);

    const malformedOfferForwarded = new Promise(resolve => {
      const listener = data => {
        try {
          if (JSON.parse(data.toString()).type === 'e2e_key_offer') {
            memberSocket.off('message', listener);
            resolve(true);
          }
        } catch {}
      };
      memberSocket.on('message', listener);
      setTimeout(() => { memberSocket.off('message', listener); resolve(false); }, 100);
    });
    ownerSocket.send(JSON.stringify({ type: 'e2e_key_offer', targetDeviceId: member.deviceId, publicKey: 'invalid!' }));
    assert.equal(await malformedOfferForwarded, false, 'malformed E2E key offers must not be forwarded');
    const validOffer = wait(memberSocket, 'e2e_key_offer');
    ownerSocket.send(JSON.stringify({ type: 'e2e_key_offer', targetDeviceId: member.deviceId, publicKey: 'B'.repeat(120) }));
    const [validMessage] = await validOffer;
    assert.equal(JSON.parse(validMessage.toString()).publicKey, 'B'.repeat(120));
  } finally {
    const closes = [ownerSocket, memberSocket].map(socket => socket.readyState === WebSocket.CLOSED ? Promise.resolve() : new Promise(resolve => { socket.once('close', resolve); socket.close(); }));
    await Promise.all(closes);
  }
});

test('disabling a data-sharing category clears pending and active grants and notifies requester', async () => {
  const ownerResponse = await fetch(`${baseUrl}/api/device/session`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'Share Requester' })
  });
  const owner = await ownerResponse.json();
  const memberResponse = await fetch(`${baseUrl}/api/device/join`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'Share Target', pairingCode: owner.pairingCode })
  });
  const member = await memberResponse.json();
  const wsBase = baseUrl.replace('http:', 'ws:');
  const connect = pair => new WebSocket(`${wsBase}/ws?sessionId=${encodeURIComponent(pair.sessionId)}&deviceId=${encodeURIComponent(pair.deviceId)}`, {
    headers: { authorization: `Bearer ${pair.deviceToken}` }
  });
  const ownerSocket = connect(owner);
  const ownerConnected = nextWsMessage(ownerSocket, data => {
    try { return JSON.parse(data.toString()).type === 'connected'; } catch { return false; }
  });
  await once(ownerSocket, 'open'); await ownerConnected;
  const memberSocket = connect(member);
  const memberConnected = nextWsMessage(memberSocket, data => {
    try { return JSON.parse(data.toString()).type === 'connected'; } catch { return false; }
  });
  await once(memberSocket, 'open'); await memberConnected;

  memberSocket.send(JSON.stringify({ type: 'permission_update', permissions: { media: true } }));
  const requestPromise = nextWsMessage(memberSocket, data => {
    try { return JSON.parse(data.toString()).type === 'data_share_request'; } catch { return false; }
  });
  ownerSocket.send(JSON.stringify({ type: 'data_share_request', targetDeviceId: member.deviceId, category: 'media', itemLabel: 'Photo' }));
  const [requestMessage] = await requestPromise;
  const firstShareRequest = JSON.parse(requestMessage.toString());
  assert.equal(firstShareRequest.fromDeviceId, owner.deviceId);
  assert.match(firstShareRequest.requestId, /^[A-Za-z0-9_-]{12,64}$/);

  const approvedPromise = nextWsMessage(ownerSocket, data => {
    try { const msg = JSON.parse(data.toString()); return msg.type === 'data_share_result' && msg.approved === true; } catch { return false; }
  });
  memberSocket.send(JSON.stringify({ type: 'data_share_response', requesterDeviceId: owner.deviceId, category: 'media', requestId: firstShareRequest.requestId, approved: true, minutes: 5 }));
  const [approvedMessage] = await approvedPromise;
  assert.equal(JSON.parse(approvedMessage.toString()).category, 'media');

  const pendingRequest = nextWsMessage(memberSocket, data => {
    try { return JSON.parse(data.toString()).type === 'data_share_request'; } catch { return false; }
  });
  ownerSocket.send(JSON.stringify({ type: 'data_share_request', targetDeviceId: member.deviceId, category: 'media', itemLabel: 'Second photo' }));
  await pendingRequest;

  const revokedPromise = nextWsMessage(ownerSocket, data => {
    try { const msg = JSON.parse(data.toString()); return msg.type === 'data_share_result' && msg.approved === false && msg.reason.includes('disabled'); } catch { return false; }
  });
  memberSocket.send(JSON.stringify({ type: 'permission_update', permissions: { media: false } }));
  const [revokedMessage] = await revokedPromise;
  assert.equal(JSON.parse(revokedMessage.toString()).targetDeviceId, member.deviceId);

  const ownerClosed = once(ownerSocket, 'close');
  const memberClosed = once(memberSocket, 'close');
  ownerSocket.close(); memberSocket.close();
  await Promise.all([ownerClosed, memberClosed]);
  await fetch(`${baseUrl}/api/device/revoke`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${owner.deviceToken}` },
    body: JSON.stringify({ sessionId: owner.sessionId, deviceId: owner.deviceId, targetDeviceId: owner.deviceId })
  });
});



test('small shared files require a one-time grant and end-to-end encrypted envelope', async () => {
  const ownerResponse = await fetch(`${baseUrl}/api/device/session`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'Encrypted Share Sender' })
  });
  const owner = await ownerResponse.json();
  const memberResponse = await fetch(`${baseUrl}/api/device/join`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'Encrypted Share Receiver', pairingCode: owner.pairingCode })
  });
  const member = await memberResponse.json();
  const wsBase = baseUrl.replace('http:', 'ws:');
  const connect = pair => new WebSocket(`${wsBase}/ws?sessionId=${encodeURIComponent(pair.sessionId)}&deviceId=${encodeURIComponent(pair.deviceId)}`, {
    headers: { authorization: `Bearer ${pair.deviceToken}` }
  });
  const ownerSocket = connect(owner);
  const memberSocket = connect(member);
  try {
    await Promise.all([once(ownerSocket, 'open'), once(memberSocket, 'open')]);
    const waitType = (socket, type) => nextWsMessage(socket, data => {
      try { return JSON.parse(data.toString()).type === type; } catch { return false; }
    });
    memberSocket.send(JSON.stringify({ type: 'permission_update', permissions: { media: true } }));
    await new Promise(resolve => setTimeout(resolve, 50));
    const requestPromise = waitType(memberSocket, 'data_share_request');
    ownerSocket.send(JSON.stringify({ type: 'data_share_request', targetDeviceId: member.deviceId, category: 'media', itemLabel: 'Photo attachment' }));
    const [requestMessage] = await requestPromise;
    const requestId = JSON.parse(requestMessage.toString()).requestId;
    const approvalPromise = waitType(ownerSocket, 'data_share_result');
    memberSocket.send(JSON.stringify({ type: 'data_share_response', requesterDeviceId: owner.deviceId, category: 'media', requestId, approved: true, minutes: 5 }));
    const [approval] = await approvalPromise;
    assert.equal(JSON.parse(approval.toString()).approved, true);

    let plaintextForwarded = false;
    const onPlaintext = data => {
      try { if (JSON.parse(data.toString()).type === 'data_share_payload') plaintextForwarded = true; } catch {}
    };
    memberSocket.on('message', onPlaintext);
    ownerSocket.send(JSON.stringify({
      type: 'data_share_payload', targetDeviceId: member.deviceId, category: 'media', requestId,
      fileName: 'photo.jpg', mimeType: 'image/jpeg', contentType: 'file', sizeBytes: 4,
      sha256: 'a'.repeat(64), payload: '/////w=='
    }));
    await new Promise(resolve => setTimeout(resolve, 120));
    memberSocket.off('message', onPlaintext);
    assert.equal(plaintextForwarded, false, 'plaintext attachments must never reach the target');

    const encryptedPromise = waitType(memberSocket, 'e2e_message');
    ownerSocket.send(JSON.stringify({
      type: 'e2e_message', targetDeviceId: member.deviceId, messageKind: 'data_share_payload',
      category: 'media', requestId, nonce: 'A'.repeat(16),
      ciphertext: Buffer.from('opaque encrypted payload').toString('base64')
    }));
    const [encryptedMessage] = await encryptedPromise;
    const forwarded = JSON.parse(encryptedMessage.toString());
    assert.equal(forwarded.messageKind, 'data_share_payload');
    assert.equal(forwarded.category, 'media');
    assert.equal(forwarded.requestId, requestId);

    let replayForwarded = false;
    const onReplay = data => {
      try { if (JSON.parse(data.toString()).type === 'e2e_message') replayForwarded = true; } catch {}
    };
    memberSocket.on('message', onReplay);
    ownerSocket.send(JSON.stringify({
      type: 'e2e_message', targetDeviceId: member.deviceId, messageKind: 'data_share_payload',
      category: 'media', requestId, nonce: 'B'.repeat(16),
      ciphertext: Buffer.from('replayed encrypted payload').toString('base64')
    }));
    await new Promise(resolve => setTimeout(resolve, 120));
    memberSocket.off('message', onReplay);
    assert.equal(replayForwarded, false, 'the one-time share grant must be consumed after one payload');
  } finally {
    const closes = [ownerSocket, memberSocket].map(socket => socket.readyState === WebSocket.CLOSED ? Promise.resolve() : new Promise(resolve => { socket.once('close', resolve); socket.close(); }));
    await Promise.all(closes);
  }
});

test('target device can revoke active remote-control grants immediately', async () => {
  const ownerResponse = await fetch(`${baseUrl}/api/device/session`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'Control Requester' })
  });
  const owner = await ownerResponse.json();
  const memberResponse = await fetch(`${baseUrl}/api/device/join`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'Control Target', pairingCode: owner.pairingCode })
  });
  const member = await memberResponse.json();
  const wsBase = baseUrl.replace('http:', 'ws:');
  const connect = (pair) => new WebSocket(`${wsBase}/ws?sessionId=${encodeURIComponent(pair.sessionId)}&deviceId=${encodeURIComponent(pair.deviceId)}`, {
    headers: { authorization: `Bearer ${pair.deviceToken}` }
  });
  const ownerSocket = connect(owner);
  const ownerConnected = nextWsMessage(ownerSocket, data => {
    try { return JSON.parse(data.toString()).type === 'connected'; } catch { return false; }
  });
  await once(ownerSocket, 'open');
  await ownerConnected;
  const memberSocket = connect(member);
  const memberConnected = nextWsMessage(memberSocket, data => {
    try { return JSON.parse(data.toString()).type === 'connected'; } catch { return false; }
  });
  await once(memberSocket, 'open');
  await memberConnected;

  memberSocket.send(JSON.stringify({ type: 'permission_update', permissions: { remote_control: true } }));
  const requestPromise = nextWsMessage(memberSocket, data => {
    try { return JSON.parse(data.toString()).type === 'control_request'; } catch { return false; }
  });
  ownerSocket.send(JSON.stringify({ type: 'control_request', targetDeviceId: member.deviceId, minutes: 5 }));
  const [requestMessage] = await requestPromise;
  assert.equal(JSON.parse(requestMessage.toString()).fromDeviceId, owner.deviceId);

  const approvedPromise = nextWsMessage(ownerSocket, data => {
    try { const msg = JSON.parse(data.toString()); return msg.type === 'control_result' && msg.approved === true; } catch { return false; }
  });
  memberSocket.send(JSON.stringify({ type: 'control_response', requesterDeviceId: owner.deviceId, approved: true, minutes: 5 }));
  const [approvedMessage] = await approvedPromise;
  assert.equal(JSON.parse(approvedMessage.toString()).targetDeviceId, member.deviceId);

  const revokedPromise = nextWsMessage(ownerSocket, data => {
    try { const msg = JSON.parse(data.toString()); return msg.type === 'control_result' && msg.approved === false && msg.reason.includes('revoked'); } catch { return false; }
  });
  memberSocket.send(JSON.stringify({ type: 'control_revoke' }));
  const [revokedMessage] = await revokedPromise;
  assert.equal(JSON.parse(revokedMessage.toString()).targetDeviceId, member.deviceId);

  const secondRequest = nextWsMessage(memberSocket, data => {
    try { return JSON.parse(data.toString()).type === 'control_request'; } catch { return false; }
  });
  ownerSocket.send(JSON.stringify({ type: 'control_request', targetDeviceId: member.deviceId, minutes: 5 }));
  await secondRequest;
  const secondApproval = nextWsMessage(ownerSocket, data => {
    try { const msg = JSON.parse(data.toString()); return msg.type === 'control_result' && msg.approved === true; } catch { return false; }
  });
  memberSocket.send(JSON.stringify({ type: 'control_response', requesterDeviceId: owner.deviceId, approved: true, minutes: 5 }));
  await secondApproval;
  const disabledPromise = nextWsMessage(ownerSocket, data => {
    try { const msg = JSON.parse(data.toString()); return msg.type === 'control_result' && msg.approved === false && msg.reason.includes('disabled'); } catch { return false; }
  });
  memberSocket.send(JSON.stringify({ type: 'permission_update', permissions: { remote_control: false } }));
  const [disabledMessage] = await disabledPromise;
  assert.equal(JSON.parse(disabledMessage.toString()).targetDeviceId, member.deviceId);

  const ownerClosed = once(ownerSocket, 'close');
  const memberClosed = once(memberSocket, 'close');
  ownerSocket.close();
  memberSocket.close();
  await Promise.all([ownerClosed, memberClosed]);
  await fetch(`${baseUrl}/api/device/revoke`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${owner.deviceToken}` },
    body: JSON.stringify({ sessionId: owner.sessionId, deviceId: owner.deviceId, targetDeviceId: owner.deviceId })
  });
});

test('connected WebSocket event reports owner/member roles and restores owner role after reconnect', async () => {
  const ownerResponse = await fetch(`${baseUrl}/api/device/session`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'Role Owner' })
  });
  const owner = await ownerResponse.json();
  const wsBase = baseUrl.replace('http:', 'ws:');
  const ownerSocket = new WebSocket(`${wsBase}/ws?sessionId=${encodeURIComponent(owner.sessionId)}&deviceId=${encodeURIComponent(owner.deviceId)}`, {
    headers: { authorization: `Bearer ${owner.deviceToken}` }
  });
  const ownerConnected = nextWsMessage(ownerSocket, data => {
    try { return JSON.parse(data.toString()).type === 'connected'; } catch { return false; }
  });
  await once(ownerSocket, 'open');
  const [ownerMessage] = await ownerConnected;
  assert.equal(JSON.parse(ownerMessage.toString()).role, 'owner');
  const ownerInitialClosed = once(ownerSocket, 'close');
  ownerSocket.close();
  await ownerInitialClosed;

  const ownerReconnect = new WebSocket(`${wsBase}/ws?sessionId=${encodeURIComponent(owner.sessionId)}&deviceId=${encodeURIComponent(owner.deviceId)}`, {
    headers: { authorization: `Bearer ${owner.deviceToken}` }
  });
  const ownerReconnected = nextWsMessage(ownerReconnect, data => {
    try { return JSON.parse(data.toString()).type === 'connected'; } catch { return false; }
  });
  await once(ownerReconnect, 'open');
  const [reconnectedOwnerMessage] = await ownerReconnected;
  assert.equal(JSON.parse(reconnectedOwnerMessage.toString()).role, 'owner');

  const memberResponse = await fetch(`${baseUrl}/api/device/join`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'Role Member', pairingCode: owner.pairingCode })
  });
  const member = await memberResponse.json();
  const memberSocket = new WebSocket(`${wsBase}/ws?sessionId=${encodeURIComponent(member.sessionId)}&deviceId=${encodeURIComponent(member.deviceId)}`, {
    headers: { authorization: `Bearer ${member.deviceToken}` }
  });
  const memberConnected = nextWsMessage(memberSocket, data => {
    try { return JSON.parse(data.toString()).type === 'connected'; } catch { return false; }
  });
  await once(memberSocket, 'open');
  const [memberMessage] = await memberConnected;
  assert.equal(JSON.parse(memberMessage.toString()).role, 'member');

  const ownerClosed = once(ownerReconnect, 'close');
  const memberClosed = once(memberSocket, 'close');
  ownerReconnect.close();
  memberSocket.close();
  await Promise.all([ownerClosed, memberClosed]);
  await fetch(`${baseUrl}/api/device/revoke`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${owner.deviceToken}` },
    body: JSON.stringify({ sessionId: owner.sessionId, deviceId: owner.deviceId, targetDeviceId: owner.deviceId })
  });
});

test('device revocation requires authentication and owner authority, and removes the whole owner session', async () => {
  const ownerResponse = await fetch(`${baseUrl}/api/device/session`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'Revoke Owner' })
  });
  const owner = await ownerResponse.json();
  assert.equal(ownerResponse.status, 201);

  const memberResponse = await fetch(`${baseUrl}/api/device/join`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'Revoke Member', pairingCode: owner.pairingCode })
  });
  const member = await memberResponse.json();
  assert.equal(memberResponse.status, 200);

  const endpoint = `${baseUrl}/api/device/revoke`;
  const payload = (deviceId, targetDeviceId) => ({
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ sessionId: owner.sessionId, deviceId, targetDeviceId })
  });
  const memberSocket = new WebSocket(
    `${baseUrl.replace(/^http/, 'ws')}/ws?sessionId=${encodeURIComponent(member.sessionId)}&deviceId=${encodeURIComponent(member.deviceId)}`,
    { headers: { authorization: `Bearer ${member.deviceToken}` } }
  );
  await once(memberSocket, 'open');
  const memberSocketClosed = once(memberSocket, 'close');
  const ownerSocket = new WebSocket(
    `${baseUrl.replace(/^http/, 'ws')}/ws?sessionId=${encodeURIComponent(owner.sessionId)}&deviceId=${encodeURIComponent(owner.deviceId)}`,
    { headers: { authorization: `Bearer ${owner.deviceToken}` } }
  );
  await once(ownerSocket, 'open');
  const ownerSocketClosed = once(ownerSocket, 'close');
  const unauthenticated = await fetch(endpoint, payload(member.deviceId, owner.deviceId));
  assert.equal(unauthenticated.status, 401);

  const memberCannotRevokeOwner = await fetch(endpoint, {
    ...payload(member.deviceId, owner.deviceId), headers: {
      'content-type': 'application/json', authorization: `Bearer ${member.deviceToken}`
    }
  });
  assert.equal(memberCannotRevokeOwner.status, 403);

  const invalidTarget = await fetch(endpoint, {
    ...payload(owner.deviceId, 'not-a-device-id'), headers: {
      'content-type': 'application/json', authorization: `Bearer ${owner.deviceToken}`
    }
  });
  assert.equal(invalidTarget.status, 400);

  const ownerRevokesMember = await fetch(endpoint, {
    ...payload(owner.deviceId, member.deviceId), headers: {
      'content-type': 'application/json', authorization: `Bearer ${owner.deviceToken}`
    }
  });
  assert.equal(ownerRevokesMember.status, 200);
  assert.deepEqual(await ownerRevokesMember.json(), { ok: true, revoked: member.deviceId });
  const revokedMemberCannotRevoke = await fetch(endpoint, {
    ...payload(member.deviceId, member.deviceId), headers: {
      'content-type': 'application/json', authorization: `Bearer ${member.deviceToken}`
    }
  });
  assert.equal(revokedMemberCannotRevoke.status, 401);
  const [memberCloseCode] = await memberSocketClosed;
  assert.equal(memberCloseCode, 4003, 'revoking a paired device must close its active relay socket');

  const ownerRevokesSession = await fetch(endpoint, {
    ...payload(owner.deviceId, owner.deviceId), headers: {
      'content-type': 'application/json', authorization: `Bearer ${owner.deviceToken}`
    }
  });
  assert.equal(ownerRevokesSession.status, 200);
  assert.deepEqual(await ownerRevokesSession.json(), { ok: true, revoked: 'session' });
  const [ownerCloseCode] = await ownerSocketClosed;
  assert.equal(ownerCloseCode, 4003, 'owner session revocation must close the owner socket');
});

test('device revocation validates identifiers and lets a member revoke only its own pairing', async () => {
  const invalid = await fetch(`${baseUrl}/api/device/revoke`, {
    method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer invalid' },
    body: JSON.stringify({ sessionId: 'bad', deviceId: 'bad', targetDeviceId: 'bad' })
  });
  assert.equal(invalid.status, 400);

  const ownerResponse = await fetch(`${baseUrl}/api/device/session`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'Self Revoke Owner' })
  });
  const owner = await ownerResponse.json();
  const memberResponse = await fetch(`${baseUrl}/api/device/join`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'Self Revoke Member', pairingCode: owner.pairingCode })
  });
  const member = await memberResponse.json();
  const response = await fetch(`${baseUrl}/api/device/revoke`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${member.deviceToken}` },
    body: JSON.stringify({ sessionId: member.sessionId, deviceId: member.deviceId, targetDeviceId: member.deviceId })
  });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ok: true, revoked: member.deviceId });

  const reuse = await fetch(`${baseUrl}/api/device/revoke`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${member.deviceToken}` },
    body: JSON.stringify({ sessionId: member.sessionId, deviceId: member.deviceId, targetDeviceId: member.deviceId })
  });
  assert.equal(reuse.status, 401);
});

test('end-to-end remote actions require a live control grant and relay opaque ciphertext', async () => {
  const ownerResponse = await fetch(`${baseUrl}/api/device/session`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'E2E Action Owner' })
  });
  const owner = await ownerResponse.json();
  const memberResponse = await fetch(`${baseUrl}/api/device/join`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ deviceName: 'E2E Action Target', pairingCode: owner.pairingCode })
  });
  const member = await memberResponse.json();
  const wsBase = baseUrl.replace(/^http/, 'ws');
  const connect = pair => new WebSocket(`${wsBase}/ws?sessionId=${encodeURIComponent(pair.sessionId)}&deviceId=${encodeURIComponent(pair.deviceId)}`, {
    headers: { authorization: `Bearer ${pair.deviceToken}` }
  });
  const ownerSocket = connect(owner);
  const memberSocket = connect(member);
  const waitType = (socket, type) => nextWsMessage(socket, data => {
    try { return JSON.parse(data.toString()).type === type; } catch { return false; }
  });
  try {
    await Promise.all([once(ownerSocket, 'open'), once(memberSocket, 'open')]);
    const permission = waitType(ownerSocket, 'permission_status');
    memberSocket.send(JSON.stringify({ type: 'permission_update', permissions: { remote_control: true } }));
    await permission;

    const controlRequest = waitType(memberSocket, 'control_request');
    ownerSocket.send(JSON.stringify({ type: 'control_request', targetDeviceId: member.deviceId, minutes: 5 }));
    await controlRequest;
    const controlResult = waitType(ownerSocket, 'control_result');
    memberSocket.send(JSON.stringify({ type: 'control_response', requesterDeviceId: owner.deviceId, approved: true, minutes: 5 }));
    const [controlMessage] = await controlResult;
    assert.equal(JSON.parse(controlMessage.toString()).approved, true);

    const actionId = 'action_123456789';
    const nonce = Buffer.alloc(12, 1).toString('base64').replace(/=+$/, '');
    const ciphertext = Buffer.alloc(16, 2).toString('base64').replace(/=+$/, '');
    const actionPromise = waitType(memberSocket, 'e2e_message');
    ownerSocket.send(JSON.stringify({
      type: 'e2e_message', targetDeviceId: member.deviceId, messageKind: 'remote_action',
      actionId, actionKind: 'tap', nonce, ciphertext
    }));
    const [actionMessage] = await actionPromise;
    const forwardedAction = JSON.parse(actionMessage.toString());
    assert.equal(forwardedAction.fromDeviceId, owner.deviceId);
    assert.equal(forwardedAction.actionId, actionId);
    assert.equal(forwardedAction.ciphertext, ciphertext);

    const resultPromise = waitType(ownerSocket, 'e2e_message');
    memberSocket.send(JSON.stringify({
      type: 'e2e_message', targetDeviceId: owner.deviceId, messageKind: 'remote_action_result',
      actionId, actionKind: 'tap', nonce, ciphertext
    }));
    const [resultMessage] = await resultPromise;
    assert.equal(JSON.parse(resultMessage.toString()).fromDeviceId, member.deviceId);
  } finally {
    const closes = [ownerSocket, memberSocket].map(socket => socket.readyState === WebSocket.CLOSED ? Promise.resolve() : new Promise(resolve => { socket.once('close', resolve); socket.close(); }));
    await Promise.all(closes);
  }
});

test('switching to master-only immediately revokes old member grants and blocks encrypted actions', async () => {
  const ownerResponse = await fetch(`${baseUrl}/api/device/session`, {
    method: 'POST', headers: { 'content-type': 'application/json', 'x-forwarded-for': '203.0.113.201' },
    body: JSON.stringify({ deviceName: 'Master Mode Owner' })
  });
  const owner = await ownerResponse.json();
  const memberResponse = await fetch(`${baseUrl}/api/device/join`, {
    method: 'POST', headers: { 'content-type': 'application/json', 'x-forwarded-for': '203.0.113.202' },
    body: JSON.stringify({ deviceName: 'Mutual Mode Member', pairingCode: owner.pairingCode })
  });
  const member = await memberResponse.json();
  const wsBase = baseUrl.replace(/^http/, 'ws');
  const connect = pair => new WebSocket(`${wsBase}/ws?sessionId=${encodeURIComponent(pair.sessionId)}&deviceId=${encodeURIComponent(pair.deviceId)}`, {
    headers: { authorization: `Bearer ${pair.deviceToken}` }
  });
  const ownerSocket = connect(owner);
  const memberSocket = connect(member);
  const waitType = (socket, type, predicate = () => true, timeoutMs = 5_000) =>
    nextWsMessage(socket, data => {
      try { const message = JSON.parse(data.toString()); return message.type === type && predicate(message); }
      catch { return false; }
    }, timeoutMs);
  try {
    await Promise.all([once(ownerSocket, 'open'), once(memberSocket, 'open')]);
    const permission = waitType(memberSocket, 'permission_status');
    ownerSocket.send(JSON.stringify({ type: 'permission_update', permissions: { remote_control: true } }));
    await permission;

    const request = waitType(ownerSocket, 'control_request');
    memberSocket.send(JSON.stringify({ type: 'control_request', targetDeviceId: owner.deviceId, minutes: 5 }));
    await request;
    const approval = waitType(memberSocket, 'control_result', message => message.approved === true);
    ownerSocket.send(JSON.stringify({ type: 'control_response', requesterDeviceId: member.deviceId, approved: true, minutes: 5 }));
    await approval;

    const revoked = waitType(memberSocket, 'control_result', message =>
      message.approved === false && message.reason.includes('owner-only'));
    ownerSocket.send(JSON.stringify({ type: 'set_control_mode', mode: 'master' }));
    await revoked;

    const unexpectedAction = waitType(ownerSocket, 'e2e_message', () => true, 300);
    memberSocket.send(JSON.stringify({
      type: 'e2e_message', targetDeviceId: owner.deviceId, messageKind: 'remote_action',
      actionId: 'stale_grant_action_01', actionKind: 'tap',
      nonce: Buffer.alloc(12, 7).toString('base64').replace(/=+$/, ''),
      ciphertext: Buffer.alloc(16, 8).toString('base64').replace(/=+$/, '')
    }));
    await assert.rejects(unexpectedAction, /Timed out waiting/);
  } finally {
    const closes = [ownerSocket, memberSocket].map(socket => {
      if (socket.readyState === WebSocket.CLOSED) return Promise.resolve();
      if (socket.readyState === WebSocket.CONNECTING) {
        socket.terminate();
        return Promise.resolve();
      }
      return new Promise(resolve => { socket.once('close', resolve); socket.close(); });
    });
    await Promise.all(closes);
  }
});

test('task handoffs and responses are relayed only as E2E ciphertext', async () => {
  const ownerResponse = await fetch(`${baseUrl}/api/device/session`, {
    method: 'POST', headers: { 'content-type': 'application/json', 'x-forwarded-for': '203.0.113.211' },
    body: JSON.stringify({ deviceName: 'Task Sender' })
  });
  const owner = await ownerResponse.json();
  const memberResponse = await fetch(`${baseUrl}/api/device/join`, {
    method: 'POST', headers: { 'content-type': 'application/json', 'x-forwarded-for': '203.0.113.212' },
    body: JSON.stringify({ deviceName: 'Task Receiver', pairingCode: owner.pairingCode })
  });
  const member = await memberResponse.json();
  const wsBase = baseUrl.replace(/^http/, 'ws');
  const connect = pair => new WebSocket(`${wsBase}/ws?sessionId=${encodeURIComponent(pair.sessionId)}&deviceId=${encodeURIComponent(pair.deviceId)}`, {
    headers: { authorization: `Bearer ${pair.deviceToken}` }
  });
  const ownerSocket = connect(owner), memberSocket = connect(member);
  const waitEncrypted = (socket, kind) => nextWsMessage(socket, data => {
    try { const msg = JSON.parse(data.toString()); return msg.type === 'e2e_message' && msg.messageKind === kind; }
    catch { return false; }
  });
  try {
    await Promise.all([once(ownerSocket, 'open'), once(memberSocket, 'open')]);
    const taskId = 'task_handoff_123456';
    const title = 'Private plan title';
    const ciphertext = Buffer.alloc(32, 9).toString('base64').replace(/=+$/, '');
    const offerPromise = waitEncrypted(memberSocket, 'task_handoff');
    ownerSocket.send(JSON.stringify({
      type: 'e2e_message', targetDeviceId: member.deviceId, messageKind: 'task_handoff',
      requestId: taskId, nonce: Buffer.alloc(12, 9).toString('base64').replace(/=+$/, ''), ciphertext
    }));
    const [offer] = await offerPromise;
    const forwardedOffer = JSON.parse(offer.toString());
    assert.equal(forwardedOffer.requestId, taskId);
    assert.equal(forwardedOffer.fromDeviceName, 'Task Sender');
    assert.equal(forwardedOffer.ciphertext, ciphertext);
    assert.equal(JSON.stringify(forwardedOffer).includes(title), false);

    const responsePromise = waitEncrypted(ownerSocket, 'task_handoff_response');
    memberSocket.send(JSON.stringify({
      type: 'e2e_message', targetDeviceId: owner.deviceId, messageKind: 'task_handoff_response',
      requestId: taskId, nonce: Buffer.alloc(12, 10).toString('base64').replace(/=+$/, ''), ciphertext
    }));
    const [response] = await responsePromise;
    assert.equal(JSON.parse(response.toString()).requestId, taskId);

    const noPlaintext = waitEncrypted(memberSocket, 'task_handoff');
    ownerSocket.send(JSON.stringify({ type: 'task_handoff', targetDeviceId: member.deviceId, task: { title } }));
    await assert.rejects(noPlaintext, /Timed out waiting/);
  } finally {
    const closes = [ownerSocket, memberSocket].map(socket => {
      if (socket.readyState === WebSocket.CLOSED) return Promise.resolve();
      if (socket.readyState === WebSocket.CONNECTING) {
        socket.terminate();
        return Promise.resolve();
      }
      return new Promise(resolve => { socket.once('close', resolve); socket.close(); });
    });
    await Promise.all(closes);
  }
});

test('task handoffs and responses are relayed only as E2E ciphertext', async () => {
  const ownerResponse = await fetch(`${baseUrl}/api/device/session`, {
    method: 'POST', headers: { 'content-type': 'application/json', 'x-forwarded-for': '203.0.113.211' },
    body: JSON.stringify({ deviceName: 'Task Sender' })
  });
  const owner = await ownerResponse.json();
  const memberResponse = await fetch(`${baseUrl}/api/device/join`, {
    method: 'POST', headers: { 'content-type': 'application/json', 'x-forwarded-for': '203.0.113.212' },
    body: JSON.stringify({ deviceName: 'Task Receiver', pairingCode: owner.pairingCode })
  });
  const member = await memberResponse.json();
  const wsBase = baseUrl.replace(/^http/, 'ws');
  const connect = pair => new WebSocket(`${wsBase}/ws?sessionId=${encodeURIComponent(pair.sessionId)}&deviceId=${encodeURIComponent(pair.deviceId)}`, {
    headers: { authorization: `Bearer ${pair.deviceToken}` }
  });
  const ownerSocket = connect(owner), memberSocket = connect(member);
  const waitEncrypted = (socket, kind) => nextWsMessage(socket, data => {
    try { const msg = JSON.parse(data.toString()); return msg.type === 'e2e_message' && msg.messageKind === kind; }
    catch { return false; }
  });
  try {
    await Promise.all([once(ownerSocket, 'open'), once(memberSocket, 'open')]);
    const taskId = 'task_handoff_123456';
    const title = 'Private plan title';
    const ciphertext = Buffer.alloc(32, 9).toString('base64').replace(/=+$/, '');
    const offerPromise = waitEncrypted(memberSocket, 'task_handoff');
    ownerSocket.send(JSON.stringify({
      type: 'e2e_message', targetDeviceId: member.deviceId, messageKind: 'task_handoff',
      requestId: taskId, nonce: Buffer.alloc(12, 9).toString('base64').replace(/=+$/, ''), ciphertext
    }));
    const [offer] = await offerPromise;
    const forwardedOffer = JSON.parse(offer.toString());
    assert.equal(forwardedOffer.requestId, taskId);
    assert.equal(forwardedOffer.fromDeviceName, 'Task Sender');
    assert.equal(forwardedOffer.ciphertext, ciphertext);
    assert.equal(JSON.stringify(forwardedOffer).includes(title), false);

    const responsePromise = waitEncrypted(ownerSocket, 'task_handoff_response');
    memberSocket.send(JSON.stringify({
      type: 'e2e_message', targetDeviceId: owner.deviceId, messageKind: 'task_handoff_response',
      requestId: taskId, nonce: Buffer.alloc(12, 10).toString('base64').replace(/=+$/, ''), ciphertext
    }));
    const [response] = await responsePromise;
    assert.equal(JSON.parse(response.toString()).requestId, taskId);

    const noPlaintext = waitEncrypted(memberSocket, 'task_handoff');
    ownerSocket.send(JSON.stringify({ type: 'task_handoff', targetDeviceId: member.deviceId, task: { title } }));
    await assert.rejects(noPlaintext, /Timed out waiting/);
  } finally {
    await Promise.all([closeWebSocket(ownerSocket), closeWebSocket(memberSocket)]);
  }
});

test('screen frames are blocked until the sending device explicitly enables screen sharing', async () => {
  const ownerResponse = await fetch(`${baseUrl}/api/device/session`, {
    method: 'POST', headers: { 'content-type': 'application/json', 'x-forwarded-for': '203.0.113.81' },
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

    const encryptedFrame = (type, targetId, sequence, fill = 0x5a) => {
      const target = Buffer.from(targetId, 'utf8');
      const payload = Buffer.alloc(8 + 12 + 16, fill); // sequence + nonce + GCM tag/ciphertext
      payload.writeBigUInt64BE(BigInt(sequence), 0);
      return Buffer.concat([Buffer.from([type, target.length]), target, payload]);
    };
    const firstFrame = nextWsMessage(memberSocket, (_data, isBinary) => isBinary);
    ownerSocket.send(JSON.stringify({ type: 'permission_update', permissions: { screen: false, camera: false } }));
    await waitForPermission(false);
    // Legacy plaintext JPEG frames are rejected even when syntactically valid.
    ownerSocket.send(Buffer.concat([Buffer.from([1, 0]), Buffer.from([0xff, 0xd8, 0x00, 0x11])]));

    ownerSocket.send(JSON.stringify({ type: 'permission_update', permissions: { screen: true, camera: false } }));
    await waitForPermission(true);
    // Encrypted frames are bounded and must address one verified peer.
    ownerSocket.send(encryptedFrame(0x81, member.deviceId, 1).subarray(0, 2 + member.deviceId.length + 35));
    ownerSocket.send(Buffer.concat([Buffer.from([3, 0]), Buffer.alloc(40)]));
    ownerSocket.send(Buffer.concat([Buffer.from([0x81, 65]), Buffer.alloc(65, 0x61), Buffer.alloc(40)]));
    ownerSocket.send(encryptedFrame(0x81, member.deviceId, 2).subarray(0, 2 + member.deviceId.length + 35));
    ownerSocket.send(Buffer.concat([
      Buffer.from([0x81, Buffer.byteLength(member.deviceId)]),
      Buffer.from(member.deviceId), Buffer.alloc(2_500_037)
    ])); // Reject an oversized encrypted envelope before relaying it.
    const allowedOpaque = encryptedFrame(0x81, member.deviceId, 3, 0x7a).subarray(2 + member.deviceId.length);
    ownerSocket.send(encryptedFrame(0x81, member.deviceId, 3, 0x7a));

    const [frameData, isBinary] = await firstFrame;
    assert.equal(isBinary, true);
    const frame = Buffer.from(frameData);
    const sourceLength = frame[0];
    assert.equal(frame.subarray(1, 1 + sourceLength).toString('utf8'), owner.deviceId);
    assert.equal(frame[1 + sourceLength], 0x81);
    assert.deepEqual(frame.subarray(2 + sourceLength), allowedOpaque,
      'the relay must forward opaque ciphertext unchanged only after screen permission is enabled');

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
    const allowedCameraOpaque = encryptedFrame(0x82, member.deviceId, 4, 0x66).subarray(2 + member.deviceId.length);
    ownerSocket.send(encryptedFrame(0x82, member.deviceId, 4, 0x66));
    const [cameraData, cameraBinary] = await firstCameraFrame;
    assert.equal(cameraBinary, true);
    const cameraFrame = Buffer.from(cameraData);
    const cameraSourceLength = cameraFrame[0];
    assert.equal(cameraFrame[cameraSourceLength + 1], 0x82);
    assert.deepEqual(cameraFrame.subarray(cameraSourceLength + 2), allowedCameraOpaque,
      'camera ciphertext is relayed only when camera permission is enabled');
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


test('file transfer resume uses only the receiver persisted missing-index bitmap', async () => {
  const ownerResponse = await fetch(`${baseUrl}/api/device/session`, {
    method: 'POST', headers: { 'content-type': 'application/json', 'x-forwarded-for': '203.0.113.221' },
    body: JSON.stringify({ deviceName: 'Transfer Sender' })
  });
  const owner = await ownerResponse.json();
  const memberResponse = await fetch(`${baseUrl}/api/device/join`, {
    method: 'POST', headers: { 'content-type': 'application/json', 'x-forwarded-for': '203.0.113.222' },
    body: JSON.stringify({ deviceName: 'Transfer Receiver', pairingCode: owner.pairingCode })
  });
  const member = await memberResponse.json();
  const wsBase = baseUrl.replace(/^http/, 'ws');
  const connect = pair => new WebSocket(`${wsBase}/ws?sessionId=${encodeURIComponent(pair.sessionId)}&deviceId=${encodeURIComponent(pair.deviceId)}`, {
    headers: { authorization: `Bearer ${pair.deviceToken}` }
  });
  const ownerSocket = connect(owner), memberSocket = connect(member);
  try {
    const ownerConnected = nextWsMessage(ownerSocket, data => {
      try { return JSON.parse(data.toString()).type === 'connected'; } catch { return false; }
    });
    const memberConnected = nextWsMessage(memberSocket, data => {
      try { return JSON.parse(data.toString()).type === 'connected'; } catch { return false; }
    });
    await Promise.all([waitForWebSocketOpen(ownerSocket), waitForWebSocketOpen(memberSocket)]);
    await Promise.all([ownerConnected, memberConnected]);

    memberSocket.send(JSON.stringify({ type: 'permission_update', permissions: { documents: true } }));
    const shareRequestPromise = nextWsMessage(memberSocket, data => {
      try { return JSON.parse(data.toString()).type === 'data_share_request'; } catch { return false; }
    });
    ownerSocket.send(JSON.stringify({
      type: 'data_share_request', targetDeviceId: member.deviceId,
      category: 'documents', itemLabel: 'resume-test.bin'
    }));
    const [shareRequestMessage] = await shareRequestPromise;
    const shareRequest = JSON.parse(shareRequestMessage.toString());

    const approvedPromise = nextWsMessage(ownerSocket, data => {
      try { const msg = JSON.parse(data.toString()); return msg.type === 'data_share_result' && msg.approved === true; } catch { return false; }
    });
    memberSocket.send(JSON.stringify({
      type: 'data_share_response', requesterDeviceId: owner.deviceId,
      category: 'documents', requestId: shareRequest.requestId, approved: true, minutes: 5
    }));
    await approvedPromise;

    const transferId = 'resume_test_12345';
    const startedPromise = nextWsMessage(memberSocket, data => {
      try { return JSON.parse(data.toString()).type === 'file_transfer_start'; } catch { return false; }
    });
    ownerSocket.send(JSON.stringify({
      type: 'file_transfer_start', targetDeviceId: member.deviceId,
      requestId: shareRequest.requestId, transferId, fileName: 'resume-test.bin',
      sizeBytes: 100000, chunkCount: 3, sha256: 'a'.repeat(64)
    }));
    const [startedMessage] = await startedPromise;
    assert.equal(JSON.parse(startedMessage.toString()).transferId, transferId);

    const resumePromise = nextWsMessage(ownerSocket, data => {
      try { return JSON.parse(data.toString()).type === 'file_transfer_resume'; } catch { return false; }
    });
    memberSocket.send(JSON.stringify({
      type: 'file_transfer_resume', targetDeviceId: owner.deviceId,
      transferId, chunkCount: 3, missingIndexes: [1, 2]
    }));
    const [resumeMessage] = await resumePromise;
    const resume = JSON.parse(resumeMessage.toString());
    assert.equal(resume.fromDeviceId, member.deviceId);
    assert.equal(resume.transferId, transferId);
    assert.equal(resume.chunkCount, 3);
    assert.deepEqual(resume.missingIndexes, [1, 2]);

    const prematureCompletionPromise = nextWsMessage(ownerSocket, data => {
      try { const msg = JSON.parse(data.toString()); return msg.type === 'file_transfer_ack' && msg.complete === true; } catch { return false; }
    });
    memberSocket.send(JSON.stringify({
      type: 'file_transfer_ack', targetDeviceId: owner.deviceId,
      transferId, index: -1, complete: true, ok: true
    }));
    const [prematureCompletionMessage] = await prematureCompletionPromise;
    assert.equal(JSON.parse(prematureCompletionMessage.toString()).ok, false);

    for (const index of [0, 1, 2]) {
      const chunkAckPromise = nextWsMessage(ownerSocket, data => {
        try { const msg = JSON.parse(data.toString()); return msg.type === 'file_transfer_ack' && msg.index === index && msg.complete === false; } catch { return false; }
      });
      memberSocket.send(JSON.stringify({
        type: 'file_transfer_ack', targetDeviceId: owner.deviceId,
        transferId, index, complete: false, ok: true
      }));
      const [chunkAckMessage] = await chunkAckPromise;
      assert.equal(JSON.parse(chunkAckMessage.toString()).ok, true);
    }

    const verifiedCompletionPromise = nextWsMessage(ownerSocket, data => {
      try { const msg = JSON.parse(data.toString()); return msg.type === 'file_transfer_ack' && msg.complete === true; } catch { return false; }
    });
    memberSocket.send(JSON.stringify({
      type: 'file_transfer_ack', targetDeviceId: owner.deviceId,
      transferId, index: -1, complete: true, ok: true
    }));
    const [verifiedCompletionMessage] = await verifiedCompletionPromise;
    assert.equal(JSON.parse(verifiedCompletionMessage.toString()).ok, true);

    const duplicateCompletion = assert.rejects(nextWsMessage(ownerSocket, () => true, 150), /Timed out/);
    memberSocket.send(JSON.stringify({
      type: 'file_transfer_ack', targetDeviceId: owner.deviceId,
      transferId, index: -1, complete: true, ok: true
    }));
    await duplicateCompletion;

    for (const invalid of [
      { type: 'file_transfer_resume', targetDeviceId: owner.deviceId, transferId, chunkCount: 3, missingIndexes: [1, 1] },
      { type: 'file_transfer_resume', targetDeviceId: owner.deviceId, transferId, chunkCount: 3, missingIndexes: [3] },
      { type: 'file_transfer_resume', targetDeviceId: owner.deviceId, transferId, chunkCount: 3, missingIndexes: [1.5] },
      { type: 'file_transfer_resume', targetDeviceId: owner.deviceId, transferId, chunkCount: 2, missingIndexes: [1] },
      { type: 'file_transfer_resume', targetDeviceId: owner.deviceId, transferId, chunkCount: '3', missingIndexes: [1] },
      { type: 'file_transfer_resume', targetDeviceId: owner.deviceId, transferId: 'invalid transfer id', chunkCount: 3, missingIndexes: [1] },
      { type: 'file_transfer_resume', targetDeviceId: 'unknown-device', transferId, chunkCount: 3, missingIndexes: [1] },
      { type: 'file_transfer_resume', targetDeviceId: owner.deviceId, transferId, chunkCount: 3 }
    ]) {
      const noForward = assert.rejects(nextWsMessage(ownerSocket, () => true, 150), /Timed out/);
      memberSocket.send(JSON.stringify(invalid));
      await noForward;
    }
  } finally {
    await Promise.all([closeWebSocket(ownerSocket), closeWebSocket(memberSocket)]);
  }
});
