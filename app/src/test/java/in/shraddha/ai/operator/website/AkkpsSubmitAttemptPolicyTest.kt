package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsSubmitAttemptPolicyTest {
    @Test fun rejectsSecondSubmitWhileVerificationIsPending() {
        val values = listOf("Farmer Name" to "Ramesh")
        assertFalse(AkkpsSubmitAttemptPolicy.canStart(true, values))
    }

    @Test fun acceptsOneWellFormedAttempt() {
        assertTrue(AkkpsSubmitAttemptPolicy.canStart(false, listOf("Farmer Name" to "Ramesh")))
    }

    @Test fun rejectsEmptyOversizedOrMalformedPayloads() {
        assertFalse(AkkpsSubmitAttemptPolicy.canStart(false, emptyList()))
        assertFalse(AkkpsSubmitAttemptPolicy.canStart(false, (1..101).map { "f$it" to "v" }))
        assertFalse(AkkpsSubmitAttemptPolicy.canStart(false, listOf(" " to "v")))
        assertFalse(AkkpsSubmitAttemptPolicy.canStart(false, listOf("x".repeat(161) to "v")))
        assertFalse(AkkpsSubmitAttemptPolicy.canStart(false, listOf("key" to "v".repeat(1_001))))
    }
    @Test fun rejectsRetryUntilManualRecordReviewIsAcknowledged() {
        val values = listOf("Farmer Name" to "Ramesh")
        assertFalse(AkkpsSubmitAttemptPolicy.canStart(false, values, manualReviewRequired = true))
        assertTrue(AkkpsSubmitAttemptPolicy.canStart(false, values, manualReviewRequired = false))
    }

}
