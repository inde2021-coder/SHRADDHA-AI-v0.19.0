# Shraddha AI call history and consent controls

## Implemented behavior

- Native SIM-call UI is a Telecom InCallService control surface for answer, decline/end, mute and speaker routing. It does not claim to intercept normal SIM-call audio or act as an AI caller.
- Edesy inbound-call assistant is a separate carrier/provider Media Stream integration. The caller must be told that they are speaking with an AI assistant.
- Audio is streamed live for the call session and is not written as a recording. Persistent call recording is disabled.
- Call metadata and any permitted transcript/summary are stored in Firestore as AES-256-GCM encrypted envelopes. The provider call ID is encrypted inside the envelope; Firestore document IDs are SHA-256 hashes.
- Call history is limited to the newest 30 records in the Android client/API, and expired records are excluded after 90 days.
- Transcript collection and AI summaries require both CALL_TRANSCRIPTS_ENABLED=true and explicit provider-IVR consent metadata: transcription_consent=true and consent_source=provider_ivr.
- Without that explicit consent, transcript text is not collected and summaries remain disabled.
- Transcript data is bounded to 24,000 characters per call and 2,000 characters per event chunk.
- Call history API requires the backend gateway bearer token. If durable encrypted storage is unavailable, the API marks it unavailable and does not silently claim temporary in-memory data is durable.

## Required backend configuration

- FIREBASE_SERVICE_ACCOUNT_JSON: service account with access to the configured Firestore database.
- CALL_HISTORY_ENCRYPTION_KEY: base64 encoding of exactly 32 cryptographically random bytes. Keep this secret separate from the Firebase service account and never commit it.
- GATEWAY_TOKEN: high-entropy gateway bearer token.
- EDESY_STREAM_TOKEN, EDESY_API_KEY, EDESY_STREAM_URL, PUBLIC_ORIGIN, and OPENAI_API_KEY for the carrier/live-AI integration.
- Set CALL_TRANSCRIPTS_ENABLED=true only after the provider IVR is configured to capture explicit opt-in and pass the trusted consent metadata described above.

## Firestore retention

Configure Firestore TTL on the encryptedCallHistory.expiresAt timestamp field in the Firebase project. The API also excludes records whose expiry timestamp has passed; TTL is the background physical deletion mechanism. Without enabling this TTL policy in Firebase Console, expired encrypted documents can remain physically stored.

## Validation and external acceptance

- Automated backend tests cover key validation, AES-GCM encrypted storage, ID privacy, tamper rejection, expiry filtering, bounded list behavior, and explicit Hindi/English transcript-consent parsing.
- Provider configuration, Firebase credentials/Firestore TTL policy, carrier DID, IVR consent flow, real inbound/outbound calls, and two-way audio require owner-controlled accounts and real phone validation.
- No call audio recording, hidden call monitoring, or automatic SIM audio interception is implemented or claimed.
