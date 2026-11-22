package `in`.shraddha.ai.future

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectPlanStoreInstrumentedTest {
    @Test fun projectPlansAndMilestonesPersistEncryptedAndCanBeDeleted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ProjectPlanStore(context)
        val rawStore = EncryptedFeatureStore(context)
        val title = "Private Project Lab test ${System.currentTimeMillis()}"
        val plan = ProjectWorkspace.create(title, listOf("Research", "Build", "Review"))
        try {
            val saved = store.save(plan).getOrThrow()
            assertTrue(saved.any { it.id == plan.id })
            assertEquals(0, ProjectWorkspace.progress(saved.first { it.id == plan.id }))

            val updated = ProjectWorkspace.update(
                plan,
                milestoneId = "m1",
                state = MilestoneState.DONE,
                note = "User marked this milestone complete."
            )
            store.save(updated).getOrThrow()
            val loaded = store.loadAll().getOrThrow().first { it.id == plan.id }
            assertEquals(33, ProjectWorkspace.progress(loaded))
            assertEquals(MilestoneState.DONE, loaded.milestones.first().state)

            val rawValues = rawStore.getAllWithPrefixes(setOf("project_plan_")).getOrThrow().values
            assertTrue(rawValues.isNotEmpty())
            assertFalse(rawValues.any { it.contains(title) })

            assertTrue(store.delete(plan.id).getOrThrow().none { it.id == plan.id })
        } finally {
            store.delete(plan.id)
        }
    }
}
