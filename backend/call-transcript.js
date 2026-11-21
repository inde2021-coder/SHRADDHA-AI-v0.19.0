export const MAX_CALL_TRANSCRIPT_CHARS = 24_000;
export const MAX_CALL_TRANSCRIPT_CHUNK_CHARS = 2_000;

/** Append consented transcript text with strict per-chunk and per-call memory bounds. */
export function appendCallTranscript(callRecord, speaker, delta, at = new Date().toISOString()) {
  if (!callRecord || !Array.isArray(callRecord.transcript) ||
      !['caller', 'assistant'].includes(speaker) || typeof delta !== 'string') return 0;
  const current = Number.isInteger(callRecord.transcriptChars) && callRecord.transcriptChars >= 0
    ? callRecord.transcriptChars : 0;
  const remaining = Math.max(0, MAX_CALL_TRANSCRIPT_CHARS - current);
  const chunk = delta.slice(0, Math.min(MAX_CALL_TRANSCRIPT_CHUNK_CHARS, remaining));
  if (!chunk) return 0;
  callRecord.transcript.push({ speaker, text: chunk, at });
  callRecord.transcriptChars = current + chunk.length;
  return chunk.length;
}

export function clearCallTranscript(callRecord) {
  if (!callRecord) return;
  callRecord.transcript = [];
  callRecord.transcriptChars = 0;
}
