import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from pc_companion.satellite_model_pack import load_manifest, verify_model_file


class SatelliteModelPackTests(unittest.TestCase):
    def test_manifest_pins_zenodo_https_and_license(self):
        manifest = load_manifest()
        self.assertEqual(manifest["license"], "CC-BY-4.0")
        self.assertEqual(manifest["trained_imagery_source"], "Airbus SPOT")
        self.assertEqual(manifest["automatic_inference_enabled"], False)

    def test_manifest_rejects_untrusted_url_checksum_and_inference_flag(self):
        base = load_manifest()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            for changes in (
                {"download_url": "https://evil.example/model.params"},
                {"published_md5": "not-a-checksum"},
                {"automatic_inference_enabled": True},
            ):
                candidate = dict(base)
                candidate.update(changes)
                path.write_text(json.dumps(candidate), encoding="utf-8")
                with self.assertRaises(ValueError):
                    load_manifest(path)

    def test_verifier_rejects_wrong_size_or_checksum(self):
        manifest = {
            "published_size_bytes": 4,
            "published_md5": hashlib.md5(b"good").hexdigest()
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model.params"
            path.write_bytes(b"bad!")
            with self.assertRaises(ValueError):
                verify_model_file(path, manifest)
            path.write_bytes(b"good")
            result = verify_model_file(path, manifest)
            self.assertEqual(result["sha256"], hashlib.sha256(b"good").hexdigest())
            self.assertEqual(result["size_bytes"], 4)


if __name__ == "__main__":
    unittest.main()
