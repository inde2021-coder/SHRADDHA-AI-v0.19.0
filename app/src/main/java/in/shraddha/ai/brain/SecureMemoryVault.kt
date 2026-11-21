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

    /** Backwards-compatible default slot used by encrypted conversation recap. */
    @Synchronized fun save(text: String): Result<Unit> = saveSlot("memory", text)
    @Synchronized fun load(): Result<String> = loadSlot("memory")
    @Synchronized fun clear(): Boolean = clearSlot("memory")

    /** Independent encrypted slots prevent one memory feature overwriting another. */
    @Synchronized fun saveSlot(slot: String, text: String): Result<Unit> = runCatching {
        require(validSlot(slot)) { "Invalid secure memory slot" }
        require(text.length <= 16000) { "Memory is limited to 16,000 characters" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString("slot_$slot", payload).commit()) { "Could not save secure memory" }
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
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val plaintext = String(cipher.doFinal(data), Charsets.UTF_8)
        // One-time migration from the original pre-slot memory preference.
        if (stored == null && legacy != null && slot == "memory") {
            saveSlot("memory", plaintext).getOrThrow()
            prefs.edit().remove("memory").commit()
        }
        plaintext
    }

    @Synchronized fun clearSlot(slot: String): Boolean {
        if (!validSlot(slot)) return false
        val editor = prefs.edit().remove("slot_$slot")
        if (slot == "memory") editor.remove("memory")
        return editor.commit()
    }

    private fun validSlot(slot: String): Boolean = slot.matches(Regex("[a-z][a-z0-9_]{0,39}"))
}
