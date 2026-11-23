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
