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
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session binding" }
        require(localDeviceId.length in 1..64 && peerDeviceId.length in 1..64 && localDeviceId != peerDeviceId)
        require(peerPublicKeyBase64.length in 108..344) { "Invalid ECDH public key encoding size" }
        val encoded = Base64.getDecoder().decode(peerPublicKeyBase64)
        require(encoded.size in 80..256) { "Invalid ECDH public key size" }
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        require(publicKey is java.security.interfaces.ECPublicKey) { "Peer key is not an EC public key" }
        val localPublic = pair.public as java.security.interfaces.ECPublicKey
        val peerParams = publicKey.params
        val localParams = localPublic.params
        val peerPrime = (peerParams.curve.field as? java.security.spec.ECFieldFp)?.p
        val localPrime = (localParams.curve.field as? java.security.spec.ECFieldFp)?.p
        require(peerPrime != null && peerPrime == localPrime) { "Peer key must use the same prime field" }
        require(
            peerParams.order == localParams.order &&
                peerParams.cofactor == localParams.cofactor &&
                peerParams.curve.field.fieldSize == localParams.curve.field.fieldSize &&
                peerParams.curve.a == localParams.curve.a &&
                peerParams.curve.b == localParams.curve.b &&
                peerParams.generator.affineX == localParams.generator.affineX &&
                peerParams.generator.affineY == localParams.generator.affineY
        ) { "Peer key must use the same P-256 curve" }

        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(pair.private)
        agreement.doPhase(publicKey, true)
        val shared = agreement.generateSecret()
        val sortedIds = listOf(localDeviceId, peerDeviceId).sorted().joinToString("|")
        val context = "shraddha-device-e2e-v2|$sessionId|$sortedIds"
        val salt = MessageDigest.getInstance("SHA-256").digest(context.toByteArray(Charsets.UTF_8))
        val prkMac = Mac.getInstance("HmacSHA256")
        var prk: ByteArray? = null
        try {
            prkMac.init(SecretKeySpec(salt, "HmacSHA256"))
            val extracted = prkMac.doFinal(shared)
            prk = extracted
            prkMac.init(SecretKeySpec(extracted, "HmacSHA256"))
            prkMac.update(context.toByteArray(Charsets.UTF_8))
            prkMac.update(1.toByte())
            val key = prkMac.doFinal()

            val publicKeys = listOf(pair.public.encoded, encoded).sortedWith { a, b ->
                val limit = minOf(a.size, b.size)
                var result = 0
                for (i in 0 until limit) {
                    result = (a[i].toInt() and 255).compareTo(b[i].toInt() and 255)
                    if (result != 0) break
                }
                if (result == 0) a.size.compareTo(b.size) else result
            }
            val fingerprintDigest = MessageDigest.getInstance("SHA-256").digest(
                context.toByteArray(Charsets.UTF_8) + publicKeys[0] + publicKeys[1]
            )
            val fingerprint = fingerprintDigest.take(16)
                .joinToString("") { "%02X".format(it) }.chunked(4).joinToString("-")
            return key to fingerprint
        } finally {
            shared.fill(0)
            salt.fill(0)
            prk?.fill(0)
        }
    }

    companion object {
        private const val MAX_PLAINTEXT_BYTES = 2_500_000
        private const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + 16
        private const val MAX_CIPHERTEXT_BASE64_CHARS = 3_333_356
        private const val MAX_AAD_BYTES = 1_024

        fun create(): DeviceE2ECrypto {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            return DeviceE2ECrypto(generator.generateKeyPair())
        }

        fun encrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): Sealed {
            require(key.size == 32 && plaintext.size <= MAX_PLAINTEXT_BYTES)
            require(associatedData.size <= MAX_AAD_BYTES) { "Associated data is too large" }
            val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(associatedData)
            val sealed = cipher.doFinal(plaintext)
            try {
                return Sealed(
                    Base64.getEncoder().withoutPadding().encodeToString(nonce),
                    Base64.getEncoder().withoutPadding().encodeToString(sealed)
                )
            } finally {
                sealed.fill(0)
                nonce.fill(0)
            }
        }

        fun decrypt(key: ByteArray, nonceBase64: String, ciphertextBase64: String, associatedData: ByteArray): ByteArray {
            require(key.size == 32)
            require(associatedData.size <= MAX_AAD_BYTES) { "Associated data is too large" }
            require(nonceBase64.length == 16) { "Invalid AES-GCM nonce encoding" }
            require(ciphertextBase64.length in 22..MAX_CIPHERTEXT_BASE64_CHARS) { "Ciphertext encoding is too large or truncated" }
            val nonce = Base64.getDecoder().decode(nonceBase64)
            val ciphertext = Base64.getDecoder().decode(ciphertextBase64)
            require(nonce.size == 12 && ciphertext.size in 16..MAX_CIPHERTEXT_BYTES)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                cipher.updateAAD(associatedData)
                return cipher.doFinal(ciphertext)
            } finally {
                nonce.fill(0)
                ciphertext.fill(0)
            }
        }
    }
}
