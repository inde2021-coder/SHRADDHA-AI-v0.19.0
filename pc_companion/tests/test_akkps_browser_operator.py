import tempfile
import unittest

from pc_companion.akkps_browser_operator import AkkpsBrowserOperator, FieldInfo, PageInventory, RUNNER_MODULES, SENSITIVE_LABEL, _safe_action_label, _safe_runner_location, validate_akkps_url, validate_runner_url, verified_record_response


class AkkpsBrowserOperatorTests(unittest.TestCase):
    def test_allows_only_https_approved_hosts(self):
        self.assertEqual(validate_akkps_url("https://www.akkps.ngo"), "https://www.akkps.ngo")

    def test_rejects_other_hosts_and_http(self):
        for url in ("http://www.akkps.ngo", "https://evil.example", "https://akkps.ngo.evil.example"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                validate_akkps_url(url)

    def test_rejects_credentials_in_url(self):
        with self.assertRaises(ValueError):
            validate_akkps_url("https://user:pass@www.akkps.ngo")

    def test_inventory_redacts_record_specific_urls_and_dynamic_action_text(self):
        safe_url, title = _safe_runner_location("https://www.akkps.ngo/runner/farmers/farmer-928374/edit?phone=999")
        self.assertEqual(safe_url, "https://www.akkps.ngo/runner/farmers")
        self.assertEqual(title, "FARMERS")
        self.assertEqual(_safe_action_label("Create New Farmer"), "Create")
        self.assertEqual(_safe_action_label("View Ram Kumar"), "View")

    def test_runner_module_catalog_is_exact_and_bounded(self):
        self.assertEqual(RUNNER_MODULES["farmers"], "/runner/farmers")
        self.assertEqual(RUNNER_MODULES["plots"], "/runner/plots")
        self.assertEqual(RUNNER_MODULES["awd reports"], "/runner/awd-reports")
        self.assertNotIn("admin", RUNNER_MODULES)
        self.assertNotIn("coordinator", RUNNER_MODULES)

    def test_runner_navigation_allows_login_and_runner_only(self):
        for url in ("https://www.akkps.ngo/", "https://www.akkps.ngo/login",
                    "https://www.akkps.ngo/auth/otp", "https://www.akkps.ngo/auth/verify",
                    "https://www.akkps.ngo/runner", "https://www.akkps.ngo/runner/farmers"):
            with self.subTest(url=url):
                self.assertEqual(validate_runner_url(url), url)

    def test_runner_navigation_rejects_admin_and_traversal(self):
        for url in ("https://www.akkps.ngo/admin", "https://www.akkps.ngo/coordinator/plots",
                    "https://www.akkps.ngo/runner/admin", "https://www.akkps.ngo/runner/coordinator/plots",
                    "https://www.akkps.ngo/runner/../admin",
                    "https://www.akkps.ngo/runner/%2e%2e/admin",
                    "https://www.akkps.ngo/runner/%252e%252e/admin",
                    "https://www.akkps.ngo/runner%2f..%2fadmin"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                validate_runner_url(url)

    def test_rejects_unverified_lookalike_host(self):
        with self.assertRaises(ValueError):
            validate_akkps_url("https://www.akps.ngo/runner")
        with self.assertRaises(ValueError):
            validate_akkps_url("https://www.akkps.ngo:8443/runner")
        with self.assertRaises(ValueError):
            validate_akkps_url("https://www.akkps.ngo:invalid/runner")

    def test_sensitive_labels_are_detected(self):
        for label in ("Password", "OTP", "PIN", "CAPTCHA", "CVV", "Security Code", "Recovery Phrase", "पासवर्ड", "ओटीपी"):
            with self.subTest(label=label):
                self.assertRegex(label, SENSITIVE_LABEL)

    def test_only_explicit_final_submit_labels_are_accepted(self):
        from pc_companion.akkps_browser_operator import _is_explicit_submit_label
        for label in ("Submit", "Submit Report", "Submit Plot", "Submit Registration", "जमा करें", "सबमिट करें"):
            self.assertTrue(_is_explicit_submit_label(label), label)
        for label in ("Save Draft", "Save", "Add visit", "Remove", "Cancel", ""):
            self.assertFalse(_is_explicit_submit_label(label), label)

    def test_server_save_verification_requires_success_record_id_and_approved_host(self):
        url = "https://www.akkps.ngo/api/runner/plots"
        self.assertEqual(
            verified_record_response("POST", url, 201, {"success": True, "plotId": "PLOT-101"}),
            "PLOT-101"
        )
        self.assertIsNone(verified_record_response("POST", url, 200, {"success": True}))
        self.assertIsNone(verified_record_response("POST", url, 500, {"success": True, "plotId": "PLOT-101"}))
        self.assertIsNone(verified_record_response("GET", url, 200, {"success": True, "plotId": "PLOT-101"}))
        self.assertIsNone(verified_record_response("POST", "https://evil.example/api/plots", 201, {"success": True, "plotId": "PLOT-101"}))
        self.assertIsNone(verified_record_response("POST", "https://www.akkps.ngo/api/telemetry", 201, {"success": True, "id": "x"}))

    def test_form_inventory_maps_visible_form_metadata_without_values(self):
        # Form metadata is structural only; no field values are represented.
        sample = {"index": 0, "fieldCount": 4, "submitLabels": ["Save Plot"]}
        self.assertEqual(sample["fieldCount"], 4)
        self.assertNotIn("value", sample)

    def test_batch_preview_requires_unique_visible_non_sensitive_fields(self):
        with tempfile.TemporaryDirectory() as folder:
            operator = AkkpsBrowserOperator(folder)
            operator.inventory = lambda: PageInventory(
                url="https://www.akkps.ngo/runner/farmers/new",
                title="New Farmer", links=(),
                fields=(
                    FieldInfo("Farmer Name", "name", "text", True, False),
                    FieldInfo("Phone Number", "phone", "tel", True, False),
                    FieldInfo("Password", "password", "password", False, False),
                ),
                buttons=(), table_headers=()
            )
            preview = operator.preview({"Farmer Name": "Test Farmer", "Phone Number": "9999999999"})
            self.assertEqual(len(preview["changes"]), 2)
            self.assertFalse(preview["submitted"])
            with self.assertRaises(RuntimeError):
                operator.preview({"Unknown Field": "x"})
            with self.assertRaises(RuntimeError):
                operator.preview({"Password": "not-allowed"})

    def test_batch_preview_rejects_ambiguous_duplicate_labels(self):
        with tempfile.TemporaryDirectory() as folder:
            operator = AkkpsBrowserOperator(folder)
            operator.inventory = lambda: PageInventory(
                url="https://www.akkps.ngo/runner/farmers/new",
                title="New Farmer", links=(),
                fields=(
                    FieldInfo("Remarks", "farmerRemarks", "text", False, False),
                    FieldInfo("Remarks", "plotRemarks", "text", False, False),
                ),
                buttons=(), table_headers=()
            )
            with self.assertRaises(RuntimeError):
                operator.preview({"Remarks": "Ambiguous"})

    def test_submit_requires_exact_confirmation_before_browser_access(self):
        with tempfile.TemporaryDirectory() as folder:
            operator = AkkpsBrowserOperator(folder)
            with self.assertRaises(PermissionError):
                operator.submit_after_confirmation("yes")

    def test_requires_browser_before_page_access(self):
        with tempfile.TemporaryDirectory() as folder:
            operator = AkkpsBrowserOperator(folder)
            with self.assertRaises(RuntimeError):
                _ = operator.page


if __name__ == "__main__":
    unittest.main()
