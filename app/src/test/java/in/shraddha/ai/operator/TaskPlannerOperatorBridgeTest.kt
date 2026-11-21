package `in`.shraddha.ai.operator

import `in`.shraddha.ai.future.Risk
import `in`.shraddha.ai.future.WorkflowStatus
import org.junit.Assert.*
import org.junit.Test

class TaskPlannerOperatorBridgeTest {
    @Test fun plannerDispatchesOnlyAfterStartAndVerifiesInDependencyOrder() {
        val executed = mutableListOf<String>()
        val bridge = TaskPlannerOperatorBridge(executeVerified = { step, _ ->
            executed += step.target
            true
        })
        bridge.createPlan("bridge-1", "Open and inspect", listOf(
            PlannedOperatorAction("open", "Open settings", OperatorStep(OperatorActionType.OPEN_APP, target = "Settings")),
            PlannedOperatorAction("inspect", "Inspect screen", OperatorStep(OperatorActionType.BACK), dependsOn = setOf("open"))
        ))
        assertTrue(executed.isEmpty())
        val completed = bridge.start()
        assertEquals(listOf("Settings", ""), executed)
        assertEquals(WorkflowStatus.COMPLETED, completed.workflow.status)
        assertEquals(2, completed.audit.count { it.event == "ACTION_VERIFIED" })
    }

    @Test fun sensitiveActionWaitsForApprovalAndDeclineNeverExecutes() {
        val executed = mutableListOf<String>()
        val bridge = TaskPlannerOperatorBridge(executeVerified = { step, _ -> executed += step.target; true })
        bridge.createPlan("bridge-2", "Sensitive action", listOf(
            PlannedOperatorAction("send", "Send message", OperatorStep(OperatorActionType.CLICK_TEXT, target = "Send"),
                risk = Risk.SENSITIVE, requiresApproval = true)
        ))
        val waiting = bridge.start()
        assertEquals(WorkflowStatus.WAITING_FOR_APPROVAL, waiting.workflow.status)
        assertTrue(executed.isEmpty())
        val declined = bridge.approveCurrentStep(false)
        assertEquals(WorkflowStatus.PAUSED, declined.workflow.status)
        assertTrue(executed.isEmpty())
    }

    @Test fun approvalRunsOnlyCurrentSensitiveStepAndExecutionFailureStopsPlan() {
        val executed = mutableListOf<String>()
        val bridge = TaskPlannerOperatorBridge(executeVerified = { step, confirmed ->
            executed += "${step.target}:$confirmed"
            false
        })
        bridge.createPlan("bridge-3", "Fail safely", listOf(
            PlannedOperatorAction("send", "Send", OperatorStep(OperatorActionType.CLICK_TEXT, target = "Send"),
                risk = Risk.SENSITIVE, requiresApproval = true)
        ))
        bridge.start()
        val failed = bridge.approveCurrentStep(true)
        assertEquals(listOf("Send:true"), executed)
        assertEquals(WorkflowStatus.FAILED, failed.workflow.status)
        assertTrue(failed.audit.any { it.event == "ACTION_FAILED" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateActionIdsAreRejectedBeforePlanning() {
        val bridge = TaskPlannerOperatorBridge(executeVerified = { _, _ -> true })
        bridge.createPlan("bridge-4", "Bad plan", listOf(
            PlannedOperatorAction("same", "First", OperatorStep(OperatorActionType.BACK)),
            PlannedOperatorAction("same", "Second", OperatorStep(OperatorActionType.HOME))
        ))
    }
}
