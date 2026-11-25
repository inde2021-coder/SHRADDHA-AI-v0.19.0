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
            PasswordProtectedApiKeyVault.saveAll(context, existing)
            existing.forEach { (provider, value) ->
                if (value.isNotBlank()) {
                    check(PasswordProtectedApiKeyVault.get(context, provider) == value) {
                        "Could not verify the migrated $provider API key."
                    }
                }
            }
        } catch (error: Exception) {
            PasswordProtectedApiKeyVault.resetAndDeleteAllKeys(context)
            throw IllegalStateException("Could not migrate existing provider keys. Your previous encrypted keys were left unchanged.", error)
        }

        // Remove old encrypted copies only after all new records are committed and verified.
        try {
            ApiKeyStore.clearLegacyKey(context)
            providers.forEach { ProviderKeyStore.clearLegacy(context, it) }
        } catch (error: Exception) {
            throw IllegalStateException(
                "Vault is enabled and verified, but an old encrypted copy could not be cleaned up. Keep this password and contact support before resetting.",
                error
            )
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
