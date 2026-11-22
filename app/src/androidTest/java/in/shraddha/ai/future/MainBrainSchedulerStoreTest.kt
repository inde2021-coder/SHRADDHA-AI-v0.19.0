package `in`.shraddha.ai.future

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainBrainSchedulerStoreTest {
    @Test
    fun encryptedSnapshotSurvivesStoreRecreationAndFailsClosedForRunningWork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = "instrumentation_${System.nanoTime()}"
        val source = listOf(
            SharedTaskScheduler.Snapshot(
                task = SharedTaskScheduler.Task(id, "Pending durable task", payload = "typed-payload"),
                state = SharedTaskScheduler.State.PENDING,
                submittedOrder = 1L
            ),
            SharedTaskScheduler.Snapshot(
                task = SharedTaskScheduler.Task("${id}_running", "Interrupted task"),
                state = SharedTaskScheduler.State.RUNNING,
                submittedOrder = 2L
            )
        )
        val firstStore = MainBrainSchedulerStore(context)
        firstStore.save(source)
        val restored = MainBrainSchedulerStore(context).load()

        assertEquals(2, restored.size)
        assertEquals(SharedTaskScheduler.State.PENDING, restored[0].state)
        assertEquals("typed-payload", restored[0].task.payload)
        assertEquals(SharedTaskScheduler.State.FAILED, restored[1].state)
        assertTrue(restored[1].error.orEmpty().contains("not replayed"))
    }
}
