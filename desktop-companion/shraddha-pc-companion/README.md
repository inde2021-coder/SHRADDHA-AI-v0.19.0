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
py shraddha_pc_companion.py --base-url https://YOUR-RELAY-DOMAIN --pairing-code 123456 --device-name "Office PC"
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
