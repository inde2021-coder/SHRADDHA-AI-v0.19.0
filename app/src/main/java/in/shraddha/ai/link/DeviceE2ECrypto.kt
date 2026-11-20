package `in`.shraddha.ai.link

import android.util.Base64
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

    fun publicKeyBase64(): String = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)

    fun derivePeerKey(peerPublicKeyBase64: String, localDeviceId: String, peerDeviceId: String): Pair<ByteArray, String> {
        require(localDeviceId.isNotBlank() && peerDeviceId.isNotBlank() && localDeviceId != peerDeviceId)
        val encoded = Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP)
        require(encoded.size in 80..256) { "Invalid ECDH public key size" }
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(pair.private)
        agreement.doPhase(publicKey, true)
        val shared = agreement.generateSecret()
        val salt = (minOf(localDeviceId, peerDeviceId) + "|" + maxOf(localDeviceId, peerDeviceId)).toByteArray(Charsets.UTF_8)
        val prkMac = Mac.getInstance("HmacSHA256")
        prkMac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = prkMac.doFinal(shared)
        prkMac.init(SecretKeySpec(prk, "HmacSHA256"))
        prkMac.update("shraddha-device-e2e-v1".toByteArray(Charsets.UTF_8))
        prkMac.update(1.toByte())
        val key = prkMac.doFinal()
        val publicKeys = listOf(pair.public.encoded, encoded).sortedWith { a, b ->
            val limit = minOf(a.size, b.size)
            var result = 0
            for (i in 0 until limit) { result = (a[i].toInt() and 255).compareTo(b[i].toInt() and 255); if (result != 0) break }
            if (result == 0) a.size.compareTo(b.size) else result
        }
        val fingerprintDigest = MessageDigest.getInstance("SHA-256").digest(publicKeys[0] + publicKeys[1])
        val fingerprint = fingerprintDigest.take(16).joinToString("") { "%02X".format(it) }.chunked(4).joinToString("-")
        shared.fill(0); prk.fill(0)
        return key to fingerprint
    }

    companion object {
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
            return Sealed(Base64.encodeToString(nonce, Base64.NO_WRAP), Base64.encodeToString(sealed, Base64.NO_WRAP))
        }

        fun decrypt(key: ByteArray, nonceBase64: String, ciphertextBase64: String, associatedData: ByteArray): ByteArray {
            require(key.size == 32)
            val nonce = Base64.decode(nonceBase64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
            require(nonce.size == 12 && ciphertext.size in 16..2_500_016)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(ciphertext)
        }
    }
}
