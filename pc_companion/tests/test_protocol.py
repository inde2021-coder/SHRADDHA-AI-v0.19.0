import math
import unittest

from pc_companion.protocol import validate_remote_action


class ProtocolTests(unittest.TestCase):
    def test_valid_normalized_tap(self):
        self.assertEqual(validate_remote_action({"kind": "tap", "x": 0.5, "y": 1}), {"kind": "tap", "x": 0.5, "y": 1.0})

    def test_rejects_out_of_range_and_non_finite_coordinates(self):
        self.assertIsNone(validate_remote_action({"kind": "tap", "x": 1.1, "y": 0.5}))
        self.assertIsNone(validate_remote_action({"kind": "tap", "x": math.nan, "y": 0.5}))

    def test_swipe_requires_four_normalized_coordinates(self):
        self.assertIsNotNone(validate_remote_action({"kind": "swipe", "x1": 0, "y1": 0, "x2": 1, "y2": 1}))
        self.assertIsNone(validate_remote_action({"kind": "swipe", "x1": 0, "y1": 0, "x2": 2, "y2": 1}))

    def test_text_is_bounded(self):
        self.assertIsNotNone(validate_remote_action({"kind": "type", "text": "hello"}))
        self.assertIsNone(validate_remote_action({"kind": "type", "text": "x" * 2001}))
        self.assertIsNone(validate_remote_action({"kind": "click_text", "text": "x" * 121}))

    def test_open_app_rejects_paths_and_shell_fragments(self):
        self.assertIsNotNone(validate_remote_action({"kind": "open_app", "appName": "Notepad"}))
        self.assertIsNone(validate_remote_action({"kind": "open_app", "appName": "..\\evil.exe"}))
        self.assertIsNone(validate_remote_action({"kind": "open_app", "appName": "cmd /c"}))

    def test_scroll_is_bounded(self):
        self.assertEqual(validate_remote_action({"kind": "scroll", "amount": -5}), {"kind": "scroll", "amount": -5})
        self.assertIsNone(validate_remote_action({"kind": "scroll", "amount": 1000}))

    def test_unknown_action_is_rejected(self):
        self.assertIsNone(validate_remote_action({"kind": "exec", "command": "calc.exe"}))


if __name__ == "__main__":
    unittest.main()
