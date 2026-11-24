const MAX_PROVIDER_RESPONSE_BYTES = 2 * 1024 * 1024;
const RETRYABLE_STATUS = new Set([408, 425, 429, 500, 502, 503, 504]);

function boundedRetryDelay(response, attempt) {
  const retryAfter = Number(response?.headers?.get?.('retry-after'));
  if (Number.isFinite(retryAfter) && retryAfter >= 0) {
    return Math.min(5_000, Math.max(250, retryAfter * 1_000));
  }
  return Math.min(4_000, 300 * (2 ** attempt));
}

function nonRetryableError(message) {
  const error = new Error(message);
  error.retryable = false;
  return error;
}

async function readBoundedJson(response) {
  const declared = Number(response.headers?.get?.('content-length'));
  if (Number.isFinite(declared) && declared > MAX_PROVIDER_RESPONSE_BYTES) {
    throw nonRetryableError('AI provider response exceeded the 2 MiB safety limit.');
  }

  const chunks = [];
  let total = 0;
  const append = chunk => {
    const byteLength = typeof chunk === 'string'
      ? Buffer.byteLength(chunk, 'utf8')
      : Number(chunk?.byteLength ?? chunk?.length ?? 0);
    if (!Number.isSafeInteger(byteLength) || byteLength < 0 || total + byteLength > MAX_PROVIDER_RESPONSE_BYTES) {
      throw nonRetryableError('AI provider response exceeded the 2 MiB safety limit.');
    }
    const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    total += bytes.byteLength;
    chunks.push(bytes);
  };

  if (response.body?.getReader) {
    const reader = response.body.getReader();
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        append(value);
      }
    } catch (error) {
      if (error?.retryable === false) await reader.cancel().catch(() => {});
      throw error;
    } finally {
      reader.releaseLock?.();
    }
  } else if (response.body?.[Symbol.asyncIterator]) {
    for await (const chunk of response.body) append(chunk);
  } else if (typeof response.text === 'function') {
    // Compatibility for lightweight test doubles; native Fetch responses use the bounded stream path.
    append(Buffer.from(await response.text(), 'utf8'));
  } else {
    throw nonRetryableError('AI provider returned an unreadable response.');
  }

  try { return JSON.parse(Buffer.concat(chunks, total).toString('utf8') || '{}'); }
  catch { throw nonRetryableError('AI provider returned an invalid JSON response.'); }
}

function hasIdempotencyKey(init) {
  const headers = init?.headers;
  if (!headers) return false;
  if (typeof headers.get === 'function') return Boolean(headers.get('idempotency-key'));
  return Object.entries(headers).some(([name, value]) =>
    name.toLowerCase() === 'idempotency-key' && String(value).trim().length > 0
  );
}

function isRetrySafe(init) {
  const method = String(init?.method || 'GET').toUpperCase();
  return ['GET', 'HEAD', 'OPTIONS'].includes(method) || hasIdempotencyKey(init);
}

/** Shared bounded provider transport. Mutating requests retry only with an explicit idempotency key. */
export async function requestProviderJson(url, init = {}, {
  provider = 'AI',
  fetchImpl = fetch,
  attempts = 3,
  delay = ms => new Promise(resolve => setTimeout(resolve, ms))
} = {}) {
  if (typeof url !== 'string' || !/^https:\/\//i.test(url)) throw new Error('AI provider endpoint must use HTTPS.');
  const maxAttempts = Math.max(1, Math.min(3, Number.isInteger(attempts) ? attempts : 3));
  const retrySafe = isRetrySafe(init);
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
        if (retrySafe && RETRYABLE_STATUS.has(response.status) && attempt + 1 < maxAttempts) {
          await delay(boundedRetryDelay(response, attempt));
          continue;
        }
        throw error;
      }
      return await readBoundedJson(response);
    } catch (error) {
      lastError = error;
      const status = Number(error?.statusCode || 0);
      const retryable = error?.retryable !== false && retrySafe &&
        (status === 0 || RETRYABLE_STATUS.has(status));
      if (!retryable || attempt + 1 >= maxAttempts) throw error;
      if (status === 0) await delay(Math.min(4_000, 300 * (2 ** attempt)));
    }
  }
  throw lastError || new Error(`${provider} request failed.`);
}
