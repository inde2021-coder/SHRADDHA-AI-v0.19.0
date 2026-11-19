const VISIBILITIES = new Set(['public', 'unlisted', 'private', 'direct']);

export function validatePublishIntent(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error('Invalid publish request');
  if (body.confirmed !== true || typeof body.preview !== 'string' || body.preview !== body.text) {
    throw new Error('Explicit confirmation of the exact post preview is required');
  }
  if (typeof body.text !== 'string' || !body.text.trim() || body.text.length > 5000) {
    throw new Error('Post text must be 1-5000 characters');
  }
  const platform = String(body.platform || '').toLowerCase();
  if (!['telegram', 'mastodon'].includes(platform)) throw new Error('Unsupported social platform');
  return { platform, text: body.text.trim() };
}

export function validateMastodonOrigin(value) {
  let url;
  try { url = new URL(value); } catch { throw new Error('MASTODON_BASE_URL must be a valid HTTPS origin'); }
  const host = url.hostname.toLowerCase();
  const ipv4 = /^(?:\d{1,3}\.){3}\d{1,3}$/.test(host);
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash ||
      host === 'localhost' || host.endsWith('.local') || !host.includes('.') || ipv4 ||
      url.pathname !== '' && url.pathname !== '/') {
    throw new Error('MASTODON_BASE_URL must be a public HTTPS origin without credentials, path, query or IP address');
  }
  return url.origin;
}

export function mastodonVisibility(value) {
  const visibility = String(value || 'unlisted').toLowerCase();
  if (!VISIBILITIES.has(visibility)) throw new Error('MASTODON_VISIBILITY must be public, unlisted, private or direct');
  return visibility;
}
