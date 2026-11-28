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

- Supported: normalized mouse click, drag/swipe, bounded text paste, Start Menu shortcut launch, open a relative file/folder inside Documents, Downloads or Desktop, show desktop, browser-style back and bounded scroll.
- **Text-based click:** exact, unique visible Button/Hyperlink/Tab/Menu/CheckBox/RadioButton controls can be activated through Windows UI Automation. Ambiguous labels, hidden/disabled controls and unknown/sensitive active windows fail closed.
- **Active-window OCR:** `read screen` / `स्क्रीन पढ़ो` captures only the current non-sensitive window and runs local Tesseract OCR. OCR text is redacted for credential labels and long number strings before being returned over the encrypted peer channel. It is never silently uploaded to a cloud OCR provider.
- `pytesseract` is included in the companion Python requirements; install the Windows Tesseract engine separately. The companion uses both `eng` and `hin` packs when installed, gracefully falls back to either available pack, and reports which language is missing. If neither supported pack exists, OCR returns a setup error.
- Voice commands `open file Reports/summary.pdf` and `open folder Projects` resolve only inside the current Windows user's Documents, Downloads or Desktop. Absolute paths, drive letters, network shares, `..`, symlink escapes and ambiguous matches are rejected. Opening uses the OS file association; it never invokes a shell.
- The agent never executes arbitrary shell commands, PowerShell, scripts or unrestricted file operations.
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
- File transfer is bidirectional: use **Send File to Phone** on Windows or **CHOOSE FILE & SEND** on Android. The receiving device must approve the transfer and choose its save location; chunks are E2E encrypted and the final file is SHA-256 verified. The Android transfer screen stores chunk checkpoints and offers Resume / Retry after reconnect.
- The companion does not capture microphone audio, read files, or silently send messages.

## Offline Language Lab (optional)

Open **Offline Language Lab** in the Windows companion to use locally installed models for speech-to-text, translation, or consented reference-voice synthesis. This is a separate optional environment; it is not installed into the default companion EXE and does not add model weights to the Android APK.

Install only on a PC that will run the models:

```powershell
py -3.12 -m venv .venv-language
.\.venv-language\Scripts\Activate.ps1
py -m pip install -r pc_companion/requirements-language.txt
```

Configure local model paths before use. The ASR adapter currently allows Hindi, Marathi and English only. Translation allows Hindi, English and Marathi. IndicF5 synthesis allows Hindi and Marathi only and requires explicit permission from the reference-voice owner plus the exact reference transcript. Sanskrit and Pawari remain disabled in these PC adapters until the model artifacts, licenses and real-device quality are independently validated. The runtime never downloads or executes remote model code and never falls back to a cloud provider.

Set model paths locally before starting the companion (never paste private model paths into a public issue):

- `SHRADDHA_ASR_MODEL_HI`, `SHRADDHA_ASR_MODEL_MR`, `SHRADDHA_ASR_MODEL_EN`: local Sherpa-ONNX model files.
- `SHRADDHA_ASR_TOKENS_INDIC` and `SHRADDHA_ASR_TOKENS_EN`: matching local token files.
- `SHRADDHA_TRANSLATION_MODEL_DIR`: local IndicTrans2 checkpoint directory.
- IndicF5 weights must already exist in the local Hugging Face cache; runtime network downloads are disabled.

The optional lab's Python unit tests are included in the PC test suite. A successful unit test does not prove that a user's GPU, model files, microphone or speaker produce acceptable inference; validate each installed model locally before relying on it.

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

The Windows GitHub Actions workflow builds and publishes the EXE as a separate artifact. The PC companion is not part of the Android APK.


CI verification is run on the work branch for the companion's protocol/action unit tests and Windows executable packaging; this does not replace paired physical-device testing.
