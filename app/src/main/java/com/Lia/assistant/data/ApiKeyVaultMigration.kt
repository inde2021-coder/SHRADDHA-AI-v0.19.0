package com.Lia.assistant.data

import android.content.Context

/**
 * Safely migrates provider keys between the optional password vault and the
 * app's normal Android Keystore-backed stores.
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

    /**
     * Removes the password layer without losing credentials. The vault must be
     * unlocked once so every key can be decrypted and copied back to the
     * Android Keystore-backed stores. Old vault data is cleared only after
     * every non-empty key has been written and verified.
     */
    @Synchronized
    fun disable(context: Context) {
        require(PasswordProtectedApiKeyVault.isConfigured(context)) { "Password protection is not enabled." }
        require(PasswordProtectedApiKeyVault.isUnlocked()) {
            "Unlock the existing vault once to safely remove its password protection."
        }

        val values = linkedMapOf<String, String>()
        values["GEMINI"] = PasswordProtectedApiKeyVault.get(context, "GEMINI")
        providers.forEach { provider ->
            values[provider] = PasswordProtectedApiKeyVault.get(context, provider)
        }

        // Write and verify all replacement copies before touching the old vault.
        ApiKeyStore.saveLegacyKey(context, values.getValue("GEMINI"))
        providers.forEach { provider ->
            ProviderKeyStore.saveLegacy(context, provider, values.getValue(provider))
        }

        check(ApiKeyStore.getLegacyKey(context) == values.getValue("GEMINI")) {
            "Could not verify the restored GEMINI API key. Password protection was kept."
        }
        providers.forEach { provider ->
            check(ProviderKeyStore.getLegacy(context, provider) == values.getValue(provider)) {
                "Could not verify the restored $provider API key. Password protection was kept."
            }
        }

        // This is now safe: verified Android Keystore copies exist for every provider.
        PasswordProtectedApiKeyVault.resetAndDeleteAllKeys(context)
    }

    @Synchronized
    fun resetAndDeleteAll(context: Context) {
        PasswordProtectedApiKeyVault.resetAndDeleteAllKeys(context)
        ApiKeyStore.clearLegacyKey(context)
        providers.forEach { ProviderKeyStore.clearLegacy(context, it) }
    }
}
