import test from 'node:test';
import assert from 'node:assert/strict';
import { publishSocialPost } from './social-publisher.js';

test('Telegram text publishing uses only configured server credentials', async () => {
  const previous = globalThis.fetch;
  let captured;
  globalThis.fetch = async (url, options) => {
    captured = { url: String(url), body: JSON.parse(options.body) };
    return new Response(JSON.stringify({ ok: true, result: { message_id: 77 } }), { status: 200 });
  };
  try {
    const result = await publishSocialPost({}, { platform: 'telegram', text: 'approved exact text' }, {
      TELEGRAM_BOT_TOKEN: '123456:abcdefghijklmnopqrstuvwxyz0123456789',
      TELEGRAM_CHAT_ID: '-1001234567890'
    });
    assert.equal(result.messageId, 77);
    assert.equal(captured.body.text, 'approved exact text');
    assert.match(captured.url, /sendMessage$/);
  } finally { globalThis.fetch = previous; }
});

test('Mastodon requests refuse redirects so bearer credentials cannot be forwarded to redirect targets', async () => {
  const previous = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (url, options) => {
    requests.push({ url: String(url), redirect: options.redirect });
    return new Response(JSON.stringify({ id: 'redirect-safe-123', url: 'https://mastodon.example/@user/123' }), { status: 200 });
  };
  try {
    await publishSocialPost({}, { platform: 'mastodon', text: 'approved status' }, {
      MASTODON_BASE_URL: 'https://mastodon.example', MASTODON_ACCESS_TOKEN: 'server-token'
    });
    assert.equal(requests.length, 1);
    assert.equal(requests[0].redirect, 'error');
  } finally { globalThis.fetch = previous; }
});

test('Mastodon text publishing validates origin and returns accepted post id', async () => {
  const previous = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    assert.equal(String(url), 'https://mastodon.example/api/v1/statuses');
    assert.equal(options.headers.authorization, 'Bearer server-token');
    return new Response(JSON.stringify({ id: '123', url: 'https://mastodon.example/@user/123' }), { status: 200 });
  };
  try {
    const result = await publishSocialPost({}, { platform: 'mastodon', text: 'approved status' }, {
      MASTODON_BASE_URL: 'https://mastodon.example', MASTODON_ACCESS_TOKEN: 'server-token', MASTODON_VISIBILITY: 'unlisted'
    });
    assert.equal(result.postId, '123');
    assert.equal(result.visibility, 'unlisted');
  } finally { globalThis.fetch = previous; }
});

test('provider failures do not report a successful publish', async () => {
  const previous = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({ ok: false }), { status: 400 });
  try {
    await assert.rejects(() => publishSocialPost({}, { platform: 'telegram', text: 'approved' }, {
      TELEGRAM_BOT_TOKEN: '123456:abcdefghijklmnopqrstuvwxyz0123456789', TELEGRAM_CHAT_ID: '12345'
    }), /did not accept/);
  } finally { globalThis.fetch = previous; }
});

test('Facebook Page text publishing uses the configured Graph API token', async () => {
  const previous = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    assert.match(String(url), /\/123456789\/feed$/);
    assert.match(String(options.body), /message=Page\+announcement/);
    return new Response(JSON.stringify({ id: '123456789_987654321' }), { status: 200 });
  };
  try {
    const result = await publishSocialPost({}, { platform: 'facebook', text: 'Page announcement' }, {
      FACEBOOK_PAGE_ID: '123456789', FACEBOOK_PAGE_ACCESS_TOKEN: 'server-side-token-abcdefghijklmnopqrstuvwxyz', META_GRAPH_API_VERSION: 'v24.0'
    });
    assert.equal(result.platform, 'facebook');
    assert.equal(result.postId, '123456789_987654321');
  } finally { globalThis.fetch = previous; }
});

test('Instagram feed image creates and publishes a verified container', async () => {
  const previous = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url: String(url), body: String(options.body || '') });
    if (String(url).endsWith('/media')) return new Response(JSON.stringify({ id: 'container123' }), { status: 200 });
    return new Response(JSON.stringify({ id: 'igpost123' }), { status: 200 });
  };
  try {
    const result = await publishSocialPost({}, {
      platform: 'instagram', text: 'Photo caption', mediaKind: 'image', mediaUrl: 'https://cdn.example.com/photo.jpg'
    }, {
      INSTAGRAM_ACCOUNT_ID: '17841400000000000', INSTAGRAM_ACCESS_TOKEN: 'server-side-token-abcdefghijklmnopqrstuvwxyz', META_GRAPH_API_VERSION: 'v24.0'
    });
    assert.equal(result.postId, 'igpost123');
    assert.equal(calls.length, 2);
    assert.match(calls[0].body, /image_url=https%3A%2F%2Fcdn/);
  } finally { globalThis.fetch = previous; }
});
