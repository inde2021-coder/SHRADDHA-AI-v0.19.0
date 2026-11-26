import unittest
from unittest.mock import patch

from pc_companion.actions import execute_action, redact_ocr_text


class FakeGui:
    def __init__(self):
        self.calls = []
    def size(self):
        return (1000, 500)
    def click(self, *args): self.calls.append(("click", args))
    def moveTo(self, *args, **kwargs): self.calls.append(("moveTo", args, kwargs))
    def mouseDown(self): self.calls.append(("mouseDown",))
    def mouseUp(self): self.calls.append(("mouseUp",))
    def hotkey(self, *args): self.calls.append(("hotkey", args))
    def scroll(self, amount): self.calls.append(("scroll", amount))
    class FailSafeException(Exception): pass


class FakeClipboard:
    def __init__(self): self.value = None
    def copy(self, value): self.value = value


class ActionTests(unittest.TestCase):
    def setUp(self):
        self.gui = FakeGui()
        self.clipboard = FakeClipboard()

    def test_tap_maps_normalized_coordinates(self):
        ok, _ = execute_action({"kind": "tap", "x": 0.5, "y": 1.0}, self.gui, self.clipboard, lambda _name: False)
        self.assertTrue(ok)
        self.assertEqual(self.gui.calls[0], ("click", (500, 499)))

    def test_swipe_uses_drag_and_releases_mouse(self):
        ok, _ = execute_action({"kind": "swipe", "x1": 0, "y1": 0, "x2": 1, "y2": 1}, self.gui, self.clipboard, lambda _name: False)
        self.assertTrue(ok)
        self.assertIn(("mouseDown",), self.gui.calls)
        self.assertIn(("mouseUp",), self.gui.calls)

    def test_type_uses_clipboard_paste(self):
        ok, _ = execute_action({"kind": "type", "text": "नमस्ते"}, self.gui, self.clipboard, lambda _name: False)
        self.assertTrue(ok)
        self.assertEqual(self.clipboard.value, "नमस्ते")
        self.assertIn(("hotkey", ("ctrl", "v")), self.gui.calls)

    def test_remote_input_fails_closed_for_unknown_and_sensitive_windows(self):
        actions = [
            {"kind": "tap", "x": 0.5, "y": 0.5},
            {"kind": "swipe", "x1": 0.2, "y1": 0.2, "x2": 0.8, "y2": 0.8},
            {"kind": "key_press", "chord": "enter"},
            {"kind": "scroll", "amount": 3},
            {"kind": "type", "text": "hello"},
            {"kind": "home"},
            {"kind": "back"},
        ]
        for title in ("", "Bank - secure payment", "Enter OTP", "Password Manager"):
            with self.subTest(window=title):
                with patch("pc_companion.actions._active_window", side_effect=RuntimeError("blocked window")):
                    for action in actions:
                        ok, message = execute_action(action, self.gui, self.clipboard, lambda _name: False)
                        self.assertFalse(ok)
                        self.assertIn("blocked window", message)
                self.assertEqual(self.gui.calls, [])
                self.assertIsNone(self.clipboard.value)

    def test_open_app_uses_only_resolver(self):
        ok, _ = execute_action({"kind": "open_app", "appName": "Notepad"}, self.gui, self.clipboard, lambda name: name == "Notepad")
        self.assertTrue(ok)

    def test_text_click_uses_the_exact_text_targeter(self):
        calls = []
        ok, message = execute_action(
            {"kind": "click_text", "text": "Save"}, self.gui, self.clipboard,
            lambda _name: False, text_clicker=lambda label: calls.append(label) or "Clicked Save"
        )
        self.assertTrue(ok)
        self.assertEqual(calls, ["Save"])
        self.assertEqual(message, "Clicked Save")

    def test_ocr_action_returns_local_reader_result(self):
        ok, message = execute_action(
            {"kind": "ocr"}, self.gui, self.clipboard, lambda _name: False,
            ocr_reader=lambda: "OCR • Notepad\nNamaste Shraddha"
        )
        self.assertTrue(ok)
        self.assertIn("Namaste Shraddha", message)

    def test_pc_file_browser_is_confined_to_home_and_filters_hidden_files(self):
        import tempfile
        from pathlib import Path
        from pc_companion.actions import list_user_files
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "Documents").mkdir()
            (root / ".ssh").mkdir()
            (root / "Documents" / "note.txt").write_text("hello", encoding="utf-8")
            with patch("pc_companion.actions.Path.home", return_value=root):
                listing = list_user_files("Documents")
                self.assertEqual(listing["entries"][0]["name"], "note.txt")
                self.assertEqual(listing["entries"][0]["type"], "file")
                with self.assertRaisesRegex(RuntimeError, "Parent"):
                    list_user_files("../outside")
                with self.assertRaisesRegex(RuntimeError, "hidden"):
                    list_user_files(".ssh")

    def test_pc_file_search_is_bounded_and_skips_hidden_and_sensitive_paths(self):
        import tempfile
        from pathlib import Path
        from pc_companion.actions import search_user_files
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "Documents").mkdir()
            (root / "Documents" / "invoice-2026.txt").write_text("ok", encoding="utf-8")
            (root / ".ssh").mkdir()
            (root / ".ssh" / "invoice-secret.txt").write_text("secret", encoding="utf-8")
            (root / "AppData").mkdir()
            (root / "AppData" / "invoice-token.txt").write_text("secret", encoding="utf-8")
            with patch("pc_companion.actions.Path.home", return_value=root):
                result = search_user_files("invoice")
                self.assertEqual([item["name"] for item in result["matches"]], ["invoice-2026.txt"])
                self.assertGreaterEqual(result["scannedFiles"], 1)
                with self.assertRaisesRegex(RuntimeError, "2-80"):
                    search_user_files("x")

    def test_pc_text_preview_allows_small_utf8_text_only(self):
        import tempfile
        from pathlib import Path
        from pc_companion.actions import read_user_text_file
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "note.txt").write_text("नमस्ते Shraddha\nOTP: 123456\n654321\nInvoice total 42", encoding="utf-8")
            (root / "image.png").write_bytes(b"not text")
            with patch("pc_companion.actions.Path.home", return_value=root):
                preview = read_user_text_file("note.txt")
                self.assertIn("नमस्ते", preview["content"])
                self.assertIn("[संवेदनशील OCR पंक्ति छिपाई गई]", preview["content"])
                self.assertNotIn("123456", preview["content"])
                self.assertTrue(preview["redacted"])
                with self.assertRaisesRegex(RuntimeError, "text files"):
                    read_user_text_file("image.png")

    def test_remote_file_open_launches_only_supported_pdf(self):
        import tempfile
        from pathlib import Path
        from pc_companion.actions import open_user_path
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "report.pdf").write_bytes(b"%PDF")
            with patch("pc_companion.actions.Path.home", return_value=root), patch("pc_companion.actions.os.startfile", create=True) as startfile:
                result = open_user_path("report.pdf")
                self.assertEqual(result, {"opened": "report.pdf", "type": "file"})
                startfile.assert_called_once()
                self.assertTrue(Path(startfile.call_args.args[0]).samefile(root / "report.pdf"))

    def test_remote_file_open_rejects_executable_extensions(self):
        import tempfile
        from pathlib import Path
        from pc_companion.actions import open_user_path
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "report.pdf").write_bytes(b"%PDF")
            (root / "payload.exe").write_bytes(b"MZ")
            with patch("pc_companion.actions.Path.home", return_value=root), patch("pc_companion.actions.os.startfile", create=True) as startfile:
                with self.assertRaisesRegex(RuntimeError, "file type"):
                    open_user_path("payload.exe")
                startfile.assert_not_called()

    def test_ocr_language_selection_falls_back_to_installed_packs(self):
        from pc_companion.actions import select_ocr_languages
        self.assertEqual(select_ocr_languages(["eng", "hin"]), (["eng", "hin"], []))
        self.assertEqual(select_ocr_languages(["eng"]), (["eng"], ["hin"]))
        self.assertEqual(select_ocr_languages(["hin"]), (["hin"], ["eng"]))
        self.assertEqual(select_ocr_languages(["osd"]), ([], ["eng", "hin"]))
        self.assertEqual(select_ocr_languages(["ENG", "hin"]), (["eng", "hin"], []))

    def test_ocr_redacts_secret_lines_and_long_numbers(self):
        safe = redact_ocr_text("Invoice total 42\nOTP: 123456\n654321\nAPI key: abc123secret\nAmount 900")
        self.assertIn("Invoice total 42", safe)
        self.assertIn("[संवेदनशील OCR पंक्ति छिपाई गई]", safe)
        self.assertIn("[संवेदनशील जानकारी छिपाई गई]", safe)
        self.assertNotIn("654321", safe)
        self.assertNotIn("abc123secret", safe)

    def test_ocr_aborts_if_active_window_changes_before_capture(self):
        import pc_companion.actions as actions
        active = object()
        with patch("pc_companion.actions._active_window", side_effect=[
            (active, "Notepad", (0, 0, 500, 300)),
            (active, "Password Manager", (0, 0, 500, 300)),
        ]), patch.object(self.gui, "screenshot", create=True) as screenshot:
            with self.assertRaisesRegex(RuntimeError, "active window changed"):
                actions.read_active_window_ocr(self.gui)
        screenshot.assert_not_called()

    def test_ocr_aborts_for_minimized_active_window(self):
        import pc_companion.actions as actions
        class MinimizedWindow:
            isMinimized = True
        active = MinimizedWindow()
        with patch("pc_companion.actions._active_window", return_value=(active, "Notepad", (0, 0, 500, 300))), \
             patch.object(self.gui, "screenshot", create=True) as screenshot:
            with self.assertRaisesRegex(RuntimeError, "minimized"):
                actions.read_active_window_ocr(self.gui)
        screenshot.assert_not_called()

    def test_file_open_action_is_wired_to_safe_user_path_handler(self):
        from pc_companion.actions import open_user_path
        with patch("pc_companion.actions.open_user_path",
                   return_value={"opened": "Documents/report.pdf", "type": "file"}) as opener:
            ok, result = execute_action(
                {"kind": "file_open", "path": "Documents/report.pdf"},
                self.gui, self.clipboard, lambda _name: False
            )
        self.assertTrue(ok)
        self.assertIn("Documents/report.pdf", result)
        opener.assert_called_once_with("Documents/report.pdf")
    def test_text_click_aborts_if_foreground_window_changes_before_click(self):
        import sys
        from types import SimpleNamespace
        from pc_companion.actions import click_unique_text
        clicked = []
        class FakeControl:
            element_info = SimpleNamespace(name="Save", control_type="Button")
            def is_visible(self): return True
            def is_enabled(self): return True
            def click_input(self): clicked.append(True)
        control = FakeControl()
        root = SimpleNamespace(descendants=lambda: [control])
        desktop = SimpleNamespace(get_active=lambda: root)
        pywinauto = SimpleNamespace(Desktop=lambda backend: desktop)
        before = SimpleNamespace(_hWnd=101)
        after = SimpleNamespace(_hWnd=202)
        with patch.dict(sys.modules, {"pywinauto": pywinauto}), patch(
            "pc_companion.actions._active_window",
            side_effect=[(before, "Notepad", (0, 0, 500, 300)),
                         (after, "Bank - secure payment", (0, 0, 500, 300))]
        ):
            with self.assertRaisesRegex(RuntimeError, "active window changed"):
                click_unique_text("Save")
        self.assertEqual(clicked, [])

if __name__ == "__main__":
    unittest.main()
