package `in`.shraddha.ai.future

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated, passphrase-encrypted backup envelope. Never stores the passphrase. */
object EncryptedBackupCodec {
    private const val SCHEMA = "shraddha-encrypted-backup-v2"
    private const val ITERATIONS = 240_000
    private const val MAX_PLAINTEXT = 2_000_000

    fun encrypt(plaintext: String, passphrase: CharArray): String {
        require(passphrase.size >= 12) { "Backup passphrase must contain at least 12 characters" }
        require(plaintext.isNotBlank() && plaintext.toByteArray(Charsets.UTF_8).size <= MAX_PLAINTEXT) { "Backup data is empty or too large" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(passphrase, salt), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("schema", SCHEMA)
            .put("kdf", "PBKDF2-HMAC-SHA256")
            .put("iterations", ITERATIONS)
            .put("cipher", "AES-256-GCM")
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("payload", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .toString(2)
    }

    fun decrypt(envelope: String, passphrase: CharArray): String {
        require(passphrase.size >= 12) { "Backup passphrase must contain at least 12 characters" }
        require(envelope.toByteArray(Charsets.UTF_8).size <= MAX_PLAINTEXT + 8192) { "Backup file is too large" }
        val json = JSONObject(envelope)
        require(json.optString("schema") == SCHEMA) { "Unsupported or unencrypted backup format" }
        require(json.optString("kdf") == "PBKDF2-HMAC-SHA256" && json.optString("cipher") == "AES-256-GCM") { "Unsupported backup encryption" }
        val iterations = json.optInt("iterations", -1)
        require(iterations in 150_000..500_000) { "Backup KDF settings are invalid" }
        val salt = Base64.decode(json.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP)
        val payload = Base64.decode(json.getString("payload"), Base64.NO_WRAP)
        require(salt.size == 16 && iv.size == 12 && payload.size in 16..MAX_PLAINTEXT + 16) { "Backup envelope is malformed" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(passphrase, salt, iterations), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }

    private fun derive(passphrase: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
