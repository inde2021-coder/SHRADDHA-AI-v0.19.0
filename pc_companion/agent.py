"""Shraddha AI Windows PC Companion.

Pair with an Android-created relay code. Remote control is opt-in on this PC and
each control request requires a local approval dialog. No arbitrary shell commands.
"""
import json
import os
import queue
import ssl
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
import tkinter as tk
from tkinter import messagebox, ttk

import keyring
import mss
import pyautogui
import pyperclip
import websocket
from PIL import Image

from actions import execute_action
from protocol import valid_pairing_ids, validate_remote_action

SERVICE_NAME = "ShraddhaAI-PC-Companion"
CONFIG_PATH = Path(os.environ.get("APPDATA", str(Path.home()))) / "ShraddhaAI" / "pc-companion.json"
MAX_FRAME_BYTES = 2_500_000


def _https_base(value):
    value = value.strip().rstrip("/")
    parsed = urllib.parse.urlparse(value)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        raise ValueError("Relay URL must be HTTPS and must not contain credentials.")
    return value


def join_with_pairing_code(base_url, device_name, code):
    base = _https_base(base_url)
    if len(code) != 6 or not code.isdigit():
        raise ValueError("Enter the six-digit pairing code shown on the Android phone.")
    payload = json.dumps({"deviceName": device_name[:48], "pairingCode": code}).encode("utf-8")
    request = urllib.request.Request(
        base + "/api/device/join", data=payload,
        headers={"Content-Type": "application/json"}, method="POST"
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        result = json.loads(response.read(64 * 1024).decode("utf-8"))
    required = ("sessionId", "deviceId", "deviceToken")
    if not all(isinstance(result.get(key), str) and result[key] for key in required):
        raise RuntimeError("Relay returned an incomplete pairing response.")
    return result


class PcCompanionApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Shraddha AI • Windows PC Companion")
        self.root.geometry("560x470")
        self.root.minsize(520, 430)
        pyautogui.FAILSAFE = True
        pyautogui.PAUSE = 0.04

        self.events = queue.Queue()
        self.ws_app = None
        self.ws_thread = None
        self.send_lock = threading.Lock()
        self.screen_event = threading.Event()
        self.stop_event = threading.Event()
        self.control_grant_until = 0.0
        self.device_token = None
        self._pending_pairing_auth = False
        self.config = self._load_config()

        self.base_var = tk.StringVar(value=self.config.get("baseUrl", "https://"))
        self.name_var = tk.StringVar(value=self.config.get("deviceName", "Windows PC"))
        self.code_var = tk.StringVar()
        self.remote_var = tk.BooleanVar(value=False)
        self.screen_var = tk.BooleanVar(value=False)
        self.status_var = tk.StringVar(value="Not connected")
        self._build_ui()

        self.screen_thread = threading.Thread(target=self._screen_loop, daemon=True)
        self.screen_thread.start()
        self.root.after(120, self._drain_events)
        self.root.bind("<F12>", lambda _event: self.disconnect(emergency=True))
        self.root.protocol("WM_DELETE_WINDOW", self._close)

    def _build_ui(self):
        frame = ttk.Frame(self.root, padding=16)
        frame.pack(fill="both", expand=True)
        ttk.Label(frame, text="SHRADDHA AI  /  PC COMPANION", font=("Segoe UI", 15, "bold")).pack(anchor="w")
        ttk.Label(frame, text="Secure relay pairing • local approval • no arbitrary shell access",
                  font=("Segoe UI", 9)).pack(anchor="w", pady=(2, 12))
        ttk.Label(frame, text="HTTPS Relay URL").pack(anchor="w")
        ttk.Entry(frame, textvariable=self.base_var).pack(fill="x", pady=(2, 8))
        ttk.Label(frame, text="PC device name").pack(anchor="w")
        ttk.Entry(frame, textvariable=self.name_var).pack(fill="x", pady=(2, 8))
        ttk.Label(frame, text="6-digit pairing code from the Android phone").pack(anchor="w")
        ttk.Entry(frame, textvariable=self.code_var, width=12).pack(anchor="w", pady=(2, 8))
        buttons = ttk.Frame(frame)
        buttons.pack(fill="x", pady=(2, 8))
        ttk.Button(buttons, text="Join & Connect", command=self.join_and_connect).pack(side="left")
        ttk.Button(buttons, text="Connect Saved PC", command=self.connect_saved).pack(side="left", padx=6)
        ttk.Button(buttons, text="Disconnect", command=self.disconnect).pack(side="left")
        ttk.Button(buttons, text="Revoke PC Pairing", command=self.revoke_pairing).pack(side="left", padx=6)
        ttk.Separator(frame).pack(fill="x", pady=8)
        ttk.Checkbutton(frame, text="Allow remote mouse / keyboard (approve each request on this PC)",
                        variable=self.remote_var, command=self._publish_permissions).pack(anchor="w", pady=4)
        ttk.Checkbutton(frame, text="Share this PC screen with paired devices",
                        variable=self.screen_var, command=self._publish_permissions).pack(anchor="w", pady=4)
        ttk.Label(frame, textvariable=self.status_var, foreground="#146c43", wraplength=510).pack(anchor="w", pady=(10, 4))
        ttk.Label(frame, text="Safety: every control request opens a local approval dialog. F12 or Disconnect stops access. Screen sharing is off by default.",
                  wraplength=510, font=("Segoe UI", 9)).pack(anchor="w", pady=(6, 0))

    def _load_config(self):
        try:
            return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        except Exception:
            return {}

    def _save_config(self):
        CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
        temp = CONFIG_PATH.with_suffix(".tmp")
        temp.write_text(json.dumps(self.config), encoding="utf-8")
        temp.replace(CONFIG_PATH)

    def _token_for_saved_device(self):
        device_id = self.config.get("deviceId", "")
        if not device_id:
            return None
        try:
            return keyring.get_password(SERVICE_NAME, device_id)
        except Exception:
            return None

    def join_and_connect(self):
        try:
            base = _https_base(self.base_var.get())
            name = self.name_var.get().strip() or "Windows PC"
            joined = join_with_pairing_code(base, name, self.code_var.get().strip())
            # Do not persist a bearer token until the WebSocket handshake proves
            # that the relay accepted it. A successful HTTP join alone is not
            # proof that the new device credential is active.
            self.config = {
                "baseUrl": base, "deviceName": name[:48],
                "sessionId": joined["sessionId"], "deviceId": joined["deviceId"]
            }
            self.device_token = joined["deviceToken"]
            self._pending_pairing_auth = True
            self.status_var.set("Pairing created. Waiting for authenticated relay connection…")
            self.code_var.set("")
            self._connect_socket()
        except Exception as exc:
            if self._pending_pairing_auth:
                self._pending_pairing_auth = False
                self.device_token = None
                self.config = {
                    "baseUrl": self.base_var.get().strip().rstrip("/"),
                    "deviceName": self.name_var.get().strip()[:48] or "Windows PC"
                }
            self.status_var.set(f"Pairing failed: {str(exc)[:180]}")

    def connect_saved(self):
        try:
            if not self.config.get("sessionId") or not self.config.get("deviceId"):
                raise ValueError("No saved PC pairing. Join with the six-digit code first.")
            self.device_token = self._token_for_saved_device()
            if not self.device_token:
                raise ValueError("Windows Credential Manager has no saved token. Pair this PC again.")
            self.base_var.set(self.config["baseUrl"])
            self._connect_socket()
        except Exception as exc:
            self.status_var.set(f"Connect failed: {str(exc)[:180]}")

    def _connect_socket(self):
        if self.ws_thread and self.ws_thread.is_alive():
            self.status_var.set("Already connecting or connected.")
            return
        base = _https_base(self.config.get("baseUrl", self.base_var.get()))
        query = urllib.parse.urlencode({
            "sessionId": self.config["sessionId"], "deviceId": self.config["deviceId"]
        })
        ws_url = base.replace("https://", "wss://", 1) + "/ws?" + query
        token = self.device_token or self._token_for_saved_device()
        if not token:
            raise ValueError("Device token is unavailable. Pair this PC again.")
        self.ws_app = websocket.WebSocketApp(
            ws_url,
            header={"Authorization": f"Bearer {token}"},
            on_open=self._on_open,
            on_message=self._on_message,
            on_error=self._on_error,
            on_close=self._on_close
        )
        self.ws_thread = threading.Thread(
            target=lambda: self.ws_app.run_forever(
                ping_interval=25, ping_timeout=10, sslopt={"cert_reqs": ssl.CERT_REQUIRED}
            ), daemon=True
        )
        self.ws_thread.start()

    def _send_json(self, payload):
        ws = self.ws_app
        if not ws or not ws.sock or not ws.sock.connected:
            return False
        try:
            with self.send_lock:
                ws.send(json.dumps(payload, separators=(",", ":")))
            return True
        except Exception as exc:
            self.events.put(("status", f"Send failed: {str(exc)[:120]}"))
            return False

    def _on_open(self, _ws):
        self.events.put(("opened", None))

    def _on_message(self, _ws, message):
        if not isinstance(message, str):
            return
        try:
            self.events.put(("message", json.loads(message)))
        except Exception:
            self.events.put(("status", "Ignored malformed relay message."))

    def _on_error(self, _ws, error):
        if self._pending_pairing_auth:
            self.events.put(("pairing_failed", f"Relay authentication failed: {str(error)[:140]}"))
        else:
            self.events.put(("status", f"Relay error: {str(error)[:160]}"))

    def _on_close(self, _ws, code, reason):
        self.screen_event.clear()
        self.control_grant_until = 0
        if code == 4003:
            self.events.put(("revoked", None))
        elif self._pending_pairing_auth:
            self.events.put(("pairing_failed", f"Pairing was not authenticated ({code or 'closed'}): {str(reason or '')[:100]}"))
        else:
            self.events.put(("status", f"Disconnected ({code or 'closed'}): {str(reason or '')[:100]}"))

    def _publish_permissions(self):
        remote = bool(self.remote_var.get())
        screen = bool(self.screen_var.get())
        if not remote:
            self.control_grant_until = 0
        if screen:
            self.screen_event.set()
        else:
            self.screen_event.clear()
        self._send_json({
            "type": "permission_update",
            "permissions": {"remote_control": remote, "screen": screen}
        })

    def _drain_events(self):
        try:
            while True:
                kind, payload = self.events.get_nowait()
                if kind == "status":
                    self.status_var.set(payload)
                elif kind == "revoked":
                    device_id = str(self.config.get("deviceId", ""))
                    if device_id:
                        try:
                            keyring.delete_password(SERVICE_NAME, device_id)
                        except Exception:
                            pass
                    self.config.pop("sessionId", None)
                    self.config.pop("deviceId", None)
                    self.device_token = None
                    self._save_config()
                    self.status_var.set("This PC pairing was revoked by the session owner. Join again with a new pairing code.")
                elif kind == "message":
                    self._handle_message(payload)
                elif kind == "pairing_failed":
                    self._pending_pairing_auth = False
                    self.device_token = None
                    self.config = {
                        "baseUrl": self.base_var.get().strip().rstrip("/"),
                        "deviceName": self.name_var.get().strip()[:48] or "Windows PC"
                    }
                    self.status_var.set(payload)
                elif kind == "opened":
                    if self._pending_pairing_auth:
                        self._pending_pairing_auth = False
                        device_id = str(self.config.get("deviceId", ""))
                        try:
                            keyring.set_password(SERVICE_NAME, device_id, self.device_token)
                            self._save_config()
                            self.status_var.set("Relay authenticated. Pairing token saved in Windows Credential Manager.")
                        except Exception:
                            try:
                                keyring.delete_password(SERVICE_NAME, device_id)
                            except Exception:
                                pass
                            self.status_var.set("Relay authenticated for this run only; Windows Credential Manager could not save the token.")
                    else:
                        self.status_var.set("Secure relay connected. Remote control and screen sharing are still off.")
                    self._publish_permissions()
        except queue.Empty:
            pass
        self.root.after(120, self._drain_events)

    def _handle_message(self, msg):
        kind = msg.get("type")
        if kind == "connected":
            peers = msg.get("devices", [])
            role = "Owner" if msg.get("role") == "owner" else "Member"
            self.status_var.set(f"Connected as {role} • {len(peers)} other paired device(s).")
        elif kind == "peer_status":
            self.status_var.set(f"{msg.get('name', 'Device')}: {'online' if msg.get('online') else 'offline'}")
        elif kind == "control_request":
            requester = str(msg.get("requesterName", "Paired device"))[:80]
            requester_id = str(msg.get("fromDeviceId", ""))[:80]
            minutes = max(1, min(5, int(msg.get("requestedMinutes", 5))))
            approved = messagebox.askyesno(
                "Shraddha AI • Remote control request",
                f"{requester} wants to control this PC for up to {minutes} minutes.\n\n"
                "Approve only if you initiated this request on your phone."
            )
            self._send_json({
                "type": "control_response", "requesterDeviceId": requester_id,
                "approved": bool(approved), "minutes": minutes
            })
            self.control_grant_until = time.time() + minutes * 60 if approved else 0
            self.status_var.set("Remote control approved." if approved else "Remote control request denied.")
        elif kind == "remote_action":
            if not self.remote_var.get() or time.time() > self.control_grant_until:
                self.status_var.set("Ignored action: no active local PC approval.")
                return
            action = validate_remote_action(msg.get("action"))
            if not action:
                self.status_var.set("Ignored invalid or unsupported remote action.")
                return
            self._execute(action)
        elif kind == "permission_status":
            self.status_var.set("Peer permissions updated.")

    def _execute(self, action):
        try:
            ok, message = execute_action(action, pyautogui, pyperclip, self._open_start_menu_shortcut)
            self.status_var.set(message)
            if not ok and "fail-safe" in message.lower():
                self.disconnect(emergency=True)
        except Exception as exc:
            self.status_var.set(f"Action failed: {str(exc)[:160]}")

    def _open_start_menu_shortcut(self, app_name):
        roots = [
            Path(os.environ.get("APPDATA", "")) / "Microsoft/Windows/Start Menu/Programs",
            Path(os.environ.get("PROGRAMDATA", "C:/ProgramData")) / "Microsoft/Windows/Start Menu/Programs"
        ]
        query = app_name.casefold()
        for root in roots:
            if not root.exists():
                continue
            for shortcut in root.rglob("*.lnk"):
                if shortcut.stem.casefold() == query:
                    os.startfile(str(shortcut))
                    return True
        return False

    def _screen_loop(self):
        while not self.stop_event.is_set():
            if not self.screen_event.is_set():
                time.sleep(0.25)
                continue
            ws = self.ws_app
            if not ws or not ws.sock or not ws.sock.connected:
                time.sleep(0.25)
                continue
            try:
                with mss.mss() as capture:
                    monitor = capture.monitors[1]
                    shot = capture.grab(monitor)
                image = Image.frombytes("RGB", shot.size, shot.rgb)
                try:
                    image.thumbnail((1280, 720), Image.Resampling.LANCZOS)
                    from io import BytesIO
                    with BytesIO() as output:
                        image.save(output, format="JPEG", quality=55, optimize=True)
                        jpeg = output.getvalue()
                    if jpeg and len(jpeg) <= MAX_FRAME_BYTES:
                        with self.send_lock:
                            if ws.sock and ws.sock.connected:
                                ws.send(bytes((1, 0)) + jpeg, opcode=websocket.ABNF.OPCODE_BINARY)
                finally:
                    image.close()
            except Exception as exc:
                self.events.put(("status", f"Screen capture paused: {str(exc)[:120]}"))
                self.screen_event.clear()
            time.sleep(0.25)

    def revoke_pairing(self):
        if not messagebox.askyesno(
            "Revoke PC pairing?",
            "This permanently revokes this PC's relay pairing. You will need a new six-digit code to connect again. Continue?",
            parent=self.root,
        ):
            return
        device_id = str(self.config.get("deviceId", ""))
        session_id = str(self.config.get("sessionId", ""))
        token = self.device_token or self._token_for_saved_device()
        try:
            base = _https_base(self.config.get("baseUrl", self.base_var.get()))
            if not valid_pairing_ids(session_id, device_id) or not token:
                raise ValueError("Saved PC pairing identifiers are invalid or unavailable. Pair this PC again.")
            payload = json.dumps({
                "sessionId": session_id, "deviceId": device_id,
                "targetDeviceId": device_id
            }).encode("utf-8")
            request = urllib.request.Request(
                base + "/api/device/revoke", data=payload, method="POST",
                headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"}
            )
            with urllib.request.urlopen(request, timeout=20) as response:
                result = json.loads(response.read(16 * 1024).decode("utf-8"))
            if result.get("ok") is not True:
                raise RuntimeError("Relay did not confirm revocation.")
            self.disconnect()
            try:
                keyring.delete_password(SERVICE_NAME, device_id)
            except Exception:
                pass
            self.config = {"baseUrl": base, "deviceName": self.name_var.get().strip()[:48]}
            self._save_config()
            self.device_token = None
            self.status_var.set("This PC pairing was revoked on the relay and its saved token was deleted.")
        except urllib.error.HTTPError as exc:
            detail = exc.read(4096).decode("utf-8", errors="replace")
            self.status_var.set(f"Pairing revoke failed ({exc.code}): {detail[:160]}")
        except Exception as exc:
            self.status_var.set(f"Pairing revoke failed: {str(exc)[:180]}")

    def disconnect(self, emergency=False):
        self.screen_event.clear()
        self.remote_var.set(False)
        self.screen_var.set(False)
        self.control_grant_until = 0
        self._send_json({"type": "permission_update", "permissions": {"remote_control": False, "screen": False}})
        ws = self.ws_app
        if ws:
            try:
                ws.close()
            except Exception:
                pass
        self.status_var.set("Emergency stop: permissions revoked." if emergency else "Disconnected. Permissions revoked.")

    def _close(self):
        self.disconnect()
        self.stop_event.set()
        self.root.destroy()


def main():
    root = tk.Tk()
    PcCompanionApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
