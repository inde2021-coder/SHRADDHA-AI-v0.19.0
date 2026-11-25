package com.Lia.assistant.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Gemini key encrypted with a non-exportable Android Keystore AES key.
 * Existing plaintext preference values are migrated on first read.
 */
object ApiKeyStore {
    private const val PREF_NAME = "nova_prefs"
    private const val LEGACY_KEY = "gemini_api_key"
    private const val ENCRYPTED_KEY = "gemini_api_key_ciphertext"
    private const val IV_KEY = "gemini_api_key_iv"
    private const val KEY_ALIAS = "shraddha_gemini_api_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun getKey(context: Context): String {
        if (PasswordProtectedApiKeyVault.isConfigured(context)) return PasswordProtectedApiKeyVault.get(context, "GEMINI")
        return getLegacyKey(context)
    }

    @Synchronized
    internal fun getLegacyKey(context: Context): String {
        val prefs = getPrefs(context)
        val ciphertext = prefs.getString(ENCRYPTED_KEY, null)
        val ivText = prefs.getString(IV_KEY, null)

        if (ciphertext.isNullOrBlank() && ivText.isNullOrBlank()) {
            // Migrate old plaintext values once; never leave the old preference behind.
            val legacy = prefs.getString(LEGACY_KEY, "").orEmpty().trim()
            if (legacy.isNotEmpty()) {
                // saveKey commits the encrypted value and removes the legacy field atomically.
                saveKey(context, legacy)
                return legacy
            }
            return ""
        }

        return try {
            require(!ciphertext.isNullOrBlank() && !ivText.isNullOrBlank()) {
                "Saved GEMINI API-key ciphertext is incomplete; existing data was preserved."
            }
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            val encrypted = Base64.decode(ciphertext, Base64.NO_WRAP)
            try {
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                    ?: error("Gemini API-key encryption key is missing; refusing to replace it while encrypted key data still exists.")
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, existingKey, GCMParameterSpec(128, iv))
                val clear = cipher.doFinal(encrypted)
                try {
                    String(clear, Charsets.UTF_8)
                } finally {
                    clear.fill(0)
                }
            } finally {
                iv.fill(0)
                encrypted.fill(0)
            }
        } catch (error: Exception) {
            // Never destroy ciphertext on a read failure: a transient Keystore error or
            // device restore must not silently erase the user's only copy of the key.
            throw IllegalStateException(
                "The saved GEMINI API key could not be decrypted. Re-enter the key if this device's Keystore was reset.",
                error
            )
        }
    }

    @Synchronized
    fun saveKey(context: Context, key: String) {
        if (PasswordProtectedApiKeyVault.isConfigured(context)) {
            PasswordProtectedApiKeyVault.save(context, "GEMINI", key)
            return
        }
        saveLegacyKey(context, key)
    }

    @Synchronized
    internal fun saveLegacyKey(context: Context, key: String) {
        val prefs = getPrefs(context)
        val trimmed = key.trim()
        require(trimmed.length <= 512) { "GEMINI API key is too long." }
        if (trimmed.isEmpty()) {
            check(prefs.edit().remove(ENCRYPTED_KEY).remove(IV_KEY).remove(LEGACY_KEY).commit()) {
                "Could not remove the stored GEMINI API key."
            }
            return
        }

        val clear = trimmed.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
            val encrypted = cipher.doFinal(clear)
            val iv = cipher.iv
            try {
                check(prefs.edit()
                    .putString(ENCRYPTED_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(IV_KEY, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .remove(LEGACY_KEY)
                    .commit()) { "Could not persist the encrypted GEMINI API key." }
            } finally {
                encrypted.fill(0)
                iv.fill(0)
            }
        } finally {
            clear.fill(0)
        }
    }

    fun hasKey(context: Context): Boolean = getKey(context).isNotEmpty()

    internal fun clearLegacyKey(context: Context) = saveLegacyKey(context, "")
}
