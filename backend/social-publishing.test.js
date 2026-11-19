import test from 'node:test';
import assert from 'node:assert/strict';
import { validatePublishIntent, validateMastodonOrigin, mastodonVisibility } from './social-publishing.js';

test('publishing requires explicit confirmation of the exact preview', () => {
  assert.deepEqual(validatePublishIntent({ platform: 'telegram', text: 'Hello', preview: 'Hello', confirmed: true }), { platform: 'telegram', text: 'Hello' });
  assert.throws(() => validatePublishIntent({ platform: 'telegram', text: 'Hello', preview: 'Changed', confirmed: true }), /exact post preview/);
  assert.throws(() => validatePublishIntent({ platform: 'telegram', text: 'Hello', preview: 'Hello', confirmed: false }), /confirmation/);
});

test('only supported platforms and bounded non-empty posts are accepted', () => {
  assert.throws(() => validatePublishIntent({ platform: 'instagram', text: 'x', preview: 'x', confirmed: true }), /Unsupported/);
  assert.throws(() => validatePublishIntent({ platform: 'mastodon', text: ' ', preview: ' ', confirmed: true }), /1-5000/);
  assert.throws(() => validatePublishIntent({ platform: 'telegram', text: 'x'.repeat(5001), preview: 'x'.repeat(5001), confirmed: true }), /1-5000/);
});

test('Mastodon server URL must be a public HTTPS origin', () => {
  assert.equal(validateMastodonOrigin('https://mastodon.social'), 'https://mastodon.social');
  for (const url of ['http://mastodon.social', 'https://localhost', 'https://127.0.0.1', 'https://mastodon.social/api', 'https://user:pass@mastodon.social']) {
    assert.throws(() => validateMastodonOrigin(url));
  }
});

test('Mastodon visibility is restricted to official visibility values', () => {
  assert.equal(mastodonVisibility('unlisted'), 'unlisted');
  assert.equal(mastodonVisibility(''), 'unlisted');
  assert.throws(() => mastodonVisibility('secret'));
});
