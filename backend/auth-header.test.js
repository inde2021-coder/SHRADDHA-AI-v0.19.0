import test from 'node:test';
import assert from 'node:assert/strict';
import { extractBearerToken } from './auth-header.js';

test('extracts standard Bearer credentials with one or more spaces', () => {
  assert.equal(extractBearerToken('Bearer secret-token'), 'secret-token');
  assert.equal(extractBearerToken('bearer   secret-token'), 'secret-token');
  assert.equal(extractBearerToken('  Bearer secret-token  '), 'secret-token');
});

test('keeps raw internal tokens compatible', () => {
  assert.equal(extractBearerToken('secret-token'), 'secret-token');
});

test('rejects missing, empty and non-string credentials', () => {
  assert.equal(extractBearerToken(undefined), '');
  assert.equal(extractBearerToken(null), '');
  assert.equal(extractBearerToken(''), '');
  assert.equal(extractBearerToken('Bearer'), '');
  assert.equal(extractBearerToken('Bearer   '), '');
});
