package `in`.shraddha.ai.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFrameValidationTest {
    @Test fun acceptsBoundedJpegWithStartOfImageMarker() {
        assertTrue(DeviceFrameValidation.isValidJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
    }

    @Test fun rejectsEmptyShortAndNonJpegFrames() {
        assertFalse(DeviceFrameValidation.isValidJpeg(byteArrayOf()))
        assertFalse(DeviceFrameValidation.isValidJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertFalse(DeviceFrameValidation.isValidJpeg(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
    }

    @Test fun rejectsOversizedJpegFrames() {
        assertFalse(DeviceFrameValidation.isValidJpeg(ByteArray(DeviceFrameValidation.MAX_JPEG_BYTES + 1)))
    }

    @Test fun encryptedVisualFrameSurvivesRelayEnvelopeAndRetainsAuthenticatedFields() {
        val nonce = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(32) { (it + 1).toByte() }
        val senderPacket = DeviceFrameValidation.encodeForRelay(2, "target-device", "source-device", 42L, nonce, ciphertext)
        val targetLength = senderPacket[1].toInt() and 0xff
        val opaquePayload = senderPacket.copyOfRange(2 + targetLength, senderPacket.size)
        val source = "source-device".toByteArray(Charsets.UTF_8)
        val relayPacket = byteArrayOf(source.size.toByte()) + source + byteArrayOf(senderPacket[0]) + opaquePayload

        val decoded = DeviceFrameValidation.decodeFromRelay(relayPacket)
        assertTrue(decoded != null)
        assertEquals("source-device", decoded!!.sourceDeviceId)
        assertEquals(2, decoded.frameType)
        assertEquals(42L, decoded.sequence)
        assertArrayEquals(nonce, decoded.nonce)
        assertArrayEquals(ciphertext, decoded.ciphertext)
    }

    @Test fun encryptedVisualFrameCodecRejectsInvalidTypeTargetSequenceAndEnvelope() {
        val nonce = ByteArray(12)
        val ciphertext = ByteArray(16)
        assertThrows(IllegalArgumentException::class.java) {
            DeviceFrameValidation.encodeForRelay(3, "peer", "sender", 1L, nonce, ciphertext)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceFrameValidation.encodeForRelay(1, "", "sender", 1L, nonce, ciphertext)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceFrameValidation.encodeForRelay(1, "peer", "sender", 0L, nonce, ciphertext)
        }
        assertFalse(DeviceFrameValidation.decodeFromRelay(byteArrayOf(0, 0x81.toByte())) != null)
    }


    @Test fun endToEndVisualFrameCanBeEncryptedRelayedAndAuthenticated() {
        val senderCrypto = DeviceE2ECrypto.create()
        val receiverCrypto = DeviceE2ECrypto.create()
        val senderKey = senderCrypto.derivePeerKey(receiverCrypto.publicKeyBase64(), "sender", "receiver").first
        val receiverKey = receiverCrypto.derivePeerKey(senderCrypto.publicKeyBase64(), "receiver", "sender").first
        assertArrayEquals(senderKey, receiverKey)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val sequence = 7L
        val aad = "session|sender|receiver|visual_frame|1|7".toByteArray(Charsets.UTF_8)
        val sealed = DeviceE2ECrypto.encrypt(senderKey, jpeg, aad)
        val senderPacket = DeviceFrameValidation.encodeForRelay(
            1, "receiver", sequence,
            java.util.Base64.getDecoder().decode(sealed.nonce),
            java.util.Base64.getDecoder().decode(sealed.ciphertext)
        )
        val targetLength = senderPacket[1].toInt() and 0xff
        val opaquePayload = senderPacket.copyOfRange(2 + targetLength, senderPacket.size)
        val source = "sender".toByteArray(Charsets.UTF_8)
        val relayed = byteArrayOf(source.size.toByte()) + source + byteArrayOf(senderPacket[0]) + opaquePayload
        val frame = DeviceFrameValidation.decodeFromRelay(relayed)!!
        val decoded = DeviceE2ECrypto.decrypt(
            receiverKey,
            java.util.Base64.getEncoder().withoutPadding().encodeToString(frame.nonce),
            java.util.Base64.getEncoder().withoutPadding().encodeToString(frame.ciphertext),
            "session|sender|receiver|visual_frame|1|7".toByteArray(Charsets.UTF_8)
        )
        assertArrayEquals(jpeg, decoded)
        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            DeviceE2ECrypto.decrypt(
                receiverKey,
                java.util.Base64.getEncoder().withoutPadding().encodeToString(frame.nonce),
                java.util.Base64.getEncoder().withoutPadding().encodeToString(frame.ciphertext),
                "session|attacker|receiver|visual_frame|1|7".toByteArray(Charsets.UTF_8)
            )
        }
        senderKey.fill(0); receiverKey.fill(0)
    }

}
