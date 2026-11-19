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
import sys
import time
import urllib.request
from urllib.error import HTTPError, URLError
from urllib.parse import quote

SENSITIVE_WINDOW = re.compile(r"password|passcode|sign.?in|log.?in|bank|otp|pin|payment|checkout|credential|secure", re.I)
SUPPORTED_ACTIONS = {"tap", "swipe", "scroll", "back", "home", "type"}


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
    return True


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
        pyautogui.click(round(normalize_point(action["x"]) * width), round(normalize_point(action["y"]) * height))
        return "tap_executed"
    if kind == "swipe":
        pyautogui.moveTo(round(normalize_point(action["x1"]) * width), round(normalize_point(action["y1"]) * height))
        pyautogui.dragTo(round(normalize_point(action["x2"]) * width), round(normalize_point(action["y2"]) * height), duration=0.35)
        return "swipe_executed"
    if kind == "scroll":
        pyautogui.scroll(-3)
        return "scroll_executed"
    if kind == "back":
        pyautogui.hotkey("alt", "left")
        return "back_executed"
    if kind == "home":
        pyautogui.hotkey("win", "d")
        return "desktop_shown"
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


async def run(args) -> None:
    try:
        import websockets
    except ImportError as exc:
        raise RuntimeError("Install requirements first: python -m pip install -r requirements.txt") from exc

    if not args.base_url.startswith("https://"):
        raise RuntimeError("Use the HTTPS URL of your deployed Shraddha relay.")
    pair = await asyncio.to_thread(join_device, args.base_url, args.pairing_code, args.device_name)
    ws_base = args.base_url.rstrip("/").replace("https://", "wss://", 1)
    url = f"{ws_base}/ws?sessionId={quote(pair['sessionId'])}&deviceId={quote(pair['deviceId'])}"
    headers = {"Authorization": "Bearer " + pair["deviceToken"]}

    print(f"Paired as {args.device_name}. Device ID: {pair['deviceId']}")
    allow_control = await asyncio.to_thread(confirm, "Allow this paired phone to request temporary control of this PC?")
    async with websockets.connect(url, extra_headers=headers, ping_interval=20, max_size=2**20) as ws:
        await ws.send(json.dumps({"type": "permission_update", "permissions": {"remote_control": allow_control}}))
        print("Relay connected. Remote control permission:", "ON" if allow_control else "OFF")
        approved_until = 0.0
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


def main() -> int:
    parser = argparse.ArgumentParser(description="Consent-gated Shraddha AI Windows PC companion")
    parser.add_argument("--base-url", required=True, help="HTTPS URL of your Shraddha relay")
    parser.add_argument("--pairing-code", required=True, help="Six-digit code shown by the Android owner device")
    parser.add_argument("--device-name", default="My Windows PC")
    parser.add_argument("--allow-typing", action="store_true", help="Enable text paste; each paste still asks PC user")
    args = parser.parse_args()
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
