/** Read and parse a bounded JSON request without first copying an unbounded body into a string. */
export async function readJsonBody(req, limit = 64 * 1024) {
  const maxBytes = Number.isSafeInteger(limit) && limit >= 0 ? limit : 64 * 1024;
  const declaredHeader = req.headers?.['content-length'];
  const declared = declaredHeader == null ? NaN : Number(declaredHeader);
  if (Number.isFinite(declared) && declared > maxBytes) {
    req.resume?.();
    const error = new Error('Request too large');
    error.statusCode = 413;
    throw error;
  }

  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    const chunkBytes = Buffer.isBuffer(chunk) ? chunk.byteLength : Buffer.byteLength(String(chunk));
    if (chunkBytes > maxBytes - total) {
      req.resume?.();
      const error = new Error('Request too large');
      error.statusCode = 413;
      throw error;
    }
    const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    total += chunkBytes;
    chunks.push(bytes);
  }

  const raw = Buffer.concat(chunks, total).toString('utf8');
  try {
    return JSON.parse(raw || '{}');
  } catch {
    const error = new Error('Request body must be valid JSON');
    error.statusCode = 400;
    throw error;
  }
}
