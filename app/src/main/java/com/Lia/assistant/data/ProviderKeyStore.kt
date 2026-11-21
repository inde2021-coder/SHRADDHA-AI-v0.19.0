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
        EncryptedFeatureStore(context.applicationContext).get(key(provider)).getOrDefault("").trim()

    fun save(context: Context, provider: String, value: String) {
        val clean = value.trim()
        require(clean.length <= 512) { "API key is too long." }
        val store = EncryptedFeatureStore(context.applicationContext)
        if (clean.isEmpty()) store.remove(key(provider))
        else store.put(key(provider), clean).getOrThrow()
    }

    fun has(context: Context, provider: String): Boolean = get(context, provider).isNotEmpty()
}
