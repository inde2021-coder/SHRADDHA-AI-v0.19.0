# Shraddha AI — Windows PC Companion

यह अलग Windows companion, Android Shraddha AI के Device Universe relay से pair होता है।

## Requirements
- Windows 10/11
- Python 3.10+
- Deployed Shraddha relay with HTTPS/WSS
- Android Shraddha AI app में Device Universe से बनाया गया single-use QR / छह-अंकों का pairing code


## Feature-wise installation (modular PC build)

PC companion के core और optional features अलग dependency packs में हैं। सामान्य pairing/secure relay के लिए OCR, screen capture, clipboard, desktop automation या offline LLM packages install करने की जरूरत नहीं है। Optional libraries feature इस्तेमाल होने पर ही import होती हैं।

Core secure pairing/relay:

```powershell
py -m pip install -r requirements.txt
```

केवल जरूरत के अनुसार अतिरिक्त pack install करें:

| PC feature | Install command |
|---|---|
| Mouse/keyboard/app control | `py -m pip install -r requirements-control.txt` |
| Active-window screenshot, OCR और screen share | `py -m pip install -r requirements-vision.txt` |
| Clipboard transfer | `py -m pip install -r requirements-clipboard.txt` |
| Offline voice commands | `py -m pip install -r requirements-voice.txt` |
| QR pairing camera | `py -m pip install -r requirements-qr.txt` |
| Local GGUF model inference (बड़ा optional runtime) | `py -m pip install -r requirements-offline.txt` |

सभी PC features चाहिए हों तभी `requirements-all.txt` install करें। GGUF model weights इसमें शामिल नहीं हैं; वे PC पर अलग से चुने जाते हैं। Missing optional package होने पर संबंधित feature unavailable रहेगा; core pairing को crash नहीं करना चाहिए।

## Install & run
PowerShell में:

```powershell
py -m venv .venv
.\.venv\Scripts\Activate.ps1
py -m pip install -r requirements.txt
# click_text के लिए Windows UI Automation dependency pywinauto भी requirements में शामिल है
py shraddha_pc_companion.py --base-url https://YOUR-RELAY-DOMAIN --pairing-code 123456 --device-name "Office PC"

# या Android Shraddha AI के QR को PC camera से scan करें
py -m pip install -r requirements-qr.txt
py shraddha_pc_companion.py --scan-pairing-qr --device-name "Office PC"
```

पहली pairing के बाद device credentials Windows Credential Manager में सुरक्षित रखे जाते हैं। अगली बार केवल यह चलाएँ:

```powershell
py shraddha_pc_companion.py
```

Pairing हटाने के लिए:

```powershell
py shraddha_pc_companion.py --forget-pairing

```

Text entry enable करना हो तो `--allow-typing` जोड़ें। Text paste के लिए हर बार PC पर अलग confirmation आएगा।

## Consent & limits
- PC user के स्पष्ट Yes के बिना remote-control permission enable नहीं होगी।
- हर remote-control session के लिए PC पर अलग approval dialog आएगा; approval अधिकतम 5 मिनट का है।
- Tap, swipe, scroll, Back और Desktop actions supported हैं।
- Text paste default में disabled है; enabled होने पर हर paste की पुष्टि जरूरी है।
- Password, login, banking, OTP, PIN और payment जैसे window titles में text paste block किया जाता है। यह heuristic है, perfect detector नहीं।
- App launching, arbitrary shell commands, file access, remote webcam and PC screen streaming इस first bridge में enabled नहीं हैं।
- Windows UAC secure desktop, lock screen, protected apps और elevated windows को control करने का दावा नहीं है।
- Companion बंद करने के लिए Ctrl+C करें; Android से device unpair करने पर access revoke करें।

PC पर control permission को ON रखना तभी चुनें जब यह आपका भरोसेमंद PC हो।


## Optional live PC screen sharing
Install the additional `mss` and `Pillow` dependencies (included in requirements), then run with `--share-screen`. The PC user must confirm sharing locally every session. Frames are JPEG-compressed and relayed only to paired devices in the same Shraddha session; use `--screen-target DEVICE_ID` to target one paired device instead of broadcasting. The stream captures only the active application window and pauses when its title matches password, sign-in, bank, OTP, PIN, payment or credential patterns. If the active window or its geometry cannot be identified, capture fails closed. Title matching is still a best-effort guard: sensitive content shown inside an ordinary-titled window may not be detected; close sensitive windows before enabling sharing. Stop the companion to revoke the stream.


## Active-window OCR and safe file previews

Optional Windows OCR is enabled only when the companion is started with `--allow-ocr`. Install the Tesseract OCR engine separately and install Python dependencies from `requirements-vision.txt` (or `requirements-all.txt`); Hindi OCR additionally requires the Tesseract Hindi language data (`hin`). At runtime the companion checks the installed Tesseract language packs: it uses both English and Hindi when available, gracefully falls back to whichever of those packs is installed, and returns an explicit partial-language status instead of failing the entire OCR request. If neither pack is installed, it reports the exact language-data requirement. Every OCR request requires confirmation in the Windows console and refuses unknown or sensitive active windows. OCR reads only the active window capture, not the whole desktop.

Safe file browsing is disabled unless you explicitly set `--file-root "C:\\Users\\YourName\\Documents"`. File listing and text preview are limited to that root, reject path traversal and symlinks, cap listings at 100 entries, allow only selected text/code extensions, cap previews at 256 KB, and ask for local confirmation on every request. The companion does not execute, delete, move, or overwrite files.

Example:

```powershell
python shraddha_pc_companion.py --allow-ocr --file-root "$HOME\\Documents"
```

Remote OCR and file-preview results are relayed only to the paired requester while its temporary PC-control grant remains active.


## Local offline AI inference (GGUF)

The Windows companion can run a real local language model through `llama.cpp`. Model inference happens on the paired Windows PC; the companion does not call a cloud AI provider for `offline_chat`. Model weights are not bundled in this repository: download a compatible instruct GGUF file yourself and verify its license and source.

Install the optional CPU runtime:

```powershell
python -m pip install -r requirements-offline.txt
```

Start the companion with your local model:

```powershell
python shraddha_pc_companion.py --offline-model "C:\\Models\\assistant-instruct-q4.gguf"
```

The first startup loads the model into memory and may take time. CPU speed and memory depend on the model size and quantization. The Android Devices workspace can send a prompt to the paired PC; the PC console asks for local confirmation before inference. The answer returns over the authenticated, paired-device relay. This is PC-local inference, not Android on-device inference. The model generation itself makes no cloud AI request, but sending a prompt from Android to a paired PC still needs the authenticated device relay network; it is not a fully disconnected phone-to-PC workflow. If no model path is configured, the offline action is unavailable.

The companion binds no inference HTTP port and exposes no unauthenticated local model server.


## OCR privacy and approved-folder search

- Active-window OCR requires `--allow-ocr` and a separate local PC confirmation for each request.
- OCR output is redacted before it is returned to the paired device: credential-labelled lines, nearby value lines, and long numeric identifiers are hidden. This is a defensive filter, not a guarantee that every secret can be detected; avoid OCR on sensitive screens.
- Optional file browsing uses only the folder explicitly selected with `--file-root`. Listing, reading, and text search each require local PC confirmation.
- File search is restricted to text/code formats, skips hidden files and symlinks, and enforces depth, file-count, file-size, result-count, and total-read limits.
- The local GGUF Code Assistant can optionally read one selected approved text/code file as context after a separate PC confirmation; it returns a proposal only and never writes the file itself.\n- Android can request a bounded text/code file create or update inside that root. The PC owner must confirm each write locally; updates require an explicit overwrite flag and create a timestamped backup under the hidden `.shraddha-backups` folder before atomic replacement. Only selected text/code extensions and files up to 256 KB are supported. No file execution, delete, move, or arbitrary shell command is provided.


## Bidirectional Android control and screen viewing

Install the optional camera/preview dependency once:

~~~powershell
py -m pip install -r requirements-qr.txt
~~~

Pair by scanning the QR shown by Android Shraddha AI:

~~~powershell
py shraddha_pc_companion.py --scan-pairing-qr --device-name "Office PC"
~~~

The QR scanner asks for local PC confirmation before opening the webcam. The QR contains a five-minute, single-use invitation; it does not contain a device token. After joining, the PC credentials are stored in Windows Credential Manager.

To request control of the paired Android phone, use its device ID shown in the Android Device Universe peer list:

~~~powershell
py shraddha_pc_companion.py --control-phone ANDROID_DEVICE_ID --phone-action back
py shraddha_pc_companion.py --control-phone ANDROID_DEVICE_ID --phone-action tap --phone-x 0.5 --phone-y 0.5
py shraddha_pc_companion.py --control-phone ANDROID_DEVICE_ID --phone-action type --phone-text "Hello"
py shraddha_pc_companion.py --control-phone ANDROID_DEVICE_ID --phone-action open_app --phone-app "Settings"
~~~

Each command asks the PC user for local confirmation, then sends a short control request. The Android owner must also have Remote Control permission enabled and accept the Android approval dialog. Actions are sent only after both approvals; the companion reports the Android accessibility service's acceptance or rejection. This acknowledgement means the service accepted/rejected the request, not that the app's business task was independently verified. Main-phone-only mode may block a PC member from initiating control.

To view an Android screen stream on Windows, start the companion with:

~~~powershell
py shraddha_pc_companion.py --view-phone-screen
~~~

On Android, enable Screen sharing in its permissions, select the paired PC, then press Share This Phone Screen and accept Android's system MediaProjection prompt. The PC preview uses the optional OpenCV window; press Q or stop the companion to close it. Sharing is targeted to the selected paired PC, not broadcast to the whole session.

To share the active Windows app window back to Android, use --share-screen; the PC user must separately confirm sharing at startup. Sensitive-looking window titles are excluded, but title matching is only a best-effort guard. Close private windows before sharing.

## File transfer, clipboard, voice commands and recovery

File transfer supports 48 KiB chunks, files up to 100 MiB, a SHA-256 manifest, per-chunk acknowledgements, pause/resume and cancellation. A device must first request and receive the receiver's one-time Documents share approval. Android now provides a SAF file picker, app-private disk-backed receive staging, persisted source/destination URI grants, chunk-index checkpoints, resume after activity/process recreation and final SHA-256 verification. The relay keeps transfer metadata only in memory for up to 30 minutes, so a relay restart or expired transfer still requires a new transfer. Physical Android↔Windows reconnect, interruption and 100 MiB throughput tests remain required before production sign-off.

Windows clipboard sync is a one-shot operation and requires the receiver to approve the Clipboard share request. Install `pyperclip` on Windows:

~~~powershell
py -m pip install pyperclip
~~~

Send the current Windows text clipboard to a paired Android device (replace the device ID):

~~~powershell
py shraddha_pc_companion.py --send-clipboard-to ANDROID_DEVICE_ID
~~~

Receiving clipboard text on Windows asks for a separate local confirmation before copying it. Likely password, OTP, PIN and recovery phrase content is blocked. Clipboard history is not uploaded or retained.

Offline voice control uses a local Vosk model; no cloud speech recognition is used. Install `vosk` and `sounddevice`, download an appropriate Hindi/English Vosk model, then run:

~~~powershell
py -m pip install -r requirements-voice.txt
py shraddha_pc_companion.py --voice-control-phone ANDROID_DEVICE_ID --vosk-model C:\\Models\\vosk-model-small-en-us
~~~

Only allowlisted commands such as `go back`, `go home`, `scroll up`, `scroll down`, and `open app Settings` are accepted. The PC asks for confirmation and Android must separately approve remote control.

Automatic reconnect uses bounded exponential backoff up to 60 seconds. Reconnection reuses the saved pairing token but asks for fresh local sharing/control consent; temporary control grants are not silently restored.

## Android-to-Windows desktop actions

When the paired PC has explicitly enabled remote control, the Android Device Universe can request these additional actions. The PC user must still approve each action locally, and the Android side must have the active paired-device control grant:

- **App launcher:** allowlisted Notepad, Calculator, Paint, Explorer, Task Manager, Settings, Edge, Chrome, PowerShell and Terminal. Arbitrary shell commands and arbitrary executable paths are not accepted.
- **System control:** lock workstation, volume keys and supported display brightness controls. Shutdown/restart are deliberately not exposed.
- **Media control:** play/pause, next/previous track, volume up/down and mute.
- **Screenshot AI:** save a JPEG of the active window under Pictures/Shraddha AI Screenshots. Unknown or sensitive-looking window titles are blocked. OCR remains a separate action and requires --allow-ocr.
- **Clipboard transformations:** trim, uppercase/lowercase, collapse whitespace and remove blank lines. Likely credential content is rejected; the PC user confirms returning the transformed text. Clipboard history is not collected or retained.
- **Web search:** live DuckDuckGo HTML search; requires internet access and returns a small bounded set of results.
- **Code assistant:** uses only the configured local GGUF model (--offline-model); it does not silently switch to a cloud provider.

These are bounded desktop actions, not unrestricted remote administration. App launch success is reported as a launch request, not proof that the app finished loading. Screenshot capture and OCR operate on the active window only. Display brightness depends on compatible hardware and the optional screen_brightness_control package. Web search depends on the external search service being reachable. The Android UI exposes the supported actions, but a real paired Windows PC test is still required before describing end-to-end operation as production verified.
