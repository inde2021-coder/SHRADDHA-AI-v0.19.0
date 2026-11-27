package `in`.shraddha.ai.operator.learning

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowCheckpointPolicyTest {
    private fun checkpoint(
        packageName: String = "com.example.target",
        workflowName: String = "Submit form",
        workflowHash: String = "a".repeat(64),
        nextStep: Int = 1,
        totalSteps: Int = 3,
        state: String = "READY",
        expectedText: String = "",
        beforeScreenFingerprint: String = "",
        updatedAt: Long = 1L
    ) = WorkflowCheckpoint(
        packageName, workflowName, workflowHash, nextStep, totalSteps, state,
        expectedText, beforeScreenFingerprint, false, updatedAt
    )

    @Test fun acceptsWellFormedCheckpoint() {
        assertTrue(WorkflowCheckpointPolicy.isValid(checkpoint()))
    }

    @Test fun rejectsMalformedPackageAndWorkflowNames() {
        assertFalse(WorkflowCheckpointPolicy.isValid(checkpoint(packageName = "not a package")))
        assertFalse(WorkflowCheckpointPolicy.isValid(checkpoint(workflowName = " ")))
        assertFalse(WorkflowCheckpointPolicy.isValid(checkpoint(workflowName = "x".repeat(121))))
    }

    @Test fun rejectsMalformedWorkflowHash() {
        assertFalse(WorkflowCheckpointPolicy.isValid(checkpoint(workflowHash = "short")))
    }

    @Test fun rejectsOutOfRangeStepIndexesAndCounts() {
        assertFalse(WorkflowCheckpointPolicy.isValid(checkpoint(nextStep = 4)))
        assertFalse(WorkflowCheckpointPolicy.isValid(checkpoint(nextStep = -1)))
        assertFalse(WorkflowCheckpointPolicy.isValid(checkpoint(totalSteps = 101)))
    }

    @Test fun rejectsUnknownStatesAndOversizedRecoveryText() {
        assertFalse(WorkflowCheckpointPolicy.isValid(checkpoint(state = "DONE")))
        assertFalse(WorkflowCheckpointPolicy.isValid(checkpoint(expectedText = "x".repeat(301))))
        assertFalse(WorkflowCheckpointPolicy.isValid(checkpoint(beforeScreenFingerprint = "x".repeat(129))))
    }

    @Test fun rejectsInvalidTimestamp() {
        assertFalse(WorkflowCheckpointPolicy.isValid(checkpoint(updatedAt = 0L)))
    }
}
