import unittest
from types import SimpleNamespace
from shraddha_pc_companion import normalize_point, is_sensitive_window, validate_action, encode_screen_frame, safe_active_window_region, resolve_unique_text_target, safe_relative_path, list_safe_files, read_safe_text_file, redact_ocr_text, search_safe_text_files


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

    def test_scroll_amount_is_bounded_and_directional(self):
        self.assertTrue(validate_action({"kind": "scroll", "amount": 4}))
        self.assertTrue(validate_action({"kind": "scroll", "amount": -6}))
        self.assertFalse(validate_action({"kind": "scroll", "amount": 0}))
        self.assertFalse(validate_action({"kind": "scroll", "amount": 11}))
        self.assertFalse(validate_action({"kind": "scroll", "amount": True}))

    def test_active_window_capture_fails_closed(self):
        safe = SimpleNamespace(title="Notepad", isMinimized=False, left=20, top=30, width=800, height=600)
        self.assertEqual(safe_active_window_region(safe), {"left": 20, "top": 30, "width": 800, "height": 600})
        sensitive = SimpleNamespace(title="Bank Login", isMinimized=False, left=20, top=30, width=800, height=600)
        self.assertIsNone(safe_active_window_region(sensitive))
        minimized = SimpleNamespace(title="Notepad", isMinimized=True, left=20, top=30, width=800, height=600)
        self.assertIsNone(safe_active_window_region(minimized))
        self.assertIsNone(safe_active_window_region(None))

    def test_click_text_requires_bounded_exact_label(self):
        self.assertTrue(validate_action({"kind": "click_text", "label": "Save"}))
        self.assertTrue(validate_action({"kind": "click_text", "text": "Save"}))
        self.assertFalse(validate_action({"kind": "click_text", "label": " Save"}))
        self.assertFalse(validate_action({"kind": "click_text", "label": ""}))
        self.assertFalse(validate_action({"kind": "click_text", "label": "x" * 121}))

    def test_text_target_requires_one_visible_allowed_exact_match(self):
        button = {"name": "Save", "control_type": "Button", "visible": True}
        self.assertEqual(resolve_unique_text_target([button], "Save"), button)
        self.assertIsNone(resolve_unique_text_target([button, button], "Save"))
        self.assertIsNone(resolve_unique_text_target([{**button, "visible": False}], "Save"))
        self.assertIsNone(resolve_unique_text_target([{**button, "control_type": "Text"}], "Save"))
        self.assertIsNone(resolve_unique_text_target([button], "Save now"))

    def test_invalid_coordinates_and_unknown_actions_are_rejected(self):
        self.assertFalse(validate_action({"kind": "tap", "x": 5, "y": 0.5}))
        self.assertFalse(validate_action({"kind": "unknown"}))
        self.assertTrue(validate_action({"kind": "tap", "x": 0.5, "y": 0.5}))


    def test_file_access_rejects_path_traversal(self):
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as outside:
            outside_file = Path(outside) / "secret.txt"
            outside_file.write_text("private", encoding="utf-8")
            with self.assertRaises(ValueError):
                safe_relative_path(root, str(outside_file))

    def test_file_listing_and_text_preview_are_bounded(self):
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as root:
            Path(root, "notes.txt").write_text("hello", encoding="utf-8")
            Path(root, "private.exe").write_bytes(b"binary")
            self.assertEqual(list_safe_files(root)[0]["name"], "notes.txt")
            self.assertEqual(read_safe_text_file(root, "notes.txt"), "hello")
            with self.assertRaises(ValueError):
                read_safe_text_file(root, "private.exe")
            with self.assertRaises(ValueError):
                read_safe_text_file(root, "../outside.txt")

    def test_ocr_and_file_actions_require_explicit_local_flags(self):
        self.assertFalse(validate_action({"kind": "ocr"}))
        self.assertTrue(validate_action({"kind": "ocr"}, allow_ocr=True))
        self.assertFalse(validate_action({"kind": "file_list"}))
        self.assertTrue(validate_action({"kind": "file_list"}, file_root="C:/Approved"))
        self.assertFalse(validate_action({"kind": "file_read", "path": ""}, file_root="C:/Approved"))


    def test_offline_chat_requires_a_loaded_local_model_and_bounded_prompt(self):
        self.assertFalse(validate_action({"kind": "offline_chat", "prompt": "Hello"}))
        self.assertTrue(validate_action({"kind": "offline_chat", "prompt": "Hello"}, offline_model=object()))
        self.assertFalse(validate_action({"kind": "offline_chat", "prompt": ""}, offline_model=object()))
        self.assertFalse(validate_action({"kind": "offline_chat", "prompt": "x" * 4001}, offline_model=object()))

    def test_ocr_redacts_credentials_adjacent_values_and_long_numbers(self):
        raw = chr(10).join(["Account number", "123456789012", "Public heading", "OTP: 123456", "654321", "Visible note"])
        safe = redact_ocr_text(raw)
        self.assertNotIn("123456789012", safe)
        self.assertNotIn("OTP: 123456", safe)
        self.assertNotIn("654321", safe)
        self.assertIn("Public heading", safe)
        self.assertIn("Visible note", safe)

    def test_ocr_output_is_bounded_and_non_string_safe(self):
        self.assertEqual(redact_ocr_text(None), "")
        self.assertLessEqual(len(redact_ocr_text("hello " * 10000)), 12000)

    def test_file_search_is_confined_to_approved_folder_and_bounded(self):
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as outside:
            Path(root, "notes.txt").write_text("hello from Shraddha", encoding="utf-8")
            Path(root, "binary.exe").write_text("Shraddha", encoding="utf-8")
            Path(outside, "secret.txt").write_text("Shraddha secret", encoding="utf-8")
            matches = search_safe_text_files(root, ".", "shraddha")
            self.assertEqual(len(matches), 1)
            self.assertEqual(matches[0]["path"], "notes.txt")
            self.assertEqual(search_safe_text_files(root, ".", "missing"), [])
            with self.assertRaises(ValueError):
                search_safe_text_files(root, "../" + Path(outside).name, "secret")

    def test_file_search_action_validation(self):
        self.assertTrue(validate_action({"kind": "file_search", "query": "invoice"}, file_root="C:/Approved"))
        self.assertFalse(validate_action({"kind": "file_search", "query": ""}, file_root="C:/Approved"))
        self.assertFalse(validate_action({"kind": "file_search", "query": "x" * 121}, file_root="C:/Approved"))
        self.assertFalse(validate_action({"kind": "file_search", "query": "x", "limit": 51}, file_root="C:/Approved"))


if __name__ == "__main__":
    unittest.main()
