import os
import sys
import tempfile
import unittest
from pathlib import Path

MYRA_ROOT = Path(__file__).resolve().parents[1] / "MYRA_ULTRA_APP"
sys.path.insert(0, str(MYRA_ROOT))
from shraddha_link_core import build_launch_spec, validate_pairing_code, validate_relay_url


class MyraShraddhaIntegrationTests(unittest.TestCase):
    def test_relay_requires_https_origin_only(self):
        self.assertEqual(validate_relay_url("https://relay.example/"), "https://relay.example")
        for value in ("http://relay.example", "relay.example", "https://user:pass@relay.example",
                      "https://relay.example/path", "https://relay.example/?token=x",
                      "https://relay.example/#frag"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate_relay_url(value)

    def test_pairing_code_is_optional_or_exactly_six_digits(self):
        self.assertEqual(validate_pairing_code(""), "")
        self.assertEqual(validate_pairing_code("012345"), "012345")
        for value in ("12345", "1234567", "12ab56", " 12345 "):
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate_pairing_code(value)

    def test_source_companion_launch_keeps_pairing_code_out_of_arguments(self):
        with tempfile.TemporaryDirectory() as folder:
            app = Path(folder) / "MYRA_ULTRA_APP"
            app.mkdir()
            companion = Path(folder) / "shraddha-pc-companion"
            companion.mkdir()
            (companion / "shraddha_pc_companion.py").write_text("# companion", encoding="utf-8")
            spec = build_launch_spec(app, "https://relay.example", "012345", "MYRA Test PC",
                                     python_executable="python-test", environ={"PATH": "safe"})
            self.assertEqual(spec["program"], "python-test")
            self.assertNotIn("012345", spec["arguments"])
            self.assertEqual(spec["environment"]["SHRADDHA_PAIRING_CODE"], "012345")
            self.assertNotIn("GEMINI_API_KEY", spec["environment"])
            self.assertNotIn("LINKEDIN_ACCESS_TOKEN", spec["environment"])
            self.assertIn("--base-url", spec["arguments"])
            self.assertEqual(spec["arguments"][-1], "MYRA Test PC")
            self.assertFalse(spec["uses_saved_pairing"])

    def test_packaged_companion_executable_is_preferred(self):
        with tempfile.TemporaryDirectory() as folder:
            app = Path(folder) / "MYRA_ULTRA_APP"
            app.mkdir()
            executable = Path(folder) / "ShraddhaAI-Desktop-Companion.exe"
            executable.write_bytes(b"test")
            spec = build_launch_spec(app, "https://relay.example", "", "MYRA PC",
                                     environ={"PATH": "safe"})
            self.assertEqual(spec["program"], str(executable))
            self.assertNotIn(".py", " ".join(spec["arguments"]))

    def test_saved_pairing_does_not_inherit_stale_one_time_code(self):
        with tempfile.TemporaryDirectory() as folder:
            app = Path(folder) / "MYRA_ULTRA_APP"
            app.mkdir()
            companion = Path(folder) / "shraddha-pc-companion"
            companion.mkdir()
            (companion / "shraddha_pc_companion.py").write_text("# companion", encoding="utf-8")
            spec = build_launch_spec(app, "https://relay.example", "", "MYRA PC",
                                     python_executable="python-test",
                                     environ={"SHRADDHA_PAIRING_CODE": "999999"})
            self.assertNotIn("SHRADDHA_PAIRING_CODE", spec["environment"])
            self.assertTrue(spec["uses_saved_pairing"])

    def test_rejects_control_characters_in_device_name(self):
        with tempfile.TemporaryDirectory() as folder:
            app = Path(folder) / "MYRA_ULTRA_APP"
            app.mkdir()
            companion = Path(folder) / "shraddha-pc-companion"
            companion.mkdir()
            (companion / "shraddha_pc_companion.py").write_text("# companion", encoding="utf-8")
            with self.assertRaises(ValueError):
                build_launch_spec(app, "https://relay.example", "", "MYRA\nPC",
                                  python_executable="python-test")


if __name__ == "__main__":
    unittest.main()
