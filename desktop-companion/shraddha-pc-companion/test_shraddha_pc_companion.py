import unittest
from shraddha_pc_companion import normalize_point, is_sensitive_window, validate_action, encode_screen_frame


class PcCompanionPolicyTests(unittest.TestCase):
    def test_normalize_point_clamps_coordinates(self):
        self.assertEqual(normalize_point(-1), 0.0)
        self.assertEqual(normalize_point(2), 1.0)
        self.assertEqual(normalize_point("0.5"), 0.5)

    def test_sensitive_windows_are_detected(self):
        self.assertTrue(is_sensitive_window("Online Banking - Login"))
        self.assertTrue(is_sensitive_window("Enter OTP"))
        self.assertFalse(is_sensitive_window("Notepad"))

    def test_type_is_disabled_by_default(self):
        self.assertFalse(validate_action({"kind": "type", "text": "hello"}))
        self.assertTrue(validate_action({"kind": "type", "text": "hello"}, allow_typing=True))

    def test_invalid_coordinates_and_unknown_actions_are_rejected(self):
        self.assertFalse(validate_action({"kind": "tap", "x": 5, "y": 0.5}))
        self.assertFalse(validate_action({"kind": "unknown"}))
        self.assertTrue(validate_action({"kind": "tap", "x": 0.5, "y": 0.5}))


if __name__ == "__main__":
    unittest.main()
