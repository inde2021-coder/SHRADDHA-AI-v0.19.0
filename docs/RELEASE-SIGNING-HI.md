# Shraddha AI — सुरक्षित Android Release Signing (हिंदी)

Production-signed APK/AAB बनाने के लिए मालिक की अपनी Android upload/release key आवश्यक है। निजी keystore और passwords को GitHub repository में commit **न करें**।

## 1. अपने कंप्यूटर पर release keystore बनाएँ

JDK के `keytool` से एक बार keystore बनाएँ और इसकी सुरक्षित offline backup रखें:

```bash
keytool -genkeypair -v -keystore shraddha-release.jks -alias shraddha-release -keyalg RSA -keysize 4096 -validity 10000
```

Alias और दोनों passwords सुरक्षित रूप से लिखकर रखें। Keystore खो जाने पर उसी signing identity से आगे के updates publish करना संभव नहीं हो सकता।

## 2. Keystore को Base64 में बदलें

Linux/macOS:

```bash
base64 -w 0 shraddha-release.jks > shraddha-release.base64.txt
```

Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("shraddha-release.jks")) | Set-Content -NoNewline shraddha-release.base64.txt
```

Base64 file को भी password/keystore जितना ही secret मानें। इसे chat, issue, commit या public paste में न डालें।

## 3. GitHub Actions secrets सेट करें

Repository → Settings → Secrets and variables → Actions → New repository secret में ये चार secrets बनाएँ:

- `ANDROID_KEYSTORE_BASE64` — Base64 text file की पूरी सामग्री
- `ANDROID_KEYSTORE_PASSWORD` — keystore password
- `ANDROID_KEY_ALIAS` — ऊपर दिया alias
- `ANDROID_KEY_PASSWORD` — alias/key password

## 4. Signed release build चलाएँ

Repository → Actions → **Signed Android Release** → Run workflow चुनें। Workflow:
- signing secrets की मौजूदगी जाँचेगा;
- temporary runner storage में keystore लिखेगा;
- unit tests और release lint चलाएगा;
- signed APK तथा AAB बनाएगा;
- SHA-256 checksums सहित artifact प्रकाशित करेगा।

कोई secret repository source या build artifact में शामिल नहीं किया जाता। Workflow secrets उपलब्ध न होने पर build जानबूझकर रोक देगा।

## सुरक्षा और सीमा

- Debug APK production release नहीं है।
- इस workflow को अभी मालिक की वास्तविक signing key के साथ चलाया नहीं गया है।
- Release workflow की सफलता, वास्तविक Android install/upgrade और Play Console acceptance का विकल्प नहीं है।
- Signing key की backup और recovery की जिम्मेदारी मालिक की है।
