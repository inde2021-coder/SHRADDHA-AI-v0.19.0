# Shraddha AI UI acceptance baseline

## Identity
- The visible product identity is **Shraddha AI** only.
- The launcher, About screen, navigation, share target and PC companion use Shraddha AI labels.
- Legacy Kotlin package/class names are implementation details and must never appear as visible product copy.

## Visual system
- App-wide default is the dark premium palette: background #090D16, raised surfaces #1B2638, violet accent #B69CFF and cyan accent #78D6E8.
- Use the shared Nova spacing, typography, depth and color tokens. Avoid one-off page backgrounds that contradict the active theme.
- Use glass surfaces with restrained borders and shadows; avoid oversized blocks and repeated explanatory paragraphs.
- Keep primary actions visually distinct, secondary actions compact, and destructive actions explicitly confirmed.
- Preserve accessibility contrast, touch target size, reduced-motion support and Android system bars.

## Navigation and density
- Main navigation has Home, Chat, Voice, History and Settings only.
- Advanced tools remain inside their relevant grouped screens instead of becoming additional permanent tabs.
- Device Center and Visual Lens should prioritize pairing/voice or camera/voice workflows; secondary controls stay subordinate.

## About and release metadata
- About shows the configured Shraddha AI name, version/build, developer details and copyright.
- Capability descriptions must distinguish implemented, optional, permission-gated and not-yet-verified features.

## Current branch changes
- Main app now enters through `ShraddhaTheme` using the dark palette.
- `nightSky()` uses the same dark palette instead of a fixed lavender background.
- Main bottom navigation uses the Shraddha-branded component name.
- About screen title is consistent with Shraddha AI.
