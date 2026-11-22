package `in`.shraddha.ai.future

import `in`.shraddha.ai.operator.OperatorActionType
import `in`.shraddha.ai.operator.OperatorStep
import `in`.shraddha.ai.operator.PlannedOperatorAction
import org.junit.Assert.*
import org.junit.Test

class MainBrainTest {
    @Test fun safeWorkflowUsesSharedQueueAndVerifiedOperatorBridge() {
        var executions = 0
        val brain = MainBrain(executeVerified = { step, confirmed ->
            executions++
            assertEquals(OperatorActionType.BACK, step.action)
            assertFalse(confirmed)
            true
        })
        brain.enqueueOperatorWorkflow(MainBrain.WorkflowRequest(
            id = "safe-1",
            title = "Go back",
            actions = listOf(PlannedOperatorAction("back", "Go back", OperatorStep(OperatorActionType.BACK)))
        ))

        val result = brain.startNextWorkflow()
        assertTrue(result is MainBrainExecutionResult.Started)
        val started = result as MainBrainExecutionResult.Started
        assertEquals(WorkflowStatus.COMPLETED, started.session.workflow.status)
        assertEquals(1, executions)
        assertEquals(SharedTaskScheduler.State.SUCCEEDED, brain.queueSnapshot().single().state)
        assertNull(brain.activeWorkflowId())
    }

    @Test fun sensitiveWorkflowWaitsForExplicitStepApproval() {
        var executions = 0
        val brain = MainBrain(executeVerified = { _, confirmed ->
            executions++
            assertTrue(confirmed)
            true
        })
        brain.enqueueOperatorWorkflow(MainBrain.WorkflowRequest(
            id = "sensitive-1",
            title = "Click submit",
            actions = listOf(PlannedOperatorAction(
                "submit", "Submit form",
                OperatorStep(OperatorActionType.CLICK_TEXT, target = "Submit", requiresConfirmation = true),
                risk = Risk.SENSITIVE,
                requiresApproval = true
            ))
        ))

        val start = brain.startNextWorkflow() as MainBrainExecutionResult.Started
        assertEquals(WorkflowStatus.WAITING_FOR_APPROVAL, start.session.workflow.status)
        assertEquals(0, executions)
        val approved = brain.approveCurrentStep(true) as MainBrainExecutionResult.ApprovalUpdated
        assertEquals(WorkflowStatus.COMPLETED, approved.session.workflow.status)
        assertEquals(1, executions)
        assertEquals(SharedTaskScheduler.State.SUCCEEDED, brain.queueSnapshot().single().state)
    }

    @Test fun decliningApprovalCancelsWorkflowWithoutDispatch() {
        var executions = 0
        val brain = MainBrain(executeVerified = { _, _ -> executions++; true })
        brain.enqueueOperatorWorkflow(MainBrain.WorkflowRequest(
            id = "decline-1",
            title = "Click submit",
            actions = listOf(PlannedOperatorAction(
                "submit", "Submit form",
                OperatorStep(OperatorActionType.CLICK_TEXT, target = "Submit", requiresConfirmation = true),
                risk = Risk.SENSITIVE,
                requiresApproval = true
            ))
        ))
        brain.startNextWorkflow()
        brain.approveCurrentStep(false)
        assertEquals(0, executions)
        assertEquals(SharedTaskScheduler.State.CANCELLED, brain.queueSnapshot().single().state)
        assertNull(brain.activeWorkflowId())
    }

    @Test fun blockedSecretNeverEntersTheScheduler() {
        val brain = MainBrain(executeVerified = { _, _ -> fail("Blocked action must never execute"); false })
        try {
            brain.enqueueOperatorWorkflow(MainBrain.WorkflowRequest(
                id = "secret-1",
                title = "Enter OTP",
                actions = listOf(PlannedOperatorAction(
                    "otp", "Enter OTP",
                    OperatorStep(OperatorActionType.TYPE_TEXT, value = "OTP 123456"),
                    risk = Risk.SENSITIVE,
                    requiresApproval = true
                ))
            ))
            fail("Secret entry must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertTrue(brain.queueSnapshot().isEmpty())
    }

    @Test fun dependentWorkflowWaitsUntilItsParentCompletes() {
        val brain = MainBrain(executeVerified = { _, _ -> true })
        brain.enqueueOperatorWorkflow(MainBrain.WorkflowRequest(
            id = "parent", title = "Parent",
            actions = listOf(PlannedOperatorAction("back1", "Back", OperatorStep(OperatorActionType.BACK)))
        ))
        brain.enqueueOperatorWorkflow(MainBrain.WorkflowRequest(
            id = "child", title = "Child", priority = 1, dependsOn = setOf("parent"),
            actions = listOf(PlannedOperatorAction("back2", "Back", OperatorStep(OperatorActionType.BACK)))
        ))
        val first = brain.startNextWorkflow() as MainBrainExecutionResult.Started
        assertEquals("parent", first.workflowId)
        assertEquals(SharedTaskScheduler.State.PENDING, brain.queueSnapshot().first { it.task.id == "child" }.state)
        val second = brain.startNextWorkflow() as MainBrainExecutionResult.Started
        assertEquals("child", second.workflowId)
    }
}
