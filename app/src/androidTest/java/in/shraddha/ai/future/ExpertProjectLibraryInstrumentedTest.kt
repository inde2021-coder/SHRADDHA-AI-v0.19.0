package `in`.shraddha.ai.future

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpertProjectLibraryInstrumentedTest {
    @Test fun migratesLegacyPlaintextProjectsToEncryptedStoreAndClearsOldPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacy = context.getSharedPreferences("shraddha_expert_library", Context.MODE_PRIVATE)
        val store = EncryptedFeatureStore(context)
        try {
            store.remove("expert_library_migrated_v1")
            store.remove("expert_project_library")
            val old = JSONObject()
                .put("title", "Old electronics course")
                .put("content", "Legacy generated lesson")
                .put("savedAt", 1_777_000_000_000L)
                .toString()
            legacy.edit().putStringSet("items", setOf(old)).commit()

            val entries = ExpertProjectLibrary(context).load().getOrThrow()
            assertEquals(1, entries.size)
            assertEquals("Old electronics course", entries.single().title)
            assertEquals("Legacy generated lesson", entries.single().content)
            assertTrue(legacy.all.isEmpty())
            assertEquals(1, JSONArraySize(store.get("expert_project_library").getOrThrow()))
        } finally {
            legacy.edit().clear().commit()
            store.remove("expert_library_migrated_v1")
            store.remove("expert_project_library")
        }
    }

    @Test fun corruptLegacyProjectDataFailsClosedWithoutDeletingOriginal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacy = context.getSharedPreferences("shraddha_expert_library", Context.MODE_PRIVATE)
        val store = EncryptedFeatureStore(context)
        try {
            store.remove("expert_library_migrated_v1")
            store.remove("expert_project_library")
            legacy.edit().putStringSet("items", setOf("not-json")).commit()
            assertTrue(ExpertProjectLibrary(context).load().isFailure)
            assertTrue(legacy.all.isNotEmpty())
        } finally {
            legacy.edit().clear().commit()
            store.remove("expert_library_migrated_v1")
            store.remove("expert_project_library")
        }
    }

    private fun JSONArraySize(raw: String): Int = org.json.JSONArray(raw).length()
}
