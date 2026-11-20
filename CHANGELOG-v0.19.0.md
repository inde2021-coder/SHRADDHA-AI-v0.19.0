# v0.19.0 — Shraddha AI unified integration and reliability hardening

## Unified source
- Merged Shraddha AI Android main + direct source trees into the single SHRADDHA `:app` source set.
- Renamed the Shraddha AI launcher activity to `com.Lia.assistant.MainActivity` (internal Activity); SHRADDHA remains the only launcher/application ID.
- Added Compose compiler plugin and required Compose, Navigation, DataStore, WebKit and SplashScreen dependencies.
- Merged Shraddha AI foreground voice service, Accessibility service and share intents into SHRADDHA manifest.
- Added SHRADDHA home entry for the integrated Shraddha AI voice experience and Shraddha AI PC Companion.
- Kept Shraddha AI PC Companion systems as companion source under `desktop-companion/`.

## Firebase and cloud memory
- Added Firebase Realtime Database Android SDK and prefilled the supplied public Firebase app configuration.
- Added AES-256-GCM encrypted memory mirror to Firestore and Realtime Database with UID-scoped rules.
- Restore now falls back to Firestore when the RTDB copy is missing, unavailable or cannot decrypt.
- Partial cloud mirror failures are reported accurately instead of being shown as complete success.

## Voice and AI provider reliability
- Improved fatal microphone error cleanup, retry behavior, speaker AudioTrack recovery and foreground-service lifecycle.
- The voice foreground service now releases its notification/wake lock when the session becomes inactive and does not silently restart after process death.
- Updated Gemini Text Chat to prefer available stable Gemini 3.8 Flash models.
- Updated Groq default to `openai/gpt-oss-120b`, Claude default to `claude-sonnet-5`, and OpenAI default to `gpt-4.1-mini`.
- Migrated Perplexity chat to the Agent API and parse response messages/search sources.
- Clarified that the provider selector affects typed Chat; Voice Live remains Gemini Live.

## Device Operator and privacy
- Redacted password/PIN contents from both Accessibility screen readers and blocked typing into password fields.
- Added runtime-variable secret checks for learned workflows and credential checks for voice-generated SMS/WhatsApp drafts.
- Windows PC remote typing now blocks password/OTP/PIN/CVV-labelled content.
- Final Send/Pay/Checkout/Delete/Publish actions require confirmation in Android Operator.
- Accessibility coordinate taps are reported as dispatched rather than falsely marked completed.
- Removed broad `QUERY_ALL_PACKAGES`; launcher-app inventory uses declared package visibility queries.

## Visual Lens, screen share and PC companion
- Released screen-share frame bitmaps on success and failure; fail clearly if Android cannot create a virtual display.
- Built the separate Windows PC Companion executable with local PC approval, opt-in screen sharing and allowlisted mouse/keyboard actions.
- Added review-first Android Sharesheet support for social content; no unattended auto-publishing was added.

## Automated verification
- Android CI run #280: unit tests, backend tests, debug APK build, ARM ABI split verification and checksums passed.
- Windows PC Companion CI run #7: 13 Python tests and EXE packaging passed.
- Physical Android device, real provider/Firebase account, and release-signing tests remain separate requirements.

### UI / UX continuation — 2026-11-20
- Reworked Android launcher layout for status/navigation bar and keyboard insets.
- Bottom dock now moves out of the way while the keyboard is visible.
- Added workspace search across the launcher destinations.
- Simplified dashboard labels and removed redundant static system-status copy.
- Standardized native action buttons to compact single-line raised controls with ripple and accessible help text.
- Refined Compose button shape and touch size; cancelled gestures no longer trigger actions.
- Reduced unused bottom padding in Home and Settings; floating dock respects navigation-bar insets.
- Verification: Android CI #299 is running; no phone visual testing is claimed.
