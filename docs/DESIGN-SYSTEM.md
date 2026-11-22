# SHRADDHA AI Design System v0.11.0

- Direction: cinematic 3D command cockpit, not a flat chat app.
- Base: midnight navy (#070B14); layered deep-teal panels (#0C202D, #102B37, #173540).
- Accents: luminous cyan (#68E7EA), aqua (#8CE8E7), cool mint status (#69E7C0).
- Hero: large animated orb beside concise identity and voice action.
- Work proof: live workspace panel is empty/waiting until a real screen/task stream arrives.
- Navigation: named workspaces; no duplicate controls for the same action.
- Cards: 19–30dp corners, subtle edge stroke, restrained elevation, compact hierarchy.
- Accessibility: preserve readable contrast, tap targets, labels and reduced-motion option as a future preference.
- Content rule: never show fabricated "online", "working", percentage or task-complete indicators.


## Pending branch glass-depth pass — 22 November 2026

The shared `MainActivity.section()` surface now uses a three-layer deep-teal gradient, cyan edge stroke, increased elevation and restrained aqua title glow. Because the helper is shared, this updates the common card surface across the programmatic workspaces rather than styling only the Home screen. Existing action buttons already use a raised gradient treatment and inputs retain inset styling.

This is a foundation pass, not the completed requested redesign. Each workspace still needs its own information hierarchy, purpose-built controls, responsive layout, empty/loading/error states, motion behavior tied to real state, and accessibility review. The custom orb's decorative motion must never be presented as proof that the microphone or camera is active.


The shared input factory now also uses a dark inset gradient, a visible cool-blue edge, minimum touch height and restrained elevation. Together with the raised gradient action buttons and layered workspace panels, this establishes consistent depth across the programmatic screens. Individual screen composition and full motion/accessibility treatment remain open work.


The user's accepted design direction is dark teal + cyan Premium Glass, not a purple/violet-first theme. Shared cards, inset inputs, raised actions and the Home energy orb now follow the teal/cyan palette while preserving the existing screens and functionality.


The native Home/Assistant 3D energy core now observes the real voice session state and microphone amplitude. Listening, thinking, speaking, connecting, idle and error states have different pulse/rotation behavior; mute suppresses the listening pulse, and accessibility text reports the real state rather than implying the microphone is active. The visual animation remains decorative when idle and does not imply camera activity.
