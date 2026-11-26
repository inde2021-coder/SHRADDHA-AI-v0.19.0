import math
import unittest

from pc_companion.protocol import valid_pairing_ids, validate_remote_action, validate_remote_action_envelope, remote_action_approval_summary, claim_remote_action_id


class ProtocolTests(unittest.TestCase):
    def test_pairing_ids_match_relay_format(self):
        self.assertTrue(valid_pairing_ids("a" * 24, "b" * 24))
        self.assertTrue(valid_pairing_ids("a_b-cD123456789012", "0123456789abcdef01234567"))
        self.assertFalse(valid_pairing_ids("short", "b" * 24))
        self.assertFalse(valid_pairing_ids("a" * 24, "not-a-device-id"))
        self.assertFalse(valid_pairing_ids(None, "b" * 24))

    def test_remote_action_replay_guard_fails_closed_at_capacity(self):
        receipts = {("peer", f"action_{index:06d}"): 100.0 for index in range(3)}
        self.assertTrue(claim_remote_action_id(receipts, "peer", "action_fresh_01", 101.0, ttl_seconds=600, max_entries=4))
        self.assertFalse(claim_remote_action_id(receipts, "peer", "action_new_0002", 102.0, ttl_seconds=600, max_entries=4))
        self.assertIn(("peer", "action_000000"), receipts)
        self.assertFalse(claim_remote_action_id(receipts, "peer", "action_fresh_01", 103.0, ttl_seconds=600, max_entries=4))
        self.assertTrue(claim_remote_action_id(receipts, "peer", "action_new_0002", 701.0, ttl_seconds=600, max_entries=4))

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

    def test_type_rejects_password_otp_pin_and_cvv_content(self):
        for text in (
            "Password: example123",
            "OTP 123456",
            "UPI PIN 1234",
            "CVV 123",
            "यह ओटीपी 654321 है",
            "पासवर्ड बदलें"
        ):
            self.assertIsNone(validate_remote_action({"kind": "type", "text": text}))

    def test_open_app_rejects_paths_and_shell_fragments(self):
        self.assertIsNotNone(validate_remote_action({"kind": "open_app", "appName": "Notepad"}))
        self.assertIsNone(validate_remote_action({"kind": "open_app", "appName": "..\\evil.exe"}))
        self.assertIsNone(validate_remote_action({"kind": "open_app", "appName": "cmd /c"}))

    def test_scroll_is_bounded(self):
        self.assertEqual(validate_remote_action({"kind": "scroll", "amount": -5}), {"kind": "scroll", "amount": -5})
        self.assertIsNone(validate_remote_action({"kind": "scroll", "amount": 1000}))

    def test_android_direct_action_payload_is_accepted(self):
        action = {"kind": "tap", "x": 0.5, "y": 0.5}
        self.assertEqual(validate_remote_action_envelope(action, "tap"), action)
        self.assertEqual(validate_remote_action_envelope({"action": action}, "tap"), action)

    def test_remote_action_kind_binding_is_enforced(self):
        self.assertIsNone(validate_remote_action_envelope({"kind": "home"}, "tap"))
        self.assertIsNone(validate_remote_action_envelope({"kind": "exec"}, "exec"))

    def test_pc_file_actions_accept_only_relative_paths(self):
        self.assertEqual(validate_remote_action({"kind": "file_list", "path": "Documents/Reports"}),
                         {"kind": "file_list", "path": "Documents/Reports"})
        self.assertEqual(validate_remote_action({"kind": "file_read", "path": "Notes/today.txt"}),
                         {"kind": "file_read", "path": "Notes/today.txt"})
        for action in (
            {"kind": "file_list", "path": "../Windows"},
            {"kind": "file_read", "path": "C:/Users/Someone/secret.txt"},
            {"kind": "file_list", "path": ".ssh"},
            {"kind": "file_read", "path": "/etc/passwd"},
            {"kind": "file_list", "path": "safe", "command": "whoami"},
        ):
            self.assertIsNone(validate_remote_action(action))

    def test_pc_file_search_requires_bounded_query_and_safe_relative_path(self):
        self.assertEqual(validate_remote_action({"kind": "file_search", "query": "invoice", "path": "Documents"}),
                         {"kind": "file_search", "query": "invoice", "path": "Documents"})
        for action in (
            {"kind": "file_search", "query": "x", "path": ""},
            {"kind": "file_search", "query": "invoice", "path": "../"},
            {"kind": "file_search", "query": "invoice", "path": ".ssh"},
            {"kind": "file_search", "query": "x" * 81, "path": ""},
        ):
            self.assertIsNone(validate_remote_action(action))

    def test_file_open_requires_safe_relative_path(self):
        self.assertEqual(validate_remote_action({"kind": "file_open", "path": "Documents/report.pdf"}),
                         {"kind": "file_open", "path": "Documents/report.pdf"})
        for path in ("../secret.pdf", ".ssh/key", "C:/Windows/win.ini"):
            self.assertIsNone(validate_remote_action({"kind": "file_open", "path": path}))

    def test_ocr_is_allowlisted_without_client_supplied_payload(self):
        self.assertEqual(validate_remote_action({"kind": "ocr"}), {"kind": "ocr"})
        self.assertIsNone(validate_remote_action({"kind": "ocr", "script": "arbitrary"}))
        self.assertIsNone(validate_remote_action({"kind": "ocr", "command": "calc.exe"}))
        self.assertEqual(validate_remote_action_envelope({"kind": "ocr"}, "ocr"), {"kind": "ocr"})

    def test_click_text_requires_bounded_exact_label(self):
        self.assertEqual(validate_remote_action({"kind": "click_text", "text": "Save"}), {"kind": "click_text", "text": "Save"})
        self.assertIsNone(validate_remote_action({"kind": "click_text", "text": " "}))
        self.assertIsNone(validate_remote_action({"kind": "click_text", "text": "x" * 121}))

    def test_hardware_status_is_allowlisted_without_parameters(self):
        self.assertEqual(validate_remote_action({"kind": "hardware_status"}), {"kind": "hardware_status"})

    def test_volume_and_brightness_percentages_are_bounded(self):
        self.assertEqual(validate_remote_action({"kind": "set_volume", "percent": 50}),
                         {"kind": "set_volume", "percent": 50})
        self.assertEqual(validate_remote_action({"kind": "set_brightness", "percent": 0}),
                         {"kind": "set_brightness", "percent": 0})
        for action in (
            {"kind": "set_volume", "percent": 101},
            {"kind": "set_volume", "percent": True},
            {"kind": "set_brightness", "percent": -1},
            {"kind": "set_brightness", "percent": 50.5},
        ):
            self.assertIsNone(validate_remote_action(action))

    def test_power_actions_are_strictly_allowlisted(self):
        for operation in ("shutdown", "restart", "sleep"):
            self.assertEqual(validate_remote_action({"kind": "power", "operation": operation}),
                             {"kind": "power", "operation": operation})
        self.assertIsNone(validate_remote_action({"kind": "power", "operation": "format_disk"}))

    def test_remote_action_approval_summary_shows_exact_validated_operation(self):
        summary = remote_action_approval_summary(
            "0123456789abcdef01234567",
            {"kind": "type", "text": "Send this exact approved text"}
        )
        self.assertIn("0123456789abcdef01234567", summary)
        self.assertIn('"kind": "type"', summary)
        self.assertIn('"text": "Send this exact approved text"', summary)
        self.assertIsNone(remote_action_approval_summary("", {"kind": "home"}))
        self.assertIsNone(remote_action_approval_summary("peer", {"kind": "exec"}))

    def test_unknown_action_is_rejected(self):
        self.assertIsNone(validate_remote_action({"kind": "exec", "command": "calc.exe"}))


if __name__ == "__main__":
    unittest.main()
