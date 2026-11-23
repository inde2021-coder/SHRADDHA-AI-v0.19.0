import {
  validateBlueskyCredentials, validateLinkedInAuthor, validateLinkedInVersion,
  validateMastodonOrigin, mastodonVisibility, validateMediaAttachment
} from './social-publishing.js';

function fail(status, message, extra = {}) {
  const error = new Error(message);
  error.statusCode = status;
  error.details = extra;
  throw error;
}

/** Provider publisher shared by immediate and scheduled posts. */
export async function publishSocialPost(body, intent, env = process.env) {
  if (body.mediaBase64 !== undefined && !['telegram', 'mastodon'].includes(intent.platform)) {
    fail(400, 'Media publishing is not enabled for this platform.');
  }
  if (body.mediaBase64 !== undefined && intent.platform === 'telegram' && intent.text.length > 1024) {
    fail(400, 'Telegram media captions are limited to 1024 characters.');
  }
  if (intent.platform === 'telegram') {
    const botToken = env.TELEGRAM_BOT_TOKEN || '';
    const chatId = env.TELEGRAM_CHAT_ID || '';
    if (!/^[0-9]+:[A-Za-z0-9_-]{20,}$/.test(botToken) || !chatId || chatId.length > 128) {
      fail(503, 'Telegram bot credentials and destination are not configured on the server');
    }
    if (body.mediaBase64 !== undefined) {
      const media = validateMediaAttachment(body, 'telegram');
      const form = new FormData();
      form.set('chat_id', chatId); form.set('caption', intent.text);
      form.set(media.kind === 'video' ? 'video' : 'photo', new Blob([media.bytes], { type: media.mimeType }), media.fileName);
      let upstream;
      try {
        upstream = await fetch(`https://api.telegram.org/bot${botToken}/${media.kind === 'video' ? 'sendVideo' : 'sendPhoto'}`, {
          method: 'POST', body: form, signal: AbortSignal.timeout(45000)
        });
      } catch { fail(502, 'Telegram media upload could not be reached'); }
      const result = await upstream.json().catch(() => ({}));
      if (!upstream.ok || result.ok !== true) fail(502, 'Telegram did not accept the media post');
      return { ok: true, platform: 'telegram', mediaKind: media.kind, messageId: result.result?.message_id || null, confirmedPreview: true };
    }
    let upstream;
    try {
      upstream = await fetch(`https://api.telegram.org/bot${botToken}/sendMessage`, {
        method: 'POST', headers: { 'content-type': 'application/json' }, signal: AbortSignal.timeout(15000),
        body: JSON.stringify({ chat_id: chatId, text: intent.text, disable_web_page_preview: true })
      });
    } catch { fail(502, 'Telegram service could not be reached'); }
    const result = await upstream.json().catch(() => ({}));
    if (!upstream.ok || result.ok !== true) fail(502, 'Telegram did not accept the post');
    return { ok: true, platform: 'telegram', messageId: result.result?.message_id || null, confirmedPreview: true };
  }

  if (intent.platform === 'bluesky') {
    const credentials = (() => {
      try { return validateBlueskyCredentials(env.BLUESKY_IDENTIFIER || '', env.BLUESKY_APP_PASSWORD || ''); }
      catch (error) { fail(503, error.message); }
    })();
    try {
      const login = await fetch('https://bsky.social/xrpc/com.atproto.server.createSession', {
        method: 'POST', headers: { 'content-type': 'application/json' }, signal: AbortSignal.timeout(15000),
        body: JSON.stringify({ identifier: credentials.identifier, password: credentials.appPassword })
      });
      const session = await login.json().catch(() => ({}));
      if (!login.ok || typeof session.accessJwt !== 'string' || typeof session.did !== 'string') fail(502, 'Bluesky authentication failed; check the app password and identifier');
      const post = await fetch('https://bsky.social/xrpc/com.atproto.repo.createRecord', {
        method: 'POST', headers: { authorization: 'Bearer ' + session.accessJwt, 'content-type': 'application/json' },
        signal: AbortSignal.timeout(15000),
        body: JSON.stringify({ repo: session.did, collection: 'app.bsky.feed.post', record: { '$type': 'app.bsky.feed.post', text: intent.text, createdAt: new Date().toISOString() } })
      });
      const result = await post.json().catch(() => ({}));
      if (!post.ok || typeof result.uri !== 'string') fail(502, 'Bluesky did not accept the post');
      return { ok: true, platform: 'bluesky', postId: result.uri, url: result.uri, confirmedPreview: true };
    } catch (error) {
      if (error.statusCode) throw error;
      fail(502, 'Bluesky service could not be reached');
    }
  }

  if (intent.platform === 'linkedin') {
    const accessToken = env.LINKEDIN_ACCESS_TOKEN || '';
    let author, version;
    try { author = validateLinkedInAuthor(env.LINKEDIN_AUTHOR_URN || ''); version = validateLinkedInVersion(env.LINKEDIN_VERSION || ''); }
    catch (error) { fail(503, error.message); }
    if (!accessToken || accessToken.length > 8192) fail(503, 'LINKEDIN_ACCESS_TOKEN is not configured on the server');
    let upstream;
    try {
      upstream = await fetch('https://api.linkedin.com/rest/posts', {
        method: 'POST',
        headers: { authorization: 'Bearer ' + accessToken, 'content-type': 'application/json', 'Linkedin-Version': version, 'X-Restli-Protocol-Version': '2.0.0' },
        signal: AbortSignal.timeout(20000),
        body: JSON.stringify({ author, commentary: intent.text, visibility: 'PUBLIC', distribution: { feedDistribution: 'MAIN_FEED', targetEntities: [], thirdPartyDistributionChannels: [] }, lifecycleState: 'PUBLISHED', isReshare: false })
      });
    } catch { fail(502, 'LinkedIn service could not be reached'); }
    const result = await upstream.json().catch(() => ({}));
    const postId = upstream.headers.get('x-restli-id') || result.id || result.urn;
    if (!upstream.ok || typeof postId !== 'string') fail(502, 'LinkedIn did not accept the post', { status: upstream.status });
    return { ok: true, platform: 'linkedin', postId, confirmedPreview: true };
  }

  const base = env.MASTODON_BASE_URL || '';
  const accessToken = env.MASTODON_ACCESS_TOKEN || '';
  if (!base || !accessToken) fail(503, 'Mastodon server URL and access token are not configured on the server');
  let origin, visibility;
  try { origin = validateMastodonOrigin(base); visibility = mastodonVisibility(env.MASTODON_VISIBILITY); }
  catch (error) { fail(503, error.message); }
  let mediaId;
  let mediaKind;
  if (body.mediaBase64 !== undefined) {
    const media = validateMediaAttachment(body, 'mastodon');
    const form = new FormData();
    form.set('file', new Blob([media.bytes], { type: media.mimeType }), media.fileName);
    let upload;
    try {
      upload = await fetch(origin + '/api/v2/media', {
        method: 'POST', headers: { authorization: 'Bearer ' + accessToken }, body: form, signal: AbortSignal.timeout(45000)
      });
    } catch { fail(502, 'Mastodon media upload could not be reached'); }
    const uploaded = await upload.json().catch(() => ({}));
    if (!upload.ok || typeof uploaded.id !== 'string') fail(502, 'Mastodon did not accept the media upload');
    mediaId = uploaded.id; mediaKind = media.kind;
  }
  let upstream;
  try {
    upstream = await fetch(origin + '/api/v1/statuses', {
      method: 'POST', headers: { authorization: 'Bearer ' + accessToken, 'content-type': 'application/json' },
      signal: AbortSignal.timeout(20000),
      body: JSON.stringify({ status: intent.text, visibility, ...(mediaId ? { media_ids: [mediaId] } : {}) })
    });
  } catch { fail(502, 'Mastodon service could not be reached; check account before retrying'); }
  const result = await upstream.json().catch(() => ({}));
  if (!upstream.ok || typeof result.id !== 'string') fail(502, 'Mastodon did not accept the post');
  return { ok: true, platform: 'mastodon', ...(mediaKind ? { mediaKind } : {}), postId: result.id, url: typeof result.url === 'string' ? result.url : '', visibility, confirmedPreview: true };
}
