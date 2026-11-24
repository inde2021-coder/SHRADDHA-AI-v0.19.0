import test from 'node:test';
import assert from 'node:assert/strict';
import { resolvePinnedPublicIpv4 } from './pinned-https.js';

test('pinned resolver accepts globally routable IPv4', async () => {
  assert.equal(await resolvePinnedPublicIpv4('mastodon.example', async () => ['8.8.8.8']), '8.8.8.8');
});

test('pinned resolver rejects private, loopback, link-local and reserved IPv4', async () => {
  for (const address of ['10.0.0.1', '127.0.0.1', '169.254.1.2', '172.16.0.1', '192.168.1.1', '100.64.0.1', '198.51.100.4', '203.0.113.8', '224.0.0.1', '240.0.0.1']) {
    await assert.rejects(() => resolvePinnedPublicIpv4('mastodon.example', async () => [address]), /public IPv4/);
  }
});

test('pinned resolver rejects mixed public and private DNS answers', async () => {
  await assert.rejects(() => resolvePinnedPublicIpv4('mastodon.example', async () => ['8.8.8.8', '127.0.0.1']), /public IPv4/);
});

test('pinned resolver rejects empty DNS answers', async () => {
  await assert.rejects(() => resolvePinnedPublicIpv4('mastodon.example', async () => []), /public IPv4/);
});
