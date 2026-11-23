package `in`.shraddha.ai.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceE2ECryptoTest {
    @Test fun p256PeersDeriveSameKeyAndFingerprint() {
        val android = DeviceE2ECrypto.create()
        val windows = DeviceE2ECrypto.create()
        val (androidKey, androidFingerprint) = android.derivePeerKey(windows.publicKeyBase64(), "session-123456789012", "android-1", "windows-1")
        val (windowsKey, windowsFingerprint) = windows.derivePeerKey(android.publicKeyBase64(), "session-123456789012", "windows-1", "android-1")
        assertArrayEquals(androidKey, windowsKey)
        assertEquals(androidFingerprint, windowsFingerprint)
    }

    @Test fun peerKeyAndFingerprintAreBoundToTheSameSession() {
        val alice = DeviceE2ECrypto.create()
        val bob = DeviceE2ECrypto.create()
        val (sessionOneKey, sessionOneFingerprint) =
            alice.derivePeerKey(bob.publicKeyBase64(), "session-one-123456", "alice", "bob")
        val (sessionTwoKey, sessionTwoFingerprint) =
            alice.derivePeerKey(bob.publicKeyBase64(), "session-two-123456", "alice", "bob")
        assertNotEquals(sessionOneKey.toList(), sessionTwoKey.toList())
        assertNotEquals(sessionOneFingerprint, sessionTwoFingerprint)
        sessionOneKey.fill(0)
        sessionTwoKey.fill(0)
    }

    @Test fun aesGcmAuthenticatesPayloadAndAssociatedData() {
        val alice = DeviceE2ECrypto.create()
        val bob = DeviceE2ECrypto.create()
        val (keyA, _) = alice.derivePeerKey(bob.publicKeyBase64(), "session-123456789012", "a", "b")
        val (keyB, _) = bob.derivePeerKey(alice.publicKeyBase64(), "session-123456789012", "b", "a")
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


    @Test fun quickShareCategoryAndOneTimeRequestAreBoundToAesGcmAad() {
        val sender = DeviceE2ECrypto.create()
        val receiver = DeviceE2ECrypto.create()
        val senderKey = sender.derivePeerKey(receiver.publicKeyBase64(), "session-123456789012", "sender", "receiver").first
        val receiverKey = receiver.derivePeerKey(sender.publicKeyBase64(), "session-123456789012", "receiver", "sender").first
        val requestId = "shareRequest123456"
        val aad = "session|sender|receiver|data_share_payload|$requestId|media".toByteArray(Charsets.UTF_8)
        val payload = """{"fileName":"photo.jpg","sizeBytes":4,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","payload":"/////w=="}""".toByteArray()
        val sealed = DeviceE2ECrypto.encrypt(senderKey, payload, aad)
        val opened = DeviceE2ECrypto.decrypt(receiverKey, sealed.nonce, sealed.ciphertext, aad)
        assertArrayEquals(payload, opened)
        val wrongCategory = "session|sender|receiver|data_share_payload|$requestId|documents".toByteArray(Charsets.UTF_8)
        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            DeviceE2ECrypto.decrypt(receiverKey, sealed.nonce, sealed.ciphertext, wrongCategory)
        }
        senderKey.fill(0)
        receiverKey.fill(0)
    }

    @Test fun peersWithDifferentEphemeralKeysDoNotDeriveSameSecret() {
        val a = DeviceE2ECrypto.create()
        val b = DeviceE2ECrypto.create()
        val c = DeviceE2ECrypto.create()
        val (ab, _) = a.derivePeerKey(b.publicKeyBase64(), "session-123456789012", "a", "b")
        val (ac, _) = a.derivePeerKey(c.publicKeyBase64(), "session-123456789012", "a", "c")
        assertNotEquals(ab.toList(), ac.toList())
    }
}
