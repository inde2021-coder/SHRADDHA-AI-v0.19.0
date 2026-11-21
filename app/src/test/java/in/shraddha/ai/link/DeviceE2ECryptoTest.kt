package `in`.shraddha.ai.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeviceE2ECryptoTest {
    @Test fun p256PeersDeriveSameKeyAndFingerprint() {
        val android = DeviceE2ECrypto.create()
        val windows = DeviceE2ECrypto.create()
        val (androidKey, androidFingerprint) = android.derivePeerKey(windows.publicKeyBase64(), "session-1", "android-1", "windows-1")
        val (windowsKey, windowsFingerprint) = windows.derivePeerKey(android.publicKeyBase64(), "session-1", "windows-1", "android-1")
        assertArrayEquals(androidKey, windowsKey)
        assertEquals(androidFingerprint, windowsFingerprint)
    }

    @Test fun aesGcmAuthenticatesPayloadAndAssociatedData() {
        val alice = DeviceE2ECrypto.create()
        val bob = DeviceE2ECrypto.create()
        val (keyA, _) = alice.derivePeerKey(bob.publicKeyBase64(), "session-1", "a", "b")
        val (keyB, _) = bob.derivePeerKey(alice.publicKeyBase64(), "session-1", "b", "a")
        val aad = "a|b|remote_action".toByteArray()
        val plain = """{"kind":"back"}""".toByteArray()
        val sealed = DeviceE2ECrypto.encrypt(keyA, plain, aad)
        assertArrayEquals(plain, DeviceE2ECrypto.decrypt(keyB, sealed.nonce, sealed.ciphertext, aad))
        try {
            DeviceE2ECrypto.decrypt(keyB, sealed.nonce, sealed.ciphertext, "wrong".toByteArray())
            throw AssertionError("Wrong AAD must fail authentication")
        } catch (_: javax.crypto.AEADBadTagException) {
            // Expected: ciphertext is authenticated, not merely encrypted.
        }
    }

    @Test fun peersWithDifferentEphemeralKeysDoNotDeriveSameSecret() {
        val a = DeviceE2ECrypto.create()
        val b = DeviceE2ECrypto.create()
        val c = DeviceE2ECrypto.create()
        val (ab, _) = a.derivePeerKey(b.publicKeyBase64(), "session-1", "a", "b")
        val (ac, _) = a.derivePeerKey(c.publicKeyBase64(), "session-1", "a", "c")
        assertNotEquals(ab.toList(), ac.toList())
    }

    @Test fun rejectsOversizedEncodedInputsBeforeDecoding() {
        val crypto = DeviceE2ECrypto.create()
        try {
            crypto.derivePeerKey("A".repeat(100_000), "session-1", "android", "windows")
            throw AssertionError("Oversized public key must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            DeviceE2ECrypto.decrypt(ByteArray(32), "A".repeat(100_000), "AA", byteArrayOf())
            throw AssertionError("Oversized nonce must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            DeviceE2ECrypto.decrypt(ByteArray(32), "AAAAAAAAAAAAAAAA", "A".repeat(4_000_000), byteArrayOf())
            throw AssertionError("Oversized ciphertext must be rejected before decoding")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun sessionIdIsCryptographicallyBoundIntoPeerKeyAndFingerprint() {
        val alice = DeviceE2ECrypto.create()
        val bob = DeviceE2ECrypto.create()
        val (keyOne, fingerprintOne) = alice.derivePeerKey(bob.publicKeyBase64(), "session-one", "alice", "bob")
        val (keyTwo, fingerprintTwo) = alice.derivePeerKey(bob.publicKeyBase64(), "session-two", "alice", "bob")
        assertNotEquals(keyOne.toList(), keyTwo.toList())
        assertNotEquals(fingerprintOne, fingerprintTwo)
    }

    @Test fun rejectsPeerKeysFromDifferentEllipticCurve() {
        val alice = DeviceE2ECrypto.create()
        val wrongCurve = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp384r1"))
        }.generateKeyPair()
        val encoded = java.util.Base64.getEncoder().encodeToString(wrongCurve.public.encoded)
        try {
            alice.derivePeerKey(encoded, "session-1", "alice", "bob")
            throw AssertionError("A non-P-256 peer key must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsInvalidSessionBinding() {
        val alice = DeviceE2ECrypto.create()
        val bob = DeviceE2ECrypto.create()
        try {
            alice.derivePeerKey(bob.publicKeyBase64(), "", "alice", "bob")
            throw AssertionError("Blank session binding must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            alice.derivePeerKey(bob.publicKeyBase64(), "x".repeat(129), "alice", "bob")
            throw AssertionError("Oversized session binding must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsOversizedAssociatedData() {
        val key = ByteArray(32)
        try {
            DeviceE2ECrypto.encrypt(key, byteArrayOf(1), ByteArray(1_025))
            throw AssertionError("Oversized AAD must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

}
