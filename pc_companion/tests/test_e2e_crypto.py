import unittest

from pc_companion.e2e_crypto import DeviceE2ECrypto, MAX_PLAINTEXT_BYTES, visual_frame_aad


class DeviceE2ECryptoTests(unittest.TestCase):
    def test_two_peers_derive_same_key_and_fingerprint(self):
        alice = DeviceE2ECrypto()
        bob = DeviceE2ECrypto()
        alice_key, alice_fingerprint = alice.derive_peer_key(
            bob.public_key_base64(), "session-123456789012", "a" * 24, "b" * 24
        )
        bob_key, bob_fingerprint = bob.derive_peer_key(
            alice.public_key_base64(), "session-123456789012", "b" * 24, "a" * 24
        )
        self.assertEqual(alice_key, bob_key)
        self.assertEqual(alice_fingerprint, bob_fingerprint)
        self.assertRegex(alice_fingerprint, r"^[A-F0-9]{4}(?:-[A-F0-9]{4}){7}$")

    def test_key_is_bound_to_session_and_peer_ids(self):
        alice = DeviceE2ECrypto()
        bob = DeviceE2ECrypto()
        key_a, _ = alice.derive_peer_key(bob.public_key_base64(), "session-one", "a" * 24, "b" * 24)
        key_b, _ = bob.derive_peer_key(alice.public_key_base64(), "session-two", "b" * 24, "a" * 24)
        self.assertNotEqual(key_a, key_b)

    def test_aes_gcm_round_trip_and_associated_data_authentication(self):
        alice = DeviceE2ECrypto()
        bob = DeviceE2ECrypto()
        key_a, _ = alice.derive_peer_key(bob.public_key_base64(), "session-123456789012", "a" * 24, "b" * 24)
        key_b, _ = bob.derive_peer_key(alice.public_key_base64(), "session-123456789012", "b" * 24, "a" * 24)
        aad = b"session-123456789012|a|b|screen|frame-1"
        nonce, ciphertext = DeviceE2ECrypto.encrypt(key_a, b"encrypted screen frame", aad)
        self.assertEqual(DeviceE2ECrypto.decrypt(key_b, nonce, ciphertext, aad), b"encrypted screen frame")
        with self.assertRaises(ValueError):
            DeviceE2ECrypto.decrypt(key_b, nonce, ciphertext, aad + b"-tampered")

    def test_ciphertext_tampering_is_rejected(self):
        crypto = DeviceE2ECrypto()
        peer = DeviceE2ECrypto()
        key, _ = crypto.derive_peer_key(peer.public_key_base64(), "session-123456789012", "a" * 24, "b" * 24)
        nonce, ciphertext = DeviceE2ECrypto.encrypt(key, b"frame", b"aad")
        replacement = ("A" if ciphertext[0] != "A" else "B") + ciphertext[1:]
        with self.assertRaises(ValueError):
            DeviceE2ECrypto.decrypt(key, nonce, replacement, b"aad")

    def test_visual_frame_aad_matches_android_wire_format(self):
        self.assertEqual(
            visual_frame_aad("session-1", "source", "target", 2, 42),
            b"session-1|source|target|visual_frame|2|42"
        )
        for args in (
            ("session", "same", "same", 1, 1),
            ("session", "source", "target", 3, 1),
            ("session", "source", "target", 1, 0),
        ):
            with self.assertRaises(ValueError):
                visual_frame_aad(*args)

    def test_nonce_is_fresh_for_each_encryption(self):
        key = b"k" * 32
        first = DeviceE2ECrypto.encrypt(key, b"same", b"aad")
        second = DeviceE2ECrypto.encrypt(key, b"same", b"aad")
        self.assertNotEqual(first[0], second[0])

    def test_limits_and_malformed_peer_keys_are_rejected(self):
        with self.assertRaises(ValueError):
            DeviceE2ECrypto.encrypt(b"k" * 32, b"x" * (MAX_PLAINTEXT_BYTES + 1), b"aad")
        with self.assertRaises(ValueError):
            DeviceE2ECrypto.encrypt(b"k" * 32, b"x", b"a" * 1025)
        with self.assertRaises(ValueError):
            DeviceE2ECrypto().derive_peer_key("not-base64", "session", "a" * 24, "b" * 24)
        peer = DeviceE2ECrypto().public_key_base64()
        with self.assertRaises(ValueError):
            DeviceE2ECrypto().derive_peer_key(peer.rstrip("="), "session", "a" * 24, "b" * 24)
        with self.assertRaises(ValueError):
            DeviceE2ECrypto().derive_peer_key(peer + "=", "session", "a" * 24, "b" * 24)


if __name__ == "__main__":
    unittest.main()
