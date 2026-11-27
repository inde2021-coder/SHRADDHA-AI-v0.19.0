package `in`.shraddha.ai.future

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WorkflowEngineTest {
    private val engine = WorkflowEngine()

    private fun snapshot(
        steps: List<WorkflowStep> = listOf(
            WorkflowStep("read", "Read details", Risk.READ_ONLY),
            WorkflowStep("send", "Send message", Risk.SENSITIVE)
        )
    ) = WorkflowSnapshot("wf-1", "Test workflow", WorkflowStatus.DRAFT, 0, steps, "")

    @Test fun readOnlyStepStartsWithoutApprovalAndSensitiveStepWaits() {
        val running = engine.start(snapshot())
        assertEquals(WorkflowStatus.RUNNING, running.status)
        val verifying = engine.markExecuted(running)
        val next = engine.markVerified(verifying, true)
        assertEquals(1, next.currentStep)
        assertEquals(WorkflowStatus.WAITING_FOR_APPROVAL, next.status)
    }

    @Test fun declinedApprovalPausesAndResumeRequiresFreshApproval() {
        val sensitive = snapshot(listOf(WorkflowStep("delete", "Delete data", Risk.IRREVERSIBLE)))
        val waiting = engine.start(sensitive)
        val paused = engine.approve(waiting, false)
        assertEquals(WorkflowStatus.PAUSED, paused.status)
        val resumed = engine.resume(paused)
        assertEquals(WorkflowStatus.WAITING_FOR_APPROVAL, resumed.status)
    }

    @Test fun failedVerificationStopsWorkflow() {
        val running = engine.start(snapshot())
        val failed = engine.markVerified(engine.markExecuted(running), false)
        assertEquals(WorkflowStatus.FAILED, failed.status)
        assertTrue(failed.message.contains("Verification failed"))
    }

    @Test fun cancellationIsTerminal() {
        val cancelled = engine.cancel(engine.start(snapshot()))
        assertEquals(WorkflowStatus.CANCELLED, cancelled.status)
        try {
            engine.resume(cancelled)
            fail("Cancelled workflow must not resume")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun invalidStepIndexAndDuplicateIdsAreRejected() {
        try {
            engine.start(snapshot().copy(currentStep = 8))
            fail("Invalid current step must be rejected")
        } catch (_: IllegalArgumentException) { }

        try {
            engine.start(snapshot(listOf(
                WorkflowStep("same", "First", Risk.READ_ONLY),
                WorkflowStep("same", "Second", Risk.READ_ONLY)
            )))
            fail("Duplicate step IDs must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun approvalCannotBeAppliedToUnrelatedState() {
        try {
            engine.approve(engine.start(snapshot()), true)
            fail("Approval must only be accepted when requested")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsOversizedWorkflowAndInvalidIdentifiers() {
        val tooMany = (0..256).map { WorkflowStep("step_$it", "Step $it", Risk.READ_ONLY) }
        try {
            engine.start(snapshot(tooMany))
            fail("More than 256 steps must be rejected")
        } catch (_: IllegalArgumentException) { }

        try {
            engine.start(snapshot(listOf(WorkflowStep("bad id", "Step", Risk.READ_ONLY))))
            fail("Whitespace in step ID must be rejected")
        } catch (_: IllegalArgumentException) { }

        try {
            engine.start(snapshot().copy(id = "bad/id"))
            fail("Invalid workflow ID must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsOversizedTitlesAndMessages() {
        try {
            engine.start(snapshot().copy(title = "x".repeat(121)))
            fail("Oversized workflow title must be rejected")
        } catch (_: IllegalArgumentException) { }

        try {
            engine.start(snapshot().copy(message = "x".repeat(241)))
            fail("Oversized workflow message must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
