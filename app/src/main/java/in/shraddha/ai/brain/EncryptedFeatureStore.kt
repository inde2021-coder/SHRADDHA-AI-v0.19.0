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
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
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
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val payload = Base64.encodeToString(c.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(c.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString(name, payload).commit()) { "Save failed" }
    }
    @Synchronized fun get(name: String): Result<String> = runCatching {
        val payload = prefs.getString(name, null) ?: return@runCatching ""
        val p = payload.split('.', limit = 2); require(p.size == 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(p[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    /** Encrypt and commit a related set of feature records in one SharedPreferences transaction. */
    @Synchronized fun putAll(values: Map<String, String>): Result<Unit> = runCatching {
        require(values.isNotEmpty()) { "At least one record is required" }
        val secretKey = key()
        val editor = prefs.edit()
        values.forEach { (name, value) ->
            require(name.matches(Regex("[a-zA-Z0-9_.-]{1,80}")))
            require(value.length <= 100_000) { "Data too large for $name" }
            val plain = value.toByteArray(Charsets.UTF_8)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val encrypted = cipher.doFinal(plain)
                val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
                    Base64.encodeToString(encrypted, Base64.NO_WRAP)
                editor.putString(name, payload)
                encrypted.fill(0)
            } finally {
                plain.fill(0)
            }
        }
        check(editor.commit()) { "Atomic encrypted save failed" }
    }

    @Synchronized fun remove(name: String): Boolean = prefs.edit().remove(name).commit()

    /** Atomically remove all encrypted entries in one namespaced feature family. */
    @Synchronized fun removeAllWithPrefix(prefix: String): Boolean {
        require(prefix.matches(Regex("[a-zA-Z0-9_.-]{1,80}")))
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        return editor.commit()
    }
}
