package com.Lia.assistant.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiKeyStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetBeforeTest() {
        ApiKeyVaultMigration.resetAndDeleteAll(context)
        context.getSharedPreferences("shraddha_api_keys_plaintext", 0).edit().clear().commit()
    }

    @After
    fun resetAfterTest() {
        ApiKeyVaultMigration.resetAndDeleteAll(context)
        context.getSharedPreferences("shraddha_api_keys_plaintext", 0).edit().clear().commit()
    }

    @Test
    fun geminiKeyCanBeSavedReadAndRemovedWithoutVaultOrEncryption() {
        ApiKeyStore.saveKey(context, "test-gemini-secret")
        assertEquals("test-gemini-secret", ApiKeyStore.getKey(context))
        assertEquals(
            "test-gemini-secret",
            context.getSharedPreferences("nova_prefs", 0).getString("gemini_api_key_plaintext", null)
        )
        assertFalse(PasswordProtectedApiKeyVault.isConfigured(context))

        ApiKeyStore.saveKey(context, "")
        assertEquals("", ApiKeyStore.getKey(context))
        assertFalse(ApiKeyStore.hasKey(context))
    }

    @Test
    fun providerKeysCanBeSavedReadAndRemovedWithoutVaultOrEncryption() {
        ProviderKeyStore.save(context, "OPENAI", "test-openai-secret")
        ProviderKeyStore.save(context, "GROQ", "test-groq-secret")

        assertEquals("test-openai-secret", ProviderKeyStore.get(context, "OPENAI"))
        assertEquals("test-groq-secret", ProviderKeyStore.get(context, "GROQ"))
        assertEquals(
            "test-openai-secret",
            context.getSharedPreferences("shraddha_api_keys_plaintext", 0).getString("api_key_OPENAI", null)
        )
        assertFalse(PasswordProtectedApiKeyVault.isConfigured(context))

        ProviderKeyStore.save(context, "OPENAI", "")
        assertEquals("", ProviderKeyStore.get(context, "OPENAI"))
        assertFalse(ProviderKeyStore.has(context, "OPENAI"))
    }
}
