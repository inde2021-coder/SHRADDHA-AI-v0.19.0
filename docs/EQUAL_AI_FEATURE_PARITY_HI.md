# Shraddha AI Call Assistant — Feature Parity and Release Checklist

This is an original Shraddha AI implementation plan informed by publicly documented Equal AI user-facing capabilities. It does not copy Equal AI code, assets, branding, or proprietary behavior.

## Current implementation status

| Capability | Status | Notes |
|---|---|---|
| Voice command to find a saved contact and start a normal SIM call | Partial | Hindi speech-recognition button, Contacts lookup, ambiguity-safe matching and TelecomManager call initiation are in the Android Phone Agent. It is not yet wired into the always-listening wake-word/tool pipeline. |
| Incoming call UI and basic call controls | Partial | Android InCallService can show call state and provide answer/end/mute/speaker controls when selected as the default dialer. |
| Personal SIM rings first, then forwards unanswered calls to Edesy DID | Configuration required | Carrier-side conditional no-answer forwarding; not controlled by the app. |
| Edesy bidirectional audio bridge to GPT-Live | Implemented in code, unverified live | 16 kHz mono L16 ↔ 24 kHz PCM16 bridge; requires provisioned DID, provider flow, backend secrets and real PSTN testing. |
| Caller identity metadata | Partial | Backend stores a masked caller number and caller name from Edesy metadata in a bounded in-memory call list. |
| Live transcript | Implemented behind opt-in, unverified live | Set CALL_TRANSCRIPTS_ENABLED=true only after consent/privacy review. |
| AI post-call summary | Implemented behind opt-in, unverified live | Generated from transcript using the server OpenAI chat API; summary remains in bounded process memory only. |
| Caller interruption / barge-in | Partial | Edesy killAudio triggers GPT-Live response.cancel; real-world interruption latency must be tested. |
| Audio recording | Not implemented | No audio files are captured or stored. Requires explicit notice/consent, encrypted durable storage, retention/deletion controls and legal review. |
| One-tap human takeover / transfer | Not implemented | Requires a supported Edesy transfer/bridging flow and Android notification/deep-link handoff. |
| Caller screening policies (known/unknown, selected categories, VIP allowlist/blocklist) | Not implemented | Needs a persistent policy model and carrier/backend enforcement, not just local UI toggles. |
| Custom caller instructions/messages | Partial | Global call instructions can be set server-side; per-call live message injection/UI is not wired. |
| Multiple Indian languages and language preferences | Partial | Hindi/English are requested in the Live agent prompt; per-user language preferences and reliable language switching need testing. |
| Call notifications and caller green-card / live call monitor | Not implemented | Needs Android notification channels, foreground lifecycle, live call state and transcript sync. |
| Durable call history and summaries | Not implemented | Current backend list is bounded memory and is lost on restart. Add authenticated encrypted database/Firebase persistence with retention controls. |
| WhatsApp summary delivery and post-call actions | Not implemented | Requires WhatsApp Cloud API credentials, recipient confirmation, template/policy checks and delivery receipts. |
| Pause/resume assistant and scheduled quiet hours | Not implemented | Must coordinate server call-routing state with carrier forwarding; Android toggle alone cannot disable carrier forwarding. |
| Assistant health check | Not implemented | Need checks for backend secrets, provider WebSocket reachability, DID route, forwarding status and a guided recovery UI. |
| Setup/onboarding, SIM selection, permissions and safe rollback | Partial | Default-dialer role exists; carrier setup and multi-SIM state need robust onboarding and rollback verification. |
| Outbound AI-initiated calls through a virtual DID | Not implemented | The current voice contact action starts a normal SIM call. Edesy outbound Calls API and call-leg correlation are a separate optional feature. |

## Release blockers before main merge

1. Finish Android voice command integration with the always-on Shraddha voice session; do not require a second mic tap once wake-word/voice mode is active.
2. Add a proper contact disambiguation dialogue for same-name contacts and multiple numbers.
3. Add persistent encrypted call records, transcript viewer, summary screen, notifications and export/delete controls.
4. Implement caller screening policy and human takeover using a supported carrier transfer flow.
5. Verify Edesy metadata, 16 kHz L16 frames, 24 kHz GPT-Live frames, barge-in, disconnect, reconnect, timeouts and error handling with real calls.
6. Verify Jio no-answer forwarding: owner handset rings first, answered calls remain local, unanswered calls reach the Edesy DID, and disabling forwarding restores normal routing.
7. Complete caller notice/consent, transcript/recording privacy, retention and data deletion design. Recording remains disabled until then.
8. Add unit/integration tests for contact parsing, call routing state, transcript assembly, summary failures, security, and provider protocol.
9. Run Android unit tests, debug APK build, emulator checks, then real-device tests on the user's Jio Android phone.
10. Only then mark PR ready and merge into main.

## Important implementation boundaries

- A normal third-party Android InCallService cannot freely capture/replace the carrier call's media audio. The SIM call must be forwarded at carrier level to the Edesy DID for AI conversation.
- The Android app must never auto-answer a call and claim AI is talking unless the carrier media bridge is actually active.
- Do not enable Jio forwarding until the Edesy DID and real inbound AI audio have passed test calls.
- Do not store call recordings or transcripts without clear notice, appropriate consent, encryption, retention and deletion controls.
- Current Edesy call records/transcripts/summaries are process-memory only; a backend restart clears them.
