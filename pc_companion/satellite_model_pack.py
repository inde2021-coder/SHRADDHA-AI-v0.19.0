"""Explicit opt-in downloader and checksum verifier for the optional India satellite model.

This does not install or execute MXNet and does not enable field-boundary inference.
The checkpoint is kept outside the Android APK and is downloaded only when this module
is run directly by the Windows PC companion owner.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import tempfile
from urllib.parse import urlparse
from urllib.request import Request, urlopen

MANIFEST_PATH = Path(__file__).with_name("satellite_model_manifest.json")
MAX_MODEL_BYTES = 300 * 1024 * 1024


def load_manifest(path=MANIFEST_PATH):
    manifest = json.loads(Path(path).read_text(encoding="utf-8"))
    required = {
        "model_id", "file_name", "download_url", "published_size_bytes",
        "published_md5", "license", "trained_imagery_source",
        "training_ground_sample_distance_m", "automatic_inference_enabled"
    }
    if not isinstance(manifest, dict) or not required.issubset(manifest):
        raise ValueError("Satellite model manifest is incomplete.")
    url = urlparse(manifest["download_url"])
    if (url.scheme != "https" or url.hostname != "zenodo.org" or
            url.path != "/records/7315090/files/india_Airbus_SPOT_model.params"):
        raise ValueError("Model download must use the pinned HTTPS Zenodo record and file.")
    if (not isinstance(manifest["published_size_bytes"], int) or
            not 1 <= manifest["published_size_bytes"] <= MAX_MODEL_BYTES):
        raise ValueError("Published model size is outside the allowed download range.")
    if manifest["license"] != "CC-BY-4.0":
        raise ValueError("Unexpected model license; manual review is required.")
    if not isinstance(manifest["published_md5"], str) or not re.fullmatch(r"[a-f0-9]{32}", manifest["published_md5"]):
        raise ValueError("Published model checksum is malformed.")
    if manifest.get("automatic_inference_enabled") is not False:
        raise ValueError("Automatic inference must remain disabled until target-region validation.")
    if manifest.get("file_name") != "india_Airbus_SPOT_model.params":
        raise ValueError("Unexpected model filename.")
    return manifest


def verify_model_file(path, manifest=None):
    manifest = manifest or load_manifest()
    file_path = Path(path)
    if not file_path.is_file():
        raise ValueError("Model checkpoint file does not exist.")
    if file_path.stat().st_size != manifest["published_size_bytes"]:
        raise ValueError("Model checkpoint size does not match the published Zenodo record.")
    md5 = hashlib.md5()
    sha256 = hashlib.sha256()
    with file_path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            md5.update(block)
            sha256.update(block)
    actual_md5 = md5.hexdigest()
    if actual_md5.lower() != manifest["published_md5"].lower():
        raise ValueError("Model checkpoint MD5 does not match the published Zenodo record.")
    return {"md5": actual_md5, "sha256": sha256.hexdigest(), "size_bytes": file_path.stat().st_size}


def download_model(destination, manifest=None, timeout=45):
    manifest = manifest or load_manifest()
    destination = Path(destination).expanduser().resolve()
    if destination.exists():
        raise FileExistsError("Destination already exists; move it or choose a new path. No overwrite is performed.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = Request(manifest["download_url"], headers={"User-Agent": "ShraddhaAI-SatelliteModelPack/1.0"})
    md5 = hashlib.md5()
    sha256 = hashlib.sha256()
    size = 0
    temp_name = None
    try:
        with urlopen(request, timeout=timeout) as response:
            final_host = urlparse(response.geturl()).hostname or ""
            if final_host != "zenodo.org" and not final_host.endswith(".zenodo.org"):
                raise ValueError("Model download redirected outside the trusted Zenodo domain.")
            with tempfile.NamedTemporaryFile(prefix="shraddha-model-", suffix=".part",
                                             dir=str(destination.parent), delete=False) as temp:
                temp_name = temp.name
                while True:
                    block = response.read(1024 * 1024)
                    if not block:
                        break
                    size += len(block)
                    if size > MAX_MODEL_BYTES or size > manifest["published_size_bytes"]:
                        raise ValueError("Downloaded model exceeds the published size limit.")
                    temp.write(block)
                    md5.update(block)
                    sha256.update(block)
                temp.flush()
                os.fsync(temp.fileno())
        if size != manifest["published_size_bytes"]:
            raise ValueError("Downloaded model size does not match the published Zenodo record.")
        if md5.hexdigest().lower() != manifest["published_md5"].lower():
            raise ValueError("Downloaded model checksum does not match the published Zenodo record.")
        os.replace(temp_name, destination)
        temp_name = None
        result = {"path": str(destination), "size_bytes": size,
                  "md5": md5.hexdigest(), "sha256": sha256.hexdigest(),
                  "license": manifest["license"], "inference_enabled": False}
        sidecar = destination.with_suffix(destination.suffix + ".verification.json")
        sidecar.write_text(json.dumps(result, indent=2), encoding="utf-8")
        return result
    finally:
        if temp_name:
            try:
                os.unlink(temp_name)
            except OSError:
                pass


def main():
    parser = argparse.ArgumentParser(description="Download the optional India field-boundary model pack.")
    parser.add_argument("destination", help="Destination path outside the Android APK/repository assets")
    args = parser.parse_args()
    result = download_model(args.destination)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
