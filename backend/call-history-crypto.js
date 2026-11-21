import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';

const MAX_RECORD_BYTES = 64_000;
const AAD_PREFIX = 'shraddha-call-history-v1|';

function validateKey(key) {
  if (!Buffer.isBuffer(key) || key.length !== 32) throw new Error('Call-history encryption key must be exactly 32 bytes.');
}

export function encryptCallHistoryRecord(record, key) {
  validateKey(key);
  if (!record || typeof record !== 'object' || Array.isArray(record) ||
      typeof record.id !== 'string' || !/^[A-Za-z0-9_-]{1,120}$/.test(record.id)) {
    throw new Error('Call-history record identifier is invalid.');
  }
  const plaintext = Buffer.from(JSON.stringify(record), 'utf8');
  if (plaintext.length > MAX_RECORD_BYTES) {
    plaintext.fill(0);
    throw new Error('Call-history record exceeds the encrypted storage limit.');
  }
  const iv = randomBytes(12);
  try {
    const cipher = createCipheriv('aes-256-gcm', key, iv);
    cipher.setAAD(Buffer.from(AAD_PREFIX + record.id, 'utf8'));
    const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
    const tag = cipher.getAuthTag();
    return {
      version: 1,
      iv: iv.toString('base64'),
      tag: tag.toString('base64'),
      ciphertext: ciphertext.toString('base64')
    };
  } finally {
    plaintext.fill(0);
    iv.fill(0);
  }
}

export function decryptCallHistoryRecord(envelope, key, expectedId) {
  validateKey(key);
  if (!envelope || envelope.version !== 1 ||
      typeof expectedId !== 'string' || !/^[A-Za-z0-9_-]{1,120}$/.test(expectedId)) {
    throw new Error('Call-history encrypted envelope is invalid.');
  }
  const decode = (value, min, max) => {
    if (typeof value !== 'string' || value.length < min || value.length > max ||
        !/^[A-Za-z0-9+/]+={0,2}$/.test(value)) throw new Error('Call-history encrypted field is invalid.');
    return Buffer.from(value, 'base64');
  };
  const iv = decode(envelope.iv, 16, 16);
  const tag = decode(envelope.tag, 24, 24);
  const ciphertext = decode(envelope.ciphertext, 4, 90_000);
  if (iv.length !== 12 || tag.length !== 16 || ciphertext.length < 1 || ciphertext.length > MAX_RECORD_BYTES) {
    throw new Error('Call-history encrypted payload size is invalid.');
  }
  let plaintext;
  try {
    const decipher = createDecipheriv('aes-256-gcm', key, iv);
    decipher.setAAD(Buffer.from(AAD_PREFIX + expectedId, 'utf8'));
    decipher.setAuthTag(tag);
    plaintext = Buffer.concat([decipher.update(ciphertext), decipher.final()]);
    if (plaintext.length > MAX_RECORD_BYTES) throw new Error('Decrypted call-history record is too large.');
    const record = JSON.parse(plaintext.toString('utf8'));
    if (!record || typeof record !== 'object' || Array.isArray(record) || record.id !== expectedId) {
      throw new Error('Decrypted call-history record binding does not match.');
    }
    return record;
  } finally {
    iv.fill(0);
    tag.fill(0);
    ciphertext.fill(0);
    plaintext?.fill(0);
  }
}
