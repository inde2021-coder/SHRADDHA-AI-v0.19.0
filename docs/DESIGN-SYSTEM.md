# SHRADDHA AI Design System v0.11.0

- Direction: cinematic 3D command cockpit, not a flat chat app.
- Base: midnight navy (#070B14); layered panels (#11182C, #1A2540).
- Accents: violet (#9F8BFF), electric blue (#9FBCFF), mint status (#69E7C0).
- Hero: large animated orb beside concise identity and voice action.
- Work proof: live workspace panel is empty/waiting until a real screen/task stream arrives.
- Navigation: named workspaces; no duplicate controls for the same action.
- Cards: 19–30dp corners, subtle edge stroke, restrained elevation, compact hierarchy.
- Accessibility: preserve readable contrast, tap targets, labels and reduced-motion option as a future preference.
- Content rule: never show fabricated "online", "working", percentage or task-complete indicators.


## Pending branch glass-depth pass — 22 November 2026

The shared `MainActivity.section()` surface now uses a three-layer navy gradient, brighter edge stroke, increased elevation and restrained title glow. Because the helper is shared, this updates the common card surface across the programmatic workspaces rather than styling only the Home screen. Existing action buttons already use a raised gradient treatment and inputs retain inset styling.

This is a foundation pass, not the completed requested redesign. Each workspace still needs its own information hierarchy, purpose-built controls, responsive layout, empty/loading/error states, motion behavior tied to real state, and accessibility review. The custom orb's decorative motion must never be presented as proof that the microphone or camera is active.


The shared input factory now also uses a dark inset gradient, a visible cool-blue edge, minimum touch height and restrained elevation. Together with the raised gradient action buttons and layered workspace panels, this establishes consistent depth across the programmatic screens. Individual screen composition and full motion/accessibility treatment remain open work.
