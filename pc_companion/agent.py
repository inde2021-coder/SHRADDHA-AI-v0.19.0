"""Shraddha AI Windows PC Companion.

Pair with an Android-created relay code. Remote control is opt-in on this PC and
each control request requires a local approval dialog. No arbitrary shell commands.
"""
import json
import base64
import struct
import os
import re
import queue
import ssl
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
import tkinter as tk
from tkinter import messagebox, ttk, filedialog

import keyring
import mss
import pyautogui
import pyperclip
import websocket
from PIL import Image, ImageTk

from actions import execute_action
from protocol import valid_pairing_ids, validate_remote_action, validate_remote_action_envelope, remote_action_approval_summary
from e2e_crypto import DeviceE2ECrypto, visual_frame_aad
from windows_hardware import get_hardware_status, execute_hardware_action
from reconnect import reconnect_delay_ms

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
        self.phone_control_peer = None
        self.phone_control_grant_until = 0.0
        self.device_token = None
        self.e2e_crypto = DeviceE2ECrypto()
        self.peer_keys = {}
        self.e2e_lock = threading.RLock()
        self.seen_remote_actions = {}
        self.frame_window = None
        self.frame_label = None
        self.frame_photo = None
        self._pending_pairing_auth = False
        self.pending_pc_send = None
        self.auto_reconnect_enabled = True
        self.reconnect_attempts = 0
        self.reconnect_scheduled = False
        self.pc_incoming = self._load_pc_incoming()
        self.remote_target_var = tk.StringVar()
        self.remote_action_var = tk.StringVar(value="home")
        self.remote_value_var = tk.StringVar()
        self.config = self._load_config()
        now_epoch = time.time()
        for item in self.config.get("remoteActionReceipts", []):
            try:
                peer, action_id, timestamp = item
                if isinstance(peer, str) and isinstance(action_id, str) and now_epoch - float(timestamp) <= 600:
                    self.seen_remote_actions[(peer, action_id)] = now_epoch - max(0.0, now_epoch - float(timestamp))
            except (TypeError, ValueError):
                continue
        self.monitor_labels = self._monitor_labels()
        saved_monitor = str(self.config.get("monitorIndex", 1))
        initial_monitor = next((label for label in self.monitor_labels if label.split(":", 1)[0] == saved_monitor), self.monitor_labels[min(1, len(self.monitor_labels) - 1)])
        self.monitor_var = tk.StringVar(value=initial_monitor)

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
        # Reconnect saved pair automatically after app launch; no LAN discovery is required.
        self.root.after(1500, self._auto_connect_saved_pair)
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
        ttk.Button(buttons, text="Send File to Phone", command=self.choose_file_for_phone).pack(side="left", padx=6)
        ttk.Button(buttons, text="PC Hardware Status", command=self._show_hardware_status).pack(side="left", padx=6)
        ttk.Button(buttons, text="Set PC Volume", command=lambda: self._set_local_hardware("set_volume")).pack(side="left", padx=6)
        ttk.Button(buttons, text="Set PC Brightness", command=lambda: self._set_local_hardware("set_brightness")).pack(side="left", padx=6)
        ttk.Separator(frame).pack(fill="x", pady=8)
        ttk.Checkbutton(frame, text="Allow remote mouse / keyboard (approve each request on this PC)",
                        variable=self.remote_var, command=self._publish_permissions).pack(anchor="w", pady=4)
        ttk.Checkbutton(frame, text="Share this PC screen with paired devices",
                        variable=self.screen_var, command=self._publish_permissions).pack(anchor="w", pady=4)
        monitor_row = ttk.Frame(frame)
        monitor_row.pack(fill="x", pady=(2, 6))
        ttk.Label(monitor_row, text="Display to share/control").pack(side="left")
        ttk.Combobox(monitor_row, textvariable=self.monitor_var, values=self.monitor_labels, state="readonly", width=28).pack(side="left", padx=8)
        monitor_row.winfo_children()[-1].bind("<<ComboboxSelected>>", lambda _event: self._save_monitor_selection())
        ttk.Separator(frame).pack(fill="x", pady=8)
        ttk.Label(frame, text="CONTROL PAIRED ANDROID (phone approval required)").pack(anchor="w")
        ttk.Entry(frame, textvariable=self.remote_target_var).pack(fill="x", pady=3)
        ttk.Label(frame, text="Android device ID").pack(anchor="w")
        ttk.Combobox(frame, textvariable=self.remote_action_var,
                     values=("home", "back", "scroll", "open_app", "type", "tap", "swipe"),
                     state="readonly").pack(fill="x", pady=3)
        ttk.Entry(frame, textvariable=self.remote_value_var).pack(fill="x", pady=3)
        ttk.Label(frame, text="Action value: app name / text / scroll amount").pack(anchor="w")
        ttk.Button(frame, text="Request Phone Control (5 min)", command=self.request_phone_control).pack(anchor="w", pady=3)
        ttk.Button(frame, text="Send approved action to Android", command=self.send_remote_action_to_phone).pack(anchor="w", pady=3)
        ttk.Label(frame, textvariable=self.status_var, foreground="#146c43", wraplength=510).pack(anchor="w", pady=(10, 4))
        ttk.Label(frame, text="Safety: every control request opens a local approval dialog. F12 or Disconnect stops access. Screen sharing is off by default.",
                  wraplength=510, font=("Segoe UI", 9)).pack(anchor="w", pady=(6, 0))
        ttk.Label(frame, text="E2E safety: screen frames are sent only after both devices confirm the same fingerprint over a separate trusted channel. Reject mismatches. Keys are ephemeral and must be verified again after reconnect.",
                  wraplength=510, foreground="#146c43", font=("Segoe UI", 9)).pack(anchor="w", pady=(5, 0))

    def _set_local_hardware(self, kind):
        from tkinter import simpledialog
        label = "PC volume" if kind == "set_volume" else "Display brightness"
        value = simpledialog.askinteger("Shraddha AI • " + label,
                                        "Set percentage (0–100):", minvalue=0, maxvalue=100,
                                        parent=self.root)
        if value is None:
            return
        try:
            ok, result = execute_hardware_action({"kind": kind, "percent": value})
            self.status_var.set(str(result))
            if not ok:
                messagebox.showwarning("Hardware control unavailable", str(result), parent=self.root)
        except Exception as exc:
            self.status_var.set(f"Hardware control failed: {str(exc)[:140]}")

    def _show_hardware_status(self):
        try:
            status = get_hardware_status()
            messagebox.showinfo("Shraddha AI • PC Hardware Status",
                                json.dumps(status, ensure_ascii=False, indent=2)[:12000],
                                parent=self.root)
            self.status_var.set("PC hardware status refreshed. Unsupported vendor sensors are reported as unavailable.")
        except Exception as exc:
            self.status_var.set(f"Hardware status unavailable: {str(exc)[:160]}")

    def _monitor_labels(self):
        try:
            with mss.mss() as capture:
                return [str(index) + ': ' + str(item.get('width', 0)) + 'x' + str(item.get('height', 0)) for index, item in enumerate(capture.monitors)]
        except Exception:
            return ['1: Primary display']

    def _save_monitor_selection(self):
        self.config["monitorIndex"] = self._selected_monitor_index()
        self._save_config()
        self.status_var.set("Display selection saved: " + str(self.monitor_var.get()))

    def _selected_monitor_index(self):
        try:
            index = int(str(self.monitor_var.get()).split(':', 1)[0])
            with mss.mss() as capture:
                return index if 0 <= index < len(capture.monitors) else 1
        except Exception:
            return 1

    def _selected_monitor_bounds(self):
        try:
            with mss.mss() as capture:
                item = capture.monitors[self._selected_monitor_index()]
                return (int(item['left']), int(item['top']), int(item['width']), int(item['height']))
        except Exception:
            return None

    def _claim_remote_action(self, peer_id, action_id):
        now = time.time()
        for key, seen_at in list(self.seen_remote_actions.items()):
            if now - seen_at > 600:
                self.seen_remote_actions.pop(key, None)
        key = (peer_id, action_id)
        if key in self.seen_remote_actions:
            return False
        if len(self.seen_remote_actions) >= 4096:
            oldest = min(self.seen_remote_actions, key=self.seen_remote_actions.get)
            self.seen_remote_actions.pop(oldest, None)
        self.seen_remote_actions[key] = now
        # Persist bounded replay receipts so restarting the Windows companion does not
        # reopen the ten-minute replay window for an already accepted remote command.
        self.config["remoteActionReceipts"] = [
            [peer, action, timestamp]
            for (peer, action), timestamp in sorted(self.seen_remote_actions.items(), key=lambda item: item[1])
        ][-4096:]
        try:
            self._save_config()
        except Exception as exc:
            self.seen_remote_actions.pop(key, None)
            self.events.put(("status", "Remote action rejected: replay protection could not be persisted."))
            return False
        return True

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
            if self.ws_thread and self.ws_thread.is_alive():
                raise ValueError("Disconnect the current PC pairing before joining a different session.")
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

    def _auto_connect_saved_pair(self):
        if not self.auto_reconnect_enabled:
            return
        if self.config.get("sessionId") and self.config.get("deviceId") and self._token_for_saved_device():
            self.connect_saved()

    def _schedule_reconnect(self):
        if not self.auto_reconnect_enabled or self.stop_event.is_set() or self.reconnect_scheduled:
            return
        self.reconnect_attempts = min(self.reconnect_attempts + 1, 8)
        delay = reconnect_delay_ms(self.reconnect_attempts)
        self.reconnect_scheduled = True
        self.root.after(delay, self._try_reconnect)

    def _try_reconnect(self):
        self.reconnect_scheduled = False
        if not self.auto_reconnect_enabled or self.stop_event.is_set():
            return
        if self.ws_thread and self.ws_thread.is_alive():
            self._schedule_reconnect()
            return
        if not self.config.get("sessionId") or not self.config.get("deviceId") or not self._token_for_saved_device():
            self.status_var.set("Remote link paused: saved pairing credentials are unavailable.")
            return
        try:
            self.device_token = self._token_for_saved_device()
            self._connect_socket()
            self.status_var.set("Internet relay reconnecting…")
        except Exception as exc:
            self.status_var.set(f"Reconnect pending: {str(exc)[:120]}")
            self._schedule_reconnect()

    def connect_saved(self):
        self.auto_reconnect_enabled = True
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
        self.reconnect_attempts = 0
        self.events.put(("opened", None))

    def _on_message(self, _ws, message):
        if isinstance(message, (bytes, bytearray)):
            self._handle_binary_frame(bytes(message))
            return
        if not isinstance(message, str):
            return
        try:
            self.events.put(("message", json.loads(message)))
        except Exception:
            self.events.put(("status", "Ignored malformed relay message."))

    def _on_error(self, _ws, error):
        status_code = getattr(error, "status_code", None)
        if self._pending_pairing_auth:
            self.events.put(("pairing_failed", f"Relay authentication failed: {str(error)[:140]}"))
        elif status_code in (401, 403):
            self.events.put(("credential_rejected", status_code))
        else:
            self.events.put(("status", f"Relay error: {str(error)[:160]}"))

    def _on_close(self, _ws, code, reason):
        self._clear_peer_keys()
        self.screen_event.clear()
        self.control_grant_until = 0
        if code == 4003:
            self.events.put(("revoked", None))
        elif self._pending_pairing_auth:
            self.events.put(("pairing_failed", f"Pairing was not authenticated ({code or 'closed'}): {str(reason or '')[:100]}"))
        else:
            self.events.put(("status", f"Disconnected ({code or 'closed'}): {str(reason or '')[:100]}"))
            if self.auto_reconnect_enabled:
                self.events.put(("schedule_reconnect", None))

    def _publish_permissions(self):
        remote = bool(self.remote_var.get())
        screen = bool(self.screen_var.get())
        self.config['monitorIndex'] = self._selected_monitor_index()
        self._save_config()
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
                if kind == "schedule_reconnect":
                    self._schedule_reconnect()
                elif kind == "status":
                    self.status_var.set(payload)
                elif kind == "credential_rejected":
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
                    self.status_var.set(f"Saved PC pairing was rejected by relay (HTTP {payload}). Pair this PC again.")
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
                elif kind == "frame":
                    self._show_frame(*payload)
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

    def _handle_binary_frame(self, frame):
        try:
            if len(frame) < 1:
                return
            source_length = frame[0]
            if source_length < 1 or source_length > 64 or len(frame) < 2 + source_length + 8 + 12 + 16:
                return
            source_id = frame[1:1 + source_length].decode("utf-8")
            frame_type = frame[1 + source_length]
            if frame_type not in (0x81, 0x82):
                return
            sequence_offset = 2 + source_length
            sequence = struct.unpack_from(">Q", frame, sequence_offset)[0]
            nonce = frame[sequence_offset + 8:sequence_offset + 20]
            ciphertext = frame[sequence_offset + 20:]
            with self.e2e_lock:
                state = self.peer_keys.get(source_id)
                if not state or not state["local_confirmed"] or not state["remote_confirmed"]:
                    return
                sequence_key = "recv_screen_sequence" if frame_type == 0x81 else "recv_camera_sequence"
                if sequence <= state[sequence_key]:
                    return
                key = bytes(state["key"])
            local_id = str(self.config.get("deviceId", ""))
            session_id = str(self.config.get("sessionId", ""))
            base_type = frame_type & 0x7f
            aad = visual_frame_aad(session_id, source_id, local_id, base_type, sequence)
            nonce_b64 = base64.b64encode(nonce).decode("ascii").rstrip("=")
            ciphertext_b64 = base64.b64encode(ciphertext).decode("ascii").rstrip("=")
            jpeg = DeviceE2ECrypto.decrypt(key, nonce_b64, ciphertext_b64, aad)
            if len(jpeg) < 4 or jpeg[:2] != b"\\xff\\xd8" or jpeg[-2:] != b"\\xff\\xd9":
                return
            with self.e2e_lock:
                current = self.peer_keys.get(source_id)
                if current is not state or sequence <= current[sequence_key]:
                    return
                current[sequence_key] = sequence
            self.events.put(("frame", (source_id, base_type, jpeg)))
        except Exception:
            self.events.put(("status", "Dropped an invalid, replayed or untrusted encrypted visual frame."))

    def _show_frame(self, source_id, frame_type, jpeg):
        try:
            from io import BytesIO
            image = Image.open(BytesIO(jpeg)).convert("RGB")
            image.thumbnail((1280, 800), Image.Resampling.LANCZOS)
            if not self.frame_window or not self.frame_window.winfo_exists():
                self.frame_window = tk.Toplevel(self.root)
                self.frame_window.title("Shraddha AI • Encrypted paired-device screen")
                self.frame_label = ttk.Label(self.frame_window)
                self.frame_label.pack(fill="both", expand=True, padx=8, pady=8)
            self.frame_photo = ImageTk.PhotoImage(image)
            self.frame_label.configure(image=self.frame_photo,
                                       text=f"{source_id} • {'Screen' if frame_type == 1 else 'Camera'}")
            self.frame_window.title(f"Shraddha AI • {source_id} • {'Screen' if frame_type == 1 else 'Camera'}")
            image.close()
        except Exception as exc:
            self.status_var.set(f"Could not display encrypted peer frame: {str(exc)[:120]}")

    def _handle_message(self, msg):
        kind = msg.get("type")
        if kind == "connected":
            peers = msg.get("devices", [])
            role = "Owner" if msg.get("role") == "owner" else "Member"
            for peer in peers:
                peer_id = str(peer.get("id", ""))
                if peer_id and peer_id != self.config.get("deviceId"):
                    self._send_e2e_key_offer(peer_id)
            self.status_var.set(f"Connected as {role} • {len(peers)} other paired device(s).")
        elif kind == "peer_status":
            peer_id = str(msg.get("deviceId", ""))
            if msg.get("revoked"):
                with self.e2e_lock:
                    state = self.peer_keys.pop(peer_id, None)
                    if state:
                        state["key"][:] = b"\x00" * len(state["key"])
            elif msg.get("online") and peer_id:
                self._send_e2e_key_offer(peer_id)
            self.status_var.set(f"{msg.get('name', 'Device')}: {'online' if msg.get('online') else 'offline'}")
        elif kind == "e2e_key_offer":
            peer_id = str(msg.get("fromDeviceId", ""))
            try:
                key, fingerprint = self.e2e_crypto.derive_peer_key(
                    msg.get("publicKey", ""), self.config.get("sessionId", ""),
                    self.config.get("deviceId", ""), peer_id
                )
                with self.e2e_lock:
                    previous = self.peer_keys.pop(peer_id, None)
                    if previous:
                        previous["key"][:] = b"\x00" * len(previous["key"])
                    self.peer_keys[peer_id] = {"key": bytearray(key), "fingerprint": fingerprint,
                                               "local_confirmed": False, "remote_confirmed": False,
                                               "send_sequence": 0, "recv_screen_sequence": -1,
                                               "recv_camera_sequence": -1}
                approved = messagebox.askyesno(
                    "Shraddha AI • Verify paired-device fingerprint",
                    f"Peer device: {peer_id}\n\nFingerprint:\n{fingerprint}\n\n"
                    "Compare this exact fingerprint with the other device using a separate trusted channel. "
                    "Confirm only if every character matches."
                )
                if approved:
                    sent = self._send_json({"type": "e2e_key_confirm", "targetDeviceId": peer_id,
                                            "fingerprint": fingerprint})
                    with self.e2e_lock:
                        if peer_id in self.peer_keys:
                            self.peer_keys[peer_id]["local_confirmed"] = bool(sent)
                    self.status_var.set("Fingerprint confirmation sent; waiting for the other device.")
                else:
                    with self.e2e_lock:
                        rejected = self.peer_keys.pop(peer_id, None)
                        if rejected:
                            rejected["key"][:] = b"\x00" * len(rejected["key"])
                    self.status_var.set("Peer fingerprint rejected; encrypted sharing remains blocked.")
            except Exception as exc:
                self.status_var.set(f"Rejected peer E2E key offer: {str(exc)[:140]}")
        elif kind == "e2e_key_confirm":
            peer_id = str(msg.get("fromDeviceId", ""))
            with self.e2e_lock:
                state = self.peer_keys.get(peer_id)
                if state and state["fingerprint"] == msg.get("fingerprint"):
                    state["remote_confirmed"] = True
                    trusted = state["local_confirmed"]
                else:
                    trusted = False
            if trusted:
                self.status_var.set("Peer fingerprint confirmed. Mutual trust: READY")
            elif state and state["fingerprint"] == msg.get("fingerprint"):
                self.status_var.set("Peer fingerprint confirmed; waiting for local confirmation.")
            else:
                self.status_var.set("E2E fingerprint mismatch. Do not trust this peer.")
        elif kind == "data_share_request":
            self._handle_data_share_request(msg)
        elif kind == "data_share_result":
            self._handle_data_share_result(msg)
        elif kind == "file_transfer_start":
            self._handle_incoming_file_start(msg)
        elif kind == "file_transfer_ack":
            self._handle_file_ack(msg)
        elif kind == "file_transfer_resume":
            self._handle_file_resume(msg)
        elif kind == "file_transfer_cancel":
            self._handle_file_cancel(msg)
        elif kind == "e2e_message":
            peer_id = str(msg.get("fromDeviceId", ""))
            message_kind = str(msg.get("messageKind", ""))
            action_id = str(msg.get("actionId", ""))
            action_kind = str(msg.get("actionKind", ""))
            if message_kind == "file_transfer_chunk":
                self._handle_incoming_file_chunk(msg, peer_id)
                return
            if message_kind == "remote_action_result":
                self._handle_phone_remote_action_result(msg, peer_id, action_id, action_kind)
                return
            if message_kind != "remote_action" or not re.fullmatch(r"[A-Za-z0-9_-]{12,64}", action_id):
                self.status_var.set("Ignored unsupported encrypted peer message.")
                return
            with self.e2e_lock:
                state = self.peer_keys.get(peer_id)
                if not state or not state["local_confirmed"] or not state["remote_confirmed"]:
                    state = None
                key = bytes(state["key"]) if state else None
            if key is None:
                self.status_var.set("Blocked encrypted action: peer fingerprint is not mutually verified.")
                return
            try:
                aad = f"{self.config.get('sessionId', '')}|{peer_id}|{self.config.get('deviceId', '')}|remote_action|{action_id}".encode("utf-8")
                plain = DeviceE2ECrypto.decrypt(key, msg.get("nonce", ""), msg.get("ciphertext", ""), aad)
                payload = json.loads(plain.decode("utf-8"))
                action = validate_remote_action_envelope(payload, action_kind)
                if not action or action.get("kind") != action_kind:
                    raise ValueError("Action payload or action-kind binding is invalid.")
            except Exception as exc:
                self.status_var.set(f"Encrypted action rejected: {str(exc)[:140]}")
                return
            if not self._claim_remote_action(peer_id, action_id):
                self.status_var.set("Blocked replayed remote action ID.")
                self._send_e2e_message(peer_id, "remote_action_result", {"ok": False, "reason": "Duplicate action ID rejected."}, action_id, action_kind)
                return
            if not self.remote_var.get() or time.time() > self.control_grant_until:
                self.status_var.set("Ignored action: no active local PC approval.")
                self._send_e2e_message(peer_id, "remote_action_result",
                                       {"ok": False, "reason": "No active local PC approval."},
                                       action_id, action_kind)
                return
            approval_summary = remote_action_approval_summary(peer_id, action)
            if not approval_summary:
                self.status_var.set("Blocked remote action: could not create a safe approval summary.")
                self._send_e2e_message(peer_id, "remote_action_result",
                                       {"ok": False, "result": "Local approval summary validation failed."},
                                       action_id, action_kind)
                return
            approved = messagebox.askyesno(
                "Shraddha AI • Approve this exact PC action",
                approval_summary + "\\n\\nApprove only this single action? A separate approval is required for every command."
            )
            if not approved:
                self.status_var.set("Remote action rejected by the local Windows user.")
                self._send_e2e_message(peer_id, "remote_action_result",
                                       {"ok": False, "result": "Rejected by local Windows user."},
                                       action_id, action_kind)
                return
            self._execute(action, peer_id, action_id)
        elif kind == "remote_action":
            self.status_var.set("Blocked plaintext remote action. E2E encryption is mandatory.")
        elif kind == "control_result":
            target_id = str(msg.get("targetDeviceId", ""))
            if target_id != self.phone_control_peer:
                return
            if msg.get("approved") is True:
                minutes = max(1, min(5, int(msg.get("expiresInMinutes", 5))))
                self.phone_control_grant_until = time.time() + minutes * 60
                self.status_var.set(f"Android approved control for {minutes} minutes. Each action still needs phone approval.")
            else:
                self.phone_control_grant_until = 0
                self.status_var.set(f"Android denied control request: {str(msg.get('reason', 'Denied'))[:140]}")
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
        elif kind == "permission_status":
            self.status_var.set("Peer permissions updated.")

    def request_phone_control(self):
        peer_id = self.remote_target_var.get().strip()
        if not peer_id or not self.is_e2e_trusted(peer_id):
            self.status_var.set("Select an Android device with a mutually verified fingerprint first.")
            return
        self.phone_control_peer = peer_id
        self.phone_control_grant_until = 0
        if self._send_json({"type": "control_request", "targetDeviceId": peer_id, "minutes": 5}):
            self.status_var.set("Phone control request sent. Unlock Android and approve the request there.")
        else:
            self.status_var.set("Could not request phone control: relay is disconnected.")

    def send_remote_action_to_phone(self):
        peer_id = self.remote_target_var.get().strip()
        if peer_id != self.phone_control_peer or time.time() >= self.phone_control_grant_until:
            self.status_var.set("First request Phone Control and approve the five-minute session on Android.")
            return
        kind = self.remote_action_var.get()
        value = self.remote_value_var.get().strip()
        if not peer_id or not self.is_e2e_trusted(peer_id):
            self.status_var.set("Select a paired Android device with a mutually verified fingerprint first.")
            return
        action = {"kind": kind}
        if kind == "open_app":
            action["appName"] = value
        elif kind == "type":
            action["text"] = value
        elif kind == "scroll":
            try:
                action["amount"] = int(value)
            except ValueError:
                self.status_var.set("Scroll amount must be an integer from -20 to 20.")
                return
        elif kind in ("set_volume", "set_brightness"):
            try:
                action["percent"] = int(value)
            except ValueError:
                self.status_var.set("Enter a percentage from 0 to 100.")
                return
        elif kind == "power":
            action["operation"] = value.lower()
        elif kind == "tap":
            action.update(x=0.5, y=0.5)
        elif kind == "swipe":
            action.update(x1=0.5, y1=0.75, x2=0.5, y2=0.25)
        if kind in ("open_app", "type") and not value:
            self.status_var.set("Enter the app name or text for this action.")
            return
        import secrets
        action_id = secrets.token_urlsafe(18).replace("-", "_")
        sent = self._send_e2e_message(peer_id, "remote_action", action, action_id, kind)
        self.status_var.set("Encrypted action sent. Android will show its own consent and per-action approval." if sent
                            else "Could not send action. Check connection and peer verification.")

    def _handle_phone_remote_action_result(self, msg, peer_id, action_id, action_kind):
        if not re.fullmatch(r"[A-Za-z0-9_-]{12,64}", action_id):
            return
        with self.e2e_lock:
            state = self.peer_keys.get(peer_id)
            key = bytes(state["key"]) if state and state["local_confirmed"] and state["remote_confirmed"] else None
        if key is None:
            return
        try:
            aad = f"{self.config.get('sessionId', '')}|{peer_id}|{self.config.get('deviceId', '')}|remote_action_result|{action_id}".encode("utf-8")
            plain = DeviceE2ECrypto.decrypt(key, msg.get("nonce", ""), msg.get("ciphertext", ""), aad)
            result = json.loads(plain.decode("utf-8"))
            self.status_var.set(f"Android {action_kind}: {str(result.get('result', 'No result'))[:220]}")
        except Exception as exc:
            self.status_var.set(f"Could not verify Android action result: {str(exc)[:120]}")

    def _pc_transfer_state_path(self):
        return CONFIG_PATH.parent / "incoming-transfers.json"

    def _load_pc_incoming(self):
        try:
            raw = json.loads(self._pc_transfer_state_path().read_text(encoding="utf-8"))
            restored = {}
            if not isinstance(raw, dict):
                return restored
            for transfer_id, item in raw.items():
                if (not re.fullmatch(r"[A-Za-z0-9_-]{12,64}", transfer_id) or
                        not isinstance(item, dict) or
                        not isinstance(item.get("path"), str) or len(item["path"]) > 1024 or
                        not isinstance(item.get("peer"), str) or
                        not re.fullmatch(r"[a-f0-9]{64}", str(item.get("sha256", "")))):
                    continue
                part = Path(item["path"] + ".shraddha-part")
                size = int(item.get("size", 0))
                chunks = int(item.get("chunks", 0))
                if not part.is_file() or part.stat().st_size != size or not 1 <= size <= 20_000_000:
                    continue
                if chunks != (size + 47999) // 48000:
                    continue
                received = {int(i) for i in item.get("received", []) if isinstance(i, int) and 0 <= i < chunks}
                restored[transfer_id] = {
                    "peer": item["peer"], "path": item["path"], "size": size,
                    "chunks": chunks, "sha256": item["sha256"], "received": received
                }
            return restored
        except Exception:
            return {}

    def _persist_pc_incoming(self):
        try:
            path = self._pc_transfer_state_path()
            path.parent.mkdir(parents=True, exist_ok=True)
            payload = {
                transfer_id: {
                    "peer": item["peer"], "path": item["path"], "size": item["size"],
                    "chunks": item["chunks"], "sha256": item["sha256"],
                    "received": sorted(item["received"])
                }
                for transfer_id, item in self.pc_incoming.items()
            }
            temp = path.with_suffix(".tmp")
            temp.write_text(json.dumps(payload), encoding="utf-8")
            temp.replace(path)
        except Exception as exc:
            self.status_var.set(f"Could not save transfer recovery state: {str(exc)[:100]}")

    def choose_file_for_phone(self):
        peer_id = __import__("tkinter.simpledialog", fromlist=["askstring"]).askstring(
            "Send to paired phone", "Enter the paired Android device ID:", parent=self.root)
        if not peer_id:
            return
        path = filedialog.askopenfilename(title="Choose a file to send to Android")
        if not path:
            return
        try:
            file_path = Path(path)
            size = file_path.stat().st_size
            if size < 1 or size > 20_000_000:
                raise ValueError("Relay currently permits files up to 20 MB per transfer.")
            import hashlib, secrets
            digest = hashlib.sha256(file_path.read_bytes()).hexdigest()
            request_id = secrets.token_urlsafe(18).replace("-", "_")
            transfer_id = secrets.token_urlsafe(18).replace("-", "_")
            self.pending_pc_send = {
                "peer": peer_id.strip(), "path": str(file_path), "name": file_path.name[:120],
                "size": size, "sha256": digest, "requestId": request_id, "transferId": transfer_id
            }
            sent = self._send_json({
                "type": "data_share_request", "targetDeviceId": peer_id.strip(),
                "category": "documents", "requestId": request_id,
                "itemLabel": file_path.name[:120]
            })
            if not sent:
                self.pending_pc_send = None
                raise RuntimeError("Relay is not connected.")
            self.status_var.set("File selected. Waiting for the Android user to approve Documents access.")
        except Exception as exc:
            self.status_var.set(f"Could not prepare file: {str(exc)[:160]}")

    def _handle_data_share_request(self, msg):
        peer_id = str(msg.get("fromDeviceId", ""))[:80]
        request_id = str(msg.get("requestId", ""))
        category = str(msg.get("category", ""))
        if not peer_id or category != "documents" or not re.fullmatch(r"[A-Za-z0-9_-]{12,64}", request_id):
            return
        approved = messagebox.askyesno(
            "Shraddha AI • Incoming file permission",
            f"Paired device {peer_id} requests Documents sharing.\n\nApprove only if you initiated this transfer."
        )
        self._send_json({
            "type": "data_share_response", "requesterDeviceId": peer_id,
            "category": category, "requestId": request_id,
            "approved": bool(approved), "minutes": 5
        })

    def _handle_data_share_result(self, msg):
        pending = self.pending_pc_send
        if not pending or msg.get("requestId") != pending["requestId"]:
            return
        if not msg.get("approved"):
            self.pending_pc_send = None
            self.status_var.set("Android user denied file transfer.")
            return
        chunks = (pending["size"] + 49151) // 48000
        sent = self._send_json({
            "type": "file_transfer_start", "targetDeviceId": pending["peer"],
            "requestId": pending["requestId"], "transferId": pending["transferId"],
            "fileName": pending["name"], "sizeBytes": pending["size"],
            "chunkCount": chunks, "sha256": pending["sha256"]
        })
        self.status_var.set("Transfer manifest sent; waiting for Android user to accept and choose a save location." if sent
                            else "Could not send transfer manifest.")

    def _handle_file_ack(self, msg):
        pending = self.pending_pc_send
        if not pending or msg.get("transferId") not in (None, "", pending["transferId"]):
            return
        index = int(msg.get("index", -2))
        if index == -1 and msg.get("ok") and not msg.get("complete"):
            self._send_pc_file_chunks(list(range((pending["size"] + 49151) // 48000)))
        elif msg.get("complete") and msg.get("ok"):
            self.pending_pc_send = None
            self.status_var.set("Android receiver verified and saved the file (completion acknowledged).")
        elif index >= 0:
            self.status_var.set(f"Android acknowledged file chunk {index + 1}.")

    def _send_pc_file_chunks(self, indexes):
        pending = self.pending_pc_send
        if not pending or not self.is_e2e_trusted(pending["peer"]):
            self.status_var.set("File send blocked: verify the Android peer fingerprint on both devices first.")
            return
        try:
            with Path(pending["path"]).open("rb") as source:
                for index in indexes:
                    source.seek(index * 48000)
                    data = source.read(48000)
                    if not data:
                        raise ValueError(f"Missing file bytes for chunk {index}")
                    self._send_encrypted_file_chunk(pending["peer"], pending["transferId"], index, data)
            self.status_var.set(f"Sent {len(indexes)} file chunks. Waiting for receiver SHA-256 verification.")
        except Exception as exc:
            self.status_var.set(f"File transfer paused: {str(exc)[:160]}")

    def _send_encrypted_file_chunk(self, peer_id, transfer_id, index, data):
        with self.e2e_lock:
            state = self.peer_keys.get(peer_id)
            if not state or not state["local_confirmed"] or not state["remote_confirmed"]:
                raise ValueError("Peer is not mutually verified")
            key = bytes(state["key"])
        source_id = str(self.config.get("deviceId", ""))
        session_id = str(self.config.get("sessionId", ""))
        aad = f"{session_id}|{source_id}|{peer_id}|file_transfer_chunk|{transfer_id}:{index}".encode("utf-8")
        payload = json.dumps({"payload": base64.b64encode(data).decode("ascii")}, separators=(",", ":")).encode("utf-8")
        nonce, ciphertext = DeviceE2ECrypto.encrypt(key, payload, aad)
        if not self._send_json({
            "type": "e2e_message", "targetDeviceId": peer_id,
            "messageKind": "file_transfer_chunk", "transferId": transfer_id,
            "index": index, "nonce": nonce, "ciphertext": ciphertext
        }):
            raise RuntimeError(f"Relay send failed at chunk {index}")

    def _handle_incoming_file_start(self, msg):
        peer_id = str(msg.get("fromDeviceId", ""))
        transfer_id = str(msg.get("transferId", ""))
        size = int(msg.get("sizeBytes", 0))
        chunks = int(msg.get("chunkCount", 0))
        digest = str(msg.get("sha256", ""))
        if not self.is_e2e_trusted(peer_id):
            self._send_json({"type": "file_transfer_cancel", "targetDeviceId": peer_id,
                             "transferId": transfer_id, "reason": "peer_not_verified"})
            return
        if (not re.fullmatch(r"[A-Za-z0-9_-]{12,64}", transfer_id) or size < 1 or size > 20_000_000
                or chunks != (size + 49151) // 48000 or not re.fullmatch(r"[a-f0-9]{64}", digest)):
            self.status_var.set("Rejected invalid incoming file manifest.")
            return
        destination = filedialog.asksaveasfilename(
            title="Choose where to save incoming file",
            initialfile=Path(str(msg.get("fileName", "received-file"))).name
        )
        if not destination:
            self._send_json({"type": "file_transfer_cancel", "targetDeviceId": peer_id,
                             "transferId": transfer_id, "reason": "user_cancelled"})
            return
        self.pc_incoming[transfer_id] = {
            "peer": peer_id, "path": destination, "size": size, "chunks": chunks,
            "sha256": digest, "received": set()
        }
        with open(destination + ".shraddha-part", "wb") as output:
            output.truncate(size)
        self._persist_pc_incoming()
        self._send_json({"type": "file_transfer_ack", "targetDeviceId": peer_id,
                         "transferId": transfer_id, "index": -1, "complete": False, "ok": True})
        self.status_var.set(f"Receiving {Path(destination).name} from paired Android device…")

    def _handle_incoming_file_chunk(self, msg, peer_id):
        transfer_id = str(msg.get("transferId", ""))
        transfer = self.pc_incoming.get(transfer_id)
        if not transfer or transfer["peer"] != peer_id or not self.is_e2e_trusted(peer_id):
            return
        index = int(msg.get("index", -1))
        if not 0 <= index < transfer["chunks"]:
            return
        try:
            with self.e2e_lock:
                state = self.peer_keys.get(peer_id)
                key = bytes(state["key"]) if state else None
            if key is None:
                return
            aad = f"{self.config.get('sessionId', '')}|{peer_id}|{self.config.get('deviceId', '')}|file_transfer_chunk|{transfer_id}:{index}".encode("utf-8")
            plain = DeviceE2ECrypto.decrypt(key, msg.get("nonce", ""), msg.get("ciphertext", ""), aad)
            payload = json.loads(plain.decode("utf-8"))
            data = base64.b64decode(payload["payload"], validate=True)
            expected = transfer["size"] - index * 48000 if index == transfer["chunks"] - 1 else 48000
            if len(data) != expected:
                raise ValueError("Chunk length mismatch")
            if index not in transfer["received"]:
                with open(transfer["path"] + ".shraddha-part", "r+b") as output:
                    output.seek(index * 48000)
                    output.write(data)
                transfer["received"].add(index)
                self._persist_pc_incoming()
            self._send_json({"type": "file_transfer_ack", "targetDeviceId": peer_id,
                             "transferId": transfer_id, "index": index, "complete": False, "ok": True})
            if len(transfer["received"]) == transfer["chunks"]:
                import hashlib
                digest = hashlib.sha256(Path(transfer["path"] + ".shraddha-part").read_bytes()).hexdigest()
                if digest != transfer["sha256"]:
                    raise ValueError("SHA-256 mismatch; refusing to save corrupted file")
                os.replace(transfer["path"] + ".shraddha-part", transfer["path"])
                self._send_json({"type": "file_transfer_ack", "targetDeviceId": peer_id,
                                 "transferId": transfer_id, "index": -1, "complete": True, "ok": True})
                self.pc_incoming.pop(transfer_id, None)
                self._persist_pc_incoming()
                self.status_var.set("Incoming file saved and SHA-256 verified.")
        except Exception as exc:
            self.status_var.set(f"Incoming file rejected: {str(exc)[:140]}")
            self._send_json({"type": "file_transfer_cancel", "targetDeviceId": peer_id,
                             "transferId": transfer_id, "reason": "integrity_or_protocol_error"})
            Path(transfer["path"] + ".shraddha-part").unlink(missing_ok=True)
            self.pc_incoming.pop(transfer_id, None)
            self._persist_pc_incoming()

    def _handle_file_resume(self, msg):
        transfer_id = str(msg.get("transferId", ""))
        incoming = self.pc_incoming.get(transfer_id)
        if incoming:
            peer_id = str(msg.get("fromDeviceId", incoming["peer"]))
            if peer_id != incoming["peer"] or not self.is_e2e_trusted(peer_id):
                return
            missing = [i for i in range(incoming["chunks"]) if i not in incoming["received"]]
            self._send_json({"type": "file_transfer_resume", "targetDeviceId": peer_id,
                             "transferId": transfer_id, "missingIndexes": missing})
            self.status_var.set(f"Resume response sent: {len(missing)} missing chunks.")
            return
        pending = self.pending_pc_send
        if not pending or transfer_id != pending["transferId"]:
            return
        missing = msg.get("missingIndexes", [])
        if isinstance(missing, list):
            self._send_pc_file_chunks([i for i in missing if isinstance(i, int) and 0 <= i < (pending["size"] + 49151) // 48000])

    def _handle_file_cancel(self, msg):
        transfer_id = str(msg.get("transferId", ""))
        if self.pending_pc_send and self.pending_pc_send.get("transferId") == transfer_id:
            self.pending_pc_send = None
        incoming = self.pc_incoming.pop(transfer_id, None)
        if incoming:
            Path(incoming["path"] + ".shraddha-part").unlink(missing_ok=True)
            self._persist_pc_incoming()
        self.status_var.set(f"File transfer cancelled: {str(msg.get('reason', 'cancelled'))[:120]}")

    def _send_e2e_key_offer(self, peer_id):
        if not peer_id or peer_id == self.config.get("deviceId"):
            return False
        return self._send_json({
            "type": "e2e_key_offer", "targetDeviceId": peer_id,
            "publicKey": self.e2e_crypto.public_key_base64()
        })

    def is_e2e_trusted(self, peer_id):
        with self.e2e_lock:
            state = self.peer_keys.get(peer_id)
            return bool(state and state["local_confirmed"] and state["remote_confirmed"])

    def _clear_peer_keys(self):
        with self.e2e_lock:
            for state in self.peer_keys.values():
                state["key"][:] = b"\x00" * len(state["key"])
            self.peer_keys.clear()

    def _send_e2e_message(self, peer_id, kind, payload, action_id, action_kind=""):
        with self.e2e_lock:
            state = self.peer_keys.get(peer_id)
            if not state or not state["local_confirmed"] or not state["remote_confirmed"]:
                return False
            key = bytes(state["key"])
        source_id = str(self.config.get("deviceId", ""))
        target_id = peer_id
        session_id = str(self.config.get("sessionId", ""))
        aad = f"{session_id}|{source_id}|{target_id}|{kind}|{action_id}".encode("utf-8")
        nonce, ciphertext = DeviceE2ECrypto.encrypt(key, json.dumps(payload, separators=(",", ":")).encode("utf-8"), aad)
        return self._send_json({
            "type": "e2e_message", "targetDeviceId": target_id, "messageKind": kind,
            "actionId": action_id, "actionKind": action_kind,
            "nonce": nonce, "ciphertext": ciphertext
        })

    def _execute(self, action, peer_id=None, action_id=None):
        ok = False
        message = "Action failed."
        try:
            if action.get("kind") in {"hardware_status", "set_volume", "set_brightness", "power"}:
                ok, result = execute_hardware_action(
                    action,
                    confirm_power=lambda operation: messagebox.askyesno(
                        "Shraddha AI • Confirm Windows power action",
                        f"Are you sure you want to {operation} this PC now?\\n\\n"
                        "This confirmation must be approved locally on the Windows PC."
                    )
                )
                message = json.dumps(result, ensure_ascii=False)[:1200] if isinstance(result, dict) else str(result)
            else:
                ok, message = execute_action(action, pyautogui, pyperclip, self._open_start_menu_shortcut, self._selected_monitor_bounds())
            self.status_var.set(message)
            if peer_id and action_id:
                sent = self._send_e2e_message(peer_id, "remote_action_result",
                                              {"ok": bool(ok), "result": str(message)[:240]},
                                              action_id, action.get("kind", ""))
                if not sent:
                    self.status_var.set("Action finished, but encrypted result could not be delivered.")
            if not ok and "fail-safe" in message.lower():
                self.disconnect(emergency=True)
        except Exception as exc:
            message = f"Action failed: {str(exc)[:160]}"
            self.status_var.set(message)
            if peer_id and action_id:
                self._send_e2e_message(peer_id, "remote_action_result",
                                       {"ok": False, "result": message[:240]}, action_id, action.get("kind", ""))

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
                    monitor = capture.monitors[self._selected_monitor_index()]
                    shot = capture.grab(monitor)
                image = Image.frombytes("RGB", shot.size, shot.rgb)
                try:
                    image.thumbnail((1280, 720), Image.Resampling.LANCZOS)
                    from io import BytesIO
                    with BytesIO() as output:
                        image.save(output, format="JPEG", quality=55, optimize=True)
                        jpeg = output.getvalue()
                    if jpeg and len(jpeg) <= MAX_FRAME_BYTES:
                        source_id = str(self.config.get("deviceId", ""))
                        session_id = str(self.config.get("sessionId", ""))
                        with self.e2e_lock:
                            targets = [
                                (peer_id, state) for peer_id, state in self.peer_keys.items()
                                if state["local_confirmed"] and state["remote_confirmed"]
                            ]
                        for peer_id, state in targets:
                            target_bytes = peer_id.encode("utf-8")
                            if not target_bytes or len(target_bytes) > 64:
                                continue
                            with self.e2e_lock:
                                current = self.peer_keys.get(peer_id)
                                if current is not state or not current["local_confirmed"] or not current["remote_confirmed"]:
                                    continue
                                sequence = current["send_sequence"]
                                current["send_sequence"] += 1
                                key = bytes(current["key"])
                            aad = visual_frame_aad(session_id, source_id, peer_id, 1, sequence)
                            nonce_b64, ciphertext_b64 = DeviceE2ECrypto.encrypt(key, jpeg, aad)
                            nonce = base64.b64decode(nonce_b64 + "=" * ((4 - len(nonce_b64) % 4) % 4))
                            ciphertext = base64.b64decode(ciphertext_b64 + "=" * ((4 - len(ciphertext_b64) % 4) % 4))
                            packet = bytes((0x81, len(target_bytes))) + target_bytes + struct.pack(">Q", sequence) + nonce + ciphertext
                            with self.send_lock:
                                if ws.sock and ws.sock.connected:
                                    ws.send(packet, opcode=websocket.ABNF.OPCODE_BINARY)
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
        self.auto_reconnect_enabled = False
        self.reconnect_scheduled = False
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
        self._clear_peer_keys()
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
