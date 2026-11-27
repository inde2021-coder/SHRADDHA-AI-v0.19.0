# Permission dashboard: GPS and device pairing

- AKKPS edge-walk needs foreground precise location only while the user starts the walk. The app does not request background location.
- Bluetooth Connect is shown only on Android 12+ and is relevant when the user chooses Bluetooth for PC/device pairing. Internet/Wi-Fi pairing does not require it.
- A permission being granted does not mean GPS hardware/provider is enabled; the AKKPS Runner still checks GPS provider availability at start.
- The dashboard refreshes on resume so changes made in Android Settings are reflected.
