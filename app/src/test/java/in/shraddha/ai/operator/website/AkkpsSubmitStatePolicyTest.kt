package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsSubmitStatePolicyTest {
    @Test fun interruptedVerificationRequiresManualRecordReview() {
        assertTrue(AkkpsSubmitStatePolicy.requiresManualReview(AkkpsSubmitStatePolicy.VERIFYING))
        assertTrue(AkkpsSubmitStatePolicy.requiresManualReview(AkkpsSubmitStatePolicy.REVIEW_REQUIRED))
    }

    @Test fun clearedOrUnknownStateDoesNotCreateAnUnnecessaryLock() {
        assertFalse(AkkpsSubmitStatePolicy.requiresManualReview(null))
        assertFalse(AkkpsSubmitStatePolicy.requiresManualReview(""))
        assertFalse(AkkpsSubmitStatePolicy.requiresManualReview("VERIFIED"))
        assertFalse(AkkpsSubmitStatePolicy.requiresManualReview("FAILED"))
    }
}
