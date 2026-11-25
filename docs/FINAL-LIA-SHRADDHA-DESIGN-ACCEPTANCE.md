# Unified LIA + Shraddha AI product decision

## Product identity and feature-preservation rule
- The single product, app name, assistant identity, and user-facing brand are **Shraddha AI**. There is no separate LIA AI product or LIA identity in the finished app.
- **All LIA AI features and workflows must be retained and incorporated into Shraddha AI.** LIA is not merely a visual theme/reference; its functional capabilities, screens, interactions, and user workflows are part of the required product scope.
- **All existing Shraddha AI features and workflows must also be retained.** Neither product's capabilities may be removed, stubbed, hidden, downgraded, or silently disabled to simplify integration or redesign.
- The final app is one unified assistant: LIA AI capabilities + Shraddha AI capabilities, under the Shraddha AI name and identity. Shared/overlapping capabilities should be integrated into one coherent implementation rather than duplicated; preserve the full union of behavior and user-accessible actions.
- Apply the LIA visual design and interaction patterns across the unified Shraddha AI experience, while retaining Shraddha AI branding and assistant identity. Keep all providers, execution modules, permissions, backend/APK configuration modes, routes, and workflows from both feature sets.
- Any capability not yet ported or verified must be tracked as outstanding work, not described as complete. Do not infer that a feature is implemented merely because a screen, route, button, or design reference exists.

## Main-branch source audit (2026-11-25)
The following implementation is already present in main and should be preserved:
- `NovaApp.kt` applies `LiaReferenceTheme` and retains the existing NavHost routes for Home, Chat, History, Settings, API Keys, Voice, Visual Lens, AR Mapping, Offline Model, Smart Home, Quick Actions, Profile, Personality, Orb Style, Privacy, Permissions, About, Debug, Forge, Agent Council, Phone Call Agent, Device Center, Expert Studio, Engineering Workbench, Project Lab, Data Vault, Plugins, and Social Publisher.
- `QuickActionsScreen.kt` contains real navigation/action callbacks, including voice, chat, Visual Lens, PC/device center, app operator, Expert Studio, Project Lab, Data Vault, Forge, and Agent Council. Keep the two-column reference layout and all existing actions.
- `SettingsScreen.kt` uses the LIA reference palette and retains the existing grouped settings, voice/gateway, memory, privacy, provider and assistant configuration controls.
- `HomeScreen.kt` retains the Shraddha assistant identity, responsive hero sizing based on available screen height and selected home design, live voice-session state/amplitude, animated orb, and command composer routed to the mobile command center.
- `Color.kt` and `Depth.kt` contain the LIA lavender palette and matching light background treatment used by the reference surfaces.

## Non-regression requirements
1. Do not change feature behavior or route destinations as part of visual alignment.
2. Keep Hindi/English content, vertical scrolling, accessible labels, adequate touch targets, and no horizontal overflow.
3. Hero media must fit the available screen bounds responsively without clipping the subject. Preserve the existing selectable home-design behavior.
4. Voice/listening/thinking/speaking indicators must continue to reflect actual service/session state; decorative animation must not imply microphone or camera activation.
5. Keep explicit confirmation for messaging, publishing, remote control, and destructive actions.
6. Keep Backend Mode and user-owned APK API-key mode distinct; never embed publisher secrets in the APK.
7. Do not claim physical-device validation from CI. OPPO Android 15 visual/runtime validation remains a separate device check.

## Asset integration note
The current repository has vector launcher resources and existing Shraddha avatar/hero assets. The newly supplied full transparent SHRADDHA AI logo image was not present as an accessible binary asset during this source audit. Do not substitute a guessed or recreated image. Add it to launcher and in-app branding only after the exact transparent PNG is committed, preserving the full artwork and alpha channel.

## Verification status at audit time
The main-branch GitHub Actions runs for Android build/tests, integration consolidation/full verification, and Branch CI at commit `2a8f565404db0125311405ed8077dd64f419ca0e` completed successfully on 2026-11-25. This confirms repository CI at that commit, not a physical OPPO device test.

## Merge scope
This document records the accepted product/design boundary and source audit only. It does not claim that a new UI implementation, logo asset replacement, user-selectable gallery hero, or physical-device test has been completed.
