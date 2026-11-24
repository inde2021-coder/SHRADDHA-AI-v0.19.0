import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { pinnedHttpsJsonRequest } from './pinned-https.js';
import { resolvePinnedPublicIpv4 } from './pinned-https.js';

test('pinned resolver accepts globally routable IPv4', async () => {
  assert.equal(await resolvePinnedPublicIpv4('mastodon.example', async () => ['8.8.8.8']), '8.8.8.8');
});

test('pinned resolver rejects private, loopback, link-local and reserved IPv4', async () => {
  for (const address of ['10.0.0.1', '127.0.0.1', '169.254.1.2', '172.16.0.1', '192.168.1.1', '192.0.0.1', '192.31.196.1', '192.52.193.1', '192.88.99.1', '192.175.48.1', '100.64.0.1', '198.18.0.1', '198.51.100.4', '203.0.113.8', '224.0.0.1', '240.0.0.1', '255.255.255.255']) {
    await assert.rejects(() => resolvePinnedPublicIpv4('mastodon.example', async () => [address]), /public IPv4/);
  }
});

test('pinned resolver rejects mixed public and private DNS answers', async () => {
  await assert.rejects(() => resolvePinnedPublicIpv4('mastodon.example', async () => ['8.8.8.8', '127.0.0.1']), /public IPv4/);
});

test('pinned resolver rejects empty DNS answers', async () => {
  await assert.rejects(() => resolvePinnedPublicIpv4('mastodon.example', async () => []), /public IPv4/);
});

test('HTTPS socket lookup is pinned to the validated address and preserves TLS hostname', async () => {
  let captured;
  const response = new EventEmitter();
  response.statusCode = 200;
  const request = new EventEmitter();
  request.setTimeout = () => {};
  request.write = () => {};
  request.end = () => {
    queueMicrotask(() => {
      response.emit('data', Buffer.from('{"id":"post-1"}'));
      response.emit('end');
    });
  };
  const resultPromise = pinnedHttpsJsonRequest('https://mastodon.example/api/v1/statuses', {
    method: 'POST', body: '{}', headers: { 'content-type': 'application/json' }
  }, {
    resolve4: async () => ['8.8.4.4'],
    request: (url, options, callback) => {
      captured = { url: String(url), options };
      queueMicrotask(() => callback(response));
      return request;
    }
  });
  const result = await resultPromise;
  assert.equal(captured.url, 'https://mastodon.example/api/v1/statuses');
  assert.equal(captured.options.servername, 'mastodon.example');
  assert.equal(captured.options.rejectUnauthorized, true);
  await new Promise((resolve, reject) => captured.options.lookup('mastodon.example', {}, (error, address, family) => {
    if (error) return reject(error);
    assert.equal(address, '8.8.4.4');
    assert.equal(family, 4);
    resolve();
  }));
  assert.deepEqual(await result.json(), { id: 'post-1' });
});


test('pinned transport rejects nonstandard HTTPS ports before DNS or socket creation', async () => {
  let resolved = false;
  await assert.rejects(() => pinnedHttpsJsonRequest('https://mastodon.example:8443/api/v1/statuses', {}, {
    resolve4: async () => { resolved = true; return ['8.8.8.8']; },
    request: () => { throw new Error('socket must not be created'); }
  }), /port 443/);
  assert.equal(resolved, false);
});
