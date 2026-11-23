"""Wire-compatible P-256 ECDH / HKDF-SHA256 / AES-256-GCM for Shraddha peer frames."""
import base64
import hashlib
import os

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

MAX_PLAINTEXT_BYTES = 2_500_000
MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + 16
MAX_AAD_BYTES = 1_024


def _b64encode(value):
    return base64.b64encode(value).decode("ascii").rstrip("=")


def _b64decode(value, minimum, maximum):
    if not isinstance(value, str) or not minimum <= len(value) <= maximum:
        raise ValueError("Invalid base64 payload size")
    try:
        return base64.b64decode(value + "=" * ((4 - len(value) % 4) % 4), validate=True)
    except Exception as exc:
        raise ValueError("Invalid base64 payload") from exc


class DeviceE2ECrypto:
    """Ephemeral P-256 key pair with the same derivation and fingerprint as Android."""

    def __init__(self):
        self._private_key = ec.generate_private_key(ec.SECP256R1())

    def public_key_base64(self):
        der = self._private_key.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        return base64.b64encode(der).decode("ascii")

    def derive_peer_key(self, peer_public_key_base64, session_id, local_device_id, peer_device_id):
        if not isinstance(session_id, str) or not session_id.strip() or len(session_id) > 128:
            raise ValueError("Invalid session binding")
        if (not isinstance(local_device_id, str) or not 1 <= len(local_device_id) <= 64 or
                not isinstance(peer_device_id, str) or not 1 <= len(peer_device_id) <= 64 or
                local_device_id == peer_device_id):
            raise ValueError("Invalid peer device binding")
        if not isinstance(peer_public_key_base64, str) or not 108 <= len(peer_public_key_base64) <= 344:
            raise ValueError("Invalid ECDH public key encoding size")
        encoded = base64.b64decode(peer_public_key_base64, validate=True)
        if not 80 <= len(encoded) <= 256:
            raise ValueError("Invalid ECDH public key size")
        peer = serialization.load_der_public_key(encoded)
        if not isinstance(peer, ec.EllipticCurvePublicKey) or not isinstance(peer.curve, ec.SECP256R1):
            raise ValueError("Peer key must use P-256")
        shared = self._private_key.exchange(ec.ECDH(), peer)
        sorted_ids = "|".join(sorted((local_device_id, peer_device_id)))
        context = f"shraddha-device-e2e-v2|{session_id}|{sorted_ids}".encode("utf-8")
        salt = hashlib.sha256(context).digest()
        key = HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=context).derive(shared)
        local_der = self._private_key.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        public_keys = sorted((local_der, encoded))
        fingerprint = hashlib.sha256(context + public_keys[0] + public_keys[1]).digest()[:16]
        fingerprint_text = "-".join(
            fingerprint.hex().upper()[index:index + 4]
            for index in range(0, 32, 4)
        )
        return key, fingerprint_text

    @staticmethod
    def encrypt(key, plaintext, associated_data):
        if (not isinstance(key, (bytes, bytearray, memoryview)) or len(key) != 32 or
                not isinstance(plaintext, bytes) or len(plaintext) > MAX_PLAINTEXT_BYTES or
                not isinstance(associated_data, bytes) or len(associated_data) > MAX_AAD_BYTES):
            raise ValueError("Invalid AES-GCM key, plaintext or associated-data size")
        nonce = os.urandom(12)
        ciphertext = AESGCM(bytes(key)).encrypt(nonce, plaintext, associated_data)
        if len(ciphertext) > MAX_CIPHERTEXT_BYTES:
            raise ValueError("Ciphertext exceeds the frame limit")
        return _b64encode(nonce), _b64encode(ciphertext)

    @staticmethod
    def decrypt(key, nonce_base64, ciphertext_base64, associated_data):
        if (not isinstance(key, bytes) or len(key) != 32 or
                not isinstance(associated_data, bytes) or len(associated_data) > MAX_AAD_BYTES):
            raise ValueError("Invalid AES-GCM key or associated-data size")
        nonce = _b64decode(nonce_base64, 16, 16)
        ciphertext = _b64decode(ciphertext_base64, 22, 3_333_356)
        if len(nonce) != 12 or not 16 <= len(ciphertext) <= MAX_CIPHERTEXT_BYTES:
            raise ValueError("Invalid AES-GCM nonce or ciphertext size")
        try:
            plaintext = AESGCM(key).decrypt(nonce, ciphertext, associated_data)
        except Exception as exc:
            raise ValueError("AES-GCM authentication failed") from exc
        if len(plaintext) > MAX_PLAINTEXT_BYTES:
            raise ValueError("Decrypted frame exceeds the size limit")
        return plaintext


def visual_frame_aad(session_id, source_device_id, target_device_id, frame_type, sequence):
    """Canonical AAD for Android-compatible encrypted screen/camera frames."""
    if (not isinstance(session_id, str) or not 1 <= len(session_id) <= 128 or
            not isinstance(source_device_id, str) or not 1 <= len(source_device_id) <= 64 or
            not isinstance(target_device_id, str) or not 1 <= len(target_device_id) <= 64 or
            source_device_id == target_device_id or frame_type not in (1, 2) or
            not isinstance(sequence, int) or isinstance(sequence, bool) or sequence <= 0):
        raise ValueError("Invalid visual-frame AAD fields")
    return f"{session_id}|{source_device_id}|{target_device_id}|visual_frame|{frame_type}|{sequence}".encode("utf-8")
