# SHRADDHA AI ↔ MYRA PC Companion

यह MYRA desktop companion, SHRADDHA AI Android ऐप का paired PC execution companion है। MYRA को अलग Android app के रूप में install नहीं करना है।

## इसमें मौजूद MYRA systems
- MYRA Ultra desktop assistant/orb and voice command interface.
- Linux and Termux companion variants are retained in `MYRA_LINUX/` and `MYRA_TERMUX/`.
- MYRA Coder (Node/TypeScript) for code generation and project workflows.
- WebForge website builder and deployment integration.
- Screen share and camera vision modules.
- Desktop app/action utilities, file operations and office workflows.

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

## सीमा
यह companion PC पर local permissions के साथ काम करता है। मोबाइल ऐप अकेले Windows/Linux के local files, terminal या desktop को नियंत्रित नहीं कर सकता। इसके लिए paired companion आवश्यक है।
