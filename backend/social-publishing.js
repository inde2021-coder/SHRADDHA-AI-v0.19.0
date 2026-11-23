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
  if (!['telegram', 'mastodon', 'bluesky', 'linkedin', 'facebook', 'instagram'].includes(platform)) throw new Error('Unsupported social platform');
  const text = body.text;
  if (platform === 'bluesky' && [...text].length > 300) throw new Error('Bluesky posts must be at most 300 characters');
  if (platform === 'linkedin' && text.length > 3000) throw new Error('LinkedIn posts must be at most 3000 characters');
  if (platform === 'instagram') {
    if (text.length > 2200) throw new Error('Instagram captions must be at most 2200 characters');
    const mediaKind = String(body.mediaKind || '').toLowerCase();
    if (!['image', 'reel', 'story_image', 'story_video'].includes(mediaKind)) throw new Error('Choose an Instagram image, Reel, or Story media type');
    return { platform, text, mediaKind, mediaUrl: validateInstagramMediaUrl(body.mediaUrl) };
  }
  if (body.mediaUrl !== undefined || body.mediaKind !== undefined) throw new Error('Public media URLs are only supported for Instagram');
  return { platform, text };
}

export function validateInstagramMediaUrl(value) {
  if (typeof value !== 'string' || value.length > 2048) throw new Error('Instagram media URL must be a public HTTPS URL up to 2048 characters');
  let url;
  try { url = new URL(value); } catch { throw new Error('Instagram media URL is invalid'); }
  const host = url.hostname.toLowerCase();
  const ipv4 = /^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$/.test(host);
  if (url.protocol !== 'https:' || url.username || url.password || url.hash ||
      host === 'localhost' || host.endsWith('.local') || !host.includes('.') || ipv4 || host.startsWith('[')) {
    throw new Error('Instagram media URL must use a public HTTPS hostname without credentials, IP address or fragment');
  }
  return url.toString();
}

export function validateBlueskyCredentials(identifier, appPassword) {
  if (typeof identifier !== 'string' || identifier.length < 3 || identifier.length > 253 || /\s/.test(identifier)) throw new Error('BLUESKY_IDENTIFIER must be a handle or email');
  if (typeof appPassword !== 'string' || !/^[A-Za-z0-9-]{16,64}$/.test(appPassword.replace(/-/g, ''))) throw new Error('BLUESKY_APP_PASSWORD must be an app password');
  return { identifier, appPassword };
}

export function validateLinkedInAuthor(value) {
  if (typeof value !== 'string' || !/^urn:li:(person|organization):[A-Za-z0-9_-]{3,80}$/.test(value)) throw new Error('LINKEDIN_AUTHOR_URN must be a valid person or organization URN');
  return value;
}

export function validateLinkedInVersion(value) {
  if (typeof value !== 'string' || !/^20[0-9]{4}$/.test(value)) throw new Error('LINKEDIN_VERSION must use YYYYMM format');
  const month = Number(value.slice(4, 6));
  if (month < 1 || month > 12) throw new Error('LINKEDIN_VERSION month must be 01-12');
  return value;
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

export function validateMediaAttachment(body, platform) {
  if (!body || typeof body !== 'object') throw new Error('Media request is invalid');
  if (!['telegram', 'mastodon'].includes(platform)) throw new Error('Media publishing is not enabled for this platform');
  const mimeType = String(body.mimeType || '').toLowerCase();
  const kind = String(body.mediaKind || '').toLowerCase();
  const allowed = kind === 'image'
    ? new Set(['image/jpeg', 'image/png', 'image/webp'])
    : kind === 'video'
      ? (platform === 'telegram' ? new Set(['video/mp4']) : new Set(['video/mp4', 'video/quicktime']))
      : new Set();
  if (!allowed.has(mimeType)) throw new Error('Media type and MIME type are not supported');
  if (typeof body.mediaBase64 !== 'string' || body.mediaBase64.length < 8 || body.mediaBase64.length > 11_200_000 ||
      !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(body.mediaBase64)) {
    throw new Error('Media must be valid Base64 and no larger than 8 MiB');
  }
  const bytes = Buffer.from(body.mediaBase64, 'base64');
  if (bytes.length < 1 || bytes.length > 8 * 1024 * 1024) throw new Error('Media must be between 1 byte and 8 MiB');
  const fileName = typeof body.fileName === 'string' ? body.fileName.replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 100) : '';
  return { kind, mimeType, bytes, fileName: fileName || (kind === 'video' ? 'upload.mp4' : 'upload.jpg') };
}

export function validateScheduleIntent(body, now = Date.now()) {
  const intent = validatePublishIntent(body);
  if (body.mediaBase64 !== undefined) throw new Error('Binary media uploads cannot be stored in the schedule queue. Use a public HTTPS media URL for Instagram or schedule a text-only post.');
  if (intent.platform === 'instagram' && (!intent.mediaKind || !intent.mediaUrl)) throw new Error('Instagram scheduled posts require a media type and public HTTPS media URL.');
  if (intent.platform !== 'instagram' && (body.mediaUrl !== undefined || body.mediaKind !== undefined)) throw new Error('Media URL scheduling is currently supported only for Instagram.');
  const idempotencyKey = String(body.idempotencyKey || '');
  if (!/^[A-Za-z0-9_-]{16,80}$/.test(idempotencyKey)) throw new Error('idempotencyKey must contain 16-80 safe characters');
  const scheduledAt = String(body.scheduledAt || '');
  if (!/(?:Z|[+-][0-9]{2}:[0-9]{2})$/.test(scheduledAt)) throw new Error('scheduledAt must be an ISO-8601 timestamp with timezone');
  const scheduledAtMs = Date.parse(scheduledAt);
  if (!Number.isFinite(scheduledAtMs) || scheduledAtMs < now + 60_000 || scheduledAtMs > now + 90 * 24 * 60 * 60_000) {
    throw new Error('Schedule time must be at least 1 minute and no more than 90 days in the future');
  }
  return { ...intent, idempotencyKey, scheduledAt: new Date(scheduledAtMs).toISOString(), scheduledAtMs };
}
