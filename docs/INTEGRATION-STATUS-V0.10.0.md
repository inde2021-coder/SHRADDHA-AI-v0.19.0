# Integration status — v0.10.0

| Area | Status in this bundle | Evidence / limit |
|---|---|---|
| Shraddha Android base | Included | Existing Kotlin app, operator, device link, expert hub, Kids Universe and Person Memory sources |
| Firebase Authentication | Implemented in source | Email/password create + sign-in; requires user's own Firebase project config |
| Firestore encrypted memory vault | Implemented in source | Client-side AES-GCM; PBKDF2 key from password; owner-scoped rules |
| Firebase Storage | SDK + starter rules only | Upload/download UI and transfer worker not implemented |
| Firebase Cloud Messaging | SDK only | Token registration and notification handler not implemented |
| Lia voice/chat/orb | Source included, not merged | Separate Compose app, package and flavor architecture |
| Lia app actions / Accessibility | Source included, not merged | Requires careful service/manifest merge and device tests |
| Lia Website Forge | Source included, not merged | Requires provider endpoint/key configuration and UI integration |
| MYRA Windows/Linux desktop | Source included as companion | Runs separately; no paired Shraddha-to-MYRA bridge shipped here |
| MYRA Coder / WebForge | Source included | Requires Node/Python dependencies and user's provider/deploy tokens |
| MYRA face authentication | Source included but Firebase config sanitized | Must configure own project; no biometric integration into Shraddha |
| Face recognition in Shraddha | Not implemented | Consent-based profile directory only; no face embedding/matching model |
| Voice speaker recognition in Shraddha | Not implemented | No voiceprint enrollment/matching model |

## Validation performed
- Backend `node --check`: passed.
- MYRA Python `compileall`: passed.
- Android manifest/resource XML parse: passed.
- Upstream source trees copied and kept separate to avoid package/manifest/resource collisions.
- Android Gradle build, APK install, Firebase live sign-in, Firestore rules emulator tests, and physical device tests: not run. This environment has no Android SDK and no installed Gradle executable; Firebase credentials were not supplied.
