import tempfile
import unittest

from pc_companion.akkps_browser_operator import AkkpsBrowserOperator, SENSITIVE_LABEL, validate_akkps_url, validate_runner_url


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

    def test_runner_navigation_allows_login_and_runner_only(self):
        for url in ("https://www.akkps.ngo/", "https://www.akkps.ngo/login",
                    "https://www.akkps.ngo/runner", "https://www.akkps.ngo/runner/farmers"):
            with self.subTest(url=url):
                self.assertEqual(validate_runner_url(url), url)

    def test_runner_navigation_rejects_admin_and_traversal(self):
        for url in ("https://www.akkps.ngo/admin", "https://www.akkps.ngo/coordinator/plots",
                    "https://www.akkps.ngo/runner/../admin",
                    "https://www.akkps.ngo/runner/%2e%2e/admin",
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
        for label in ("Password", "OTP", "PIN", "CAPTCHA", "पासवर्ड", "ओटीपी"):
            with self.subTest(label=label):
                self.assertRegex(label, SENSITIVE_LABEL)

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
