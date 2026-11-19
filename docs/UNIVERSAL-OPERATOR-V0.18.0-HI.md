# SHRADDHA AI — Universal App Operator v0.18.0

## Operator में जोड़ा गया
1. **Per-app learning profile:** हर ऐप का package name, UI anchors और workflows अलग सेव होते हैं।
2. **Semantic UI map:** text, contentDescription, resource ID, class और widget role के आधार पर UI लक्ष्य पहचानना।
3. **Confidence guard:** अस्पष्ट/कम-confidence मैच पर क्लिक नहीं; उपयोगकर्ता को कारण बताकर रुकना।
4. **Training Lab:** उपयोगकर्ता वर्तमान स्क्रीन से button/field anchor सहेज सकता है, CLICK/TYPE/BACK/SCROLL/SELECT_FILE steps बना सकता है और named workflow सेव कर सकता है।
5. **Adaptive replay:** हर step से पहले नई स्क्रीन पढ़ना, anchor फिर resolve करना और step के बाद अपेक्षित text सत्यापित करना। Coordinates को स्थायी locator नहीं बनाया जाता।
6. **Reusable variables:** `customer_name=Rahul` जैसे values रन के समय देना; profile में वास्तविक secret save नहीं करना।
7. **File locator:** exact filename को MediaStore तथा पहले से user-granted folder trees में खोजता है। एक से अधिक परिणाम पर अनुमान नहीं लगाता।
8. **Picker bridge:** supported Android DocumentsUI/media picker में पहले exact filename दिखे तो चुनता है; अन्यथा Search control उपलब्ध होने पर filename खोजकर चुनने का प्रयास करता है।
9. **Safety policy:** password, OTP, PIN, CVV, recovery phrase रोकना; payment, send, delete, purchase और public publish पर explicit approval gate।
10. **Audit:** केवल action type, package, outcome और timestamp; screen frames/credentials नहीं।
11. **Voice command:** Android speech recognition से Hindi निर्देश लेकर केवल पहले से सिखाए गए workflow से मिलान; अनजान task पर रुकना।

## ऐप को ट्रेन कैसे करें
- SHRADDHA के App Operator में जाएँ → App Training.
- जिस ऐप को सिखाना है उसे पहले खोलें; Accessibility सेवा चालू हो।
- वर्तमान ऐप पहचानें; target का exact visible label लिखें; उसका role चुनें और anchor save करें।
- workflow नाम दें, step type चुनें, target anchor चुनें, variable key/expected text दें और steps जोड़ें।
- पूरा workflow सेव करें। अगली बार Operator console में saved workflow चुनें और values दें।
- फ़ाइल खोज के लिए File Access में जाएँ और Download/Pictures जैसे फ़ोल्डर की अनुमति Android system picker से एक बार दें।

## अभी क्या सार्वभौमिक रूप से संभव नहीं है
- सभी apps के हर custom canvas, CAPTCHA, biometric prompt, secure screen या hidden webview को पढ़ना/कंट्रोल करना।
- हर Android picker में बिना किसी user interaction के content URI inject करना।
- दूसरे ऐप की private storage या permission-restricted files पढ़ना।
- unseen app के लिए बिना training/AI planning के मनमाने natural-language tasks को विश्वसनीय ढंग से पूरा करना।
- Accessibility permission, Android OS protections या target app की security policy को bypass करना।

## Status truth
- **Implemented in source:** training profile persistence, anchor capture, saved workflow editor, semantic matcher, adaptive runner, file lookup, SAF folder grant, risk policy, audit metadata.
- **Needs device verification:** OEM-specific Accessibility behavior, picker text visibility, MediaStore permissions, multi-screen workflows and target-app compatibility.
- **Needs configured service/provider:** free-form natural-language planning across unseen apps, cloud memory sync and model-based screen interpretation.
- **Not claimed:** successful APK build, real-device test, universal compatibility or error-free operation. This environment could not download the Gradle 8.11.1 distribution because network DNS was unavailable.
