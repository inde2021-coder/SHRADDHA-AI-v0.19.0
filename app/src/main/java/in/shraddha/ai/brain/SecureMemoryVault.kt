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

/** Device-local AES-256-GCM memory. The key is non-exportable in Android Keystore. */
class SecureMemoryVault(context: Context) {
    private val prefs = context.getSharedPreferences("shraddha_secure_vault", Context.MODE_PRIVATE)
    private val alias = "shraddha_memory_aes_v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
    @Synchronized fun save(text: String): Result<Unit> = runCatching {
        require(text.length <= 16000) { "Memory is limited to 16,000 characters" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString("memory", payload).commit()) { "Could not save secure memory" }
    }
    @Synchronized fun load(): Result<String> = runCatching {
        val payload = prefs.getString("memory", null) ?: return@runCatching ""
        val parts = payload.split('.', limit = 2); require(parts.size == 2) { "Vault data is invalid" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP); val data = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(data), Charsets.UTF_8)
    }
    @Synchronized fun clear(): Boolean = prefs.edit().remove("memory").commit()
}
