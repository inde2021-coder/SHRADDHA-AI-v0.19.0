import unittest

from pc_companion.command_parser import parse_android_command


class AndroidCommandParserTests(unittest.TestCase):
    def test_basic_commands(self):
        self.assertEqual(parse_android_command("home"), {"kind": "home"})
        self.assertEqual(parse_android_command("scroll down"), {"kind": "scroll", "amount": -5})
        self.assertEqual(parse_android_command("open Settings"), {"kind": "open_app", "appName": "Settings"})
        self.assertEqual(parse_android_command("type नमस्ते"), {"kind": "type", "text": "नमस्ते"})
        self.assertEqual(parse_android_command("read screen"), {"kind": "ocr"})
        self.assertEqual(parse_android_command("स्क्रीन पढ़ो"), {"kind": "ocr"})
        self.assertEqual(parse_android_command("click text Save"), {"kind": "click_text", "text": "Save"})
        self.assertEqual(parse_android_command("बटन दबाओ Submit"), {"kind": "click_text", "text": "Submit"})

    def test_file_search_commands_in_hindi_and_english(self):
        self.assertEqual(parse_android_command("search files invoice"),
                         {"kind": "file_search", "query": "invoice", "path": ""})
        self.assertEqual(parse_android_command("फाइल खोजो बिल"),
                         {"kind": "file_search", "query": "बिल", "path": ""})
        with self.assertRaises(ValueError):
            parse_android_command("search files x")

    def test_file_open_commands_in_hindi_and_english(self):
        self.assertEqual(parse_android_command("open file Documents/report.pdf"),
                         {"kind": "file_open", "path": "Documents/report.pdf"})
        self.assertEqual(parse_android_command("फ़ाइल खोलो Documents/report.pdf"),
                         {"kind": "file_open", "path": "Documents/report.pdf"})
        with self.assertRaises(ValueError):
            parse_android_command("open file ../Windows/system.ini")

    def test_rejects_sensitive_and_unknown(self):
        with self.assertRaises(ValueError):
            parse_android_command("type my password 123")
        with self.assertRaises(ValueError):
            parse_android_command("do anything")

    def test_bounds_and_paths(self):
        with self.assertRaises(ValueError):
            parse_android_command("open C:/Windows")
        with self.assertRaises(ValueError):
            parse_android_command("x" * 501)


if __name__ == "__main__":
    unittest.main()
