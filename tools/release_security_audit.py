#!/usr/bin/env python3
"""Static release security audit for the Shraddha AI Android application."""
from __future__ import annotations

import argparse
import re
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
SECRET_PATTERNS = (
    re.compile(b"-" * 5 + rb"BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(rb'"private_key"\s*:\s*"-----BEGIN'),
    re.compile(rb"\bsk-[A-Za-z0-9]{24,}\b"),
    re.compile(rb"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    re.compile(rb"\bxox[baprs]-[A-Za-z0-9-]{20,}\b"),
)
SKIP_DIRS = {".git", ".gradle", ".idea", "build", "node_modules", ".venv", "venv"}


def _secret_hits(data: bytes) -> list[str]:
    return [pattern.pattern.decode("ascii", errors="ignore") for pattern in SECRET_PATTERNS if pattern.search(data)]


def audit_repository(root: Path, apk: Path | None = None) -> list[str]:
    errors: list[str] = []
    manifest_path = root / "app/src/main/AndroidManifest.xml"
    gradle_path = root / "app/build.gradle.kts"
    if not manifest_path.is_file() or not gradle_path.is_file():
        return ["Android manifest or app Gradle file is missing."]

    try:
        manifest = ET.parse(manifest_path).getroot()
    except ET.ParseError as exc:
        return [f"Android manifest XML is invalid: {exc}"]

    application = manifest.find("application")
    if application is None:
        errors.append("Android manifest has no application element.")
    else:
        if application.get(ANDROID + "allowBackup", "").lower() != "false":
            errors.append("Application backup must remain disabled for private app data.")
        if application.get(ANDROID + "usesCleartextTraffic", "false").lower() == "true":
            errors.append("Cleartext network traffic is enabled.")
        if application.get(ANDROID + "debuggable", "false").lower() == "true":
            errors.append("Release manifest must not enable debuggable.")

        for component in list(application):
            if component.tag not in {"activity", "activity-alias", "service", "receiver", "provider"}:
                continue
            exported = component.get(ANDROID + "exported", "").lower() == "true"
            if not exported:
                continue
            permission = component.get(ANDROID + "permission")
            has_filter = component.find("intent-filter") is not None
            if component.tag == "service" and not permission:
                errors.append(f"Exported service lacks an explicit binding permission: {component.get(ANDROID + 'name', 'unknown')}")
            if component.tag in {"activity", "activity-alias", "receiver"} and not has_filter:
                errors.append(f"Exported component has no explicit intent filter: {component.get(ANDROID + 'name', 'unknown')}")

        for service in application.findall("service"):
            name = service.get(ANDROID + "name", "")
            if "CameraService" in name or "LiveEyesCameraService" in name:
                if "camera" not in service.get(ANDROID + "foregroundServiceType", ""):
                    errors.append(f"Camera service lacks camera foreground-service type: {name}")
            if "VoiceForegroundService" in name and "microphone" not in service.get(ANDROID + "foregroundServiceType", ""):
                errors.append("Voice service lacks microphone foreground-service type.")

    gradle = gradle_path.read_text(encoding="utf-8")
    if not re.search(r"isMinifyEnabled\s*=\s*true", gradle):
        errors.append("Release R8 minification is not enabled.")
    if not re.search(r"isShrinkResources\s*=\s*true", gradle):
        errors.append("Release resource shrinking is not enabled.")
    if re.search(r"(?:storePassword|keyPassword)\s*=\s*[\"'][^\"']+[\"']", gradle):
        errors.append("A literal release signing password is present in Gradle source.")

    for path in root.rglob("*"):
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        if path.is_file():
            lower = path.name.lower()
            if lower.endswith((".jks", ".keystore", ".p12", ".pfx")):
                errors.append(f"Release signing material is present in repository tree: {path.relative_to(root)}")
            if lower.startswith("service-account") and lower.endswith(".json"):
                errors.append(f"Service-account credential file is present: {path.relative_to(root)}")
            if lower == ".env":
                errors.append(f"Unignored .env file is present: {path.relative_to(root)}")
            if path.suffix.lower() in {".kt", ".kts", ".java", ".py", ".js", ".ts", ".json", ".xml", ".properties"}:
                try:
                    data = path.read_bytes()
                except OSError:
                    continue
                if _secret_hits(data):
                    errors.append(f"High-confidence private credential pattern found in source: {path.relative_to(root)}")

    if apk is not None:
        if not apk.is_file():
            errors.append(f"APK file does not exist: {apk}")
        else:
            try:
                with zipfile.ZipFile(apk) as archive:
                    for name in archive.namelist():
                        if name.endswith((".dex", ".so")) or name.startswith(("assets/", "res/raw/")):
                            data = archive.read(name)
                            if _secret_hits(data):
                                errors.append(f"High-confidence private credential pattern found in APK entry: {name}")
            except (OSError, zipfile.BadZipFile) as exc:
                errors.append(f"APK is not a readable ZIP archive: {exc}")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    errors = audit_repository(args.root.resolve(), args.apk.resolve() if args.apk else None)
    if errors:
        for error in errors:
            print("FAIL:", error)
        return 1
    print("PASS: Android manifest, release shrinking, signing-source and credential-pattern checks.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
