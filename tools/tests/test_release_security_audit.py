import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.release_security_audit import audit_repository


class ReleaseSecurityAuditTests(unittest.TestCase):
    def make_repo(self, root: Path, allow_backup="false"):
        manifest = root / "app/src/main/AndroidManifest.xml"
        manifest.parent.mkdir(parents=True)
        manifest.write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
            '<application android:allowBackup="' + allow_backup + '"></application></manifest>',
            encoding="utf-8"
        )
        gradle = root / "app/build.gradle.kts"
        gradle.write_text(
            'release { isMinifyEnabled = true; isShrinkResources = true }',
            encoding="utf-8"
        )

    def test_hardened_release_configuration_passes(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            self.make_repo(root)
            self.assertEqual([], audit_repository(root))

    def test_backup_must_remain_disabled(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            self.make_repo(root, allow_backup="true")
            self.assertTrue(any("backup must remain disabled" in item for item in audit_repository(root)))

    def test_release_shrinking_and_signing_source_are_checked(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            self.make_repo(root)
            gradle = root / "app/build.gradle.kts"
            gradle.write_text('release { isMinifyEnabled = false; isShrinkResources = true; storePassword = "example-hardcoded-password" }')
            errors = audit_repository(root)
            self.assertTrue(any("R8 minification" in item for item in errors))
            self.assertTrue(any("literal release signing password" in item for item in errors))

    def test_private_credential_patterns_and_keystore_files_are_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            self.make_repo(root)
            (root / "app/src/main/java/Example.kt").parent.mkdir(parents=True)
            (root / "app/src/main/java/Example.kt").write_text('val token = "sk-' + "A" * 30 + '"')
            (root / "release.jks").write_bytes(b"not a real key")
            errors = audit_repository(root)
            self.assertTrue(any("credential pattern" in item for item in errors))
            self.assertTrue(any(".jks" in item for item in errors))

    def test_apk_payload_is_scanned_for_private_credentials(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            self.make_repo(root)
            apk = root / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"embedded sk-" + b"B" * 30)
            errors = audit_repository(root, apk)
            self.assertTrue(any("APK entry" in item for item in errors))


if __name__ == "__main__":
    unittest.main()
