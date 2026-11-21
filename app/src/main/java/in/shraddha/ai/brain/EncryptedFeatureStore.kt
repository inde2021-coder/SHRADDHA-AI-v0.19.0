package `in`.shraddha.ai.brain

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small encrypted, device-local JSON store for child and person profiles. */
class EncryptedFeatureStore(context: Context) {
    private val prefs = context.getSharedPreferences("shraddha_feature_store", Context.MODE_PRIVATE)
    private val alias = "shraddha_feature_store_aes_v1"
    private fun existingKey(): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(alias, null) as? SecretKey
    }
    private fun key(): SecretKey {
        existingKey()?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    @Synchronized fun put(name: String, value: String): Result<Unit> = runCatching {
        require(name.matches(Regex("[a-zA-Z0-9_.-]{1,80}")))
        require(value.length <= 100_000) { "Data too large" }
        val clear = value.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(clear)
            try {
                val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
                    Base64.encodeToString(encrypted, Base64.NO_WRAP)
                check(prefs.edit().putString(name, payload).commit()) { "Save failed" }
            } finally {
                encrypted.fill(0)
            }
        } finally {
            clear.fill(0)
        }
    }
    @Synchronized fun get(name: String): Result<String> = runCatching {
        require(name.matches(Regex("[a-zA-Z0-9_.-]{1,80}")))
        val payload = prefs.getString(name, null) ?: return@runCatching ""
        require(payload.length <= 600_000) { "Encrypted feature data is too large" }
        val parts = payload.split('.', limit = 2)
        require(parts.size == 2) { "Encrypted feature data format is invalid" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        require(iv.size == 12 && encrypted.size >= 16) { "Encrypted feature data parameters are invalid" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val existingKey = existingKey() ?: error("Encrypted feature-store key is missing; refusing to create a new key for decryption")
        cipher.init(Cipher.DECRYPT_MODE, existingKey, GCMParameterSpec(128, iv))
        val clear = cipher.doFinal(encrypted)
        try {
            String(clear, Charsets.UTF_8)
        } finally {
            clear.fill(0)
            encrypted.fill(0)
        }
    }
    @Synchronized fun remove(name: String): Boolean = prefs.edit().remove(name).commit()
}
