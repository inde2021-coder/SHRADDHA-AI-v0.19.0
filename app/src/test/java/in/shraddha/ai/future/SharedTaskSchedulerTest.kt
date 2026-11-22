package `in`.shraddha.ai.future

import org.junit.Assert.*
import org.junit.Test

class SharedTaskSchedulerTest {
    @Test fun singleTaskUsesTheSameQueueAndMustBeClaimedBeforeCompletion() {
        val scheduler = SharedTaskScheduler()
        scheduler.submitAll(listOf(SharedTaskScheduler.Task("one", "Single task")))
        assertEquals(SharedTaskScheduler.State.PENDING, scheduler.get("one")?.state)
        assertEquals(listOf("one"), scheduler.claimReady().map { it.task.id })
        assertEquals(SharedTaskScheduler.State.SUCCEEDED, scheduler.complete("one").state)
    }

    @Test fun batchHonorsDependenciesAndPriority() {
        val scheduler = SharedTaskScheduler(maxParallel = 2)
        scheduler.submitAll(listOf(
            SharedTaskScheduler.Task("low", "Low priority", priority = 5),
            SharedTaskScheduler.Task("high", "High priority", priority = 1),
            SharedTaskScheduler.Task("child", "Depends on high", dependsOn = setOf("high"), priority = 1)
        ))
        val first = scheduler.claimReady()
        assertEquals(listOf("high", "low"), first.map { it.task.id })
        scheduler.complete("high")
        scheduler.complete("low")
        assertEquals(listOf("child"), scheduler.claimReady().map { it.task.id })
    }

    @Test fun concurrencyLimitIsEnforcedAcrossClaims() {
        val scheduler = SharedTaskScheduler(maxParallel = 2)
        scheduler.submitAll((1..5).map { SharedTaskScheduler.Task("t$it", "Task $it") })
        assertEquals(2, scheduler.claimReady().size)
        assertEquals(0, scheduler.availableSlots())
        assertTrue(scheduler.claimReady().isEmpty())
        scheduler.complete("t1")
        assertEquals(1, scheduler.claimReady().size)
    }

    @Test fun failedDependencyNeverReleasesItsChild() {
        val scheduler = SharedTaskScheduler()
        scheduler.submitAll(listOf(
            SharedTaskScheduler.Task("parent", "Parent"),
            SharedTaskScheduler.Task("child", "Child", dependsOn = setOf("parent"))
        ))
        scheduler.claimReady()
        scheduler.fail("parent", "worker crashed")
        assertTrue(scheduler.claimReady().isEmpty())
        assertEquals(SharedTaskScheduler.State.PENDING, scheduler.get("child")?.state)
    }

    @Test fun batchRejectsCyclesWithoutPartiallySubmittingTasks() {
        val scheduler = SharedTaskScheduler()
        try {
            scheduler.submitAll(listOf(
                SharedTaskScheduler.Task("a", "A", dependsOn = setOf("b")),
                SharedTaskScheduler.Task("b", "B", dependsOn = setOf("a"))
            ))
            fail("Cycle must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertTrue(scheduler.snapshot().isEmpty())
    }

    @Test fun batchRejectsUnknownDependenciesAndDuplicateIds() {
        val scheduler = SharedTaskScheduler()
        try {
            scheduler.submitAll(listOf(SharedTaskScheduler.Task("a", "A", dependsOn = setOf("missing"))))
            fail("Unknown dependency must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            scheduler.submitAll(listOf(
                SharedTaskScheduler.Task("b", "B"),
                SharedTaskScheduler.Task("b", "Duplicate")
            ))
            fail("Duplicate IDs must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertTrue(scheduler.snapshot().isEmpty())
    }

    @Test fun taskSpecificClaimCannotStealAnotherAdaptersTask() {
        val scheduler = SharedTaskScheduler(maxParallel = 1)
        scheduler.submitAll(listOf(
            SharedTaskScheduler.Task("operator_1", "Operator"),
            SharedTaskScheduler.Task("bg_1", "Background")
        ))
        assertNull(scheduler.claimSpecific("missing"))
        assertEquals("bg_1", scheduler.claimSpecific("bg_1")?.task?.id)
        assertNull(scheduler.claimSpecific("operator_1"))
        scheduler.complete("bg_1")
        assertEquals("operator_1", scheduler.claimReadyMatching { it.task.id.startsWith("operator_") }.single().task.id)
    }

    @Test fun schedulerChangeCallbackReceivesDurableTransitions() {
        val updates = mutableListOf<List<SharedTaskScheduler.Snapshot>>()
        val scheduler = SharedTaskScheduler(onChanged = { updates += it })
        scheduler.submitAll(listOf(SharedTaskScheduler.Task("persisted", "Persist me")))
        scheduler.claimReady()
        scheduler.complete("persisted")
        assertEquals(3, updates.size)
        assertEquals(SharedTaskScheduler.State.SUCCEEDED, updates.last().single().state)
    }

    @Test fun restoreResumesOnlyUnclaimedPendingWorkAndNeverReplaysRunningWork() {
        val source = SharedTaskScheduler()
        source.submitAll(listOf(
            SharedTaskScheduler.Task("pending", "Safe queued task", payload = "typed-plan"),
            SharedTaskScheduler.Task("running", "Interrupted task")
        ))
        source.claimReady()
        val saved = source.snapshot()
        val restored = SharedTaskScheduler()
        restored.restore(saved)

        assertEquals(SharedTaskScheduler.State.PENDING, restored.get("pending")?.state)
        assertEquals("typed-plan", restored.get("pending")?.task?.payload)
        assertEquals(SharedTaskScheduler.State.FAILED, restored.get("running")?.state)
        assertTrue(restored.get("running")?.error?.contains("not replayed") == true)
        assertEquals(listOf("pending"), restored.claimReady().map { it.task.id })
    }

    @Test fun onlyRunningTasksCanBeCompletedAndCancellationIsExplicit() {
        val scheduler = SharedTaskScheduler()
        scheduler.submitAll(listOf(SharedTaskScheduler.Task("a", "A")))
        try {
            scheduler.complete("a")
            fail("Pending task cannot complete")
        } catch (_: IllegalArgumentException) { }
        assertEquals(SharedTaskScheduler.State.CANCELLED, scheduler.cancel("a").state)
        assertTrue(scheduler.claimReady().isEmpty())
    }
}
