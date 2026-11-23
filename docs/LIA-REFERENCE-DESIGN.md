# LIA Reference Recording — UI Design Audit

## Source checked
- Main-branch asset: `reference/lia-app-recording/LIA-APP-REFERENCE.mov`
- File size: 18,075,102 bytes
- A CI storyboard job extracted 12 evenly distributed frames from the recording so the full recording could be reviewed without copying the large video into the Android APK.

## Visible design language
- Older LIA screens use a very light lavender background, rounded white/lavender cards, violet action icons, compact two-column quick-action tiles and a bottom navigation bar.
- The listening screen centers a small 3D planet with orbital rings and a short listening status. Mic, end-call and minimize controls sit below the orb.
- Settings are grouped by task: connections, voice/language, chats/memory, app settings, phone control and privacy/about.
- Voice settings include language selection and a speech-speed slider.
- Privacy screens use explicit confirmation for destructive clear-all actions.
- The final recording frames show a newer dark screen with a human avatar, a centered purple microphone/orb and a compact chat composer.

## Shraddha AI implementation direction
Preserve Shraddha AI's existing dark 3D identity and animated orb rather than copying the older flat UI. Apply the reference's information architecture:
1. One primary assistant home with the animated orb/assistant identity and clear listening/thinking/speaking state.
2. Two-column quick-action cards with purple icon tiles and short descriptions.
3. Settings grouped into clear categories rather than one long mixed list.
4. Dedicated full-screen voice mode with the orb and only essential mic/end/minimize actions.
5. Explicit preview and confirmation for publishing, messaging, remote control and destructive data actions.
6. Keep Hindi and English labels, large tap targets and vertical scrolling; never introduce horizontal overflow.

## Existing source observations
- `HomeScreen.kt` already has the dark assistant avatar, animated `LiaOrb3D`, live voice state, two-column feature rows and quick voice/chat actions.
- `QuickActionsScreen.kt` already uses a two-column grid of action cards.
- The next design work should refine spacing, icon consistency, section grouping and the dedicated listening screen; do not replace the current orb with a static screenshot or hard-code the reference video into the app.
