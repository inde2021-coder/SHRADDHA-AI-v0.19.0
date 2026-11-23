# Shraddha AI Android Mobile Hardware Controls

The Mobile Command Center now exposes a phone-local hardware/system control surface, separate from the Windows PC Hardware Agent.

## Direct controls
- Media volume percentage (0–100), using Android AudioManager.
- Display brightness percentage (0–100), only after the user grants Android's special Modify System Settings access.
- Rear/available camera flash torch on/off, only after runtime Camera permission and only where a flash unit exists.
- Battery percentage, charging state, battery temperature, battery health code and Android thermal status where the OS exposes it.
- RAM and internal storage capacity/free space.
- Device model, Android version, current media volume, current brightness, Wi-Fi/Bluetooth state when readable, flash availability and brightness permission state.
- Existing media play/pause/next/previous commands remain available.

## System-owned controls
The app opens Android's own settings panel/page for Wi-Fi, Bluetooth, internet/SIM, NFC, display/auto-rotate/screen timeout, sound, battery saver, location, Do Not Disturb, accessibility, notification access, apps, storage, date/time, language and security.

## Platform restrictions (intentional)
- Android 10+ does not allow ordinary apps to silently toggle Wi-Fi; Android 13+ also restricts programmatic Bluetooth enable/disable. Shraddha opens OS-owned panels instead of bypassing those restrictions.
- Airplane mode, mobile data, hotspot, GPS/location master switch, NFC and Do Not Disturb may require system UI, special access, device-owner privileges or OEM support. No root/hidden API is used.
- Brightness writes require explicit special access. Camera permission is used for flashlight only when requested.
- CPU temperature, fan speed, battery health diagnostics and detailed GPU telemetry are not uniformly exposed to third-party Android apps. Unavailable values are not fabricated.
- Android settings panels differ by OEM/version; if a panel is missing, the command center falls back to general Settings.
- These phone-local controls do not grant remote control of another person's device. Remote device pairing remains separately permissioned and authenticated.

## Voice examples
- "Set phone volume 35"
- "Set phone brightness 60"
- "Flashlight on" / "Flashlight off"
- "Mobile hardware status"
- "Wi-Fi settings", "Bluetooth settings", "Location settings", "Battery settings", "Display settings"
