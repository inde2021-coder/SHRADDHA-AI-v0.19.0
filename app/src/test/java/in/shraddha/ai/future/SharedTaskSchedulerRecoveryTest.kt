package `in`.shraddha.ai.future

import org.junit.Assert.*
import org.junit.Test

class SharedTaskSchedulerRecoveryTest {
    @Test fun interruptedBackgroundCouncilCanBeExplicitlyRequeued() {
        val task = SharedTaskScheduler.Task(
            id = "bg_retry_1",
            title = "Background council",
            payload = """{"kind":"background_council"}"""
        )
        val original = SharedTaskScheduler(1)
        original.submitAll(listOf(task))
        original.claimSpecific(task.id)
        val persisted = original.snapshot()

        val restored = SharedTaskScheduler(1)
        restored.restore(persisted)
        assertEquals(SharedTaskScheduler.State.FAILED, restored.get(task.id)?.state)

        val requeued = restored.requeueInterrupted(task.id) {
            it.payload.contains("\"kind\":\"background_council\"")
        }
        assertEquals(SharedTaskScheduler.State.PENDING, requeued?.state)
        assertEquals(task.id, restored.claimSpecific(task.id)?.task?.id)
    }

    @Test fun interruptedDeviceActionCannotBeRequeuedByBackgroundAdapter() {
        val task = SharedTaskScheduler.Task(
            id = "voice_action_1",
            title = "Send message",
            payload = """{"kind":"voice_device_action"}"""
        )
        val original = SharedTaskScheduler(1)
        original.submitAll(listOf(task))
        original.claimSpecific(task.id)

        val restored = SharedTaskScheduler(1)
        restored.restore(original.snapshot())
        val requeued = restored.requeueInterrupted(task.id) {
            it.payload.contains("\"kind\":\"background_council\"")
        }

        assertNull(requeued)
        assertEquals(SharedTaskScheduler.State.FAILED, restored.get(task.id)?.state)
        assertNull(restored.claimSpecific(task.id))
    }
}
