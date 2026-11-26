# Shraddha AI Windows PC Companion

This is a separate Windows desktop companion for the Android Shraddha AI app. It joins the same HTTPS relay session and exposes only an allowlisted set of desktop actions.

## Pairing

1. On the Android phone, open **Device Universe** and create the main phone session.
2. Copy the six-digit pairing code (valid for five minutes).
3. Run `ShraddhaAI-PC-Companion.exe` on Windows.
4. Enter the same HTTPS relay URL, a PC device name and the pairing code, then choose **Join & Connect**.
5. On the PC, explicitly enable **Allow remote mouse / keyboard** and/or **Share this PC screen**.
6. On Android, select the Windows PC and request remote control. The PC displays a local approval dialog for every control session and a separate confirmation showing the exact action payload before each remote action executes.

The PC device token is stored in Windows Credential Manager only after the relay accepts the authenticated WebSocket connection. An HTTP pairing response alone is not treated as proof of a valid pairing. The configuration file in %APPDATA% contains only the relay URL, session ID and device ID.

## Controls and safety

- Supported: normalized mouse click, drag/swipe, bounded text paste, Start Menu shortcut launch, show desktop, browser-style back and bounded scroll.
- **Text-based click:** exact, unique visible Button/Hyperlink/Tab/Menu/CheckBox/RadioButton controls can be activated through Windows UI Automation. Ambiguous labels, hidden/disabled controls and unknown/sensitive active windows fail closed.
- **Active-window OCR:** `read screen` / `स्क्रीन पढ़ो` captures only the current non-sensitive window and runs local Tesseract OCR. OCR text is redacted for credential labels and long number strings before being returned over the encrypted peer channel. It is never silently uploaded to a cloud OCR provider.
- **PC file browser:** Android can request a folder listing or preview a text file under the signed-in Windows user folder. Paths must be relative; parent traversal, hidden folders, credential-related folders, symlinks, and paths outside the user folder are blocked. Listings are capped at 100 entries. Preview is limited to UTF-8 text formats and files up to 128 KB (12,000 characters returned); binary files are never previewed. Each list/read request receives its own exact local Windows approval.
- **PC filename search:** Android can search names under the signed-in Windows user folder or a selected safe subfolder. Search is limited to five directory levels, 500 folders, 5,000 files, 40 matches, and two seconds; hidden folders, symlinks, and credential-related paths are skipped. Search returns filenames and relative paths only, and requires its own exact Windows approval.
- `pytesseract` is included in the companion build. For one-step Windows setup, open PowerShell in the extracted release folder and run `powershell -ExecutionPolicy Bypass -File .\\setup-ocr.ps1`. The script installs the Tesseract engine through Windows Package Manager (winget) if needed, then downloads the official English (`eng`) and Hindi (`hin`) fast trained-data files. It does not run automatically or elevate itself. The companion auto-detects the standard Tesseract install path; for a custom install, set `SHRADDHA_TESSERACT_CMD` to the full `tesseract.exe` path. If only one language pack is installed, OCR falls back to it and reports the missing language.
- The agent never executes arbitrary shell commands, PowerShell, scripts or file operations.
- **Revoke PC Pairing** asks for explicit confirmation, revokes this device on the relay, and deletes its Windows Credential Manager token. A new six-digit code is required to pair again.
- Screen sharing is off by default and sends JPEG frames only while the local checkbox is enabled.
- Android and Windows now exchange P-256 ECDH public keys through the authenticated relay, derive a session- and peer-bound AES-256-GCM key, and require both users to confirm the same short fingerprint out-of-band before visual sharing. Screen/camera frames use per-peer ciphertext envelopes with monotonically increasing sequence numbers; the relay forwards opaque ciphertext and rejects plaintext JPEG frames. A peer that is not mutually verified receives no visual frames.
- Fingerprint confirmation is not automatic identity proof: compare the exact fingerprint on both devices through a separate trusted channel. If it differs or cannot be checked, reject the peer. Keys are ephemeral and must be verified again after reconnect/restart; the relay does not persist private keys.
- This protocol is source-integrated but still requires Android↔Windows physical-device verification, replay/tamper tests and reconnect tests before production use. WSS alone is not end-to-end privacy.
- Every remote-control session requires approval on the physical PC, and every individual remote action displays its exact validated operation for a separate local Yes/No confirmation. The PC user can disable permission, click Disconnect, or press F12 while the companion window is focused.
- The PC user can choose the virtual desktop (all displays) or an individual monitor for screen sharing and normalized mouse coordinates. The selected monitor is stored locally; changing it affects the next capture/action.
- Android keyboard shortcuts use a strict allowlist (Enter, Escape, Tab, Ctrl+C/F/Z/S/A/V/X, Alt+Tab/Left/Right and Shift+Tab). Arbitrary key names and shell commands are rejected.
- Remote action IDs are deduplicated per peer for a bounded ten-minute replay window; the Windows companion persists the bounded receipt ledger so restarting the app does not immediately reopen replay of accepted actions. If receipt persistence fails, the remote action is rejected. Encrypted receipts carry explicit success/failure state.
- PyAutoGUI's top-left fail-safe is enabled.
- File transfer is bidirectional: use **Send File to Phone** on Windows or **CHOOSE FILE & SEND** on Android. The receiving device must approve the transfer and choose its save location; chunks are E2E encrypted and the final file is SHA-256 verified. The Android transfer screen stores chunk checkpoints and offers Resume / Retry after reconnect.\n- The companion does not capture microphone audio, read files, or silently send messages.

## PC hardware agent

The Windows companion includes a separate, allowlisted hardware adapter:

- **Telemetry:** CPU utilization/core count, RAM, battery/charging, mounted disk usage and monitor dimensions.
- **Optional GPU telemetry:** NVIDIA name, temperature, utilization and VRAM via the fixed `nvidia-smi` query when installed.
- **Controls:** Windows master volume and supported display brightness (driver/API dependent).
- **Power:** shutdown, restart and sleep are accepted only after a separate confirmation dialog on the physical Windows PC.
- **Unavailable sensors:** CPU temperature and fan speed are not fabricated; they remain unavailable until a supported vendor/LibreHardwareMonitor adapter is configured.
- **Safety:** no arbitrary PowerShell, shell command, process launch or raw hardware register access is exposed. Remote hardware commands are strictly validated, remain behind the PC remote-control permission, and destructive power actions require local confirmation.

Hardware status is also available from the PC companion's **PC Hardware Status** button. The volume and brightness quick controls are local to the Windows PC. The Android-to-PC voice/UI command surface still needs a paired Android build and physical-device verification before remote hardware control can be considered complete.

## Build from source

Requires Python 3.12 and Windows:

```powershell
py -3.12 -m pip install -r pc_companion/requirements.txt
py -3.12 -m unittest discover -s pc_companion/tests -v
py -3.12 -m PyInstaller --noconfirm --clean --onefile --windowed --name ShraddhaAI-PC-Companion --collect-all keyring pc_companion/agent.py
```

The Windows GitHub Actions workflow packages a ready-to-use release folder containing the EXE, this guide, and `setup-ocr.ps1`. Extract the artifact to a normal folder. Run the OCR setup script once from PowerShell, then launch `ShraddhaAI-PC-Companion.exe`. The PC companion is separate from the Android APK.


The Windows workflow is a manual packaging workflow; it does not run the deferred combined test pass. A successful EXE build also does not replace paired Android↔Windows physical-device verification.
