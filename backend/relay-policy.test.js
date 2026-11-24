import test from 'node:test';
import assert from 'node:assert/strict';
import { canShareVisualFrame, consumePairingCode, resolveControlApproval, validFileTransferManifest, validFileTransferChunk, validRemoteActionEnvelope, revokePairedDevice, ALLOWED_REMOTE_ACTION_KINDS, canUseRemoteControl, validMissingTransferIndexes, pruneExpiredDeviceState } from './relay-policy.js';

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


test('remote control authorization rechecks target opt-in, expiry, identity and current master-only mode', () => {
  const owner = { role: 'owner' };
  const member = { role: 'member' };
  const target = {
    role: 'member',
    sharePermissions: { remote_control: true },
    controlGrants: new Map([['owner', 500], ['member', 500]])
  };
  const session = { controlMode: 'mutual', devices: new Map([['owner', owner], ['member', member], ['target', target]]) };
  assert.equal(canUseRemoteControl(session, 'owner', 'target', 100), true);
  assert.equal(canUseRemoteControl(session, 'member', 'target', 100), true);
  assert.equal(canUseRemoteControl(session, 'member', 'target', 500), false);
  assert.equal(canUseRemoteControl(session, 'missing', 'target', 100), false);
  assert.equal(canUseRemoteControl(session, 'member', 'member', 100), false);
  target.sharePermissions.remote_control = false;
  assert.equal(canUseRemoteControl(session, 'owner', 'target', 100), false);
  target.sharePermissions.remote_control = true;
  session.controlMode = 'master';
  assert.equal(canUseRemoteControl(session, 'owner', 'target', 100), true);
  assert.equal(canUseRemoteControl(session, 'member', 'target', 100), false);
  session.controlMode = 'unknown';
  assert.equal(canUseRemoteControl(session, 'owner', 'target', 100), false);
});


test('receiver-owned resume index manifests are bounded, unique and in range', () => {
  assert.equal(validMissingTransferIndexes([0, 2, 4], 5), true);
  assert.equal(validMissingTransferIndexes([], 5), true);
  assert.equal(validMissingTransferIndexes([0, 0], 5), false);
  assert.equal(validMissingTransferIndexes([-1], 5), false);
  assert.equal(validMissingTransferIndexes([5], 5), false);
  assert.equal(validMissingTransferIndexes(Array(2201).fill(0).map((_, i) => i), 2201), false);
  assert.equal(validMissingTransferIndexes('0,1', 2), false);
  assert.equal(validMissingTransferIndexes([0], 0), false);
});


test('resume chunk indexes must be bounded, unique and within the active manifest', () => {
  assert.equal(validMissingTransferIndexes([0, 2, 4], 5), true);
  assert.equal(validMissingTransferIndexes([], 5), true);
  assert.equal(validMissingTransferIndexes([5], 5), false);
  assert.equal(validMissingTransferIndexes([-1], 5), false);
  assert.equal(validMissingTransferIndexes([1, 1], 5), false);
  assert.equal(validMissingTransferIndexes(Array(2201).fill(0), 2201), false);
  assert.equal(validMissingTransferIndexes([0], 5), true);
  assert.equal(validMissingTransferIndexes('0', 5), false);
});


test('expired device grants and pending relay work are pruned by their expiresAt fields', () => {
  const device = {
    pendingControlRequests: new Map([['old-control', { expiresAt: 99 }], ['live-control', { expiresAt: 101 }]]),
    pendingDataShares: new Map([['old-share', { expiresAt: 99 }]]),
    pendingRemoteActions: new Map([['old-action', { requesterId: 'peer', expiresAt: 99 }], ['live-action', { expiresAt: 101 }]]),
    incomingTransfers: new Map([['old-transfer', { expiresAt: 99 }]]),
    pendingTaskHandoffs: new Map([['old-task', { expiresAt: 99 }]]),
    controlGrants: new Map([['old-control-grant', 99], ['live-control-grant', 101]]),
    grants: new Map([['old-data-grant', { requestId: 'r', expiresAt: 99 }], ['live-data-grant', { requestId: 'r2', expiresAt: 101 }]])
  };
  assert.equal(pruneExpiredDeviceState(device, 100), 7);
  assert.deepEqual([...device.pendingControlRequests.keys()], ['live-control']);
  assert.deepEqual([...device.pendingRemoteActions.keys()], ['live-action']);
  assert.deepEqual([...device.controlGrants.keys()], ['live-control-grant']);
  assert.deepEqual([...device.grants.keys()], ['live-data-grant']);
});

test('malformed expiry entries fail closed and do not poison future remote actions', () => {
  const device = { pendingRemoteActions: new Map([['bad', {}], ['valid', { expiresAt: 200 }]]) };
  assert.equal(pruneExpiredDeviceState(device, 100), 1);
  assert.equal(device.pendingRemoteActions.has('bad'), false);
  assert.equal(device.pendingRemoteActions.has('valid'), true);
});


test('file transfer completion requires positive result and every chunk acknowledged', () => {
  const transfer = { chunkCount: 3, chunks: new Map([[0, true], [1, true]]) };
  assert.equal(canCompleteFileTransfer(transfer, true), false);
  transfer.chunks.set(2, true);
  assert.equal(canCompleteFileTransfer(transfer, true), true);
  assert.equal(canCompleteFileTransfer(transfer, false), false);
  assert.equal(canCompleteFileTransfer({ chunkCount: 3, chunks: new Map([[0, true]]) }, true), false);
  assert.equal(canCompleteFileTransfer(null, true), false);
});
