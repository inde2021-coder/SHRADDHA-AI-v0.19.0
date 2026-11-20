import unittest

from pc_companion.actions import execute_action


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

    def test_open_app_uses_only_resolver(self):
        ok, _ = execute_action({"kind": "open_app", "appName": "Notepad"}, self.gui, self.clipboard, lambda name: name == "Notepad")
        self.assertTrue(ok)

    def test_text_click_is_explicitly_unsupported(self):
        ok, message = execute_action({"kind": "click_text", "text": "Save"}, self.gui, self.clipboard, lambda _name: False)
        self.assertFalse(ok)
        self.assertIn("not supported", message)


if __name__ == "__main__":
    unittest.main()
