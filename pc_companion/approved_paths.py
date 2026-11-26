"""Safe open-file/open-folder helpers restricted to standard user folders."""
import os
import re
from pathlib import Path

APPROVED_ROOTS = ("Documents", "Downloads", "Desktop")

def resolve_user_path(relative_path, kind, home=None):
    if kind not in {"file", "folder"}:
        raise ValueError("Unsupported path kind.")
    if not isinstance(relative_path, str) or not relative_path.strip() or len(relative_path) > 260:
        raise ValueError("A bounded relative path is required.")
    raw = relative_path.strip().replace("/", chr(92))
    if raw.startswith(chr(92) * 2) or re.match(r"^[A-Za-z]:", raw) or chr(0) in raw:
        raise ValueError("Absolute paths and network shares are not allowed.")
    parts = [part for part in raw.split(chr(92)) if part]
    if not parts or any(part in {".", ".."} or ":" in part for part in parts):
        raise ValueError("Path traversal and drive syntax are not allowed.")
    root_home = (Path(home) if home is not None else Path.home()).resolve(strict=True)
    if not root_home.is_dir():
        raise ValueError("The current user's home directory is unavailable.")
    matches = []
    for name in APPROVED_ROOTS:
        try:
            # Known folders may be junctions/symlinks. Resolve them, but do not
            # allow a redirected Documents/Downloads/Desktop root outside this
            # user's home directory (including network or removable locations).
            root = (root_home / name).resolve(strict=True)
            root.relative_to(root_home)
            if not root.is_dir():
                continue
            candidate = root.joinpath(*parts).resolve(strict=True)
            candidate.relative_to(root)
            if (kind == "file" and candidate.is_file()) or (kind == "folder" and candidate.is_dir()):
                matches.append(candidate)
        except (OSError, ValueError):
            continue
    if len(matches) != 1:
        if not matches:
            raise FileNotFoundError("No matching item in Documents, Downloads or Desktop.")
        raise ValueError("Path is ambiguous across approved user folders.")
    return matches[0]

def open_user_path(relative_path, kind, opener=None, home=None):
    target = resolve_user_path(relative_path, kind, home)
    launch = opener or getattr(os, "startfile", None)
    if launch is None:
        raise RuntimeError("Windows file association launching is unavailable.")
    launch(str(target))
    return "Opened approved " + kind + ": " + target.name
