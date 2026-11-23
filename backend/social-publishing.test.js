import test from 'node:test';
import assert from 'node:assert/strict';
import { validatePublishIntent, validateMastodonOrigin, mastodonVisibility, validateBlueskyCredentials, validateLinkedInAuthor, validateLinkedInVersion, validateMediaAttachment, validateInstagramMediaUrl, validateScheduleIntent } from './social-publishing.js';

test('publishing requires explicit confirmation of the exact preview', () => {
  assert.deepEqual(validatePublishIntent({ platform: 'telegram', text: 'Hello', preview: 'Hello', confirmed: true }), { platform: 'telegram', text: 'Hello' });
  assert.deepEqual(validatePublishIntent({ platform: 'telegram', text: ' Hello ', preview: ' Hello ', confirmed: true }), { platform: 'telegram', text: ' Hello ' });
  assert.throws(() => validatePublishIntent({ platform: 'telegram', text: 'Hello', preview: 'Changed', confirmed: true }), /exact post preview/);
  assert.throws(() => validatePublishIntent({ platform: 'telegram', text: 'Hello', preview: 'Hello', confirmed: false }), /confirmation/);
});

test('only supported platforms and bounded non-empty posts are accepted', () => {
  assert.throws(() => validatePublishIntent({ platform: 'tiktok', text: 'x', preview: 'x', confirmed: true }), /Unsupported/);
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

test('Bluesky and LinkedIn exact-preview posts pass platform-specific validation', () => {
  assert.deepEqual(validatePublishIntent({ platform: 'bluesky', text: 'Namaste', preview: 'Namaste', confirmed: true }), { platform: 'bluesky', text: 'Namaste' });
  assert.deepEqual(validatePublishIntent({ platform: 'linkedin', text: 'Hello professionals', preview: 'Hello professionals', confirmed: true }), { platform: 'linkedin', text: 'Hello professionals' });
  assert.throws(() => validatePublishIntent({ platform: 'bluesky', text: 'x'.repeat(301), preview: 'x'.repeat(301), confirmed: true }), /300 characters/);
  assert.throws(() => validatePublishIntent({ platform: 'linkedin', text: 'x'.repeat(3001), preview: 'x'.repeat(3001), confirmed: true }), /3000 characters/);
});

test('Bluesky requires a valid identifier and app password', () => {
  assert.equal(validateBlueskyCredentials('shraddha.bsky.social', 'abcd-efgh-ijkl-mnop').identifier, 'shraddha.bsky.social');
  assert.throws(() => validateBlueskyCredentials('bad handle', 'abcd-efgh-ijkl-mnop'));
  assert.throws(() => validateBlueskyCredentials('user@example.com', 'short'));
});

test('LinkedIn author URN and API version are validated', () => {
  assert.equal(validateLinkedInAuthor('urn:li:person:abc123'), 'urn:li:person:abc123');
  assert.equal(validateLinkedInAuthor('urn:li:organization:123456'), 'urn:li:organization:123456');
  assert.equal(validateLinkedInVersion('202611'), '202611');
  assert.throws(() => validateLinkedInAuthor('https://linkedin.com/in/user'));
  assert.throws(() => validateLinkedInVersion('202613'));
});
test('media publishing accepts only bounded image/video attachments for supported providers', () => {
  const bytes = Buffer.from('fake-media-bytes').toString('base64');
  assert.equal(validateMediaAttachment({ mediaKind: 'image', mimeType: 'image/jpeg', mediaBase64: bytes }, 'telegram').kind, 'image');
  assert.equal(validateMediaAttachment({ mediaKind: 'video', mimeType: 'video/mp4', mediaBase64: bytes }, 'mastodon').kind, 'video');
  assert.throws(() => validateMediaAttachment({ mediaKind: 'image', mimeType: 'image/svg+xml', mediaBase64: bytes }, 'telegram'));
  assert.throws(() => validateMediaAttachment({ mediaKind: 'video', mimeType: 'video/quicktime', mediaBase64: bytes }, 'telegram'));
  assert.throws(() => validateMediaAttachment({ mediaKind: 'image', mimeType: 'image/jpeg', mediaBase64: bytes }, 'bluesky'));
  assert.throws(() => validateMediaAttachment({ mediaKind: 'image', mimeType: 'image/jpeg', mediaBase64: 'not-base64' }, 'telegram'));
});

test('Instagram requires a safe public HTTPS media URL and a supported media type', () => {
  const intent = validatePublishIntent({ platform: 'instagram', text: 'caption', preview: 'caption', confirmed: true, mediaKind: 'reel', mediaUrl: 'https://cdn.example.com/video.mp4?signature=abc' });
  assert.equal(intent.mediaKind, 'reel');
  assert.match(intent.mediaUrl, /^https:\/\//);
  assert.throws(() => validateInstagramMediaUrl('http://cdn.example.com/image.jpg'), /public HTTPS/);
  assert.throws(() => validateInstagramMediaUrl('https://localhost/image.jpg'), /public HTTPS/);
  assert.throws(() => validateInstagramMediaUrl('https://127.0.0.1/image.jpg'), /public HTTPS/);
  assert.throws(() => validateInstagramMediaUrl('https://user:pass@cdn.example.com/image.jpg'), /public HTTPS/);
  assert.throws(() => validatePublishIntent({ platform: 'instagram', text: 'caption', preview: 'caption', confirmed: true, mediaKind: 'video', mediaUrl: 'https://cdn.example.com/video.mp4' }), /media type/);
});

test('Facebook text posts and Instagram media posts can be scheduled safely', () => {
  assert.equal(validatePublishIntent({ platform: 'facebook', text: 'Page post', preview: 'Page post', confirmed: true }).platform, 'facebook');
  const now = Date.parse('2026-11-23T06:00:00Z');
  const scheduled = validateScheduleIntent({ platform: 'instagram', text: 'caption', preview: 'caption', confirmed: true, idempotencyKey: 'schedule_key_123456', scheduledAt: '2026-11-23T08:00:00.000Z', mediaKind: 'reel', mediaUrl: 'https://cdn.example.com/p.mp4' }, now);
  assert.equal(scheduled.mediaKind, 'reel');
  assert.equal(scheduled.mediaUrl, 'https://cdn.example.com/p.mp4');
  assert.throws(() => validateScheduleIntent({ platform: 'instagram', text: 'caption', preview: 'caption', confirmed: true, idempotencyKey: 'schedule_key_123456', scheduledAt: '2026-11-23T08:00:00.000Z' }, now), /media type/);
  assert.throws(() => validateScheduleIntent({ platform: 'telegram', text: 'caption', preview: 'caption', confirmed: true, idempotencyKey: 'schedule_key_123456', scheduledAt: '2026-11-23T08:00:00.000Z', mediaUrl: 'https://cdn.example.com/p.jpg', mediaKind: 'image' }, now), /only supported for Instagram/);
});
