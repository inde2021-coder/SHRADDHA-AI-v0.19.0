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
import os
import sys
import time
import subprocess
import webbrowser
import html
from pathlib import Path
import urllib.request
from urllib.error import HTTPError, URLError
from urllib.parse import quote

SENSITIVE_WINDOW = re.compile(r"password|passcode|sign.?in|log.?in|bank|otp|pin|payment|checkout|credential|secure", re.I)
SUPPORTED_ACTIONS = {"tap", "swipe", "scroll", "key_press", "back", "home", "type", "click_text", "ocr", "file_list", "file_read", "file_search", "file_write", "offline_chat", "code_assistant", "open_app", "media_control", "system_control", "screenshot", "clipboard_transform", "web_search"}


def create_e2e_identity():
    """Create a fresh P-256 identity for this live paired-device connection."""
    import base64
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives import serialization
    private = ec.generate_private_key(ec.SECP256R1())
    public = private.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    return private, base64.b64encode(public).decode("ascii")


def derive_e2e_peer_key(private, peer_public_b64: str, session_id: str, local_id: str, peer_id: str):
    import base64, hashlib
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.kdf.hkdf import HKDF
    if not isinstance(session_id, str) or not 1 <= len(session_id) <= 128:
        raise ValueError("Invalid E2E session binding")
    if not isinstance(local_id, str) or not isinstance(peer_id, str) or not 1 <= len(local_id) <= 64 or not 1 <= len(peer_id) <= 64 or local_id == peer_id:
        raise ValueError("Invalid E2E peer identity")
    encoded = base64.b64decode(peer_public_b64, validate=True)
    if not 80 <= len(encoded) <= 256:
        raise ValueError("Invalid E2E public key")
    public = serialization.load_der_public_key(encoded)
    if not isinstance(public, ec.EllipticCurvePublicKey) or not isinstance(public.curve, ec.SECP256R1):
        raise ValueError("Only P-256 peer keys are accepted")
    shared = private.exchange(ec.ECDH(), public)
    context = f"shraddha-device-e2e-v2|{session_id}|{'|'.join(sorted((local_id, peer_id)))}".encode("utf-8")
    salt = hashlib.sha256(context).digest()
    key = HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=context).derive(shared)
    from cryptography.hazmat.primitives import serialization as ser
    own_public = private.public_key().public_bytes(ser.Encoding.DER, ser.PublicFormat.SubjectPublicKeyInfo)
    digest = hashlib.sha256(context + b"".join(sorted((own_public, encoded)))).digest()[:16]
    fingerprint = "-".join(digest.hex().upper()[i:i+4] for i in range(0, 32, 4))
    return key, fingerprint


def clear_peer_key(peer_keys: dict, peer_id: str) -> bool:
    """Drop ephemeral E2E material when a paired peer disconnects or is revoked."""
    if not isinstance(peer_keys, dict) or not isinstance(peer_id, str) or not peer_id:
        return False
    state = peer_keys.pop(peer_id, None)
    if not isinstance(state, dict):
        return False
    key = state.pop("key", None)
    if isinstance(key, bytearray):
        key[:] = b"\x00" * len(key)
    state.clear()
    return True


def e2e_associated_data(session_id: str, sender_id: str, receiver_id: str, kind: str, correlation: str) -> bytes:
    return f"{session_id}|{sender_id}|{receiver_id}|{kind}|{correlation}".encode("utf-8")


async def send_e2e_message(ws, peer_keys: dict, session_id: str, sender_id: str, target_id: str,
                           kind: str, correlation: str, payload: dict, action_kind: str = "", index: int = -1,
                           category: str = ""):
    import base64, json, secrets
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    state = peer_keys.get(target_id)
    if not state or not state.get("local_confirmed") or not state.get("remote_confirmed"):
        raise RuntimeError("E2E peer fingerprint is not mutually verified")
    if kind == "file_transfer_chunk":
        aad_correlation = f"{correlation}:{index}"
    elif kind == "data_share_payload":
        aad_correlation = f"{correlation}|{category}"
    else:
        aad_correlation = correlation
    nonce = secrets.token_bytes(12)
    ciphertext = AESGCM(state["key"]).encrypt(
        nonce, json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8"),
        e2e_associated_data(session_id, sender_id, target_id, kind, aad_correlation)
    )
    envelope = {"type": "e2e_message", "targetDeviceId": target_id, "messageKind": kind,
                "nonce": base64.b64encode(nonce).decode("ascii"),
                "ciphertext": base64.b64encode(ciphertext).decode("ascii")}
    if kind == "file_transfer_chunk":
        envelope.update({"transferId": correlation, "index": index})
    elif kind == "clipboard_payload":
        envelope.update({"requestId": correlation})
    elif kind == "data_share_payload":
        envelope.update({"requestId": correlation, "category": category})
    else:
        envelope.update({"actionId": correlation, "actionKind": action_kind[:32]})
    await ws.send(json.dumps(envelope))


def decrypt_e2e_message(message: dict, peer_keys: dict, session_id: str, local_id: str) -> dict:
    import base64, json
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    peer_id = str(message.get("fromDeviceId", ""))
    kind = str(message.get("messageKind", ""))
    state = peer_keys.get(peer_id)
    if not state or not state.get("local_confirmed") or not state.get("remote_confirmed"):
        raise ValueError("E2E peer is not mutually verified")
    action_id = str(message.get("actionId", ""))
    transfer_id = str(message.get("transferId", ""))
    index = int(message.get("index", -1))
    if kind == "file_transfer_chunk":
        correlation = f"{transfer_id}:{index}"
    elif kind in {"clipboard_payload", "data_share_payload"}:
        correlation = str(message.get("requestId", ""))
        if kind == "data_share_payload":
            correlation += "|" + str(message.get("category", ""))
    else:
        correlation = action_id
    aad = e2e_associated_data(session_id, peer_id, local_id, kind, correlation)
    nonce_text = str(message.get("nonce", ""))
    ciphertext_text = str(message.get("ciphertext", ""))
    nonce = base64.b64decode(nonce_text + ("=" * (-len(nonce_text) % 4)), validate=True)
    ciphertext = base64.b64decode(ciphertext_text + ("=" * (-len(ciphertext_text) % 4)), validate=True)
    plain = AESGCM(state["key"]).decrypt(nonce, ciphertext, aad)
    payload = json.loads(plain.decode("utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("Encrypted peer payload must be an object")
    normalized = {"fromDeviceId": peer_id, **payload}
    if kind == "remote_action":
        normalized.update({"type": "remote_action", "actionId": action_id, "action": payload,
                           "requiresPerActionApproval": True})
    elif kind == "remote_action_result":
        normalized.update({"type": "remote_action_result", "actionId": action_id,
                           "actionKind": str(message.get("actionKind", ""))})
    elif kind == "file_transfer_chunk":
        normalized.update({"type": "file_transfer_chunk", "transferId": transfer_id, "index": index})
    elif kind == "clipboard_payload":
        normalized.update({"type": "data_share_payload", "category": "clipboard", "requestId": correlation})
    elif kind == "data_share_payload":
        normalized.update({"type": "data_share_payload", "category": str(message.get("category", "")),
                           "requestId": str(message.get("requestId", ""))})
    else:
        raise ValueError("Unsupported encrypted message kind")
    return normalized



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
    if kind == "key_press":
        chord = action.get("chord")
        allowed_chords = {
            "enter", "escape", "tab", "up", "down", "left", "right", "home", "end",
            "pageup", "pagedown", "ctrl+a", "ctrl+c", "ctrl+f", "ctrl+z", "ctrl+y",
            "alt+tab", "alt+left", "alt+right", "win+d", "ctrl+v"
        }
        if not isinstance(chord, str) or chord not in allowed_chords:
            return False
        if chord == "ctrl+v" and not allow_typing:
            return False
    if kind == "ocr" and not allow_ocr:
        return False
    if kind == "offline_chat" and offline_model is None:
        return False
    if kind in {"offline_chat", "code_assistant"}:
        prompt = action.get("prompt")
        if offline_model is None or not isinstance(prompt, str) or not 0 < len(prompt.strip()) <= 4000:
            return False
        if kind == "code_assistant" and "path" in action:
            relative = action.get("path")
            return bool(file_root) and isinstance(relative, str) and 0 < len(relative) <= 500
        return True
    if kind == "open_app":
        name = action.get("name")
        return isinstance(name, str) and name.strip().casefold() in {"notepad", "calculator", "paint", "file explorer", "explorer", "task manager", "settings", "edge", "chrome", "powershell", "terminal"}
    if kind == "media_control":
        return action.get("command") in {"play_pause", "next", "previous", "volume_up", "volume_down", "mute"}
    if kind == "system_control":
        command = action.get("command")
        if command in {"lock", "volume_up", "volume_down", "mute", "brightness_up", "brightness_down"}:
            return True
        return command == "set_brightness" and isinstance(action.get("value"), int) and not isinstance(action.get("value"), bool) and 0 <= action["value"] <= 100
    if kind == "screenshot":
        return True
    if kind == "clipboard_transform":
        return action.get("operation") in {"trim", "uppercase", "lowercase", "collapse_whitespace", "remove_blank_lines"}
    if kind == "web_search":
        query = action.get("query")
        return isinstance(query, str) and 2 <= len(query.strip()) <= 160
    if kind in {"file_list", "file_read", "file_search", "file_write"} and not file_root:
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
    if kind == "file_write":
        relative = action.get("path")
        content = action.get("content")
        overwrite = action.get("overwrite", False)
        return (isinstance(relative, str) and 0 < len(relative) <= 500 and
                isinstance(content, str) and len(content) <= 256_000 and
                isinstance(overwrite, bool))
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
    return kind in {"back", "home", "ocr", "offline_chat", "key_press"}


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
    """Resolve only a relative path below the approved root; reject traversal and symlink components."""
    from pathlib import Path
    base = Path(root).expanduser().resolve()
    raw = Path(relative or ".")
    if raw.is_absolute() or any(part == ".." for part in raw.parts) or ".shraddha-backups" in raw.parts:
        raise ValueError("Only relative paths inside the approved file root are allowed")
    candidate = base
    for part in raw.parts:
        if part in {"", "."}:
            continue
        candidate = candidate / part
        if candidate.is_symlink():
            raise ValueError("Symlinks are not allowed in the approved file workspace")
    resolved = candidate.resolve()
    if resolved != base and base not in resolved.parents:
        raise ValueError("Path escapes the approved file root")
    return base, resolved


def list_safe_files(root: str, relative: str = "") -> list[dict]:
    _, folder = safe_relative_path(root, relative or ".")
    if not folder.exists() or not folder.is_dir():
        raise ValueError("Folder does not exist")
    results = []
    for item in sorted(folder.iterdir(), key=lambda p: p.name.casefold())[:100]:
        if item.is_symlink() or item.name == ".shraddha-backups":
            continue
        results.append({"name": item.name[:180], "type": "folder" if item.is_dir() else "file",
                        "size": item.stat().st_size if item.is_file() else 0})
    return results


def read_safe_text_file(root: str, relative: str) -> str:
    _, candidate = safe_relative_path(root, relative)
    if not candidate.is_file():
        raise ValueError("Only regular files can be read")
    if candidate.suffix.lower() not in {".txt", ".md", ".csv", ".json", ".log", ".py", ".js", ".kt", ".html", ".css", ".xml", ".yaml", ".yml"}:
        raise ValueError("File type is not allowed for text preview")
    if candidate.stat().st_size > 256_000:
        raise ValueError("File exceeds 256 KB preview limit")
    return candidate.read_text(encoding="utf-8", errors="replace")[:256_000]


def write_safe_text_file(root: str, relative: str, content: str, overwrite: bool = False) -> dict:
    """Atomically create/update one approved text file; updates always keep a local backup."""
    import hashlib
    import os
    import shutil
    import tempfile
    from pathlib import Path

    if not isinstance(content, str) or len(content) > 256_000:
        raise ValueError("Text/code content must be at most 256 KB")
    base, candidate = safe_relative_path(root, relative)
    allowed = {".txt", ".md", ".csv", ".json", ".log", ".py", ".js", ".kt", ".html", ".css", ".xml", ".yaml", ".yml"}
    if candidate == base or candidate.suffix.lower() not in allowed:
        raise ValueError("Only named text/code files with an approved extension can be written")
    if not candidate.parent.exists() or not candidate.parent.is_dir():
        raise ValueError("Parent folder must already exist inside the approved root")
    if candidate.exists() and not overwrite:
        raise FileExistsError("File already exists; explicit overwrite approval is required")
    if candidate.exists() and not candidate.is_file():
        raise ValueError("Only regular files can be replaced")
    if candidate.exists() and candidate.stat().st_size > 256_000:
        raise ValueError("Existing file exceeds the 256 KB safe-edit limit")

    backup_relative = ""
    if candidate.exists():
        backup_dir = base / ".shraddha-backups"
        if backup_dir.is_symlink():
            raise ValueError("Backup directory cannot be a symlink")
        backup_dir.mkdir(exist_ok=True)
        relative_key = candidate.relative_to(base).as_posix().encode("utf-8")
        stamp = time.strftime("%Y%m%d_%H%M%S")
        backup_name = hashlib.sha256(relative_key).hexdigest()[:16] + "_" + stamp + candidate.suffix + ".bak"
        backup_path = backup_dir / backup_name
        if backup_path.exists():
            backup_path = backup_dir / (hashlib.sha256(relative_key).hexdigest()[:16] + "_" + str(time.time_ns()) + candidate.suffix + ".bak")
        shutil.copy2(candidate, backup_path)
        backup_relative = backup_path.relative_to(base).as_posix()

    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="", delete=False,
                                         dir=str(candidate.parent), prefix=".shraddha-", suffix=".tmp") as handle:
            temp_path = Path(handle.name)
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        if candidate.exists() and not overwrite:
            raise FileExistsError("File appeared during write; refusing to overwrite")
        os.replace(temp_path, candidate)
        temp_path = None
    finally:
        if temp_path is not None:
            try:
                temp_path.unlink(missing_ok=True)
            except OSError:
                pass
    return {"path": candidate.relative_to(base).as_posix(), "bytes": candidate.stat().st_size,
            "backup": backup_relative, "status": "file_written"}


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
    """Reject the retired plaintext visual-frame protocol."""
    raise ValueError("Plaintext visual frames are disabled; use decode_encrypted_screen_frame")


def decode_encrypted_screen_frame(packet: bytes, peer_keys: dict, session_id: str,
                                  local_device_id: str, seen_sequences: dict) -> tuple[str, int, bytes]:
    """Authenticate and decrypt the relay's source-prefixed visual-frame v2 envelope."""
    import struct
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    if not isinstance(packet, (bytes, bytearray)) or len(packet) < 1 + 1 + 8 + 12 + 16:
        raise ValueError("Invalid or truncated encrypted visual frame")
    source_length = packet[0]
    if source_length < 1 or source_length > 64 or len(packet) < 1 + source_length + 1 + 8 + 12 + 16:
        raise ValueError("Invalid visual-frame source ID length")
    try:
        source_id = bytes(packet[1:1 + source_length]).decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ValueError("Visual-frame source ID is invalid") from exc
    type_offset = 1 + source_length
    encoded_type = packet[type_offset]
    if encoded_type not in (0x81, 0x82):
        raise ValueError("Plaintext or unsupported visual frame type")
    frame_type = encoded_type & 0x7f
    sequence_offset = type_offset + 1
    sequence = struct.unpack_from(">Q", packet, sequence_offset)[0]
    if sequence < 1:
        raise ValueError("Invalid visual-frame sequence")
    nonce_offset = sequence_offset + 8
    nonce = bytes(packet[nonce_offset:nonce_offset + 12])
    ciphertext = bytes(packet[nonce_offset + 12:])
    if len(ciphertext) < 16 or len(ciphertext) > 2_500_016:
        raise ValueError("Encrypted visual frame exceeds size limits")
    state = peer_keys.get(source_id)
    if not state or not state.get("local_confirmed") or not state.get("remote_confirmed"):
        raise ValueError("Visual-frame peer fingerprint is not mutually verified")
    sequence_key = (source_id, frame_type)
    if sequence <= seen_sequences.get(sequence_key, 0):
        raise ValueError("Replayed visual frame rejected")
    aad = f"{session_id}|{source_id}|{local_device_id}|visual_frame|{frame_type}|{sequence}".encode("utf-8")
    jpeg = AESGCM(bytes(state["key"])).decrypt(nonce, ciphertext, aad)
    if len(jpeg) < 4 or len(jpeg) > 2_500_000 or jpeg[:2] != b"\xff\xd8" or jpeg[-2:] != b"\xff\xd9":
        raise ValueError("Decrypted visual frame is not a bounded JPEG")
    seen_sequences[sequence_key] = sequence
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
    """Reject the retired plaintext sender protocol."""
    raise ValueError("Plaintext visual frames are disabled; use encode_encrypted_screen_frame")


def encode_encrypted_screen_frame(jpeg: bytes, target_device_id: str, peer_keys: dict,
                                  session_id: str, sender_device_id: str, sequence: int,
                                  frame_type: int = 1) -> bytes:
    """Create the relay's encrypted sender packet; the server only forwards opaque ciphertext."""
    import secrets
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    target = target_device_id.encode("utf-8")
    if (not jpeg or len(jpeg) > 2_500_000 or jpeg[:2] != b"\xff\xd8" or jpeg[-2:] != b"\xff\xd9" or
            not 1 <= len(target) <= 64 or not 1 <= len(session_id) <= 128 or
            not 1 <= len(sender_device_id) <= 64 or sender_device_id == target_device_id or
            not isinstance(sequence, int) or isinstance(sequence, bool) or sequence < 1 or
            frame_type not in (1, 2)):
        raise ValueError("Invalid encrypted visual-frame fields")
    state = peer_keys.get(target_device_id)
    if not state or not state.get("local_confirmed") or not state.get("remote_confirmed"):
        raise RuntimeError("Visual sharing requires a mutually verified E2E peer")
    nonce = secrets.token_bytes(12)
    aad = f"{session_id}|{sender_device_id}|{target_device_id}|visual_frame|{frame_type}|{sequence}".encode("utf-8")
    ciphertext = AESGCM(bytes(state["key"])).encrypt(nonce, bytes(jpeg), aad)
    return bytes((0x80 | frame_type, len(target))) + target + sequence.to_bytes(8, "big") + nonce + ciphertext


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


async def share_screen_loop(ws, fps: float, target_device_id: str, peer_keys: dict,
                           session_id: str, sender_device_id: str) -> None:
    interval = 1.0 / max(0.5, min(2.0, fps))
    sequence = 0
    while True:
        jpeg = await asyncio.to_thread(capture_screen_jpeg)
        state = peer_keys.get(target_device_id)
        if jpeg and state and state.get("local_confirmed") and state.get("remote_confirmed"):
            sequence += 1
            packet = encode_encrypted_screen_frame(
                jpeg, target_device_id, peer_keys, session_id, sender_device_id, sequence, frame_type=1
            )
            await ws.send(packet)
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

def validate_missing_transfer_indexes(indexes, total: int) -> list[int]:
    if not isinstance(total, int) or isinstance(total, bool) or not 1 <= total <= 2200:
        raise ValueError('Invalid transfer chunk count')
    if not isinstance(indexes, list) or len(indexes) > total or len(indexes) > 2200:
        raise ValueError('Invalid resume index list')
    clean = []
    seen = set()
    for index in indexes:
        if not isinstance(index, int) or isinstance(index, bool) or not 0 <= index < total or index in seen:
            raise ValueError('Invalid or duplicate missing chunk index')
        seen.add(index)
        clean.append(index)
    return clean


def validate_transfer_chunk(index: int, total: int, encoded: str) -> bytes:
    import base64
    if not isinstance(index, int) or not isinstance(total, int) or total < 1 or total > 2200 or not 0 <= index < total: raise ValueError('Invalid chunk index')
    if not isinstance(encoded, str) or len(encoded) > 70000: raise ValueError('Chunk exceeds relay limit')
    try: data = base64.b64decode(encoded, validate=True)
    except Exception as exc: raise ValueError('Invalid base64 chunk') from exc
    if not data or len(data) > FILE_CHUNK_BYTES: raise ValueError('Invalid chunk size')
    return data
def windows_pick_file() -> str:
    import tkinter as tk
    from tkinter import filedialog
    root = tk.Tk(); root.withdraw(); root.attributes('-topmost', True)
    try: return filedialog.askopenfilename(title='Choose a file to send with Shraddha AI')
    finally: root.destroy()

def windows_save_file(name: str) -> str:
    import tkinter as tk
    from tkinter import filedialog
    root = tk.Tk(); root.withdraw(); root.attributes('-topmost', True)
    try: return filedialog.asksaveasfilename(title='Save incoming Shraddha AI file', initialfile=name, defaultextension='')
    finally: root.destroy()

def verify_transfer_file(path: str, expected_size: int, expected_sha256: str) -> bool:
    import hashlib
    from pathlib import Path
    source = Path(path)
    if not source.is_file() or source.stat().st_size != expected_size: return False
    digest = hashlib.sha256()
    with source.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''): digest.update(block)
    return digest.hexdigest() == expected_sha256.lower()

async def send_file_chunks(ws, target: str, transfer_id: str, path: str, indexes: list[int],
                           peer_keys: dict, session_id: str, sender_id: str) -> None:
    import base64
    paused = False
    try:
        import msvcrt
    except ImportError:
        msvcrt = None
    for position, index in enumerate(indexes):
        if msvcrt:
            while msvcrt.kbhit():
                if msvcrt.getwch().lower() == 'p': paused = not paused; print('File transfer', 'PAUSED' if paused else 'RESUMING')
            while paused:
                await asyncio.sleep(0.15)
                while msvcrt.kbhit():
                    if msvcrt.getwch().lower() == 'p': paused = not paused; print('File transfer', 'PAUSED' if paused else 'RESUMING')
        data = await asyncio.to_thread(read_file_chunk, path, index)
        await send_e2e_message(ws, peer_keys, session_id, sender_id, target, "file_transfer_chunk",
                               transfer_id, {"payload": base64.b64encode(data).decode('ascii')}, index=index)
        if position % 20 == 0: print(f"Sending file chunks: {position + 1}/{len(indexes)}")
        await asyncio.sleep(0.01)
    if msvcrt: print("Press P during transfer to pause/resume sending.")


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


def claim_remote_action_id(seen: dict, sender_id: str, action_id: str, limit: int = 4096) -> bool:
    """Reject replayed action IDs within a live E2E session, with bounded memory."""
    if not sender_id or len(sender_id) > 80 or not re.fullmatch(r"[A-Za-z0-9_-]{12,64}", action_id or ""):
        return False
    key = (sender_id, action_id)
    if key in seen:
        return False
    seen[key] = time.monotonic()
    while len(seen) > max(1, min(int(limit), 8192)):
        seen.pop(next(iter(seen)))
    return True


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
    if kind == "key_press":
        keys = action["chord"].split("+")
        if len(keys) == 1:
            pyautogui.press(keys[0])
        else:
            pyautogui.hotkey(*keys)
        return "key_press_sent:" + action["chord"]
    if kind == "back":
        pyautogui.hotkey("alt", "left")
        return "back_executed"
    if kind == "home":
        pyautogui.hotkey("win", "d")
        return "desktop_shown"
    if kind == "open_app":
        aliases = {
            "notepad": ["notepad.exe"], "calculator": ["calc.exe"], "paint": ["mspaint.exe"],
            "file explorer": ["explorer.exe"], "explorer": ["explorer.exe"],
            "task manager": ["taskmgr.exe"], "edge": ["msedge.exe"],
            "chrome": ["chrome.exe"], "powershell": ["powershell.exe"],
            "terminal": ["wt.exe"]
        }
        name = action["name"].strip().casefold()
        if name == "settings":
            try:
                os.startfile("ms-settings:")
                return "settings_open_requested"
            except Exception as exc:
                return "app_launch_failed:" + type(exc).__name__
        executable = aliases.get(name)
        if not executable:
            return "blocked_app_not_allowlisted"
        try:
            subprocess.Popen(executable, close_fds=True)
            return "app_launch_requested:" + name
        except Exception as exc:
            return "app_launch_failed:" + type(exc).__name__
    if kind == "media_control":
        keys = {"play_pause": "playpause", "next": "nexttrack", "previous": "prevtrack",
                "volume_up": "volumeup", "volume_down": "volumedown", "mute": "volumemute"}
        pyautogui.press(keys[action["command"]])
        return "media_control_sent:" + action["command"]
    if kind == "system_control":
        command = action["command"]
        if command == "lock":
            try:
                import ctypes
                ctypes.windll.user32.LockWorkStation()
                return "workstation_lock_requested"
            except Exception as exc:
                return "lock_failed:" + type(exc).__name__
        if command in {"volume_up", "volume_down", "mute"}:
            key = {"volume_up": "volumeup", "volume_down": "volumedown", "mute": "volumemute"}[command]
            pyautogui.press(key)
            return "system_volume_control_sent:" + command
        try:
            import screen_brightness_control as sbc
            current = sbc.get_brightness()
            current = int(current[0] if isinstance(current, (list, tuple)) else current)
            value = action["value"] if command == "set_brightness" else max(0, min(100, current + (10 if command == "brightness_up" else -10)))
            sbc.set_brightness(value)
            return "brightness_set:" + str(value)
        except Exception as exc:
            return "brightness_unavailable:" + type(exc).__name__
    if kind == "screenshot":
        try:
            import pygetwindow
            active = pygetwindow.getActiveWindow()
            title = active.title if active else ""
        except Exception:
            active, title = None, ""
        if not active or is_sensitive_window(title):
            return "blocked_unknown_or_sensitive_window"
        jpeg = await asyncio.to_thread(capture_screen_jpeg, 1600, 75)
        if not jpeg:
            return "screenshot_capture_unavailable"
        try:
            from PIL import Image
            folder = Path.home() / "Pictures" / "Shraddha AI Screenshots"
            folder.mkdir(parents=True, exist_ok=True)
            stamp = time.strftime("%Y%m%d_%H%M%S")
            path = folder / f"shraddha_{stamp}.jpg"
            Image.open(io.BytesIO(jpeg)).save(path, format="JPEG", quality=90)
            return json.dumps({"status": "screenshot_saved", "path": str(path), "window": title[:120]}, ensure_ascii=False)
        except Exception as exc:
            return "screenshot_save_failed:" + type(exc).__name__
    if kind == "clipboard_transform":
        try:
            import pyperclip
            source = pyperclip.paste()
            if not isinstance(source, str) or not source.strip():
                return "clipboard_empty_or_not_text"
            safe = sanitize_clipboard_text(source)
            operation = action["operation"]
            transformed = {
                "trim": lambda value: value.strip(),
                "uppercase": lambda value: value.upper(),
                "lowercase": lambda value: value.lower(),
                "collapse_whitespace": lambda value: re.sub(r"\s+", " ", value).strip(),
                "remove_blank_lines": lambda value: "\n".join(line for line in value.splitlines() if line.strip())
            }[operation](safe)
            if not await asyncio.to_thread(confirm, f"Return transformed clipboard text to the paired phone? {transformed[:120]!r}"):
                return "clipboard_transform_rejected_by_pc_user"
            return json.dumps({"status": "clipboard_transformed", "operation": operation, "text": transformed[:20000]}, ensure_ascii=False)
        except Exception as exc:
            return "clipboard_transform_unavailable:" + type(exc).__name__
    if kind == "web_search":
        query = action["query"].strip()
        try:
            request = urllib.request.Request(
                "https://html.duckduckgo.com/html/?q=" + quote(query),
                headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) ShraddhaAI/1.0"}
            )
            with urllib.request.urlopen(request, timeout=12) as response:
                page = response.read(1_500_000).decode("utf-8", "replace")
            anchors = re.findall(r'<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>', page, re.I | re.S)
            results = []
            for url, title in anchors[:5]:
                clean_title = re.sub(r"<[^>]+>", "", title)
                results.append({"title": html.unescape(clean_title)[:180], "url": html.unescape(url)[:1000]})
            return json.dumps({"status": "web_search_complete" if results else "no_results", "query": query, "results": results}, ensure_ascii=False)
        except Exception as exc:
            return "web_search_unavailable:" + type(exc).__name__
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
    if kind in {"offline_chat", "code_assistant"}:
        prompt = action["prompt"].strip()
        if kind == "code_assistant" and action.get("path"):
            if not await asyncio.to_thread(confirm, f"Read approved project file {action['path']!r} into the local offline code assistant?"):
                return "code_context_read_rejected_by_pc_user"
            try:
                file_context = await asyncio.to_thread(read_safe_text_file, file_root, action["path"])
            except Exception as exc:
                return "code_context_read_failed: " + str(exc)[:180]
            prompt += "\n\nCURRENT APPROVED PROJECT FILE (read-only context; do not assume it has been changed):\n"
            prompt += file_context[:12000]
            if len(file_context) > 12000:
                prompt += "\n[File context truncated at 12,000 characters.]"
        if not await asyncio.to_thread(confirm, "Run this prompt on the local offline PC model? " + prompt[:160]):
            return "offline_ai_request_rejected_by_pc_user"
        system_prompt = (
            "You are Shraddha AI Code Assistant running locally on this PC without network access. "
            "Explain, write and debug code carefully. If a current file is supplied, preserve its intent and return a complete revised file or a minimal patch as requested. "
            "State assumptions and risks. Never claim to have changed files or run tests unless tools actually did so. "
            "Your response is a proposal only; file changes require a separate approved file_write action."
            if kind == "code_assistant" else
            "You are Shraddha AI running locally on this PC without network access. Be accurate, concise, and state uncertainty. Never claim to have performed external actions."
        )
        try:
            result = await asyncio.to_thread(
                offline_model.create_chat_completion,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": prompt}
                ],
                temperature=0.3,
                max_tokens=1800 if kind == "code_assistant" else 600
            )
            answer = result.get("choices", [{}])[0].get("message", {}).get("content", "")
            if not isinstance(answer, str) or not answer.strip():
                return "offline_ai_returned_no_answer"
            status = "code_assistant_complete" if kind == "code_assistant" else "offline_inference_complete"
            return json.dumps({"status": status, "answer": answer[:10000]}, ensure_ascii=False)
        except Exception as exc:
            return "offline_inference_failed: " + str(exc)[:180]
    if kind in {"ocr", "file_list", "file_read", "file_search", "file_write"}:
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
            if kind == "file_write":
                preview = action["content"][:400].replace("\n", " ")
                if not await asyncio.to_thread(confirm, f"Create/update {action['path']!r} under approved root? overwrite={action.get('overwrite', False)}. Backup is created before overwrite. Preview: {preview!r}"):
                    return "file_write_rejected_by_pc_user"
                result = write_safe_text_file(file_root, action["path"], action["content"], action.get("overwrite", False))
                return json.dumps(result, ensure_ascii=False)
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
    e2e_private, e2e_public_b64 = create_e2e_identity()
    peer_keys = {}

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
    file_send = None
    if args.send_file_to:
        import secrets
        selected_path = args.send_file or await asyncio.to_thread(windows_pick_file)
        if not selected_path: print('File picker cancelled; no file sent.')
        else:
            manifest = await asyncio.to_thread(file_transfer_manifest, selected_path)
            request_id = secrets.token_urlsafe(18)
            transfer_id = secrets.token_urlsafe(18)
            file_send = {'path': selected_path, 'manifest': manifest, 'requestId': request_id, 'transferId': transfer_id, 'target': args.send_file_to}
    if args.send_clipboard_to:
        import pyperclip, secrets
        request_id = secrets.token_urlsafe(18)
        clipboard_send = (request_id, build_clipboard_payload(await asyncio.to_thread(pyperclip.paste)))
    if phone_action and not await asyncio.to_thread(confirm, f"Ask the selected Android phone for permission to run {phone_action['kind']}?"):
        phone_action = None
    phone_action_sent = False
    allow_control = await asyncio.to_thread(confirm, "Allow this paired phone to request temporary control of this PC?")
    allow_file_receive = bool(args.allow_file_receive and await asyncio.to_thread(confirm, "Allow this paired phone to request one-time file receive approval? Each transfer still needs your confirmation."))
    share_screen = bool(args.share_screen and await asyncio.to_thread(confirm, "Share this PC screen with paired Shraddha devices? Sensitive active windows will pause sharing."))
    incoming_transfer = None
    from pathlib import Path
    transfer_state_dir = Path.home() / ".shraddha-ai" / "transfers"
    transfer_state_dir.mkdir(parents=True, exist_ok=True)
    transfer_state_file = transfer_state_dir / "incoming-transfer.json"
    if transfer_state_file.is_file():
        try:
            saved = json.loads(transfer_state_file.read_text(encoding="utf-8"))
            if Path(saved["temp"]).is_file():
                saved["stream"] = open(saved["temp"], "r+b")
                saved["received"] = set(saved.get("received", []))
                incoming_transfer = saved
                print("Restored interrupted incoming transfer; missing chunks will be requested after reconnect.")
        except Exception as exc: print("Could not restore previous transfer:", str(exc)[:160])
    async with websockets.connect(url, extra_headers=headers, ping_interval=20, max_size=3 * 2**20) as ws:
        await ws.send(json.dumps({"type": "permission_update", "permissions": {"remote_control": allow_control, "screen": share_screen, "documents": allow_file_receive, "media": allow_file_receive}}))
        print("Relay connected. Remote control:", "ON" if allow_control else "OFF", "| Screen share:", "ON" if share_screen else "OFF")
        if phone_action:
            await ws.send(json.dumps({"type": "control_request", "targetDeviceId": args.control_phone, "minutes": 2}))
            print("Waiting for the Android phone owner to approve remote control…")
        if clipboard_send:
            await ws.send(json.dumps({"type": "data_share_request", "targetDeviceId": args.send_clipboard_to, "category": "clipboard", "requestId": clipboard_send[0], "itemLabel": "Clipboard text"}))
            print("Waiting for the Android device owner to approve clipboard sync…")
        if file_send:
            await ws.send(json.dumps({"type": "data_share_request", "targetDeviceId": file_send["target"], "category": "documents", "requestId": file_send["requestId"], "itemLabel": file_send["manifest"]["name"]}))
            print("Waiting for Android receiver to approve this file transfer…")
        approved_until = 0.0
        approved_device_id = ""
        seen_remote_actions = {}
        seen_visual_sequences = {}
        screen_task = asyncio.create_task(share_screen_loop(ws, args.screen_fps, args.screen_target, peer_keys, session_id, device_id)) if share_screen else None
        try:
            async for raw in ws:
                if isinstance(raw, bytes):
                    if args.view_phone_screen:
                        try:
                            source_id, frame_type, jpeg = decode_encrypted_screen_frame(raw, peer_keys, session_id, device_id, seen_visual_sequences)
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
                encrypted_transport = kind == "e2e_message"
                if kind == "e2e_key_offer":
                    peer_id = str(message.get("fromDeviceId", ""))
                    try:
                        key, fingerprint = derive_e2e_peer_key(e2e_private, str(message.get("publicKey", "")), session_id, device_id, peer_id)
                        trusted = await asyncio.to_thread(confirm,
                            f"Verify Android peer {peer_id}. Compare this fingerprint with the Android screen over a separate trusted channel:\n{fingerprint}\n\nDo both devices show exactly the same fingerprint?")
                        peer_keys[peer_id] = {"key": key, "fingerprint": fingerprint,
                                              "local_confirmed": bool(trusted), "remote_confirmed": False}
                        if trusted:
                            await ws.send(json.dumps({"type": "e2e_key_confirm", "targetDeviceId": peer_id, "fingerprint": fingerprint}))
                        else:
                            print("E2E peer was not trusted; encrypted actions and transfers remain blocked.")
                    except Exception as exc:
                        print("Rejected invalid E2E key offer:", str(exc)[:160])
                elif kind == "e2e_key_confirm":
                    peer_id = str(message.get("fromDeviceId", ""))
                    state = peer_keys.get(peer_id)
                    if state and state["fingerprint"] == str(message.get("fingerprint", "")):
                        state["remote_confirmed"] = True
                        print("End-to-end encryption verified with", peer_id)
                    else:
                        if state: state["remote_confirmed"] = False
                        print("E2E fingerprint mismatch; peer is NOT trusted.")
                elif kind == "e2e_message":
                    try:
                        message = decrypt_e2e_message(message, peer_keys, session_id, device_id)
                        kind = message.get("type")
                    except Exception as exc:
                        print("Blocked invalid/untrusted encrypted peer message:", str(exc)[:160])
                        continue
                if kind == "connected":
                    print("Connected to Shraddha AI device session.")
                    for peer in message.get("devices") or []:
                        peer_id = str(peer.get("id", ""))
                        if peer_id and peer_id != device_id:
                            await ws.send(json.dumps({"type": "e2e_key_offer", "targetDeviceId": peer_id, "publicKey": e2e_public_b64}))
                    if incoming_transfer:
                        missing = [index for index in range(incoming_transfer["chunks"]) if index not in incoming_transfer["received"]]
                        await ws.send(json.dumps({"type": "file_transfer_resume", "targetDeviceId": incoming_transfer["source"],
                                                  "transferId": incoming_transfer["id"], "chunkCount": incoming_transfer["chunks"],
                                                  "missingIndexes": missing}))
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
                    if not encrypted_transport:
                        print("Blocked plaintext remote action; E2E encryption is mandatory.")
                        continue
                    sender_id = str(message.get("fromDeviceId", ""))
                    action_id = str(message.get("actionId", ""))
                    if message.get("requiresPerActionApproval") is not True or not claim_remote_action_id(seen_remote_actions, sender_id, action_id):
                        print("Blocked remote action: invalid or replayed action ID, or missing per-action approval requirement.")
                        continue
                    if not control_grant_allows(allow_control, approved_until, time.time(), approved_device_id, sender_id):
                        print("Blocked remote action: no active grant for this exact paired requester.")
                        continue
                    action = message.get("action") or {}
                    action_kind = str(action.get("kind", "unknown"))[:32]
                    if not await asyncio.to_thread(confirm, f"Allow this remote action ({action_kind}) from paired device {sender_id}?"):
                        result = "denied_by_local_user"
                    else:
                        try:
                            result = await execute_action(action, args.allow_typing, args.allow_ocr, args.file_root, offline_model)
                        except Exception as exc:
                            result = "action_error:" + type(exc).__name__
                    print("Remote action:", action_kind, "=>", result[:500])
                    await send_e2e_message(ws, peer_keys, session_id, device_id, sender_id,
                                           "remote_action_result", action_id, {"result": result[:14000]},
                                           action_kind=action_kind)
                elif kind == "control_result":
                    if phone_action and message.get("targetDeviceId") == args.control_phone and message.get("approved") is True:
                        action_id = __import__("secrets").token_urlsafe(18)
                        await send_e2e_message(ws, peer_keys, session_id, device_id, args.control_phone,
                                               "remote_action", action_id, phone_action,
                                               action_kind=str(phone_action.get("kind", "unknown")))
                        phone_action_sent = True
                        print("Android owner approved. Sending one locally-confirmed action…")
                    elif phone_action:
                        print("Android remote-control request was denied or expired:", str(message.get("reason", "not approved"))[:240])
                        break
                elif kind == "remote_action_result":
                    if not encrypted_transport:
                        print("Blocked plaintext remote action result; E2E encryption is mandatory.")
                        continue
                    if phone_action_sent and message.get("fromDeviceId") == args.control_phone:
                        print("Android action result:", str(message.get("result", "no result"))[:1000])
                        break
                elif kind == "data_share_request":
                    requester_id = str(message.get("fromDeviceId", ""))
                    category = str(message.get("category", ""))
                    request_id = str(message.get("requestId", ""))
                    allowed = (category in {"documents", "media"} and allow_file_receive) or (category == "clipboard" and allow_control)
                    approved = bool(allowed and await asyncio.to_thread(confirm, f"Allow one-time {category} share request from paired device {requester_id}?"))
                    await ws.send(json.dumps({"type": "data_share_response", "requesterDeviceId": requester_id, "category": category, "requestId": request_id, "approved": approved, "minutes": 5 if approved else 0}))
                elif kind == "data_share_result" and clipboard_send and message.get("requestId") == clipboard_send[0]:
                    if message.get("approved") is True and message.get("category") == "clipboard":
                        await send_e2e_message(ws, peer_keys, session_id, device_id, args.send_clipboard_to,
                                               "clipboard_payload", clipboard_send[0], clipboard_send[1])
                        print("Clipboard text sent with end-to-end encryption after explicit device approval.")
                    else: print("Clipboard sync was denied.")
                elif kind == "data_share_result" and file_send and message.get("requestId") == file_send["requestId"]:
                    if message.get("approved") is True and message.get("category") == "documents":
                        manifest = file_send["manifest"]
                        await ws.send(json.dumps({"type": "file_transfer_start", "targetDeviceId": file_send["target"], "requestId": file_send["requestId"], "transferId": file_send["transferId"], "fileName": manifest["name"], "sizeBytes": manifest["size"], "chunkCount": manifest["chunks"], "sha256": manifest["sha256"]}))
                        print("Receiver approved. Manifest sent; waiting for the receiver to choose a save destination…")
                    else: print("File transfer permission denied.")
                elif kind == "file_transfer_start":
                    source_id = str(message.get("fromDeviceId", ""))
                    transfer_id = str(message.get("transferId", ""))
                    name = str(message.get("fileName", "incoming-file"))[:120]
                    size = int(message.get("sizeBytes", 0)); chunks = int(message.get("chunkCount", 0)); digest = str(message.get("sha256", ""))
                    if not allow_file_receive or size < 1 or size > MAX_TRANSFER_BYTES or chunks < 1 or chunks > 2200 or not re.fullmatch(r"[a-f0-9]{64}", digest):
                        await ws.send(json.dumps({"type": "file_transfer_cancel", "targetDeviceId": source_id, "transferId": transfer_id, "reason": "invalid_manifest_or_permission"}))
                        continue
                    approved = await asyncio.to_thread(confirm, f"Receive {name} ({size} bytes) from {source_id}?")
                    destination = await asyncio.to_thread(windows_save_file, name) if approved else ""
                    if not destination:
                        await ws.send(json.dumps({"type": "file_transfer_cancel", "targetDeviceId": source_id, "transferId": transfer_id, "reason": "receiver_cancelled"}))
                        continue
                    temporary = destination + ".shraddha-part"
                    stream = open(temporary, "w+b"); stream.truncate(size)
                    incoming_transfer = {"id": transfer_id, "source": source_id, "name": name, "size": size, "chunks": chunks, "sha256": digest, "path": destination, "temp": temporary, "stream": stream, "received": set()}
                    transfer_state_file.write_text(json.dumps({"id": transfer_id, "source": source_id, "name": name, "size": size, "chunks": chunks, "sha256": digest, "path": destination, "temp": temporary, "received": []}), encoding="utf-8")
                    await ws.send(json.dumps({"type": "file_transfer_ack", "targetDeviceId": source_id, "transferId": transfer_id, "index": -1, "complete": False, "ok": True}))
                    print(f"Receiving {name}: 0/{chunks} chunks (0%).")
                elif kind == "file_transfer_chunk" and incoming_transfer and message.get("transferId") == incoming_transfer["id"]:
                    if not encrypted_transport:
                        print("Blocked plaintext file chunk; E2E encryption is mandatory.")
                        continue
                    try:
                        import base64
                        index = int(message.get("index", -1)); data = validate_transfer_chunk(index, incoming_transfer["chunks"], str(message.get("payload", "")))
                        expected = incoming_transfer["size"] - index * FILE_CHUNK_BYTES if index == incoming_transfer["chunks"] - 1 else FILE_CHUNK_BYTES
                        if len(data) != expected: raise ValueError("Chunk size mismatch")
                        if index not in incoming_transfer["received"]:
                            incoming_transfer["stream"].seek(index * FILE_CHUNK_BYTES); incoming_transfer["stream"].write(data); incoming_transfer["received"].add(index)
                        await ws.send(json.dumps({"type": "file_transfer_ack", "targetDeviceId": incoming_transfer["source"], "transferId": incoming_transfer["id"], "index": index, "complete": False, "ok": True}))
                        count = len(incoming_transfer["received"]); total = incoming_transfer["chunks"]
                        transfer_state_file.write_text(json.dumps({"id": incoming_transfer["id"], "source": incoming_transfer["source"], "name": incoming_transfer["name"], "size": incoming_transfer["size"], "chunks": total, "sha256": incoming_transfer["sha256"], "path": incoming_transfer["path"], "temp": incoming_transfer["temp"], "received": sorted(incoming_transfer["received"])}), encoding="utf-8")
                        received_name = incoming_transfer["name"]
                        print(f"Receiving {received_name}: {count}/{total} chunks ({count*100//total}%).", end="\\r", flush=True)
                        if count == total:
                            incoming_transfer["stream"].flush(); incoming_transfer["stream"].close()
                            if not verify_transfer_file(incoming_transfer["temp"], incoming_transfer["size"], incoming_transfer["sha256"]):
                                import os
                                os.remove(incoming_transfer["temp"]); raise ValueError("Final SHA-256 verification failed")
                            import os
                            os.replace(incoming_transfer["temp"], incoming_transfer["path"])
                            await ws.send(json.dumps({"type": "file_transfer_ack", "targetDeviceId": incoming_transfer["source"], "transferId": incoming_transfer["id"], "index": -1, "complete": True, "ok": True}))
                            saved_path = incoming_transfer["path"]
                            print(f"\\nSaved and SHA-256 verified: {saved_path}")
                            incoming_transfer = None
                            transfer_state_file.unlink(missing_ok=True)
                    except Exception as exc: print("File receive error:", str(exc)[:200])
                elif kind == "file_transfer_resume" and file_send and message.get("transferId") == file_send["transferId"]:
                    if (message.get("fromDeviceId") != file_send["target"] or
                        message.get("chunkCount") != file_send["manifest"]["chunks"]):
                        print("Rejected resume request: paired peer or transfer manifest mismatch.")
                        continue
                    try:
                        safe_missing = validate_missing_transfer_indexes(message.get("missingIndexes"), file_send["manifest"]["chunks"])
                    except ValueError as exc:
                        print("Rejected resume request:", str(exc))
                        continue
                    print(f"Resume requested: retransmitting {len(safe_missing)} missing chunks.")
                    await send_file_chunks(ws, file_send["target"], file_send["transferId"], file_send["path"], safe_missing, peer_keys, session_id, device_id)
                elif kind == "file_transfer_ack" and file_send and message.get("transferId") == file_send["transferId"]:
                    if message.get("complete"): print("Receiver confirmed file save and SHA-256 verification.")
                    elif message.get("index") == -1 and message.get("ok") is True:
                        manifest = file_send["manifest"]
                        print("Receiver selected a save location. Starting transfer…")
                        await send_file_chunks(ws, file_send["target"], file_send["transferId"], file_send["path"], list(range(manifest["chunks"])), peer_keys, session_id, device_id)
                elif kind == "file_transfer_cancel":
                    if incoming_transfer and message.get("transferId") == incoming_transfer["id"]:
                        try: incoming_transfer["stream"].close()
                        except Exception: pass
                        print("Incoming transfer cancelled by peer.")
                        incoming_transfer = None
                        transfer_state_file.unlink(missing_ok=True)
                elif kind == "data_share_payload" and message.get("category") == "clipboard" and message.get("contentType") == "clipboard_text":
                    text_value = decode_clipboard_payload(message)
                    if await asyncio.to_thread(confirm, "Copy approved paired-device clipboard text onto this Windows PC?"):
                        import pyperclip
                        await asyncio.to_thread(pyperclip.copy, text_value)
                        print("Clipboard text copied locally.")
                elif kind == "data_share_payload":
                    try:
                        import base64, hashlib, tempfile
                        temp_path = None
                        category = str(message.get("category", ""))
                        if category not in {"media", "documents"}:
                            raise ValueError("Unsupported shared-file category")
                        if str(message.get("contentType", "")) != "file":
                            raise ValueError("Unsupported shared attachment content type")
                        mime_type = str(message.get("mimeType", ""))
                        if not mime_type or len(mime_type) > 100 or any(ord(ch) < 32 for ch in mime_type):
                            raise ValueError("Invalid shared attachment MIME type")
                        size = int(message.get("sizeBytes", -1))
                        if size < 1 or size > 650_000:
                            raise ValueError("Shared attachment exceeds the 650 KB quick-share limit")
                        encoded = str(message.get("payload", ""))
                        raw = base64.b64decode(encoded, validate=True)
                        if len(raw) != size:
                            raise ValueError("Shared attachment byte length mismatch")
                        expected = str(message.get("sha256", "")).lower()
                        if not re.fullmatch(r"[a-f0-9]{64}", expected) or hashlib.sha256(raw).hexdigest() != expected:
                            raise ValueError("Shared attachment SHA-256 verification failed")
                        supplied_name = str(message.get("fileName", "shared-file"))[:120]
                        safe_name = re.sub(r"[^A-Za-z0-9._ -]", "_", Path(supplied_name).name).strip(" .") or "shared-file"
                        if safe_name.split(".")[0].upper() in {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)), *(f"LPT{i}" for i in range(1, 10))}:
                            safe_name = "_" + safe_name
                        if not await asyncio.to_thread(confirm, f"Save verified {category} attachment '{safe_name}' to Downloads/Shraddha Transfers?"):
                            print("Shared attachment declined by PC user.")
                        else:
                            destination_dir = Path.home() / "Downloads" / "Shraddha Transfers"
                            destination_dir.mkdir(parents=True, exist_ok=True)
                            destination = destination_dir / safe_name
                            stem, suffix = destination.stem, destination.suffix
                            counter = 1
                            while destination.exists():
                                destination = destination_dir / f"{stem} ({counter}){suffix}"
                                counter += 1
                            with tempfile.NamedTemporaryFile(prefix=".shraddha-", suffix=".part", dir=destination_dir, delete=False) as temp_file:
                                temp_path = Path(temp_file.name)
                                temp_file.write(raw)
                                temp_file.flush()
                                os.fsync(temp_file.fileno())
                            os.replace(temp_path, destination)
                            temp_path = None
                            print(f"Saved SHA-256 verified attachment: {destination}")
                    except Exception as exc:
                        if temp_path is not None:
                            try: temp_path.unlink(missing_ok=True)
                            except Exception: pass
                        print("Shared attachment rejected:", str(exc)[:200])
                elif kind == "device_revoked":
                    peer_id = str(message.get("deviceId", ""))
                    clear_peer_key(peer_keys, peer_id)
                    print("Paired device revoked; its ephemeral E2E key was discarded:", peer_id)
                elif kind == "peer_status":
                    peer_id = str(message.get("deviceId", ""))
                    if message.get("online") is True and peer_id and peer_id != device_id and peer_id not in peer_keys:
                        await ws.send(json.dumps({"type": "e2e_key_offer", "targetDeviceId": peer_id, "publicKey": e2e_public_b64}))
                    elif message.get("online") is not True:
                        clear_peer_key(peer_keys, peer_id)
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
    parser.add_argument("--send-file-to", default="", help="Choose a Windows file and request one-time Documents permission before sending it")
    parser.add_argument("--send-file", default="", help="Optional exact source path used with --send-file-to; otherwise open a Windows file picker")
    parser.add_argument("--allow-file-receive", action="store_true", help="Offer Windows file receive permission; each incoming file still requires confirmation and a save picker")
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
