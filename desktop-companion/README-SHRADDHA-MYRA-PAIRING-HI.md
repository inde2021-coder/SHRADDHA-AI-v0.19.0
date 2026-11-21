# SHRADDHA AI — Windows PC Companion

इस repository में दो अलग Windows desktop codebases हैं:

- **Shraddha AI Windows PC Companion:** `pc_companion/` में उपलब्ध छोटा, allowlisted remote-control companion. यही Android के Device Universe HTTPS relay से pair होकर mouse/keyboard और opt-in screen sharing देता है। Windows EXE build और setup के लिए `pc_companion/README.md` देखें।
- **Shraddha AI Ultra / Shraddha AI Coder:** `desktop-companion/Shraddha AI_ULTRA_APP/` में मौजूद अलग advanced desktop assistant, coding और Website Forge source. यह Shraddha PC Companion EXE के अंदर bundled नहीं है और इसे अलग से setup करना होता है।

## वास्तविक Shraddha PC pairing
Android app के साथ अभी tested/implemented pairing client यहाँ है:
`shraddha-pc-companion/`

1. Android app में Device Universe खोलकर pairing code बनाएँ।
2. Windows पर `shraddha-pc-companion/README.md` के अनुसार Python dependencies install करें।
3. छह-अंकों के pairing code से PC pair करें। बाद की बार device credential Windows Credential Manager से load होता है।
4. PC पर remote-control permission के लिए Yes चुनें; हर control session पर PC user से अलग approval आएगा।
5. Text paste default में बंद है; enable करने पर हर paste पर पुष्टि जरूरी है।

## MYRA Ultra desktop app
`MYRA_ULTRA_APP/` में MYRA Ultra, Coder और Website Forge अलग desktop product/source हैं। इसे अभी Shraddha relay से paired PC control client न समझें; इसके relay adapter का integration और real Windows test बाकी है.

Windows device token Windows Credential Manager में रखा जाता है। Config file में केवल relay URL, session ID और device ID रखे जाते हैं। Pairing code/token किसी से साझा न करें।

## Shraddha AI PC Companion setup
1. Windows PC पर `desktop-companion/Shraddha AI_ULTRA_APP/README.md` पढ़ें।
2. Shraddha AI PC Companion की Python dependencies install करें।
3. API keys केवल local `.env` या OS secret store में रखें; उन्हें GitHub/ZIP में commit न करें।
4. PC Companion को केवल pairing approval के बाद connect करें।

## सुरक्षा सीमाएँ
- Windows PC Companion पर screen sharing default में बंद है।
- Remote mouse/keyboard allowlisted actions तक सीमित हैं; arbitrary shell/PowerShell execution उपलब्ध नहीं है।
- हर remote-control session के लिए PC पर local approval आवश्यक है। Disconnect या F12 से access बंद किया जा सकता है।
- Android app अकेले Windows/Linux के local files या desktop को नियंत्रित नहीं कर सकता; paired companion आवश्यक है।
- Remote control और screen sharing डिफ़ॉल्ट रूप से बंद रहते हैं; PC पर स्थानीय अनुमति मिलने पर ही उन्हें चालू करें।
