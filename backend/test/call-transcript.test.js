import test from 'node:test';
import assert from 'node:assert/strict';
import {
  appendCallTranscript,
  clearCallTranscript,
  hasExplicitTranscriptConsentPrompt,
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

test('transcript consent detection is explicit and supports Hindi and English', () => {
  assert.equal(hasExplicitTranscriptConsentPrompt('Please press 1 if you consent to saving a text transcript.'), true);
  assert.equal(hasExplicitTranscriptConsentPrompt('ट्रांसक्रिप्ट सेव करने की सहमति है तो प्रेस 1 दबाएं।'), true);
  assert.equal(hasExplicitTranscriptConsentPrompt('Press 1 to continue the call.'), false);
  assert.equal(hasExplicitTranscriptConsentPrompt('We may save a transcript if you agree.'), false);
  assert.equal(hasExplicitTranscriptConsentPrompt('press 1 to save the transcript'), false);
  assert.equal(hasExplicitTranscriptConsentPrompt(null), false);
});
