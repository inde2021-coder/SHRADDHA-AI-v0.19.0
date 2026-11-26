package `in`.shraddha.ai.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class DeviceE2ECryptoTest {
    @Test fun p256PeersDeriveSameKeyAndFingerprint() {
        val android = DeviceE2ECrypto.create()
        val windows = DeviceE2ECrypto.create()
        val (androidKey, androidFingerprint) = android.derivePeerKey(windows.publicKeyBase64(), "session-123456789012", "android-1", "windows-1")
        val (windowsKey, windowsFingerprint) = windows.derivePeerKey(android.publicKeyBase64(), "session-123456789012", "windows-1", "android-1")
        assertArrayEquals(androidKey, windowsKey)
        assertEquals(androidFingerprint, windowsFingerprint)
    }

    @Test fun visualFrameAadBindsSessionPeersFrameTypeAndSequence() {
        assertArrayEquals(
            "session-1|source|target|visual_frame|2|42".toByteArray(Charsets.UTF_8),
            DeviceE2ECrypto.visualFrameAad("session-1", "source", "target", 2, 42L)
        )
        assertThrows(IllegalArgumentException::class.java) {
            DeviceE2ECrypto.visualFrameAad("session-1", "same", "same", 1, 1L)
        }
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
    @Test fun rejectsNonP256PeerKeysAndNonCanonicalBase64() {
        val local = DeviceE2ECrypto.create()
        val wrongCurveGenerator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp384r1"))
        }
        val wrongCurveKey = wrongCurveGenerator.generateKeyPair().public.encoded
        val wrongCurveBase64 = java.util.Base64.getEncoder().encodeToString(wrongCurveKey)
        assertThrows(IllegalArgumentException::class.java) {
            local.derivePeerKey(wrongCurveBase64, "session-123456789012", "local", "peer")
        }
        val peer = DeviceE2ECrypto.create()
        assertThrows(IllegalArgumentException::class.java) {
            local.derivePeerKey(peer.publicKeyBase64().trimEnd('='), "session-123456789012", "local", "peer")
        }
        assertThrows(IllegalArgumentException::class.java) {
            local.derivePeerKey("A".repeat(10000), "session-123456789012", "local", "peer")
        }
    }


    @Test fun confirmationProofIsDirectionalAndBoundToFingerprint() {
        val left = DeviceE2ECrypto.create()
        val right = DeviceE2ECrypto.create()
        val session = "session-123456789012"
        val (leftKey, fingerprint) = left.derivePeerKey(right.publicKeyBase64(), session, "left", "right")
        val (rightKey, _) = right.derivePeerKey(left.publicKeyBase64(), session, "right", "left")
        val sentProof = DeviceE2ECrypto.confirmationProof(leftKey, session, "left", "right", fingerprint)
        val expectedProof = DeviceE2ECrypto.confirmationProof(rightKey, session, "left", "right", fingerprint)
        val reverseProof = DeviceE2ECrypto.confirmationProof(rightKey, session, "right", "left", fingerprint)
        val changedFingerprint = DeviceE2ECrypto.confirmationProof(
            rightKey, session, "left", "right", fingerprint.dropLast(1) + "0"
        )
        assertEquals(sentProof, expectedProof)
        assertNotEquals(sentProof, reverseProof)
        assertNotEquals(sentProof, changedFingerprint)
        leftKey.fill(0)
        rightKey.fill(0)
    }

}
