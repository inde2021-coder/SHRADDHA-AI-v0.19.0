package com.Lia.assistant.data

import android.content.Context
import `in`.shraddha.ai.brain.EncryptedFeatureStore

/**
 * API credentials are encrypted at rest with an Android Keystore AES-GCM key.
 * The previous SharedPreferences value is migrated once and removed only after
 * the encrypted write has succeeded.
 */
object ApiKeyStore {
    private const val PREF_NAME = "nova_prefs"
    private const val KEY_GEMINI_API_KEY = "gemini_api_key"

    @Synchronized
    fun getKey(context: Context): String {
        val secureStore = EncryptedFeatureStore(context.applicationContext)
        val secureValue = secureStore.get(KEY_GEMINI_API_KEY).getOrDefault("").trim()
        if (secureValue.isNotEmpty()) return secureValue

        val legacyPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val legacyValue = legacyPrefs.getString(KEY_GEMINI_API_KEY, "").orEmpty().trim()
        if (legacyValue.isNotEmpty()) {
            // Fail closed: retain the old value only if migration cannot be persisted.
            secureStore.put(KEY_GEMINI_API_KEY, legacyValue).getOrThrow()
            check(legacyPrefs.edit().remove(KEY_GEMINI_API_KEY).commit()) {
                "Encrypted API key was saved but the legacy preference could not be cleared."
            }
        }
        return legacyValue
    }

    @Synchronized
    fun saveKey(context: Context, key: String) {
        val trimmed = key.trim()
        require(trimmed.length <= 512) { "API key is too long." }
        val secureStore = EncryptedFeatureStore(context.applicationContext)
        if (trimmed.isEmpty()) {
            secureStore.remove(KEY_GEMINI_API_KEY)
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_GEMINI_API_KEY).apply()
        } else {
            secureStore.put(KEY_GEMINI_API_KEY, trimmed).getOrThrow()
            // Remove any older plaintext copy after the encrypted write succeeds.
            check(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_GEMINI_API_KEY).commit()) {
                "Could not clear the previous plaintext API key."
            }
        }
    }

    fun hasKey(context: Context): Boolean = getKey(context).isNotEmpty()
}
