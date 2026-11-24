import math
import unittest


from pc_companion.protocol import valid_pairing_ids, validate_remote_action, validate_remote_action_envelope, remote_action_approval_summary, valid_incoming_file_manifest, matches_transfer_peer, expected_file_chunk_bytes, CHUNK_BYTES, MAX_FILE_BYTES


class ProtocolTests(unittest.TestCase):
    def test_pairing_ids_match_relay_format(self):
        self.assertTrue(valid_pairing_ids("a" * 24, "b" * 24))
        self.assertTrue(valid_pairing_ids("a_b-cD123456789012", "0123456789abcdef01234567"))
        self.assertFalse(valid_pairing_ids("short", "b" * 24))
        self.assertFalse(valid_pairing_ids("a" * 24, "not-a-device-id"))
        self.assertFalse(valid_pairing_ids(None, "b" * 24))

    def test_windows_incoming_file_manifest_requires_trusted_peer_and_single_active_transfer(self):
        active = {}
        self.assertTrue(valid_incoming_file_manifest("a" * 24, "transfer_123456", 49153, 2, "a" * 64, True, active))
        self.assertFalse(valid_incoming_file_manifest("a" * 24, "../bad", 49153, 2, "a" * 64, True, active))
        self.assertFalse(valid_incoming_file_manifest("a" * 24, "transfer_123456", True, 1, "a" * 64, True, active))
        self.assertFalse(valid_incoming_file_manifest("a" * 24, "transfer_123456", 49153, 1, "a" * 64, True, active))
        self.assertFalse(valid_incoming_file_manifest("a" * 24, "transfer_123456", 49153, 2, "a" * 64, False, active))
        self.assertFalse(valid_incoming_file_manifest("a" * 24, "transfer_123456", 49153, 2, "a" * 64, True, {"other": {}}))

    def test_windows_chunks_match_android_48_kib_boundaries(self):
        self.assertEqual(expected_file_chunk_bytes(49152, 1, 0), 49152)
        self.assertEqual(expected_file_chunk_bytes(49153, 2, 0), 49152)
        self.assertEqual(expected_file_chunk_bytes(49153, 2, 1), 1)
        max_chunks = (MAX_FILE_BYTES + CHUNK_BYTES - 1) // CHUNK_BYTES
        self.assertEqual(max_chunks, 2134)
        self.assertEqual(expected_file_chunk_bytes(MAX_FILE_BYTES, max_chunks, max_chunks - 1), MAX_FILE_BYTES - (max_chunks - 1) * CHUNK_BYTES)
        self.assertIsNone(expected_file_chunk_bytes(49153, 1, 0))
        self.assertIsNone(expected_file_chunk_bytes(49153, 2, 2))

    def test_file_transfer_messages_require_exact_peer_and_transfer_identity(self):
        message = {"fromDeviceId": "a" * 24, "transferId": "transfer_123456"}
        self.assertTrue(matches_transfer_peer(message, "a" * 24, "transfer_123456"))
        self.assertFalse(matches_transfer_peer(message, "b" * 24, "transfer_123456"))
        self.assertFalse(matches_transfer_peer(message, "a" * 24, "transfer_654321"))
        self.assertFalse(matches_transfer_peer({"transferId": "transfer_123456"}, "a" * 24, "transfer_123456"))

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
