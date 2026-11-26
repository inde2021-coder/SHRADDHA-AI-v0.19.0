import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeVoiceRequest, synthesizeShraddhaPcm } from './voice-synthesis.js';

test('normalizes Hindi and English voice requests', () => {
  assert.deepEqual(normalizeVoiceRequest({ text: ' नमस्ते ', language: 'hi' }), { text: 'नमस्ते', language: 'hi' });
  assert.deepEqual(normalizeVoiceRequest({ text: 'Hello', language: 'fr' }), { text: 'Hello', language: 'en' });
});

test('rejects empty and oversized speech text', () => {
  assert.throws(() => normalizeVoiceRequest({ text: ' ' }));
  assert.throws(() => normalizeVoiceRequest({ text: 'x'.repeat(6001) }));
});

test('does not silently use a provider when custom voice is missing', async () => {
  await assert.rejects(() => synthesizeShraddhaPcm({ text: 'hello' }, {}, async () => {
    throw new Error('must not call upstream');
  }), /SHRADDHA_TTS_URL is not configured/);
});

test('requires HTTPS and returns validated PCM', async () => {
  await assert.rejects(() => synthesizeShraddhaPcm({ text: 'hello' }, { SHRADDHA_TTS_URL: 'http://localhost:5000/tts' }), /HTTPS/);
  await assert.rejects(
    () => synthesizeShraddhaPcm({ text: 'hello' }, { SHRADDHA_TTS_URL: 'https://tts.example/synthesize' }),
    /SHRADDHA_TTS_TOKEN/
  );
  const audio = Buffer.from([1, 0, 2, 0]);
  const result = await synthesizeShraddhaPcm({ text: 'hello', language: 'en' }, {
    SHRADDHA_TTS_URL: 'https://tts.example/synthesize',
    SHRADDHA_TTS_TOKEN: 'a-strong-test-token-with-more-than-16'
  }, async (_url, options) => {
    assert.equal(JSON.parse(options.body).voice, 'shraddha');
    assert.equal(JSON.parse(options.body).sample_rate, 24000);
    assert.equal(options.headers.authorization, 'Bearer a-strong-test-token-with-more-than-16');
    return { ok: true, headers: { get: () => 'audio/pcm' }, arrayBuffer: async () => audio };
  });
  assert.deepEqual(result, audio);
});
