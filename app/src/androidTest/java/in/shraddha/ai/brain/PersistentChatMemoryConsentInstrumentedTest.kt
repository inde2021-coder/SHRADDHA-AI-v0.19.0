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
        val vaultPrefs = context.getSharedPreferences("shraddha_secure_vault", Context.MODE_PRIVATE)
        val previousPayload = vaultPrefs.getString("slot_memory", null)
        val previousLegacyPayload = vaultPrefs.getString("memory", null)
        val previousPending = prefs.getBoolean("conversation_memory_clear_pending", false)
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
            if (previousPayload == null) vaultPrefs.edit().remove("slot_memory").commit()
            else vaultPrefs.edit().putString("slot_memory", previousPayload).commit()
            if (previousLegacyPayload == null) vaultPrefs.edit().remove("memory").commit()
            else vaultPrefs.edit().putString("memory", previousLegacyPayload).commit()
            prefs.edit()
                .putBoolean("remember_conversations", previousConsent)
                .putBoolean("conversation_memory_clear_pending", previousPending)
                .commit()
        }
    }

    @Test fun interruptedRevocationTombstonePreventsOldRecapFromReappearing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
        val vault = SecureMemoryVault(context)
        val vaultPrefs = context.getSharedPreferences("shraddha_secure_vault", Context.MODE_PRIVATE)
        val previousConsent = prefs.getBoolean("remember_conversations", false)
        val previousPending = prefs.getBoolean("conversation_memory_clear_pending", false)
        val previousPayload = vaultPrefs.getString("slot_memory", null)
        try {
            assertTrue(vault.save("old private recap").isSuccess)
            assertTrue(prefs.edit()
                .putBoolean("remember_conversations", false)
                .putBoolean("conversation_memory_clear_pending", true)
                .commit())

            assertFalse(PersistentChatMemory.isEnabled(context))
            assertEquals(null, PersistentChatMemory.recap(context))

            PersistentChatMemory.setEnabled(context, true)

            assertTrue(PersistentChatMemory.isEnabled(context))
            assertEquals("", vault.load().getOrThrow())
        } finally {
            if (previousPayload == null) vaultPrefs.edit().remove("slot_memory").commit()
            else vaultPrefs.edit().putString("slot_memory", previousPayload).commit()
            prefs.edit()
                .putBoolean("remember_conversations", previousConsent)
                .putBoolean("conversation_memory_clear_pending", previousPending)
                .commit()
        }
    }

    @Test fun freshOptInDoesNotRestoreRecapFromEarlierDisabledPeriod() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
        val vaultPrefs = context.getSharedPreferences("shraddha_secure_vault", Context.MODE_PRIVATE)
        val vault = SecureMemoryVault(context)
        val previousConsent = prefs.getBoolean("remember_conversations", false)
        val previousPending = prefs.getBoolean("conversation_memory_clear_pending", false)
        val previousPayload = vaultPrefs.getString("slot_memory", null)
        try {
            assertTrue(vault.save("recap from before opt-out").isSuccess)
            assertTrue(prefs.edit()
                .putBoolean("remember_conversations", false)
                .putBoolean("conversation_memory_clear_pending", false)
                .commit())

            PersistentChatMemory.setEnabled(context, true)

            assertTrue(PersistentChatMemory.isEnabled(context))
            assertEquals("", vault.load().getOrThrow())
        } finally {
            if (previousPayload == null) vaultPrefs.edit().remove("slot_memory").commit()
            else vaultPrefs.edit().putString("slot_memory", previousPayload).commit()
            prefs.edit()
                .putBoolean("remember_conversations", previousConsent)
                .putBoolean("conversation_memory_clear_pending", previousPending)
                .commit()
        }
    }

    @Test fun unreadableEncryptedRecapIsNotReplacedByNewTurn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
        val vaultPrefs = context.getSharedPreferences("shraddha_secure_vault", Context.MODE_PRIVATE)
        val previousConsent = prefs.getBoolean("remember_conversations", false)
        val previousPending = prefs.getBoolean("conversation_memory_clear_pending", false)
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
            prefs.edit()
                .putBoolean("remember_conversations", previousConsent)
                .putBoolean("conversation_memory_clear_pending", previousPending)
                .commit()
        }
    }
}
