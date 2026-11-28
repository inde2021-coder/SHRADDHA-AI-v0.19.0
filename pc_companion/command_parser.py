"""Small allow-listed text command parser for PC-to-Android control."""
import re
import unicodedata


# Remote typing must never be used to enter authentication or payment secrets.
# Include common Hindi spellings and English transliterations used in voice commands.
_SENSITIVE_INPUT = re.compile(
    r"(?i)(?:"
    r"\b(?:pass(?:word|code)?|pass\s*word|otp|one[- ]?time\s*(?:password|code|pin)?|"
    r"verification\s*code|security\s*code|secret\s*key|recovery\s*(?:phrase|code)|"
    r"cvv|cvc|bank\s*pin|upi\s*pin|pin)\b"
    r"|पासवर्ड|पासकोड|ओटीपी|ओ टी पी|वन टाइम पासवर्ड|सत्यापन कोड|सुरक्षा कोड|"
    r"गुप्त कुंजी|रिकवरी वाक्यांश|रिकवरी कोड|सीवीवी|यूपीआई पिन|बैंक पिन|पिन"
    r")"
)


def _normalise_command(raw):
    # NFKC makes visually equivalent full-width/compatibility characters consistent.
    return " ".join(unicodedata.normalize("NFKC", str(raw)).strip().split())


def parse_android_command(raw):
    command = _normalise_command(raw)
    if not command:
        raise ValueError("Type a command first.")
    if len(command) > 500:
        raise ValueError("Command is too long.")
    lower = command.casefold()
    akkps_modules = {
        "open akkps farmers": "farmers", "open akkps plots": "plots",
        "open akkps awd reports": "awd reports", "open akkps profile": "profile",
        "open akkps data sync": "data sync", "akkps किसान खोलो": "farmers",
        "akkps प्लॉट खोलो": "plots", "akkps एडब्ल्यूडी रिपोर्ट खोलो": "awd reports",
    }
    if lower in akkps_modules:
        return {"kind": "akkps_module", "module": akkps_modules[lower]}
    if lower in {"open akkps", "open terramark", "akkps खोलो", "टेरामार्क खोलो"}:
        return {"kind": "akkps_open"}
    if lower in {"open akkps runner", "akkps runner खोलो", "runner खोलो"}:
        return {"kind": "akkps_runner"}
    if lower in {"inspect akkps", "inspect runner", "akkps जांचो", "runner जांचो"}:
        return {"kind": "akkps_inspect"}
    if lower in {"home", "होम", "home screen"}:
        return {"kind": "home"}
    if lower in {"back", "go back", "वापस", "पीछे जाओ"}:
        return {"kind": "back"}
    if lower in {"scroll up", "ऊपर scroll", "ऊपर स्क्रॉल"}:
        return {"kind": "scroll", "amount": 5}
    if lower in {"scroll down", "नीचे scroll", "नीचे स्क्रॉल"}:
        return {"kind": "scroll", "amount": -5}
    match = re.match(r"^(?:open|launch|खोलो|खोल)\s+(.+)$", command, re.I)
    if match:
        name = match.group(1).strip()
        if len(name) > 80 or any(ch in name for ch in "\\\\/:") or ".." in name:
            raise ValueError("App name is invalid.")
        return {"kind": "open_app", "appName": name}
    match = re.match(r"^(?:type|लिखो|टाइप करो|लिख दो|टाइप)\s+(.+)$", command, re.I)
    if match:
        text = match.group(1)
        if _SENSITIVE_INPUT.search(text):
            raise ValueError("Sensitive password, OTP, PIN or security text cannot be typed remotely.")
        return {"kind": "type", "text": text}
    if lower in {
        "read screen", "read this screen", "screen ocr", "ocr",
        "स्क्रीन पढ़ो", "स्क्रीन का टेक्स्ट पढ़ो", "स्क्रीन से टेक्स्ट निकालो",
        "स्क्रीन पर क्या लिखा है", "यह स्क्रीन पढ़ो"
    }:
        return {"kind": "ocr"}
    match = re.match(r"^(?:click text|click button|press button|बटन दबाओ|टेक्स्ट पर क्लिक करो)\s+(.+)$", command, re.I)
    if match:
        label = match.group(1).strip()
        if not label or len(label) > 120:
            raise ValueError("Button text must contain 1-120 characters.")
        return {"kind": "click_text", "text": label}
    if lower in {"tap center", "tap", "बीच में टैप"}:
        return {"kind": "tap", "x": 0.5, "y": 0.5}
    if lower in {"swipe up", "ऊपर swipe"}:
        return {"kind": "swipe", "x1": 0.5, "y1": 0.75, "x2": 0.5, "y2": 0.25}
    raise ValueError("Command not understood. Try: home, back, open Settings, read screen, click text Save, scroll down, type Hello.")
