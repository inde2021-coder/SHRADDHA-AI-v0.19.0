package `in`.shraddha.ai.operator.learning

import org.junit.Assert.assertEquals
import org.junit.Test

class InterruptedStepRecoveryTest {
    @Test fun visiblePostconditionMarksInterruptedStepVerified() {
        assertEquals(
            InterruptedStepRecovery.Decision.VERIFIED_COMPLETE,
            InterruptedStepRecovery.assess(
                expectedText = "Message sent",
                visibleText = listOf("Inbox", "Message sent successfully"),
                beforeScreenFingerprint = "before123",
                currentScreenFingerprint = "after456",
                expectedTextAbsentBefore = true
            )
        )
    }

    @Test fun missingPostconditionNeverAuthorizesAutomaticReplay() {
        assertEquals(
            InterruptedStepRecovery.Decision.AMBIGUOUS,
            InterruptedStepRecovery.assess(
                expectedText = "Message sent",
                visibleText = listOf("Compose", "Send"),
                beforeScreenFingerprint = "before123",
                currentScreenFingerprint = "after456",
                expectedTextAbsentBefore = true
            )
        )
    }

    @Test fun emptyPostconditionIsAlwaysAmbiguous() {
        assertEquals(
            InterruptedStepRecovery.Decision.AMBIGUOUS,
            InterruptedStepRecovery.assess("", listOf("Some screen changed"), "before123", "after456", true)
        )
    }

    @Test fun matchingIsCaseInsensitive() {
        assertEquals(
            InterruptedStepRecovery.Decision.VERIFIED_COMPLETE,
            InterruptedStepRecovery.assess("Order Confirmed", listOf("order confirmed"), "before123", "after456", true)
        )
    }
    @Test fun unchangedScreenCannotProveAnInterruptedActionCompleted() {
        assertEquals(
            InterruptedStepRecovery.Decision.AMBIGUOUS,
            InterruptedStepRecovery.assess(
                "Saved",
                listOf("Saved"),
                "sameFingerprint",
                "sameFingerprint",
                true
            )
        )
    }

    @Test fun postconditionAlreadyVisibleBeforeActionIsNotProof() {
        assertEquals(
            InterruptedStepRecovery.Decision.AMBIGUOUS,
            InterruptedStepRecovery.assess(
                "Saved",
                listOf("Saved"),
                "before123",
                "after456",
                false
            )
        )
    }

}
