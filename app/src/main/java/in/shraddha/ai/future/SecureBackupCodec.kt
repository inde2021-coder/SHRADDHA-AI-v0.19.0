package `in`.shraddha.ai.future

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-protected, portable backup envelope. Never stores the passphrase or device Keystore key. */
object SecureBackupCodec {
    const val MIN_PASSPHRASE_LENGTH = 12
    private const val ITERATIONS = 210_000
    private const val MAX_PLAINTEXT_BYTES = 1_500_000
    private const val VERSION = "SBA1"
    private val aad = "ShraddhaAI:$VERSION".toByteArray(StandardCharsets.UTF_8)

    fun encrypt(plaintext: String, passphrase: CharArray): String {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) { "Backup passphrase must contain at least 12 characters" }
        val bytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_PLAINTEXT_BYTES) { "Backup payload is empty or exceeds 1.5 MB" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(passphrase, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(bytes)
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf(VERSION, encoder.encodeToString(salt), encoder.encodeToString(iv), encoder.encodeToString(ciphertext)).joinToString(".")
    }

    fun decrypt(envelope: String, passphrase: CharArray): String {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) { "Backup passphrase must contain at least 12 characters" }
        require(envelope.length in 40..2_100_000) { "Encrypted backup is empty or too large" }
        val parts = envelope.split('.')
        require(parts.size == 4 && parts[0] == VERSION) { "Unsupported or invalid encrypted backup format" }
        val decoder = Base64.getUrlDecoder()
        val salt = decoder.decode(parts[1])
        val iv = decoder.decode(parts[2])
        val ciphertext = decoder.decode(parts[3])
        require(salt.size == 16 && iv.size == 12 && ciphertext.size in 17..(MAX_PLAINTEXT_BYTES + 16)) {
            "Encrypted backup fields are invalid"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(passphrase, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return try {
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("Wrong passphrase or backup integrity check failed")
        }
    }

    private fun derive(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, 256)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
