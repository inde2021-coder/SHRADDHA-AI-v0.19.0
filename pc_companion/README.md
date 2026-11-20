# Shraddha AI Windows PC Companion

This is a separate Windows desktop companion for the Android Shraddha AI app. It joins the same HTTPS relay session and exposes only an allowlisted set of desktop actions.

## Pairing

1. On the Android phone, open **Device Universe** and create the main phone session.
2. Copy the six-digit pairing code (valid for five minutes).
3. Run `ShraddhaAI-PC-Companion.exe` on Windows.
4. Enter the same HTTPS relay URL, a PC device name and the pairing code, then choose **Join & Connect**.
5. On the PC, explicitly enable **Allow remote mouse / keyboard** and/or **Share this PC screen**.
6. On Android, select the Windows PC and request remote control. The PC displays a local approval dialog for every control session.

The PC device token is stored in Windows Credential Manager. The configuration file in %APPDATA% contains only the relay URL, session ID and device ID.

## Controls and safety

- Supported: normalized mouse click, drag/swipe, bounded text paste, Start Menu shortcut launch, show desktop, browser-style back and bounded scroll.
- Text-based click is currently unsupported on Windows; the agent reports this instead of guessing.
- The agent never executes arbitrary shell commands, PowerShell, scripts or file operations.
- Screen sharing is off by default and sends JPEG frames only while the local checkbox is enabled.
- Every remote-control session requires approval on the physical PC. The PC user can disable permission, click Disconnect, or press F12 while the companion window is focused.
- PyAutoGUI's top-left fail-safe is enabled.
- The companion does not capture microphone audio, read files, or silently send messages.

## Build from source

Requires Python 3.12 and Windows:

```powershell
py -3.12 -m pip install -r pc_companion/requirements.txt
py -3.12 -m unittest discover -s pc_companion/tests -v
py -3.12 -m PyInstaller --noconfirm --clean --onefile --windowed --name ShraddhaAI-PC-Companion --collect-all keyring pc_companion/agent.py
```

The Windows GitHub Actions workflow builds and publishes the EXE as a separate artifact. The PC companion is not part of the Android APK.


CI verification is run on the work branch for the companion's protocol/action unit tests and Windows executable packaging; this does not replace paired physical-device testing.
