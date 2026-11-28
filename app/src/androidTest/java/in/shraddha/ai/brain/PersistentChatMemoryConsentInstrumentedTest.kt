package `in`.shraddha.ai.brain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentChatMemoryConsentInstrumentedTest {
    @Test fun revokingConsentSynchronouslyDeletesEncryptedRecap() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
        val previousConsent = prefs.getBoolean("remember_conversations", false)
        val vault = SecureMemoryVault(context)
        try {
            PersistentChatMemory.setEnabled(context, true)
            assertTrue(PersistentChatMemory.record(context, "User", "private recap").isSuccess)
            assertEquals("User: private recap", PersistentChatMemory.recap(context))

            PersistentChatMemory.setEnabled(context, false)

            assertFalse(PersistentChatMemory.isEnabled(context))
            assertEquals("", vault.load().getOrThrow())
            assertEquals(null, PersistentChatMemory.recap(context))
        } finally {
            vault.clear()
            prefs.edit().putBoolean("remember_conversations", previousConsent).commit()
        }
    }

    @Test fun unreadableEncryptedRecapIsNotReplacedByNewTurn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
        val vaultPrefs = context.getSharedPreferences("shraddha_secure_vault", Context.MODE_PRIVATE)
        val previousConsent = prefs.getBoolean("remember_conversations", false)
        val previousPayload = vaultPrefs.getString("slot_memory", null)
        try {
            PersistentChatMemory.setEnabled(context, true)
            assertTrue(vaultPrefs.edit().putString("slot_memory", "corrupt-payload").commit())

            val result = PersistentChatMemory.record(context, "Assistant", "new turn")

            assertTrue(result.isFailure)
            assertEquals("corrupt-payload", vaultPrefs.getString("slot_memory", null))
        } finally {
            if (previousPayload == null) vaultPrefs.edit().remove("slot_memory").commit()
            else vaultPrefs.edit().putString("slot_memory", previousPayload).commit()
            prefs.edit().putBoolean("remember_conversations", previousConsent).commit()
        }
    }
}
