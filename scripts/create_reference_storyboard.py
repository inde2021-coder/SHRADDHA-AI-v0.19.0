#!/usr/bin/env python3
"""Extract a time-uniform contact sheet from the LIA recording stored on main."""
import json
import pathlib
import subprocess

source = pathlib.Path("reference/lia-app-recording/LIA-APP-REFERENCE.mov")
out_dir = pathlib.Path("build/reference-storyboard")
out_dir.mkdir(parents=True, exist_ok=True)
if not source.is_file() or source.stat().st_size == 0:
    raise SystemExit("Main-branch LIA reference recording is missing or empty")

probe = subprocess.run(
    ["ffprobe", "-v", "error", "-show_entries", "format=duration",
     "-of", "json", str(source)],
    check=True, capture_output=True, text=True,
)
duration = float(json.loads(probe.stdout)["format"]["duration"])
if duration <= 0:
    raise SystemExit("Reference video has invalid duration")

# Twelve evenly distributed samples, including the opening and closing moments.
rate = 12.0 / duration
sheet = out_dir / "lia-reference-contact-sheet.jpg"
filters = (
    f"fps={rate:.10f},"
    "scale=480:270:force_original_aspect_ratio=decrease,"
    "pad=480:270:(ow-iw)/2:(oh-ih)/2,"
    "tile=4x3"
)
subprocess.run(
    ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source),
     "-vf", filters, "-frames:v", "1", "-q:v", "3", str(sheet)],
    check=True,
)
(out_dir / "recording-info.txt").write_text(
    f"source={source}\nsize_bytes={source.stat().st_size}\nduration_seconds={duration:.3f}\n"
    f"sample_frames=12\ncontact_sheet={sheet.name}\n",
    encoding="utf-8",
)
if not sheet.is_file() or sheet.stat().st_size < 10_000:
    raise SystemExit("Storyboard image was not generated correctly")
print(f"Created {sheet} from {duration:.2f}s reference video")
