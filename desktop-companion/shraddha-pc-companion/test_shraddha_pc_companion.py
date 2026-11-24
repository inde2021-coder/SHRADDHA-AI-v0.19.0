import unittest
from types import SimpleNamespace
from shraddha_pc_companion import build_clipboard_payload, decode_clipboard_payload, clear_peer_key, file_transfer_manifest, read_file_chunk, validate_transfer_chunk, sanitize_clipboard_text, parse_voice_device_command, reconnect_delay, normalize_point, is_sensitive_window, active_window_allows_remote_input, validate_action, encode_screen_frame, encode_encrypted_screen_frame, decode_encrypted_screen_frame, safe_active_window_region, resolve_unique_text_target, safe_relative_path, list_safe_files, read_safe_text_file, write_safe_text_file, redact_ocr_text, search_safe_text_files, validate_missing_transfer_indexes, control_grant_allows, claim_remote_action_id, parse_pairing_qr, build_phone_action, decode_screen_frame


class RemoteInputWindowSafetyTests(unittest.TestCase):
    def test_remote_pointer_and_keyboard_input_fails_closed_for_unknown_or_sensitive_windows(self):
        self.assertFalse(active_window_allows_remote_input(""))
        self.assertFalse(active_window_allows_remote_input("Bank - secure payment"))
        self.assertFalse(active_window_allows_remote_input("Enter OTP"))
        self.assertFalse(active_window_allows_remote_input("Password Manager"))
        self.assertTrue(active_window_allows_remote_input("Notepad"))
        self.assertTrue(active_window_allows_remote_input("Chrome - documentation"))


class DeviceE2ECryptoTests(unittest.TestCase):
    def test_live_relay_transport_matches_android_kdf_and_aad(self):
        import base64
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        from shraddha_pc_companion import create_e2e_identity, derive_e2e_peer_key, e2e_associated_data, decrypt_e2e_message
        android_private, android_public = create_e2e_identity()
        windows_private, windows_public = create_e2e_identity()
        android_key, android_fp = derive_e2e_peer_key(android_private, windows_public, "session-1", "android-1", "windows-1")
        windows_key, windows_fp = derive_e2e_peer_key(windows_private, android_public, "session-1", "windows-1", "android-1")
        self.assertEqual(android_key, windows_key)
        self.assertEqual(android_fp, windows_fp)
        self.assertRegex(android_fp, r"^(?:[A-F0-9]{4}-){7}[A-F0-9]{4}$")
        aad = e2e_associated_data("session-1", "android-1", "windows-1", "remote_action", "action1234567890")
        nonce = bytes([1]) * 12
        encrypted = AESGCM(android_key).encrypt(nonce, b'{"kind":"back"}', aad)
        self.assertEqual(AESGCM(windows_key).decrypt(nonce, encrypted, aad), b'{"kind":"back"}')
        envelope = {"fromDeviceId": "android-1", "messageKind": "remote_action", "actionId": "action1234567890",
                    "actionKind": "back", "nonce": base64.b64encode(nonce).decode("ascii").rstrip("="),
                    "ciphertext": base64.b64encode(encrypted).decode("ascii").rstrip("=")}
        decoded = decrypt_e2e_message(envelope, {"android-1": {"key": windows_key, "local_confirmed": True, "remote_confirmed": True}},
                                     "session-1", "windows-1")
        self.assertEqual(decoded["action"]["kind"], "back")
        clipboard_aad = e2e_associated_data("session-1", "android-1", "windows-1", "clipboard_payload", "request123456789")
        clipboard_bytes = b'{"payload":"YWJj","sizeBytes":3,"sha256":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad","contentType":"clipboard_text"}'
        clipboard_cipher = AESGCM(android_key).encrypt(nonce, clipboard_bytes, clipboard_aad)
        clipboard_message = {"fromDeviceId": "android-1", "messageKind": "clipboard_payload",
                             "requestId": "request123456789", "nonce": base64.b64encode(nonce).decode("ascii").rstrip("="),
                             "ciphertext": base64.b64encode(clipboard_cipher).decode("ascii").rstrip("=")}
        clipboard_decoded = decrypt_e2e_message(clipboard_message,
            {"android-1": {"key": windows_key, "local_confirmed": True, "remote_confirmed": True}},
            "session-1", "windows-1")
        self.assertEqual(clipboard_decoded["type"], "data_share_payload")
        self.assertEqual(clipboard_decoded["category"], "clipboard")
        with self.assertRaises(Exception):
            AESGCM(windows_key).decrypt(nonce, encrypted, aad + b"tampered")

    def test_peer_ephemeral_key_is_discarded_on_disconnect_or_revocation(self):
        from shraddha_pc_companion import clear_peer_key
        mutable_key = bytearray(b"k" * 32)
        peers = {"peer-1": {"key": mutable_key, "local_confirmed": True, "remote_confirmed": True}}
        self.assertTrue(clear_peer_key(peers, "peer-1"))
        self.assertEqual(mutable_key, bytearray(32))
        self.assertNotIn("peer-1", peers)
        self.assertFalse(clear_peer_key(peers, "peer-1"))

    def test_e2e_peer_keys_are_bound_to_the_relay_session(self):
        from shraddha_pc_companion import create_e2e_identity, derive_e2e_peer_key
        alice_private, alice_public = create_e2e_identity()
        bob_private, bob_public = create_e2e_identity()
        alice_key, alice_fingerprint = derive_e2e_peer_key(
            alice_private, bob_public, "session-one-123456", "alice-device", "bob-device"
        )
        bob_key, bob_fingerprint = derive_e2e_peer_key(
            bob_private, alice_public, "session-one-123456", "bob-device", "alice-device"
        )
        other_session_key, other_fingerprint = derive_e2e_peer_key(
            alice_private, bob_public, "session-two-123456", "alice-device", "bob-device"
        )
        self.assertEqual(alice_key, bob_key)
        self.assertEqual(alice_fingerprint, bob_fingerprint)
        self.assertNotEqual(alice_key, other_session_key)
        self.assertNotEqual(alice_fingerprint, other_fingerprint)

    def test_encrypted_small_file_share_binds_one_time_request_and_category(self):
        import base64, json
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        from shraddha_pc_companion import create_e2e_identity, derive_e2e_peer_key, e2e_associated_data, decrypt_e2e_message
        android_private, android_public = create_e2e_identity()
        windows_private, windows_public = create_e2e_identity()
        android_key, _ = derive_e2e_peer_key(android_private, windows_public, "session-1", "android-1", "windows-1")
        windows_key, _ = derive_e2e_peer_key(windows_private, android_public, "session-1", "windows-1", "android-1")
        request_id = "shareRequest123456"
        category = "media"
        payload = {"fileName": "photo.jpg", "mimeType": "image/jpeg", "contentType": "file",
                   "sizeBytes": 4, "sha256": "a" * 64, "payload": "/////w=="}
        aad = e2e_associated_data("session-1", "android-1", "windows-1", "data_share_payload", request_id + "|" + category)
        nonce = bytes([7]) * 12
        encrypted = AESGCM(android_key).encrypt(nonce, json.dumps(payload, separators=(",", ":")).encode(), aad)
        message = {"fromDeviceId": "android-1", "messageKind": "data_share_payload", "requestId": request_id,
                   "category": category, "nonce": base64.b64encode(nonce).decode().rstrip("="),
                   "ciphertext": base64.b64encode(encrypted).decode().rstrip("=")}
        decoded = decrypt_e2e_message(message, {"android-1": {"key": windows_key, "local_confirmed": True, "remote_confirmed": True}},
                                     "session-1", "windows-1")
        self.assertEqual(decoded["type"], "data_share_payload")
        self.assertEqual(decoded["category"], "media")
        self.assertEqual(decoded["requestId"], request_id)
        self.assertEqual(decoded["fileName"], "photo.jpg")
        tampered = dict(message, category="documents")
        with self.assertRaises(Exception):
            decrypt_e2e_message(tampered, {"android-1": {"key": windows_key, "local_confirmed": True, "remote_confirmed": True}},
                                "session-1", "windows-1")

    def test_p256_key_agreement_and_aes_gcm_roundtrip(self):
        from e2e_crypto import DeviceE2ECrypto
        alice = DeviceE2ECrypto()
        bob = DeviceE2ECrypto()
        alice_key, alice_fingerprint = alice.derive_peer_key(bob.public_key_base64, "session-1", "android-device", "windows-device")
        bob_key, bob_fingerprint = bob.derive_peer_key(alice.public_key_base64, "session-1", "windows-device", "android-device")
        self.assertEqual(alice_key, bob_key)
        self.assertEqual(alice_fingerprint, bob_fingerprint)
        sealed = DeviceE2ECrypto.encrypt(alice_key, b"approved remote command", b"android-device|windows-device|remote_action")
        opened = DeviceE2ECrypto.decrypt(bob_key, sealed["nonce"], sealed["ciphertext"], b"android-device|windows-device|remote_action")
        self.assertEqual(opened, b"approved remote command")
        with self.assertRaises(Exception):
            DeviceE2ECrypto.decrypt(bob_key, sealed["nonce"], sealed["ciphertext"], b"wrong-associated-data")

    def test_e2e_rejects_invalid_peer_and_payload_bounds(self):
        from e2e_crypto import DeviceE2ECrypto
        alice = DeviceE2ECrypto()
        with self.assertRaises(ValueError):
            alice.derive_peer_key("%%%invalid%%%", "session-1", "a", "b")
        with self.assertRaises(ValueError):
            DeviceE2ECrypto.encrypt(b"x" * 31, b"hello", b"aad")


class PcCompanionPolicyTests(unittest.TestCase):
    def test_clipboard_payload_roundtrip_and_integrity(self):
        payload = build_clipboard_payload('Namaste Shraddha')
        message = dict(payload, sizeBytes=payload['sizeBytes'], sha256=payload['sha256'])
        self.assertEqual(decode_clipboard_payload(message), 'Namaste Shraddha')
        message['sha256'] = '0' * 64
        with self.assertRaises(ValueError): decode_clipboard_payload(message)

    def test_file_transfer_manifest_and_chunk_bounds(self):
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'sample.bin'
            path.write_bytes(b'x' * 50000)
            manifest = file_transfer_manifest(str(path))
            self.assertEqual(manifest['size'], 50000)
            self.assertEqual(manifest['chunks'], 2)
            self.assertEqual(len(read_file_chunk(str(path), 0)), 48 * 1024)
            with self.assertRaises(ValueError): read_file_chunk(str(path), 2)

    def test_resume_manifest_requires_unique_bounded_indexes(self):
        self.assertEqual(validate_missing_transfer_indexes([0, 2, 4], 5), [0, 2, 4])
        self.assertEqual(validate_missing_transfer_indexes([], 5), [])
        for indexes, total in [([0, 0], 5), ([-1], 5), ([5], 5), ([True], 5), (list(range(2201)), 2201)]:
            with self.assertRaises(ValueError):
                validate_missing_transfer_indexes(indexes, total)

    def test_file_write_is_atomic_requires_overwrite_and_keeps_backup(self):
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as root:
            folder = Path(root) / "project"
            folder.mkdir()
            created = write_safe_text_file(root, "project/notes.md", "first version")
            self.assertEqual(created["status"], "file_written")
            self.assertEqual((folder / "notes.md").read_text(encoding="utf-8"), "first version")
            with self.assertRaises(FileExistsError):
                write_safe_text_file(root, "project/notes.md", "replacement", overwrite=False)
            updated = write_safe_text_file(root, "project/notes.md", "replacement", overwrite=True)
            self.assertEqual((folder / "notes.md").read_text(encoding="utf-8"), "replacement")
            backup = Path(root) / updated["backup"]
            self.assertTrue(backup.is_file())
            self.assertEqual(backup.read_text(encoding="utf-8"), "first version")

    def test_file_write_rejects_unsafe_paths_and_file_types(self):
        import tempfile
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(ValueError):
                write_safe_text_file(root, "../outside.py", "bad")
            with self.assertRaises(ValueError):
                write_safe_text_file(root, "payload.exe", "not executable")
            with self.assertRaises(ValueError):
                write_safe_text_file(root, "large.txt", "x" * 256_001)

    def test_file_write_action_requires_root_bounded_content_and_boolean_overwrite(self):
        action = {"kind": "file_write", "path": "notes.md", "content": "hello", "overwrite": False}
        self.assertFalse(validate_action(action, file_root=""))
        self.assertTrue(validate_action(action, file_root="C:/Approved"))
        self.assertFalse(validate_action({**action, "overwrite": "yes"}, file_root="C:/Approved"))
        self.assertFalse(validate_action({**action, "content": "x" * 256_001}, file_root="C:/Approved"))

    def test_clipboard_sensitive_text_is_blocked(self):
        self.assertEqual(sanitize_clipboard_text('hello'), 'hello')
        with self.assertRaises(ValueError): sanitize_clipboard_text('OTP: 123456')

    def test_voice_command_parser_is_allowlisted(self):
        self.assertEqual(parse_voice_device_command('go back'), {'kind': 'back'})
        self.assertEqual(parse_voice_device_command('ऐप खोलो settings'), {'kind': 'open_app', 'appName': 'settings'})
        with self.assertRaises(ValueError): parse_voice_device_command('send all passwords')

    def test_remote_action_ids_are_single_use_per_peer_and_bounded(self):
        seen = {}
        action_id = "action_1234567890"
        self.assertTrue(claim_remote_action_id(seen, "android-device-1", action_id))
        self.assertFalse(claim_remote_action_id(seen, "android-device-1", action_id))
        self.assertTrue(claim_remote_action_id(seen, "android-device-2", action_id))
        self.assertFalse(claim_remote_action_id(seen, "android-device-1", "bad"))
        self.assertFalse(claim_remote_action_id(seen, "", "action_1234567890"))
        for index in range(20):
            self.assertTrue(claim_remote_action_id(seen, "peer", f"action_{index:08d}", limit=8))
        self.assertLessEqual(len(seen), 8)

    def test_ocr_language_selection_uses_available_english_and_hindi_packs(self):
        from shraddha_pc_companion import select_ocr_languages
        self.assertEqual(select_ocr_languages(["eng", "hin"]), (["eng", "hin"], []))
        self.assertEqual(select_ocr_languages(["eng"]), (["eng"], ["hin"]))
        self.assertEqual(select_ocr_languages(["hin"]), (["hin"], ["eng"]))
        self.assertEqual(select_ocr_languages(["osd"]), ([], ["eng", "hin"]))
        self.assertEqual(select_ocr_languages(["ENG", "hin", "fra"]), (["eng", "hin"], []))

    def test_reconnect_backoff_is_bounded(self):
        self.assertEqual(reconnect_delay(0), 1.0)
        self.assertEqual(reconnect_delay(6), 60.0)
        self.assertEqual(reconnect_delay(99), 60.0)


    def test_plaintext_android_screen_frames_are_rejected(self):
        packet = bytes([6]) + b"phone1" + bytes([1, 255, 216]) + b"jpeg"
        with self.assertRaises(ValueError):
            decode_screen_frame(packet)
        with self.assertRaises(ValueError):
            decode_screen_frame(b"\\x00")


    def test_windows_can_build_bounded_actions_for_an_approved_android_phone(self):
        from types import SimpleNamespace
        args = SimpleNamespace(control_phone="android-1", phone_action="tap", phone_x=0.4, phone_y=0.6)
        self.assertEqual(build_phone_action(args), {"kind": "tap", "x": 0.4, "y": 0.6})
        args.phone_action = "type"
        args.phone_text = "hello"
        self.assertEqual(build_phone_action(args), {"kind": "type", "text": "hello"})
        args.phone_action = "open_app"
        args.phone_app = "Settings"
        self.assertEqual(build_phone_action(args), {"kind": "open_app", "appName": "Settings"})
        args.phone_action = "tap"
        args.phone_x = 1.5
        with self.assertRaises(ValueError):
            build_phone_action(args)


    def test_pairing_qr_parser_validates_scheme_relay_and_code(self):
        relay, code = parse_pairing_qr("shraddha://pair?relay=https%3A%2F%2Frelay.example&code=123456")
        self.assertEqual(relay, "https://relay.example")
        self.assertEqual(code, "123456")
        for payload in (
            "https://evil.example/?relay=https%3A%2F%2Frelay.example&code=123456",
            "shraddha://pair?relay=http%3A%2F%2Frelay.example&code=123456",
            "shraddha://pair?relay=https%3A%2F%2Frelay.example&code=abc123",
        ):
            with self.assertRaises(ValueError):
                parse_pairing_qr(payload)

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

    def test_remote_keyboard_shortcuts_are_allowlisted_and_paste_requires_typing_consent(self):
        for chord in ("enter", "escape", "tab", "up", "ctrl+a", "ctrl+c", "ctrl+f", "alt+tab", "win+d"):
            self.assertTrue(validate_action({"kind": "key_press", "chord": chord}))
        self.assertFalse(validate_action({"kind": "key_press", "chord": "ctrl+v"}))
        self.assertTrue(validate_action({"kind": "key_press", "chord": "ctrl+v"}, allow_typing=True))
        for chord in ("ctrl+alt+delete", "win+r", "powershell", "ctrl+shift+esc", "", "x" * 50):
            self.assertFalse(validate_action({"kind": "key_press", "chord": chord}))

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

    def test_code_assistant_can_load_only_an_approved_project_file(self):
        action = {"kind": "code_assistant", "prompt": "Review this file", "path": "src/main.py"}
        self.assertFalse(validate_action(action, offline_model=object(), file_root=""))
        self.assertTrue(validate_action(action, offline_model=object(), file_root="C:/Approved"))
        self.assertFalse(validate_action({**action, "path": "x" * 501}, offline_model=object(), file_root="C:/Approved"))

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
        self.assertFalse(validate_action({"kind": "file_search", "path": "x" * 501, "query": "x"}, file_root="C:/Approved"))

    def test_pc_control_grant_is_bound_to_one_approved_device_and_expiry(self):
        self.assertTrue(control_grant_allows(True, 200.0, 100.0, "device_A", "device_A"))
        self.assertFalse(control_grant_allows(True, 200.0, 100.0, "device_A", "device_B"))
        self.assertFalse(control_grant_allows(True, 100.0, 100.0, "device_A", "device_A"))
        self.assertFalse(control_grant_allows(False, 200.0, 100.0, "device_A", "device_A"))
        self.assertFalse(control_grant_allows(True, 200.0, 100.0, "", "device_A"))
        self.assertFalse(control_grant_allows(True, 200.0, 100.0, "device_A", ""))


    def test_pc_app_launcher_is_allowlisted(self):
        self.assertTrue(validate_action({"kind": "open_app", "name": "Notepad"}))
        self.assertTrue(validate_action({"kind": "open_app", "name": "settings"}))
        self.assertFalse(validate_action({"kind": "open_app", "name": "cmd /c del C:\\\\*"}))
        self.assertFalse(validate_action({"kind": "open_app", "name": ""}))

    def test_media_and_system_controls_are_allowlisted_and_bounded(self):
        for command in ("play_pause", "next", "previous", "volume_up", "volume_down", "mute"):
            self.assertTrue(validate_action({"kind": "media_control", "command": command}))
        self.assertFalse(validate_action({"kind": "media_control", "command": "run_shell"}))
        self.assertTrue(validate_action({"kind": "system_control", "command": "lock"}))
        self.assertTrue(validate_action({"kind": "system_control", "command": "set_brightness", "value": 55}))
        self.assertFalse(validate_action({"kind": "system_control", "command": "set_brightness", "value": 101}))
        self.assertFalse(validate_action({"kind": "system_control", "command": "shutdown"}))

    def test_screenshot_and_clipboard_transform_actions_are_bounded(self):
        self.assertTrue(validate_action({"kind": "screenshot"}))
        for operation in ("trim", "uppercase", "lowercase", "collapse_whitespace", "remove_blank_lines"):
            self.assertTrue(validate_action({"kind": "clipboard_transform", "operation": operation}))
        self.assertFalse(validate_action({"kind": "clipboard_transform", "operation": "upload_history"}))

    def test_web_search_and_code_assistant_require_bounded_inputs(self):
        self.assertTrue(validate_action({"kind": "web_search", "query": "Windows shortcuts"}))
        self.assertFalse(validate_action({"kind": "web_search", "query": "x"}))
        self.assertFalse(validate_action({"kind": "web_search", "query": "x" * 161}))
        self.assertFalse(validate_action({"kind": "code_assistant", "prompt": "debug"}))
        self.assertTrue(validate_action({"kind": "code_assistant", "prompt": "debug this function"}, offline_model=object()))
        self.assertFalse(validate_action({"kind": "code_assistant", "prompt": " "}, offline_model=object()))


if __name__ == "__main__":
    unittest.main()
