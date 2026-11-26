"""Allowlisted Windows UI actions. No shell execution or arbitrary process launch."""
import os
import re
import shutil
from pathlib import Path


_SENSITIVE_WINDOW = re.compile(
    r"(?i)password|passcode|otp|one[- ]time|pin|cvv|bank|payment|checkout|"
    r"credential|password manager|vault|sign[ -]?in|login|पासवर्ड|ओटीपी|पिन|बैंक|भुगतान"
)
_SENSITIVE_LINE = re.compile(
    r"(?i)password|passcode|otp|one[- ]time|pin|cvv|security code|verification code|"
    r"recovery phrase|seed phrase|mnemonic|api key|access token|secret key|private key|bearer|authorization|"
    r"account number|card number|bank account|aadhaar|aadhar|pan(?: number)?|ifsc|upi|passport|"
    r"पासवर्ड|ओटीपी|पिन|गुप्त कोड|आधार|पैन|पासपोर्ट|"
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
    active_before, title_before, bounds_before = _active_window()
    active_handle = getattr(active_before, "_hWnd", None)
    from pywinauto import Desktop
    root = Desktop(backend="uia").get_active()
    root_handle = getattr(getattr(root, "element_info", None), "handle", None)
    if active_handle is not None and root_handle is not None and root_handle != active_handle:
        raise RuntimeError("UI Automation target does not match the active foreground window.")
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
    active_now, title_now, bounds_now = _active_window()
    if (title_now != title_before or bounds_now != bounds_before or
            (active_handle is not None and getattr(active_now, "_hWnd", None) != active_handle)):
        raise RuntimeError("The active window changed before text click; retry on the intended window.")
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
    if width * height > 16_000_000:
        raise RuntimeError("The active window is too large for safe OCR capture; resize it and retry.")
    # Re-check focus before consulting external OCR tooling: window changes and
    # minimized windows must fail deterministically without requiring Tesseract.
    active_now, title_now, bounds_now = _active_window()
    if title_now != title or bounds_now != (left, top, width, height):
        raise RuntimeError("The active window changed before OCR capture; retry on the intended window.")
    if getattr(active_now, "isMinimized", False):
        raise RuntimeError("The active window is minimized; OCR capture was cancelled.")
    try:
        import pytesseract
    except ImportError as exc:
        raise RuntimeError("Install the optional pytesseract package and the Windows Tesseract OCR engine with Hindi (hin) and English (eng) language data.") from exc
    # Discover standard Windows installations even when the installer did not
    # add Tesseract to PATH. SHRADDHA_TESSERACT_CMD can override this safely.
    configured = os.environ.get("SHRADDHA_TESSERACT_CMD", "").strip()
    candidates = [
        configured,
        shutil.which("tesseract"),
        os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"), "Tesseract-OCR", "tesseract.exe"),
        os.path.join(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)"), "Tesseract-OCR", "tesseract.exe"),
    ]
    executable = next((candidate for candidate in candidates
                       if candidate and Path(candidate).is_file()), None)
    if executable:
        pytesseract.pytesseract.tesseract_cmd = executable
        tessdata = Path(executable).parent / "tessdata"
        if tessdata.is_dir() and not os.environ.get("TESSDATA_PREFIX"):
            os.environ["TESSDATA_PREFIX"] = str(tessdata)
    try:
        installed = pytesseract.get_languages(config="")
    except Exception as exc:
        raise RuntimeError(
            "Tesseract OCR engine was not found. Install Tesseract to "
            r"C:\\Program Files\\Tesseract-OCR or set SHRADDHA_TESSERACT_CMD."
        ) from exc
    selected, missing = select_ocr_languages(installed)
    if not selected:
        raise RuntimeError("No supported OCR language data found. Install Tesseract eng and/or hin traineddata.")
    image = pyautogui.screenshot(region=(max(0, left), max(0, top), width, height))
    try:
        raw = pytesseract.image_to_string(image, lang="+".join(selected), config="--psm 6", timeout=8)
    except Exception as exc:
        raise RuntimeError("Installed Tesseract OCR language data could not process this window.") from exc
    finally:
        close = getattr(image, "close", None)
        if callable(close):
            close()
    safe = redact_ocr_text(raw)
    if not safe.strip():
        raise RuntimeError("No non-sensitive readable text was found in the active window.")
    language_note = "English + Hindi" if not missing else " + ".join(selected) + " only (missing " + ", ".join(missing) + ")"
    return "OCR • " + title[:100] + " • " + language_note + "\n" + safe


_SAFE_TEXT_EXTENSIONS = {".txt", ".md", ".csv", ".json", ".xml", ".html", ".htm", ".log", ".ini", ".yaml", ".yml"}
_SENSITIVE_PATH_PARTS = {
    "appdata", "application data", "credentials", "credential", ".ssh", ".gnupg",
    ".aws", ".azure", ".config", "wallet", "passwords", "secrets", "tokens",
    "browser", "login data", "cookies", "keychains"
}


def _safe_user_path(relative_path, *, must_exist=True):
    """Resolve a relative path strictly inside the current user's home directory."""
    if not isinstance(relative_path, str) or len(relative_path) > 500 or "\x00" in relative_path:
        raise RuntimeError("Invalid PC file path.")
    normalized = relative_path.replace("\\", "/").strip()
    if normalized.startswith("/") or re.match(r"^[A-Za-z]:", normalized):
        raise RuntimeError("Only paths relative to the Windows user folder are allowed.")
    parts = [part for part in normalized.split("/") if part not in ("", ".")]
    if any(part == ".." or part.startswith(".") for part in parts):
        raise RuntimeError("Parent and hidden paths are blocked.")
    if any(part.casefold() in _SENSITIVE_PATH_PARTS for part in parts):
        raise RuntimeError("Access to sensitive folders is blocked.")
    root = Path.home().resolve(strict=True)
    candidate = root.joinpath(*parts)
    current = root
    for part in parts:
        current = current / part
        if current.is_symlink():
            raise RuntimeError("Symbolic links are blocked for remote file access.")
    resolved = candidate.resolve(strict=must_exist)
    try:
        resolved.relative_to(root)
    except ValueError as exc:
        raise RuntimeError("The requested path is outside the user folder.") from exc
    if any(part.casefold() in _SENSITIVE_PATH_PARTS for part in resolved.relative_to(root).parts):
        raise RuntimeError("Access to sensitive folders is blocked.")
    return root, resolved


def list_user_files(relative_path=""):
    """List a bounded set of non-hidden entries under the current user's home folder."""
    root, folder = _safe_user_path(relative_path)
    if not folder.is_dir():
        raise RuntimeError("The requested path is not a folder.")
    entries = []
    try:
        children = sorted(folder.iterdir(), key=lambda item: (not item.is_dir(), item.name.casefold()))
    except OSError as exc:
        raise RuntimeError("The folder could not be read.") from exc
    for item in children:
        if len(entries) >= 100:
            break
        if item.name.startswith(".") or item.is_symlink() or item.name.casefold() in _SENSITIVE_PATH_PARTS:
            continue
        try:
            resolved_item = item.resolve(strict=True)
            resolved_item.relative_to(root)
            if item.is_dir():
                entries.append({"name": item.name[:160], "type": "folder"})
            elif item.is_file():
                entries.append({"name": item.name[:160], "type": "file", "sizeBytes": item.stat().st_size})
        except (OSError, ValueError):
            # Also rejects Windows junctions/reparse points that resolve outside home.
            continue
    return {"folder": str(folder.relative_to(root)).replace("\\", "/") or ".", "entries": entries, "truncated": len(children) > len(entries)}


def search_user_files(query, relative_path="", max_results=40, max_directories=500, max_files=5000, time_limit_seconds=2.0):
    """Search filenames below a safe user folder with strict traversal/time/result limits."""
    import time
    if not isinstance(query, str) or not 2 <= len(query.strip()) <= 80:
        raise RuntimeError("Search text must contain 2-80 characters.")
    root, folder = _safe_user_path(relative_path)
    if not folder.is_dir():
        raise RuntimeError("The requested search location is not a folder.")
    needle = query.strip().casefold()
    deadline = time.monotonic() + time_limit_seconds
    pending = [(folder, 0)]
    results = []
    visited_dirs = 0
    visited_paths = {str(folder.resolve(strict=True)).casefold()}
    visited_files = 0
    truncated = False
    while pending:
        if time.monotonic() >= deadline or visited_dirs >= max_directories or visited_files >= max_files or len(results) >= max_results:
            truncated = True
            break
        current, depth = pending.pop(0)
        visited_dirs += 1
        try:
            children = list(current.iterdir())
        except OSError:
            continue
        for item in children:
            if time.monotonic() >= deadline or visited_files >= max_files or len(results) >= max_results:
                truncated = True
                break
            if item.is_symlink() or item.name.startswith(".") or item.name.casefold() in _SENSITIVE_PATH_PARTS:
                continue
            try:
                resolved_item = item.resolve(strict=True)
                relative = resolved_item.relative_to(root)
                if any(part.casefold() in _SENSITIVE_PATH_PARTS or part.startswith(".") for part in relative.parts):
                    continue
                if item.is_dir():
                    resolved_key = str(resolved_item).casefold()
                    if depth < 5 and resolved_key not in visited_paths:
                        visited_paths.add(resolved_key)
                        pending.append((resolved_item, depth + 1))
                    continue
                if not item.is_file():
                    continue
                visited_files += 1
                if needle in item.name.casefold():
                    results.append({"name": item.name[:160], "path": str(relative).replace("\\", "/")[:500], "sizeBytes": item.stat().st_size})
            except (OSError, ValueError):
                continue
    return {"query": query.strip(), "location": str(folder.relative_to(root)).replace("\\", "/") or ".", "matches": results, "scannedFiles": visited_files, "truncated": truncated}


def read_user_text_file(relative_path):
    """Read only small, known text formats; never return arbitrary binary or secret files."""
    root, target = _safe_user_path(relative_path)
    if not target.is_file() or target.suffix.casefold() not in _SAFE_TEXT_EXTENSIONS:
        raise RuntimeError("Only supported text files can be previewed.")
    if target.stat().st_size > 128 * 1024:
        raise RuntimeError("Text preview is limited to 128 KB.")
    try:
        content = target.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeError) as exc:
        raise RuntimeError("The file is not readable UTF-8 text.") from exc
    safe_content = redact_ocr_text(content)
    return {
        "file": str(target.relative_to(root)).replace("\\", "/"),
        "content": safe_content[:12000],
        "truncated": len(content) > 12000 or len(content.splitlines()) > 200,
        "redacted": safe_content != content[:12000]
    }


_SAFE_OPEN_EXTENSIONS = {".pdf", ".txt", ".md", ".csv", ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx"}

def open_user_path(relative_path):
    """Open a safe user-owned folder or a non-executable document/media file."""
    import os
    root, target = _safe_user_path(relative_path)
    if target.is_symlink():
        raise RuntimeError("Symbolic links cannot be opened remotely.")
    if target.is_dir():
        os.startfile(str(target))
        return {"opened": str(target.relative_to(root)).replace("\\", "/") or ".", "type": "folder"}
    if not target.is_file() or target.suffix.casefold() not in _SAFE_OPEN_EXTENSIONS:
        raise RuntimeError("This file type is not allowed to open remotely.")
    if target.stat().st_size > 100 * 1024 * 1024:
        raise RuntimeError("Remote open is limited to files up to 100 MB.")
    os.startfile(str(target))
    return {"opened": str(target.relative_to(root)).replace("\\", "/"), "type": "file"}


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
        if kind == "file_list":
            import json
            return True, json.dumps(list_user_files(action.get("path", "")), ensure_ascii=False, indent=2)[:12000]
        if kind == "file_search":
            import json
            result = search_user_files(action.get("query", ""), action.get("path", ""))
            return True, json.dumps(result, ensure_ascii=False, indent=2)[:12000]
        if kind == "file_read":
            import json
            return True, json.dumps(read_user_text_file(action.get("path", "")), ensure_ascii=False, indent=2)[:14000]
        if kind == "file_open":
            import json
            return True, json.dumps(open_user_path(action.get("path", "")), ensure_ascii=False, indent=2)[:12000]
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
