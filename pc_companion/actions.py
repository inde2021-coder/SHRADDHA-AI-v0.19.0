"""Allowlisted Windows UI actions. No shell execution or arbitrary process launch."""
import re


_SENSITIVE_WINDOW = re.compile(
    r"(?i)password|passcode|otp|one[- ]time|pin|cvv|bank|payment|checkout|"
    r"credential|sign[ -]?in|login|पासवर्ड|ओटीपी|पिन|बैंक|भुगतान"
)
_SENSITIVE_LINE = re.compile(
    r"(?i)password|passcode|otp|one[- ]time|pin|cvv|security code|verification code|"
    r"recovery phrase|account number|card number|upi|पासवर्ड|ओटीपी|पिन|गुप्त कोड|"
    r"सत्यापन कोड|खाता संख्या|कार्ड नंबर|यूपीआई"
)
_LONG_NUMBER = re.compile(r"(?<!\d)(?:\d[ -]?){4,}(?!\d)")
_CLICKABLE_TYPES = {"Button", "Hyperlink", "TabItem", "MenuItem", "CheckBox", "RadioButton"}


def redact_ocr_text(raw):
    """Redact credential-labelled lines and adjacent values before returning OCR."""
    if not isinstance(raw, str):
        return ""
    output = []
    hide_next = False
    for line in raw[:24000].splitlines()[:200]:
        value = line.strip()[:500]
        if not value:
            continue
        if _SENSITIVE_LINE.search(value):
            output.append("[संवेदनशील OCR पंक्ति छिपाई गई]")
            hide_next = True
            continue
        if hide_next:
            output.append("[संवेदनशील जानकारी छिपाई गई]")
            hide_next = False
            continue
        output.append(_LONG_NUMBER.sub("[अंक छिपाए गए]", value))
    return "\n".join(output)[:12000]


def _active_window():
    """Return active window bounds/title, failing closed for unknown or sensitive windows."""
    import pygetwindow
    active = pygetwindow.getActiveWindow()
    if not active:
        raise RuntimeError("No active desktop window is available.")
    title = str(active.title or "").strip()
    if not title or _SENSITIVE_WINDOW.search(title):
        raise RuntimeError("OCR and text targeting are blocked on unknown or sensitive windows.")
    bounds = (int(active.left), int(active.top), int(active.width), int(active.height))
    if bounds[2] < 80 or bounds[3] < 60:
        raise RuntimeError("The active window bounds are not readable.")
    return active, title, bounds


def click_unique_text(label):
    """Click one exact visible UI Automation control; ambiguity never falls back to coordinates."""
    _, _, _bounds = _active_window()
    from pywinauto import Desktop
    root = Desktop(backend="uia").get_active()
    wanted = " ".join(str(label).casefold().split())
    matches = []
    for control in root.descendants()[:1500]:
        try:
            info = control.element_info
            name = " ".join(str(info.name or "").casefold().split())
            if (name == wanted and info.control_type in _CLICKABLE_TYPES
                    and control.is_visible() and control.is_enabled()):
                matches.append(control)
        except Exception:
            continue
    if len(matches) != 1:
        if not matches:
            raise RuntimeError("No exact visible clickable control matched that text.")
        raise RuntimeError("More than one control has that text; refusing an ambiguous click.")
    matches[0].click_input()
    return "Clicked the unique visible control labelled: " + str(label)[:120]


def select_ocr_languages(available):
    """Select installed English/Hindi Tesseract packs and report missing packs."""
    installed = {str(item).strip().casefold() for item in available if isinstance(item, str)}
    requested = ["eng", "hin"]
    return ([language for language in requested if language in installed],
            [language for language in requested if language not in installed])


def read_active_window_ocr(pyautogui):
    """Read only the active non-sensitive window using local Tesseract Hindi+English OCR."""
    _, title, (left, top, width, height) = _active_window()
    try:
        import pytesseract
    except ImportError as exc:
        raise RuntimeError("Install the optional pytesseract package and the Windows Tesseract OCR engine with Hindi (hin) and English (eng) language data.") from exc
    try:
        installed = pytesseract.get_languages(config="")
    except Exception as exc:
        raise RuntimeError("Tesseract is not installed or its executable is not on PATH.") from exc
    selected, missing = select_ocr_languages(installed)
    if not selected:
        raise RuntimeError("No supported OCR language data found. Install Tesseract eng and/or hin traineddata.")
    image = pyautogui.screenshot(region=(max(0, left), max(0, top), width, height))
    try:
        raw = pytesseract.image_to_string(image, lang="+".join(selected), config="--psm 6")
    except Exception as exc:
        raise RuntimeError("Installed Tesseract OCR language data could not process this window.") from exc
    safe = redact_ocr_text(raw)
    if not safe.strip():
        raise RuntimeError("No non-sensitive readable text was found in the active window.")
    language_note = "English + Hindi" if not missing else " + ".join(selected) + " only (missing " + ", ".join(missing) + ")"
    return "OCR • " + title[:100] + " • " + language_note + "\n" + safe


def execute_action(action, pyautogui, clipboard, open_shortcut, monitor_bounds=None,
                   text_clicker=None, ocr_reader=None):
    kind = action["kind"]
    try:
        # Every remote action that can alter the focused desktop must fail closed
        # when the foreground window is unknown or matches a sensitive title.
        if kind in {"tap", "swipe", "key_press", "scroll", "type", "home", "back"}:
            _active_window()
        width, height = pyautogui.size()
        left, top = 0, 0
        if monitor_bounds:
            left, top, width, height = monitor_bounds
        if kind == "tap":
            pyautogui.click(left + round(action["x"] * (width - 1)), top + round(action["y"] * (height - 1)))
            return True, "Mouse click completed."
        if kind == "swipe":
            x1, y1 = left + round(action["x1"] * (width - 1)), top + round(action["y1"] * (height - 1))
            x2, y2 = left + round(action["x2"] * (width - 1)), top + round(action["y2"] * (height - 1))
            pyautogui.moveTo(x1, y1)
            pyautogui.mouseDown()
            try:
                pyautogui.moveTo(x2, y2, duration=0.35)
            finally:
                pyautogui.mouseUp()
            return True, "Mouse drag completed."
        if kind == "key_press":
            pyautogui.hotkey(*action["chord"].split("+"))
            return True, "Keyboard shortcut " + action["chord"] + " completed."
        if kind == "type":
            clipboard.copy(action["text"])
            pyautogui.hotkey("ctrl", "v")
            return True, "Text pasted into the focused control."
        if kind == "open_app":
            return (True, "Application shortcut opened.") if open_shortcut(action["appName"]) else (False, "No matching Start Menu shortcut found.")
        if kind == "home":
            pyautogui.hotkey("win", "d")
            return True, "Desktop shown."
        if kind == "back":
            pyautogui.hotkey("alt", "left")
            return True, "Back shortcut sent."
        if kind == "scroll":
            pyautogui.scroll(action["amount"])
            return True, "Scroll completed."
        if kind == "click_text":
            label = action["text"]
            message = text_clicker(label) if text_clicker else click_unique_text(label)
            return True, message
        if kind == "ocr":
            message = ocr_reader() if ocr_reader else read_active_window_ocr(pyautogui)
            return True, str(message)[:12000]
        return False, "Unsupported action."
    except pyautogui.FailSafeException:
        return False, "PyAutoGUI fail-safe triggered; move the pointer away from the top-left corner and re-approve."
    except Exception as exc:
        return False, f"Action failed: {str(exc)[:300]}"
