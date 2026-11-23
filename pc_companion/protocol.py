"""Strict validation for remote actions received by the Windows companion."""
import math
import re

_ALLOWED = {"tap", "swipe", "type", "click_text", "open_app", "home", "back", "scroll", "key_press"}
_ALLOWED_CHORDS = {"enter", "escape", "tab", "ctrl+c", "ctrl+f", "ctrl+z", "alt+tab", "alt+left", "alt+right", "ctrl+s", "ctrl+a", "ctrl+v", "ctrl+x", "shift+tab"}
_DEVICE_ID = re.compile(r"^[a-f0-9]{24}$")
_SESSION_ID = re.compile(r"^[A-Za-z0-9_-]{16,64}$")
_SECRET_TEXT = re.compile(r"(?i)(?:\b(?:password|passcode|otp|one[- ]time code|pin|cvv|security code|recovery phrase)\b|पासवर्ड|ओटीपी|पिन)")


def valid_pairing_ids(session_id, device_id):
    """Validate the opaque identifiers accepted by the relay before making requests."""
    return isinstance(session_id, str) and _SESSION_ID.fullmatch(session_id) is not None and \
        isinstance(device_id, str) and _DEVICE_ID.fullmatch(device_id) is not None


def _number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(float(value))


def _normalized(value):
    return _number(value) and 0.0 <= float(value) <= 1.0


def validate_remote_action(action):
    if not isinstance(action, dict):
        return None
    kind = action.get("kind")
    if kind not in _ALLOWED:
        return None
    clean = {"kind": kind}
    if kind == "tap":
        if not _normalized(action.get("x")) or not _normalized(action.get("y")):
            return None
        clean.update(x=float(action["x"]), y=float(action["y"]))
    elif kind == "swipe":
        keys = ("x1", "y1", "x2", "y2")
        if not all(_normalized(action.get(key)) for key in keys):
            return None
        clean.update({key: float(action[key]) for key in keys})
    elif kind in {"type", "click_text"}:
        text = action.get("text")
        limit = 2000 if kind == "type" else 120
        if not isinstance(text, str) or not text.strip() or len(text) > limit:
            return None
        if kind == "type" and _SECRET_TEXT.search(text):
            return None
        clean["text"] = text
    elif kind == "open_app":
        name = action.get("appName")
        if not isinstance(name, str) or not name.strip() or len(name) > 80:
            return None
        if any(ch in name for ch in ("\\", "/", ":", "\x00")) or ".." in name:
            return None
        clean["appName"] = name.strip()
    elif kind == "key_press":
        chord = action.get("chord")
        if not isinstance(chord, str) or chord.lower() not in _ALLOWED_CHORDS:
            return None
        clean["chord"] = chord.lower()
    elif kind == "scroll":
        amount = action.get("amount", 5)
        if not isinstance(amount, int) or isinstance(amount, bool) or not -20 <= amount <= 20 or amount == 0:
            return None
        clean["amount"] = amount
    return clean


def validate_remote_action_envelope(payload, action_kind):
    """Accept the Android protocol's direct action object or legacy wrapped envelope."""
    if not isinstance(payload, dict):
        return None
    candidate = payload.get("action", payload)
    action = validate_remote_action(candidate)
    if not action or action.get("kind") != action_kind:
        return None
    return action
