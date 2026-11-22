package `in`.shraddha.ai.operator.learning

import org.junit.Assert.assertEquals
import org.junit.Test

class InterruptedStepRecoveryTest {
    @Test fun visiblePostconditionMarksInterruptedStepVerified() {
        assertEquals(
            InterruptedStepRecovery.Decision.VERIFIED_COMPLETE,
            InterruptedStepRecovery.assess(
                expectedText = "Message sent",
                visibleText = listOf("Inbox", "Message sent successfully")
            )
        )
    }

    @Test fun missingPostconditionNeverAuthorizesAutomaticReplay() {
        assertEquals(
            InterruptedStepRecovery.Decision.AMBIGUOUS,
            InterruptedStepRecovery.assess(
                expectedText = "Message sent",
                visibleText = listOf("Compose", "Send")
            )
        )
    }

    @Test fun emptyPostconditionIsAlwaysAmbiguous() {
        assertEquals(
            InterruptedStepRecovery.Decision.AMBIGUOUS,
            InterruptedStepRecovery.assess("", listOf("Some screen changed"))
        )
    }

    @Test fun matchingIsCaseInsensitive() {
        assertEquals(
            InterruptedStepRecovery.Decision.VERIFIED_COMPLETE,
            InterruptedStepRecovery.assess("Order Confirmed", listOf("order confirmed"))
        )
    }
}
