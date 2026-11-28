import tempfile
import unittest

from akkps_browser_operator import AkkpsBrowserOperator, validate_akkps_url


class AkkpsBrowserOperatorTests(unittest.TestCase):
    def test_allows_only_https_approved_hosts(self):
        self.assertEqual(validate_akkps_url("https://www.akkps.ngo"), "https://www.akkps.ngo")
        self.assertEqual(validate_akkps_url("https://www.akps.ngo/runner"), "https://www.akps.ngo/runner")

    def test_rejects_other_hosts_and_http(self):
        for url in ("http://www.akkps.ngo", "https://evil.example", "https://akkps.ngo.evil.example"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                validate_akkps_url(url)

    def test_rejects_credentials_in_url(self):
        with self.assertRaises(ValueError):
            validate_akkps_url("https://user:pass@www.akkps.ngo")

    def test_requires_browser_before_page_access(self):
        with tempfile.TemporaryDirectory() as folder:
            operator = AkkpsBrowserOperator(folder)
            with self.assertRaises(RuntimeError):
                _ = operator.page


if __name__ == "__main__":
    unittest.main()
