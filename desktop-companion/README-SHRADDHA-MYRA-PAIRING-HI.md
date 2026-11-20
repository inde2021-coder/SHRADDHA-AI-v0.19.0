# SHRADDHA AI, Windows PC Companion और MYRA

इस repository में दो अलग Windows desktop codebases हैं:

- **Shraddha AI Windows PC Companion:** `pc_companion/` में उपलब्ध छोटा, allowlisted remote-control companion. यही Android के Device Universe HTTPS relay से pair होकर mouse/keyboard और opt-in screen sharing देता है। Windows EXE build और setup के लिए `pc_companion/README.md` देखें।
- **MYRA Ultra / MYRA Coder:** `desktop-companion/MYRA_ULTRA_APP/` में मौजूद अलग advanced desktop assistant, coding और Website Forge source. यह Shraddha PC Companion EXE के अंदर bundled नहीं है और इसे अलग से setup करना होता है।

## Shraddha AI ↔ Windows PC pairing
1. Android पर **Device Universe** खोलें और मुख्य phone session बनाएं।
2. छह अंकों का pairing code लें (5 मिनट में expire होता है)।
3. Windows पर `ShraddhaAI-PC-Companion.exe` चलाएँ।
4. वही HTTPS relay URL, PC का नाम और pairing code डालकर **Join & Connect** दबाएँ।
5. Windows पर mouse/keyboard remote control और screen sharing अलग-अलग opt-in करें।
6. Android से Windows PC चुनें और control request भेजें। Windows पर local approval dialog स्वीकार करना आवश्यक है।

Windows device token Windows Credential Manager में रखा जाता है। Config file में केवल relay URL, session ID और device ID रखे जाते हैं। Pairing code/token किसी से साझा न करें।

## MYRA source setup
1. Windows PC पर `desktop-companion/MYRA_ULTRA_APP/README.md` पढ़ें।
2. MYRA की Python requirements और MYRA Coder के लिए Node.js dependencies अलग से install करें।
3. API keys केवल local `.env` या OS secret store में रखें; उन्हें GitHub/ZIP में commit न करें।
4. MYRA को अलग desktop agent की तरह चलाएँ। यह Shraddha PC Companion की जगह नहीं लेता।

## सुरक्षा सीमाएँ
- Windows PC Companion पर screen sharing default में बंद है।
- Remote mouse/keyboard allowlisted actions तक सीमित हैं; arbitrary shell/PowerShell execution उपलब्ध नहीं है।
- हर remote-control session के लिए PC पर local approval आवश्यक है। Disconnect या F12 से access बंद किया जा सकता है।
- Android app अकेले Windows/Linux के local files या desktop को नियंत्रित नहीं कर सकता; paired companion आवश्यक है।
- MYRA के अपने permissions और integrations अलग हैं; Shraddha relay pairing को MYRA की सभी local capabilities का automatic permission न समझें।
