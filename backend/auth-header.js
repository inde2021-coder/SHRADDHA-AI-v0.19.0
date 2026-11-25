/** Extract a Bearer credential without accepting malformed or empty schemes. Raw tokens remain supported for internal callers. */
export function extractBearerToken(header) {
  if (typeof header !== 'string') return '';
  const value = header.trim();
  if (!value) return '';
  const match = /^Bearer\s+(.+)$/i.exec(value);
  if (match) return match[1].trim();
  if (/^Bearer(?:\s|$)/i.test(value)) return '';
  return value;
}
