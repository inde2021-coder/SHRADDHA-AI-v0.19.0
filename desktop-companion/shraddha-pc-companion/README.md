# Shraddha AI — Windows PC Companion

यह अलग Windows companion, Android Shraddha AI के Device Universe relay से pair होता है।

## Requirements
- Windows 10/11
- Python 3.10+
- Deployed Shraddha relay with HTTPS/WSS
- Android Shraddha AI app में Device Universe से बनाया गया छह-अंकों का pairing code

## Install & run
PowerShell में:

```powershell
py -m venv .venv
.\.venv\Scripts\Activate.ps1
py -m pip install -r requirements.txt
# click_text के लिए Windows UI Automation dependency pywinauto भी requirements में शामिल है
py shraddha_pc_companion.py --base-url https://YOUR-RELAY-DOMAIN --pairing-code 123456 --device-name "Office PC"
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

Optional Windows OCR is enabled only when the companion is started with `--allow-ocr`. Install the Tesseract OCR engine separately and install Python dependencies from `requirements.txt`; Hindi OCR additionally requires the Tesseract Hindi language data (`hin`). Every OCR request requires confirmation in the Windows console and refuses unknown or sensitive active windows. OCR reads only the active window capture, not the whole desktop.

Safe file browsing is disabled unless you explicitly set `--file-root "C:\\Users\\YourName\\Documents"`. File listing and text preview are limited to that root, reject path traversal and symlinks, cap listings at 100 entries, allow only selected text/code extensions, cap previews at 256 KB, and ask for local confirmation on every request. The companion does not execute, delete, move, or overwrite files.

Example:

```powershell
python shraddha_pc_companion.py --allow-ocr --file-root "$HOME\\Documents"
```

Remote OCR and file-preview results are relayed only to the paired requester while its temporary PC-control grant remains active.
