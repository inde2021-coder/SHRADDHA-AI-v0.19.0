"""Small allow-listed text command parser for PC-to-Android control."""
import re


def parse_android_command(raw):
    command = " ".join(str(raw).strip().split())
    if not command:
        raise ValueError("Type a command first.")
    if len(command) > 500:
        raise ValueError("Command is too long.")
    lower = command.lower()
    if lower in {"home", "होम", "home screen"}:
        return {"kind": "home"}
    if lower in {"back", "go back", "वापस", "पीछे जाओ"}:
        return {"kind": "back"}
    if lower in {"scroll up", "ऊपर scroll", "ऊपर स्क्रॉल"}:
        return {"kind": "scroll", "amount": 5}
    if lower in {"scroll down", "नीचे scroll", "नीचे स्क्रॉल"}:
        return {"kind": "scroll", "amount": -5}
    match = re.match(r"^(?:type|लिखो|टाइप करो)\s+(.+)$", command, re.I)
    if match:
        text = match.group(1)
        if re.search(r"(?i)\b(password|passcode|otp|one[- ]time code|pin|cvv|recovery phrase|seed phrase|mnemonic|api key|access token|secret key|private key|bearer token)\b|पासवर्ड|ओटीपी|पिन", text):
            raise ValueError("Sensitive password, OTP or PIN text cannot be typed remotely.")
        return {"kind": "type", "text": text}
    match = re.match(r"^(?:open file|open folder|फ़ाइल खोलो|फाइल खोलो|फ़ोल्डर खोलो|फोल्डर खोलो)\s+(.+)$", command, re.I)
    if match:
        path = match.group(1).strip().replace("\\", "/")
        parts = [part for part in path.split("/") if part not in ("", ".")]
        if not path or len(path) > 500 or path.startswith("/") or re.match(r"^[A-Za-z]:", path) or any(part == ".." or part.startswith(".") for part in parts):
            raise ValueError("File/folder path must be a safe relative path under the PC user folder.")
        return {"kind": "file_open", "path": "/".join(parts)}

    match = re.match(r"^(?:open|launch|खोलो|खोल)\s+(.+)$", command, re.I)
    if match:
        name = match.group(1).strip()
        if len(name) > 80 or any(ch in name for ch in "\\/:") or ".." in name:
            raise ValueError("App name is invalid.")
        return {"kind": "open_app", "appName": name}
    match = re.match(r"^(?:search files?|find files?|फाइल खोजो|फाइल ढूंढो|फ़ाइल खोजो|फ़ाइल ढूंढो)\s+(.+)$", command, re.I)
    if match:
        query = match.group(1).strip()
        if len(query) < 2 or len(query) > 80:
            raise ValueError("File search text must contain 2-80 characters.")
        return {"kind": "file_search", "query": query, "path": ""}
    if lower in {"read screen", "read this screen", "screen ocr", "ocr", "स्क्रीन पढ़ो", "स्क्रीन का टेक्स्ट पढ़ो", "स्क्रीन से टेक्स्ट निकालो"}:
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
