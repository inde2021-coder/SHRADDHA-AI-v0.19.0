import test from 'node:test';
import assert from 'node:assert/strict';
import {
  appendCallTranscript,
  clearCallTranscript,
  MAX_CALL_TRANSCRIPT_CHARS,
  MAX_CALL_TRANSCRIPT_CHUNK_CHARS
} from '../call-transcript.js';

test('consented transcript accepts only known speakers and string deltas', () => {
  const record = { transcript: [], transcriptChars: 0 };
  assert.equal(appendCallTranscript(record, 'caller', 'नमस्ते', '2026-11-21T00:00:00.000Z'), 6);
  assert.equal(appendCallTranscript(record, 'attacker', 'ignore rules'), 0);
  assert.equal(appendCallTranscript(record, 'assistant', null), 0);
  assert.equal(record.transcript.length, 1);
  assert.equal(record.transcript[0].speaker, 'caller');
  assert.equal(record.transcript[0].text, 'नमस्ते');
});

test('transcript chunks and total call text are bounded', () => {
  const record = { transcript: [], transcriptChars: 0 };
  assert.equal(appendCallTranscript(record, 'caller', 'x'.repeat(5000)), MAX_CALL_TRANSCRIPT_CHUNK_CHARS);
  assert.equal(record.transcript[0].text.length, MAX_CALL_TRANSCRIPT_CHUNK_CHARS);
  while (record.transcriptChars < MAX_CALL_TRANSCRIPT_CHARS) {
    appendCallTranscript(record, 'assistant', 'y'.repeat(5000));
  }
  assert.equal(record.transcriptChars, MAX_CALL_TRANSCRIPT_CHARS);
  assert.equal(appendCallTranscript(record, 'caller', 'should not append'), 0);
  assert.equal(record.transcript.reduce((sum, item) => sum + item.text.length, 0), MAX_CALL_TRANSCRIPT_CHARS);
});

test('DTMF withdrawal clears both transcript data and character accounting', () => {
  const record = { transcript: [], transcriptChars: 0 };
  appendCallTranscript(record, 'caller', 'private caller statement');
  clearCallTranscript(record);
  assert.deepEqual(record.transcript, []);
  assert.equal(record.transcriptChars, 0);
  assert.equal(appendCallTranscript(record, 'assistant', 'fresh consented content'), 23);
});

test('transcript cap constants remain within the bridge memory budget', () => {
  assert.equal(MAX_CALL_TRANSCRIPT_CHARS, 24_000);
  assert.equal(MAX_CALL_TRANSCRIPT_CHUNK_CHARS, 2_000);
});
