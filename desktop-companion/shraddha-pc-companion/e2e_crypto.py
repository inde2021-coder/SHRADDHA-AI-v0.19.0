"""P-256 ECDH, HKDF-SHA256 and AES-256-GCM for Shraddha paired devices.

Both endpoints must compare the displayed short fingerprint out-of-band and
confirm it before using a derived key. The relay only forwards public keys and
opaque ciphertext envelopes.
"""
import base64
import hashlib
import hmac
import os
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


class DeviceE2ECrypto:
    def __init__(self):
        self._private = ec.generate_private_key(ec.SECP256R1())
        self._public_der = self._private.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )

    @property
    def public_key_base64(self):
        return base64.b64encode(self._public_der).decode("ascii")

    def derive_peer_key(self, peer_public_key_base64, local_device_id, peer_device_id):
        if not local_device_id or not peer_device_id or local_device_id == peer_device_id:
            raise ValueError("Invalid peer identity")
        peer_der = base64.b64decode(peer_public_key_base64, validate=True)
        if not 80 <= len(peer_der) <= 256:
            raise ValueError("Invalid ECDH public key size")
        peer_public = serialization.load_der_public_key(peer_der)
        if not isinstance(peer_public, ec.EllipticCurvePublicKey) or not isinstance(peer_public.curve, ec.SECP256R1):
            raise ValueError("Unsupported ECDH curve")
        shared = self._private.exchange(ec.ECDH(), peer_public)
        salt = (min(local_device_id, peer_device_id) + "|" + max(local_device_id, peer_device_id)).encode("utf-8")
        key = HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=b"shraddha-device-e2e-v1").derive(shared)
        first, second = sorted((self._public_der, peer_der))
        fingerprint = hashlib.sha256(first + second).digest()[:16].hex().upper()
        fingerprint = "-".join(fingerprint[i:i + 4] for i in range(0, 16, 4))
        return key, fingerprint

    @staticmethod
    def encrypt(key, plaintext, associated_data):
        if len(key) != 32 or len(plaintext) > 2_500_000:
            raise ValueError("Invalid E2E payload bounds")
        nonce = os.urandom(12)
        ciphertext = AESGCM(key).encrypt(nonce, plaintext, associated_data)
        return {
            "nonce": base64.b64encode(nonce).decode("ascii"),
            "ciphertext": base64.b64encode(ciphertext).decode("ascii"),
        }

    @staticmethod
    def decrypt(key, nonce, ciphertext, associated_data):
        raw_nonce = base64.b64decode(nonce, validate=True)
        raw_ciphertext = base64.b64decode(ciphertext, validate=True)
        if len(key) != 32 or len(raw_nonce) != 12 or not 16 <= len(raw_ciphertext) <= 2_500_016:
            raise ValueError("Invalid E2E envelope")
        return AESGCM(key).decrypt(raw_nonce, raw_ciphertext, associated_data)
