/** Validation and proxy helper for the self-hosted Shraddha voice engine. */
export function normalizeVoiceRequest(body) {
  if (!body || typeof body.text !== 'string') throw new Error('text is required');
  const text = body.text.trim();
  if (!text || text.length > 6000) throw new Error('text must be 1-6000 characters');
  const language = body.language === 'hi' ? 'hi' : 'en';
  return { text, language };
}

export async function synthesizeShraddhaPcm(body, env = process.env, fetchImpl = fetch) {
  const input = normalizeVoiceRequest(body);
  const endpoint = String(env.SHRADDHA_TTS_URL || '').trim();
  if (!endpoint) {
    const error = new Error('SHRADDHA_TTS_URL is not configured. Add a self-hosted custom Shraddha voice model endpoint.');
    error.statusCode = 503;
    throw error;
  }
  const parsed = new URL(endpoint);
  if (parsed.protocol !== 'https:') throw new Error('SHRADDHA_TTS_URL must use HTTPS');
  const headers = { 'content-type': 'application/json', accept: 'audio/pcm, application/octet-stream' };
  if (env.SHRADDHA_TTS_TOKEN) headers.authorization = `Bearer ${env.SHRADDHA_TTS_TOKEN}`;
  const upstream = await fetchImpl(endpoint, {
    method: 'POST',
    headers,
    signal: AbortSignal.timeout(30000),
    body: JSON.stringify({
      ...input,
      voice: String(env.SHRADDHA_TTS_VOICE || 'shraddha').slice(0, 80),
      format: 'pcm_s16le',
      sample_rate: 24000,
      channels: 1
    })
  });
  if (!upstream.ok) {
    const message = (await upstream.text()).slice(0, 240);
    const error = new Error(`Custom TTS provider failed (HTTP ${upstream.status}): ${message}`);
    error.statusCode = 502;
    throw error;
  }
  const contentType = String(upstream.headers.get('content-type') || '').toLowerCase();
  if (!contentType.includes('audio/pcm') && !contentType.includes('application/octet-stream')) {
    const error = new Error('Custom TTS provider must return raw 24 kHz mono PCM signed-16 little-endian audio');
    error.statusCode = 502;
    throw error;
  }
  const audio = Buffer.from(await upstream.arrayBuffer());
  if (audio.length < 2 || audio.length > 3_000_000 || audio.length % 2 !== 0) {
    const error = new Error('Custom TTS provider returned invalid or oversized PCM audio');
    error.statusCode = 502;
    throw error;
  }
  return audio;
}
