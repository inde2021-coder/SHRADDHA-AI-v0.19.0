# SHRADDHA AI v0.11.0 — Feature and integration matrix

| Area | Included in source | Runtime status / dependency |
|---|---|---|
| Premium command-centre UI | Yes | Android home screen updated; verify on physical device |
| Animated agent orb | Yes | Decorative animation; microphone state is not inferred from it |
| Lia Android UI/voice source | Preserved under integrations/lia-android-source | Not merged into main Shraddha APK; requires package/dependency reconciliation |
| MYRA desktop/Linux/Termux source | Preserved under companions/myra-desktop-source | Runs as separate companion; OS-specific packaging and secure pairing still required |
| Firebase Auth + encrypted Firestore memory | Yes | Requires user's Firebase project configuration and live rules/deployment test |
| Firebase Storage + FCM | SDK dependencies may be present | Upload and push notification workflows are not yet wired end-to-end |
| Live App Operator | Main Android source present | Accessibility permission and supported UI; test on target device |
| Cross-device remote control | Source present | Requires relay, pairing, online companion, consent and network |
| Kids Universe | Source present | Educational journal/activities; not medical diagnosis or infant surveillance |
| Person Memory | Source present | Profile memory; biometric face/voice matching remains separate future integration |
| AI providers | Provider/relay configuration paths | Requires valid provider credentials, quotas and backend setup |
| Offline mode | Local command/knowledge modules | Limited command set; not an unrestricted offline LLM |

## Future-ready modules recommended
1. Secure plugin/connector framework with permission scopes and signed updates.
2. Durable workflow engine with pause/resume, retries, audit trail and undo where possible.
3. Personal knowledge graph with user-visible memory review/export/delete.
4. Cross-device encrypted sync queue and conflict resolution.
5. On-device model manager with hardware-aware model selection.
6. Accessibility-first UI, Hindi/English voice, captions and reduced-motion mode.
7. Security centre: device sessions, key rotation, app integrity, permission timeline and incident alerts.
8. Family/kids safety controls with parent PIN, age profiles, screen-time controls and offline learning packs.
9. Project workspace: requirements, milestones, files, tests, deployments and rollback.
10. Data portability: encrypted export, restore validation and account deletion workflow.

## Release gate
Do not label a capability complete until it passes build, unit/integration tests, permission-denial tests, network-loss/reconnect tests, privacy/security review and real-device validation. Android permissions, third-party provider limits and companion availability remain platform constraints.
