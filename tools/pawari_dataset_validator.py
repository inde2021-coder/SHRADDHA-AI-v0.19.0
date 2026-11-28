#!/usr/bin/env python3
"""Fail-closed validator for consented Shraddha Pawari field CSVs (stdlib only)."""
from __future__ import annotations

import argparse
import csv
import json
import re
import wave
from pathlib import Path

PROFILES = {
    "pawari-balaghat-mp": ("Madhya Pradesh", "Balaghat"),
    "pawari-seoni-mp": ("Madhya Pradesh", "Seoni"),
    "powari-gondia-mh": ("Maharashtra", "Gondia"),
    "powari-bhandara-mh": ("Maharashtra", "Bhandara"),
}
CONSENT_FIELDS = (
    "consent_transcription", "consent_research", "consent_public_release",
    "consent_commercial_training", "consent_voice_synthesis",
)
REVIEW_FIELDS = ("reviewer_1_status", "reviewer_2_status")
REQUIRED = (
    "record_id", "profile_id", "state", "district", "speaker_self_identified_variety",
    "preferred_script", "utterance_type", "transcript_original_script", "audio_filename",
    "audio_format", "sample_rate_hz", "bit_depth", *CONSENT_FIELDS, *REVIEW_FIELDS,
)
ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{5,79}$")


def validate_csv(csv_path: Path, audio_dir: Path | None = None) -> dict:
    errors: list[dict] = []
    warnings: list[dict] = []
    accepted: list[str] = []
    seen: set[str] = set()
    total = 0

    def issue(row: int, code: str, message: str) -> None:
        errors.append({"row": row, "code": code, "message": message})

    try:
        handle = csv_path.open("r", encoding="utf-8-sig", newline="")
    except OSError as exc:
        return {"ok": False, "rows": 0, "eligible_record_ids": [], "errors": [{"row": 0, "code": "CSV_OPEN", "message": str(exc)}], "warnings": []}

    with handle:
        reader = csv.DictReader(handle)
        headers = reader.fieldnames or []
        missing = [name for name in REQUIRED if name not in headers]
        if missing:
            issue(1, "MISSING_COLUMNS", "Missing required columns: " + ", ".join(missing))
            return {"ok": False, "rows": 0, "eligible_record_ids": [], "errors": errors, "warnings": warnings}

        for row_no, row in enumerate(reader, start=2):
            total += 1
            before = len(errors)
            rid = (row.get("record_id") or "").strip()
            profile = (row.get("profile_id") or "").strip()
            if not ID_RE.fullmatch(rid):
                issue(row_no, "INVALID_RECORD_ID", "record_id must be 6-80 safe ASCII characters; do not use personal identifiers.")
            elif rid in seen:
                issue(row_no, "DUPLICATE_RECORD_ID", "record_id is duplicated.")
            seen.add(rid)

            if profile not in PROFILES:
                issue(row_no, "UNKNOWN_PROFILE", "profile_id must be one of the four approved district profiles.")
            else:
                expected_state, expected_district = PROFILES[profile]
                if (row.get("state") or "").strip() != expected_state or (row.get("district") or "").strip().casefold() != expected_district.casefold():
                    issue(row_no, "PROFILE_LOCATION_MISMATCH", "state/district do not match profile_id.")

            for field in REQUIRED:
                if not (row.get(field) or "").strip():
                    issue(row_no, "EMPTY_REQUIRED_FIELD", f"{field} is empty.")

            if not (row.get("transcript_original_script") or "").strip():
                issue(row_no, "EMPTY_TRANSCRIPT", "Original-script transcript is required.")
            if not (row.get("speaker_self_identified_variety") or "").strip():
                issue(row_no, "MISSING_SELF_IDENTIFICATION", "Speaker must self-identify the variety.")

            for field in CONSENT_FIELDS:
                value = (row.get(field) or "").strip().upper()
                if value not in {"YES", "NO", "WITHDRAWN"}:
                    issue(row_no, "INVALID_CONSENT_VALUE", f"{field} must be YES, NO or WITHDRAWN; blank is not consent.")

            for field in REVIEW_FIELDS:
                value = (row.get(field) or "").strip().upper()
                if value not in {"PENDING", "APPROVED", "REJECTED"}:
                    issue(row_no, "INVALID_REVIEW_STATUS", f"{field} must be PENDING, APPROVED or REJECTED.")

            filename = (row.get("audio_filename") or "").strip()
            if filename and (Path(filename).name != filename or filename in {".", ".."}):
                issue(row_no, "UNSAFE_AUDIO_FILENAME", "audio_filename must be a filename only, not a path.")
            if (row.get("audio_format") or "").strip().upper() != "WAV":
                issue(row_no, "AUDIO_FORMAT", "Only WAV master recordings are accepted by this first validator.")
            try:
                rate = int(row.get("sample_rate_hz") or "")
                bits = int(row.get("bit_depth") or "")
                if rate < 16000 or bits < 16:
                    issue(row_no, "AUDIO_QUALITY", "WAV must be at least 16 kHz and 16-bit.")
            except ValueError:
                issue(row_no, "AUDIO_METADATA", "sample_rate_hz and bit_depth must be integers.")

            if audio_dir is not None and filename and Path(filename).name == filename:
                audio_path = audio_dir / filename
                if not audio_path.is_file():
                    issue(row_no, "AUDIO_MISSING", f"Audio file not found: {filename}")
                else:
                    try:
                        with wave.open(str(audio_path), "rb") as wav:
                            if wav.getcomptype() != "NONE" or wav.getsampwidth() * 8 < 16 or wav.getframerate() < 16000:
                                issue(row_no, "AUDIO_WAV_INVALID", "Audio must be uncompressed PCM WAV, >=16-bit and >=16 kHz.")
                    except (wave.Error, OSError):
                        issue(row_no, "AUDIO_WAV_INVALID", "Audio file is not a readable WAV.")

            if len(errors) == before:
                consents = {field: (row.get(field) or "").strip().upper() for field in CONSENT_FIELDS}
                reviews = [(row.get(field) or "").strip().upper() for field in REVIEW_FIELDS]
                # A row is eligible for internal training only with research + commercial-training consent,
                # both independent reviews, and no withdrawal. Public release / voice synthesis are separate.
                if consents["consent_transcription"] == "YES" and consents["consent_research"] == "YES" and consents["consent_commercial_training"] == "YES" and "WITHDRAWN" not in consents.values() and reviews == ["APPROVED", "APPROVED"]:
                    accepted.append(rid)
                else:
                    warnings.append({"row": row_no, "code": "NOT_TRAINING_ELIGIBLE", "message": "Valid record, but missing transcription/research/commercial-training consent, has a withdrawal, or lacks two approvals."})

    return {
        "ok": not errors,
        "rows": total,
        "valid_rows": total - len({e["row"] for e in errors if e["row"] > 1}),
        "training_eligible_count": len(accepted),
        "eligible_record_ids": accepted,
        "errors": errors,
        "warnings": warnings,
        "note": "Validation does not itself authorize publication or model training; confirm licence, consent evidence and community release approval separately.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path, help="Completed Pawari field CSV")
    parser.add_argument("--audio-dir", type=Path, help="Optional directory containing WAV files")
    args = parser.parse_args()
    report = validate_csv(args.csv, args.audio_dir)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["ok"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
