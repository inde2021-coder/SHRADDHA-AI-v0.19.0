package `in`.shraddha.ai.link

import java.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Ephemeral P-256 ECDH + HKDF-SHA256 + AES-256-GCM.
 * Fingerprints must be compared out-of-band by both device owners before a peer is trusted.
 */
class DeviceE2ECrypto private constructor(private val pair: KeyPair) {
    data class Sealed(val nonce: String, val ciphertext: String)

    fun publicKeyBase64(): String = Base64.getEncoder().encodeToString(pair.public.encoded)

    fun derivePeerKey(
        peerPublicKeyBase64: String,
        sessionId: String,
        localDeviceId: String,
        peerDeviceId: String
    ): Pair<ByteArray, String> {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid E2E session binding" }
        require(localDeviceId.length in 1..64 && peerDeviceId.length in 1..64 && localDeviceId != peerDeviceId) {
            "Invalid E2E peer identity"
        }
        val encoded = Base64.getDecoder().decode(peerPublicKeyBase64)
        require(encoded.size in 80..256) { "Invalid ECDH public key size" }
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(pair.private)
        agreement.doPhase(publicKey, true)
        val shared = agreement.generateSecret()
        val sortedIds = listOf(localDeviceId, peerDeviceId).sorted().joinToString("|")
        val context = "shraddha-device-e2e-v2|$sessionId|$sortedIds".toByteArray(Charsets.UTF_8)
        val salt = MessageDigest.getInstance("SHA-256").digest(context)
        val prkMac = Mac.getInstance("HmacSHA256")
        val prk = try {
            prkMac.init(SecretKeySpec(salt, "HmacSHA256"))
            val extracted = prkMac.doFinal(shared)
            prkMac.init(SecretKeySpec(extracted, "HmacSHA256"))
            prkMac.update(context)
            prkMac.update(1.toByte())
            val expanded = prkMac.doFinal()
            extracted.fill(0)
            expanded
        } finally {
            shared.fill(0)
            salt.fill(0)
        }
        val publicKeys = listOf(pair.public.encoded, encoded).sortedWith { a, b ->
            val limit = minOf(a.size, b.size)
            var result = 0
            for (i in 0 until limit) { result = (a[i].toInt() and 255).compareTo(b[i].toInt() and 255); if (result != 0) break }
            if (result == 0) a.size.compareTo(b.size) else result
        }
        val fingerprintDigest = MessageDigest.getInstance("SHA-256").digest(context + publicKeys[0] + publicKeys[1])
        val fingerprint = fingerprintDigest.take(16).joinToString("") { "%02X".format(it) }.chunked(4).joinToString("-")
        context.fill(0)
        return prk to fingerprint
    }

    companion object {
        fun visualFrameAad(
            sessionId: String,
            sourceDeviceId: String,
            targetDeviceId: String,
            frameType: Int,
            sequence: Long
        ): ByteArray {
            require(sessionId.isNotBlank() && sessionId.length <= 128)
            require(sourceDeviceId.length in 1..64 && targetDeviceId.length in 1..64 && sourceDeviceId != targetDeviceId)
            require(frameType in 1..2 && sequence > 0L)
            return "$sessionId|$sourceDeviceId|$targetDeviceId|visual_frame|$frameType|$sequence".toByteArray(Charsets.UTF_8)
        }

        fun create(): DeviceE2ECrypto {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            return DeviceE2ECrypto(generator.generateKeyPair())
        }

        fun encrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): Sealed {
            require(key.size == 32 && plaintext.size <= 2_500_000)
            val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(associatedData)
            val sealed = cipher.doFinal(plaintext)
            return Sealed(Base64.getEncoder().withoutPadding().encodeToString(nonce), Base64.getEncoder().withoutPadding().encodeToString(sealed))
        }

        fun decrypt(key: ByteArray, nonceBase64: String, ciphertextBase64: String, associatedData: ByteArray): ByteArray {
            require(key.size == 32)
            val nonce = Base64.getDecoder().decode(nonceBase64)
            val ciphertext = Base64.getDecoder().decode(ciphertextBase64)
            require(nonce.size == 12 && ciphertext.size in 16..2_500_016)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(ciphertext)
        }
    }
}
