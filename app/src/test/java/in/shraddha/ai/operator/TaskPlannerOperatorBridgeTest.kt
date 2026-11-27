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

    @Test fun destructiveIntentIsAutomaticallyGatedEvenWhenPlannerMarksItReadOnly() {
        val executed = mutableListOf<String>()
        val bridge = TaskPlannerOperatorBridge(executeVerified = { step, confirmed ->
            executed += "${step.target}:$confirmed"
            true
        })
        val planned = bridge.createPlan("bridge-auto-risk", "Send safely", listOf(
            PlannedOperatorAction(
                "send", "Send message",
                OperatorStep(OperatorActionType.CLICK_TEXT, target = "Send"),
                risk = Risk.READ_ONLY,
                requiresApproval = false
            )
        ))
        assertEquals(WorkflowStatus.WAITING_FOR_APPROVAL, bridge.start().workflow.status)
        assertTrue(executed.isEmpty())
        val completed = bridge.approveCurrentStep(true)
        assertEquals(listOf("Send:true"), executed)
        assertEquals(WorkflowStatus.COMPLETED, completed.workflow.status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun secretEntryIsRejectedBySafetyPolicyBeforeExecution() {
        val bridge = TaskPlannerOperatorBridge(executeVerified = { _, _ -> fail("Blocked action must never execute"); false })
        bridge.createPlan("bridge-block-secret", "Never type OTP", listOf(
            PlannedOperatorAction(
                "otp", "Enter OTP",
                OperatorStep(OperatorActionType.TYPE_TEXT, target = "OTP", value = "user-supplied-code")
            )
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateActionIdsAreRejectedBeforePlanning() {
        val bridge = TaskPlannerOperatorBridge(executeVerified = { _, _ -> true })
        bridge.createPlan("bridge-4", "Bad plan", listOf(
            PlannedOperatorAction("same", "First", OperatorStep(OperatorActionType.BACK)),
            PlannedOperatorAction("same", "Second", OperatorStep(OperatorActionType.HOME))
        ))
    }
    @Test fun actionIsNeverDispatchedWhenWriteAheadJournalCannotCommit() {
        var executions = 0
        val bridge = TaskPlannerOperatorBridge(
            executeVerified = { _, _ -> executions++; true },
            beforeExecute = { _, _ -> false }
        )
        bridge.createPlan("journal-gate", "Journal gate", listOf(
            PlannedOperatorAction("back", "Go back", OperatorStep(OperatorActionType.BACK))
        ))
        val failed = bridge.start()
        assertEquals(0, executions)
        assertEquals(WorkflowStatus.FAILED, failed.workflow.status)
        assertTrue(failed.workflow.message.contains("journal", ignoreCase = true))
    }

    @Test fun workflowStopsWhenVerifiedResultCannotBePersisted() {
        var executions = 0
        val bridge = TaskPlannerOperatorBridge(
            executeVerified = { _, _ -> executions++; true },
            beforeExecute = { _, _ -> true },
            afterExecute = { _, _, verified -> verified && false }
        )
        bridge.createPlan("journal-commit", "Journal commit", listOf(
            PlannedOperatorAction("back", "Go back", OperatorStep(OperatorActionType.BACK))
        ))
        val failed = bridge.start()
        assertEquals(1, executions)
        assertEquals(WorkflowStatus.FAILED, failed.workflow.status)
        assertTrue(failed.workflow.message.contains("journal", ignoreCase = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingDependencyIsRejectedBeforePlanCreation() {
        val bridge = TaskPlannerOperatorBridge(executeVerified = { _, _ -> fail("Invalid plan must never execute"); false })
        bridge.createPlan("missing-dependency", "Invalid dependency", listOf(
            PlannedOperatorAction("child", "Child action", OperatorStep(OperatorActionType.BACK), dependsOn = setOf("does-not-exist"))
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun selfDependencyIsRejectedBeforePlanCreation() {
        val bridge = TaskPlannerOperatorBridge(executeVerified = { _, _ -> fail("Invalid plan must never execute"); false })
        bridge.createPlan("self-dependency", "Self dependency", listOf(
            PlannedOperatorAction("loop", "Loop action", OperatorStep(OperatorActionType.BACK), dependsOn = setOf("loop"))
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun cyclicDependenciesAreRejectedBeforePlanCreation() {
        val bridge = TaskPlannerOperatorBridge(executeVerified = { _, _ -> fail("Invalid plan must never execute"); false })
        bridge.createPlan("cyclic-plan", "Cyclic dependency", listOf(
            PlannedOperatorAction("first", "First action", OperatorStep(OperatorActionType.BACK), dependsOn = setOf("second")),
            PlannedOperatorAction("second", "Second action", OperatorStep(OperatorActionType.HOME), dependsOn = setOf("first"))
        ))
    }

    @Test fun validMultiDependencyGraphStillExecutesInDependencyOrder() {
        val executed = mutableListOf<String>()
        val bridge = TaskPlannerOperatorBridge(executeVerified = { step, _ ->
            executed += step.target
            true
        })
        bridge.createPlan("multi-dependency", "Valid graph", listOf(
            PlannedOperatorAction("a", "A", OperatorStep(OperatorActionType.OPEN_APP, target = "A")),
            PlannedOperatorAction("b", "B", OperatorStep(OperatorActionType.OPEN_APP, target = "B")),
            PlannedOperatorAction("c", "C", OperatorStep(OperatorActionType.BACK), dependsOn = setOf("a", "b"))
        ))
        val result = bridge.start()
        assertEquals(listOf("A", "B", ""), executed)
        assertEquals(WorkflowStatus.COMPLETED, result.workflow.status)
    }


    @Test fun rejectedReplacementPlanDoesNotEraseCurrentWorkflowAdapters() {
        val executed = mutableListOf<String>()
        val bridge = TaskPlannerOperatorBridge(executeVerified = { step, _ ->
            executed += step.target
            true
        })
        bridge.createPlan("atomic-1", "Existing valid plan", listOf(
            PlannedOperatorAction("back", "Go back", OperatorStep(OperatorActionType.BACK, target = "original"))
        ))
        try {
            bridge.createPlan("atomic-2", "Rejected secret plan", listOf(
                PlannedOperatorAction("secret", "Enter OTP",
                    OperatorStep(OperatorActionType.TYPE_TEXT, target = "OTP", value = "123456"))
            ))
            fail("Unsafe replacement plan should be rejected")
        } catch (_: IllegalArgumentException) { }

        val completed = bridge.start()
        assertEquals(listOf("original"), executed)
        assertEquals(WorkflowStatus.COMPLETED, completed.workflow.status)
    }

    @Test fun hubPersistsVerifyingStateBeforeExecutorCanCauseSideEffect() {
        lateinit var bridge: TaskPlannerOperatorBridge
        var stateAtDispatch: WorkflowStatus? = null
        bridge = TaskPlannerOperatorBridge(executeVerified = { _, _ ->
            stateAtDispatch = bridge.current()?.workflow?.status
            true
        })
        bridge.createPlan("dispatch-state", "Record dispatch first", listOf(
            PlannedOperatorAction("back", "Go back", OperatorStep(OperatorActionType.BACK))
        ))
        val result = bridge.start()
        assertEquals(WorkflowStatus.VERIFYING, stateAtDispatch)
        assertEquals(WorkflowStatus.COMPLETED, result.workflow.status)
        assertTrue(result.audit.any { it.event == "ACTION_DISPATCHED" })
    }

    @Test fun stepLevelConfirmationCannotBeDowngradedByPlannerRisk() {
            val executed = mutableListOf<String>()
            val bridge = TaskPlannerOperatorBridge(executeVerified = { step, confirmed ->
                executed += "${step.target}:$confirmed"
                true
            })
            bridge.createPlan("step-confirmation", "Confirm step", listOf(
                PlannedOperatorAction(
                    "click", "Click protected control",
                    OperatorStep(OperatorActionType.CLICK_TEXT, target = "Continue", requiresConfirmation = true),
                    risk = Risk.READ_ONLY,
                    requiresApproval = false
                )
            ))
            val waiting = bridge.start()
            assertEquals(WorkflowStatus.WAITING_FOR_APPROVAL, waiting.workflow.status)
            assertTrue(executed.isEmpty())
            val completed = bridge.approveCurrentStep(true)
            assertEquals(listOf("Continue:true"), executed)
            assertEquals(WorkflowStatus.COMPLETED, completed.workflow.status)
        }
    
        @Test(expected = IllegalArgumentException::class)
        fun clickWithoutExplicitTargetIsRejected() {
            val bridge = TaskPlannerOperatorBridge(executeVerified = { _, _ -> fail("Invalid action must not execute"); false })
            bridge.createPlan("missing-target", "Missing target", listOf(
                PlannedOperatorAction("click", "Click", OperatorStep(OperatorActionType.CLICK_TEXT))
            ))
        }
    
        @Test(expected = IllegalArgumentException::class)
        fun typeWithoutValueIsRejected() {
            val bridge = TaskPlannerOperatorBridge(executeVerified = { _, _ -> fail("Invalid action must not execute"); false })
            bridge.createPlan("missing-value", "Missing value", listOf(
                PlannedOperatorAction("type", "Type", OperatorStep(OperatorActionType.TYPE_TEXT, target = "Name"))
            ))
        }
    
        @Test(expected = IllegalArgumentException::class)
        fun oversizedOperatorInputIsRejected() {
            val bridge = TaskPlannerOperatorBridge(executeVerified = { _, _ -> fail("Invalid action must not execute"); false })
            bridge.createPlan("oversized-input", "Oversized input", listOf(
                PlannedOperatorAction("type", "Type", OperatorStep(
                    OperatorActionType.TYPE_TEXT, target = "Name", value = "x".repeat(1_001)
                ))
            ))
        }
    
        @Test(expected = IllegalArgumentException::class)
        fun missingDependencyIsRejectedBeforeHubRegistration() {
            val bridge = TaskPlannerOperatorBridge(executeVerified = { _, _ -> fail("Invalid plan must not execute"); false })
            bridge.createPlan("missing-dependency", "Missing dependency", listOf(
                PlannedOperatorAction("step-a", "Step A", OperatorStep(OperatorActionType.BACK),
                    dependsOn = setOf("does-not-exist"))
            ))
        }
    
        @Test(expected = IllegalArgumentException::class)
        fun selfDependencyIsRejectedBeforeHubRegistration() {
            val bridge = TaskPlannerOperatorBridge(executeVerified = { _, _ -> fail("Invalid plan must not execute"); false })
            bridge.createPlan("self-dependency", "Self dependency", listOf(
                PlannedOperatorAction("step-a", "Step A", OperatorStep(OperatorActionType.BACK),
                    dependsOn = setOf("step-a"))
            ))
        }
    
        @Test(expected = IllegalArgumentException::class)
        fun dependencyCycleIsRejectedBeforeHubRegistration() {
            val bridge = TaskPlannerOperatorBridge(executeVerified = { _, _ -> fail("Invalid plan must not execute"); false })
            bridge.createPlan("cycle", "Dependency cycle", listOf(
                PlannedOperatorAction("step-a", "Step A", OperatorStep(OperatorActionType.BACK),
                    dependsOn = setOf("step-b")),
                PlannedOperatorAction("step-b", "Step B", OperatorStep(OperatorActionType.HOME),
                    dependsOn = setOf("step-a"))
            ))
        }
}
