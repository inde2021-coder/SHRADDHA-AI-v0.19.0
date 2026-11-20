#!/usr/bin/env python3
"""Consent-gated Windows PC companion for Shraddha AI device relay.

This bridge is intentionally opt-in. It never enables control until the PC user
accepts, and text entry requires a second per-action confirmation.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import re
import io
import sys
import time
import urllib.request
from urllib.error import HTTPError, URLError
from urllib.parse import quote

SENSITIVE_WINDOW = re.compile(r"password|passcode|sign.?in|log.?in|bank|otp|pin|payment|checkout|credential|secure", re.I)
SUPPORTED_ACTIONS = {"tap", "swipe", "scroll", "back", "home", "type", "click_text", "ocr", "file_list", "file_read", "file_search", "offline_chat"}


def normalize_point(value: object) -> float:
    try:
        return max(0.0, min(1.0, float(value)))
    except (TypeError, ValueError):
        return 0.0


def is_sensitive_window(title: str) -> bool:
    return bool(SENSITIVE_WINDOW.search(title or ""))


def validate_action(action: dict, allow_typing: bool = False, allow_ocr: bool = False, file_root: str = "", offline_model=None) -> bool:
    kind = action.get("kind")
    if kind not in SUPPORTED_ACTIONS:
        return False
    if kind == "type" and not allow_typing:
        return False
    if kind == "ocr" and not allow_ocr:
        return False
    if kind == "offline_chat" and offline_model is None:
        return False
    if kind == "offline_chat":
        return isinstance(action.get("prompt"), str) and 0 < len(action["prompt"].strip()) <= 4000
    if kind in {"file_list", "file_read", "file_search"} and not file_root:
        return False
    if kind == "file_list":
        return isinstance(action.get("path", ""), str) and len(action.get("path", "")) <= 500
    if kind == "file_read":
        return isinstance(action.get("path"), str) and 0 < len(action["path"]) <= 500
    if kind == "file_search":
        query = action.get("query")
        relative = action.get("path", "")
        limit = action.get("limit", 20)
        return isinstance(relative, str) and len(relative) <= 500 and isinstance(query, str) and 0 < len(query.strip()) <= 120 and isinstance(limit, int) and not isinstance(limit, bool) and 1 <= limit <= 50
    if kind in {"tap", "swipe"}:
        keys = ("x", "y") if kind == "tap" else ("x1", "y1", "x2", "y2")
        try:
            return all(0.0 <= float(action[k]) <= 1.0 for k in keys)
        except (KeyError, TypeError, ValueError):
            return False
    if kind == "type":
        return isinstance(action.get("text"), str) and 0 < len(action["text"]) <= 500
    if kind == "click_text":
        label = action.get("label", action.get("text"))
        return isinstance(label, str) and 0 < len(label.strip()) <= 120 and label.strip() == label
    if kind == "scroll":
        amount = action.get("amount", -3)
        return isinstance(amount, (int, float)) and not isinstance(amount, bool) and -10 <= amount <= 10 and amount != 0
    return kind in {"back", "home", "ocr", "offline_chat"}


def resolve_unique_text_target(candidates: list[dict], label: str) -> dict | None:
    """Resolve one exact, visible UIA label; ambiguity always fails closed."""
    wanted = " ".join(label.casefold().split())
    if not wanted:
        return None
    matches = []
    allowed = {"Button", "Hyperlink", "TabItem", "MenuItem", "CheckBox", "RadioButton"}
    for item in candidates:
        name = " ".join(str(item.get("name", "")).casefold().split())
        if item.get("control_type") in allowed and item.get("visible") is True and name == wanted:
            matches.append(item)
    return matches[0] if len(matches) == 1 else None


def safe_relative_path(root: str, relative: str):
    """Resolve a relative path under an explicitly approved root; reject traversal."""
    from pathlib import Path
    base = Path(root).expanduser().resolve()
    candidate = (base / relative).resolve()
    if candidate != base and base not in candidate.parents:
        raise ValueError("Path escapes the approved file root")
    return base, candidate


def list_safe_files(root: str, relative: str = "") -> list[dict]:
    _, folder = safe_relative_path(root, relative or ".")
    if not folder.exists() or not folder.is_dir():
        raise ValueError("Folder does not exist")
    results = []
    for item in sorted(folder.iterdir(), key=lambda p: p.name.casefold())[:100]:
        if item.is_symlink():
            continue
        results.append({"name": item.name[:180], "type": "folder" if item.is_dir() else "file",
                        "size": item.stat().st_size if item.is_file() else 0})
    return results


def read_safe_text_file(root: str, relative: str) -> str:
    _, candidate = safe_relative_path(root, relative)
    if candidate.is_symlink() or not candidate.is_file():
        raise ValueError("Only regular files can be read")
    if candidate.suffix.lower() not in {".txt", ".md", ".csv", ".json", ".log", ".py", ".js", ".kt", ".html", ".css", ".xml", ".yaml", ".yml"}:
        raise ValueError("File type is not allowed for text preview")
    if candidate.stat().st_size > 256_000:
        raise ValueError("File exceeds 256 KB preview limit")
    return candidate.read_text(encoding="utf-8", errors="replace")[:256_000]



def redact_ocr_text(raw: str) -> str:
    """Redact likely credentials and long identifiers before OCR leaves the PC."""
    if not isinstance(raw, str):
        return ""
    sensitive = re.compile(
        r"(?i)password|passcode|one[ -]?time|otp|pin|cvv|security code|verification code|"
        r"account number|card number|expiry|expiration|पासवर्ड|ओटीपी|पिन|गुप्त कोड|"
        r"सत्यापन कोड|खाता संख्या|कार्ड नंबर|यूपीआई"
    )
    long_number = re.compile(r"(?<!\d)(?:\d[ -]?){4,}(?!\d)")
    output = []
    hide_next = 0
    for line in raw[:24000].splitlines()[:200]:
        value = line.strip()[:500]
        if not value:
            continue
        if sensitive.search(value):
            output.append("[संवेदनशील OCR पंक्ति छिपाई गई]")
            hide_next = 1
            continue
        if hide_next:
            output.append("[संवेदनशील जानकारी छिपाई गई]")
            hide_next -= 1
            continue
        value = long_number.sub("[अंक छिपाए गए]", value)
        output.append(value)
    return "\n".join(output)[:12000]


def search_safe_text_files(root: str, relative: str, query: str, limit: int = 20) -> list[dict]:
    """Search approved text files only, with traversal, depth, count and size limits."""
    from pathlib import Path
    _, folder = safe_relative_path(root, relative or ".")
    if not folder.exists() or not folder.is_dir():
        raise ValueError("Folder does not exist")
    needle = query.strip().casefold()
    if not needle or len(needle) > 120:
        raise ValueError("Search query must be 1-120 characters")
    allowed = {".txt", ".md", ".csv", ".json", ".log", ".py", ".js", ".kt", ".html", ".css", ".xml", ".yaml", ".yml"}
    matches, visited, total_bytes = [], 0, 0
    for current, dirs, files in __import__("os").walk(folder, followlinks=False):
        current_path = Path(current)
        depth = len(current_path.relative_to(folder).parts)
        dirs[:] = [name for name in dirs if not name.startswith(".") and not (current_path / name).is_symlink()]
        if depth >= 3:
            dirs[:] = []
        for name in sorted(files, key=str.casefold):
            path = current_path / name
            if name.startswith(".") or path.is_symlink() or path.suffix.lower() not in allowed:
                continue
            visited += 1
            if visited > 2000:
                return matches
            try:
                size = path.stat().st_size
                if size > 256_000 or total_bytes + size > 4_000_000:
                    continue
                total_bytes += size
                content = path.read_text(encoding="utf-8", errors="replace")
                index = content.casefold().find(needle)
                if index >= 0:
                    start, end = max(0, index - 100), min(len(content), index + len(needle) + 180)
                    matches.append({"path": str(path.relative_to(Path(root).expanduser().resolve()))[:500],
                                    "snippet": redact_ocr_text(content[start:end])[:400]})
                    if len(matches) >= limit:
                        return matches
            except (OSError, ValueError):
                continue
    return matches



def decode_screen_frame(packet: bytes) -> tuple[str, int, bytes]:
    """Decode relay frame [source-id-length][source-id][frame-type][JPEG]."""
    if not isinstance(packet, (bytes, bytearray)) or len(packet) < 4:
        raise ValueError("Invalid or truncated screen frame")
    source_length = packet[0]
    frame_offset = 1 + source_length
    if source_length < 1 or frame_offset >= len(packet):
        raise ValueError("Screen frame source ID is missing")
    try:
        source_id = bytes(packet[1:frame_offset]).decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ValueError("Screen frame source ID is invalid") from exc
    frame_type = packet[frame_offset]
    jpeg = bytes(packet[frame_offset + 1:])
    if frame_type not in (1, 2) or not jpeg or len(jpeg) > 2_500_000:
        raise ValueError("Screen frame type or JPEG payload is invalid")
    return source_id, frame_type, jpeg


def show_phone_screen_frame(jpeg: bytes) -> None:
    """Display a received phone frame locally; requires the optional OpenCV package."""
    try:
        import cv2
        import numpy as np
    except ImportError as exc:
        raise RuntimeError("Install screen viewer dependency: python -m pip install -r requirements-qr.txt") from exc
    image = cv2.imdecode(np.frombuffer(jpeg, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError("Received screen JPEG could not be decoded")
    cv2.imshow("Shraddha AI - paired Android screen (Q to close preview)", image)
    cv2.waitKey(1)


def encode_screen_frame(jpeg: bytes, target_device_id: str = "") -> bytes:
    target = target_device_id.encode("utf-8")
    if not jpeg or len(jpeg) > 2_500_000 or len(target) > 255:
        raise ValueError("Screen frame is empty or exceeds relay limits")
    return bytes((1, len(target))) + target + jpeg


def safe_active_window_region(window) -> dict | None:
    """Return a bounded visible active-window rectangle; fail closed if unknown."""
    try:
        title = str(window.title or "").strip()
        if not title or is_sensitive_window(title) or bool(window.isMinimized):
            return None
        left, top = int(window.left), int(window.top)
        width, height = int(window.width), int(window.height)
        if width < 160 or height < 120 or width > 10000 or height > 10000:
            return None
        if left < 0 or top < 0:
            return None
        return {"left": left, "top": top, "width": width, "height": height}
    except Exception:
        return None


def capture_screen_jpeg(max_width: int = 960, quality: int = 48) -> bytes | None:
    # Share only the active application window, never the entire desktop. If the
    # title/geometry cannot be read, stop sharing rather than guessing.
    try:
        import pygetwindow
        window = pygetwindow.getActiveWindow()
    except Exception:
        return None
    region = safe_active_window_region(window) if window else None
    if not region:
        return None
    import mss
    from PIL import Image
    try:
        with mss.mss() as capture:
            shot = capture.grab(region)
            image = Image.frombytes("RGB", shot.size, shot.rgb)
            image.thumbnail((max_width, 540))
            output = io.BytesIO()
            image.save(output, format="JPEG", quality=max(25, min(70, quality)), optimize=True)
            payload = output.getvalue()
            return payload if len(payload) <= 2_500_000 else None
    except Exception:
        return None


async def share_screen_loop(ws, fps: float, target_device_id: str) -> None:
    interval = 1.0 / max(0.5, min(2.0, fps))
    while True:
        jpeg = await asyncio.to_thread(capture_screen_jpeg)
        if jpeg:
            await ws.send(encode_screen_frame(jpeg, target_device_id))
        await asyncio.sleep(interval)




FILE_CHUNK_BYTES = 48 * 1024
MAX_TRANSFER_BYTES = 100 * 1024 * 1024

def file_transfer_manifest(path: str) -> dict:
    from pathlib import Path
    import hashlib
    source = Path(path).expanduser().resolve()
    if source.is_symlink() or not source.is_file(): raise ValueError('Transfer source must be a regular file')
    size = source.stat().st_size
    if size < 1 or size > MAX_TRANSFER_BYTES: raise ValueError('File transfer is limited to 1 byte–100 MB')
    digest = hashlib.sha256()
    with source.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''): digest.update(block)
    return {'name': source.name[:120], 'size': size, 'sha256': digest.hexdigest(), 'chunkBytes': FILE_CHUNK_BYTES, 'chunks': (size + FILE_CHUNK_BYTES - 1) // FILE_CHUNK_BYTES}

def read_file_chunk(path: str, index: int) -> bytes:
    from pathlib import Path
    if not isinstance(index, int) or index < 0: raise ValueError('Invalid chunk index')
    source = Path(path).expanduser().resolve()
    if source.is_symlink() or not source.is_file() or source.stat().st_size > MAX_TRANSFER_BYTES: raise ValueError('Invalid transfer source')
    with source.open('rb') as stream:
        stream.seek(index * FILE_CHUNK_BYTES)
        data = stream.read(FILE_CHUNK_BYTES)
    if not data: raise ValueError('Chunk outside file')
    return data

def validate_transfer_chunk(index: int, total: int, encoded: str) -> bytes:
    import base64
    if not isinstance(index, int) or not isinstance(total, int) or total < 1 or total > 2200 or not 0 <= index < total: raise ValueError('Invalid chunk index')
    if not isinstance(encoded, str) or len(encoded) > 70000: raise ValueError('Chunk exceeds relay limit')
    try: data = base64.b64decode(encoded, validate=True)
    except Exception as exc: raise ValueError('Invalid base64 chunk') from exc
    if not data or len(data) > FILE_CHUNK_BYTES: raise ValueError('Invalid chunk size')
    return data

def sanitize_clipboard_text(value: str) -> str:
    if not isinstance(value, str) or not value or len(value) > 20000: raise ValueError('Clipboard accepts 1–20,000 characters')
    if re.search(r'(?i)password|passcode|otp|one.time.code|cvv|pin|recovery phrase|seed phrase|पासवर्ड|ओटीपी|पिन|गुप्त कोड', value): raise ValueError('Sensitive clipboard content blocked')
    return value

def build_clipboard_payload(value: str) -> dict:
    import base64, hashlib
    clean = sanitize_clipboard_text(value)
    raw = clean.encode('utf-8')
    return {'payload': base64.b64encode(raw).decode('ascii'), 'sizeBytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(), 'contentType': 'clipboard_text', 'fileName': 'clipboard.txt', 'mimeType': 'text/plain'}

def decode_clipboard_payload(message: dict) -> str:
    import base64, hashlib
    encoded = message.get('payload', '')
    if not isinstance(encoded, str) or len(encoded) > 30000: raise ValueError('Clipboard payload too large')
    raw = base64.b64decode(encoded, validate=True)
    if len(raw) != message.get('sizeBytes') or len(raw) > 20000: raise ValueError('Clipboard size mismatch')
    if hashlib.sha256(raw).hexdigest() != str(message.get('sha256', '')).lower(): raise ValueError('Clipboard integrity check failed')
    return sanitize_clipboard_text(raw.decode('utf-8'))

def parse_voice_device_command(transcript: str) -> dict:
    text = ' '.join(str(transcript or '').casefold().strip().split())
    commands = {'go back': {'kind': 'back'}, 'वापस जाओ': {'kind': 'back'}, 'go home': {'kind': 'home'}, 'होम खोलो': {'kind': 'home'}, 'scroll down': {'kind': 'scroll', 'amount': -3}, 'नीचे स्क्रॉल करो': {'kind': 'scroll', 'amount': -3}, 'scroll up': {'kind': 'scroll', 'amount': 3}, 'ऊपर स्क्रॉल करो': {'kind': 'scroll', 'amount': 3}}
    if text in commands: return commands[text]
    match = re.fullmatch(r'open app ([a-z0-9 ._-]{1,60})', text)
    if match: return {'kind': 'open_app', 'appName': match.group(1).strip()}
    match = re.fullmatch(r'ऐप खोलो ([\w .-]{1,60})', text)
    if match: return {'kind': 'open_app', 'appName': match.group(1).strip()}
    raise ValueError('Voice command not recognized; no action sent')

def reconnect_delay(attempt: int) -> float:
    return min(60.0, 2.0 ** max(0, min(6, int(attempt))))

def listen_voice_device_command(model_path: str) -> dict:
    if not model_path: raise RuntimeError('Offline voice control requires --vosk-model with a downloaded Vosk model folder')
    try:
        import sounddevice as sd
        from vosk import Model, KaldiRecognizer
    except ImportError as exc:
        raise RuntimeError('Install optional voice packages: pip install vosk sounddevice') from exc
    from pathlib import Path
    model_dir = Path(model_path).expanduser().resolve()
    if not model_dir.is_dir(): raise RuntimeError('Vosk model folder does not exist')
    model = Model(str(model_dir))
    recognizer = KaldiRecognizer(model, 16000)
    print('Speak one supported command: go back, go home, scroll up/down, open app Settings')
    audio = sd.rec(int(5 * 16000), samplerate=16000, channels=1, dtype='int16')
    sd.wait()
    if not recognizer.AcceptWaveform(audio.tobytes()): raise RuntimeError('Could not recognize a complete voice command')
    transcript = json.loads(recognizer.Result()).get('text', '')
    print('Recognized voice command:', transcript[:100])
    return parse_voice_device_command(transcript)

def build_phone_action(args) -> dict | None:
    """Build one bounded action for a paired Android phone; the phone still approves control."""
    target = str(getattr(args, "control_phone", "") or "").strip()
    if not target:
        return None
    kind = getattr(args, "phone_action", "back")
    if kind == "tap":
        x, y = float(args.phone_x), float(args.phone_y)
        if not (0 <= x <= 1 and 0 <= y <= 1):
            raise ValueError("Phone tap coordinates must be normalized between 0 and 1")
        return {"kind": "tap", "x": x, "y": y}
    if kind == "swipe":
        points = [float(args.phone_x1), float(args.phone_y1), float(args.phone_x2), float(args.phone_y2)]
        if not all(0 <= point <= 1 for point in points):
            raise ValueError("Phone swipe coordinates must be normalized between 0 and 1")
        return {"kind": "swipe", "x1": points[0], "y1": points[1], "x2": points[2], "y2": points[3]}
    if kind == "type":
        value = str(args.phone_text or "")
        if not value or len(value) > 500:
            raise ValueError("Phone text must contain 1-500 characters")
        return {"kind": "type", "text": value}
    if kind == "click_text":
        value = str(args.phone_text or "").strip()
        if not value or len(value) > 120:
            raise ValueError("Phone button label must contain 1-120 characters")
        return {"kind": "click_text", "text": value}
    if kind == "open_app":
        value = str(args.phone_app or "").strip()
        if not value or len(value) > 120:
            raise ValueError("Phone app name must contain 1-120 characters")
        return {"kind": "open_app", "appName": value}
    if kind == "scroll":
        amount = int(args.phone_scroll)
        if amount == 0 or not -10 <= amount <= 10:
            raise ValueError("Phone scroll amount must be between -10 and 10, excluding 0")
        return {"kind": "scroll", "amount": amount}
    if kind in {"back", "home"}:
        return {"kind": kind}
    raise ValueError("Unsupported Android phone action")


def parse_pairing_qr(payload: str) -> tuple[str, str]:
    """Validate a Shraddha pairing QR deep link without trusting its contents."""
    from urllib.parse import parse_qs, urlparse
    parsed = urlparse(str(payload or "").strip())
    values = parse_qs(parsed.query, strict_parsing=True)
    relay = values.get("relay", [""])[0].rstrip("/")
    code = values.get("code", [""])[0]
    if parsed.scheme != "shraddha" or parsed.netloc != "pair":
        raise ValueError("QR is not a Shraddha device-pairing invitation")
    if not relay.startswith("https://") or len(relay) > 300:
        raise ValueError("Pairing QR must contain a valid HTTPS relay URL")
    if not re.fullmatch(r"[0-9]{6}", code):
        raise ValueError("Pairing QR code is invalid or expired")
    return relay, code


def scan_pairing_qr(timeout_seconds: int = 60) -> tuple[str, str]:
    """Scan a QR only after a local PC user explicitly starts the camera scan."""
    if not confirm("Open the PC camera to scan a Shraddha pairing QR?"):
        raise RuntimeError("Pairing QR scan cancelled by PC user")
    try:
        import cv2
    except ImportError as exc:
        raise RuntimeError("Install QR scanner dependency: python -m pip install -r requirements-qr.txt") from exc
    camera = cv2.VideoCapture(0, cv2.CAP_DSHOW if sys.platform == "win32" else 0)
    if not camera.isOpened():
        camera.release()
        raise RuntimeError("Could not open a camera for QR scanning")
    detector = cv2.QRCodeDetector()
    deadline = time.monotonic() + max(5, min(180, timeout_seconds))
    try:
        while time.monotonic() < deadline:
            ok, frame = camera.read()
            if not ok:
                continue
            payload, _, _ = detector.detectAndDecode(frame)
            cv2.putText(frame, "Scan Shraddha pairing QR - ESC to cancel", (12, 28), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (40, 230, 180), 2)
            cv2.imshow("Shraddha AI secure pairing", frame)
            if cv2.waitKey(1) & 0xFF == 27:
                raise RuntimeError("Pairing QR scan cancelled")
            if payload:
                return parse_pairing_qr(payload)
        raise RuntimeError("Pairing QR scan timed out")
    finally:
        camera.release()
        cv2.destroyAllWindows()


def join_device(base_url: str, pairing_code: str, device_name: str) -> dict:
    url = base_url.rstrip("/") + "/api/device/join"
    payload = json.dumps({"pairingCode": pairing_code.strip(), "deviceName": device_name[:48]}).encode()
    req = urllib.request.Request(url, data=payload, headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            result = json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")[:400]
        raise RuntimeError(f"Pairing failed (HTTP {exc.code}): {detail}") from exc
    except (URLError, TimeoutError) as exc:
        raise RuntimeError(f"Cannot reach Shraddha relay: {exc}") from exc
    for field in ("sessionId", "deviceId", "deviceToken"):
        if not result.get(field):
            raise RuntimeError("Relay returned incomplete pairing credentials.")
    return result


def confirm(prompt: str) -> bool:
    answer = input(prompt + " [y/N]: ").strip().lower()
    return answer in {"y", "yes"}


def control_grant_allows(approved: bool, expires_at: float, now: float,
                         approved_device_id: str, sender_device_id: str) -> bool:
    """A temporary local approval is bound to one paired requester, never the whole session."""
    return bool(approved and expires_at > now and approved_device_id
                and sender_device_id and approved_device_id == sender_device_id)


async def execute_action(action: dict, allow_typing: bool, allow_ocr: bool = False, file_root: str = "", offline_model=None) -> str:
    if not validate_action(action, allow_typing, allow_ocr, file_root, offline_model):
        return "blocked_unsupported_or_invalid_action"

    import pyautogui

    kind = action["kind"]
    width, height = pyautogui.size()
    if kind == "tap":
        pyautogui.click(round(normalize_point(action["x"]) * (width - 1)), round(normalize_point(action["y"]) * (height - 1)))
        return "tap_executed"
    if kind == "swipe":
        pyautogui.moveTo(round(normalize_point(action["x1"]) * (width - 1)), round(normalize_point(action["y1"]) * (height - 1)))
        pyautogui.dragTo(round(normalize_point(action["x2"]) * (width - 1)), round(normalize_point(action["y2"]) * (height - 1)), duration=0.35)
        return "swipe_executed"
    if kind == "scroll":
        pyautogui.scroll(int(action.get("amount", -3)))
        return "scroll_executed"
    if kind == "back":
        pyautogui.hotkey("alt", "left")
        return "back_executed"
    if kind == "home":
        pyautogui.hotkey("win", "d")
        return "desktop_shown"
    if kind == "click_text":
        label = action.get("label", action.get("text", ""))
        try:
            import pygetwindow
            active = pygetwindow.getActiveWindow()
            title = active.title if active else ""
        except Exception:
            active, title = None, ""
        if not active or is_sensitive_window(title):
            return "blocked_unknown_or_sensitive_window"
        try:
            from pywinauto import Desktop
            root = Desktop(backend="uia").get_active()
            candidates = []
            controls = root.descendants()
            for control in controls[:1500]:
                try:
                    info = control.element_info
                    candidates.append({
                        "name": str(info.name or ""),
                        "control_type": str(info.control_type or ""),
                        "visible": bool(info.visible and info.enabled)
                    })
                except Exception:
                    continue
            target = resolve_unique_text_target(candidates, label)
            if target is None:
                return "blocked_missing_or_ambiguous_text"
            if not await asyncio.to_thread(confirm, f"Click the unique PC control labelled {label!r} in {title!r}?"):
                return "text_click_rejected_by_pc_user"
            # Re-resolve immediately before acting so a changed UI cannot redirect the click.
            fresh = root.descendants()
            live = []
            for control in fresh[:1500]:
                try:
                    info = control.element_info
                    live.append((control, {"name": str(info.name or ""), "control_type": str(info.control_type or ""), "visible": bool(info.visible and info.enabled)}))
                except Exception:
                    continue
            current = resolve_unique_text_target([item for _, item in live], label)
            if current is None:
                return "blocked_ui_changed_or_ambiguous"
            for control, item in live:
                if item == current:
                    control.click_input()
                    return "unique_text_click_executed_after_confirmation"
            return "blocked_ui_target_disappeared"
        except Exception:
            return "blocked_ui_automation_unavailable"
    if kind == "offline_chat":
        prompt = action["prompt"].strip()
        if not await asyncio.to_thread(confirm, "Run this prompt on the local offline PC model? " + prompt[:160]):
            return "offline_ai_request_rejected_by_pc_user"
        try:
            result = await asyncio.to_thread(
                offline_model.create_chat_completion,
                messages=[
                    {"role": "system", "content": "You are Shraddha AI running locally on this PC without network access. Be accurate, concise, and state uncertainty. Never claim to have performed external actions."},
                    {"role": "user", "content": prompt}
                ],
                temperature=0.3,
                max_tokens=600
            )
            answer = result.get("choices", [{}])[0].get("message", {}).get("content", "")
            if not isinstance(answer, str) or not answer.strip():
                return "offline_ai_returned_no_answer"
            return json.dumps({"status": "offline_inference_complete", "answer": answer[:10000]}, ensure_ascii=False)
        except Exception as exc:
            return "offline_inference_failed: " + str(exc)[:180]
    if kind in {"ocr", "file_list", "file_read", "file_search"}:
        if kind == "ocr":
            try:
                import pygetwindow
                active = pygetwindow.getActiveWindow()
                title = active.title if active else ""
            except Exception:
                active, title = None, ""
            if not active or is_sensitive_window(title):
                return "blocked_unknown_or_sensitive_window"
            if not await asyncio.to_thread(confirm, f"Read visible text from active PC window {title!r}?"):
                return "ocr_rejected_by_pc_user"
            jpeg = await asyncio.to_thread(capture_screen_jpeg)
            if not jpeg:
                return "ocr_capture_unavailable"
            try:
                import pytesseract
                from PIL import Image
                image = Image.open(io.BytesIO(jpeg))
                text = await asyncio.to_thread(pytesseract.image_to_string, image, lang="eng+hin")
                safe_text = redact_ocr_text(text)
                return json.dumps({"status": "ocr_complete", "window": title[:120], "text": safe_text}, ensure_ascii=False)
            except Exception as exc:
                return "ocr_unavailable_install_tesseract_and_pytesseract: " + str(exc)[:180]
        if not await asyncio.to_thread(confirm, f"Allow {kind} within approved folder {file_root!r}?"):
            return "file_access_rejected_by_pc_user"
        try:
            if kind == "file_list":
                return json.dumps({"status": "file_list", "items": list_safe_files(file_root, action.get("path", ""))}, ensure_ascii=False)
            if kind == "file_search":
                return json.dumps({"status": "file_search", "matches": search_safe_text_files(file_root, action.get("path", ""), action["query"], action.get("limit", 20))}, ensure_ascii=False)
            return json.dumps({"status": "file_read", "path": action["path"], "text": read_safe_text_file(file_root, action["path"])}, ensure_ascii=False)
        except Exception as exc:
            return "file_access_blocked: " + str(exc)[:180]
    if kind == "type":
        try:
            import pygetwindow
            title = pygetwindow.getActiveWindowTitle() or ""
        except Exception:
            title = ""
        if is_sensitive_window(title):
            return "blocked_sensitive_window"
        text = action["text"]
        if not await asyncio.to_thread(confirm, f"Paste this text into the active PC window? {text[:120]!r}"):
            return "typing_rejected_by_pc_user"
        import pyperclip
        pyperclip.copy(text)
        pyautogui.hotkey("ctrl", "v")
        return "text_pasted_after_confirmation"
    return "blocked_unsupported_action"


def load_saved_pairing():
    try:
        import keyring
        raw = keyring.get_password("shraddha-pc-companion", "device-pairing")
        return json.loads(raw) if raw else None
    except Exception as exc:
        raise RuntimeError(f"Windows Credential Manager unavailable: {exc}") from exc


def save_pairing(pair: dict, base_url: str) -> None:
    try:
        import keyring
        keyring.set_password("shraddha-pc-companion", "device-pairing", json.dumps({
            "baseUrl": base_url.rstrip("/"),
            "sessionId": pair["sessionId"],
            "deviceId": pair["deviceId"],
            "deviceToken": pair["deviceToken"]
        }))
    except Exception as exc:
        raise RuntimeError(f"Could not store pairing securely in Windows Credential Manager: {exc}") from exc


async def run_once(args) -> None:
    try:
        import websockets
    except ImportError as exc:
        raise RuntimeError("Install requirements first: python -m pip install -r requirements.txt") from exc

    qr_invite = await asyncio.to_thread(scan_pairing_qr) if args.scan_pairing_qr else None
    pairing_code = args.pairing_code or (qr_invite[1] if qr_invite else "")
    saved = load_saved_pairing() if not pairing_code else None
    base_url = (args.base_url or (qr_invite[0] if qr_invite else "") or (saved or {}).get("baseUrl") or "").rstrip("/")
    if not base_url.startswith("https://"):
        raise RuntimeError("Use --base-url or scan a Shraddha HTTPS pairing QR.")
    if pairing_code:
        pair = await asyncio.to_thread(join_device, base_url, pairing_code, args.device_name)
        save_pairing(pair, base_url)
        print("Pairing credentials saved in Windows Credential Manager.")
        session_id, device_id, device_token = pair["sessionId"], pair["deviceId"], pair["deviceToken"]
    elif saved:
        session_id, device_id, device_token = saved["sessionId"], saved["deviceId"], saved["deviceToken"]
    else:
        raise RuntimeError("First run requires --pairing-code. Later runs reuse Windows Credential Manager.")
    ws_base = base_url.replace("https://", "wss://", 1)
    url = f"{ws_base}/ws?sessionId={quote(session_id)}&deviceId={quote(device_id)}"
    headers = {"Authorization": "Bearer " + device_token}

    print(f"Paired PC device ID: {device_id}")
    offline_model = None
    if args.offline_model:
        from pathlib import Path
        model_path = Path(args.offline_model).expanduser().resolve()
        if not model_path.is_file() or model_path.suffix.lower() != ".gguf" or model_path.stat().st_size < 1_000_000:
            raise RuntimeError("--offline-model must point to a valid local .gguf model file")
        try:
            from llama_cpp import Llama
        except ImportError as exc:
            raise RuntimeError("Install optional offline dependencies: python -m pip install -r requirements-offline.txt") from exc
        import os
        print("Loading local GGUF model; first startup may take time…")
        offline_model = await asyncio.to_thread(
            Llama, model_path=str(model_path), n_ctx=4096,
            n_threads=max(1, min(os.cpu_count() or 2, 8)), verbose=False
        )
        print("Local offline inference is ready. No cloud AI provider is used for offline_chat.")

    if args.voice_control_phone:
        args.control_phone = args.voice_control_phone
        args.phone_action_payload = await asyncio.to_thread(listen_voice_device_command, args.vosk_model)
    phone_action = getattr(args, 'phone_action_payload', None) or build_phone_action(args)
    clipboard_send = None
    if args.send_clipboard_to:
        import pyperclip, secrets
        request_id = secrets.token_urlsafe(18)
        clipboard_send = (request_id, build_clipboard_payload(await asyncio.to_thread(pyperclip.paste)))
    if phone_action and not await asyncio.to_thread(confirm, f"Ask the selected Android phone for permission to run {phone_action['kind']}?"):
        phone_action = None
    phone_action_sent = False
    allow_control = await asyncio.to_thread(confirm, "Allow this paired phone to request temporary control of this PC?")
    share_screen = bool(args.share_screen and await asyncio.to_thread(confirm, "Share this PC screen with paired Shraddha devices? Sensitive active windows will pause sharing."))
    async with websockets.connect(url, extra_headers=headers, ping_interval=20, max_size=3 * 2**20) as ws:
        await ws.send(json.dumps({"type": "permission_update", "permissions": {"remote_control": allow_control, "screen": share_screen}}))
        print("Relay connected. Remote control:", "ON" if allow_control else "OFF", "| Screen share:", "ON" if share_screen else "OFF")
        if phone_action:
            await ws.send(json.dumps({"type": "control_request", "targetDeviceId": args.control_phone, "minutes": 2}))
            print("Waiting for the Android phone owner to approve remote control…")
        if clipboard_send:
            await ws.send(json.dumps({"type": "data_share_request", "targetDeviceId": args.send_clipboard_to, "category": "clipboard", "requestId": clipboard_send[0], "itemLabel": "Clipboard text"}))
            print("Waiting for the Android device owner to approve clipboard sync…")
        approved_until = 0.0
        approved_device_id = ""
        screen_task = asyncio.create_task(share_screen_loop(ws, args.screen_fps, args.screen_target)) if share_screen else None
        try:
            async for raw in ws:
                if isinstance(raw, bytes):
                    if args.view_phone_screen:
                        try:
                            source_id, frame_type, jpeg = decode_screen_frame(raw)
                            if frame_type == 1:
                                show_phone_screen_frame(jpeg)
                            else:
                                print("Ignored remote camera frame from", source_id, "(screen viewer only).")
                        except Exception as exc:
                            print("Phone screen frame:", str(exc)[:200])
                    continue
                try:
                    message = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                kind = message.get("type")
                if kind == "connected":
                    print("Connected to Shraddha AI device session.")
                elif kind == "control_request":
                    requester_id = str(message.get("fromDeviceId", ""))
                    requester_valid = bool(requester_id) and len(requester_id) <= 80
                    if not allow_control or not requester_valid:
                        approved = False
                    else:
                        approved = await asyncio.to_thread(
                            confirm,
                            f"Allow {message.get('requesterName', 'paired phone')} ({requester_id}) to control this PC for up to {message.get('requestedMinutes', 5)} minutes?"
                        )
                    approved_device_id = requester_id if approved else ""
                    approved_until = time.time() + min(5, max(1, int(message.get("requestedMinutes", 5)))) * 60 if approved else 0
                    await ws.send(json.dumps({
                        "type": "control_response",
                        "requesterDeviceId": requester_id,
                        "approved": approved,
                        "minutes": 5 if approved else 0
                    }))
                    print("PC control", "approved for " + requester_id if approved else "denied")
                elif kind == "remote_action":
                    sender_id = str(message.get("fromDeviceId", ""))
                    if not control_grant_allows(allow_control, approved_until, time.time(), approved_device_id, sender_id):
                        print("Blocked remote action: no active grant for this exact paired requester.")
                        continue
                    action = message.get("action") or {}
                    action_kind = str(action.get("kind", "unknown"))[:32]
                    try:
                        result = await execute_action(action, args.allow_typing, args.allow_ocr, args.file_root, offline_model)
                    except Exception as exc:
                        result = "action_error:" + type(exc).__name__
                    print("Remote action:", action_kind, "=>", result[:500])
                    await ws.send(json.dumps({
                        "type": "remote_action_result",
                        "targetDeviceId": sender_id,
                        "actionKind": action_kind,
                        "result": result[:14000]
                    }))
                elif kind == "control_result":
                    if phone_action and message.get("targetDeviceId") == args.control_phone and message.get("approved") is True:
                        await ws.send(json.dumps({"type": "remote_action", "targetDeviceId": args.control_phone, "action": phone_action}))
                        phone_action_sent = True
                        print("Android owner approved. Sending one locally-confirmed action…")
                    elif phone_action:
                        print("Android remote-control request was denied or expired:", str(message.get("reason", "not approved"))[:240])
                        break
                elif kind == "remote_action_result":
                    if phone_action_sent and message.get("fromDeviceId") == args.control_phone:
                        print("Android action result:", str(message.get("result", "no result"))[:1000])
                        break
                elif kind == "data_share_result" and clipboard_send and message.get("requestId") == clipboard_send[0]:
                    if message.get("approved") is True and message.get("category") == "clipboard":
                        await ws.send(json.dumps({"type": "data_share_payload", "targetDeviceId": args.send_clipboard_to, "category": "clipboard", "requestId": clipboard_send[0], **clipboard_send[1]}))
                        print("Clipboard text sent after explicit device approval.")
                    else: print("Clipboard sync was denied.")
                elif kind == "data_share_payload" and message.get("category") == "clipboard" and message.get("contentType") == "clipboard_text":
                    text_value = decode_clipboard_payload(message)
                    if await asyncio.to_thread(confirm, "Copy approved paired-device clipboard text onto this Windows PC?"):
                        import pyperclip
                        await asyncio.to_thread(pyperclip.copy, text_value)
                        print("Clipboard text copied locally.")
                elif kind == "peer_status":
                    print("Paired device", message.get("name"), "online" if message.get("online") else "offline")
        finally:
            if screen_task:
                screen_task.cancel()
                try:
                    await screen_task
                except asyncio.CancelledError:
                    pass
            if args.view_phone_screen:
                try:
                    import cv2
                    cv2.destroyAllWindows()
                except Exception:
                    pass



async def run(args) -> None:
    attempt = 0
    while True:
        try:
            await run_once(args)
            if args.control_phone or args.voice_control_phone: return
            attempt = 0
            print('Relay disconnected. Reconnecting automatically…')
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            if args.no_auto_reconnect: raise
            print('Relay connection interrupted:', str(exc)[:200])
        if args.no_auto_reconnect: return
        delay = reconnect_delay(attempt)
        attempt += 1
        print(f'Retrying secure relay in {delay:.0f}s. Press Ctrl+C to stop.')
        await asyncio.sleep(delay)

def main() -> int:
    parser = argparse.ArgumentParser(description="Consent-gated Shraddha AI Windows PC companion")
    parser.add_argument("--base-url", default="", help="HTTPS URL of your Shraddha relay (saved after pairing)")
    parser.add_argument("--pairing-code", default="", help="Six-digit code shown by the Android owner device (first run or re-pair)")
    parser.add_argument("--scan-pairing-qr", action="store_true", help="Use the PC camera to scan a Shraddha pairing QR; requires requirements-qr.txt")
    parser.add_argument("--control-phone", default="", help="Paired Android device ID to control after its owner approves")
    parser.add_argument("--voice-control-phone", default="", help="Listen for one allowlisted offline voice command for this paired Android device")
    parser.add_argument("--send-clipboard-to", default="", help="Request one-time permission then send current Windows clipboard text to this paired device")
    parser.add_argument("--vosk-model", default="", help="Local Vosk model folder used for offline voice commands")
    parser.add_argument("--no-auto-reconnect", action="store_true", help="Exit instead of retrying after relay disconnection")
    parser.add_argument("--phone-action", choices=["tap", "swipe", "type", "click_text", "open_app", "scroll", "back", "home"], default="back")
    parser.add_argument("--phone-x", type=float, default=0.5); parser.add_argument("--phone-y", type=float, default=0.5)
    parser.add_argument("--phone-x1", type=float, default=0.5); parser.add_argument("--phone-y1", type=float, default=0.8)
    parser.add_argument("--phone-x2", type=float, default=0.5); parser.add_argument("--phone-y2", type=float, default=0.2)
    parser.add_argument("--phone-text", default="", help="Text or exact visible button label for the selected Android action")
    parser.add_argument("--phone-app", default="", help="Installed Android app name for open_app")
    parser.add_argument("--phone-scroll", type=int, default=-3)
    parser.add_argument("--device-name", default="My Windows PC")
    parser.add_argument("--allow-typing", action="store_true", help="Enable text paste; each paste still asks PC user")
    parser.add_argument("--allow-ocr", action="store_true", help="Enable active-window OCR; each request asks PC user")
    parser.add_argument("--file-root", default="", help="Optional approved folder for file listing and text previews; each request asks PC user")
    parser.add_argument("--offline-model", default="", help="Local GGUF model file for private offline inference on this PC")
    parser.add_argument("--share-screen", action="store_true", help="Offer optional live PC screen sharing; still requires a separate local confirmation")
    parser.add_argument("--view-phone-screen", action="store_true", help="Display targeted Android screen frames on this PC; install requirements-qr.txt")
    parser.add_argument("--screen-fps", type=float, default=1.0, help="PC screen share frame rate, clamped to 0.5–2 FPS")
    parser.add_argument("--screen-target", default="", help="Optional paired device ID; blank broadcasts to paired devices")
    parser.add_argument("--forget-pairing", action="store_true", help="Delete saved PC pairing from Windows Credential Manager and exit")
    args = parser.parse_args()
    if args.forget_pairing:
        try:
            import keyring
            keyring.delete_password("shraddha-pc-companion", "device-pairing")
            print("Saved PC pairing deleted.")
            return 0
        except Exception as exc:
            print("Could not remove saved pairing:", exc)
            return 1
    try:
        asyncio.run(run(args))
        return 0
    except KeyboardInterrupt:
        print("PC companion stopped.")
        return 0
    except Exception as exc:
        print("PC companion error:", exc, file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
