import test from 'node:test';
import assert from 'node:assert/strict';
import { hasExplicitTranscriptConsent } from './telephony-consent.js';

test('accepts affirmative provider IVR opt-in', () => {
  assert.equal(hasExplicitTranscriptConsent({ transcription_consent: true, consent_source: 'provider_ivr' }), true);
});

test('rejects missing or negative consent', () => {
  assert.equal(hasExplicitTranscriptConsent({ consent_source: 'provider_ivr' }), false);
  assert.equal(hasExplicitTranscriptConsent({ transcription_consent: false, consent_source: 'provider_ivr' }), false);
});

test('rejects consent asserted by an untrusted source', () => {
  assert.equal(hasExplicitTranscriptConsent({ transcription_consent: true }), false);
  assert.equal(hasExplicitTranscriptConsent({ transcription_consent: true, consent_source: 'client' }), false);
  assert.equal(hasExplicitTranscriptConsent(null), false);
  assert.equal(hasExplicitTranscriptConsent([]), false);
});
