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
class PasswordProtectedApiKeyVaultTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetBeforeTest() {
        PasswordProtectedApiKeyVault.resetAndDeleteAllKeys(context)
    }

    @After
    fun resetAfterTest() {
        PasswordProtectedApiKeyVault.resetAndDeleteAllKeys(context)
    }

    @Test
    fun passwordProtectsKeysAndSupportsRotationAndDeletion() {
        val firstPassword = "Shraddha1234".toCharArray()
        val secondPassword = "NewShraddha5678".toCharArray()

        PasswordProtectedApiKeyVault.create(context, firstPassword)
        assertTrue(PasswordProtectedApiKeyVault.isConfigured(context))
        assertTrue(PasswordProtectedApiKeyVault.isUnlocked())

        PasswordProtectedApiKeyVault.save(context, "GEMINI", "test-gemini-secret")
        PasswordProtectedApiKeyVault.save(context, "OPENAI", "test-openai-secret")
        assertEquals("test-gemini-secret", PasswordProtectedApiKeyVault.get(context, "GEMINI"))

        PasswordProtectedApiKeyVault.lock()
        assertFalse(PasswordProtectedApiKeyVault.isUnlocked())
        assertFalse(PasswordProtectedApiKeyVault.unlock(context, "WrongPassword123".toCharArray()))
        assertTrue(PasswordProtectedApiKeyVault.unlock(context, firstPassword))
        assertEquals("test-openai-secret", PasswordProtectedApiKeyVault.get(context, "OPENAI"))

        PasswordProtectedApiKeyVault.changePassword(context, firstPassword, secondPassword)
        PasswordProtectedApiKeyVault.lock()
        assertFalse(PasswordProtectedApiKeyVault.unlock(context, firstPassword))
        assertTrue(PasswordProtectedApiKeyVault.unlock(context, secondPassword))
        assertEquals("test-gemini-secret", PasswordProtectedApiKeyVault.get(context, "GEMINI"))

        PasswordProtectedApiKeyVault.save(context, "GEMINI", "")
        assertEquals("", PasswordProtectedApiKeyVault.get(context, "GEMINI"))
    }

    @Test
    fun lockedVaultRejectsKeyReadsAndWrites() {
        PasswordProtectedApiKeyVault.create(context, "Shraddha1234".toCharArray())
        PasswordProtectedApiKeyVault.lock()

        val readFailure = runCatching { PasswordProtectedApiKeyVault.get(context, "GEMINI") }
        val writeFailure = runCatching { PasswordProtectedApiKeyVault.save(context, "GEMINI", "secret") }

        assertTrue(readFailure.isFailure)
        assertTrue(writeFailure.isFailure)
    }
}
