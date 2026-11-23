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

    @Test fun projectBackupRoundTripsAndRejectsConflictingIdsWithoutChangingVault() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = ProjectPlanStore(context)
        val target = ProjectPlanStore(context)
        val suffix = System.currentTimeMillis()
        val plan = ProjectWorkspace.create("Backup test $suffix", listOf("Design", "Build"))
        val conflicting = ProjectWorkspace.create("Conflict test $suffix", listOf("Keep")).copy(id = plan.id)
        try {
            source.save(plan).getOrThrow()
            val backup = source.exportJson().getOrThrow()
            assertTrue(backup.contains("shraddha-project-backup"))
            target.save(conflicting).getOrThrow()
            val before = target.loadAll().getOrThrow().first { it.id == plan.id }
            assertTrue(target.importJson(backup).isFailure)
            assertEquals(before.title, target.loadAll().getOrThrow().first { it.id == plan.id }.title)
        } finally {
            source.delete(plan.id)
        }
    }

    @Test fun projectBackupImportRestoresValidatedPlan() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = ProjectPlanStore(context)
        val target = ProjectPlanStore(context)
        val plan = ProjectWorkspace.create("Restore test ${System.currentTimeMillis()}", listOf("One", "Two"))
        try {
            source.save(plan).getOrThrow()
            target.importJson(source.exportJson().getOrThrow()).getOrThrow()
            val restored = target.loadAll().getOrThrow().first { it.id == plan.id }
            assertEquals(plan.title, restored.title)
            assertEquals(plan.milestones.map { it.title }, restored.milestones.map { it.title })
        } finally {
            source.delete(plan.id)
            target.delete(plan.id)
        }
    }
}
