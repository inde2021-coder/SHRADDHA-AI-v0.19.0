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

    private fun keyForEncryption(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        require(prefs.all.keys.none { it.startsWith("slot_") } && !prefs.contains("memory")) {
            "Secure memory encryption key is missing; refusing to replace it while encrypted memory exists."
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    /** Backwards-compatible default slot used by encrypted conversation recap. */
    @Synchronized fun save(text: String): Result<Unit> = saveSlot("memory", text)
    @Synchronized fun load(): Result<String> = loadSlot("memory")
    @Synchronized fun clear(): Boolean = clearSlot("memory")

    /** Independent encrypted slots prevent one memory feature overwriting another. */
    @Synchronized fun saveSlot(slot: String, text: String): Result<Unit> = runCatching {
        require(validSlot(slot)) { "Invalid secure memory slot" }
        require(text.length <= 16000) { "Memory is limited to 16,000 characters" }
        val clear = text.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keyForEncryption())
            val encrypted = cipher.doFinal(clear)
            try {
                val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(encrypted, Base64.NO_WRAP)
                check(prefs.edit().putString("slot_$slot", payload).commit()) { "Could not save secure memory" }
            } finally {
                encrypted.fill(0)
            }
        } finally {
            clear.fill(0)
        }
    }

    @Synchronized fun loadSlot(slot: String): Result<String> = runCatching {
        require(validSlot(slot)) { "Invalid secure memory slot" }
        val stored = prefs.getString("slot_$slot", null)
        val legacy = if (stored == null && slot == "memory") prefs.getString("memory", null) else null
        val payload = stored ?: legacy ?: return@runCatching ""
        val parts = payload.split('.', limit = 2)
        require(parts.size == 2) { "Vault data is invalid" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val data = Base64.decode(parts[1], Base64.NO_WRAP)
        try {
            require(iv.size == 12 && data.size >= 16) { "Vault encryption parameters are invalid" }
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existingKey = keyStore.getKey(alias, null) as? SecretKey
                ?: error("Secure memory encryption key is missing; refusing to replace it while encrypted memory exists.")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, existingKey, GCMParameterSpec(128, iv))
            val clear = cipher.doFinal(data)
            try {
                val plaintext = String(clear, Charsets.UTF_8)
                // One-time migration from the original pre-slot memory preference.
                if (stored == null && legacy != null && slot == "memory") {
                    saveSlot("memory", plaintext).getOrThrow()
                    check(prefs.edit().remove("memory").commit()) { "Secure memory was migrated but legacy cleanup failed." }
                }
                plaintext
            } finally {
                clear.fill(0)
            }
        } finally {
            iv.fill(0)
            data.fill(0)
        }
    }

    @Synchronized fun clearSlot(slot: String): Boolean {
        if (!validSlot(slot)) return false
        val editor = prefs.edit().remove("slot_$slot")
        if (slot == "memory") editor.remove("memory")
        return editor.commit()
    }

    private fun validSlot(slot: String): Boolean = slot.matches(Regex("[a-z][a-z0-9_]{0,39}"))
}
