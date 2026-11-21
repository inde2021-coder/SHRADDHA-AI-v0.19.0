package `in`.shraddha.ai.operator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorConfirmationTest {
    private val step = OperatorStep(
        action = OperatorActionType.CLICK_TEXT,
        target = "Send",
        value = "hello",
        expectedVisibleText = "Sent"
    )

    @Test fun approvalIsBoundToExactStep() {
        val grant = OperatorConfirmation.afterExplicitUserApproval(step, 1_000L)
        assertFalse(grant.consume(step.copy(target = "Delete"), 1_001L))
        assertTrue(grant.consume(step, 1_001L))
    }

    @Test fun approvalExpiresAfterThirtySeconds() {
        val grant = OperatorConfirmation.afterExplicitUserApproval(step, 1_000L)
        assertFalse(grant.consume(step, 31_001L))
    }

    @Test fun approvalCannotBeReplayed() {
        val grant = OperatorConfirmation.afterExplicitUserApproval(step, 1_000L)
        assertTrue(grant.consume(step, 1_001L))
        assertFalse(grant.consume(step, 1_002L))
    }

    @Test fun approvalCannotBeUsedBeforeIssueTime() {
        val grant = OperatorConfirmation.afterExplicitUserApproval(step, 1_000L)
        assertFalse(grant.consume(step, 999L))
    }
}
