package `in`.shraddha.ai.operator.learning

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowStepApprovalTest {
    private fun grant(now: Long = 1_000L) = WorkflowStepApproval.afterExplicitUserApproval(
        packageName = "com.example.shop",
        workflowName = "send invoice",
        index = 2,
        action = "CLICK",
        target = "Send",
        resolvedInput = "invoice-42",
        expectedText = "Sent",
        nowElapsedMs = now
    )

    private fun consume(token: WorkflowStepApproval, now: Long = 1_001L, target: String = "Send") =
        token.consume("com.example.shop", "send invoice", 2, "CLICK", target, "invoice-42", "Sent", now)

    @Test fun approvalIsBoundToExactStepAndResolvedInput() {
        val token = grant()
        assertFalse(token.consume("com.example.shop", "send invoice", 2, "CLICK", "Send", "invoice-99", "Sent", 1_001L))
        assertFalse(consume(token, target = "Delete"))
        assertTrue(consume(token))
    }

    @Test fun approvalCannotBeReplayed() {
        val token = grant()
        assertTrue(consume(token))
        assertFalse(consume(token, now = 1_002L))
    }

    @Test fun approvalExpiresAndCannotBeUsedBeforeIssueTime() {
        assertFalse(consume(grant(), now = 31_001L))
        assertFalse(consume(grant(), now = 999L))
    }

    @Test fun approvalCannotCrossPackageWorkflowOrStepIndex() {
        val token = grant()
        assertFalse(token.consume("com.other.app", "send invoice", 2, "CLICK", "Send", "invoice-42", "Sent", 1_001L))
        assertFalse(token.consume("com.example.shop", "delete invoice", 2, "CLICK", "Send", "invoice-42", "Sent", 1_001L))
        assertFalse(token.consume("com.example.shop", "send invoice", 3, "CLICK", "Send", "invoice-42", "Sent", 1_001L))
        assertTrue(consume(token))
    }
}
