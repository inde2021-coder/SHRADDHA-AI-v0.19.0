package `in`.shraddha.ai.brain

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedFeatureStoreInstrumentedTest {
    @Test fun relatedEncryptedWritesAndDeletesAreAtomic() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = EncryptedFeatureStore(context)
        val first = "test_atomic_first"
        val second = "test_atomic_second"
        val third = "test_atomic_third"
        try {
            assertTrue(store.put(first, "old-first").isSuccess)
            assertTrue(store.put(second, "old-second").isSuccess)

            // The invalid second key makes the complete batch fail before commit.
            val rejected = store.putAll(linkedMapOf(first to "new-first", "invalid key" to "never-saved"))
            assertTrue(rejected.isFailure)
            assertEquals("old-first", store.get(first).getOrThrow())
            assertEquals("old-second", store.get(second).getOrThrow())

            assertTrue(store.putAllAndRemove(
                mapOf(first to "new-first", third to "new-third"),
                setOf(second)
            ).isSuccess)
            assertEquals("new-first", store.get(first).getOrThrow())
            assertEquals("", store.get(second).getOrThrow())
            assertEquals("new-third", store.get(third).getOrThrow())
        } finally {
            store.remove(first)
            store.remove(second)
            store.remove(third)
        }
    }
}
