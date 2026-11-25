package com.Lia.assistant.data

import android.content.Context

/**
 * Moves existing encrypted provider entries into the password-derived vault.
 * The old Android Keystore-backed copies are removed only after every value is
 * successfully encrypted and verified in the new vault.
 */
object ApiKeyVaultMigration {
    private val providers = listOf("OPENAI", "GROQ", "CLAUDE", "PERPLEXITY")

    @Synchronized
    fun enable(context: Context, password: CharArray) {
        require(!PasswordProtectedApiKeyVault.isConfigured(context)) { "Password protection is already enabled." }
        val existing = linkedMapOf<String, String>()
        existing["GEMINI"] = ApiKeyStore.getLegacyKey(context)
        providers.forEach { provider -> existing[provider] = ProviderKeyStore.getLegacy(context, provider) }

        PasswordProtectedApiKeyVault.create(context, password)
        try {
            existing.forEach { (provider, value) ->
                if (value.isNotBlank()) PasswordProtectedApiKeyVault.save(context, provider, value)
            }
            existing.forEach { (provider, value) ->
                if (value.isNotBlank()) {
                    check(PasswordProtectedApiKeyVault.get(context, provider) == value) {
                        "Could not verify the migrated $provider API key."
                    }
                }
            }
            // Remove old encrypted copies after the new vault has been verified.
            ApiKeyStore.clearLegacyKey(context)
            providers.forEach { ProviderKeyStore.clearLegacy(context, it) }
        } catch (error: Exception) {
            // Keep the newly-created vault intact if legacy cleanup failed: the new
            // verified copy is the authoritative copy and remains protected.
            throw IllegalStateException(
                "Vault created, but migration cleanup needs attention. Keep this password and do not reset the vault.",
                error
            )
        } finally {
            existing.keys.forEach { /* String values cannot be zeroed; references are released on return. */ }
        }
    }

    @Synchronized
    fun resetAndDeleteAll(context: Context) {
        PasswordProtectedApiKeyVault.resetAndDeleteAllKeys(context)
        // Remove any pre-migration leftovers as part of an explicit destructive reset.
        ApiKeyStore.clearLegacyKey(context)
        providers.forEach { ProviderKeyStore.clearLegacy(context, it) }
    }
}
