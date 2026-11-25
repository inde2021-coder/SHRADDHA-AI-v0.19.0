package com.Lia.assistant.data

import android.content.Context
import `in`.shraddha.ai.brain.EncryptedFeatureStore

/** Provider credentials are encrypted with the app's Android Keystore-backed vault. */
object ProviderKeyStore {
    private fun key(provider: String): String {
        val id = provider.uppercase()
        require(id in setOf("OPENAI", "GROQ", "CLAUDE", "PERPLEXITY"))
        return "ai_provider_api_key_$id"
    }

    fun get(context: Context, provider: String): String =
        if (PasswordProtectedApiKeyVault.isConfigured(context)) PasswordProtectedApiKeyVault.get(context, provider)
        else getLegacy(context, provider)

    internal fun getLegacy(context: Context, provider: String): String =
        EncryptedFeatureStore(context.applicationContext).get(key(provider)).getOrThrow().trim()

    fun save(context: Context, provider: String, value: String) {
        if (PasswordProtectedApiKeyVault.isConfigured(context)) {
            PasswordProtectedApiKeyVault.save(context, provider, value)
            return
        }
        saveLegacy(context, provider, value)
    }

    internal fun saveLegacy(context: Context, provider: String, value: String) {
        val clean = value.trim()
        require(clean.length <= 512) { "API key is too long." }
        val store = EncryptedFeatureStore(context.applicationContext)
        if (clean.isEmpty()) {
            check(store.remove(key(provider))) { "Could not remove the encrypted $provider API key." }
        } else {
            store.put(key(provider), clean).getOrThrow()
        }
    }

    fun has(context: Context, provider: String): Boolean = get(context, provider).isNotEmpty()

    internal fun clearLegacy(context: Context, provider: String) = saveLegacy(context, provider, "")
}
