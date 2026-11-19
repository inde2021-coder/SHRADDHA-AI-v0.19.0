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
SUPPORTED_ACTIONS = {"tap", "swipe", "scroll", "back", "home", "type", "click_text", "ocr", "file_list", "file_read"}


def normalize_point(value: object) -> float:
    try:
        return max(0.0, min(1.0, float(value)))
    except (TypeError, ValueError):
        return 0.0


def is_sensitive_window(title: str) -> bool:
    return bool(SENSITIVE_WINDOW.search(title or ""))


def validate_action(action: dict, allow_typing: bool = False) -> bool:
    kind = action.get("kind")
    if kind not in SUPPORTED_ACTIONS:
        return False
    if kind == "type" and not allow_typing:
        return False
    if kind in {"tap", "swipe"}:
        keys = ("x", "y") if kind == "tap" else ("x1", "y1", "x2", "y2")
        try:
            return all(0.0 <= float(action[k]) <= 1.0 for k in keys)
        except (KeyError, TypeError, ValueError):
            return False
    if kind == "type":
        return isinstance(action.get("text"), str) and 0 < len(action["text"]) <= 500
    if kind == "click_text":
        label = action.get("label")
        return isinstance(label, str) and 0 < len(label.strip()) <= 120 and label.strip() == label
    if kind == "scroll":
        amount = action.get("amount", -3)
        return isinstance(amount, (int, float)) and not isinstance(amount, bool) and -10 <= amount <= 10 and amount != 0
    return True


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
    from pathlib import Path
    base, folder = safe_relative_path(root, relative or ".")
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


def validate_action(action: dict, allow_typing: bool = False, allow_ocr: bool = False, file_root: str = "") -> bool:
    kind = action.get("kind")
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


async def execute_action(action: dict, allow_typing: bool) -> str:
    if not validate_action(action, allow_typing):
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
            target = resolve_unique_text_target(candidates, action["label"])
            if target is None:
                return "blocked_missing_or_ambiguous_text"
            if not await asyncio.to_thread(confirm, f"Click the unique PC control labelled {action['label']!r} in {title!r}?"):
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
            current = resolve_unique_text_target([item for _, item in live], action["label"])
            if current is None:
                return "blocked_ui_changed_or_ambiguous"
            for control, item in live:
                if item == current:
                    control.click_input()
                    return "unique_text_click_executed_after_confirmation"
            return "blocked_ui_target_disappeared"
        except Exception:
            return "blocked_ui_automation_unavailable"
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


async def run(args) -> None:
    try:
        import websockets
    except ImportError as exc:
        raise RuntimeError("Install requirements first: python -m pip install -r requirements.txt") from exc

    saved = load_saved_pairing() if not args.pairing_code else None
    base_url = (args.base_url or (saved or {}).get("baseUrl") or "").rstrip("/")
    if not base_url.startswith("https://"):
        raise RuntimeError("Use --base-url with the HTTPS URL of your deployed Shraddha relay.")
    if args.pairing_code:
        pair = await asyncio.to_thread(join_device, base_url, args.pairing_code, args.device_name)
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
    allow_control = await asyncio.to_thread(confirm, "Allow this paired phone to request temporary control of this PC?")
    share_screen = bool(args.share_screen and await asyncio.to_thread(confirm, "Share this PC screen with paired Shraddha devices? Sensitive active windows will pause sharing."))
    async with websockets.connect(url, extra_headers=headers, ping_interval=20, max_size=3 * 2**20) as ws:
        await ws.send(json.dumps({"type": "permission_update", "permissions": {"remote_control": allow_control, "screen": share_screen}}))
        print("Relay connected. Remote control:", "ON" if allow_control else "OFF", "| Screen share:", "ON" if share_screen else "OFF")
        approved_until = 0.0
        screen_task = asyncio.create_task(share_screen_loop(ws, args.screen_fps, args.screen_target)) if share_screen else None
        try:
            async for raw in ws:
                if isinstance(raw, bytes):
                        continue
                try:
                    message = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                kind = message.get("type")
                if kind == "connected":
                    print("Connected to Shraddha AI device session.")
                elif kind == "control_request":
                    if not allow_control:
                        approved = False
                    else:
                        approved = await asyncio.to_thread(
                            confirm,
                            f"Allow {message.get('requesterName', 'paired phone')} to control this PC for up to {message.get('requestedMinutes', 5)} minutes?"
                        )
                    approved_until = time.time() + min(5, max(1, int(message.get("requestedMinutes", 5)))) * 60 if approved else 0
                    await ws.send(json.dumps({
                        "type": "control_response",
                        "requesterDeviceId": message.get("fromDeviceId", ""),
                        "approved": approved,
                        "minutes": 5 if approved else 0
                    }))
                    print("PC control", "approved" if approved else "denied")
                elif kind == "remote_action":
                    if time.time() >= approved_until:
                        print("Blocked remote action: local PC approval expired.")
                        continue
                    action = message.get("action") or {}
                    result = await execute_action(action, args.allow_typing)
                    print("Remote action:", action.get("kind"), "=>", result)
                elif kind == "peer_status":
                    print("Paired device", message.get("name"), "online" if message.get("online") else "offline")
        finally:
            if screen_task:
                screen_task.cancel()
                try:
                    await screen_task
                except asyncio.CancelledError:
                    pass



def main() -> int:
    parser = argparse.ArgumentParser(description="Consent-gated Shraddha AI Windows PC companion")
    parser.add_argument("--base-url", default="", help="HTTPS URL of your Shraddha relay (saved after pairing)")
    parser.add_argument("--pairing-code", default="", help="Six-digit code shown by the Android owner device (first run or re-pair)")
    parser.add_argument("--device-name", default="My Windows PC")
    parser.add_argument("--allow-typing", action="store_true", help="Enable text paste; each paste still asks PC user")\n    parser.add_argument("--allow-ocr", action="store_true", help="Enable active-window OCR; each request asks PC user")\n    parser.add_argument("--file-root", default="", help="Optional approved folder for file listing and text previews; each request asks PC user")
    parser.add_argument("--share-screen", action="store_true", help="Offer optional live PC screen sharing; still requires a separate local confirmation")
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
