export function resamplePcm16(input, fromRate, toRate) {
  if (!Buffer.isBuffer(input)) input = Buffer.from(input);
  if (!Number.isFinite(fromRate) || !Number.isFinite(toRate) || fromRate <= 0 || toRate <= 0) {
    throw new RangeError('Sample rates must be positive finite numbers.');
  }
  if (input.length % 2 !== 0) throw new RangeError('PCM16 input must contain complete 16-bit samples.');
  const count = input.length / 2;
  if (count === 0) return Buffer.alloc(0);
  if (fromRate === toRate) return Buffer.from(input);
  const outCount = Math.max(1, Math.floor(count * toRate / fromRate));
  const out = Buffer.allocUnsafe(outCount * 2);
  for (let i = 0; i < outCount; i += 1) {
    const position = i * fromRate / toRate;
    const left = Math.min(count - 1, Math.floor(position));
    const right = Math.min(count - 1, left + 1);
    const fraction = position - left;
    const value = input.readInt16LE(left * 2) * (1 - fraction) + input.readInt16LE(right * 2) * fraction;
    out.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(value))), i * 2);
  }
  return out;
}
