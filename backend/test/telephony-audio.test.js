import test from 'node:test';
import assert from 'node:assert/strict';
import { resamplePcm16 } from '../telephony-audio.js';

function pcm(values) {
  const buffer = Buffer.alloc(values.length * 2);
  values.forEach((value, index) => buffer.writeInt16LE(value, index * 2));
  return buffer;
}
function values(buffer) {
  return Array.from({ length: buffer.length / 2 }, (_, index) => buffer.readInt16LE(index * 2));
}

test('upsamples 16 kHz PCM to 24 kHz using linear interpolation', () => {
  const result = resamplePcm16(pcm([0, 1000, 2000, 3000]), 16000, 24000);
  assert.deepEqual(values(result), [0, 667, 1333, 2000, 2667, 3000]);
});

test('downsamples 24 kHz PCM to 16 kHz using linear interpolation', () => {
  const result = resamplePcm16(pcm([0, 1000, 2000, 3000, 4000, 5000]), 24000, 16000);
  assert.deepEqual(values(result), [0, 1500, 3000, 4500]);
});

test('supports 8 kHz Edesy streams in both directions', () => {
  const up = resamplePcm16(pcm([0, 3000, 6000]), 8000, 24000);
  assert.deepEqual(values(up), [0, 1000, 2000, 3000, 4000, 5000, 6000, 6000, 6000]);
  const down = resamplePcm16(pcm([0, 1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000]), 24000, 8000);
  assert.deepEqual(values(down), [0, 3000, 6000]);
});

test('same sample rate returns an equal independent buffer', () => {
  const source = pcm([-32768, -1, 0, 1, 32767]);
  const result = resamplePcm16(source, 16000, 16000);
  assert.deepEqual(result, source);
  assert.notEqual(result, source);
});

test('empty input returns an empty buffer', () => {
  assert.equal(resamplePcm16(Buffer.alloc(0), 16000, 24000).length, 0);
});

test('rejects invalid rates and incomplete PCM16 samples', () => {
  assert.throws(() => resamplePcm16(pcm([1]), 0, 24000), RangeError);
  assert.throws(() => resamplePcm16(Buffer.from([1]), 16000, 24000), RangeError);
});
