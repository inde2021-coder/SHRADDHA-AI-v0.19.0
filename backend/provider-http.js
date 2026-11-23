const MAX_PROVIDER_RESPONSE_BYTES = 2 * 1024 * 1024;
const RETRYABLE_STATUS = new Set([408, 425, 429, 500, 502, 503, 504]);

function boundedRetryDelay(response, attempt) {
  const retryAfter = Number(response?.headers?.get?.('retry-after'));
  if (Number.isFinite(retryAfter) && retryAfter >= 0) {
    return Math.min(5_000, Math.max(250, retryAfter * 1_000));
  }
  return Math.min(4_000, 300 * (2 ** attempt));
}

async function readBoundedJson(response) {
  const declared = Number(response.headers?.get?.('content-length'));
  if (Number.isFinite(declared) && declared > MAX_PROVIDER_RESPONSE_BYTES) {
    throw new Error('AI provider response exceeded the 2 MiB safety limit.');
  }
  const raw = await response.text();
  if (Buffer.byteLength(raw, 'utf8') > MAX_PROVIDER_RESPONSE_BYTES) {
    throw new Error('AI provider response exceeded the 2 MiB safety limit.');
  }
  try { return JSON.parse(raw || '{}'); }
  catch { throw new Error('AI provider returned an invalid JSON response.'); }
}

/** Shared bounded provider transport: retries transient failures only, never auth/quota policy errors. */
export async function requestProviderJson(url, init, {
  provider = 'AI',
  fetchImpl = fetch,
  attempts = 3,
  delay = ms => new Promise(resolve => setTimeout(resolve, ms))
} = {}) {
  if (typeof url !== 'string' || !/^https:\/\//i.test(url)) throw new Error('AI provider endpoint must use HTTPS.');
  const maxAttempts = Math.max(1, Math.min(3, Number.isInteger(attempts) ? attempts : 3));
  let lastError;
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    let response;
    try {
      response = await fetchImpl(url, init);
      if (!response.ok) {
        const body = await readBoundedJson(response).catch(() => ({}));
        const detail = String(body?.error?.message || body?.message || '').slice(0, 240);
        const error = new Error(detail || `${provider} request failed (HTTP ${response.status}).`);
        error.statusCode = response.status;
        if (RETRYABLE_STATUS.has(response.status) && attempt + 1 < maxAttempts) {
          await delay(boundedRetryDelay(response, attempt));
          continue;
        }
        throw error;
      }
      return await readBoundedJson(response);
    } catch (error) {
      lastError = error;
      const status = Number(error?.statusCode || 0);
      const retryable = status === 0 || RETRYABLE_STATUS.has(status);
      if (!retryable || attempt + 1 >= maxAttempts) throw error;
      if (status === 0) await delay(Math.min(4_000, 300 * (2 ** attempt)));
    }
  }
  throw lastError || new Error(`${provider} request failed.`);
}
