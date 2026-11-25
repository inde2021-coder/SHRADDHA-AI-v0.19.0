package com.Lia.assistant.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores the Gemini API key as plain text in this app's private preferences,
 * as explicitly requested. Android app sandbox protection still applies, but
 * the value is not encrypted at rest by this store.
 */
object ApiKeyStore {
    private const val PREF_NAME = "nova_prefs"
    private const val PLAIN_KEY = "gemini_api_key_plaintext"
    private const val LEGACY_KEY = "gemini_api_key"
    private const val ENCRYPTED_KEY = "gemini_api_key_ciphertext"
    private const val IV_KEY = "gemini_api_key_iv"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getKey(context: Context): String {
        val store = prefs(context)
        return store.getString(PLAIN_KEY, null)?.trim().orEmpty()
    }

    @Synchronized
    fun saveKey(context: Context, key: String) {
        val clean = key.trim()
        require(clean.length <= 512) { "GEMINI API key is too long." }
        val editor = prefs(context).edit()
            .remove(LEGACY_KEY)
            .remove(ENCRYPTED_KEY)
            .remove(IV_KEY)
        if (clean.isEmpty()) editor.remove(PLAIN_KEY) else editor.putString(PLAIN_KEY, clean)
        check(editor.commit()) {
            if (clean.isEmpty()) "Could not remove the stored GEMINI API key."
            else "Could not save the GEMINI API key."
        }
    }

    fun hasKey(context: Context): Boolean = getKey(context).isNotEmpty()

    internal fun getLegacyKey(context: Context): String = getKey(context)
    internal fun saveLegacyKey(context: Context, key: String) = saveKey(context, key)
    internal fun clearLegacyKey(context: Context) = saveKey(context, "")
}
