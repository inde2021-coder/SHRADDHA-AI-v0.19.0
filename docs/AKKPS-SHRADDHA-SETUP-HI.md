# AKKPS वेबसाइट पर Shraddha AI चलाने की विधि

## क्या चाहिए
- AKKPS वेबसाइट का आपका अधिकृत login.
- Shraddha AI Android app.
- Windows PC/Laptop और Shraddha AI Windows Companion (यदि PC browser automation चाहिए).
- Windows में Microsoft Edge.

कोई AKKPS API key या वेबसाइट का password Shraddha AI में डालने की जरूरत नहीं है। Login, OTP और CAPTCHA आप स्वयं वेबसाइट के official login page पर भरें।

## Android पर
1. Shraddha AI खोलें और AKKPS Runner workspace खोलें।
2. Official AKKPS login page पर स्वयं sign in करें।
3. Runner खुलने पर Inspect/Inventory चलाएँ।
4. AI से Farmers, Plots या AWD Reports के fields map करवाएँ।
5. केवल अपने द्वारा दिए गए सही values भरवाएँ। Missing या ambiguous field पर AI को रुकना चाहिए।
6. Submit से पहले पूरी entry जाँचें और अलग confirmation दें।

## Windows browser operator
1. Windows Companion खोलें और Android के साथ PC pair करें।
2. Companion में AKKPS Website Operator खोलें।
3. Visible Microsoft Edge window में स्वयं login करें।
4. Open Runner दबाएँ, फिर Farmers / Plots / AWD Reports चुनें।
5. Inspect Current Page दबाकर visible labels और form structure देखें।
6. कई सामान्य fields भरने के लिए exact visible labels वाला JSON object दें, उदाहरण:
   ```json
   {
     "Farmer Name": "यहाँ वास्तविक नाम",
     "Phone Number": "यहाँ वास्तविक मोबाइल नंबर",
     "Village / Block Number": "यहाँ सही गाँव/ब्लॉक"
   }
   ```
7. Preview All Fields में हर label और value जाँचें। फिर Fill Reviewed Batch की local confirmation दें।
8. यह केवल fields भरता है; record submit नहीं करता।
9. Submit Record अलग confirmation मांगता है। यदि response uncertain हो, दोबारा submit न करें—पहले AKKPS list में record जाँचें।

## अभी की सीमाएँ
- Password, OTP, CAPTCHA, hidden inputs और browser cookies AI नहीं पढ़ता।
- Aadhaar/document, signature, land deed और field photos आप website के file picker से स्वयं चुनें।
- AWD के repeated drying/reflooding dates, fertilizer rows, field visits, autocomplete और map boundary drawing के लिए अलग page-specific automation चाहिए; generic field fill इन्हें complete नहीं मानता।
- Admin/Coordinator, delete, bulk update, payment और approval actions इस workflow में बंद हैं।
- Private account पर वास्तविक save की पुष्टि अधिकृत account owner ही कर सकता है।

**महत्वपूर्ण:** किसी record को saved तभी मानें जब AKKPS response में success और record ID मिले और वह संबंधित list में दिखाई दे। Timeout या ambiguous response को uncertain मानें; automatic retry नहीं होता।
