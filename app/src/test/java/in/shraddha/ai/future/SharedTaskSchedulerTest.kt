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
