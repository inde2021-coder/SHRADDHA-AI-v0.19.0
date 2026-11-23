"""Small, dependency-free launch contract shared by MYRA and Shraddha PC companion."""
from __future__ import annotations

import os
import re
import shutil
import sys
from pathlib import Path
from urllib.parse import urlsplit


def validate_relay_url(value: str) -> str:
    url = str(value or "").strip().rstrip("/")
    if not 10 <= len(url) <= 500 or any(ch.isspace() for ch in url):
        raise ValueError("Enter the HTTPS URL of your Shraddha relay.")
    parsed = urlsplit(url)
    if parsed.scheme.lower() != "https" or not parsed.hostname:
        raise ValueError("Only a secure HTTPS relay URL is accepted.")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("Relay URL must not contain credentials, query or fragment.")
    try:
        if parsed.port is not None and not 1 <= parsed.port <= 65535:
            raise ValueError("Relay URL port is invalid.")
    except ValueError as exc:
        raise ValueError("Relay URL port is invalid.") from exc
    if parsed.path not in ("", "/"):
        raise ValueError("Enter the relay origin only, without a path.")
    return f"https://{parsed.netloc}"


def validate_pairing_code(value: str) -> str:
    code = str(value or "").strip()
    if code and not re.fullmatch(r"[0-9]{6}", code):
        raise ValueError("Pairing code must be exactly six digits (or blank to reuse saved pairing).")
    return code


def _companion_candidates(app_dir: Path):
    roots = [Path(app_dir).resolve()]
    frozen_root = getattr(sys, "_MEIPASS", None)
    if frozen_root:
        roots.insert(0, Path(frozen_root).resolve())
    for root in roots:
        yield root.parent / "shraddha-pc-companion" / "shraddha_pc_companion.py"
        yield root / "shraddha-pc-companion" / "shraddha_pc_companion.py"
        yield root.parent / "ShraddhaAI-Desktop-Companion.exe"
        yield root / "ShraddhaAI-Desktop-Companion.exe"


def build_launch_spec(app_dir: str | Path, relay_url: str, pairing_code: str = "",
                      device_name: str = "MYRA Desktop", python_executable: str | None = None,
                      environ: dict | None = None) -> dict:
    """Build a fixed executable invocation; pairing secret is passed only via child env."""
    relay = validate_relay_url(relay_url)
    code = validate_pairing_code(pairing_code)
    name = str(device_name or "").strip()
    if not 1 <= len(name) <= 60 or any(ord(ch) < 32 for ch in name):
        raise ValueError("Device name must contain 1–60 printable characters.")
    candidates = list(_companion_candidates(Path(app_dir)))
    target = next((p for p in candidates if p.is_file()), None)
    if target is None:
        raise FileNotFoundError(
            "Shraddha PC companion was not found. Install desktop-companion/shraddha-pc-companion "
            "or place ShraddhaAI-Desktop-Companion.exe beside MYRA."
        )
    args = ["--base-url", relay, "--device-name", name]
    if target.suffix.lower() == ".exe":
        program = str(target)
    else:
        program = python_executable or sys.executable or shutil.which("python") or shutil.which("py")
        if not program:
            raise FileNotFoundError("Python 3 is required to start the Shraddha PC companion.")
        args.insert(0, str(target))
    child_env = dict(os.environ if environ is None else environ)
    if code:
        child_env["SHRADDHA_PAIRING_CODE"] = code
    else:
        child_env.pop("SHRADDHA_PAIRING_CODE", None)
    return {"program": str(program), "arguments": args, "environment": child_env,
            "companion_path": str(target), "uses_saved_pairing": not bool(code)}
