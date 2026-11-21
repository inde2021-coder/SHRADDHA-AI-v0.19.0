export function hasExplicitTranscriptConsent(metadata) {
  return Boolean(metadata && typeof metadata === 'object' && !Array.isArray(metadata) && metadata.transcription_consent === true && metadata.consent_source === 'provider_ivr');
}
