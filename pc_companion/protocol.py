"""Strict validation for remote actions received by the Windows companion."""
import math

_ALLOWED = {"tap", "swipe", "type", "click_text", "open_app", "home", "back", "scroll"}


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
        clean["text"] = text
    elif kind == "open_app":
        name = action.get("appName")
        if not isinstance(name, str) or not name.strip() or len(name) > 80:
            return None
        if any(ch in name for ch in ("\\", "/", ":", "\x00")) or ".." in name:
            return None
        clean["appName"] = name.strip()
    elif kind == "scroll":
        amount = action.get("amount", 5)
        if not isinstance(amount, int) or isinstance(amount, bool) or not -20 <= amount <= 20 or amount == 0:
            return None
        clean["amount"] = amount
    return clean
