package com.Lia.assistant.data

import android.content.Context
import android.content.SharedPreferences
import `in`.shraddha.ai.brain.EncryptedFeatureStore

/**
 * Stores provider API keys as plain text in app-private preferences, as explicitly
 * requested. Values are not encrypted at rest. The separate feature store remains
 * encrypted for unrelated profile/person data.
 */
object ProviderKeyStore {
    private const val PREFS = "shraddha_api_keys_plaintext"

    private fun key(provider: String): String {
        val id = provider.uppercase()
        require(id in setOf("OPENAI", "GROQ", "CLAUDE", "PERPLEXITY"))
        return "api_key_$id"
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun removeOldEncryptedCopy(context: Context, provider: String) {
        val removed = EncryptedFeatureStore(context.applicationContext).remove("ai_provider_api_key_${provider.uppercase()}")
        check(removed) { "Could not clean up the old encrypted $provider API-key entry." }
    }

    @Synchronized
    fun get(context: Context, provider: String): String =
        prefs(context).getString(key(provider), "").orEmpty().trim()

    @Synchronized
    fun save(context: Context, provider: String, value: String) {
        val clean = value.trim()
        require(clean.length <= 512) { "API key is too long." }
        val editor = prefs(context).edit()
        if (clean.isEmpty()) editor.remove(key(provider)) else editor.putString(key(provider), clean)
        check(editor.commit()) {
            if (clean.isEmpty()) "Could not remove the $provider API key."
            else "Could not save the $provider API key."
        }
        removeOldEncryptedCopy(context, provider)
    }

    fun has(context: Context, provider: String): Boolean = get(context, provider).isNotEmpty()

    internal fun getLegacy(context: Context, provider: String): String = get(context, provider)
    internal fun saveLegacy(context: Context, provider: String, value: String) = save(context, provider, value)
    internal fun clearLegacy(context: Context, provider: String) = save(context, provider, "")
}
