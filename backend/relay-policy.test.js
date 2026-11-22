import test from 'node:test';
import assert from 'node:assert/strict';
import { canShareVisualFrame, consumePairingCode, resolveControlApproval, validFileTransferManifest, validFileTransferChunk, validRemoteActionEnvelope, revokePairedDevice, ALLOWED_REMOTE_ACTION_KINDS } from './relay-policy.js';

test('screen frames require explicit screen permission', () => {
  assert.equal(canShareVisualFrame({}, 1), false);
  assert.equal(canShareVisualFrame({ screen: false, camera: true }, 1), false);
  assert.equal(canShareVisualFrame({ screen: true }, 1), true);
});

test('camera frames require explicit camera permission', () => {
  assert.equal(canShareVisualFrame({}, 2), false);
  assert.equal(canShareVisualFrame({ camera: false, screen: true }, 2), false);
  assert.equal(canShareVisualFrame({ camera: true }, 2), true);
});

test('unknown frame types are always rejected', () => {
  assert.equal(canShareVisualFrame({ screen: true, camera: true }, 0), false);
  assert.equal(canShareVisualFrame({ screen: true, camera: true }, 3), false);
  assert.equal(canShareVisualFrame(null, 2), false);
});



test('pairing codes are single-use and expire closed', () => {
  const pairs = new Map([['123456', { sessionId: 's1', expiresAt: 200 }]]);
  assert.deepEqual(consumePairingCode(pairs, '123456', 100), { sessionId: 's1', expiresAt: 200 });
  assert.equal(pairs.has('123456'), false);
  assert.equal(consumePairingCode(pairs, '123456', 100), null);
  pairs.set('654321', { sessionId: 's2', expiresAt: 100 });
  assert.equal(consumePairingCode(pairs, '654321', 100), null);
  assert.equal(pairs.has('654321'), false);
  assert.equal(consumePairingCode(pairs, 'bad', 0), null);
});

test('control approval requires an unexpired request from the same requester', () => {
  const pending = new Map([['device-A', { expiresAt: 200 }]]);
  assert.equal(resolveControlApproval(pending, 'device-B', true, 100), false);
  assert.equal(pending.has('device-A'), true);
  assert.equal(resolveControlApproval(pending, 'device-A', true, 100), true);
  assert.equal(resolveControlApproval(pending, 'device-A', true, 100), false);
  pending.set('device-C', { expiresAt: 100 });
  assert.equal(resolveControlApproval(pending, 'device-C', true, 100), false);
  pending.set('device-D', { expiresAt: 200 });
  assert.equal(resolveControlApproval(pending, 'device-D', false, 100), false);
});

test('large file manifests are bounded and chunk counts must match', () => {
  const digest = 'a'.repeat(64), id = 'transfer_12345678';
  assert.equal(validFileTransferManifest(100000, 3, digest, id), true);
  assert.equal(validFileTransferManifest(100000, 2, digest, id), false);
  assert.equal(validFileTransferManifest(101 * 1024 * 1024, 2200, digest, id), false);
  assert.equal(validFileTransferManifest(100000, 3, 'bad', id), false);
});

test('file transfer chunks must have exact bounded lengths', () => {
  assert.equal(validFileTransferChunk(0, 2, 48 * 1024, 50000), true);
  assert.equal(validFileTransferChunk(1, 2, 848, 50000), true);
  assert.equal(validFileTransferChunk(1, 2, 847, 50000), false);
  assert.equal(validFileTransferChunk(2, 2, 10, 50000), false);
});

test('remote action envelopes require a unique opaque id and allowlisted action', () => {
  assert.equal(validRemoteActionEnvelope('action_12345678', { kind: 'tap' }), true);
  assert.equal(validRemoteActionEnvelope('../bad', { kind: 'tap' }), false);
  assert.equal(validRemoteActionEnvelope('action_12345678', { kind: 'shell' }), false);
});

test('advanced consent-gated PC companion actions are relayed only from the explicit allowlist', () => {
  for (const kind of [
    'tap','swipe','scroll','back','home','type','click_text','open_app','ocr',
    'file_list','file_read','file_search','file_write','offline_chat','code_assistant',
    'media_control','system_control','screenshot','clipboard_transform','web_search'
  ]) {
    assert.equal(ALLOWED_REMOTE_ACTION_KINDS.has(kind), true, kind);
    assert.equal(validRemoteActionEnvelope('action_12345678', { kind }), true, kind);
  }
  for (const kind of ['shell','powershell','delete_file','unknown']) {
    assert.equal(ALLOWED_REMOTE_ACTION_KINDS.has(kind), false, kind);
    assert.equal(validRemoteActionEnvelope('action_12345678', { kind }), false, kind);
  }
});

test('only the owner can revoke another paired device and revocation clears grants', () => {
  const owner = { role: 'owner' }, member = { role: 'member' };
  const grants = new Map([['peer', { expiresAt: Date.now() + 1000 }]]);
  const target = { role: 'member', grants, controlGrants: new Map([['peer', Date.now()+1000]]), pendingControlRequests: new Map([['peer', {}]]), incomingTransfers: new Map(), socket: { close() {} } };
  const session = { devices: new Map([['owner', owner], ['member', target]]) };
  assert.equal(revokePairedDevice(session, 'member', 'member'), false);
  assert.equal(revokePairedDevice(session, 'owner', 'member'), false);
  assert.equal(revokePairedDevice(session, 'member', 'owner'), true);
  assert.equal(session.devices.has('member'), false);
  assert.equal(grants.size, 0);
});
